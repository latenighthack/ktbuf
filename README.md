# KtBuf

The protobuf runtime and generated messages support Linux. The built-in HTTP and RPC transports are available on JVM, Android, Apple and JavaScript targets. On Linux, HTTP, unary RPC and streaming RPC calls fail immediately with `RpcResponseException` and `Codes.UNIMPLEMENTED`; supply a custom `HttpClient` or `RpcClient` for networking. Unsupported streaming calls never invoke their block or readiness callback.

Code generation uses the versions in `gradle/protobuf-tools.properties`. Install that exact `protoc` version and `go install latenighthack.com/protoc-gen-kt@26f2823ead58e535c47ae3e6a2a2a72c74093e1a`. For paired generator development, build in its separate checkout and pass `-PprotocGenKtPath=/absolute/path/protoc-gen-kt`; `-PprotocPath` selects an explicit compiler override. Both executable contents and all configured schema imports are Gradle task inputs. Extra include directories use `-PprotoIncludeDirs` separated by the platform path separator. Generation stages output before replacing previously successful bindings.

Run `python3 scripts/test-codegen-inputs.py --plugin /absolute/path/protoc-gen-kt` to verify unchanged-input caching and invalidation after compiler, generator and imported-schema changes.

The generator pin identifies the reviewed source revision. Its commits and the runtime commits must both be available before running remote validation; workspace overrides allow local paired verification before any push.
