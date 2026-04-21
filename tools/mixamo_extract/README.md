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

## Known issues / Phase 2 backlog

- Clips that bob 2x per beat (fast hip-hop) get their BPM inferred as 2x reality.
- Clips that do step-return patterns (Moonwalk-style) trip autocorrelation.
- Euler-wrap-through-full-rotation clips (Salsa) are handled via numpy.unwrap but
  the principal rotation axis is still locked to world-Y even when the dancer
  pitches forward — Phase 2 per-joint PCA will fix this.
- No per-bone calibration yet — Phase 1 only touches Hips and Spine2 in world
  frame, so the target rig's bone naming is irrelevant. Phase 2 needs the real
  per-bone correction quats.
