package de.shyim.shopware6.test.contract

import org.codehaus.jettison.json.JSONObject
import java.io.File

/**
 * One entry of the index contract manifest. Every FileBasedIndex of the plugin must have exactly
 * one entry here; the scanner test (IndexCoverageScannerTest) fails on any difference in either
 * direction.
 *
 * @param id stable index ID (ID.create name) - changing it breaks stored indexes of users
 * @param indexVersion FileBasedIndexExtension.getVersion() snapshot
 * @param externalizerSha256 fingerprint of the value externalizer class bytes plus the dict value
 *        class bytes. Any change means serialized index values changed and needs either an index
 *        version bump or a compatibility note below.
 * @param fixtures testData files (relative to src/test/testData) copied into the fixture project;
 *        at least one key must be collected from them. Missing files fail the build, never skip.
 * @param compatibilityNote free text explaining why a version/externalizer change is compatible
 */
data class IndexContractEntry(
    val className: String,
    val id: String,
    val indexVersion: Int,
    val externalizerSha256: String,
    val fixtures: List<String>,
    val compatibilityNote: String,
)

object IndexContractManifest {
    const val UPDATE_PROPERTY = "shopware.contract.updateSnapshot"

    val manifestFile: File = File("src/test/testData/index-contract/index-manifest.json")
    val testDataRoot: File = File("src/test/testData")

    fun isUpdateMode(): Boolean = System.getProperty(UPDATE_PROPERTY) == "true"

    fun load(): List<IndexContractEntry> {
        assert(manifestFile.isFile) { "Index contract manifest not found: ${manifestFile.absolutePath}" }

        val root = JSONObject(manifestFile.readText())
        val indexes = root.getJSONArray("indexes")
        val entries = ArrayList<IndexContractEntry>(indexes.length())

        for (i in 0 until indexes.length()) {
            val entry = indexes.getJSONObject(i)
            val fixtures = entry.getJSONArray("fixtures")
            entries.add(
                IndexContractEntry(
                    className = entry.getString("className"),
                    id = entry.getString("id"),
                    indexVersion = entry.getInt("indexVersion"),
                    externalizerSha256 = entry.getString("externalizerSha256"),
                    fixtures = (0 until fixtures.length()).map { fixtures.getString(it) },
                    compatibilityNote = entry.optString("compatibilityNote", ""),
                )
            )
        }

        return entries
    }

    fun save(entries: List<IndexContractEntry>) {
        val out = StringBuilder()
        out.append("{\n")
        out.append("    \"schemaVersion\": 1,\n")
        out.append("    \"indexes\": [\n")

        entries.sortedBy { it.className }.forEachIndexed { index, entry ->
            out.append("        {\n")
            out.append("            \"className\": ${quote(entry.className)},\n")
            out.append("            \"id\": ${quote(entry.id)},\n")
            out.append("            \"indexVersion\": ${entry.indexVersion},\n")
            out.append("            \"externalizerSha256\": ${quote(entry.externalizerSha256)},\n")
            out.append("            \"fixtures\": ${quoteArray(entry.fixtures)},\n")
            out.append("            \"compatibilityNote\": ${quote(entry.compatibilityNote)}\n")
            out.append("        }")
            out.append(if (index == entries.size - 1) "\n" else ",\n")
        }

        out.append("    ]\n")
        out.append("}\n")

        manifestFile.writeText(out.toString())
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun quoteArray(values: List<String>): String =
        values.joinToString(", ", "[", "]") { quote(it) }
}
