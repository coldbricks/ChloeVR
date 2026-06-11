"""Bind-pose loaders + per-bone axis-correction rotations (D9 Phase A).

The JointDriveLayer drives were extracted in Mixamo bone-local frames; the
runtime composes them onto Tripo bind-local frames. Where the two rigs' bind
orientations differ (forearms, clavicles especially), the drives land on the
wrong local axes — Mixamo forearm Z (elbow flex) reads as Tripo gull-wing.

Correction (NOTES.md 2026-04-20 design): per mapped bone, with world bind
rotations W_src (Mixamo) and W_tgt (Tripo),

    C = W_tgt^-1 @ W_src
    D_tgt(t) = C @ D_src(t) @ C^T

so a bind-relative local delta D_src expressed in the Mixamo bone frame
becomes the world-equivalent delta in the Tripo bone frame. Derivation:
matching world-space deltas W_tgt D_tgt W_tgt^-1 = W_src D_src W_src^-1
(parents at bind — exact for the per-bone independent-sinusoid model).

Conventions (verified against both codebases):
  - Runtime (SkeletonRuntime.composeJointEulerXYZ): column-vector
    R = Rz(z) @ Ry(y) @ Rx(x), right-multiplied onto the bind local.
  - FBX eEulerXYZ ("X first, then Y, then Z", row-vector SDK convention)
    transposes to the SAME column-vector composite Rz @ Ry @ Rx.
  - glTF node rotation quats are [x, y, z, w].
  - Mixamo FBX stores the bind orientation in PreRotation; the Lcl Rotation
    curves are bind-relative deltas in the post-PreRotation frame:
        L(t) = Rpre @ Rcurves(t),  L_bind = Rpre @ RlclDefault
        D_src(t) = RlclDefault^-1 @ Rcurves(t)   (RlclDefault is ~I on Mixamo)

Self-tests run on import (compose/decompose round-trip) — a convention break
fails loudly instead of emitting silently-wrong presets.
"""

import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from fbx_anim import load_nodes, extract_object_id, extract_object_name  # noqa: E402


# ── Rotation utilities (column-vector convention throughout) ──

def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=np.float64)


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float64)


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=np.float64)


def euler_xyz_deg_to_matrix(x_deg, y_deg, z_deg):
    """Runtime/FBX composite: R = Rz @ Ry @ Rx (degrees in, column-vector)."""
    return rz(math.radians(z_deg)) @ ry(math.radians(y_deg)) @ rx(math.radians(x_deg))


def matrix_to_euler_xyz_deg(m):
    """Inverse of euler_xyz_deg_to_matrix. Returns (x_deg, y_deg, z_deg).

    For M = Rz(c) @ Ry(b) @ Rx(a):
        M[2,0] = -sin(b)
        M[2,1] = cos(b) sin(a),  M[2,2] = cos(b) cos(a)
        M[1,0] = sin(c) cos(b),  M[0,0] = cos(c) cos(b)
    Gimbal guard at |M[2,0]| ~ 1 (b = +/-90 deg): a and c degenerate; pick a=0.
    """
    sy = -float(m[2, 0])
    sy = max(-1.0, min(1.0, sy))
    b = math.asin(sy)
    if abs(sy) < 0.999999:
        a = math.atan2(float(m[2, 1]), float(m[2, 2]))
        c = math.atan2(float(m[1, 0]), float(m[0, 0]))
    else:
        # Gimbal lock: fold everything into c.
        a = 0.0
        c = math.atan2(-float(m[0, 1]), float(m[1, 1]))
    return math.degrees(a), math.degrees(b), math.degrees(c)


def quat_xyzw_to_matrix(q):
    """glTF [x,y,z,w] quaternion -> 3x3 column-vector rotation matrix."""
    x, y, z, w = (float(v) for v in q)
    n = math.sqrt(x * x + y * y + z * z + w * w)
    if n < 1e-12:
        return np.eye(3)
    x, y, z, w = x / n, y / n, z / n, w / n
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def rotation_angle_deg(m):
    """Magnitude of a rotation matrix, in degrees (0 = identity)."""
    tr = float(np.trace(m))
    c = max(-1.0, min(1.0, (tr - 1.0) / 2.0))
    return math.degrees(math.acos(c))


