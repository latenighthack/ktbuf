# Ktbuf adversarial correctness and security review

The audited runtime is ktbuf 1.1.10 at `d2073ba`, matching `origin/main` after fetch and a fast-forward-only merge. The generator is the separately released `latenighthack.com/protoc-gen-kt@v0.1.0`, which CI pins and the local executable reports; its source commit is `8eab46ebf780f84cde71308680d29e42bf13f582`.

The review identifies **44 finding groups: 19 P1, 24 P2, and one P3**. The immediate priorities are unary authentication, parser boundaries, stream lifecycle, silent map data loss, destructive patching, unsafe native length conversion, retry termination, and vulnerable dependencies. P1 means high priority because the behavior can bypass a supplied security check, corrupt or lose data, strand operations, or exhaust resources. P2 covers other correctness, interoperability, and validation defects. P3 covers API clarity and incomplete projections. Each finding states whether it was reproduced or confirmed in source.

## Validation results

Fresh wrapper runs passed **577 existing target test executions**: library and generated conformance on JVM, JS Node, Android debug and release, macOS arm64, and iOS simulator arm64; patch on JVM; server on JVM; and the Foundation large-frame regression on macOS arm64. Test tasks were forced to execute rather than simply reuse up-to-date test results.

The final defensive JVM probes assert expected behavior and fail **52 of 52 checks**: 25 wire and buffer checks, six flow and path checks, nine patch checks, and 12 server, retry, atomicity, and test-helper checks. Failure counts are probes, not distinct defects. Before the final three flow checks were added, 28 common probes were also run on Android and macOS with 28 failures each, and on JS with 27 failures; nine patch probes failed on macOS too. The one JS difference is the signed-zero hash probe.

Nineteen schema fixtures were passed through real protoc and the pinned generator, then Kotlin consumer compilation. Two schemas fail generation, ten generated outputs fail compilation, and two declaration-only cases fail when a consumer references the missing symbols. Compiling maps and proto2 fixtures also exhibit silent semantic failures. Basic messages, keyword oneofs, and bytes oneofs provide compilation controls; compilation success alone does not establish their behavioral correctness.

Malformed-wire results were independently checked with protobuf's Python runtime. Resource probes ran in isolated JVM subprocesses with bounded heaps, stacks, and wall-clock time. Maven advisory checks covered 45 resolved packages in the server runtime graph; two packages matched advisory ranges. Those range matches establish affected dependencies, not demonstrated exploitation.

## Coverage and limits

All tracked production Kotlin files in library, rpc, server, patch, and test were inspected, including the JVM, Android, JS, Apple, and Linux implementations. The published generator's entry point, naming/import/type helpers, and all six templates were inspected. Build scripts, workflows, relevant tests, and golden-generation tools were read.

Intel Apple targets and Linux were not executed on this arm64 macOS host. JS common code was tested under Node; actual browser transport and Android device transport findings are source-confirmed. Apple streaming's existing large-message test ran; new cancellation and text-frame cases for that implementation were not exercised. The unsafe NSData length case was not invoked with a multi-GiB object. No changes to production sources or dependency publications were made, so Fullhouse consumer verification is still a required step when implementing runtime or generator changes. Finite review and tests cannot establish that no defects remain.

## Findings

### F01 Unary authentication processor is skipped

**P1.** [server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:44](/Users/mikeroberts/workspace/latenighthack/ktbuf/server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:44)

**Reproduced over HTTP.** `serveAll` forwards `contextProcessor` to streaming routes but omits it when registering unary routes. A processor that always throws `UNAUTHENTICATED` ran zero times; the unary handler returned a successful response with `fInt32=42`.

Applications relying on this callback for authentication, tenant selection, or authorization expose unary methods without those checks. Pass the processor to `serveUnary` and authenticate before decoding the body. Cover every RPC shape with an unauthenticated request.

### F02 Published HTTP dependencies include known vulnerabilities

**P1.** [gradle/libs.versions.toml:5](/Users/mikeroberts/workspace/latenighthack/ktbuf/gradle/libs.versions.toml:5)

**Verified dependency resolution and advisory ranges.** The JVM server graph resolves `okhttp:4.9.1` and `okio:2.8.0`. OkHttp is in the affected range for CVE-2021-0341, involving hostname verification; Okio is in the affected range for CVE-2023-3635, involving malformed gzip processing. The default clients instantiate these libraries without overriding the affected behavior. Exploitation was not attempted.

