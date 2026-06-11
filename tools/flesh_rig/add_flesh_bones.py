"""D10 flesh-rig pass: append spring-bone helpers to a rigged Tripo GLB.

Adds Breast_L/R (children of Spine02) and Glute_L/R (children of Pelvis)
with spherical falloff vertex weights blended into the existing skin, so the
runtime SpringBoneLayer can drive real flesh inertia. Hair chains are a
follow-up (v1 = the four flesh bones; budget says <=10 spring bones).

Run headless:
    blender --background --python add_flesh_bones.py -- in.glb out.glb

Bone conventions produced (the runtime relies on these):
  - Helper bone LOCAL +Y points along the flesh protrusion direction
    (Blender bones export with +Y = head->tail).
  - Bone length 7cm (scaled by model height / 1.7m).
  - Names exactly: Breast_L, Breast_R, Glute_L, Glute_R.

Placement is auto-derived from the mesh:
  - lateral axis from L_Upperarm vs R_Upperarm head positions
  - forward = the horizontal direction with the larger chest-band bulge
  - per-side nipple/glute apex = farthest vertex along +/-forward inside a
    Y-band, with a lateral clamp so T-pose arms don't win
  - bone head sits 2cm inside the surface apex

Weights: w = 0.5 * smoothstep falloff (1 at r*0.3 -> 0 at r) added to the
existing normalized weights; the glTF exporter renormalizes to the 4
strongest influences, so the helper ends up sharing (not owning) the flesh.
Radii: breasts 9cm, glutes 12cm (scaled by height).
"""

import sys
from pathlib import Path

import bpy
from mathutils import Vector


def log(msg):
    print(f"[flesh_rig] {msg}", flush=True)


def fail(msg):
    print(f"[flesh_rig][FATAL] {msg}", flush=True)
    sys.exit(1)


def parse_args():
    argv = sys.argv
    if "--" not in argv:
        fail("usage: blender --background --python add_flesh_bones.py -- in.glb out.glb")
    rest = argv[argv.index("--") + 1:]
    if len(rest) < 2:
        fail("need input and output GLB paths after --")
    return Path(rest[0]), Path(rest[1])


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3.0 - 2.0 * t)


