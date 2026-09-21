import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.jayway.jsonpath:json-path:3.0.0")
    implementation("net.minidev:json-smart:2.6.0")
    implementation("org.codehaus.jettison:jettison:1.5.7")

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    buildSearchableOptions = false

    // Keep the IDE sandbox inside the build directory so contract cleanup stays in build/
    sandboxContainer.set(layout.buildDirectory.dir("idea-sandbox"))

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // GitHub Releases explicitly selects channels; local publishing infers EAP from a version suffix.
        // Stable releases also reach EAP subscribers because custom repositories take precedence.
        // https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html
        channels = providers.gradleProperty("pluginChannels").map { it.split(',') }
            .orElse(providers.gradleProperty("pluginVersion").map {
                if ('-' in it.substringBefore('+')) listOf("eap") else listOf("default", "eap")
            })
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    runIde {
        jvmArgs("-Xmx4000m")
    }

    // The bundled Vue plugin fails to initialize its LSP service in the test IDE because it cannot
    // resolve its language-server binary from the transformed distribution layout. That failure
    // fires from VFS listeners as soon as a `.js` file is added to a fixture project and is promoted
    // to a test failure. We don't use Vue, so disable the plugin in the test sandbox.
    prepareTestSandbox {
        val configDir = sandboxConfigDirectory
        doLast {
            configDir.get().asFile.resolve("disabled_plugins.txt")
                .writeText("org.jetbrains.plugins.vue\n")
        }
    }
    processResources {
        exclude("fileTemplates/j2ee/**")
        from(fileTree("src/main/resources/fileTemplates/j2ee").files) {
            eachFile {
                relativePath = RelativePath(true, "fileTemplates", "j2ee", this.name)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Plugin contract verification
// ---------------------------------------------------------------------------

// The shuffled suite is the second pass; keep it out of the regular unit-test run.
tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("de.shyim.shopware6.test.index.ShuffledIndexSuiteTest")
    }
}

// Deletes persisted index/system caches of the test sandbox. Cleanup is limited to build/.
val cleanTestSandbox = tasks.register("cleanTestSandbox") {
    group = "verification"
    description = "Deletes persisted index caches of the test sandbox inside the build directory."

    val sandboxDir = layout.buildDirectory.dir("idea-sandbox")

    doLast {
        val root = sandboxDir.get().asFile
        if (!root.isDirectory) {
            return@doLast
        }

        root.walkTopDown()
            .filter { it.isDirectory && (it.name == "system" || it.name.startsWith("system-")) }
            .toList()
            .forEach { it.deleteRecursively() }
    }
}

tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    // Only test sandboxes are reset; runIde keeps its system caches.
    if (name == "prepareTestSandbox" || name.startsWith("prepareSandbox_test")) {
        dependsOn(cleanTestSandbox)
    }
}

// Forward contract-related Gradle properties into every test JVM.
tasks.withType<Test>().configureEach {
    systemProperty(
        "shopware.contract.updateSnapshot",
        providers.gradleProperty("shopware.contract.updateSnapshot").getOrElse("false"),
    )
    systemProperty(
        "shopware.test.seed",
        providers.gradleProperty("shopware.test.seed").getOrElse(""),
    )
}

val contractReportDir = layout.buildDirectory.dir("reports/pluginContract")

