# Golden generators

The test suite pins ktbuf's output against **canonical protobuf wire format**. The
constants those tests compare with are generated here, by the reference protobuf
implementation — never by ktbuf itself.

That independence is the point. An encoder and decoder that are wrong in mirrored ways
round-trip perfectly, so a round-trip-only test proves nothing about interoperability.
Comparing against bytes a different implementation produced does.

## Scripts

| Script | Generates | Consumed by |
| --- | --- | --- |
| `generate_goldens.py` | `library/src/commonTest/kotlin/com/latenighthack/ktbuf/WireGoldens.kt` | the low-level `ProtobufWriter`/`ProtobufReader` tests |
| `generate_conformance_goldens.py` | `conformance/src/commonTest/kotlin/com/latenighthack/ktbuf/conformance/ConformanceGoldens.kt` | the generated-message tests in `:conformance` |

`generate_goldens.py` composes fields with `google.protobuf.internal.encoder`
primitives. `generate_conformance_goldens.py` compiles `conformance.proto` with
protoc's Python backend and serializes real message objects, then re-parses every
golden as a self-check.

## Requirements

```sh
pip install protobuf
brew install protobuf          # provides protoc
```

## Regenerating

Run after changing `conformance.proto` or adding fixtures:

```sh
python3 tools/generate_goldens.py
python3 tools/generate_conformance_goldens.py
```

Both files carry a "GENERATED — do not edit by hand" header. Review the diff: a golden
that changes unexpectedly means the wire format moved, which is exactly the signal
these tests exist to raise.

## A note on the `KtbufOrder` goldens

protoc-gen-kt writes a message's `oneof` block before its regular fields, so a
`OneOfHolder` with `lead` set serializes out of field-number order. Field order is not
significant in protobuf and the reference runtime parses those bytes back correctly
(verified during generation), but the output is not byte-identical to what the
reference serializer emits. The `*KtbufOrder` constants capture ktbuf's actual output
for the encode direction; the plain constants capture canonical bytes for the decode
direction.
