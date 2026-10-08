pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ktbuf"
include(":library")
include(":rpc")
include(":server")
include(":test")
include(":patch")
include(":conformance")

// Explicit isolated library development; release builds use published dependencies.
apply(from = "gradle/fh-workspace.settings.gradle")