Update to compatible supported dependencies that address both issues. Upgrading only to OkHttp 4.9.2 addresses the listed TLS issue but is insufficient evidence that its transitive Okio is fixed. Verify the resolved JVM and Android graphs. Sources: [OkHttp advisory](https://github.com/advisories/GHSA-3cqm-mf7h-prrj), [maintainer TLS fix](https://github.com/lysine-dev/okhttp/commit/f574ea2f5259d9040f264ddeb582fb1ce563f10c), [Okio advisory](https://github.com/advisories/GHSA-w33c-445m-f8w7), [maintainer gzip fix](https://github.com/lysine-dev/okio/commit/81bce1a30af244550b0324597720e4799281da7b).

### F03 Reads escape logical message boundaries

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/ConcreteLinkedByteArray.kt:64](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/ConcreteLinkedByteArray.kt:64); [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:156](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:156)

**Reproduced on JVM, Android, JS, and macOS.** Buffer reads validate the backing array implicitly, not the number of unread input bytes. `3d01` decodes a truncated fixed32 as 1. `7a0401` reads beyond the supplied bytes instead of rejecting the incomplete payload. Nested views also forward unchecked reads into their parent: `8a01020a040807` lets an inner string consume outer-message bytes and zero-filled capacity.

Lengths use `readInt32()` without checking overflow or available bytes; `728080808010` wraps a claimed 2^32-byte length to zero. Negative lengths can move cursors backward. Check lengths before allocation or slicing, use overflow-safe arithmetic, and bound every read and advance by the current scope. Reused buffers can otherwise expose stale data from previously written bytes.

### F04 Malformed varints and tags are accepted

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:185](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:185); [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:65](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:65)

**Reproduced.** A missing scalar payload (`08`) becomes zero. An unterminated value (`0880`) becomes 16384. An eleven-byte varint is accepted. Field zero (`0000`) and invalid wire type 6 (`0e00`) are accepted. Tags are narrowed to Int before their legal range is checked.

The reference runtime rejects those fixtures. Distinguish clean end of a message from a truncated value, require a terminating varint within the legal width, validate tag range and field number, and reject wire types 6 and 7. Ensure malformed input cannot be normalized into an apparently valid generated message.

### F05 Generated decoders ignore wire types for known fields

**P1.** [templates/decoder.kt.tmpl:18](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:18); [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:54](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:54)

**Reproduced.** Dispatch uses only `currentFieldNumber`; the wire type is private to `ScopedProtobufReader`. `0a0101`, a length-delimited occurrence of field 1, sets the int32 field to 1. The reference parser preserves it as an unknown field and leaves the int32 unset.

Expose validated wire information to generated decoders, dispatch on the complete tag or expected wire types, and preserve mismatched known-number fields as unknown. Numeric repeated fields need to recognize both packed and expanded forms.

### F06 Unknown groups inject fields into the enclosing message

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:78](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:78)

**Reproduced.** `skipField` has no group handling and no rejecting default branch. The valid unknown group `a3060863a406` contains field 1 inside group 100. Ktbuf decodes that inner field as the enclosing message's `fInt32=99`; the reference leaves `fInt32=0`.

Consume groups through their matching end tag while respecting depth and field-number matching. Reject unmatched end groups and invalid wire types. An unrecognized container must never expose its contents to the outer decoder.

### F07 Parsing lacks depth and work limits

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:156](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:156); [templates/decoder.kt.tmpl:49](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:49)

**Reproduced resource amplification; limits also absent in source.** A 2,936-byte recursive message with 1,000 levels triggers `StackOverflowError` with a 256 KiB JVM stack. Generated repeated decoders append using `list + element`, copying the entire prefix each time. With a 128 MiB heap, 40,000 scalar elements took about 194 ms and 80,000 about 744 ms in the recorded run. Unknown fields repeatedly concatenate arrays and allocate a fresh default-sized buffer per skipped field.

Use bounded recursion, total bytes and element/work budgets, mutable accumulation with one final freeze, and efficient unknown-field storage. Unary server bodies are fully received and decoded before the per-method processor. External application limits can mitigate exposure, but the runtime currently supplies none of these controls.

### F08 Streaming open interceptors never run

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/net/RpcClient.kt:145](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/net/RpcClient.kt:145)

**Reproduced.** `StreamingRpcInterceptor.interceptOpen` is declared but never called. `InterceptingRpcClient` only wraps send and receive. Its open callback also accepts headers, but the transport's streaming API offers no header parameter to carry them.

Authentication or policy enforcement installed in the advertised open interceptor is silently absent. Execute the open chain and carry its transformed method and headers into the handshake; test actual handshake headers and denied opens.

### F09 JVM streams hang at completion and leak after cancellation

**P1.** [rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:96](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:96); [rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:121](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:121)

**Reproduced over WebSocket.** Collecting a two-response server stream with `toList()` never completes and exceeds a 1.5-second deadline. `receiveCatching().getOrThrow()` on a normally closed channel throws an IllegalStateException, while the generated marshalling helper catches ClosedReceiveChannelException. The detached block records the exception without closing its channels on the exceptional path, leaving the outbound loop blocked. A cancelled transport call also leaves the remote socket open. The inbound channel is unlimited, so a fast peer can accumulate frames while the consumer is slow. JVM and Android also ignore the Boolean returned by WebSocket.send, hiding rejected sends.

