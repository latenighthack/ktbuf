# KtBuf

The protobuf runtime and generated messages support Linux. The built-in HTTP and RPC transports are available on JVM, Android, Apple and JavaScript targets. On Linux, HTTP, unary RPC and streaming RPC calls fail immediately with `RpcResponseException` and `Codes.UNIMPLEMENTED`; supply a custom `HttpClient` or `RpcClient` for networking. Unsupported streaming calls never invoke their block or readiness callback.
