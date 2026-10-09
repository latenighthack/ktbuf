# Lockers F33 / F38 — finite protobuf output and byte storage

Baseline output regressions proved the default writer could serialize beyond 16 MiB and scoped length arithmetic silently wrapped Int.MAX_VALUE before writing (`/tmp/ktbuf-output-repro.log`). These repros use bounded allocations (a reusable 1 KiB chunk and a synthetic logical length), not an OOM experiment.

ProtobufOutputLimits defaults to 16 MiB encoded bytes, depth 64, and one million write operations. All nested scopes share its budget. UTF-8 size is counted before encoding/copying strings; byte fields include tag and length overhead in preflight. Buffer growth and nested length-prefix insertion check long arithmetic before allocation or copying. A failed stream remains failed even if its encoder catches the exception, so partial packets cannot escape. Output failures use ProtobufOutputLimitException.

Generic linked byte storage also has a finite 16 MiB default, configurable through additive maxSize overloads. Allocation/growth clamps to that budget and capacity rejection leaves existing data intact (ByteArrayLimitException). Reader compaction now respects a finite physical buffer while preserving incremental/custom-limit behavior. Existing constructor, function and companion encode signatures are retained for compiled consumers.

Seven output regressions cover default bounds, tiny exact limits, nested shared budgets/backpatch expansion, UTF-8 expansion and replacement encoding, explicit storage bounds/huge requested initial buffers, zero budgets, checked integer overflow, depth/work limits, and caught failure poisoning. Full existing wire goldens pass unchanged.

Validation `/tmp/ktbuf-output-platforms.log`: library/RPC JVM and Node suites passed; Android library/RPC compilation passed; iOS simulator library tests and RPC compilation passed. Local immutable publication and Fullhouse/Lockers consumer verification use a new version after this source commit; no external release is authorized.