def main():
    src, dst = parse_args()
    if not src.exists():
        fail(f"input not found: {src}")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(src))

    arm = next((o for o in bpy.data.objects if o.type == "ARMATURE"), None)
    # The body = the LARGEST skinned mesh (FootPivot GLBs also carry a tiny
    # Icosphere pivot marker that must not win this pick).
    meshes = [o for o in bpy.data.objects if o.type == "MESH"]
    skinned = [o for o in meshes
               if any(m.type == "ARMATURE" for m in o.modifiers)] or meshes
    mesh_obj = max(skinned, key=lambda o: len(o.data.vertices), default=None)
    if arm is None or mesh_obj is None:
        fail("no armature or mesh after import")
    log(f"armature '{arm.name}' bones={len(arm.data.bones)}; mesh '{mesh_obj.name}' "
        f"verts={len(mesh_obj.data.vertices)}")

    def bone_head_world(name):
        b = arm.data.bones.get(name)
        if b is None:
            fail(f"required bone '{name}' missing")
        return arm.matrix_world @ b.head_local

    spine02 = bone_head_world("Spine02")
    pelvis = bone_head_world("Pelvis")
    head = bone_head_world("Head")
    l_arm = bone_head_world("L_Upperarm")
    r_arm = bone_head_world("R_Upperarm")

    # World axes for the bind pose: +Y up (glTF); lateral from the arm pair;
    # forward = horizontal perpendicular, sign picked by chest bulge below.
    up = Vector((0, 0, 1))  # Blender world is Z-up (importer converts from glTF Y-up)
    lat = (l_arm - r_arm)
    lat -= up * lat.dot(up)
    if lat.length < 1e-6:
        fail("upper arms coincide laterally — cannot derive axes")
    lat.normalize()
    fwd = lat.cross(up).normalized()  # sign resolved below

    deps = bpy.context.evaluated_depsgraph_get()
    mesh_eval = mesh_obj.evaluated_get(deps)
    mw = mesh_eval.matrix_world
    verts = [mw @ v.co for v in mesh_eval.data.vertices]

    ys = [v.dot(up) for v in verts]
    height = max(ys) - min(ys)
    scale = height / 1.7
    log(f"model height {height:.3f}m (scale {scale:.2f}); lat={tuple(round(c, 2) for c in lat)}")

    shoulder_half = (l_arm - r_arm).length * 0.5

    def band_apexes(y_lo, y_hi, direction, lat_lo, lat_hi):
        """Per-side apex = CENTROID of the verts within 1.5cm of the side's
        max along `direction`, inside the height band AND an anatomical
        lateral window. Centroid beats single-vertex (one outlier on an
        asymmetric Tripo bind pose moved a glute 11cm); the lateral floor
        keeps the search out of the push-up cleavage, the ceiling off the
        T-pose arms. Result is then SYMMETRIZED: both sides get the mean
        height and mirrored mean lateral offset — flesh bones want symmetric
        placement even on an asymmetric mesh."""
        center_l = (spine02.dot(lat))
        cands = {1: [], -1: []}
        for v in verts:
            if not (y_lo <= v.dot(up) <= y_hi):
                continue
            side_off = v.dot(lat) - center_l
            if not (lat_lo <= abs(side_off) <= lat_hi):
                continue
            cands[1 if side_off > 0 else -1].append(v)
        out = {}
        for side, vs in cands.items():
            if not vs:
                out[side] = None
                continue
            dmax = max(v.dot(direction) for v in vs)
            slab = [v for v in vs if v.dot(direction) >= dmax - 0.015 * height]
            out[side] = sum(slab, Vector()) / len(slab)
        if out[1] is None or out[-1] is None:
            return out
        # Symmetrize: mean height, mirrored mean |lateral|, per-side forward.
        mean_up = (out[1].dot(up) + out[-1].dot(up)) * 0.5
        mean_lat = (abs(out[1].dot(lat) - center_l) + abs(out[-1].dot(lat) - center_l)) * 0.5
        for side in (1, -1):
            p = out[side]
            p += up * (mean_up - p.dot(up))
            p += lat * ((center_l + side * mean_lat) - p.dot(lat))
            out[side] = p
        return out

    # Resolve forward sign: at chest height the bust protrudes farther from
    # the spine plane than the back does.
    chest_lo = spine02.dot(up) - 0.02 * scale
    chest_hi = spine02.dot(up) + (head.dot(up) - spine02.dot(up)) * 0.55
    spine_f = spine02.dot(fwd)
    plus = max((v.dot(fwd) - spine_f) for v in verts if chest_lo <= v.dot(up) <= chest_hi)
    minus = max((spine_f - v.dot(fwd)) for v in verts if chest_lo <= v.dot(up) <= chest_hi)
    if minus > plus:
        fwd = -fwd
    log(f"forward={tuple(round(c, 2) for c in fwd)} (bulge +{max(plus, minus):.3f} vs {min(plus, minus):.3f})")

    # Anatomical lateral windows (fractions of full height): nipples sit at
    # roughly +/-0.06h off center, glute apexes +/-0.05h. The window keeps the
    # apex search out of the cleavage AND off arms/hips.
    h = height
    breast_apex = band_apexes(chest_lo,
                              spine02.dot(up) + (head.dot(up) - spine02.dot(up)) * 0.40,
                              fwd, 0.030 * h, 0.105 * h)
    glute_lo = pelvis.dot(up) - 0.16 * scale
    glute_hi = pelvis.dot(up) + 0.06 * scale
    glute_apex = band_apexes(glute_lo, glute_hi, -fwd, 0.022 * h, 0.095 * h)
    for side, v in {**{f"breast{s}": breast_apex[s] for s in (1, -1)},
                    **{f"glute{s}": glute_apex[s] for s in (1, -1)}}.items():
        if v is None:
            fail(f"no apex vertex found for {side} — band/axis derivation wrong for this mesh")

    bone_len = 0.07 * scale
    inset = 0.02 * scale
    specs = []  # (name, parent, head_world, dir_world, radius)
    for side, suffix in ((1, "L"), (-1, "R")):
        specs.append((f"Breast_{suffix}", "Spine02",
                      breast_apex[side] - fwd * inset, fwd, 0.09 * scale))
        specs.append((f"Glute_{suffix}", "Pelvis",
                      glute_apex[side] + fwd * inset, -fwd, 0.12 * scale))

    for name, parent, head_w, dir_w, radius in specs:
        log(f"{name}: head=({head_w.x:+.3f},{head_w.y:+.3f},{head_w.z:+.3f}) "
            f"dir=({dir_w.x:+.2f},{dir_w.y:+.2f},{dir_w.z:+.2f}) r={radius:.3f}")
        if arm.data.bones.get(name) is not None:
            fail(f"bone '{name}' already exists — refusing to double-rig")

    # ── Append bones (edit mode) ──
    bpy.context.view_layer.objects.active = arm
    bpy.ops.object.mode_set(mode="EDIT")
    inv = arm.matrix_world.inverted()
    for name, parent, head_w, dir_w, radius in specs:
        eb = arm.data.edit_bones.new(name)
        eb.head = inv @ head_w
        eb.tail = inv @ (head_w + dir_w * bone_len)
        eb.parent = arm.data.edit_bones[parent]
        eb.use_connect = False
        eb.use_deform = True
    bpy.ops.object.mode_set(mode="OBJECT")

    # ── Weights: spherical falloff x parent-region mask, exporter renorms ──
    # Region mask: a vertex only takes helper weight in proportion to how
    # much it is ALREADY skinned to the parent body region — hair (Head-
    # weighted) and bikini straps crossing the sphere stay off the flesh bone.
    REGION = {
        "Breast_L": ("Spine02", "Spine01"),
        "Breast_R": ("Spine02", "Spine01"),
        "Glute_L": ("Pelvis", "Waist", "Hip", "L_Thigh", "R_Thigh"),
        "Glute_R": ("Pelvis", "Waist", "Hip", "L_Thigh", "R_Thigh"),
    }
    me = mesh_obj.data
    gi = {g.name: g.index for g in mesh_obj.vertex_groups}
    for name, parent, head_w, dir_w, radius in specs:
        region_idx = {gi[r] for r in REGION[name] if r in gi}
        vg = mesh_obj.vertex_groups.new(name=name)
        n = 0
        r_in = radius * 0.3
        for vi, v in enumerate(me.vertices):
            wv = mesh_obj.matrix_world @ v.co
            d = (wv - head_w).length
            if d >= radius:
                continue
            region_w = sum(ge.weight for ge in v.groups if ge.group in region_idx)
            w = 0.5 * (1.0 - smoothstep(r_in, radius, d)) * min(1.0, region_w * 1.5)
            if w > 0.01:
                vg.add([vi], w, "REPLACE")
                n += 1
        log(f"{name}: weighted {n} verts")
        if n < 50:
            fail(f"{name} grabbed only {n} verts — placement/radius/mask wrong, not exporting")

    # ── Export ──
    dst.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(dst),
        export_format="GLB",
        export_animations=False,
        export_skins=True,
        export_yup=True,
        export_apply=False,
    )
    log(f"exported {dst} ({dst.stat().st_size // 1024} KB)")


main()
