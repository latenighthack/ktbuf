import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("com.vanniktech.maven.publish.base")
}

// The published artifact is the patch runtime (Patch.kt / EditorList.kt) in
// commonMain. model.proto and its generated message + editor code are test
// fixtures and therefore live in commonTest.
//
// Codegen is driven by protoc directly rather than through the protobuf Gradle
// plugin: that plugin's output layout is tied to Android build variants (the old
// "/proto/debug" path check), which does not map onto generated sources that have
// to feed every Kotlin target from a common source set. This also drops the
// dependency on the retired `protoc-gen-kt-patch` plugin — the editor DSL it used
// to emit is now hand-written in commonTest.
val protoSourceDir = layout.projectDirectory.dir("src/commonTest/proto")
val protoOutputDir = layout.buildDirectory.dir("generated/proto/kotlin")

val generateProtoKotlin = tasks.register("generateProtoKotlin") {
    description = "Generates Kotlin sources from src/commonTest/proto using protoc-gen-kt."
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
    }
    androidTarget {
        publishLibraryVariants("release")
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
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(project(":library"))
            }
        }
        val commonTest by getting {
            // Wiring the task provider (not the raw directory) makes every test
            // compile task depend on codegen automatically.
            kotlin.srcDir(generateProtoKotlin)

            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.okhttp3.okhttp)
            }
        }
    }
}

android {
    namespace = "com.latenighthack.ktbuf"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

mavenPublishing {
    coordinates("com.latenighthack.ktbuf", "ktbuf-patch", version.toString())

    pom {
        name.set("KtBuf")
        description.set("A native Kotlin implementation of protocol buffers")
        inceptionYear.set("2024")
        url.set("https://github.com/latenighthack/ktbuf/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("mproberts")
                name.set("Mike Roberts")
                url.set("https://github.com/mproberts/")
            }
        }
        scm {
            url.set("https://github.com/latenighthack/ktbuf/")
            connection.set("scm:git:git://github.com/latenighthack/ktbuf.git")
            developerConnection.set("scm:git:ssh://git@github.com/latenighthack/ktbuf.git")
        }
    }
}
