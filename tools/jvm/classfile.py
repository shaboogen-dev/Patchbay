"""Minimal JVM class-file reader, shared by the map validator and the mapper.

Reads only what they need: class flags, super class, interfaces, fields, methods and,
for each method, the symbolic references and constants its bytecode uses. Checked against javap.
"""
from __future__ import annotations
import struct, zipfile
from dataclasses import dataclass, field

ACC_STATIC, ACC_INTERFACE, ACC_ABSTRACT, ACC_ENUM, ACC_ANNOTATION = 0x0008, 0x0200, 0x0400, 0x4000, 0x2000


@dataclass
class Method:
    name: str
    desc: str
    flags: int
    calls: list = field(default_factory=list)    # ("M"|"F", owner, name, desc) in bytecode order
    classes: list = field(default_factory=list)  # classes used by new / checkcast / instanceof / anewarray / ldc
    strings: list = field(default_factory=list)  # string constants
    numbers: list = field(default_factory=list)  # int / long / float / double constants (ldc and bipush/sipush)
    code_len: int = 0
    ops: bytes = b""                              # opcode sequence (operands dropped)


@dataclass
class ClassInfo:
    name: str
    flags: int
    super: str | None
    interfaces: list
    fields: dict      # (name, desc) -> flags
    methods: dict     # (name, desc) -> Method


# opcode -> operand byte length, for fixed-length instructions; -1 = special (switch / wide)
_LEN = [0] * 256
for op in list(range(0x00, 0x10)) + list(range(0x1a, 0x36)) + list(range(0x3b, 0x84)) + list(range(0x85, 0x99)) + \
        list(range(0xac, 0xb2)) + [0xbe, 0xbf, 0xc2, 0xc3]:
    _LEN[op] = 0
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
    _LEN[op] = 1
for op in (0x11, 0x13, 0x14, 0x84) + tuple(range(0x99, 0xa9)) + tuple(range(0xb2, 0xb9)) + (0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7):
    _LEN[op] = 2
_LEN[0xc5] = 3
for op in (0xb9, 0xba, 0xc8, 0xc9):
    _LEN[op] = 4
_LEN[0xaa] = _LEN[0xab] = _LEN[0xc4] = -1


def parse(data: bytes) -> ClassInfo:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    n = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * n
    tags = [0] * n
    p, i = 10, 1
    while i < n:
        t = data[p]; tags[i] = t
        if t == 1:
            ln = struct.unpack_from(">H", data, p + 1)[0]
            pool[i] = data[p + 3:p + 3 + ln].decode("utf-8", errors="surrogateescape")  # modified UTF-8; fine for names
            p += 3 + ln
        elif t in (7, 8, 16, 19, 20):
            pool[i] = struct.unpack_from(">H", data, p + 1)[0]; p += 3
        elif t in (9, 10, 11, 12, 17, 18):
            pool[i] = struct.unpack_from(">HH", data, p + 1); p += 5
        elif t == 3:
            pool[i] = ("int", struct.unpack_from(">i", data, p + 1)[0]); p += 5
        elif t == 4:
            pool[i] = ("float", struct.unpack_from(">f", data, p + 1)[0]); p += 5
        elif t == 5:
            pool[i] = ("long", struct.unpack_from(">q", data, p + 1)[0]); p += 9; i += 1
        elif t == 6:
            pool[i] = ("double", struct.unpack_from(">d", data, p + 1)[0]); p += 9; i += 1
        elif t == 15:
            pool[i] = struct.unpack_from(">BH", data, p + 1); p += 4
        else:
            raise ValueError(f"constant tag {t}")
        i += 1

    def utf(k): return pool[k]
    def cls(k): return utf(pool[k]) if k else None

    def ref(k):
        owner, nt = pool[k]
        name, desc = pool[nt]
        return ("F" if tags[k] == 9 else "M", cls(owner), utf(name), utf(desc))

    flags, this, sup, ni = struct.unpack_from(">HHHH", data, p); p += 8
    interfaces = [cls(struct.unpack_from(">H", data, p + 2 * k)[0]) for k in range(ni)]; p += 2 * ni

    def attrs(p):
        out = []
        na = struct.unpack_from(">H", data, p)[0]; p += 2
        for _ in range(na):
            an, al = struct.unpack_from(">HI", data, p)
            out.append((utf(an), p + 6, al)); p += 6 + al
        return out, p

    fields = {}
    nf = struct.unpack_from(">H", data, p)[0]; p += 2
    for _ in range(nf):
        ff, fn, fd = struct.unpack_from(">HHH", data, p)
        _, p = attrs(p + 6)
        fields[(utf(fn), utf(fd))] = ff
    methods = {}
    nm = struct.unpack_from(">H", data, p)[0]; p += 2
    for _ in range(nm):
        mf, mn, md = struct.unpack_from(">HHH", data, p)
        a, p = attrs(p + 6)
        m = Method(utf(mn), utf(md), mf)
        for an, ap, _ in a:
            if an == "Code":
                code_len = struct.unpack_from(">I", data, ap + 4)[0]
                _scan(data, ap + 8, code_len, pool, tags, ref, cls, m)
        methods[(m.name, m.desc)] = m
    return ClassInfo(cls(this), flags, cls(sup), interfaces, fields, methods)


def _scan(data, start, length, pool, tags, ref, cls, m):
    m.code_len = length
    pc, end = 0, length
    ops = bytearray()
    while pc < end:
        op = data[start + pc]
        ops.append(op)
        ln = _LEN[op]
        if op == 0xaa:  # tableswitch
            q = pc + 1 + (3 - pc % 4)
            lo, hi = struct.unpack_from(">ii", data, start + q + 4)
            pc = q + 12 + 4 * (hi - lo + 1); continue
        if op == 0xab:  # lookupswitch
            q = pc + 1 + (3 - pc % 4)
            npairs = struct.unpack_from(">i", data, start + q + 4)[0]
            pc = q + 8 + 8 * npairs; continue
        if op == 0xc4:  # wide
            pc += 6 if data[start + pc + 1] == 0x84 else 4; continue
        if op in (0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9):
            m.calls.append(ref(struct.unpack_from(">H", data, start + pc + 1)[0]))
        elif op in (0xbb, 0xbd, 0xc0, 0xc1, 0xc5):
            m.classes.append(cls(struct.unpack_from(">H", data, start + pc + 1)[0]))
        elif op in (0x12, 0x13, 0x14):
            k = data[start + pc + 1] if op == 0x12 else struct.unpack_from(">H", data, start + pc + 1)[0]
            t = tags[k]
            if t == 8:
                m.strings.append(pool[pool[k]])
            elif t == 7:
                m.classes.append(cls(k))
            elif t in (3, 4, 5, 6):
                m.numbers.append(pool[k])
        elif op == 0x10:
            m.numbers.append(("int", struct.unpack_from(">b", data, start + pc + 1)[0]))
        elif op == 0x11:
            m.numbers.append(("int", struct.unpack_from(">h", data, start + pc + 1)[0]))
        pc += 1 + ln
    m.ops = bytes(ops)


def read_jar(path) -> dict[str, ClassInfo]:
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".class") and not n.startswith("META-INF/"):
                c = parse(z.read(n))
                out[c.name] = c
    return out
