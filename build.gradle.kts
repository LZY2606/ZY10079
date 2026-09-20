import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
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

// --- Plugin contract verification -------------------------------------------------

val verifyIndexContractManifest = tasks.register<contract.VerifyIndexContractManifestTask>("verifyIndexContractManifest") {
    group = "verification"
    description = "Checks that every registered FileBasedIndex has contract coverage, fixtures and testData."

    pluginXml.set(layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml"))
    indexSourceDir.set(layout.projectDirectory.dir("src/main/kotlin/de/shyim/shopware6/index"))
    manifestFile.set(layout.projectDirectory.file("src/test/resources/index-contract/manifest.txt"))
    testDataRoot.set(layout.projectDirectory.dir("src/test/testData"))
    testSources.from(fileTree(layout.projectDirectory.dir("src/test/kotlin")) { include("**/*.kt") })
    projectDirectory.set(layout.projectDirectory)
}

// A second, fully wired IDE test task running the index test suite in randomized order.
// Registered through intellijPlatformTesting so it gets the same IDE classpath, sandbox
// and JVM setup as the standard test task.
intellijPlatformTesting {
    testIde {
        register("indexTestsRandomized") {
            testFrameworks(TestFrameworkType.Platform)

            task {
                group = "verification"
                description = "Second pass over the index tests with randomized test order."

                include("de/shyim/shopware6/test/contract/RandomizedIndexOrderSuite.class")

                // TestIdeTask wires the IDE test classpath but not the test resources.
                classpath += files(sourceSets["test"].output.resourcesDir)

                // A verification pass must always run; never skip it as up-to-date.
                outputs.upToDateWhen { false }

                mustRunAfter(tasks.named("test"))

                systemProperty(
                    "indexTestSeed",
                    providers.gradleProperty("indexTestSeed").orElse("").get(),
                )
                systemProperty(
                    "index.contract.updateSnapshots",
                    providers.gradleProperty("index.contract.updateSnapshots").orElse("false").get(),
                )
            }
        }
    }
}

val verifyPluginZipContract = tasks.register<contract.VerifyPluginZipContractTask>("verifyPluginZipContract") {
    group = "verification"
    description = "Verifies the built plugin ZIP contents and writes a stable SHA-256 manifest."

    pluginZip.set(tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile })
    expectedPluginId.set("de.shyim.shopware6")
    expectedVersion.set(providers.gradleProperty("pluginVersion"))
    shaManifest.set(layout.buildDirectory.file("reports/pluginContract/plugin-zip-manifest.sha256"))
}

tasks.named<Test>("test") {
    mustRunAfter(verifyIndexContractManifest)
    // The randomized second pass runs exclusively in indexTestsRandomized.
    exclude("de/shyim/shopware6/test/contract/RandomizedIndexOrderSuite*")
    systemProperty(
        "index.contract.updateSnapshots",
        providers.gradleProperty("index.contract.updateSnapshots").orElse("false").get(),
    )
}

tasks.register("verifyPluginContract") {
    group = "verification"
    description = "Runs static contract checks, unit tests, a randomized second pass of index tests, " +
        "the plugin build and the plugin ZIP contract check. Works offline with resolved dependencies."

    dependsOn(verifyIndexContractManifest, tasks.named("test"), tasks.named("indexTestsRandomized"), verifyPluginZipContract)
}
