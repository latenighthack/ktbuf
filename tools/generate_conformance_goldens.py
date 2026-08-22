#!/usr/bin/env python3
"""Emit canonical goldens for the :conformance generated messages.

Compiles conformance.proto with protoc's Python backend and serializes fixtures with
the reference protobuf runtime. The resulting bytes are what a real protobuf peer puts
on the wire, so the Kotlin tests are asserting interoperability rather than agreeing
with themselves.

Requires: protoc on PATH, `pip install protobuf`.

Usage:
    python3 tools/generate_conformance_goldens.py

Writes conformance/src/commonTest/kotlin/.../ConformanceGoldens.kt
"""

import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PROTO_DIR = REPO / "conformance/src/commonMain/proto"
OUT = REPO / ("conformance/src/commonTest/kotlin/com/latenighthack/ktbuf/"
              "conformance/ConformanceGoldens.kt")

INT32_MIN, INT32_MAX = -2**31, 2**31 - 1
INT64_MIN, INT64_MAX = -2**63, 2**63 - 1
UINT32_MAX, UINT64_MAX = 2**32 - 1, 2**64 - 1


def load_bindings(tmp):
    subprocess.run(
        ["protoc", f"--python_out={tmp}", "-I", str(PROTO_DIR),
         str(PROTO_DIR / "conformance.proto")],
        check=True,
    )
    sys.path.insert(0, tmp)

    import conformance_pb2

    return conformance_pb2


GOLDENS = []


def add(name, message, comment):
    GOLDENS.append((name, message.SerializeToString(), comment))


def build(p):
    A = p.AllTypes

    add("Empty", A(), "AllTypes with every field at its proto3 default -> zero bytes")

    add("Int32Only", A(f_int32=42), "f_int32 = 42")
    add("Int32Negative", A(f_int32=-1), "f_int32 = -1 (sign-extended to 10 bytes)")
    add("Int32Min", A(f_int32=INT32_MIN), "f_int32 = Int.MIN_VALUE")
    add("Int32Max", A(f_int32=INT32_MAX), "f_int32 = Int.MAX_VALUE")

    add("Int64Min", A(f_int64=INT64_MIN), "f_int64 = Long.MIN_VALUE")
    add("Int64Max", A(f_int64=INT64_MAX), "f_int64 = Long.MAX_VALUE")

    add("Uint32Max", A(f_uint32=UINT32_MAX), "f_uint32 = UInt.MAX_VALUE")
    add("Uint64Max", A(f_uint64=UINT64_MAX), "f_uint64 = ULong.MAX_VALUE")

    add("Sint32Negative", A(f_sint32=-1), "f_sint32 = -1 (zigzag -> 1)")
    add("Sint32Min", A(f_sint32=INT32_MIN), "f_sint32 = Int.MIN_VALUE")
    add("Sint64Negative", A(f_sint64=-1), "f_sint64 = -1")
    add("Sint64Min", A(f_sint64=INT64_MIN), "f_sint64 = Long.MIN_VALUE")

    add("Fixed32Max", A(f_fixed32=UINT32_MAX), "f_fixed32 = UInt.MAX_VALUE")
    add("Fixed64Max", A(f_fixed64=UINT64_MAX), "f_fixed64 = ULong.MAX_VALUE")
    add("Sfixed32Negative", A(f_sfixed32=-1), "f_sfixed32 = -1")
    add("Sfixed64Negative", A(f_sfixed64=-1), "f_sfixed64 = -1")

    add("FloatOne", A(f_float=1.0), "f_float = 1.0")
    add("FloatNegative", A(f_float=-2.5), "f_float = -2.5")
    add("FloatNan", A(f_float=float("nan")), "f_float = NaN")
    add("FloatPosInf", A(f_float=float("inf")), "f_float = +Infinity")
    add("FloatNegInf", A(f_float=float("-inf")), "f_float = -Infinity")

    add("DoubleOne", A(f_double=1.0), "f_double = 1.0")
    add("DoubleNegative", A(f_double=-2.5), "f_double = -2.5")
    add("DoubleNan", A(f_double=float("nan")), "f_double = NaN")
    add("DoublePosInf", A(f_double=float("inf")), "f_double = +Infinity")

    add("BoolTrue", A(f_bool=True), "f_bool = true")

    add("StringAscii", A(f_string="hello"), "f_string = 'hello'")
    add("StringCjk", A(f_string="你好世界"), "f_string = CJK")
    add("StringEmoji", A(f_string="\U0001f389"), "f_string = emoji (surrogate pair)")

    add("BytesSimple", A(f_bytes=bytes([0x00, 0x01, 0xFF])), "f_bytes = 00 01 FF")

    add("EnumA", A(f_enum=A.Kind.A), "f_enum = A (declared value 3)")
    add("EnumC", A(f_enum=A.Kind.C), "f_enum = C (declared value 5)")

    add("InnerSimple", A(f_inner=A.Inner(str="in", an_int=7)),
        "f_inner = { str='in', an_int=7 }")
    add("InnerNested",
        A(f_inner=A.Inner(str="a", an_int=1,
                          inner_inner=A.InnerInner(str="b", an_int=2))),
        "f_inner with a populated inner_inner")
    add("InnerEmpty", A(f_inner=A.Inner()),
        "f_inner present but empty -> zero-length submessage")

    add("RepeatedInt32", A(r_int32=[1, 2, 3]), "r_int32 = [1,2,3] (packed)")
    add("RepeatedInt32Negative", A(r_int32=[-1, 0, 1]), "r_int32 = [-1,0,1] (packed)")
    add("RepeatedString", A(r_string=["a", "bb", ""]), "r_string = ['a','bb',''] (unpacked)")
    add("RepeatedInner",
        A(r_inner=[A.Inner(an_int=1), A.Inner(an_int=2)]),
        "r_inner = two submessages")

    add("HighField", A(f_high_field=9), "f_high_field (field 2048) = 9")

    # Everything at once: catches field-ordering bugs that per-field tests cannot.
    add("AllPopulated",
        A(f_int32=-1, f_int64=-2, f_uint32=3, f_uint64=4,
          f_sint32=-5, f_sint64=-6, f_fixed32=7, f_fixed64=8,
          f_sfixed32=-9, f_sfixed64=-10, f_float=1.5, f_double=-2.5,
          f_bool=True, f_string="all", f_bytes=b"\x01\x02",
          f_enum=A.Kind.B,
          f_inner=A.Inner(str="i", an_int=11,
                          inner_inner=A.InnerInner(str="ii", an_int=12)),
          r_int32=[13, 14], r_string=["x", "y"],
          r_inner=[A.Inner(an_int=15)],
          f_high_field=16),
        "every field populated, in field-number order")

    # A submessage large enough to force the encoder's nested length back-patch.
    add("LargeInner", A(f_inner=A.Inner(str="z" * 200)),
        "f_inner whose body exceeds 127 bytes (2-byte length prefix)")

    O = p.OneOfHolder

    add("OneOfNone", O(lead=1), "OneOfHolder with lead set and no oneof case")
    add("OneOfInt", O(lead=1, c_int=2), "oneof choice = c_int")
    add("OneOfString", O(lead=1, c_string="s"), "oneof choice = c_string")
    add("OneOfInner", O(lead=1, c_inner=A.Inner(an_int=3)), "oneof choice = c_inner")
    add("OneOfIntZero", O(c_int=0),
        "oneof set to 0 -- explicit presence means it IS written")

    add("EmptyMessage", p.Empty(), "the Empty message -> zero bytes")

    # protoc-gen-kt emits the oneof block before the message's regular fields, so a
    # OneOfHolder with `lead` set serializes out of field-number order. That is legal
    # protobuf -- field order is not significant -- but it does not match what the
    # reference serializer produces, so the encode-direction goldens below capture
    # ktbuf's actual byte order. Each one is verified to parse back, via the reference
    # runtime, to exactly the same message as its canonical counterpart.
    def ktbuf_order(lead, **choice):
        oneof_part = O(**choice).SerializeToString()
        lead_part = O(lead=lead).SerializeToString()
        combined = oneof_part + lead_part

        expected = O(lead=lead, **choice)
        parsed = O()
        parsed.ParseFromString(combined)

        if parsed != expected:
            raise AssertionError(f"ktbuf-ordered bytes for {choice} do not parse back equal")

        return combined

    for label, choice in [
        ("Int", dict(c_int=2)),
        ("String", dict(c_string="s")),
        ("Inner", dict(c_inner=A.Inner(an_int=3))),
    ]:
        GOLDENS.append((
            f"OneOf{label}KtbufOrder",
            ktbuf_order(1, **choice),
            f"oneof {label.lower()} + lead, in the order protoc-gen-kt emits",
        ))


