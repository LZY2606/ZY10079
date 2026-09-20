package contract

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Static contract check for the FileBasedIndex implementations of this plugin.
 *
 * Derives the expected index coverage set from two sources and compares it with the
 * test manifest (`src/test/resources/index-contract/manifest.txt`):
 *
 *  1. `<fileBasedIndex implementation="..."/>` registrations in plugin.xml
 *  2. classes extending FileBasedIndexExtension / ScalarIndexExtension in the index source package
 *
 * Any difference in either direction fails the build. Additionally, every manifest
 * entry must have a non-empty fixture directory below `src/test/testData/contract/`
 * and every `getTestDataPath()` referenced from test sources must exist, so missing
 * testData can never be skipped silently.
 */
abstract class VerifyIndexContractManifestTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginXml: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val indexSourceDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testDataRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    private val registeredRegex = Regex("""<fileBasedIndex\s+implementation="([^"]+)"""")
    private val packageRegex = Regex("""(?m)^package\s+([\w.]+)""")
    private val indexClassRegex =
        Regex("""(?:^|\s)class\s+(\w+)\s*:\s*\w*(?:FileBasedIndexExtension|ScalarIndexExtension)""")
    private val testDataPathRegex = Regex(""""(src/test/testData/[^"]+)"""")

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()

        val registered = parseRegistered()
        val implemented = parseImplemented()
        val manifest = parseManifest()

        diff("plugin.xml registrations", registered, "index manifest", manifest, problems)
        diff("index source classes", implemented, "index manifest", manifest, problems)
        diff("plugin.xml registrations", registered, "index source classes", implemented, problems)

        manifest.forEach { fqn ->
            val fixtureDir = testDataRoot.dir("contract/${fqn.substringAfterLast('.')}").get().asFile
            if (!fixtureDir.isDirectory) {
                problems += "Missing fixture directory for $fqn: ${fixtureDir.toRelativeString(projectDir())}"
            } else if (fixtureDir.walkTopDown().none { it.isFile }) {
                problems += "Fixture directory for $fqn contains no files: ${fixtureDir.toRelativeString(projectDir())}"
            }
        }

        val referencedTestData = testSources.files
            .filter { it.extension == "kt" }
            .flatMap { file -> testDataPathRegex.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()

        referencedTestData.forEach { path ->
            if (!projectDir().resolve(path).isDirectory) {
                problems += "Test sources reference missing testData directory: $path"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Index contract verification failed with ${problems.size} problem(s):\n" +
                    problems.joinToString("\n") { " - $it" } +
                    "\n\nUpdate src/test/resources/index-contract/manifest.txt and " +
                    "src/test/testData/contract/<IndexClass>/ fixtures together with the index change."
            )
        }

        logger.lifecycle(
            "Index contract manifest OK: {} indexes registered, implemented and covered.",
            manifest.size
        )
    }

    private fun parseRegistered(): Set<String> =
        registeredRegex.findAll(pluginXml.get().asFile.readText())
            .map { it.groupValues[1] }
            .toSortedSet()

    private fun parseImplemented(): Set<String> =
        indexSourceDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                val pkg = packageRegex.find(text)?.groupValues?.get(1) ?: return@flatMap emptySequence()
                indexClassRegex.findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList().asSequence()
            }
            .toSortedSet()

    private fun parseManifest(): Set<String> =
        manifestFile.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSortedSet()

    private fun diff(
        leftName: String,
        left: Set<String>,
        rightName: String,
        right: Set<String>,
        problems: MutableList<String>
    ) {
        (left - right).forEach { problems += "$it is in $leftName but missing from $rightName" }
        (right - left).forEach { problems += "$it is in $rightName but missing from $leftName" }
    }

    private fun projectDir() = projectDirectory.get().asFile
}
