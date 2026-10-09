import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

// Test-only application: exercises the dependency and generated-code contracts at our SDK floor.
android {
    namespace = "com.latenighthack.ktbuf.consumer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
dependencies {
    implementation(project(":rpc"))
    implementation(project(":conformance"))
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