def euler_xyz_deg_to_matrices(x_deg, y_deg, z_deg):
    """Vectorized euler_xyz_deg_to_matrix: (N,) arrays -> (N,3,3)."""
    a = np.radians(np.asarray(x_deg, dtype=np.float64))
    b = np.radians(np.asarray(y_deg, dtype=np.float64))
    c = np.radians(np.asarray(z_deg, dtype=np.float64))
    ca, sa = np.cos(a), np.sin(a)
    cb, sb = np.cos(b), np.sin(b)
    cc, sc = np.cos(c), np.sin(c)
    m = np.empty((len(a), 3, 3), dtype=np.float64)
    m[:, 0, 0] = cc * cb
    m[:, 0, 1] = cc * sb * sa - sc * ca
    m[:, 0, 2] = cc * sb * ca + sc * sa
    m[:, 1, 0] = sc * cb
    m[:, 1, 1] = sc * sb * sa + cc * ca
    m[:, 1, 2] = sc * sb * ca - cc * sa
    m[:, 2, 0] = -sb
    m[:, 2, 1] = cb * sa
    m[:, 2, 2] = cb * ca
    return m


def matrices_to_euler_xyz_deg(m):
    """Vectorized matrix_to_euler_xyz_deg: (N,3,3) -> three (N,) degree arrays."""
    sy = np.clip(-m[:, 2, 0], -1.0, 1.0)
    b = np.arcsin(sy)
    gimbal = np.abs(sy) > 0.999999
    a = np.where(gimbal, 0.0, np.arctan2(m[:, 2, 1], m[:, 2, 2]))
    c = np.where(gimbal,
                 np.arctan2(-m[:, 0, 1], m[:, 1, 1]),
                 np.arctan2(m[:, 1, 0], m[:, 0, 0]))
    return np.degrees(a), np.degrees(b), np.degrees(c)


# ── Self-test: compose/decompose round trip (runs on import) ──

def _self_test():
    rng = np.random.default_rng(7)
    for _ in range(200):
        x, y, z = (float(v) for v in rng.uniform(-179, 179, 3))
        m = euler_xyz_deg_to_matrix(x, y, z)
        x2, y2, z2 = matrix_to_euler_xyz_deg(m)
        m2 = euler_xyz_deg_to_matrix(x2, y2, z2)
        if not np.allclose(m, m2, atol=1e-9):
            raise AssertionError(
                f"euler round-trip failed: ({x:.3f},{y:.3f},{z:.3f}) -> "
                f"({x2:.3f},{y2:.3f},{z2:.3f})")
    # Quat sanity: 90 deg about +Z maps +X to +Y.
    m = quat_xyzw_to_matrix([0, 0, math.sin(math.pi / 4), math.cos(math.pi / 4)])
    if not np.allclose(m @ np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), atol=1e-9):
        raise AssertionError("quat convention broken (90deg Z should map +X to +Y)")
    # Vectorized variants must agree with the scalar ones.
    xs = rng.uniform(-179, 179, 50)
    ys = rng.uniform(-89, 89, 50)
    zs = rng.uniform(-179, 179, 50)
    mv = euler_xyz_deg_to_matrices(xs, ys, zs)
    x2, y2, z2 = matrices_to_euler_xyz_deg(mv)
    for i in range(50):
        ms = euler_xyz_deg_to_matrix(xs[i], ys[i], zs[i])
        if not np.allclose(mv[i], ms, atol=1e-9):
            raise AssertionError("vectorized compose disagrees with scalar")
        m2 = euler_xyz_deg_to_matrix(x2[i], y2[i], z2[i])
        if not np.allclose(mv[i], m2, atol=1e-9):
            raise AssertionError("vectorized decompose round-trip failed")


_self_test()


# ── Tripo bind pose from the production GLB ──

