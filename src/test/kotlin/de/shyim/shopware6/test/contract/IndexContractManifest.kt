package de.shyim.shopware6.test.contract

/**
 * Loads the index contract manifest (src/test/resources/index-contract/manifest.txt).
 * The manifest is the single source of truth shared by the contract test and the
 * verifyIndexContractManifest Gradle task.
 */
object IndexContractManifest {
    const val RESOURCE = "/index-contract/manifest.txt"

    fun load(): List<String> {
        val stream = IndexContractManifest::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing test resource $RESOURCE")
        return stream.bufferedReader().use { reader ->
            reader.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }
}