Use structured child coroutines, explicit successful end-of-stream versus failure, and unconditional channel/socket cleanup in `finally`. Propagate block errors promptly. Add complete-stream tests without `take(n)`, cancellation tests, handshake failures, and failures before any request is sent.

### F10 HTTP response read failures strand the caller

**P1.** [rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:53](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.jvm.kt:53); [rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.jvm.kt:51](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jvmMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.jvm.kt:51)

**Reproduced.** A loopback response promises ten bytes, sends one, and disconnects. `response.body.bytes()` throws inside OkHttp's `onResponse`, outside any catch that resumes the continuation. The caller waits until its own timeout instead of receiving IOException. The same pattern occurs in both HTTP classes and their Android implementations; response cleanup is also skipped when reading throws.

Wrap callback body processing in `response.use`, catch read and mapping errors, resume the continuation exceptionally exactly once, and handle cancellation races. Test truncated success and error bodies, malformed gzip, and cancellation during a body read.

### F11 Completing a bidi request flow drops responses

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/GrpcService.kt:186](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/GrpcService.kt:186)

**Reproduced across common targets.** A finite request flow sends values 1, 2, 3 to the echo transport but returns only response 1. When requests finish, the sender sets `keepGoing=false` and closes the inbound channel, ending response consumption even when replies are still pending.

Track request completion separately from response completion. Define the transport's half-close contract and drain all responses until the server finishes. Bound the currently unlimited request queue so a slow or unopened transport cannot accumulate an arbitrary request flow.

### F12 Bidi receive failures are silently suppressed

**P1.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/GrpcService.kt:212](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/GrpcService.kt:212)

**Reproduced.** A transport receive that throws `PERMISSION_DENIED` makes the generated bidi flow complete successfully with an empty list. The catch-all ignores every exception, including transport failures and cancellation. Because the original request collector is a sibling, a live request flow can also keep collecting or wait indefinitely after the receive side ends.

Propagate failures, preserve cancellation, and cancel and join both producer and sender when response processing ends. Only treat an explicitly successful EOF as normal completion.

### F13 Android streaming delivery and cleanup diverge from JVM

**P1.** [rpc/src/androidMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.android.kt:115](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/androidMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.android.kt:115); [rpc/src/androidMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.android.kt:175](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/androidMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.android.kt:175)

**Source-confirmed; Android transport itself was not exercised on a device.** Every frame launches a separate GlobalScope coroutine to send into a rendezvous channel. Concurrent jobs can reorder frames; closing the channel can overtake pending deliveries and discard final messages. The main outbound loop uses throwing `receive()`, block completion does not close channels, and there is no enclosing socket cleanup. It also ignores `useApiGateway` for streaming.

Port the validated lifecycle contract to Android, preserve order at the callback boundary, bound queued data, and cover immediate close after final frames and collection cancellation. Android unit coverage of the common codec does not test this implementation.

### F14 JS streaming silently loses messages and requests

**P1.** [rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:85](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:85); [rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:138](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:138)

**Source-confirmed; live browser transport not exercised.** Incoming callbacks use `trySend` on a rendezvous channel and ignore failure. Any frame arriving while no receiver is suspended is dropped, which occurs during ordinary processing of the preceding response. Separate FileReader operations can reorder Blob frames. Before the socket opens, `send` returns immediately and replaces a single `doOnReady` slot, dropping all but the last queued request.

Use an ordered bounded queue, await handshake readiness for sends, select a consistent binary representation, and check delivery results. Close the WebSocket in `finally`; the current method only calls `stream.block()` and leaves the connection open when it returns or throws.

### F15 Message map fields silently disappear

**P1.** [templates/encoder.kt.tmpl:41](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/encoder.kt.tmpl:41); [templates/decoder.kt.tmpl:47](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:47)

**Generated, compiled, and reproduced.** `map<string,V>` compiles, but an instance containing key `a` and `V(id=7)` encodes to zero bytes. Both map template branches are empty. Decoding the reference map bytes produces an empty typed map and nine bytes of unknown fields. Scalar-valued maps fail generation through a nil pointer in the builder template.

Implement map entry encoding, parsing, and builders, including defaults and duplicate-key semantics. Until supported, reject every map schema explicitly instead of generating an API that loses populated data.

### F16 Require patches erase existing messages

**P1.** [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:292](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:292); [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:136](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:136)

**Reproduced on JVM and macOS.** `Change.Require` writes an empty message when a field exists and writes nothing when it is absent. Applying Require to an existing inner message erases its contents. Editing one property through the nested editor also destroys existing sibling fields. `createDefault` appends the terminal component again, constructing the wrong path.

