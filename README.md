# KtBuf

The protobuf runtime and generated messages support Linux. The built-in HTTP and RPC transports are available on JVM, Android, Apple and JavaScript targets. On Linux, HTTP, unary RPC and streaming RPC calls fail immediately with `RpcResponseException` and `Codes.UNIMPLEMENTED`; supply a custom `HttpClient` or `RpcClient` for networking. Unsupported streaming calls never invoke their block or readiness callback.

Code generation uses the versions in `gradle/protobuf-tools.properties`. Install that exact `protoc` version and `go install latenighthack.com/protoc-gen-kt@088118bc634c03907ad30185e321de8cb8cc98db`. For paired generator development, build in its separate checkout and pass `-PprotocGenKtPath=/absolute/path/protoc-gen-kt`; `-PprotocPath` selects an explicit compiler override. Both executable contents and all configured schema imports are Gradle task inputs. Extra include directories use `-PprotoIncludeDirs` separated by the platform path separator. Generation stages output before replacing previously successful bindings.

Run `python3 scripts/test-codegen-inputs.py --plugin /absolute/path/protoc-gen-kt` to verify unchanged-input caching and invalidation after compiler, generator and imported-schema changes.

The generator pin identifies the reviewed source revision. Its commits and the runtime commits must both be available before running remote validation; workspace overrides allow local paired verification before any push.

Run `scripts/validate-generator.sh /absolute/path/protoc-gen-kt-checkout` to compile and execute the generator regression consumers against the current runtime without Maven Local. The release workflow requires this check, the code-generation cache regression, and all runtime, patch and transport platform tests before publication.