def verify(p):
    """Re-parse every golden with the reference runtime as a self-check."""
    for name, data, _ in GOLDENS:
        cls = p.OneOfHolder if name.startswith("OneOf") else (
            p.Empty if name == "EmptyMessage" else p.AllTypes)
        msg = cls()
        msg.ParseFromString(data)

        # The KtbufOrder goldens deliberately use non-canonical field ordering, so they
        # round-trip semantically rather than byte-for-byte.
        if name.endswith("KtbufOrder"):
            again = cls()
            again.ParseFromString(msg.SerializeToString())
            if again != msg:
                raise AssertionError(f"{name} is not semantically stable")
            continue

        if msg.SerializeToString() != data:
            raise AssertionError(f"{name} is not stable under reparse")


def main():
    with tempfile.TemporaryDirectory() as tmp:
        p = load_bindings(tmp)
        build(p)
        verify(p)

    seen = set()
    for name, _, _ in GOLDENS:
        if name in seen:
            raise AssertionError(f"duplicate golden {name}")
        seen.add(name)

    lines = [
        "package com.latenighthack.ktbuf.conformance",
        "",
        "// GENERATED by tools/generate_conformance_goldens.py -- do not edit by hand.",
        "//",
        "// Produced by serializing conformance.proto fixtures with the reference protobuf",
        "// runtime (protoc --python_out + the google.protobuf library), NOT with ktbuf.",
        "// These constants are therefore a statement about what a real protobuf peer",
        "// sends and accepts.",
        "//",
        "// Regenerate with: python3 tools/generate_conformance_goldens.py",
        "object ConformanceGoldens {",
    ]
    for name, data, comment in GOLDENS:
        lines.append(f"    // {comment}")
        lines.append(f'    const val {name} = "{data.hex()}"')
    lines.append("}")
    lines.append("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines))
    print(f"wrote {len(GOLDENS)} goldens to {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
