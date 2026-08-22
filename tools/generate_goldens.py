#!/usr/bin/env python3
"""Emit canonical protobuf wire bytes as Kotlin golden constants.

The goldens MUST NOT come from ktbuf itself -- a codec whose encoder and decoder
are wrong in mirrored ways round-trips perfectly while producing bytes no other
protobuf implementation can read. Everything here is built from the reference
Python implementation's own encoder primitives (google.protobuf.internal), so the
constants are an independent statement of what the wire format is.

Usage:
    python3 tools/generate_goldens.py

Writes library/src/commonTest/kotlin/com/latenighthack/ktbuf/WireGoldens.kt
"""

import math
import struct
from pathlib import Path

from google.protobuf.internal import encoder, wire_format

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "library/src/commonTest/kotlin/com/latenighthack/ktbuf/WireGoldens.kt"

WT_VARINT = wire_format.WIRETYPE_VARINT
WT_FIXED64 = wire_format.WIRETYPE_FIXED64
WT_LEN = wire_format.WIRETYPE_LENGTH_DELIMITED
WT_FIXED32 = wire_format.WIRETYPE_FIXED32

INT32_MIN, INT32_MAX = -2**31, 2**31 - 1
INT64_MIN, INT64_MAX = -2**63, 2**63 - 1
UINT32_MAX, UINT64_MAX = 2**32 - 1, 2**64 - 1


def tag(field, wt):
    return encoder.TagBytes(field, wt)


def varint(value):
    """Unsigned varint. Callers pass an already-two's-complement value."""
    out = bytearray()
    encoder._EncodeVarint(out.extend, value)
    return bytes(out)


def u64(v):
    """Two's-complement reinterpretation, the way int32/int64 hit the wire."""
    return v & UINT64_MAX


# -- field encoders, each mirroring one ProtobufWriter method -----------------

def f_int32(field, v):
    return tag(field, WT_VARINT) + varint(u64(v))


def f_int64(field, v):
    return tag(field, WT_VARINT) + varint(u64(v))


def f_uint(field, v):
    return tag(field, WT_VARINT) + varint(v)


def f_sint32(field, v):
    return tag(field, WT_VARINT) + varint(wire_format.ZigZagEncode(v))


def f_sint64(field, v):
    return tag(field, WT_VARINT) + varint(wire_format.ZigZagEncode(v))


def f_fixed32(field, v):
    return tag(field, WT_FIXED32) + struct.pack("<I", v & UINT32_MAX)


def f_fixed64(field, v):
    return tag(field, WT_FIXED64) + struct.pack("<Q", v & UINT64_MAX)


def f_float(field, v):
    return tag(field, WT_FIXED32) + struct.pack("<f", v)


def f_double(field, v):
    return tag(field, WT_FIXED64) + struct.pack("<d", v)


def f_bool(field, v):
    return tag(field, WT_VARINT) + varint(1 if v else 0)


def f_bytes(field, v):
    return tag(field, WT_LEN) + varint(len(v)) + v


def f_string(field, s):
    return f_bytes(field, s.encode("utf-8"))


def f_msg(field, payload):
    return tag(field, WT_LEN) + varint(len(payload)) + payload


# -- golden table ------------------------------------------------------------
# (kotlin_const_name, bytes, human comment)
GOLDENS = []
# (kotlin_const_name, int, human comment) -- sizes the tests need to rebuild inputs
INTS = []


def add(name, data, comment):
    GOLDENS.append((name, data, comment))


