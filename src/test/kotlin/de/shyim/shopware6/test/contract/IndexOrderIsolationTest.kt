package de.shyim.shopware6.test.contract

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.shyim.shopware6.index.AdminComponentIndex
import de.shyim.shopware6.index.AdminSnippetIndex
import de.shyim.shopware6.index.TwigBlockHashIndex

/**
 * Two temporary projects load a same-named Twig block, admin component and snippet in opposite
 * order. Each project must observe only its own indexed content - a leaked value from the other
 * project means the project-level index caches are polluted by test/loading order.
 */
class IndexOrderIsolationTest : BasePlatformTestCase() {

    private val ownFiles = mutableSetOf<String>()

    // a fresh descriptor instance per method gives every scenario its own temporary project and
    // keeps the append-only index key enumerators of the shared light project clean
    override fun getProjectDescriptor(): LightProjectDescriptor = LightProjectDescriptor()

    fun testForwardOrderProjectSeesOnlyOwnContent() {
        runScenario("forward", listOf(::addTwigBlock, ::addAdminComponent, ::addAdminSnippet))
    }

    fun testReverseOrderProjectSeesOnlyOwnContent() {
        runScenario("reverse", listOf(::addAdminSnippet, ::addAdminComponent, ::addTwigBlock))
    }

    private fun runScenario(tag: String, steps: List<(String) -> Unit>) {
        steps.forEach { step ->
            step(tag)
            // force the indexes to ingest the file before the next one is added
            FileBasedIndex.getInstance().getAllKeys(TwigBlockHashIndex.key, project)
            FileBasedIndex.getInstance().getAllKeys(AdminComponentIndex.key, project)
            FileBasedIndex.getInstance().getAllKeys(AdminSnippetIndex.key, project)
        }

        assertTwigBlockIsOwn(tag)
        assertAdminComponentIsOwn(tag)
        assertAdminSnippetIsOwn(tag)
    }

    private fun addTwigBlock(tag: String) {
        val file = myFixture.addFileToProject(
            "Resources/views/storefront/order/shared.html.twig",
            """
            {% block order_shared_block %}
                content-$tag
            {% endblock %}
            """.trimIndent()
        )
        ownFiles.add(file.virtualFile.path)
    }

    private fun addAdminComponent(tag: String) {
        val file = myFixture.addFileToProject(
            "src/order-shared.js",
            """
            Component.register('sw-order-shared', {
                props: {
                    tag${tag.replaceFirstChar { it.uppercase() }}: {
                        type: String
                    }
                }
            });
            """.trimIndent()
        )
        ownFiles.add(file.virtualFile.path)
    }

    private fun addAdminSnippet(tag: String) {
        val file = myFixture.addFileToProject(
            "snippet/en-GB.json",
            """{"order": {"shared": "$tag"}}"""
        )
        ownFiles.add(file.virtualFile.path)
    }

    private fun assertTwigBlockIsOwn(tag: String) {
        val values = FileBasedIndex.getInstance()
            .getValues(TwigBlockHashIndex.key, "order_shared_block", GlobalSearchScope.allScope(project))

        assertTrue("expected the own twig block to be indexed", values.isNotEmpty())
        values.forEach { block ->
            assertTrue(
                "twig block leaked from another project: ${block.absolutePath}",
                block.absolutePath in ownFiles && block.text.contains("content-$tag")
            )
        }
    }

    private fun assertAdminComponentIsOwn(tag: String) {
        val values = FileBasedIndex.getInstance()
            .getValues(AdminComponentIndex.key, "sw-order-shared", GlobalSearchScope.allScope(project))

        assertTrue("expected the own admin component to be indexed", values.isNotEmpty())
        values.forEach { component ->
            assertTrue(
                "admin component leaked from another project: ${component.file}",
                component.file in ownFiles &&
                    component.props.contains("tag${tag.replaceFirstChar { it.uppercase() }}")
            )
        }
    }

    private fun assertAdminSnippetIsOwn(tag: String) {
        val key = FileBasedIndex.getInstance().getAllKeys(AdminSnippetIndex.key, project)
            .firstOrNull { it.endsWith("/snippet/en-GB.json") }

        assertNotNull("expected the own admin snippet file to be indexed", key)

        val values = FileBasedIndex.getInstance()
            .getValues(AdminSnippetIndex.key, key!!, GlobalSearchScope.allScope(project))

        assertTrue(values.isNotEmpty())
        values.forEach { snippetFile ->
            assertTrue(
                "admin snippet leaked from another project: ${snippetFile.file}",
                snippetFile.file in ownFiles && snippetFile.snippets["order.shared"] == tag
            )
        }
    }
}
