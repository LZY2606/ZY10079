package de.shyim.shopware6.test.contract

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.io.DataExternalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Contract test for every FileBasedIndex of the plugin, driven by index-manifest.json:
 *
 *  1. every manifest fixture is copied into the fixture project (missing testData fails setUp,
 *     nothing is skipped) and must produce at least one index key
 *  2. the first collected value survives a full externalizer round trip (save -> read -> save)
 *     with identical bytes and a non-null restored value
 *  3. the index ID, index version and a fingerprint of the externalizer/dict class bytes are
 *     compared against the manifest snapshot; changes require a deliberate snapshot update
 *     (`./gradlew test -Pshopware.contract.updateSnapshot=true`) plus a compatibility note
 */
class IndexContractTest : BasePlatformTestCase() {

    private lateinit var entries: List<IndexContractEntry>

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        entries = IndexContractManifest.load()

        entries.flatMap { it.fixtures }.distinct().forEach { fixture ->
            assertTrue(
                "Missing testData fixture '$fixture' required by the index contract manifest. " +
                    "Missing testData is never skipped - restore the file or fix the manifest.",
                IndexContractManifest.testDataRoot.resolve(fixture).isFile
            )
            myFixture.copyFileToProject(fixture, fixture)
        }
    }

    fun testIndexIdsAndVersionsMatchSnapshot() {
        val mismatches = ArrayList<String>()
        val updated = ArrayList<IndexContractEntry>()

        entries.forEach { entry ->
            val index = instantiate(entry)

            val actualId = index.name.name
            val actualVersion = index.version

            if (IndexContractManifest.isUpdateMode()) {
                updated.add(entry.copy(id = actualId, indexVersion = actualVersion))
                return@forEach
            }

            if (actualId != entry.id) {
                mismatches.add("${entry.className}: index ID changed '${entry.id}' -> '$actualId'")
            }
            if (actualVersion != entry.indexVersion) {
                mismatches.add(
                    "${entry.className}: index version changed ${entry.indexVersion} -> $actualVersion. " +
                        "Bump the snapshot with -Pshopware.contract.updateSnapshot=true and document " +
                        "compatibility in the manifest compatibilityNote."
                )
            }

            updated.add(entry)
        }

        if (IndexContractManifest.isUpdateMode()) {
            IndexContractManifest.save(updated)
            return
        }

        assertTrue("Index ID/version snapshot mismatch:\n  " + mismatches.joinToString("\n  "), mismatches.isEmpty())
    }

    fun testEveryIndexCollectsFixtureAndExternalizerRoundTrips() {
        val failures = ArrayList<String>()
        val updated = ArrayList<IndexContractEntry>()

        entries.forEach { entry ->
            val index = instantiate(entry)

            val keys = FileBasedIndex.getInstance().getAllKeys(index.name, project)
            if (keys.isEmpty()) {
                failures.add(
                    "${entry.className}: no keys collected from fixtures ${entry.fixtures}. " +
                        "The index must collect at least one fixture - check the testData or the indexer."
                )
                updated.add(entry)
                return@forEach
            }

            // key enumerators are append-only: keys of deleted files (e.g. from other test
            // projects sharing the persistent sandbox index) may still show up with no values,
            // so pick the first key that actually has values in this project
            val values = keys.asSequence()
                .map { key ->
                    FileBasedIndex.getInstance().getValues(index.name, key, GlobalSearchScope.allScope(project))
                }
                .firstOrNull { it.isNotEmpty() }

            if (values == null) {
                failures.add(
                    "${entry.className}: collected keys $keys but none has values in this project."
                )
                updated.add(entry)
                return@forEach
            }

            val value = values.first()

            val externalizer = index.valueExternalizer
            val serialized = serialize(externalizer, value)
            val restored = deserialize(externalizer, serialized)

            if (value != null && restored == null) {
                failures.add(
                    "${entry.className}: externalizer ${externalizer.javaClass.name} returned null when " +
                        "reading back a serialized ${value.javaClass.name} - the externalizer is not " +
                        "compatible with the current dict class."
                )
                updated.add(entry)
                return@forEach
            }

            val reserialized = serialize(externalizer, restored)
            if (!serialized.contentEquals(reserialized)) {
                failures.add(
                    "${entry.className}: externalizer round trip is not stable " +
                        "(${serialized.size} bytes -> ${reserialized.size} bytes)."
                )
                updated.add(entry)
                return@forEach
            }

            val fingerprint = externalizerFingerprint(externalizer, value)
            if (IndexContractManifest.isUpdateMode()) {
                updated.add(entry.copy(externalizerSha256 = fingerprint))
                return@forEach
            }

            if (fingerprint != entry.externalizerSha256) {
                failures.add(
                    "${entry.className}: externalizer/dict byte fingerprint changed " +
                        "${entry.externalizerSha256} -> $fingerprint. Serialized index values changed; " +
                        "bump the index version or add a compatibilityNote, then refresh the snapshot " +
                        "with -Pshopware.contract.updateSnapshot=true."
                )
            }

            updated.add(entry)
        }

        if (IndexContractManifest.isUpdateMode()) {
            IndexContractManifest.save(updated)
        }

        assertTrue("Index externalizer contract violations:\n  " + failures.joinToString("\n  "), failures.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun instantiate(entry: IndexContractEntry): FileBasedIndexExtension<Any, Any?> {
        return Class.forName(entry.className).getDeclaredConstructor().newInstance()
            as FileBasedIndexExtension<Any, Any?>
    }

    private fun serialize(externalizer: DataExternalizer<Any?>, value: Any?): ByteArray {
        val buffer = ByteArrayOutputStream()
        externalizer.save(DataOutputStream(buffer), value)
        return buffer.toByteArray()
    }

    private fun deserialize(externalizer: DataExternalizer<Any?>, bytes: ByteArray): Any? {
        return externalizer.read(DataInputStream(ByteArrayInputStream(bytes)))
    }

    private fun externalizerFingerprint(externalizer: DataExternalizer<Any?>, value: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(classBytes(externalizer.javaClass))

        if (value != null && value.javaClass.name.startsWith("de.shyim.shopware6.")) {
            digest.update(classBytes(value.javaClass))
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun classBytes(clazz: Class<*>): ByteArray {
        val resource = clazz.name.replace('.', '/') + ".class"
        val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
        return loader.getResourceAsStream(resource)?.readBytes()
            ?: throw AssertionError("Cannot locate class bytes for ${clazz.name}")
    }
}