def build():
    # int32: negatives sign-extend to a full 10-byte varint, exactly as the
    # reference implementation does (this is the classic int32-vs-sint32 trap).
    for label, v in [("Zero", 0), ("One", 1), ("MinusOne", -1), ("OneTwentySeven", 127),
                     ("OneTwentyEight", 128), ("ThreeHundred", 300),
                     ("Max", INT32_MAX), ("Min", INT32_MIN)]:
        add(f"INT32_{label}", f_int32(1, v), f"int32 field 1 = {v}")

    for label, v in [("Zero", 0), ("One", 1), ("MinusOne", -1),
                     ("Max", INT64_MAX), ("Min", INT64_MIN)]:
        add(f"INT64_{label}", f_int64(1, v), f"int64 field 1 = {v}")

    for label, v in [("Zero", 0), ("One", 1), ("Max", UINT32_MAX)]:
        add(f"UINT32_{label}", f_uint(1, v), f"uint32 field 1 = {v}")

    for label, v in [("Zero", 0), ("One", 1), ("Max", UINT64_MAX)]:
        add(f"UINT64_{label}", f_uint(1, v), f"uint64 field 1 = {v}")

    # sint: zigzag. The 0,-1,1,-2,2 sequence pins the interleave direction.
    for label, v in [("Zero", 0), ("MinusOne", -1), ("One", 1), ("MinusTwo", -2),
                     ("Two", 2), ("Max", INT32_MAX), ("Min", INT32_MIN)]:
        add(f"SINT32_{label}", f_sint32(1, v), f"sint32 field 1 = {v}")

    for label, v in [("Zero", 0), ("MinusOne", -1), ("One", 1),
                     ("Max", INT64_MAX), ("Min", INT64_MIN)]:
        add(f"SINT64_{label}", f_sint64(1, v), f"sint64 field 1 = {v}")

    for label, v in [("Zero", 0), ("One", 1), ("Max", UINT32_MAX)]:
        add(f"FIXED32_{label}", f_fixed32(1, v), f"fixed32 field 1 = {v}")

    for label, v in [("Zero", 0), ("One", 1), ("Max", UINT64_MAX)]:
        add(f"FIXED64_{label}", f_fixed64(1, v), f"fixed64 field 1 = {v}")

    for label, v in [("Zero", 0), ("MinusOne", -1), ("Max", INT32_MAX), ("Min", INT32_MIN)]:
        add(f"SFIXED32_{label}", f_fixed32(1, v), f"sfixed32 field 1 = {v}")

    for label, v in [("Zero", 0), ("MinusOne", -1), ("Max", INT64_MAX), ("Min", INT64_MIN)]:
        add(f"SFIXED64_{label}", f_fixed64(1, v), f"sfixed64 field 1 = {v}")

    # float/double: -0.0, NaN and the infinities are the bit patterns most
    # likely to be mangled by a naive toBits()/fromBits() implementation.
    for label, v in [("Zero", 0.0), ("NegZero", -0.0), ("One", 1.0), ("MinusOne", -1.0),
                     ("Nan", float("nan")), ("PosInf", float("inf")), ("NegInf", float("-inf")),
                     ("MinValue", 1.4e-45), ("MaxValue", 3.4028235e38)]:
        add(f"FLOAT_{label}", f_float(1, v), f"float field 1 = {v}")

    for label, v in [("Zero", 0.0), ("NegZero", -0.0), ("One", 1.0), ("MinusOne", -1.0),
                     ("Nan", float("nan")), ("PosInf", float("inf")), ("NegInf", float("-inf")),
                     ("MinValue", 4.9e-324), ("MaxValue", 1.7976931348623157e308)]:
        add(f"DOUBLE_{label}", f_double(1, v), f"double field 1 = {v}")

    add("BOOL_True", f_bool(1, True), "bool field 1 = true")
    add("BOOL_False", f_bool(1, False), "bool field 1 = false")

    # Strings: multi-byte UTF-8 including a surrogate-pair emoji, since Kotlin
    # strings are UTF-16 and the encoder has to widen them correctly.
    for label, s in [("Empty", ""), ("Ascii", "hello"), ("Cjk", "你好世界"),
                     ("Emoji", "\U0001f389"), ("Mixed", "aé中\U0001f600z"),
                     ("Nul", "a\x00b")]:
        add(f"STRING_{label}", f_string(1, s), f"string field 1 = {s!r}")

    for label, b in [("Empty", b""), ("Simple", bytes([0x00, 0x01, 0x02, 0xFF])),
                     ("HighBytes", bytes(range(250, 256)))]:
        add(f"BYTES_{label}", f_bytes(1, b), f"bytes field 1 = {b!r}")

    # Enum values are written as their raw int, so a non-contiguous enum
    # (UNKNOWN=0, A=3, B=4, C=5) must not be encoded by ordinal.
    for label, v in [("Unknown", 0), ("A", 3), ("B", 4), ("C", 5)]:
        add(f"ENUM_{label}", f_int32(5, v), f"enum field 5 = {v}")

    # -- varint length boundaries (uint64 field 1) --
    for label, v in [("B1Max", 127), ("B2Min", 128), ("B2Max", 16383), ("B3Min", 16384),
                     ("B3Max", 2097151), ("B4Min", 2097152), ("B4Max", 268435455),
                     ("B5Min", 268435456), ("B9Max", 2**63 - 1), ("B10Min", 2**63),
                     ("Max", UINT64_MAX)]:
        add(f"VARINT_{label}", f_uint(1, v), f"uint64 field 1 = {v}")

    # -- tag boundaries: field numbers that cross varint tag sizes --
    for label, fn in [("F1", 1), ("F15", 15), ("F16", 16), ("F2047", 2047),
                      ("F2048", 2048), ("F262143", 262143), ("F262144", 262144),
                      ("FMax", 536870911)]:
        add(f"TAG_{label}", f_int32(fn, 1), f"int32 field {fn} = 1")

    # -- nested messages: the length back-patch path --
    # ScopedProtobufWriter reserves ONE byte for the child length and inserts
    # more when the child overflows 127 / 16383 bytes. These bracket both steps.
    add("NESTED_Empty", f_msg(1, b""), "message field 1 = {} (empty)")
    add("NESTED_Simple", f_msg(1, f_int32(1, 150)), "message field 1 = { int32 field 1 = 150 }")

    for label, n in [("Len127", 127), ("Len128", 128), ("Len16383", 16383), ("Len16384", 16384)]:
        # A bytes field inside the child, sized so the CHILD's total encoded
        # length lands exactly on n.
        for payload_len in range(n, -1, -1):
            inner = f_bytes(1, b"\xAB" * payload_len)
            if len(inner) == n:
                break
        else:
            raise AssertionError(f"no payload produces child length {n}")
        add(f"NESTED_{label}", f_msg(1, inner),
            f"message field 1 with {n}-byte body (payload {payload_len})")
        INTS.append((f"NESTED_{label}_Payload", payload_len,
                     f"bytes-field payload that makes the child exactly {n} bytes"))

    # Depth: three levels, each adding its own length prefix.
    deep = f_msg(3, f_msg(3, f_msg(3, f_int32(1, 42))))
    add("NESTED_Deep", deep, "3-level nested message, innermost int32 field 1 = 42")

    # -- repeated: ktbuf emits UNPACKED (one tag per element) --
    add("REPEATED_Int32", b"".join(f_int32(2, v) for v in (1, 2, 3)),
        "repeated int32 field 2 = [1,2,3], unpacked")
    add("REPEATED_Empty", b"", "repeated int32 field 2 = [] (nothing on the wire)")
    add("REPEATED_Messages",
        b"".join(f_msg(2, f_int32(1, v)) for v in (1, 2, 3)),
        "repeated message field 2, each { int32 field 1 = v }")
    # Packed form, for the characterization test asserting ktbuf does NOT emit it.
    add("REPEATED_Int32Packed", f_bytes(2, b"".join(varint(v) for v in (1, 2, 3))),
        "repeated int32 field 2 = [1,2,3], PACKED (what ktbuf does not emit)")

    # -- mixed / ordering --
    add("MIXED_AllScalars",
        f_int32(1, -1) + f_string(2, "hi") + f_bool(3, True) + f_double(4, 1.5) + f_bytes(5, b"\x01"),
        "int32=-1, string='hi', bool=true, double=1.5, bytes=0x01")

    # -- unknown-field skip: one field of each wire type --
    add("UNKNOWN_Varint", f_int32(9, 300), "unknown varint field 9 = 300")
    add("UNKNOWN_Fixed64", f_fixed64(9, 0x0102030405060708), "unknown fixed64 field 9")
    add("UNKNOWN_Fixed32", f_fixed32(9, 0x01020304), "unknown fixed32 field 9")
    add("UNKNOWN_LengthDelimited", f_bytes(9, b"skipme"), "unknown length-delimited field 9")
    add("UNKNOWN_Interleaved",
        f_int32(1, 7) + f_bytes(9, b"skipme") + f_string(2, "kept"),
        "known field 1, unknown field 9, known field 2")


def kotlin_bytes(data):
    if not data:
        return "\"\""
    return '"' + "".join(f"{b:02x}" for b in data) + '"'


def main():
    build()

    seen = set()
    for name, _, _ in GOLDENS:
        if name in seen:
            raise AssertionError(f"duplicate golden name {name}")
        seen.add(name)

    lines = [
        "package com.latenighthack.ktbuf",
        "",
        "// GENERATED by tools/generate_goldens.py -- do not edit by hand.",
        "//",
        "// Every constant is canonical protobuf wire format produced by the reference",
        "// Python implementation (google.protobuf.internal.encoder), NOT by ktbuf. That",
        "// independence is the whole point: it catches encoder/decoder bugs that are",
        "// symmetric and would survive any round-trip-only test.",
        "//",
        "// Regenerate with: python3 tools/generate_goldens.py",
        "object WireGoldens {",
    ]
    for name, data, comment in GOLDENS:
        lines.append(f"    // {comment}")
        lines.append(f"    const val {name} = {kotlin_bytes(data)}")
    for name, value, comment in INTS:
        lines.append(f"    // {comment}")
        lines.append(f"    const val {name} = {value}")
    lines.append("}")
    lines.append("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines))
    print(f"wrote {len(GOLDENS)} goldens to {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
