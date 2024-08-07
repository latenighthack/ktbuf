import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

buildscript {
    dependencies {
        classpath(libs.gradlePlugin.mavenPublish)
    }
}

plugins {
    alias(libs.plugins.androidLibrary).apply(false)
    alias(libs.plugins.kotlinMultiplatform).apply(false)
}

allprojects {
    group = "com.latenighthack.ktbuf"
    version = "1.0.2"

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
            signAllPublications()
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
