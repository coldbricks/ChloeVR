"""Minimal FBX binary node walker. Emits bone (LimbNode) name list + summary.

FBX binary (v7500+) node record:
    u64 EndOffset, u64 NumProperties, u64 PropertyListLen,
    u8 NameLen, char Name[NameLen],
    Property[NumProperties],
    NestedNode[...] (until EndOffset),
    13 zero bytes sentinel if children present.

Property types we need:
    'S'/'R': u32 length + bytes
    Arrays (f/d/l/i/b): u32 ArrayLen, u32 Encoding, u32 CompressedLength, payload.
        Encoding 0 = raw, 1 = zlib-compressed.
    Scalars (Y/C/I/F/D/L): fixed-size.

We only need to enumerate node names, string properties, and collect
object-type/sub-class for Model nodes (bones are subclass 'LimbNode').
"""

import struct
import sys
import zlib
from pathlib import Path

HEADER_MAGIC = b"Kaydara FBX Binary  \x00\x1a\x00"
SCALAR_SIZES = {b"Y": 2, b"C": 1, b"I": 4, b"F": 4, b"D": 8, b"L": 8}
ARRAY_ELEM_SIZE = {b"f": 4, b"d": 8, b"l": 8, b"i": 4, b"b": 1}


def read_property(buf, off):
    t = buf[off:off + 1]
    off += 1
    if t in SCALAR_SIZES:
        size = SCALAR_SIZES[t]
        val = buf[off:off + size]
        off += size
        return t, val, off
    if t in (b"S", b"R"):
        (length,) = struct.unpack_from("<I", buf, off)
        off += 4
        val = buf[off:off + length]
        off += length
        return t, val, off
    if t in ARRAY_ELEM_SIZE:
        array_len, encoding, comp_len = struct.unpack_from("<III", buf, off)
        off += 12
        payload = buf[off:off + comp_len]
        off += comp_len
        if encoding == 1:
            payload = zlib.decompress(payload)
        elem = ARRAY_ELEM_SIZE[t]
        count = len(payload) // elem
        return t, (array_len, encoding, comp_len, count), off
    raise ValueError(f"Unknown prop type {t!r} at offset {off - 1}")


def walk(buf, off, end, out, use_u64, depth=0, path=()):
    """FBX < 7500 uses 13-byte header (u32×3 + u8); >= 7500 uses 25-byte (u64×3 + u8)."""
    if use_u64:
        rec_header, fmt, name_len_off = 25, "<QQQ", 24
    else:
        rec_header, fmt, name_len_off = 13, "<III", 12
    while off < end:
        if off + rec_header > end:
            break
        end_offset, num_props, prop_list_len = struct.unpack_from(fmt, buf, off)
        name_len = buf[off + name_len_off]
        if end_offset == 0:
            return off + rec_header
        name = buf[off + rec_header:off + rec_header + name_len].decode("ascii", errors="replace")
        prop_off = off + rec_header + name_len
        props_end = prop_off + prop_list_len
        props = []
        cur = prop_off
        for _ in range(num_props):
            t, v, cur = read_property(buf, cur)
            props.append((t, v))
        nested_start = props_end
        nested_end = end_offset
        out.append((depth, path + (name,), name, props))
        if nested_end > nested_start:
            walk(buf, nested_start, nested_end, out, use_u64, depth + 1, path + (name,))
        off = end_offset
    return off


def summarize(fbx_path):
    data = Path(fbx_path).read_bytes()
    if not data.startswith(HEADER_MAGIC):
        print(f"[!] Not a recognized FBX binary: {fbx_path}")
        return
    # Decode string props keeping raw bytes so we can see \x00\x01 separators.
    version = struct.unpack_from("<I", data, len(HEADER_MAGIC))[0]
    print(f"=== {fbx_path} (FBX v{version}, {len(data):,} bytes) ===")
    start = len(HEADER_MAGIC) + 4
    nodes = []
    walk(data, start, len(data) - 160, nodes, use_u64=(version >= 7500))  # footer is ~160 bytes

    # Collect Model nodes: look for node name "Model" with properties (id:L, name:S, subclass:S)
    models = []
    for depth, path, name, props in nodes:
        if name == "Model" and len(props) >= 3:
            str_props = [v.decode("ascii", errors="replace") for (t, v) in props if t in (b"S",)]
            models.append(str_props)

    limbnodes = [p for p in models if any("LimbNode" in s for s in p)]
    meshes = [p for p in models if any("Mesh" in s for s in p)]
    print(f"Model nodes: {len(models)}  (LimbNode bones: {len(limbnodes)}, Mesh: {len(meshes)})")
    if limbnodes and "--debug" in sys.argv:
        print("\n[debug] first 3 LimbNode raw string props:")
        for lp in limbnodes[:3]:
            print(f"  {lp!r}")

    bone_names = []
    for p in limbnodes:
        # FBX encodes object names as "<Name>\x00\x01<Class>" in the first prop.
        # Decode errors="replace" may have eaten the \x00\x01 — handle both.
        for s in p:
            if "\x00\x01" in s:
                bone_names.append(s.split("\x00\x01", 1)[0])
                break
            if "\ufffd" in s:  # null+\x01 got turned into replacement char(s)
                # Take substring before the first replacement char run
                idx = s.find("\ufffd")
                bone_names.append(s[:idx])
                break

    print(f"\n--- Bone list ({len(bone_names)} bones) ---")
    for bn in bone_names:
        print(f"  {bn}")

    # Animation presence check
    anim_curves = sum(1 for _, _, n, _ in nodes if n == "AnimationCurve")
    anim_layers = sum(1 for _, _, n, _ in nodes if n == "AnimationLayer")
    anim_stacks = sum(1 for _, _, n, _ in nodes if n == "AnimationStack")
    print(f"\n--- Animation ---")
    print(f"  AnimationStacks:  {anim_stacks}")
    print(f"  AnimationLayers:  {anim_layers}")
    print(f"  AnimationCurves:  {anim_curves}")

    if "--tree" in sys.argv:
        from collections import Counter
        top_level = [n for d, _, n, _ in nodes if d == 0]
        print(f"\n--- Top-level nodes ---")
        for name, c in Counter(top_level).items():
            print(f"  {name}: {c}")
        # Also list all unique node names with depths
        print(f"\n--- Unique node names (count, min/max depth) ---")
        depth_by_name = {}
        for d, _, n, _ in nodes:
            if n not in depth_by_name:
                depth_by_name[n] = [0, d, d]
            depth_by_name[n][0] += 1
            depth_by_name[n][1] = min(depth_by_name[n][1], d)
            depth_by_name[n][2] = max(depth_by_name[n][2], d)
        for n, (c, mn, mx) in sorted(depth_by_name.items(), key=lambda x: -x[1][0])[:40]:
            print(f"  {n}: {c}x (depth {mn}-{mx})")

    return bone_names


if __name__ == "__main__":
    for p in sys.argv[1:]:
        summarize(p)
        print()
