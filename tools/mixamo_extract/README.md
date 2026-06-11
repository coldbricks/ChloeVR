# Mixamo dance preset extractor

Phase 1 offline pipeline that converts Mixamo FBX dance clips into
`DancePreset` rows for `prism/app/src/main/kotlin/com/ashairfoil/prism/FilamentModelActivity.kt`.

## Why this exists

The hand-tuned `DancePreset` rows in the dance engine were authored by
guesswork: pick amplitudes, pick rates, see if it looks like a twerk. Mixamo
has ~180 professionally mocapped dance clips that encode *actual* motion
patterns. This extractor pulls the rigid-body hip motion out of those clips
and writes it into the schema as numeric parameters — one clip = one preset.

It's offline because the schema can't be changed at runtime, and because
running FFTs on the render thread is absurd. The extracted numbers ship as
Kotlin source literals.

## Limitations (Phase 1)

The existing `DancePreset` schema is rigid-body only: whole-model yaw, pitch,
and vertical bob oscillate around body-fraction pivots, with 2nd-harmonic
complexity knobs and a sine-to-triangle sharpness blend. This captures about
10% of what real mocap contains. Every single extracted preset saturates the
15deg yaw / 10deg pitch / 4cm bob caps. Phase 2 (JointDriveLayer — see
task #13 in the work-tracker) widens the schema to per-joint articulation.

## Usage

```
# Single clip
python3 extract_preset.py path/to/Dancing Twerk.fbx "TWERK MIXAMO"

# Folder of clips (the main mode)
python3 extract_preset.py path/to/folder_of_fbxes/
```

Outputs a verbose analysis per clip and then a paste-ready Kotlin block.

## What the extractor actually does

1. `fbx_peek.py` — minimal FBX binary parser. Handles v7200 (u32 headers) and
   v7500+ (u64 headers). Returns node tree with LimbNode bone names.

2. `fbx_anim.py` — resolves the FBX animation graph:
   `Model` --`OP "Lcl Rotation"`--> `AnimationCurveNode`
   `AnimationCurveNode` --`OP "d|X"`--> `AnimationCurve` (KeyTime i64[], KeyValueFloat f32[])
   Output per bone: `{channel: {axis: [(t_sec, value), ...]}}`.
   FBX ticks/second constant: 46_186_158_000.

3. `extract_preset.py` — the pipeline:
   - Resample to 60fps uniform grid, unwrap Euler angles (handles spin moves)
   - Detrend (strip linear drift from long clips)
   - Envelope amplitude = (p98 - p2) / 2. FFT amplitude at fundamental is too
     conservative for mocap because most motion energy lives outside one bin.
   - Detect fundamental frequency constrained to 60-180 BPM range (rejects the
     long-period dance envelopes that otherwise dominate on slow-bob clips)
   - rate = round(4 * f_axis / f_bob) snapped to {1,2,4,8,16,32}
   - Counter-roll gain = 1 - min(spine2_Z_envelope / hips_Z_envelope, 1)
   - Ease: LINEAR if yaw sharpness > 0.5, else SINE

## Regenerating presets

To re-extract the current 31-preset block (or extend with new clips):

1. Drop FBX files into a folder (e.g. `C:\tmp\mixamo_dances\`)
2. `python3 tools/mixamo_extract/extract_preset.py <folder>`
3. Copy the emitted Kotlin block into `FilamentModelActivity.kt`, replacing
   the existing `// --- Mixamo-extracted presets (Phase 1) ---` block.
4. Build: `./gradlew assembleDebug`

## Python deps

numpy (for FFT and array math). No scipy required.

```
pip install numpy
```

## Phase 2 — per-joint drives (landed)

The extractor now also pulls per-joint Euler rotation channels for the upper
body — Spine01, Spine02, L/R_Clavicle, L/R_Upperarm, L/R_Forearm, Head — and
emits each driven axis as a `JointDrive` inside a `JointDriveLayer` attached
to the `DancePreset`. Tier 4 still owns pelvis/waist/thigh/calf (hard-coded
biomechanics); the layer composes on top without conflict.

Per-axis decomposition is one-harmonic-plus-optional-second PLUS a DC
(rest-pose) offset: for each (bone, axis) pair we find the dominant sinusoid
in [60,180] BPM, emit `(rate, phase_offset, amplitude_deg)`, and if the 2nd
harmonic is above 0.6deg we also emit `(amplitude_2nd_deg, phase_2nd_offset)`.
The time-averaged value of the UNWRAPPED signal is emitted as `restAngleDeg`
— the Mixamo author's natural rest orientation for that axis. Runtime
`JointDriveLayer.evaluate` composes as
    `angle = restAngleDeg + A1*sin(...) + A2*sin(...)`
so the target bind pose (T-pose) is posed into the Mixamo rest first; the
Fourier drives oscillate around that rest rather than around bind-zero. This
fixed a visible bug where arms would gull-wing horizontally instead of
hanging at their sides bent at the elbow.

Axes below 1.5deg fundamental amplitude AND below 1.5deg rest offset are
dropped — they are pure noise. Axes with big rest but quiet oscillation are
emitted as zero-amplitude rest-only drives so the rest pose still applies.

Rest angles are wrapped into (-180, 180] to avoid full-turn accumulation
from the unwrap step on spin-through clips (ARMS HIP HOP / YMCA otherwise
produce e.g. +344° which is pose-equivalent to -16° but less useful
numerically).

Phase conversion: the Phase 2 runtime evaluator uses `sin(2π * ph)` directly
(vs Phase 1's stylized `waveAt` on rigid-body axes). The extractor's
`phase_to_unit_sin()` applies the `+π/2` shift so cos-phase from FFT
converts cleanly to sin-phase in the preset — this fixes the 0.25-cycle
mis-alignment that Phase 1 punted.

### Per-bone axis correction (D9 Phase A — landed 2026-06-10)

`bind_pose.py` bakes the correction the old "NOT calibrated" note asked for:

- Tripo side: per-joint world bind rotations from the PRODUCTION GLB
  (`C:\tmp\mixamo_inspect\ChloeVR_Bikini_FootPivot.glb`, pulled from the
  headset's /sdcard/RIGGED/) — node-quaternion chains, validated 0.000 deg
  against the skin's inverseBindMatrices.
- Mixamo side: per-bone PreRotation chains from the clip's own FBX (Lcl
  Rotation defaults are absent on Mixamo exports, so the curves are pure
  bind-relative deltas) — validated 0.000 deg against the Deformer
  TransformLink matrices.
- Per bone: `C = W_tripoBind^-1 @ W_mixamoBind`; the SAMPLED rotation track
  is conjugated `D_tgt(t) = C @ D_src(t) @ C^T` (matrix track, not
  coefficients — conjugation mixes axes), Euler-decomposed in the runtime's
  Rz@Ry@Rx convention, unwrapped, and re-fitted per axis.
- Verified: Mixamo elbow flex (forearm Z) lands on Tripo forearm X — the
  flex axis Tier 4 established empirically ("Z gull-winged her").

Conventions are self-tested on import (compose/decompose round-trips); a
missing GLB falls back to UNCORRECTED emission with a loud warning.
- Head motion is extracted from Mixamo alongside the body, but gaze tracking
  in `FilamentModelActivity` writes a whole-model quaternion (not the Head
  joint) for viewer-facing bias, so the two layers compose additively with
  no conflict.
- L/R mirrored variants are not auto-generated — the 33-clip source folder
  already has both-hand variants where needed.

## Phase 1 known issues

- Clips that bob 2x per beat (fast hip-hop) get their BPM inferred as 2x reality.
- Clips that do step-return patterns (Moonwalk-style) trip autocorrelation.
- Euler-wrap-through-full-rotation clips (Salsa) are handled via numpy.unwrap.
  The principal rotation axis is still locked to world-Y — Phase 2 per-bone
  local-frame extraction mostly sidesteps this.
