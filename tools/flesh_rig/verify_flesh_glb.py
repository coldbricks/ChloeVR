"""Verify a flesh-rigged GLB against the ChloeVR loader's hard requirements.

Checks (each maps to a real crash/silent-failure in the app):
  1. skins[0].joints <= 64 (MAX_JOINTS_SHADER) and TOPO-SORTED
     (SkeletonRuntime check(p < j) hard-crashes otherwise).
  2. Helper bones present with the right parents.
  3. meshes[0].primitives[0] is the BODY (the loader parses ONLY that
     primitive) and carries JOINTS_0 / WEIGHTS_0 / NORMAL / TEXCOORD_0.
  4. JOINTS_0 componentType (loader expectation: UNSIGNED_BYTE vec4 —
     report if Blender emitted UNSIGNED_SHORT so the loader can be checked).
  5. WEIGHTS_0 rows ~normalized; helper bones actually referenced.
  6. Material/baseColor texture survived the round-trip.

Usage: python verify_flesh_glb.py path.glb
"""

import json
import struct
import sys
from collections import Counter
from pathlib import Path


def parse_glb(path):
    buf = Path(path).read_bytes()
    assert buf[:4] == b"glTF", "not a GLB"
    length, = struct.unpack_from("<I", buf, 8)
    off = 12
    gltf, bin_chunk = None, None
    while off < length:
        clen, ctype = struct.unpack_from("<II", buf, off)
        off += 8
        if ctype == 0x4E4F534A:
            gltf = json.loads(buf[off:off + clen])
        elif ctype == 0x004E4942:
            bin_chunk = buf[off:off + clen]
        off += clen
    return gltf, bin_chunk