tasks.register("contractStaticCheck") {
    group = "verification"
    description = "Static checks: plugin.xml registration vs index sources, testData sanity."

    val pluginXmlFile = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
    val indexSourceDir = layout.projectDirectory.dir("src/main/kotlin/de/shyim/shopware6/index").asFile
    val testDataDir = layout.projectDirectory.dir("src/test/testData").asFile
    val manifestFile = layout.projectDirectory.file("src/test/testData/index-contract/index-manifest.json").asFile
    val expectedPluginId = "de.shyim.shopware6"

    inputs.file(pluginXmlFile)
    inputs.dir(indexSourceDir)
    inputs.dir(testDataDir)

    doLast {
        val failures = mutableListOf<String>()

        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pluginXmlFile)

        val pluginIds = document.getElementsByTagName("id")
        if (pluginIds.length == 0 || pluginIds.item(0).textContent.trim() != expectedPluginId) {
            failures.add("plugin.xml <id> must stay '$expectedPluginId'")
        }

        val registered = sortedSetOf<String>()
        val indexNodes = document.getElementsByTagName("fileBasedIndex")
        for (i in 0 until indexNodes.length) {
            val implementation = indexNodes.item(i).attributes?.getNamedItem("implementation")?.nodeValue
            if (!implementation.isNullOrBlank()) {
                registered.add(implementation)
            }
        }

        val classPattern = Regex("class\\s+(\\w+)[^:\\n]*:\\s*(?:[\\w.]+\\.)?FileBasedIndexExtension")
        val packagePattern = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
        val implemented = sortedSetOf<String>()
        indexSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val pkg = packagePattern.find(text)?.groupValues?.get(1) ?: return@forEach
                classPattern.findAll(text).forEach { implemented.add("$pkg.${it.groupValues[1]}") }
            }

        val unregistered = implemented - registered
        val missing = registered - implemented
        if (unregistered.isNotEmpty() || missing.isNotEmpty()) {
            failures.add(
                "plugin.xml fileBasedIndex registrations and index sources differ: " +
                    "not registered=$unregistered, not implemented=$missing"
            )
        }

        testDataDir.walkTopDown()
            .filter { it.isFile && it.length() == 0L }
            .forEach { failures.add("empty testData file: ${it.relativeTo(testDataDir)}") }

        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        val indexes = manifest["indexes"] as List<*>
        val manifestClasses = sortedSetOf<String>()
        indexes.forEach { entry ->
            entry as Map<*, *>
            val className = entry["className"] as String
            manifestClasses.add(className)

            val fixtures = entry["fixtures"] as List<*>
            if (fixtures.isEmpty()) {
                failures.add("$className: manifest entry declares no fixtures")
            }
            fixtures.forEach { fixture ->
                val fixtureFile = testDataDir.resolve(fixture as String)
                when {
                    !fixtureFile.isFile ->
                        failures.add("$className: missing testData fixture '$fixture' (never skipped silently)")
                    fixtureFile.length() == 0L ->
                        failures.add("$className: empty testData fixture '$fixture'")
                }
            }
        }

        val uncovered = registered - manifestClasses
        val unknown = manifestClasses - registered
        if (uncovered.isNotEmpty() || unknown.isNotEmpty()) {
            failures.add("index-manifest.json out of sync: uncovered=$uncovered, unknown=$unknown")
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "contractStaticCheck failed:\n - " + failures.joinToString("\n - ")
            )
        }
    }
}

// Second pass over all index tests in a random, seeded order, in a dedicated sandbox.
// Registered through the official intellijPlatformTesting extension so the task gets the
// same IntelliJ Platform test runtime (classpath, sandbox, JVM options) as the stock test task.
intellijPlatformTesting {
    testIde {
        register("testIndexShuffled") {
            testFramework(TestFrameworkType.Platform)

            task {
                group = "verification"
                description = "Second pass over all index tests in a random, seeded order."

                dependsOn(tasks.named("test"))

                systemProperty("shopware.contract.shuffled", "true")

                filter {
                    includeTestsMatching("de.shyim.shopware6.test.index.ShuffledIndexSuiteTest")
                }
            }
        }
    }
}

