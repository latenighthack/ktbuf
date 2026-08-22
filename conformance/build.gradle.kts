import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// Test-only module: it is deliberately not published, so it is absent from the
// explicit publish list in .github/workflows/release.yml.

// Codegen is driven by protoc directly rather than through the protobuf Gradle
// plugin. The plugin's output layout is tied to Android build variants (see the
// "/proto/debug" path check in :patch), which does not map onto a module whose
// generated sources have to feed all nine Kotlin targets from commonMain.
val protoSourceDir = layout.projectDirectory.dir("src/commonMain/proto")
val protoOutputDir = layout.buildDirectory.dir("generated/proto/kotlin")

val generateProtoKotlin = tasks.register("generateProtoKotlin") {
    description = "Generates Kotlin sources from src/commonMain/proto using protoc-gen-kt."
    group = "build"

    val inputDir = protoSourceDir.asFile
    val outputDir = protoOutputDir

    inputs.dir(inputDir).withPropertyName("protoSources")
    outputs.dir(outputDir).withPropertyName("generatedSources")

    doLast {
        val target = outputDir.get().asFile

        target.deleteRecursively()
        target.mkdirs()

        val protoFiles = inputDir.walkTopDown()
            .filter { it.isFile && it.extension == "proto" }
            .map { it.absolutePath }
            .toList()

        require(protoFiles.isNotEmpty()) { "no .proto files found in $inputDir" }

        // protoc discovers the `kt` plugin by looking for `protoc-gen-kt` on PATH.
        val result = providers.exec {
            commandLine(
                buildList {
                    add("protoc")
                    add("--kt_out=${target.absolutePath}")
                    add("-I")
                    add(inputDir.absolutePath)
                    addAll(protoFiles)
                }
            )
            isIgnoreExitValue = true
        }

        val exitCode = result.result.get().exitValue

        if (exitCode != 0) {
            val stderr = result.standardError.asText.get().trim()

            throw GradleException(
                buildString {
                    appendLine("protoc failed with exit code $exitCode.")
                    if (stderr.isNotEmpty()) appendLine(stderr)
                    appendLine()
                    appendLine("This module needs both `protoc` and the `protoc-gen-kt` plugin on PATH:")
                    appendLine("  brew install protobuf")
                    appendLine("  go install latenighthack.com/protoc-gen-kt@latest")
                    append("  export PATH=\"\$(go env GOPATH)/bin:\$PATH\"")
                }
            )
        }
    }
}

kotlin {
    jvm {
    }
    js {
        browser()
        nodejs()
    }
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    linuxX64()

    sourceSets {
        val commonMain by getting {
            // Wiring the task provider (not the raw directory) makes every compile
            // task depend on codegen automatically.
            kotlin.srcDir(generateProtoKotlin)

            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(project(":library"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "com.latenighthack.ktbuf.conformance"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