def load_glb_bind(glb_path):
    """Per-joint WORLD bind rotation from a glTF binary.

    Walks node parents all the way to the scene root (through non-joint
    ancestors — mirrors the runtime's fold of those into the root joint's
    bindLocal). Returns {joint_name: 3x3 world rotation}.
    """
    buf = Path(glb_path).read_bytes()
    if buf[:4] != b"glTF":
        raise ValueError(f"Not a GLB: {glb_path}")
    length, = struct.unpack_from("<I", buf, 8)
    off = 12
    gltf = None
    while off < length:
        chunk_len, chunk_type = struct.unpack_from("<II", buf, off)
        off += 8
        if chunk_type == 0x4E4F534A:  # 'JSON'
            gltf = json.loads(buf[off:off + chunk_len])
            break
        off += chunk_len
    if gltf is None:
        raise ValueError("No JSON chunk in GLB")

    nodes = gltf.get("nodes", [])
    skins = gltf.get("skins", [])
    if not skins:
        raise ValueError("GLB has no skin")
    joints = skins[0]["joints"]

    parent = {}
    for ni, node in enumerate(nodes):
        for ch in node.get("children", []):
            parent[ch] = ni

    def local_rot(ni):
        node = nodes[ni]
        if "matrix" in node:
            m = np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T  # column-major list
            r = m[:3, :3]
            # Strip scale (Tripo rigs are rotation+translation, but be safe).
            for col in range(3):
                n = np.linalg.norm(r[:, col])
                if n > 1e-9:
                    r[:, col] /= n
            return r
        return quat_xyzw_to_matrix(node.get("rotation", [0, 0, 0, 1]))

    world_cache = {}

    def world_rot(ni):
        if ni in world_cache:
            return world_cache[ni]
        r = local_rot(ni)
        p = parent.get(ni)
        w = r if p is None else world_rot(p) @ r
        world_cache[ni] = w
        return w

    out = {}
    for ji in joints:
        name = nodes[ji].get("name", f"node{ji}")
        out[name] = world_rot(ji)
    return out


# ── Mixamo bind pose from any animation FBX (PreRotation chains) ──

def load_fbx_bind(fbx_path):
    """Per-bone WORLD bind rotation from an FBX (Mixamo convention).

    Bind local rotation = PreRotation @ LclRotationDefault (both Properties70
    P records on the Model node; eEulerXYZ -> column composite Rz@Ry@Rx).
    World = product down the Model OO parent chain (root Models parent to the
    implicit document id 0).

    Returns ({bone_name: 3x3 world rotation}, {bone_name: D_rest_inv}) where
    D_rest_inv = RlclDefault^-1 — the matrix that rebases Lcl Rotation curve
    samples to bind-relative deltas (identity on standard Mixamo exports).
    """
    data, version, nodes = load_nodes(fbx_path)

    # Models + their Properties70 P records. The flat node list is
    # depth-annotated; P records live two levels under the Model.
    model_props = {}   # id -> {"PreRotation": (x,y,z), "Lcl Rotation": (x,y,z), "RotationOrder": int}
    id_to_name = {}
    current_model = None  # (id, depth)
    for depth, name, props, prop_off, props_end in nodes:
        if name == "Model":
            oid = extract_object_id(props)
            oname = extract_object_name(props)
            if oid is not None:
                id_to_name[oid] = oname or ""
                model_props[oid] = {}
                current_model = (oid, depth)
            continue
        if current_model is not None and name not in ("P",):
            # Leaving the model subtree?
            oid, mdepth = current_model
            if depth <= mdepth and name != "Properties70":
                current_model = None
            continue
        if current_model is None or name != "P":
            continue
        oid, mdepth = current_model
        if depth <= mdepth:
            current_model = None
            continue
        # P record: [S key, S type, S, S, then values...]
        if not props or props[0][0] != b"S":
            continue
        key = props[0][1].decode("utf-8", errors="replace")
        if key in ("PreRotation", "Lcl Rotation", "PostRotation"):
            vals = []
            for t, v in props[4:]:
                if t == b"D":
                    vals.append(struct.unpack("<d", v)[0])
                elif t == b"F":
                    vals.append(struct.unpack("<f", v)[0])
            if len(vals) >= 3:
                model_props[oid][key] = tuple(vals[:3])
        elif key == "RotationOrder":
            for t, v in props[4:]:
                if t == b"I":
                    model_props[oid][key] = struct.unpack("<i", v)[0]
                    break

    # OO Model->Model connections give the hierarchy (child src -> parent dst).
    parent = {}
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
            if name == "C" and depth == conn_depth + 1 and len(props) >= 3:
                t0, v0 = props[0]
                t1, v1 = props[1]
                t2, v2 = props[2]
                if t0 == b"S" and v0.decode("utf-8", errors="replace") == "OO" \
                        and t1 == b"L" and t2 == b"L":
                    src = struct.unpack("<q", v1)[0]
                    dst = struct.unpack("<q", v2)[0]
                    if src in model_props and (dst in model_props or dst == 0):
                        parent[src] = dst if dst in model_props else None

    def local_bind(oid):
        p = model_props.get(oid, {})
        order = p.get("RotationOrder", 0)
        if order != 0:
            raise ValueError(
                f"Bone {id_to_name.get(oid)} has RotationOrder {order} != eEulerXYZ — "
                "extractor assumes XYZ; add support before trusting output")
        pre = p.get("PreRotation", (0.0, 0.0, 0.0))
        lcl = p.get("Lcl Rotation", (0.0, 0.0, 0.0))
        post = p.get("PostRotation")
        m = euler_xyz_deg_to_matrix(*pre) @ euler_xyz_deg_to_matrix(*lcl)
        if post is not None:
            # FBX full stack: R = Rpre @ Rlcl @ Rpost^-1. Mixamo doesn't set
            # PostRotation, but honor it here if present — AND warn loudly:
            # the curve-delta rebase in extract_preset assumes D_src(t) is the
            # raw Lcl Rotation track, which is only bind-relative when
            # PostRotation is absent. A source with PostRotation needs the
            # delta conjugated by Rpost too before trusting its drives.
            if any(abs(v) > 1e-4 for v in post):
                print(f"  [bind][!] {id_to_name.get(oid, oid)}: PostRotation set — "
                      f"curve-delta rebase ignores it; verify this bone's drives")
            m = m @ euler_xyz_deg_to_matrix(*post).T
        return m

    world_cache = {}

    def world_bind(oid):
        if oid in world_cache:
            return world_cache[oid]
        m = local_bind(oid)
        p = parent.get(oid)
        w = m if p is None else world_bind(p) @ m
        world_cache[oid] = w
        return w

    worlds = {}
    rest_inv = {}
    for oid, name in id_to_name.items():
        if not name:
            continue
        worlds[name] = world_bind(oid)
        lcl = model_props.get(oid, {}).get("Lcl Rotation", (0.0, 0.0, 0.0))
        rest_inv[name] = euler_xyz_deg_to_matrix(*lcl).T
    return worlds, rest_inv


