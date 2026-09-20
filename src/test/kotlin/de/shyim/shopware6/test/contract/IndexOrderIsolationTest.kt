package de.shyim.shopware6.test.contract

import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.shyim.shopware6.index.AdminComponentIndex
import de.shyim.shopware6.index.AdminSnippetIndex
import de.shyim.shopware6.index.TwigBlockHashIndex

/**
 * Order isolation check for project-level index caches.
 *
 * Two fixture "projects" (projectA / projectB) define a Twig block, an administration component
 * and a snippet with identical names but different content. Both are loaded into the test project
 * in opposite copy order; directory-scoped queries must return only the content of the respective
 * project regardless of the load order. The randomized second pass (RandomizedIndexOrderSuite)
 * executes the two methods in both relative orders across runs.
 */
class IndexOrderIsolationTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData/isolation/"

    fun testProjectALoadedBeforeProjectB() {
        copyProject("projectA")
        copyProject("projectB")

        assertProjectIsolation()
    }

    fun testProjectBLoadedBeforeProjectA() {
        copyProject("projectB")
        copyProject("projectA")

        assertProjectIsolation()
    }

    private fun copyProject(name: String) {
        myFixture.copyDirectoryToProject(name, name)
    }

    private fun assertProjectIsolation() {
        val dirA = myFixture.findFileInTempDir("projectA")
        val dirB = myFixture.findFileInTempDir("projectB")
        val scopeA = GlobalSearchScopesCore.directoriesScope(project, true, dirA)
        val scopeB = GlobalSearchScopesCore.directoriesScope(project, true, dirB)

        // Twig block with the same name in both projects
        val blocksA = FileBasedIndex.getInstance()
            .getValues(TwigBlockHashIndex.key, "shared_isolation_block", scopeA)
        val blocksB = FileBasedIndex.getInstance()
            .getValues(TwigBlockHashIndex.key, "shared_isolation_block", scopeB)

        assertTrue("projectA twig block was not indexed", blocksA.isNotEmpty())
        assertTrue("projectB twig block was not indexed", blocksB.isNotEmpty())
        blocksA.forEach {
            assertTrue("projectA scope leaked a block from ${it.absolutePath}", it.absolutePath.startsWith(dirA.path))
            assertTrue("projectA block content missing", it.text.contains("project-a-content"))
        }
        blocksB.forEach {
            assertTrue("projectB scope leaked a block from ${it.absolutePath}", it.absolutePath.startsWith(dirB.path))
            assertTrue("projectB block content missing", it.text.contains("project-b-content"))
        }
        assertFalse("projects must produce different block hashes", blocksA.map { it.hash }.containsAll(blocksB.map { it.hash }))

        // Administration component with the same name in both projects
        val componentsA = FileBasedIndex.getInstance()
            .getValues(AdminComponentIndex.key, "sw-shared-isolation", scopeA)
        val componentsB = FileBasedIndex.getInstance()
            .getValues(AdminComponentIndex.key, "sw-shared-isolation", scopeB)

        assertTrue("projectA admin component was not indexed", componentsA.isNotEmpty())
        assertTrue("projectB admin component was not indexed", componentsB.isNotEmpty())
        componentsA.forEach {
            assertTrue("projectA scope leaked a component from ${it.file}", it.file.startsWith(dirA.path))
            assertTrue("projectA component props missing", it.props.contains("alphaProp"))
            assertFalse(it.props.contains("betaProp"))
        }
        componentsB.forEach {
            assertTrue("projectB scope leaked a component from ${it.file}", it.file.startsWith(dirB.path))
            assertTrue("projectB component props missing", it.props.contains("betaProp"))
            assertFalse(it.props.contains("alphaProp"))
        }

        // Snippet with the same key in both projects
        val snippetsA = FileBasedIndex.getInstance()
            .getValues(AdminSnippetIndex.key, "${dirA.path}/snippet/en-GB.json", scopeA)
        val snippetsB = FileBasedIndex.getInstance()
            .getValues(AdminSnippetIndex.key, "${dirB.path}/snippet/en-GB.json", scopeB)

        assertTrue("projectA snippet file was not indexed", snippetsA.isNotEmpty())
        assertTrue("projectB snippet file was not indexed", snippetsB.isNotEmpty())
        snippetsA.forEach {
            assertTrue("projectA scope leaked a snippet file from ${it.file}", it.file.startsWith(dirA.path))
            assertEquals("project-a", it.snippets["sharedIsolation"])
        }
        snippetsB.forEach {
            assertTrue("projectB scope leaked a snippet file from ${it.file}", it.file.startsWith(dirB.path))
            assertEquals("project-b", it.snippets["sharedIsolation"])
        }
    }
}
