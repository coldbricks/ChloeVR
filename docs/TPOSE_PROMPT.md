# ChloeVR T-Pose Image Generation Prompt

Canonical prompt for Gemini (Nano Banana 2) image generation of source
images that autorig cleanly through Tripo (UniRig). Also works on Seedance
4.5 with the same phrasing.

The "parallel columns with a gap" phrasing is load-bearing — earlier attempts
without it gave us X-stance, A-stance, and touching-thigh rigs that weighted
poorly. **Gemini produced a clean rig-friendly T-pose on the first attempt
with this prompt** (ash-airfoil T-pose girl, blue bikini, Apr 2026).

## Prompt

```
Re-pose the subject into a strict T-pose, preserving face, hair, skin tone,
body proportions, outfit, and platform heels exactly. Arms horizontally to
the sides at shoulder height, palms facing down, fingers straight and
slightly spread.

LEGS: parallel and vertical from hip to ankle — inner thighs NOT touching
(clear 5-10cm gap between them), knees NOT touching, feet shoulder-width
apart pointing straight forward. The silhouette of the legs should be two
vertical columns with a visible gap between them the entire length — not
an A-shape, not an X-shape, not feet-wider-than-hips. Simple parallel
stance like a soldier at ease.

Torso vertical, shoulders level, hips level, spine straight, head level
looking at camera, neutral closed-mouth expression. Pure white #FFFFFF
background, even soft lighting, orthographic framing, full body visible.
Photorealistic, preserve identity exactly.
```

## Why it works

- **"parallel and vertical from hip to ankle"** — forces column-shape legs,
  rejects A/X variants
- **"NOT touching ... clear 5-10cm gap"** — explicit negative + numeric
  distance, prevents touching-thigh default
- **"soldier at ease"** — real-world pose reference the model has
  trained on (photos of people in that stance exist)
- **"two vertical columns with a visible gap between them the entire
  length"** — describes silhouette, not just pose; models respect
  silhouette descriptions
- **"orthographic framing"** — kills wide-angle lens distortion
- **"Photorealistic, preserve identity exactly"** — keeps photo look,
  rejects CGI drift

## Known failure modes (and negatives)

| Default pose the model drifts toward | Fix |
|---|---|
| Crossed/X legs | "NOT an X-shape" + "parallel columns" |
| Touching inner thighs | "NOT touching ... clear 5-10cm gap" |
| Hip cock / contrapposto | "hips level" + "spine straight" |
| Arm droop | "at shoulder height" (not "horizontally" alone) |
| Feet splayed outward | "feet pointing straight forward" |
| Fashion/runway lean | "soldier at ease" |
| CGI/3D-render look | "Photorealistic" (drop any "mannequin", "Blender", "reference sheet" wording) |

## Rigging pipeline

1. Generate image with this prompt (Gemini / Seedance / Nano Banana)
2. Run through Tripo with **Humanoid rig** (v2.5 autorig, PBR on)
3. Export as GLB (Quad retopo if desired — keeps weights intact at ~100K tris)
4. Optionally Mixamo naming if dropping Mixamo animations later; otherwise
   Tripo's native bone names work with our loader (maps by topology)
5. Drop the GLB into `/sdcard/RIGGED/` on device (or local Downloads folder —
   ChloeVR auto-scans) and the RIGGED filter in the picker will surface it.

## Notes

- Heels (6-7" platform clear heels) are canonical to the Ash Airfoil aesthetic
  and get baked into the rig's foot bone position; not an issue as long as
  every character uses the same heel spec.
- Tripo's v2.5 autorig produces 39 joints:
  Root → Hip → Pelvis → (Waist → Spine01 → Spine02 → Neck → Head, + Clavicles → Arms)
                                               + (L/R Thigh → Calf → Foot + twist bones)
- See `CLAUDE.md` Tier 4 section for the runtime skinning implementation that
  drives these rigs.
