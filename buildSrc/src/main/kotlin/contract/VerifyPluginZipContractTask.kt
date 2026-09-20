package contract

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

/**
 * Verifies the distribution ZIP built by `buildPlugin`:
 *
 *  - the plugin jar carries META-INF/plugin.xml with the declared plugin id and version
 *  - declared resources (plugin icon, file templates, live templates) are packaged
 *  - no caches, sandbox state, logs or test data leak into the archive
 *  - writes a stable, entry-sorted SHA-256 manifest of the ZIP content for auditing
 */
abstract class VerifyPluginZipContractTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginZip: RegularFileProperty

    @get:Input
    abstract val expectedPluginId: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:OutputFile
    abstract val shaManifest: RegularFileProperty

    private val forbiddenPatterns = listOf(
        Regex("(^|/)testData(/|$)") to "test fixture data",
        Regex("(^|/)\\.idea(/|$)") to "IntelliJ project metadata",
        Regex("\\.iml$") to "IntelliJ module files",
        Regex("(^|/)\\.DS_Store$") to "macOS metadata",
        Regex("__MACOSX") to "macOS archive metadata",
        Regex("\\.log$") to "log files",
        Regex("(^|/)sandbox(/|$)") to "IDE sandbox state",
        Regex("(^|/)indexCaches?(/|$)", RegexOption.IGNORE_CASE) to "index caches",
        Regex("(^|/)caches(/|$)") to "build caches"
    )

    @TaskAction
    fun verify() {
        val zip = pluginZip.get().asFile
        if (!zip.isFile) {
            throw GradleException("Plugin distribution not found: $zip. Run buildPlugin first.")
        }

        val problems = mutableListOf<String>()
        val entryHashes = sortedMapOf<String, String>()

        ZipFile(zip).use { zipFile ->
            val entries = zipFile.entries().toList().filter { !it.isDirectory }
            if (entries.isEmpty()) {
                problems += "ZIP contains no files"
            }

            entries.forEach { entry ->
                checkForbidden(entry.name, problems)
                entryHashes[entry.name] = zipFile.getInputStream(entry).readBytes().sha256()
            }

            val pluginJar = entries
                .filter { it.name.endsWith(".jar") }
                .firstOrNull { jarEntryNames(zipFile.getInputStream(it).readBytes()).contains("META-INF/plugin.xml") }

            if (pluginJar == null) {
                problems += "No plugin jar with META-INF/plugin.xml found in the distribution"
            } else {
                val jarBytes = zipFile.getInputStream(pluginJar).readBytes()
                val jarEntries = jarEntryNames(jarBytes)
                jarEntries.forEach { checkForbidden("${pluginJar.name}!/$it", problems) }

                val pluginXml = jarEntryContent(jarBytes, "META-INF/plugin.xml")
                if (pluginXml == null) {
                    problems += "META-INF/plugin.xml missing in ${pluginJar.name}"
                } else {
                    val id = expectedPluginId.get()
                    val version = expectedVersion.get()
                    if (!pluginXml.contains("<id>$id</id>")) {
                        problems += "plugin.xml does not declare expected plugin id '$id'"
                    }
                    if (!pluginXml.contains("<version>$version</version>")) {
                        problems += "plugin.xml does not declare expected version '$version'"
                    }
                }

                if (!jarEntries.contains("META-INF/pluginIcon.svg")) {
                    problems += "META-INF/pluginIcon.svg missing in ${pluginJar.name}"
                }
                if (jarEntries.none { it.startsWith("fileTemplates/") }) {
                    problems += "No fileTemplates/ entries packaged in ${pluginJar.name}"
                }
                if (jarEntries.none { it.startsWith("liveTemplates/") }) {
                    problems += "No liveTemplates/ entries packaged in ${pluginJar.name}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Plugin ZIP contract verification failed with ${problems.size} problem(s):\n" +
                    problems.joinToString("\n") { " - $it" }
            )
        }

        val manifest = shaManifest.get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(
            buildString {
                appendLine("# SHA-256 content manifest of ${zip.name}")
                appendLine("# One line per ZIP entry: <sha256 of uncompressed content>  <entry name>")
                entryHashes.forEach { (name, hash) -> appendLine("$hash  $name") }
                appendLine("# SHA-256 of the ZIP archive itself:")
                appendLine("${zip.readBytes().sha256()}  ${zip.name}")
            }
        )

        logger.lifecycle("Plugin ZIP contract OK: {} entries, manifest at {}", entryHashes.size, manifest)
    }

    private fun checkForbidden(name: String, problems: MutableList<String>) {
        forbiddenPatterns.forEach { (pattern, description) ->
            if (pattern.containsMatchIn(name)) {
                problems += "Forbidden entry ($description): $name"
            }
        }
    }

    private fun jarEntryNames(jarBytes: ByteArray): List<String> =
        JarInputStream(jarBytes.inputStream()).use { jar ->
            generateSequence { jar.nextJarEntry }.filter { !it.isDirectory }.map { it.name }.toList()
        }

    private fun jarEntryContent(jarBytes: ByteArray, name: String): String? =
        JarInputStream(jarBytes.inputStream()).use { jar ->
            generateSequence { jar.nextJarEntry }
                .firstOrNull { it.name == name }
                ?.let { jar.readBytes().toString(Charsets.UTF_8) }
        }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
