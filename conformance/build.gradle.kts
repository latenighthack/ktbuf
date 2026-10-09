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

apply(from = rootProject.file("gradle/pinned-protoc.gradle.kts"))
val protoc = configurations.named("codegenProtoc")
val generator = layout.buildDirectory.file("tools/protoc-gen-kt" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
val generateProtoKotlin = tasks.register<Exec>("generateProtoKotlin") {
    description = "Generate fixtures with pinned protoc and the reviewed private generator"
    group = "build"
    dependsOn(tasks.named("installProtocGenKt"))
    inputs.dir(protoSourceDir).withPropertyName("protoSources")
    inputs.files(protoc)
    inputs.file(generator)
    inputs.property("protocVersion", providers.gradleProperty("codegenProtocVersion"))
    outputs.dir(protoOutputDir).withPropertyName("generatedSources")
    doFirst {
        val target = protoOutputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val executable = protoc.get().singleFile.apply { setExecutable(true) }
        val files = protoSourceDir.asFile.walkTopDown().filter { it.isFile && it.extension == "proto" }.sortedBy { it.path }.map { it.absolutePath }.toList()
        require(files.isNotEmpty()) { "No .proto inputs" }
        commandLine(listOf(executable.absolutePath, "--plugin=protoc-gen-kt=${generator.get().asFile.absolutePath}",
            "--kt_out=${target.absolutePath}", "-I", protoSourceDir.asFile.absolutePath) + files)
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
