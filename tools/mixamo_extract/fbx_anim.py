"""FBX animation channel extractor.

Produces, per bone, per channel (Lcl Rotation / Lcl Translation), per axis (X/Y/Z):
    List[(time_sec, value)]

FBX animation graph (the parts we need):
    Model (id, name) ──OP "Lcl Rotation"── AnimationCurveNode (id)
    AnimationCurveNode ──OP "d|X"── AnimationCurve (id)
    AnimationCurveNode ──OP "d|Y"── AnimationCurve
    AnimationCurveNode ──OP "d|Z"── AnimationCurve
    AnimationCurve.KeyTime (int64 array, FBX ticks)
    AnimationCurve.KeyValueFloat (float32 array)

Connection records live under the top-level `Connections` node as children
named "C", whose first three properties are: kind (OO/OP), source_id (L),
dest_id (L), and optionally a property-name (S).

FBX ticks → seconds: ticks / 46_186_158_000.
"""

import struct
import sys
from pathlib import Path

# Import the walker and property reader from the peek module.
sys.path.insert(0, str(Path(__file__).parent))
from fbx_peek import HEADER_MAGIC, walk, ARRAY_ELEM_SIZE  # noqa: E402

FBX_TICKS_PER_SEC = 46_186_158_000


def decode_property(t, v):
    """Convert a (type, raw_value) tuple into a useful Python value.

    Arrays are decoded lazily — caller can reparse if they want the payload.
    We only need: S→str, L/I→int, f/d/l/i arrays → list of numbers.
    """
    if t in (b"S", b"R"):
        try:
            return v.decode("utf-8", errors="replace")
        except Exception:
            return v
    if t == b"L":
        return struct.unpack("<q", v)[0]
    if t == b"I":
        return struct.unpack("<i", v)[0]
    if t == b"F":
        return struct.unpack("<f", v)[0]
    if t == b"D":
        return struct.unpack("<d", v)[0]
    if t == b"Y":
        return struct.unpack("<h", v)[0]
    if t == b"C":
        return v[0]
    # Arrays: v is (array_len, encoding, comp_len, count) — we need payload.
    # Our parser doesn't retain the decoded payload; re-read below.
    return v  # fallthrough for arrays handled elsewhere


def reparse_array(buf, off):
    """Read an array property at `off`, returning a list of numbers.

    Assumes caller has already confirmed the type byte. Returns (type, values, new_off).
    """
    t = buf[off:off + 1]
    off += 1
    array_len, encoding, comp_len = struct.unpack_from("<III", buf, off)
    off += 12
    payload = buf[off:off + comp_len]
    off += comp_len
    if encoding == 1:
        import zlib
        payload = zlib.decompress(payload)
    elem = ARRAY_ELEM_SIZE[t]
    count = len(payload) // elem
    if t == b"l":
        values = list(struct.unpack(f"<{count}q", payload))
    elif t == b"i":
        values = list(struct.unpack(f"<{count}i", payload))
    elif t == b"f":
        values = list(struct.unpack(f"<{count}f", payload))
    elif t == b"d":
        values = list(struct.unpack(f"<{count}d", payload))
    elif t == b"b":
        values = list(payload)
    else:
        raise ValueError(f"Unknown array type {t!r}")
    return t, values, off


def walk_with_offsets(buf, off, end, out, use_u64, depth=0):
    """Variant of walk() that also records the property-list byte-offset so we
    can re-read array payloads on demand."""
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
        # Collect props as (type, raw_bytes_or_tuple, offset_of_type_byte).
        props = []
        cur = prop_off
        for _ in range(num_props):
            prop_start = cur
            t = buf[cur:cur + 1]
            cur += 1
            if t in (b"Y",):
                cur += 2
                val = buf[prop_start + 1:cur]
            elif t in (b"C",):
                cur += 1
                val = buf[prop_start + 1:cur]
            elif t in (b"I", b"F"):
                cur += 4
                val = buf[prop_start + 1:cur]
            elif t in (b"D", b"L"):
                cur += 8
                val = buf[prop_start + 1:cur]
            elif t in (b"S", b"R"):
                (length,) = struct.unpack_from("<I", buf, cur)
                cur += 4
                val = buf[cur:cur + length]
                cur += length
            elif t in ARRAY_ELEM_SIZE:
                array_len, encoding, comp_len = struct.unpack_from("<III", buf, cur)
                cur += 12
                # DO NOT decode here — keep offset so caller can reparse.
                val = (prop_start,)  # sentinel: offset of the type byte
                cur += comp_len
            else:
                raise ValueError(f"Unknown prop type {t!r} at {cur - 1}")
            props.append((t, val))
        out.append((depth, name, props, prop_off, props_end))
        if end_offset > props_end:
            walk_with_offsets(buf, props_end, end_offset, out, use_u64, depth + 1)
        off = end_offset
    return off


