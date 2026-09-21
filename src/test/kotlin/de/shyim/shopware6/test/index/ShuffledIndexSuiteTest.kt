package de.shyim.shopware6.test.index

import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite
import java.io.File
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Random

/**
 * Second pass over all index tests (de.shyim.shopware6.test.index + .contract) in a random,
 * seeded order. Reproduce a failing order with -Pshopware.test.seed=<seed>.
 */
class ShuffledIndexSuiteTest : TestCase() {

    fun testSuiteIsNotEmpty() {
        assertTrue(discoverIndexTestClasses().isNotEmpty())
    }

    companion object {
        private val PACKAGES = listOf(
            "de.shyim.shopware6.test.index",
            "de.shyim.shopware6.test.contract",
        )

        @JvmStatic
        fun suite(): Test {
            if (System.getProperty("shopware.contract.shuffled") != "true") {
                // outside testIndexShuffled (e.g. the regular test task) run only this
                // class's own tests; the shuffled second pass is opt-in per JVM
                return TestSuite(ShuffledIndexSuiteTest::class.java)
            }

            val seed = System.getProperty("shopware.test.seed")?.toLongOrNull() ?: Random().nextLong()

            val tests = mutableListOf<Test>()
            discoverIndexTestClasses().forEach { cls ->
                Collections.list(TestSuite(cls).tests()).forEach(tests::add)
            }

            tests.shuffle(Random(seed))

            println(
                "ShuffledIndexSuiteTest: running ${tests.size} index tests with seed $seed " +
                    "(reproduce with -Pshopware.test.seed=$seed)"
            )

            return TestSuite("ShuffledIndexSuite(seed=$seed)").apply {
                tests.forEach(::addTest)
            }
        }

        private fun discoverIndexTestClasses(): List<Class<*>> {
            val classPathRoots = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .filter { it.isDirectory }

            val classNames = sortedSetOf<String>()

            PACKAGES.forEach { pkg ->
                val dir = pkg.replace('.', '/')
                classPathRoots.forEach { root ->
                    val packageDir = root.resolve(dir)
                    if (!packageDir.isDirectory) {
                        return@forEach
                    }

                    packageDir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
                        .forEach { classNames.add("$pkg.${it.name.removeSuffix(".class")}") }
                }
            }

            classNames.remove(ShuffledIndexSuiteTest::class.java.name)

            return classNames
                .map { Class.forName(it) }
                .filter { Test::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
        }
    }
}