Make Require idempotent: preserve an existing field verbatim and create an empty container only when missing. Correct scoped path construction and test editing an existing nested message with untouched sibling values.

### F17 Patch traversal corrupts unrelated fields and can loop forever

**P1.** [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:169](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:169)

**Reproduced.** Creating an inner field in a message that already has unrelated root fields throws an array bounds exception. The not-found nested branch calls `readField` on the current unrelated field rather than copying it and creating a missing container. Traversal ignores the Boolean returned by `nextField` and requires `isByteAvailable` after reading a tag, misclassifying zero-length payloads. Removing occurrence index 2 from wire `0801` loops indefinitely; the isolated process was terminated after two seconds.

Use a progress-guaranteed traversal, explicitly handle EOF and absent occurrences, validate indices and paths, and preserve unrelated fields before creating containers. Define remove and replace behavior when the requested occurrence is absent.

### F18 Retry helpers ignore limits and retry cancellation

**P1.** [rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:90](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:90); [rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:154](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:154)

**Reproduced.** Flow retry with `retryLimit=0` made six source attempts; the predicate always returns true, attempts are never incremented, and no delay is applied. Repeat retry with limit 1 never invokes the initial task. CancellationException is retried four times with limit 5. A five-millisecond time limit still runs five thirty-millisecond tasks because it only clamps each wait, rather than measuring a deadline. At default retry limits, cancellation can produce a very large tight loop.

Make cancellation terminal, define initial-attempt versus retry counting, use a monotonic total deadline, honor backoff and reset semantics, and allocate retry state per collection. Validate numeric limits before narrowing Long durations to Int.

### F19 Native byte copying narrows an unchecked length

**P1.** [rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:210](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:210)

**Source-confirmed; no oversized allocation was attempted.** `NSData.toByteArray` allocates using `length.toInt()` but calls memcpy with the original ULong length. For a length of 2^32+1, the destination is one byte while the copy length is over four GiB. Other overflowing lengths throw or incorrectly return an empty array.

This is an unsafe-memory boundary, even though typical streaming payloads are capped far below it. Reject lengths above Int.MAX_VALUE before allocation and pass the validated length to memcpy. Bound unary response bodies as well; their Foundation data task has no matching message-size limit.

### F20 Expanded repeated scalars are not decoded

**P2.** [templates/decoder.kt.tmpl:54](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:54); [templates/encoder.kt.tmpl:49](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/encoder.kt.tmpl:49)

**Reproduced.** Reference wire `900101900102` represents field 18 values 1 and 2, but ktbuf returns `[18448]`. Every repeated numeric branch assumes a length-delimited payload. The encoder also decides packing solely from scalar type and ignores a field's explicit `[packed=false]` option.

Accept packed and expanded occurrences regardless of the declared packing option, and honor that option when writing. Cover all numeric widths, bools, enums, mixed packed/expanded occurrences, and multiple packed segments. The [protobuf encoding guide](https://protobuf.dev/programming-guides/encoding/#repeated-elements) requires parsers to accept both forms.

### F21 Duplicate embedded messages overwrite instead of merge

**P2.** [templates/decoder.kt.tmpl:26](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:26); [templates/decoder.kt.tmpl:43](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/decoder.kt.tmpl:43)

**Reproduced.** Concatenating an inner message containing `str=a` with another containing `anInt=7` produces `str="", anInt=7`. The earlier message is discarded. Repeated occurrences of the same message-valued oneof member have the same problem.

Parse into or merge with the existing message builder while preserving presence information. Repeated values should concatenate, scalar occurrences should take the later value, and a change to a different oneof case should replace the previous case. See the [protobuf merge rules](https://protobuf.dev/programming-guides/encoding/#last-one-wins).

### F22 Nonzero boolean values decode as false

**P2.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:164](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:164)

**Reproduced and reference-checked.** Field 13 encoded with value 2 (`6802`) becomes false; the reference runtime returns true. `readBool` compares the varint to exactly 1.

Decode nonzero as true and replace the characterization test that currently asserts the incorrect behavior. Include expanded and packed booleans.

### F23 Invalid protobuf UTF8 is silently replaced

**P2.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:166](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/ProtobufInputStream.kt:166)

**Reproduced and reference-checked.** A string containing byte FF (`7201ff`) is accepted with replacement characters; the reference rejects it. `decodeToString` uses its permissive default.

Validate UTF8 strictly for protobuf strings and surface a parse failure. Bytes fields should remain arbitrary binary data. Include invalid continuation sequences and truncated multibyte characters.

### F24 Serialization loses negative floating point zero

**P2.** [templates/encoder.kt.tmpl:73](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/encoder.kt.tmpl:73)

**Reproduced.** `AllTypes(fFloat=-0.0f)` serializes to an empty message and returns positive zero. The reference emits `5d00000080`. Comparing floating fields to `0.0` treats both signs as the default.