def load_nodes(fbx_path):
    data = Path(fbx_path).read_bytes()
    if not data.startswith(HEADER_MAGIC):
        raise ValueError(f"Not an FBX binary: {fbx_path}")
    version = struct.unpack_from("<I", data, len(HEADER_MAGIC))[0]
    use_u64 = version >= 7500
    nodes = []
    walk_with_offsets(data, len(HEADER_MAGIC) + 4, len(data) - 160, nodes, use_u64)
    return data, version, nodes


def extract_object_name(props):
    """Object name is the 2nd property (first is ID). Strip \x00\x01Class suffix."""
    if len(props) < 2:
        return None
    t, v = props[1]
    if t != b"S":
        return None
    s = v.decode("utf-8", errors="replace")
    if "\x00\x01" in s:
        return s.split("\x00\x01", 1)[0]
    return s


def extract_object_id(props):
    if not props:
        return None
    t, v = props[0]
    if t != b"L":
        return None
    return struct.unpack("<q", v)[0]


def extract_animation(fbx_path, bone_filter=None):
    """Return {bone_name: {channel: {axis: [(t_sec, v), ...]}}}.

    channel ∈ {"Lcl Rotation", "Lcl Translation", "Lcl Scaling"}
    axis    ∈ {"X", "Y", "Z"}
    """
    data, version, nodes = load_nodes(fbx_path)

    # Pass 1: build id→name, id→object-kind tables.
    id_to_name = {}
    id_to_kind = {}   # e.g. "Model", "AnimationCurveNode", "AnimationCurve"
    id_to_curve_data = {}  # id → (times_ticks, values)

    # For AnimationCurve we need KeyTime (child node, array 'l') and
    # KeyValueFloat (child node, array 'f'). Track the currently-open
    # parent via depth stack.
    current_obj = None  # (id, kind, depth)
    for idx, (depth, name, props, prop_off, props_end) in enumerate(nodes):
        if name in ("Model", "AnimationCurveNode", "AnimationCurve",
                    "AnimationLayer", "AnimationStack"):
            oid = extract_object_id(props)
            oname = extract_object_name(props)
            if oid is not None:
                id_to_name[oid] = oname or ""
                id_to_kind[oid] = name
            current_obj = (oid, name, depth) if oid is not None else None
            continue
        # Child arrays of the last-opened object.
        if current_obj is None:
            continue
        oid, kind, parent_depth = current_obj
        if depth <= parent_depth:
            current_obj = None
            continue
        if kind == "AnimationCurve":
            if name == "KeyTime":
                # Array prop, type 'l'
                for t, v in props:
                    if t == b"l":
                        offset = v[0]
                        _, values, _ = reparse_array(data, offset)
                        id_to_curve_data.setdefault(oid, [None, None])[0] = values
                        break
            elif name == "KeyValueFloat":
                for t, v in props:
                    if t == b"f":
                        offset = v[0]
                        _, values, _ = reparse_array(data, offset)
                        id_to_curve_data.setdefault(oid, [None, None])[1] = values
                        break

    # Pass 2: parse Connections (OP/OO). Connections section is at top level.
    connections = []  # list of (kind, src_id, dst_id, prop_name)
    in_connections = False
    conn_depth = None
    for depth, name, props, prop_off, props_end in nodes:
        if name == "Connections" and depth == 0:
            in_connections = True
            conn_depth = depth
            continue
        if in_connections:
            if depth == 0:
                in_connections = False
                continue
            if name == "C" and depth == conn_depth + 1:
                kind = None
                src = None
                dst = None
                prop = None
                if len(props) >= 3:
                    t0, v0 = props[0]
                    t1, v1 = props[1]
                    t2, v2 = props[2]
                    if t0 == b"S":
                        kind = v0.decode("utf-8", errors="replace")
                    if t1 == b"L":
                        src = struct.unpack("<q", v1)[0]
                    if t2 == b"L":
                        dst = struct.unpack("<q", v2)[0]
                    if len(props) >= 4 and props[3][0] == b"S":
                        prop = props[3][1].decode("utf-8", errors="replace")
                connections.append((kind, src, dst, prop))

    # Pass 3: resolve Model → {channel: AnimCurveNode id}
    model_channels = {}  # model_id → {channel: curve_node_id}
    for kind, src, dst, prop in connections:
        if kind == "OP" and src in id_to_kind and dst in id_to_kind:
            if id_to_kind[src] == "AnimationCurveNode" and id_to_kind[dst] == "Model":
                model_channels.setdefault(dst, {})[prop] = src

    # Pass 4: resolve AnimCurveNode → {axis: AnimCurve id}
    curve_node_axes = {}  # anim_curve_node_id → {axis: curve_id}
    for kind, src, dst, prop in connections:
        if kind == "OP" and src in id_to_kind and dst in id_to_kind:
            if id_to_kind[src] == "AnimationCurve" and id_to_kind[dst] == "AnimationCurveNode":
                # prop is like "d|X", "d|Y", "d|Z"
                axis = prop.split("|")[-1] if prop else "?"
                curve_node_axes.setdefault(dst, {})[axis] = src

    # Pass 5: assemble result
    result = {}
    for model_id, channels in model_channels.items():
        bone_name = id_to_name.get(model_id, "")
        if bone_filter is not None and bone_name not in bone_filter:
            continue
        bone_data = {}
        for channel, cn_id in channels.items():
            axis_curves = curve_node_axes.get(cn_id, {})
            axis_series = {}
            for axis, curve_id in axis_curves.items():
                pair = id_to_curve_data.get(curve_id)
                if not pair or pair[0] is None or pair[1] is None:
                    continue
                times_ticks, values = pair
                series = [(t / FBX_TICKS_PER_SEC, v) for t, v in zip(times_ticks, values)]
                axis_series[axis] = series
            if axis_series:
                bone_data[channel] = axis_series
        if bone_data:
            result[bone_name] = bone_data

    return result, id_to_name, id_to_kind