CT_SIZE = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}
CT_NAME = {5121: "UNSIGNED_BYTE", 5123: "UNSIGNED_SHORT", 5126: "FLOAT"}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_accessor(gltf, bin_chunk, idx):
    acc = gltf["accessors"][idx]
    bv = gltf["bufferViews"][acc["bufferView"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    n = NCOMP[acc["type"]]
    ct = acc["componentType"]
    count = acc["count"]
    stride = bv.get("byteStride") or (CT_SIZE[ct] * n)
    fmt = {5121: "B", 5123: "H", 5125: "I", 5126: "f"}[ct]
    out = []
    for i in range(count):
        out.append(struct.unpack_from(f"<{n}{fmt}", bin_chunk, start + i * stride))
    return acc, out


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else r"C:\tmp\mixamo_inspect\ChloeVR_Bikini_Flesh.glb"
    gltf, bin_chunk = parse_glb(path)
    nodes = gltf["nodes"]
    ok = True

    def check(cond, msg):
        nonlocal ok
        print(("  OK   " if cond else "  FAIL ") + msg)
        ok = ok and cond

    skin = gltf["skins"][0]
    joints = skin["joints"]
    names = [nodes[j].get("name", f"n{j}") for j in joints]
    print(f"skin joints: {len(joints)}")
    check(len(joints) <= 64, f"joint count {len(joints)} <= 64")

    parent = {}
    for ni, node in enumerate(nodes):
        for ch in node.get("children", []):
            parent[ch] = ni
    jpos = {n: i for i, n in enumerate(joints)}
    topo_bad = []
    for i, jn in enumerate(joints):
        p = parent.get(jn)
        while p is not None and p not in jpos:
            p = parent.get(p)
        if p is not None and jpos[p] >= i:
            topo_bad.append((names[i], names[joints.index(p)] if p in joints else p))
    check(not topo_bad, f"topo order (parents before children){': ' + str(topo_bad) if topo_bad else ''}")

    for hb, want_parent in (("Breast_L", "Spine02"), ("Breast_R", "Spine02"),
                            ("Glute_L", "Pelvis"), ("Glute_R", "Pelvis")):
        if hb not in names:
            check(False, f"helper bone {hb} present")
            continue
        ni = joints[names.index(hb)]
        p = parent.get(ni)
        pname = nodes[p].get("name") if p is not None else None
        check(pname == want_parent, f"{hb} parent is {pname} (want {want_parent})")

    mesh0 = gltf["meshes"][0]
    prim = mesh0["primitives"][0]
    attrs = prim["attributes"]
    pos_acc = gltf["accessors"][attrs["POSITION"]]
    print(f"meshes[0] '{mesh0.get('name')}' primitives={len(mesh0['primitives'])} "
          f"verts={pos_acc['count']}")
    check(pos_acc["count"] > 10000, "meshes[0].primitives[0] is the body (loader reads ONLY this)")
    # Review #8: a multi-material body round-trips into MULTIPLE primitives and
    # the loader silently drops everything past [0][0].
    check(len(mesh0["primitives"]) == 1,
          f"meshes[0] has exactly 1 primitive (got {len(mesh0['primitives'])} — extras are DROPPED on device)")
    if len(gltf["meshes"]) > 1:
        def mesh_verts(mi):
            return sum(gltf["accessors"][pr["attributes"]["POSITION"]]["count"]
                       for pr in gltf["meshes"][mi]["primitives"]
                       if "POSITION" in pr.get("attributes", {}))
        total = sum(mesh_verts(mi) for mi in range(len(gltf["meshes"])))
        check(mesh_verts(0) >= 0.9 * total,
              f"meshes[0] dominates vertex count ({mesh_verts(0)}/{total}) — other meshes are dropped")
    body_nodes = [ni for ni, nd in enumerate(nodes) if nd.get("mesh") == 0]
    check(any("skin" in nodes[ni] for ni in body_nodes),
          "the node referencing meshes[0] carries the skin")
    for a in ("NORMAL", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0"):
        check(a in attrs, f"attribute {a} present")

    jacc, jdata = read_accessor(gltf, bin_chunk, attrs["JOINTS_0"])
    print(f"JOINTS_0 componentType = {CT_NAME.get(jacc['componentType'], jacc['componentType'])}")
    wacc, wdata = read_accessor(gltf, bin_chunk, attrs["WEIGHTS_0"])
    check(wacc["componentType"] == 5126, "WEIGHTS_0 is FLOAT")

    bad_norm = sum(1 for w in wdata if abs(sum(w) - 1.0) > 0.01 and sum(w) > 0)
    check(bad_norm < len(wdata) * 0.01, f"weights normalized ({bad_norm} rows off)")
    # Review #9: all-zero rows skin to a zero matrix and collapse to the origin.
    zero_rows = sum(1 for w in wdata if sum(w) == 0)
    check(zero_rows == 0, f"no zero-weight rows ({zero_rows} found — they collapse to the origin on device)")

    helper_idx = {names.index(h) for h in ("Breast_L", "Breast_R", "Glute_L", "Glute_R") if h in names}
    counts = Counter()
    maxw = {}
    for jrow, wrow in zip(jdata, wdata):
        for j, w in zip(jrow, wrow):
            if j in helper_idx and w > 0.0:
                counts[j] += 1
                maxw[j] = max(maxw.get(j, 0.0), w)
    for j in sorted(helper_idx):
        print(f"  {names[j]:>9s}: {counts.get(j, 0):5d} skinned verts, max weight {maxw.get(j, 0):.2f}")
    check(all(counts.get(j, 0) >= 50 for j in helper_idx), "every helper bone skins >= 50 verts")
    check(all(maxw.get(j, 0) <= 0.45 for j in helper_idx),
          "helper influence SHARES (max weight <= 0.45, never owns the flesh)")
    # Review #10: the per-bone cap missed verts inside TWO helper spheres
    # (cleavage) stacking combined share past the intent.
    comb = 0.0
    for jrow, wrow in zip(jdata, wdata):
        s = sum(w for j, w in zip(jrow, wrow) if j in helper_idx)
        if s > comb:
            comb = s
    check(comb <= 0.45, f"combined helper share per vertex <= 0.45 (max {comb:.2f})")

    mat_ok = bool(gltf.get("materials")) and "pbrMetallicRoughness" in gltf["materials"][0]
    tex_ok = bool(gltf.get("images"))
    check(mat_ok and tex_ok, f"material + {len(gltf.get('images', []))} image(s) survived round-trip")

    n_ibm = gltf["accessors"][skin["inverseBindMatrices"]]["count"]
    check(n_ibm == len(joints), f"IBM count {n_ibm} == joints {len(joints)}")

    print("\nVERDICT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


main()
