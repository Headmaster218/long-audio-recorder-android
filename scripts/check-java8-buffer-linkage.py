#!/usr/bin/env python3
"""Targeted classfile check, not a full Java 8 or Android API compatibility test."""
import pathlib
import struct
import sys


def buffer_references(path):
    data = path.read_bytes()
    u2 = lambda offset: struct.unpack_from(">H", data, offset)[0]
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a classfile: " + str(path))
    pool = [None] * u2(8)
    offset, index = 10, 1
    while index < len(pool):
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = u2(offset)
            offset += 2
            pool[index] = (tag, data[offset:offset + length].decode("utf-8", "replace"))
            offset += length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, u2(offset))
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, u2(offset), u2(offset + 2))
            offset += 4
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 15:
            offset += 3
        else:
            raise ValueError("unknown constant-pool tag: " + str(tag))
        index += 1
    for entry in pool:
        if entry and entry[0] in (10, 11):
            owner = pool[pool[entry[1]][1]][1]
            name_type = pool[entry[2]]
            name, descriptor = pool[name_type[1]][1], pool[name_type[2]][1]
            if owner == "java/nio/ByteBuffer" and name in {
                "clear", "flip", "position", "limit", "mark", "reset", "rewind"
            } and descriptor.endswith("Ljava/nio/ByteBuffer;"):
                yield owner + "." + name + descriptor


root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/storage-review/classes")
classes = sorted(root.rglob("*.class"))
if not classes:
    raise SystemExit("No compiled classes found; compile the review suite first.")
failures = 0
for path in classes:
    for reference in buffer_references(path):
        failures += 1
        print("FAIL Java-8 buffer linkage:", path, "references", reference)
if failures:
    raise SystemExit(1)
print("PASS targeted Java-8 buffer linkage check; full platform compatibility remains unverified")