Check raw bits when omitting implicit float and double defaults. Preserve negative zero, infinities, and NaN payload behavior consistently across targets.

### F25 Generated equality and hash contracts are inconsistent

**P2.** [templates/data.kt.tmpl:25](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/data.kt.tmpl:25); [templates/data.kt.tmpl:164](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/data.kt.tmpl:164); [templates/data.kt.tmpl:168](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/data.kt.tmpl:168)

**Reproduced.** An object with null unknown fields equals one with nonnull unknown fields, while the reverse comparison is false. Repeated bytes use content equality but identity-based list element hashes. Bytes-valued oneof cases use array identity equality. Messages with positive and negative float zero can compare equal but have different hashes on JVM and Native. Enum equality throws ClassCastException against a String, and accepts unrelated enum types with the same numeric value.

Use symmetric nullable byte-array equality, matching content hashes for lists and oneofs, one floating-point equality policy, and checked enum type comparisons. These failures break sets, map lookup, deduplication, and change detection. JS did not fail the signed-zero hash probe, illustrating target differences.

### F26 Byte buffer cursor and utility contracts disagree

**P2.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/ConcreteLinkedByteArray.kt:37](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/ConcreteLinkedByteArray.kt:37); [library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/LinkedByteArray.kt:15](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/LinkedByteArray.kt:15)

**Reproduced.** After reading input `0801`, adding `0802` returns the old value 1 again. Insert and write defaults use unread `size` as an absolute destination offset, while reads use `tipOffset`. A subarray's `reset` sets progress to its length, exhausting it instead of rewinding. Default `copyTo` does not subtract destinationOffset from destination capacity and overruns a two-byte destination when copying at offset 1. Concrete `toByteArray` includes consumed bytes while a subarray returns its unread range.

Specify cursor-relative versus absolute operations, separate buffer capacity from valid data, and make append, rewind, copy, and view extraction consistent. Validate all offsets and lengths before mutation.

### F27 Valid schemas produce Kotlin compilation failures

**P2.** [kt/imports.go:49](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/kt/imports.go:49); [kt/names.go:52](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/kt/names.go:52); [kt/names.go:251](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/kt/names.go:251)

**Reproduced with real protoc and Kotlin 2.3.10 consumer compilation.** Cases include fields named `writer`, `reader`, and `unknown_fields`; lowercase nested message names; lowercase enum values; missing `java_package`; references from package `probe` to `probe.sub`; service-only external input/output references; and repeated references to nested external messages.

Reserve generated symbols, qualify receiver properties, normalize names consistently, fall back to the proto package when appropriate, compare package identities rather than string prefixes, and collect imports from service signatures. Imported nested-message extensions live in the package, not on the enclosing class. Keep each fixture as a generation-plus-compilation regression.

### F28 Enum and service declaration files are skipped

**P2.** [kt/module.go:108](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/kt/module.go:108)

**Reproduced.** Files without messages are skipped by every generator module. An enum-only schema succeeds but produces no enum. A service-only schema importing its request message generates only the imported message files; its own service is missing, and a consumer referring to `SService` fails compilation.

Make each template's applicability depend on the declarations it handles. Test enum-only files, service-only files, and imported declarations independently.

### F29 Proto2 semantics are accepted but not implemented

**P2.** [kt/util.go:45](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/kt/util.go:45); [templates/encoder.kt.tmpl:73](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/encoder.kt.tmpl:73)

**Generated, compiled, and reproduced.** A proto2 message with required `id` and optional `count [default=7]` parses an empty body as `id=0,count=0` and serializes it successfully. The declared default, required-field validation, and explicit presence are absent. Proto3 `optional` is explicitly rejected by the pinned plugin instead.

Implement the advertised syntax and presence contract or fail generation clearly for unsupported syntax and features. Do not accept proto2 and silently apply proto3 implicit-default behavior. See the [presence guide](https://protobuf.dev/programming-guides/field_presence/).

### F30 Client streaming with a unary result is unusable

**P2.** [templates/service.kt.tmpl:34](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/service.kt.tmpl:34); [server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:39](/Users/mikeroberts/workspace/latenighthack/ktbuf/server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:39)

**Consumer compilation failure reproduced; server cast confirmed in source.** The generated client calls `clientStreamServerUnary`, which does not exist. The server dispatches a method with streamingIn=true and streamingOut=false to `serveStreamingInOut`, which casts its ClientStreaming handler to ClientServerStreaming.

Implement the fourth RPC shape with correct request completion and one response, or reject such service schemas clearly. The conformance fixture currently omits this shape to keep builds green.

### F31 URL parameters and gateway behavior lose information

**P2.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/net/RpcClient.kt:8](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/net/RpcClient.kt:8); [rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:73](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:73)

