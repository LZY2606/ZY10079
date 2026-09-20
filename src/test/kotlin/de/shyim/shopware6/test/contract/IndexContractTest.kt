package de.shyim.shopware6.test.contract

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import java.util.Properties

/**
 * Contract test for every FileBasedIndex registered in plugin.xml.
 *
 * For each index listed in src/test/resources/index-contract/manifest.txt this test:
 *  - fails when the fixture directory src/test/testData/contract/<SimpleClassName>/ is missing or empty
 *    (fixtures are never silently skipped),
 *  - copies the fixtures into the test project and requires at least one key with values to be collected,
 *  - round-trips every value through the index value externalizer (save -> read -> save) and requires
 *    stable bytes and, for value types implementing equals, equal objects,
 *  - compares the index version and the SHA-256 of the serialized representation with
 *    src/test/resources/index-contract/snapshots.properties. A version bump or externalizer byte change
 *    must be reflected there deliberately (see doc/index-versioning.md); regenerate with
 *    ./gradlew test -Pindex.contract.updateSnapshots=true
 */
class IndexContractTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData/contract/"

    fun testAllRegisteredIndexesCollectFixturesAndRoundTrip() {
        val entries = IndexContractManifest.load()
        assertTrue("index-contract manifest is empty", entries.isNotEmpty())

        val extensionsByClass = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<FileBasedIndexExtension<*, *>>()
            .associateBy { it.javaClass.name }

        entries.forEach { fqn ->
            val simpleName = fqn.substringAfterLast('.')
            val fixtureDir = File(testDataPath, simpleName)
            assertTrue(
                "Missing contract fixture directory for $fqn: $fixtureDir. " +
                    "Add at least one fixture file; missing testData must not be skipped.",
                fixtureDir.isDirectory
            )
            assertTrue(
                "Contract fixture directory for $fqn contains no files: $fixtureDir",
                fixtureDir.walkTopDown().any { it.isFile }
            )
            myFixture.copyDirectoryToProject(simpleName, "contract/$simpleName")
        }

        val actualSnapshots = LinkedHashMap<String, String>()

        entries.forEach { fqn ->
            val extension = extensionsByClass[fqn]
            assertNotNull("Index $fqn is listed in the contract manifest but is not registered at runtime", extension)
            val ext = extension!!

            val keysWithValues = keysWithValues(ext)
            assertTrue(
                "Index $fqn collected no values from its contract fixtures in ${testDataPath}${fqn.substringAfterLast('.')}/",
                keysWithValues.isNotEmpty()
            )

            val externalizer = ext.valueExternalizer as DataExternalizer<Any?>
            val digest = MessageDigest.getInstance("SHA-256")

            keysWithValues.sortedBy { it.toString() }.forEach { key ->
                values(ext, key).forEach { value ->
                    val bytes = serialize(externalizer, value)
                    val restored = deserialize(externalizer, bytes)
                    val reserialized = serialize(externalizer, restored)

                    assertArrayEquals(
                        "Externalizer round-trip of $fqn value for key $key is not byte-stable",
                        bytes,
                        reserialized
                    )

                    if (value != null && restored != null && overridesEquals(value)) {
                        assertEquals("Externalizer round-trip of $fqn changed the value for key $key", value, restored)
                    }

                    digest.update(key.toString().toByteArray(Charsets.UTF_8))
                    digest.update(bytes)
                }
            }

            actualSnapshots[fqn] = "${ext.version}:${digest.toHex()}"
        }

        verifySnapshots(actualSnapshots)
    }

    private fun keysWithValues(extension: FileBasedIndexExtension<*, *>): List<Any> {
        val id = extension.name as ID<Any, Any>
        val index = FileBasedIndex.getInstance()
        // getAllKeys may contain stale keys from other tests sharing the index storage,
        // so only keys with values in this project count
        return index.getAllKeys(id, project)
            .filter { index.getValues(id, it, GlobalSearchScope.allScope(project)).isNotEmpty() }
    }

    private fun values(extension: FileBasedIndexExtension<*, *>, key: Any): List<Any?> {
        val id = extension.name as ID<Any, Any>
        return FileBasedIndex.getInstance()
            .getValues(id, key, GlobalSearchScope.allScope(project))
    }

    private fun serialize(externalizer: DataExternalizer<Any?>, value: Any?): ByteArray {
        val buffer = ByteArrayOutputStream()
        externalizer.save(DataOutputStream(buffer), value)
        return buffer.toByteArray()
    }

    private fun deserialize(externalizer: DataExternalizer<Any?>, bytes: ByteArray): Any? =
        externalizer.read(DataInputStream(ByteArrayInputStream(bytes)))

    private fun overridesEquals(value: Any): Boolean =
        value.javaClass.getMethod("equals", Any::class.java).declaringClass != Any::class.java

    private fun verifySnapshots(actual: Map<String, String>) {
        if (System.getProperty(UPDATE_SNAPSHOTS_PROPERTY) == "true") {
            writeSnapshots(actual)
            return
        }

        val expected = Properties()
        val resource = javaClass.getResourceAsStream("/index-contract/snapshots.properties")
        assertNotNull(
            "Missing test resource /index-contract/snapshots.properties. Generate it with ./gradlew test -Pindex.contract.updateSnapshots=true",
            resource
        )
        resource!!.use { expected.load(it) }

        val expectedMap = expected.stringPropertyNames().associateWith { expected.getProperty(it) }

        val missing = actual.keys - expectedMap.keys
        val stale = expectedMap.keys - actual.keys
        val changed = actual.keys.intersect(expectedMap.keys)
            .filter { actual[it] != expectedMap[it] }

        val problems = mutableListOf<String>()
        missing.forEach { problems += "$it has no snapshot entry" }
        stale.forEach { problems += "$it has a stale snapshot entry but is not in the manifest" }
        changed.forEach {
            problems += "$it changed from ${expectedMap[it]} to ${actual[it]}. " +
                "Index version bumps and externalizer byte changes must be documented in doc/index-versioning.md"
        }

        assertTrue(
            "Index contract snapshots are out of date (see doc/index-versioning.md):\n - " +
                problems.joinToString("\n - ") +
                "\nRegenerate with: ./gradlew test -Pindex.contract.updateSnapshots=true",
            problems.isEmpty()
        )
    }

    private fun writeSnapshots(actual: Map<String, String>) {
        val file = File("src/test/resources/index-contract/snapshots.properties")
        val content = buildString {
            appendLine("# Snapshot of FileBasedIndex versions and serialized-value hashes.")
            appendLine("# Format: <index class> = <index version>:<sha256 of keys + externalizer bytes>")
            appendLine("# Regenerate with: ./gradlew test -Pindex.contract.updateSnapshots=true")
            appendLine("# Every change must be justified in doc/index-versioning.md.")
            actual.toSortedMap().forEach { (fqn, snapshot) -> appendLine("$fqn=$snapshot") }
        }
        file.writeText(content)
        println("[IndexContractTest] Updated ${file.path} with ${actual.size} entries")
    }

    private fun MessageDigest.toHex(): String =
        digest().joinToString("") { "%02x".format(it) }

    companion object {
        const val UPDATE_SNAPSHOTS_PROPERTY = "index.contract.updateSnapshots"
    }
}
