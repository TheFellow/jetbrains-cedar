import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// IntelliJ Platform 2026.2 runs on Java 25.
kotlin { jvmToolchain(25) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val chicoryVersion = "1.7.5"

dependencies {
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:wasi:$chicoryVersion")
    implementation("com.dylibso.chicory:compiler:$chicoryVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null && file(localPath).exists()) {
            local(localPath)
        } else {
            create(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
        }
        bundledPlugins("com.intellij.modules.json", "org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        // the CHANGELOG.md section for this version, as HTML for the Marketplace "What's New"
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText
            .zip(providers.gradleProperty("pluginVersion")) { changelog, version ->
                val lines = changelog.lines()
                val start = lines.indexOfFirst { it.startsWith("## $version ") || it == "## $version" }
                if (start < 0) {
                    ""
                } else {
                    val items = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
                        .filter { it.startsWith("- ") }
                        .joinToString("") { "<li>${it.removePrefix("- ")}</li>" }
                    "<ul>$items</ul>"
                }
            }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    // Marketplace publishing: PUBLISH_TOKEN is a JetBrains Marketplace personal access token.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // Plugin Verifier: the local IDE when present (fast, offline), plus the released IDEs we claim to support.
    pluginVerification {
        ides {
            val localPath = providers.gradleProperty("platformLocalPath").orNull
            if (localPath != null && file(localPath).exists() && providers.gradleProperty("verifyLocalOnly").isPresent) {
                local(localPath)
            } else {
                create(
                    providers.gradleProperty("platformType"),
                    providers.gradleProperty("platformVersion"),
                )
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
            }
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}

/*
 * Cedar SDK: cedar-wasm (Rust) compiled to wasm32-wasip1, executed in the JVM by Chicory.
 */
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Cedar SDK wasm module with cargo."
    val crate = layout.projectDirectory.dir("cedar-wasm")
    inputs.files(crate.file("Cargo.toml"), crate.file("Cargo.lock"), crate.file("build.rs"))
    inputs.dir(crate.dir("src"))
    outputs.file(crate.file("target/wasm32-wasip1/release/jetbrains_cedar_wasm.wasm"))
    workingDir = crate.asFile
    commandLine(
        providers.gradleProperty("cargo").get(),
        "build", "--release", "--locked", "--target", "wasm32-wasip1",
    )
}

val cedarWasm = tasks.register<Copy>("cedarWasm") {
    from(cargoBuild) { rename { "cedar.wasm" } }
    into(layout.buildDirectory.dir("generated/cedar-wasm/cedar"))
}

/** Plugin id/version for the About command (upstream reads package.json). */
val pluginInfo = tasks.register<WriteProperties>("pluginInfo") {
    destinationFile = layout.buildDirectory.file("generated/plugin-info/cedar/plugin.properties")
    property("id", "io.github.thefellow.cedar")
    property("version", providers.gradleProperty("pluginVersion"))
}

sourceSets.main {
    resources.srcDir(cedarWasm.map { layout.buildDirectory.dir("generated/cedar-wasm").get() })
    resources.srcDir(pluginInfo.map { layout.buildDirectory.dir("generated/plugin-info").get() })
}

tasks {
    test {
        systemProperty("cedar.testdata", layout.projectDirectory.dir("testdata").asFile.absolutePath)
    }
    wrapper { gradleVersion = "9.6.1" }
}
