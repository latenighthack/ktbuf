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
