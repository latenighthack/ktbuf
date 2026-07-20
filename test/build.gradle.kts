plugins {
    kotlin("jvm")
    `java-library`
    libs.plugins.kotlin.jvm
    id("com.vanniktech.maven.publish.base")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.test)
    implementation(project(":library"))
    implementation(project(":rpc"))
    implementation(libs.okhttp3.okhttp)
    implementation(project(":server"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
}

mavenPublishing {
    coordinates("com.latenighthack.ktbuf", "ktbuf-test", version.toString())
}
