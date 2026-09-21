package de.shyim.shopware6.test.contract

import junit.framework.TestCase
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Derives the set of indexes that must be covered from two sources of truth:
 *  - the `fileBasedIndex` registrations in plugin.xml
 *  - the `FileBasedIndexExtension` subclasses in the plugin sources
 *
 * and compares it with the test manifest (index-manifest.json). Any index that is registered or
 * implemented but missing from the manifest fails the build, and any manifest entry without a
 * matching implementation fails as well. Nothing is skipped silently.
 */
class IndexCoverageScannerTest : TestCase() {

    fun testManifestCoversExactlyTheRegisteredAndImplementedIndexes() {
        val registered = registeredIndexClasses()
        val implemented = implementedIndexClasses()

        val unregistered = implemented - registered
        assertTrue(
            "Index classes found in sources but not registered in plugin.xml: $unregistered",
            unregistered.isEmpty()
        )

        val expected = (registered + implemented).toSortedSet()
        val manifestEntries = IndexContractManifest.load()
        val manifestClasses = manifestEntries.map { it.className }.toSet()

        val duplicates = manifestEntries.groupingBy { it.className }.eachCount().filter { it.value > 1 }
        assertTrue("Duplicate manifest entries: ${duplicates.keys}", duplicates.isEmpty())

        val missing = expected - manifestClasses
        val unknown = manifestClasses - expected
        assertTrue(
            "Index contract manifest is out of sync.\n" +
                "  Missing entries (add fixtures + snapshot): $missing\n" +
                "  Unknown entries (remove or restore implementation): $unknown",
            missing.isEmpty() && unknown.isEmpty()
        )
    }

    fun testEveryManifestEntryDeclaresExistingNonEmptyFixtures() {
        val failures = ArrayList<String>()

        IndexContractManifest.load().forEach { entry ->
            if (entry.fixtures.isEmpty()) {
                failures.add("${entry.className}: no fixtures declared")
            }

            entry.fixtures.forEach { fixture ->
                val file = IndexContractManifest.testDataRoot.resolve(fixture)
                when {
                    !file.isFile -> failures.add("${entry.className}: missing testData fixture $fixture")
                    file.length() == 0L -> failures.add("${entry.className}: empty testData fixture $fixture")
                }
            }
        }

        assertTrue(
            "Index contract fixtures are incomplete (missing testData is never skipped):\n  " +
                failures.joinToString("\n  "),
            failures.isEmpty()
        )
    }

    companion object {
        private val pluginXml = File("src/main/resources/META-INF/plugin.xml")
        private val indexSources = File("src/main/kotlin/de/shyim/shopware6/index")

        fun registeredIndexClasses(): Set<String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pluginXml)
            val nodes = document.getElementsByTagName("fileBasedIndex")
            val classes = sortedSetOf<String>()

            for (i in 0 until nodes.length) {
                val implementation = nodes.item(i).attributes.getNamedItem("implementation")?.nodeValue
                if (!implementation.isNullOrBlank()) {
                    classes.add(implementation)
                }
            }

            return classes
        }

        fun implementedIndexClasses(): Set<String> {
            val classes = sortedSetOf<String>()
            val packagePattern = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
            val classPattern = Regex("class\\s+(\\w+)[^:\\n]*:\\s*(?:[\\w.]+\\.)?FileBasedIndexExtension")

            indexSources.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    val pkg = packagePattern.find(text)?.groupValues?.get(1) ?: return@forEach
                    classPattern.findAll(text).forEach { match ->
                        classes.add("$pkg.${match.groupValues[1]}")
                    }
                }

            return classes
        }
    }
}