tasks.register("verifyPluginZipContents") {
    group = "verification"
    description = "Verifies the built plugin ZIP and writes a stable SHA-256 manifest."

    dependsOn(tasks.named("buildPlugin"))

    val distributionsDir = layout.buildDirectory.dir("distributions")
    val reportDir = contractReportDir
    val expectedPluginId = "de.shyim.shopware6"
    val expectedVersion = providers.gradleProperty("pluginVersion").get()

    inputs.dir(distributionsDir)
    outputs.dir(reportDir)

    doLast {
        val distDir = distributionsDir.get().asFile
        val outDir = reportDir.get().asFile

        // cleanup is limited to the build directory
        outDir.deleteRecursively()
        outDir.mkdirs()

        val zips = distDir.listFiles { file -> file.extension == "zip" }?.toList().orEmpty()
        if (zips.size != 1) {
            throw GradleException("Expected exactly one plugin ZIP in $distDir, found ${zips.size}")
        }
        val zipFile = zips.single()

        val forbiddenMarkers = listOf("testData", "__MACOSX", ".DS_Store", "index-contract", "/caches/")
        val sha256 = MessageDigest.getInstance("SHA-256")
        fun hash(bytes: ByteArray): String =
            sha256.digest(bytes).joinToString("") { "%02x".format(it) }

        val manifestLines = mutableListOf<String>()
        val forbidden = mutableListOf<String>()
        val failures = mutableListOf<String>()

        var pluginXmlText: String? = null
        var pluginJarName: String? = null
        val pluginJarEntries = mutableSetOf<String>()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }

            entries.forEach { entry ->
                val bytes = zip.getInputStream(entry).readBytes()

                if (forbiddenMarkers.any { entry.name.contains(it) } || entry.name.endsWith(".iml")) {
                    forbidden.add(entry.name)
                }

                if (!entry.name.endsWith(".jar")) {
                    manifestLines.add("${hash(bytes)}  ${entry.name}")
                    return@forEach
                }

                JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
                    var inner = jar.nextEntry
                    while (inner != null) {
                        if (!inner.isDirectory) {
                            val innerBytes = jar.readBytes()
                            val innerPath = "${entry.name}!/${inner.name}"

                            if (forbiddenMarkers.any { inner.name.contains(it) } || inner.name.endsWith(".iml")) {
                                forbidden.add(innerPath)
                            }

                            manifestLines.add("${hash(innerBytes)}  $innerPath")

                            if (inner.name == "META-INF/plugin.xml") {
                                pluginXmlText = String(innerBytes, Charsets.UTF_8)
                                pluginJarName = entry.name
                            }
                            pluginJarEntries.add(inner.name)
                        }
                        inner = jar.nextEntry
                    }
                }
            }
        }

        val xml = pluginXmlText
            ?: throw GradleException("No jar inside ${zipFile.name} contains META-INF/plugin.xml")

        if (!xml.contains("<id>$expectedPluginId</id>")) {
            failures.add("plugin.xml in $pluginJarName does not declare <id>$expectedPluginId</id>")
        }
        if (!xml.contains("<version>$expectedVersion</version>")) {
            failures.add("plugin.xml in $pluginJarName does not declare <version>$expectedVersion</version>")
        }
        if ("META-INF/pluginIcon.svg" !in pluginJarEntries) {
            failures.add("$pluginJarName misses META-INF/pluginIcon.svg")
        }

        // every resource declared with file="..." / config-file="..." in plugin.xml must be packaged.
        // file="..." is root relative; config-file="..." is relative to META-INF/.
        Regex("""(file|config-file)="([^"]+)"""").findAll(xml).forEach { match ->
            val attribute = match.groupValues[1]
            val raw = match.groupValues[2].trimStart('/')
            val declared = when (attribute) {
                "config-file" -> "META-INF/$raw"
                else -> raw
            }
            if (declared !in pluginJarEntries) {
                failures.add("$pluginJarName misses resource declared in plugin.xml ($attribute): $raw")
            }
        }
        if (pluginJarEntries.none { it.startsWith("icons/") }) {
            failures.add("$pluginJarName contains no icons/ resources")
        }
        if (pluginJarEntries.none { it.startsWith("fileTemplates/") }) {
            failures.add("$pluginJarName contains no fileTemplates/ resources")
        }
        if (forbidden.isNotEmpty()) {
            failures.add("forbidden entries in plugin ZIP: $forbidden")
        }

        val manifestFile = outDir.resolve("plugin-zip-manifest.sha256")
        manifestFile.writeText(manifestLines.sorted().joinToString("\n") + "\n")

        println("Plugin ZIP contract: ${manifestLines.size} hashed entries -> $manifestFile")

        if (failures.isNotEmpty()) {
            throw GradleException(
                "verifyPluginZipContents failed:\n - " + failures.joinToString("\n - ")
            )
        }
    }
}

tasks.register("verifyPluginContract") {
    group = "verification"
    description = "Static checks, unit tests, shuffled second-pass index tests, plugin ZIP contract."

    dependsOn(
        "contractStaticCheck",
        "verifyPluginProjectConfiguration",
        "test",
        "testIndexShuffled",
        "verifyPluginZipContents",
    )
}

