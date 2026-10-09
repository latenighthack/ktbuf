# F42: reproducible conformance and patch generation

Both generator consumers previously invoked arbitrary PATH executables, and CI installed an unpinned generator. They now use private protobuf 4.33.0 executables, Go 1.26.1, and protoc-gen-kt v0.0.0-20251214023608-0fa742406fbf with the checked-in exact source patch manifest shared with lockers (4986d11). Every proto input, installer, patch manifest and tool version participates in task invalidation. Generated bindings and global tool/cache contents were not edited.

Validation: conformance and patch JVM and Node suites passed (52 tasks); both Apple simulator suites and Android compilation passed (39 tasks). Logs: /tmp/ktbuf-pinned-generator.log and /tmp/ktbuf-pinned-generator-platforms.log.
