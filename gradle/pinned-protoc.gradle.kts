// Every generator invocation uses private pinned executables and exact source patches.
val protocVersion = providers.gradleProperty("codegenProtocVersion").get()
val pluginVersion = providers.gradleProperty("protocGenKtVersion").get()
val goVersion = providers.gradleProperty("codegenGoVersion").get()
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val classifier = (when { os.contains("mac") || os.contains("darwin") -> "osx"; os.contains("win") -> "windows"; else -> "linux" }) + "-" +
    (when (arch) { "aarch64", "arm64" -> "aarch_64"; "x86_64", "amd64" -> "x86_64"; else -> arch })
val protoc = configurations.create("codegenProtoc")
dependencies.add(protoc.name, "com.google.protobuf:protoc:$protocVersion:$classifier@exe")
val plugin = layout.buildDirectory.file("tools/protoc-gen-kt" + if (os.contains("win")) ".exe" else "")
tasks.register<Exec>("installProtocGenKt") {
    inputs.property("module", "latenighthack.com/protoc-gen-kt@$pluginVersion")
    inputs.property("goToolchain", goVersion)
    inputs.files(rootProject.files("build-tools/install-protoc-gen-kt.go", "build-tools/protoc-gen-kt-patches.json"))
    outputs.file(plugin)
    environment("GOBIN", plugin.get().asFile.parentFile.absolutePath)
    environment("GOTOOLCHAIN", "go$goVersion")
    environment("GOPATH", layout.buildDirectory.dir("tooling/gopath").get().asFile.absolutePath)
    environment("GOMODCACHE", layout.buildDirectory.dir("tooling/gomod").get().asFile.absolutePath)
    environment("GOCACHE", layout.buildDirectory.dir("tooling/gocache").get().asFile.absolutePath)
    commandLine("go", "run", rootProject.file("build-tools/install-protoc-gen-kt.go"), pluginVersion,
        rootProject.file("build-tools/protoc-gen-kt-patches.json"), plugin.get().asFile.absolutePath)
}