**Query construction and gateway parameter loss reproduced; platform differences confirmed in source.** Parameters are concatenated without escaping. Value `a&admin=true#fragment` introduces another query parameter and a URL fragment instead of remaining one token. `toApiGatewayPath` discards every additional parameter. Apple and JS ignore the gateway flag for unary calls; Android ignores it for streams. JVM, Android, and JS prepend `http://` to an already supplied `http://` base URL, while Apple expects an actual URL.

Parse base URLs once, encode path/query components, retain parameters in gateway mode, and define one constructor contract across platforms. Test token values containing ampersands, plus signs, percent signs, equals signs, and Unicode.

### F32 Status information does not survive transport

**P2.** [library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/Status.kt:36](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/proto/Status.kt:36); [server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:115](/Users/mikeroberts/workspace/latenighthack/ktbuf/server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:115)

**Ambiguous unary mapping reproduced; remaining mappings confirmed in source.** `FAILED_PRECONDITION` with an ordinary error message becomes `INVALID_ARGUMENT`. The server sends only the message body, while the client searches that body for ad hoc code markers. `Codes.from` returns ABORTED for every value at or below 16 and UNKNOWN otherwise, including CLIENT_TIMEOUT=100. JS feeds raw WebSocket close codes into it; Apple feeds unrelated NSError codes. Streaming server errors all close with 1011, and JVM/Android discard the close code and reason.

Carry an explicit protocol status independently of human-readable text. Map each transport's status domain deliberately and preserve the cause. A normal close must complete; denied and transient failures must retain different retry policies.

### F33 JS response headers are parsed into empty values

**P2.** [rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:36](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:36); [rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.js.kt:21](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.js.kt:21)

**Source-confirmed.** `split(":", limit=1)` never produces a value segment. HttpRpcClient returns keys containing entire header lines and empty values. Header-dependent interceptors cannot recover ordinary response metadata.

Split at the first colon into two components, trim whitespace, handle empty trailing lines and case-insensitive names, and define repeated-header behavior. The generic JS HTTP client also computes the same broken map, although it does not return it.

### F34 Apple and JS operations do not honor coroutine cancellation

**P2.** [rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:72](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:72); [rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:54](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/jsMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.js.kt:54)

**Source-confirmed; cancellation of these live platform transports was not reproduced.** Unary and callback-based operations use `suspendCoroutine` rather than cancellable continuations. Cancelling a timeout does not abort Foundation data tasks or XHR. Apple WebSocket close methods are empty and readiness fires immediately after resume, before handshake success. An unexpected text frame reaches `message?.data!!`, allowing an exception to escape the Foundation callback. JS closes discard their original exception in `receive()` and Node paths call browser-only window/FileReader APIs; Node unary calls require XMLHttpRequest without a provided implementation.

Tie native tasks, XHR, receives, and sends to cancellation; resume failures through continuations; handle or explicitly reject text frames; signal readiness after a successful open; and define which JS environments the transport actually supports.

### F35 Native timer disposal does not cancel the timer

**P2.** [rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:37](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/appleMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.apple.kt:37)

**Source-confirmed.** MainQueueDispatcher's DisposableHandle.dispose leaves its assignment commented out, so disposed timeout callbacks still execute. The delay-to-nanoseconds multiplication can also overflow for very large durations.

Cancel the scheduled work or atomically record disposal, and validate or saturate time conversion. This public dispatcher is currently unused by HttpRpcClient, so its defect should not be attributed to the existing RPC call path.

### F36 Linux transports are published as nonfunctional stubs

**P2.** [rpc/src/linuxMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.linux.kt:9](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/linuxMain/kotlin/com/latenighthack/ktbuf/rpc/HttpRpcClient.linux.kt:9); [rpc/src/linuxMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.linux.kt:3](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/linuxMain/kotlin/com/latenighthack/ktbuf/net/HttpClient.linux.kt:3)

**Source-confirmed.** Linux unary RPC and HTTP calls throw NotImplementedError, while streaming RPC returns immediately without calling its block or readiness callback. The project declares and publishes the Linux target.

Implement the transport or make unsupported operations fail explicitly and document support. A streaming no-op can look like a successful empty result. Add platform transport tests beyond common wire-codec coverage.

### F37 Server failures expose internal exceptions and swallow cancellation

**P2.** [server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:59](/Users/mikeroberts/workspace/latenighthack/ktbuf/server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:59); [server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:84](/Users/mikeroberts/workspace/latenighthack/ktbuf/server/src/main/kotlin/com/latenighthack/ktbuf/server/GrpcService.kt:84)

**Source-confirmed.** Unexpected unary handler errors return `t.toString()` to the caller, disclosing exception details that can include internal paths, query contents, or implementation information. Request parsing occurs outside the error-mapping try block. Catch-all Throwable handlers also catch cancellation and attempt to send errors or close reasons.

