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
    implementation(project(":library"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.okhttp3.okhttp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":test"))
    testImplementation(project(":rpc"))
    // Generated typed messages/services, so the transport tests exercise the same
    // code path a real consumer uses rather than hand-rolled descriptors.
    testImplementation(project(":conformance"))
}

mavenPublishing {
    coordinates("com.latenighthack.ktbuf", "ktbuf-server", version.toString())
}