# ── Correction table ──

def compute_corrections(tripo_glb, mixamo_fbx, joint_map, verbose=False):
    """Per-bone C = W_tripo^-1 @ W_mixamo for every pair in joint_map.

    Returns {mixamo_name: (C, D_rest_inv)}. Bones missing from either rig are
    omitted (caller falls back to uncorrected for those, with a warning).
    """
    tgt = load_glb_bind(tripo_glb)
    src, src_rest_inv = load_fbx_bind(mixamo_fbx)
    out = {}
    for src_name, tgt_name in joint_map.items():
        if src_name not in src:
            if verbose:
                print(f"  [bind] {src_name}: missing from Mixamo FBX — uncorrected")
            continue
        if tgt_name not in tgt:
            if verbose:
                print(f"  [bind] {tgt_name}: missing from Tripo GLB — uncorrected")
            continue
        c = tgt[tgt_name].T @ src[src_name]
        out[src_name] = (c, src_rest_inv.get(src_name, np.eye(3)))
        if verbose:
            print(f"  [bind] {src_name:>24s} -> {tgt_name:<12s} "
                  f"C angle = {rotation_angle_deg(c):6.1f} deg")
    return out


if __name__ == "__main__":
    glb = sys.argv[1] if len(sys.argv) > 1 else r"C:\tmp\mixamo_inspect\ChloeVR_Bikini_FootPivot.glb"
    fbx = sys.argv[2] if len(sys.argv) > 2 else r"C:\tmp\mixamo_inspect\twerk.fbx"
    from extract_preset import JOINT_MAP
    print(f"Tripo GLB: {glb}")
    print(f"Mixamo FBX: {fbx}")
    table = compute_corrections(glb, fbx, JOINT_MAP, verbose=True)
    print(f"\n{len(table)}/{len(JOINT_MAP)} bones corrected.")
    # Dump tripo world rotations of interest for eyeballing.
    tgt = load_glb_bind(glb)
    for n in ("L_Upperarm", "R_Upperarm", "L_Forearm", "R_Forearm", "Spine01", "Head"):
        if n in tgt:
            x, y, z = matrix_to_euler_xyz_deg(tgt[n])
            print(f"  Tripo {n:<12s} world bind euler-xyz ~ ({x:7.1f}, {y:7.1f}, {z:7.1f})")