Separate input errors from internal failures, return a generic internal status, log details server-side with an appropriate policy, and rethrow cancellation. Move decoding into a controlled error boundary after authentication and enforce request limits.

### F38 List editors do not faithfully record mutations

**P2.** [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/EditorList.kt:22](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/EditorList.kt:22)

**Reproduced.** Setting list[0] changes the editor's value but records no patch. `addDistinct` does nothing. Invalid removeAt records a change before throwing. `remove(element)` removes one backing-list occurrence but emits RemoveDistinct, whose runtime removes every matching occurrence, so duplicate lists diverge from their patches. The public RemoveAll and InsertDistinct change variants throw TODO errors.

Make each supported list operation produce the same result locally and when its changes are applied. Validate before recording, define duplicate semantics, and implement or explicitly remove unsupported operations.

### F39 Distinct patch removal ignores scope indices and match selectors

**P2.** [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:234](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:234); [patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:308](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/src/commonMain/kotlin/com/latenighthack/ktbuf/patch/Patch.kt:308)

**Reproduced.** A path selecting occurrence 1 of container field 10 removes the matching value from both occurrences. The traversal descends into every matching field and ignores the path component's index. `matchPath` and `matchMessage` are accepted but never used; Replace also ignores `skipFieldNumbers`.

Respect every path component and selector, or reject unsupported options before mutation. Do not silently broaden a scoped removal to sibling containers. Include repeated nested containers and protected replacement fields.

### F40 Atomic updates are not atomic

**P2.** [rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:30](/Users/mikeroberts/workspace/latenighthack/ktbuf/rpc/src/commonMain/kotlin/com/latenighthack/ktbuf/rpc/Backoff.kt:30)

**Reproduced with controlled concurrent JVM threads.** Eight simultaneous getAndUpdate increments leave the value at 1 rather than 8. The helper separately reads, computes, and writes an AtomicReference without a compare-and-set loop. It returns the new value, unlike the conventional getAndUpdate contract; that contributes to retry counting errors.

Expose a real atomic update primitive for each concurrent platform, document its return semantics, and align callers with it. Include an interleaving test for updates; atomic getters and setters alone do not protect a compound operation.

### F41 Test utilities suppress failures and leak servers on failed tests

**P2.** [test/src/main/kotlin/com/latenighthack/ktbuf/test/rpc/StubRpcClient.kt:43](/Users/mikeroberts/workspace/latenighthack/ktbuf/test/src/main/kotlin/com/latenighthack/ktbuf/test/rpc/StubRpcClient.kt:43); [test/src/main/kotlin/com/latenighthack/ktbuf/test/server/TestServer.kt:16](/Users/mikeroberts/workspace/latenighthack/ktbuf/test/src/main/kotlin/com/latenighthack/ktbuf/test/server/TestServer.kt:16)

**Error-only typed response reproduced; other behavior confirmed in source.** TypedUnaryHandler discards any result whose response is null, including an error-only result. MatchingTypedUnaryHandler parses a request and emits it before checking its method; an unrelated handler can decode another schema and interfere with request observations. StubRpcClient's streaming method is empty. `runTestWithServer` calls stop only after the runner returns successfully, so an assertion or timeout leaves the server running. defaultServer waits for an extension job with join, which does not rethrow that job's failure.

Preserve typed errors, match before parsing, make unsupported stub calls explicit, stop servers in `finally`, and surface setup failures before announcing readiness.

### F42 Code generation is not tracked as a tool input

**P2.** [conformance/build.gradle.kts:26](/Users/mikeroberts/workspace/latenighthack/ktbuf/conformance/build.gradle.kts:26); [patch/build.gradle.kts:30](/Users/mikeroberts/workspace/latenighthack/ktbuf/patch/build.gradle.kts:30)

**Source-confirmed.** The generation tasks declare only the proto input directory and output directory. Their protoc and protoc-gen-kt executables come from PATH; changing either executable can leave generated Kotlin up-to-date or restore outputs from an incompatible cache entry. CI pins plugin v0.1.0, but build failure instructions recommend @latest, creating a different consumer path.

Pin or explicitly configure the generator and protoc, record their versions or executable checksums as task inputs, and ensure imported schema changes invalidate output. Keep runtime and generator release versions separate and add compatibility testing between supported pairs.

### F43 CI and release gates miss the failing behavior

**P2.** [.github/workflows/gradle.yml:24](/Users/mikeroberts/workspace/latenighthack/ktbuf/.github/workflows/gradle.yml:24); [.github/workflows/release.yml:41](/Users/mikeroberts/workspace/latenighthack/ktbuf/.github/workflows/release.yml:41); [.github/workflows/deploy.yml:25](/Users/mikeroberts/workspace/latenighthack/ktbuf/.github/workflows/deploy.yml:25)

