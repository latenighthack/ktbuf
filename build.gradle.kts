import groovy.json.JsonOutput

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

buildscript {
    dependencies {
        classpath(libs.gradlePlugin.mavenPublish)
    }
}

plugins {
    alias(libs.plugins.androidLibrary).apply(false)
    alias(libs.plugins.androidApplication).apply(false)
    alias(libs.plugins.kotlinAndroid).apply(false)
    alias(libs.plugins.kotlinMultiplatform).apply(false)
}

allprojects {
    group = "com.latenighthack.ktbuf"
    version = "1.1.10"

    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish.base") {
        val publishingExtension = extensions.getByType(PublishingExtension::class.java)

        configure<MavenPublishBaseExtension> {
            configureBasedOnAppliedPlugins(true, true)
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
            // Sign only when a key is configured (CI release); a local publishToMavenLocal for
            // SNAPSHOT consumption has no signatory and must not fail on the signing task.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            pom {
                name.set(project.name)
                description.set("Native protocol buffers for Kotlin.")
                url.set("https://latenighthack.github.io/ktbuf/")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/latenighthack/ktbuf.git")
                    developerConnection.set("scm:git:ssh://git@github.com/latenighthack/ktbuf.git")
                    url.set("https://github.com/latenighthack/ktbuf")
                }
                developers {
                    developer {
                        name.set("Late Night Hack")
                    }
                }
            }
        }
    }
}

// Compile the separately checked-out generator's emitted consumers against this runtime.
val generatorCompiler by configurations.creating { isCanBeConsumed = false }
val generatorConsumer by configurations.creating { isCanBeConsumed = false }
dependencies {
    generatorCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    generatorConsumer("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    generatorConsumer(libs.kotlinx.coroutines.core)
}
tasks.register("writeGeneratorClasspaths") {
    dependsOn(":library:jvmJar")
    val runtimeJar = project(":library").layout.buildDirectory.file("libs/library-jvm-${project.version}.jar")
    val output = layout.buildDirectory.file("generator-classpaths.json")
    inputs.files(generatorCompiler, generatorConsumer, runtimeJar)
    outputs.file(output)
    doLast {
        val runtime = runtimeJar.get().asFile
        check(runtime.isFile) { "Runtime JAR is missing: $runtime" }
        val paths = mapOf(
            "compiler" to generatorCompiler.asPath,
            "consumer" to (generatorConsumer.asPath + File.pathSeparator + runtime.absolutePath)
        )
        output.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.toJson(paths)) }
    }
}