def summarize_extraction(fbx_path, bones_of_interest):
    anim, names, kinds = extract_animation(fbx_path, bone_filter=set(bones_of_interest))
    print(f"=== Animation extraction: {fbx_path} ===")
    print(f"Objects: {len(names)} named, {sum(1 for k in kinds.values() if k == 'AnimationCurve')} AnimCurves")
    for bone in bones_of_interest:
        if bone not in anim:
            print(f"\n[!] {bone}: no animation data resolved")
            continue
        print(f"\n{bone}:")
        for channel, axes in anim[bone].items():
            for axis, series in axes.items():
                if not series:
                    continue
                vals = [v for _, v in series]
                duration = series[-1][0] - series[0][0] if len(series) > 1 else 0
                print(f"  {channel} {axis}: {len(series):4d} keys, "
                      f"duration {duration:5.2f}s, "
                      f"range [{min(vals):+8.2f}, {max(vals):+8.2f}], "
                      f"mean {sum(vals)/len(vals):+7.2f}")
    return anim


if __name__ == "__main__":
    fbx = sys.argv[1] if len(sys.argv) > 1 else r"C:\tmp\mixamo_inspect\twerk.fbx"
    bones = ["mixamorig:Hips", "mixamorig:Spine", "mixamorig:Spine1", "mixamorig:Spine2",
             "mixamorig:LeftUpLeg", "mixamorig:RightUpLeg", "mixamorig:LeftLeg", "mixamorig:RightLeg"]
    summarize_extraction(fbx, bones)