**Source-confirmed and contrasted with fresh baseline results.** The conformance schema intentionally omits maps, optional fields, and client-streaming unary calls. Characterization tests assert the bool and Codes.from bugs. Bidi fixtures deliberately leave requests open, and network stream tests use take(n), hiding request completion and EOF defects. CI does not run patch tests or the Apple transport regression; its comment that patch cannot compile is stale. The tag release job publishes without a test job dependency. The legacy deploy workflow requests Sonatype tasks absent from the current wrapper task inventory.

Promote the defensive probes into regression coverage, add negative schema compilation tests and real platform transport tests, gate releases on successful validation, and remove or repair the obsolete deployment workflow.

### F44 Value projection and Base64 contracts are incomplete

**P3.** [templates/data.kt.tmpl:74](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/data.kt.tmpl:74); [templates/data.kt.tmpl:119](/Users/mikeroberts/go/pkg/mod/latenighthack.com/protoc-gen-kt@v0.1.0/templates/data.kt.tmpl:119); [library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/Base64.kt:3](/Users/mikeroberts/workspace/latenighthack/ktbuf/library/src/commonMain/kotlin/com/latenighthack/ktbuf/bytes/Base64.kt:3)

**Source-confirmed quality gaps.** Oneof case toValue returns an empty map; its containing message converts the whole choice to a String. Maps are omitted. Repeated bytes are left as raw arrays while singular bytes become Base64, making the projection inconsistent. Base64 uses a URL-safe alphabet without padding, while its generic names do not advertise that choice; decoding silently skips invalid characters instead of reporting malformed input.

Define whether toValue is a diagnostic projection or a data interchange API, preserve the selected oneof member and map values, make bytes representation consistent, and name/document the Base64 variant. Add strict decoding where callers need validation. Do not describe this projection as ProtoJSON without implementing its full contract.

## Repair order and verification

1. Repair the unary processor forwarding and authentication ordering. Add denial tests for every route shape. Update and verify both vulnerable resolved dependencies.
2. Give the reader strict scopes, lengths, tags, UTF8, and resource budgets. Add wire-type awareness to the generated decoder. Keep reference-wire tests independent of the implementation and replace tests that currently preserve incorrect behavior.
3. Define streaming readiness, ordering, successful EOF, failure, half-close, and cancellation consistently. Implement that contract on JVM, Android, JS, and Apple; include finite bidi requests and full collection to EOF.
4. Fix map support or reject it, then generator compilation, merging, packing, equality, and syntax/presence semantics. Change the source generator in its own checkout; do not patch emitted bindings.
5. Repair patch traversal and editor fidelity, retry semantics, native byte-copy validation, and the remaining utility and platform gaps. Give unsupported operations explicit failure behavior.
6. Gate CI and releases on the expanded coverage. Validate changed-library tasks and generated consumer compilation separately. For paired Fullhouse verification, follow `./fh deps resolve` and `./fh deps publish --library NAME` with the isolated ignored workspace manifest; use distinct versions for distinct artifact bytes. Exercise affected JVM, Android, JS, and Apple consumers before release.

## Reproduction artifacts

The adjacent files preserve assertions of correct behavior, not assertions that endorse the bugs:

- [reproduce.py](reproduce.py) installs and runs the Kotlin probes in an isolated checkout.
- [probes.patch](probes.patch) and [probes](probes/) contain only additional fixtures and tests.
- [generator_review.py](generator_review.py) generates and compiles the 19 schema cases using cached Kotlin 2.3.10 dependencies and the installed plugin.
- [resource_review.py](resource_review.py) checks map/proto2 behavior, missing declarations, recursion, repeated-field cost, and patch nontermination in bounded subprocesses.
- [test-results.json](test-results.json), [generator-results.json](generator-results.json), [reference-results.json](reference-results.json), [resource-results.txt](resource-results.txt), and [dependency-results.json](dependency-results.json) record the evidence.
- [baseline.log](baseline.log) records the fresh positive test run.

From the repository root, choose an unused isolated directory:

```sh
git worktree add --detach /tmp/ktbuf-review-reproduction d2073ba
# Configure ANDROID_HOME or the worktree's ignored local.properties if running Android probes.
python3 review/2026-10-08/reproduce.py /tmp/ktbuf-review-reproduction
python3 review/2026-10-08/generator_review.py /tmp/ktbuf-review-reproduction
python3 review/2026-10-08/resource_review.py /tmp/ktbuf-review-reproduction
```

The first command running the defensive Kotlin suite is expected to exit unsuccessfully against the audited version. Add `--platforms` to reproduce.py for the additional common-code platform runs. Generator and resource scripts print each result and save their detailed output beneath the isolated checkout's build directory. Install the pinned `protoc-gen-kt@v0.1.0` and protoc on PATH; run the Gradle probe compilation first so the consumer classpaths exist.
