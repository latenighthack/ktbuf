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
// Shared generation tracks both executable contents and imported schemas.
extra["protobufSourceDir"] = "src/commonTest/proto"
apply(from = rootProject.file("gradle/protobuf-codegen.gradle"))
val generateProtoKotlin = tasks.named("generateProtoKotlin")

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
