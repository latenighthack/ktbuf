import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// Test-only module: it is deliberately not published, so it is absent from the
// explicit publish list in .github/workflows/release.yml.

// Shared generation tracks both executable contents and imported schemas.
extra["protobufSourceDir"] = "src/commonMain/proto"
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
