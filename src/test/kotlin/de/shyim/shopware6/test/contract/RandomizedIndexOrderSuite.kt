package de.shyim.shopware6.test.contract

import de.shyim.shopware6.test.index.AdminComponentIndexTest
import de.shyim.shopware6.test.index.AdminComponentOverrideIndexTest
import de.shyim.shopware6.test.index.AdminModuleIndexTest
import de.shyim.shopware6.test.index.AdminSnippetIndexTest
import de.shyim.shopware6.test.index.EntityDefinitionIndexTest
import de.shyim.shopware6.test.index.SystemConfigIndexTest
import de.shyim.shopware6.test.index.TwigBlockDeprecationIndexTest
import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite
import org.junit.runner.RunWith
import org.junit.runners.AllTests
import kotlin.random.Random

/**
 * Second pass over all index tests with a shuffled class and method order, to surface
 * order-dependent pollution of project-level index caches. The seed is printed to stdout;
 * reproduce a specific order with ./gradlew indexTestsRandomized -DindexTestSeed=<seed>.
 */
@RunWith(AllTests::class)
class RandomizedIndexOrderSuite {
    companion object {
        private val INDEX_TEST_CLASSES = listOf(
            AdminComponentIndexTest::class.java,
            AdminComponentOverrideIndexTest::class.java,
            AdminModuleIndexTest::class.java,
            AdminSnippetIndexTest::class.java,
            EntityDefinitionIndexTest::class.java,
            SystemConfigIndexTest::class.java,
            TwigBlockDeprecationIndexTest::class.java,
            IndexContractTest::class.java,
            IndexOrderIsolationTest::class.java,
        )

        @JvmStatic
        fun suite(): Test {
            val seed = System.getProperty("indexTestSeed")?.toLongOrNull() ?: Random.Default.nextLong()
            val random = Random(seed)
            val suite = TestSuite("index-tests-randomized-seed-$seed")

            INDEX_TEST_CLASSES.shuffled(random).forEach { testClass ->
                testClass.declaredMethods
                    .filter { it.name.startsWith("test") }
                    .map { it.name }
                    .sorted()
                    .shuffled(random)
                    .forEach { methodName ->
                        val testCase = testClass.getDeclaredConstructor().newInstance() as TestCase
                        testCase.name = methodName
                        suite.addTest(testCase)
                    }
            }

            println("[RandomizedIndexOrderSuite] Running ${suite.countTestCases()} index tests with seed $seed (reproduce with -DindexTestSeed=$seed)")
            return suite
        }
    }
}
