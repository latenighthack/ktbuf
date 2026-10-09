import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.file.RegularFileProperty
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("com.vanniktech.maven.publish.base")
}

abstract class TransportPeerService : BuildService<TransportPeerService.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters { val script: RegularFileProperty }
    private var process: Process? = null
    private var address: String? = null
    @Synchronized fun url(): String {
        if (address == null) {
            val child = ProcessBuilder("python3", "-u", parameters.script.get().asFile.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            process = child
            address = checkNotNull(child.inputStream.bufferedReader().readLine()) { "Transport fixture failed to start" }
        }
        return checkNotNull(address)
    }
    override fun close() {
        process?.let { it.destroy(); if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly() }
    }
}
val transportPeer = gradle.sharedServices.registerIfAbsent("ktbufTransportPeer", TransportPeerService::class) {
    parameters.script.set(layout.projectDirectory.file("src/testFixtures/transport_peer.py"))
}
tasks.withType<KotlinJsTest>().configureEach {
    usesService(transportPeer)
    inputs.file(layout.projectDirectory.file("src/testFixtures/transport_peer.py"))
    outputs.upToDateWhen { false }
    doFirst {
        val url = transportPeer.get().url()
        environment("KTBUF_PEER_URL", url)
        rootProject.layout.buildDirectory.file("transport-peer-url.txt").get().asFile.apply { parentFile.mkdirs(); writeText(url) }
    }
}
tasks.withType<KotlinNativeTest>().configureEach {
    if (name.startsWith("macos")) {
        usesService(transportPeer)
    inputs.file(layout.projectDirectory.file("src/testFixtures/transport_peer.py"))
    outputs.upToDateWhen { false }
        doFirst {
        val url = transportPeer.get().url()
        environment("KTBUF_PEER_URL", url)
        rootProject.layout.buildDirectory.file("transport-peer-url.txt").get().asFile.apply { parentFile.mkdirs(); writeText(url) }
    }
    }
}

kotlin {
    jvm {
    }
    js {
        browser { testTask { useKarma { useChromeHeadless() } } }
        nodejs { testTask { useMocha { timeout = "10s" } } }
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
                //put your multiplatform dependencies here
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":library"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.okhttp3.okhttp)
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(npm("ws", "8.22.0"))
            }
        }

        val androidUnitTest by getting {
            kotlin.srcDir("src/jvmTest/kotlin")
        }

        val androidMain by getting {
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
    coordinates("com.latenighthack.ktbuf", "ktbuf-rpc", version.toString())

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
