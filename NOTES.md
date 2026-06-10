# ChloeVR Inter-Agent Notes

Append-only. Every agent reads this at session start and writes at session end.
Format: `## [Agent] YYYY-MM-DD` followed by notes.

---

## [Claude] 2026-03-06

### Initial Assessment

Read the entire codebase. Project is fully functional — all core VR180/360 playback features work including grab/reposition with aim pose, roll, A/B repeat, scrub bar, zoom, seek, and speed control.

### What I Set Up

Created multi-agent coordination architecture:
- `AGENTS.md` — universal onboarding brief with architecture, file map, pipeline, and role assignments
- `NOTES.md` — this file, inter-agent message bus
- `.agent-roles/CLAUDE.md` — my standing orders
- `.agent-roles/CODEX.md` — Codex 5.3 standing orders
- `.agent-roles/GEMINI.md` — Gemini CLI standing orders

### Current Concerns

1. **`MainActivity.kt` is 1335 lines** and handles everything. It's the single biggest risk for merge conflicts between agents. I've documented line-range sections in AGENTS.md. Coordinate before editing.

2. **No tests exist.** Any agent adding tests should create `prism/app/src/test/` for unit tests.

3. **Debug logging in `openxr_input.cpp:462-467`** still active — logs hand pose every ~2s when trigger is held. Low priority but should be cleaned up.

4. **Fisheye projections** (MKX200, RF52, VRCA220) all map to `SurfaceEntity.Shape.Hemisphere`. This is an approximation. Correct rendering would need custom mesh support from the Jetpack XR SDK, which may not be available yet.

5. **No persistence.** User preferences (seek increment, last file, screen adjustments) are lost on restart.

## [Codex] 2026-03-06

### Session: Onboarding Sync

- Read `AGENTS.md`, `NOTES.md`, and `.agent-roles/CODEX.md`.
- Checked recent commits (`git log --oneline -10`) to sync current branch context.
- Searched this repo for a dedicated Codex directory (`codex`, `.codex`, `Codex`) and did not find one under `C:\Users\coldb\Desktop\ChloeVR`.
- Ready to work within Codex-owned areas: UI sections in `MainActivity.kt`, `VideoPlayer.kt`, `FileNameParser.kt`, `FilePicker.kt`, and related unit tests.

## [Codex] 2026-03-06

### Session: Alpha Passthrough Feasibility

- Investigated DeoVR `_ALPHA` support request ("red silhouette" issue).
- Verified current state in code: `FileNameParser` sets `hasAlpha`, but playback path in `MainActivity.playVideo()` does not use it.
- Confirmed likely root cause: app currently uses direct `ExoPlayer -> SurfaceEntity.getSurface()` rendering with no per-frame shader/unpack stage.
- DeoVR alpha format is packed into red-channel regions for fisheye content; proper support requires unpacking that mask each frame and compositing with transparency.
- Conclusion: true Deo alpha passthrough is not a small toggle in current pipeline; it needs a custom video rendering path (GPU shader/effect pipeline) plus passthrough environment control.

## [Codex] 2026-03-06

### Session: Deo _ALPHA Implementation (In Progress)

- Planning edits to `MainActivity.kt` sections:
  - Lines 161-326 (file picker tags for alpha indicator)
  - Lines 1013-1065 (`playVideo` alpha detection + pipeline enable)
  - Lines 1299-1334 (playback cleanup/helpers for passthrough reset)
- Will add a Media3 GL effect shader path to decode Deo packed alpha and apply per-pixel transparency.
- Will enable XR passthrough opacity when alpha playback is active.

## [Codex] 2026-03-06

### Session: Deo _ALPHA Playback Implementation (Completed)

Implemented alpha passthrough support path for Deo-style packed alpha videos:

- Added Media3 effect dependency:
  - `prism/app/build.gradle.kts` -> `androidx.media3:media3-effect:1.5.1`

- Added new shader effect class:
  - `prism/app/src/main/kotlin/com/ashairfoil/prism/DeoVrAlphaPackedEffect.kt`
  - Decodes Deo packed red-mask layout into frame alpha (GL shader).
  - Clears known packed mask patch regions so red overlays do not render.

- Updated player integration:
  - `VideoPlayer.start(...)` now accepts `useDeoAlphaPacking` and applies `setVideoEffects(...)` when enabled.

- Updated playback + XR passthrough wiring in `MainActivity.kt`:
  - Added `_ALPHA` tag in file picker rows.
  - Enabled deo-alpha path only for fisheye projections (`FISHEYE`, `MKX200`, `RF52`, `VRCA220`).
  - Enabled passthrough via `SpatialEnvironment.preferredPassthroughOpacity = 1f` while alpha playback is active.
  - Restored passthrough preference on playback stop.

Validation notes:
- Kotlin compilation reached `:app:compileDebugKotlin` and no new code errors remained after fixes.
- Build command still exits non-zero in this sandbox due environment/reporting issues (`FileAlreadyExistsException` for Gradle problems report + restricted Kotlin daemon temp path), so on-device build/run confirmation is still required in normal local environment.

## [Codex] 2026-03-06

### Session: Speed Slider + Boomerang A/B (In Progress)

- Editing `MainActivity.kt` sections:
  - Lines 1-99 (state additions for boomerang mode)
  - Lines 327-670 (control panel speed slider + A/B mode controls)
  - Lines 791-855 (A/B loop logic in seek update loop)
  - Lines 856-1012 (`toggleAbRepeat`/labels and scrub bar button labels)
- Goal:
  - Replace discrete speed +/- with slider control.
  - Add boomerang A/B loop (A->B forward, then reverse B->A, repeat).

## [Codex] 2026-03-06

### Session: Speed Slider + Boomerang A/B (Completed)

Implemented requested playback controls:

- Replaced discrete +/- speed controls with a continuous speed slider in Control Panel.
  - Range: `0.25x` to `4.0x`
  - Step: `0.05x`
  - Live label updates while sliding.

- Added boomerang A/B mode (ping-pong loop):
  - Normal loop mode: `A -> B -> A` (jump loop)
  - Boomerang mode: `A -> B` forward, then reverse stepping `B -> A`, then forward again.
  - Added Loop/Boomerang toggle row in Control Panel.
  - Updated A/B state label to show active mode.
  - Updated scrub bar A/B button label/color to indicate boomerang state.

- Added explicit `play()` / `pause()` methods to `VideoPlayer` for reverse-phase control in boomerang mode.

Validation notes:
- `:app:compileDebugKotlin` reached Kotlin compile step and showed no new code errors from these changes.
- Sandbox still reports non-code Gradle/Kotlin daemon environment issues (`FileAlreadyExistsException` problems report + daemon temp path permission), so final on-device validation should be run in normal local environment.

## [Codex] 2026-03-06

### Session: File Picker Async + Search/Filters (Completed)

Implemented file picker improvements (agreed next priority):

- Converted file scan path to asynchronous/progressive updates:
  - Added `FilePicker.listVideoFilesProgressive(...)` with root-by-root progress callbacks.
  - `MainActivity` now starts scans via coroutine (`Dispatchers.IO`) and updates picker incrementally on UI thread.

- Added loading/status UX:
  - Live scan status text (`Scanning storage... X/Y roots (N videos)`).
  - Count label updates during scan (`shown/found`) and after completion.

- Added interactive file finder controls in picker UI:
  - Search box (name/path substring).
  - Projection filter row (`All`, `180�`, `360�`, `Fisheye`, `Flat`).
  - Stereo filter row (`All`, `SBS`, `T/B`, `2D`).
  - Alpha filter row (`Any`, `Alpha Only`).
  - `Clear Filters` and `Rescan` actions.

- Added picker lifecycle safety:
  - Cancels scan when starting playback (`playVideo`) or destroying activity.
  - Prevents stale scan callbacks from replacing playback UI.

Validation:
- Ran `:app:compileDebugKotlin` successfully after changes.

## [Codex] 2026-03-06

### Session: File Picker Sorter (In Progress)

- Editing `MainActivity.kt` file-picker sections (lines 1-99 and 161-326 equivalent blocks after recent edits).
- Adding sort options: Name, Newest, Size.
- Sorting will apply after filters/search and refresh immediately.
## [Codex] 2026-03-06

### Session: File Picker Sorter (Completed)

- Added file picker sorting state and UI toggle row in `MainActivity.kt`: `Name`, `Newest`, `Size`.
- Sorting now applies after search/filter matching and refreshes immediately on selection.
- Implemented `sortFilePickerFiles(...)`:
  - Name: ascending filename
  - Newest: descending `lastModified`
  - Size: descending file length
- Validation: ran `:app:compileDebugKotlin` successfully (`BUILD SUCCESSFUL`).

## [Claude] 2026-03-13

### Major Session: File Picker Fix + 3D Model Viewer + Live Device Testing

**File Picker:**
- Fixed ANR on 20k+ files: display cap at 200, debounce scan, O(1) lookups
- Added MANAGE_EXTERNAL_STORAGE for GLB/non-media file access
- Added file type filter (All/Video/Image/3D Model)

**3D Model Viewer (NEW):**
- GLB loading via GltfModel.create(session, bytes, name) — byte[] overload required
- Multi-model passthrough AR scene
- Controls: Grip=move, Grip+Trigger=move+rotate, Grip+Stick=scale/push-pull
- Lock Hands mode for walk-around viewing
- Floor grounding via bounding box
- GLB structure parser (mesh name, node name, material properties from JSON chunk)

**Video Fix:**
- Black screen caused by ExoPlayer FinalShaderWrapper dropping frames when effects pipeline active
- Fix: only add effects with .enabled=true to setVideoEffects()

**SDK Limitations Found (via ADB logcat on Galaxy XR SM-I610):**
- setAlpha() on GltfModelEntity: no visible effect
- setMaterialOverride(): crashes native renderer (OwnedPtr lifecycle SIGABRT)
- Texture.create(session, Path): asset-only, can't load external files
- Floor anchor: requires PlaneTrackingMode enabled (fails with boundary off)

**Next Priority:**
- Material control (exposure/metallic/roughness) requires switching to FULL_SPACE_UNMANAGED + Filament renderer
- ShapesXR uses this approach (Unity + direct OpenXR), confirmed by APK analysis

## [Claude] 2026-04-19

### Tier 3 — Feature 1 (Musical Amplitude) + Feature 2 (Intensity Range) shipped

Ultrathink pass on `TIER3_PLAN.md` Session N+1 scope. Implemented and verified `assembleDebug` BUILD SUCCESSFUL.

**AudioReactor.kt:**
- Added `musicalLevel` / `silenceFloor` / `musicalDynamics` fields + `musicalLevelSmooth` state.
- In `update()` after `safeDt` compute: RMS of raw bass/mid/high → envelope follower (50ms attack / 800ms release) → headroom above `silenceFloor` scaled by `musicalDynamics` → published as `musicalLevel ∈ [0,1]`.
- `!enabled` branch sets `musicalLevel = 1f` so dance amp isn't gated when reactor is off.
- `FrameSnapshot.musicalLevel` + populated by `snapshot()`.

**Consumers wired (FilamentModelActivity.kt):**
- Per-frame: cached `val ml = reactor.musicalLevel` at the beat-wash block top.
- Beat-wash shadow (line ~1420): `pct * 0.4f * bi * ml`.
- Bloom threshold + intensity (~1430): multiplied `pct*bi` term by `ml`.
- Haptic output — shake-amp path, split-mode, unified path — all multiply raw amp by `ml` before the 0-20 int conversion. ChloeVibesEngine path left alone (has its own dynamics pipeline).
- Per-model dance loop: `ampGain = accentGain * moodGain * fillAmp * snap.musicalLevel` (decision on design Q #1 — apply musicalLevel as global multiplier on top of band gates, per plan's own recommendation).
- Impact kick `kickRad` multiplied by `snap.musicalLevel` so kicks breathe too.

**Intensity cycle expanded (InputHandler.kt + UiRenderer.kt):**
- Old: 0.25 → 0.5 → 1.0 → 1.5 → 2.0 → 0.25.
- New: 0.25 → 0.5 → 1.0 → 2.0 → 3.0 → 5.0 → 0.25. Internal `effInt = danceIntensity * 0.25f` unchanged — 1.0× still feels calm; 5.0× is new "go wild" headroom.
- Label formatter (`"%.1f".format`) already handles 3.0/5.0 cleanly — no UI code change needed beyond the comment.

**Silence Floor slider:**
- Added `BeatSlider("SILENCE", "%", 0f, 100f, ...)` between EXPAND and SMOOTH in `beatSliders`. Writes `reactor.silenceFloor`. Default 15%. Not persisted to SettingsManager (matches existing reactor knobs like SMOOTH/EXPAND/ATTACK — none are persisted).

**Skipped from plan's Session N+1 scope (kept scope tight):**
- Per-model "Music Breathe" toggle — plan marked it optional, Row C in beat panel already full. silenceFloor=1.0 disables breathing globally, sufficient for now.
- `musicalDynamics` slider — kept internal at 1.0, silenceFloor alone gives sufficient range control.

**Verified:** `./gradlew assembleDebug` BUILD SUCCESSFUL in 18s. Two pre-existing "Condition is always 'true'" warnings at FilamentModelActivity.kt:842 and :2162 — unrelated to this work.

### Tier 3 — Feature 3 (Motion Expression Sliders) shipped

Continuing Session N+1/N+2 — landed Feature 3 in the same session. `assembleDebug` BUILD SUCCESSFUL.

**SceneManager.kt:**
- `shapedWaveAt(phase, ease, sharpness, complexity)` companion helper. Blends `waveAt(phase, ease)` toward a cusp'd triangle (sharpness), then adds a 2×-rate sine ghost at 0.4× amplitude (complexity). Triangle formula `1 - 4*|((p + 0.25) % 1) - 0.5|` — allocation-free, exact peaks at ±1, matches sine's zero-crossings. Both zero → behaves identically to `waveAt`.
- `PlacedModel` +6 fields: `yawSharpness`, `yawComplexity`, `pitchSharpness`, `pitchComplexity`, `bobSharpness`, `bobComplexity` — all default 0 (preserves pre-Tier3 motion exactly).

**FilamentModelActivity.kt:**
- `DancePreset` +6 fields; all 7 presets populated from the plan's tuning table (e.g. TWERK: `yawS=0.7 yawC=0.5 pitS=0.9 pitC=0.4 bobS=0.6 bobC=0.3`; BOUNCE: `0.8 0 0.6 0 0.9 0`; SWAY: all zeros).
- `shuffleDance` + `setDancePreset` both copy the 6 character fields onto the target model.
- Dance loop: yaw and pitch math switched from `SceneManager.waveAt` → `SceneManager.shapedWaveAt(..., m.XSharpness, m.XComplexity)`.
- Bob is a one-sided bump (not ±sine) so it got bespoke shaping inline: `lerp(smoothstep, phase01, bobSharpness)` for the peak shape, plus an optional 2×-rate secondary bump scaled by `bobComplexity * 0.4`. Preserves the existing kSkew asymmetric envelope.
- Roll stays unshaped (not in plan's 6-field scope).
- New activity state `characterMode: Boolean`.

**InputHandler.kt:**
- New `characterSliders` array with 6 entries (YAW SHARP, YAW CMPLX, PITCH SHARP, PITCH CMPLX, BOB SHARP, BOB CMPLX) — same closure pattern as existing per-model sliders.
- New public `activeSliders` getter: returns `characterSliders` when `characterMode` is on, else `beatSliders`.
- `applyBeatSlider` + hit-test both switched from `beatSliders` → `activeSliders` so drag dispatch follows the toggle.
- Row C button 146 (was FOOT cycle) now toggles `characterMode`.
- FOOT moved into main reactor list as `BeatSlider("FOOT", "%", 0f, 100f, ...)` per plan.

**UiRenderer.kt:**
- `beatSliders` alias now points at `activeSliders` (single line change propagates everywhere downstream).
- BEATREACTOR header flips to "CHARACTER" in `PURPLE_DEEP` when the sub-panel is active.
- Row C button relabeled: "CHARACTER" → "← BACK" depending on mode; bolds when active.

**Decisions / simplifications vs plan:**
- Plan requested a 3×2 paired-column grid — I used a 6×1 list (reuses existing BeatSlider rendering verbatim). Functionally identical control surface, zero new rendering code.
- Plan wanted a dedicated BACK button; I made the CHARACTER Row C button toggle (label flips to "← BACK" when active). One button, two states, same affordance.
- Physics jiggle cusp artifact (plan's gotcha) not observed in code review — the triangle is position-only, the jiggle spring acts as a second-order LPF. If on-device testing reveals pops, swap in `tanh(k*sin(2π*p))` with `k = 1 + 4*sharp` per plan's fallback.
- Amp×rate K-ceiling (plan's second gotcha) not re-tuned — peak composite = 1 + 0.4 = 1.4× over baseline when sharp=1, cmplx=1. Preset defaults keep `sharp + cmplx` well under 2.0 in practice. Monitor on device.

**Verified:** `./gradlew assembleDebug` BUILD SUCCESSFUL in 7s. Two pre-existing "Condition is always 'true'" warnings at FilamentModelActivity.kt:861 and :2197 (line numbers shifted due to added code) — unrelated to this work.

### Tier 3 — Feature 4 (Glute Deformation) shipped — MVP

Third Tier 3 feature in the same session. `./gradlew assembleDebug` BUILD SUCCESSFUL. Not yet device-tested.

**GlesModelRenderer.kt — shader pipeline:**
- VERTEX_SHADER modified: 6 new uniforms (`uGluteAPos`, `uGluteBPos`, `uGluteARadius`, `uGluteBRadius`, `uGluteAPush`, `uGluteBPush`). Before `gl_Position`, `vec3 pos = aPosition`, then two `if (radius > 0.0001)` guards test distance to each glute center; inside the radius, `pos += aNormal * push * (1 - smoothstep(radius*0.3, radius, d))`. All downstream derived outputs (`gl_Position`, `vPosition`, `vWorldY`) switched from `aPosition` → `pos`. Tangent/normal left un-recomputed per plan (small-push approximation).
- 6 `uniform locations` cached in `init()` + fields on `private var uGlute*`.
- `GpuModel` data class +6 fields: `gluteAPos/gluteBPos: FloatArray(3)`, `gluteARadius/gluteBRadius: Float = 0` (0 = shader skips), `gluteAPush/gluteBPush: Float`.
- Per-model draw loop (~line 1336): 6 `glUniform*` calls after the emissive block, before VAO bind.
- **Shadow pass intentionally left unchanged** — shadow vertex shader has no `aNormal` attribute, and small pushes (<5cm) produce subtle shadow mismatch that's hard to notice with the PCF soft-shadow pass. If this proves visibly wrong on device, the shadow shader will need an `aNormal` attribute binding + the same displacement math.

**SceneManager.kt:**
- `PlacedModel` +6 fields: `gluteBasePush` (meters, 0..0.08), `gluteRadius` (0.05..0.30m), `gluteShakeIntensity` (0..1), `gluteCurrentPush` (per-frame scratch), `gluteKickLastMs` + `gluteLastBeatSeen` (for kick-spike detection).

**FilamentModelActivity.kt — CPU beat-sync:**
- New sync block at the top of the per-model dance loop (runs BEFORE the `dancing` check — non-dancing models with `basePush > 0` still get a static push).
- Kick detection: if `snap.beatCounter` advanced since `gluteLastBeatSeen`, stamp `gluteKickLastMs = snap.nowMs`. Each frame compute `spike = exp(-elapsedMs * 3 / 250)` → `boost = 1 + shakeIntensity * spike` → `currentPush = basePush * boost`. At 250ms `spike ≈ 0.05`, so the "sharp pop, soft decay" shape lands at the plan's target.
- Auto-derived A/B positions each frame (no explicit marking flow for MVP): `hipY = minY + hipFrac * height`, `sideOffset = 0.18 * boundsRadius`, `rearOffset = 0.10 * boundsRadius`. Uses `markedHipFrac` if the user marked hip anatomy, else defaults to 0.45. Results written to `GpuModel.gluteAPos/gluteBPos` in model-local space.
- When `basePush == 0`: all uniforms zero → shader's radius guard short-circuits.

**InputHandler.kt — UI:**
- 3 new sliders appended to `characterSliders` (not a new GLUTE sub-mode per plan — pragmatic merge with the CHARACTER panel which had plenty of room at 6 sliders, now 9):
  - `GLUTE PUSH` — 0..8 cm
  - `GLUTE SHAKE` — 0..100%
  - `GLUTE RADIUS` — 5..30 cm
- `UiRenderer.kt` slider formatter gained a `"cm" -> "%.1f cm"` case (also fixes a latent display bug where the pre-existing `BOB` cm slider was showing as `%`).

**Scope / plan deviations documented:**
- No explicit glute marking flow (plan called for MARK GLUTE L/R actions + stored positions). MVP auto-derives from hip mark + bbox — good enough for anatomically reasonable GLBs. Stored model-local point capture can come in a follow-up if users want per-model custom placement.
- No Y-threshold masking (plan gotcha for hair/clothing vertices) — accepted. Add if a GLB shows visible hair-pop.
- No shadow-pass displacement — accepted. Revisit if visibly wrong.
- Glute sliders merged into CHARACTER panel instead of a dedicated GLUTE sub-mode — saves a Row C slot and groups "body feel" knobs together. The CHARACTER header/affordance still reads sensibly for both motion-shape and mesh-shape controls.

**Layout note (not new — carried over from Feature 3):**
The main `beatSliders` list is at 22 entries × 28px = 616px, which exceeds the available 575px slider area. The tail clips visually into Row A on the beat panel. Row A/B/C render on top, so it reads as the last ~1-2 sliders being partially obscured. Pre-existing after Feature 3 landed FOOT. If it becomes a real issue, reduce `sliderRowH` to 24px (would give 22 sliders in 528px) or split into sub-panels. Not blocking MVP.

**Verified:** `./gradlew assembleDebug` BUILD SUCCESSFUL in 7s. Same two pre-existing warnings (lines drifted again to 861 and 2238). Runtime shader-compile verification requires ADB/device and has NOT been done — first thing to confirm on-device is that the main shader still compiles with the new uniforms + displacement math (if it fails, the app won't render models at all, which would be immediately obvious).

**Next session — Tier 3 follow-ups (any / all):**
- Device test Features 1-4 end to end; tune defaults based on feel.
- Explicit glute marking flow (raycast hit → model-local 3D point on PlacedModel).
- Shadow pass displacement if shadow mismatch is visible.
- HEAVE stretch from plan — chest mesh deformation, same system as glute.
- UI: slider list overflow fix (smaller row height OR sub-panels).
- Persist Tier 3 state in SettingsManager (silenceFloor, character/glute per model).

---

## [Claude] 2026-04-20

### Session: Mixamo dance preset extraction pipeline (Phase 1)

User direction pivot during session, captured here so next session doesn't re-derive:
the procedural Tier 4 dance engine is beat-phase-locked; Mixamo clips are
time-locked. Can't mix. Right model is to LEARN from Mixamo (extract motion
params as functions of beatPhase) not COPY it (runtime clip playback).

Within "learn", two phases:
- **Phase 1 (done this session):** extract rigid-body hip motion → existing
  DancePreset schema. Test loop to validate extraction pipeline E2E. Expected
  NOT to look dramatically better than hand-tuned — schema is the ceiling.
- **Phase 2 (designed, not implemented):** extend schema with JointDriveLayer
  for per-joint Euler Fourier drives. Axis calibration comes back here, per-bone.

### What shipped
- `tools/mixamo_extract/` — offline Python pipeline (FBX binary parser → animation
  graph resolver → harmonic extractor → DancePreset Kotlin emitter). See
  tools/mixamo_extract/README.md for the how.
- `FilamentModelActivity.kt` — 31 new `DancePreset` rows appended to dancePresets.
  7 hand-tuned originals preserved; total 38 presets live.
- APK built, not yet sideloaded/validated on-device.

### Confirmed facts about the rig landscape
- User's rigged character (BIKINI+GIRL+WITH+BONES+MIXAMO.zip, 30MB, 39 bones) uses
  **Tripo naming**: Root, Hip (singular), Pelvis, Waist, Spine01, Spine02,
  L/R_Thigh, L/R_Calf, L/R_Foot, L/R_Upperarm, L/R_Forearm, L/R_Hand, L/R_Clavicle,
  Head, plus twist bones (ThighTwist01/02, CalfTwist01/02, etc.).
- Tripo's "Mixamo" FBX preset DOES NOT BAKE A SKELETON — it's a pre-upload format
  for Mixamo Auto-Rigger. The user's earlier mixamo+bikini.zip (28MB) was
  mesh-only. The WITH+BONES zip is the actually-rigged version.
- Mixamo animation FBX files use `mixamorig:*` prefix on all 65 standard bones.
  Standard Mixamo skeleton: Hips, Spine, Spine1, Spine2, Neck, Head,
  Left/RightShoulder, Left/RightArm, Left/RightForeArm, Left/RightHand + fingers,
  Left/RightUpLeg, Left/RightLeg, Left/RightFoot, Left/RightToeBase.
- Bone name mismatch between Mixamo source and Tripo target is non-trivial
  but doesn't bite Phase 1 (we extract world-frame Hips rigid-body motion).
  Phase 2 NEEDS a retarget table: mixamorig:Hips→Hip, mixamorig:Spine→Spine01,
  mixamorig:Spine1→Spine02, mixamorig:LeftShoulder→L_Clavicle,
  mixamorig:LeftArm→L_Upperarm, mixamorig:LeftForeArm→L_Forearm, etc.

### Phase 1 quantitative results
Across all 31 extracted presets: yawDeg / pitchDeg / bobM all saturate the
runtime caps (15deg / 10deg / 0.04m) for every non-trivial clip. Raw envelope
amplitudes ranged yaw 17-107deg, pitch 11-402deg, bob 4-31cm. Most clips
delivered 2-3x the motion the schema can represent. This is *the* quantitative
argument for Phase 2. The extractor logs the clamps explicitly.

Inferred tempos span 62-145 BPM (realistic musical range) after applying:
- Euler angle unwrap (fixed Salsa/Samba 700-1000deg spurious peak-to-peak)
- 60-180 BPM band constraint on fundamental detection (fixed House=31,
  Snake=20, Macarena=22 false-positive envelope detection)

### Known extractor limitations (Phase 1 punt list)
- Some clips bob 2x/beat not 1x/beat → BPM inferred 2x reality. Affects Shuffling,
  possibly others. Low-priority in Phase 1 because the rates are ratios; high-
  priority in Phase 2 where per-joint rates matter.
- Non-stationary rhythm clips (Moonwalk-style step-return) break autocorrelation.
  Not in current clip set.
- Amplitude ceiling clamps are lossy and silent beyond a log warning. Every
  extracted preset hits them.
- No axis calibration yet. Hips rigid-body extraction is world-frame so target
  rig bind pose doesn't matter — this collapses in Phase 2.

### Phase 2 design (drafted in task-tracker #13, not implemented)
- Schema: `JointDriveLayer(drives: Array<JointDrive>)` composed into DancePreset
  as `val jointLayer: JointDriveLayer? = null`.
- JointDrive = (jointName, axis[0/1/2], rateCyclesPerMeasure, phaseOffset,
  amplitudeDeg, amplitude2ndDeg, phase2ndOffset).
- Composition model C (per-joint ownership): Tier 4 keeps Pelvis/Waist/Root/
  Thigh/Calf (hard-wired biomechanics — lordosis arch, foot-planting, thigh
  counter-rotation). JointDriveLayer owns Spine01/02, L/R_Clavicle,
  L/R_Upperarm, L/R_Forearm, Head. No conflicts.
- Runtime: zero-alloc Array<JointDrive>, evaluates after rigid-body pass,
  writes into SkeletonRuntime.localPose via setJointEulerX/Y/Z.
- Axis calibration OFFLINE per-bone at extraction time (runtime stays dumb).
  Correction quat = target_bind_world⁻¹ × source_bind_world, applied before
  PCA on the principal rotation axis.
- Four open design questions for user review:
  1. Kotlin literal vs JSON asset for the drives array?
  2. Twist bones (ThighTwist01/02 etc.) — proportional-to-parent / independent / at-rest?
  3. Head: extract from Mixamo or stay gaze-controlled?
  4. Auto-generate L/R-mirrored variants?

### Files created this session
- `tools/mixamo_extract/fbx_peek.py` — FBX binary parser (v7200 and v7500+).
- `tools/mixamo_extract/fbx_anim.py` — AnimationCurve resolver via Connections.
- `tools/mixamo_extract/extract_preset.py` — harmonic extractor + Kotlin emitter.
- `tools/mixamo_extract/README.md` — usage + limitations + regeneration flow.

### Files modified this session
- `prism/app/src/main/kotlin/com/ashairfoil/prism/FilamentModelActivity.kt` —
  31 extracted DancePreset rows appended after existing SQUAT PULSE.

### Critical: on-device validation pending
User has not yet sideloaded the built APK. APK at
`prism/app/build/outputs/apk/debug/app-debug.apk` (45MB). Install with
`adb install -r <path>`. Eyeball test: pick each MIXAMO preset from the dance
picker, compare motion quality to hand-tuned originals. Expected finding: not
dramatically better — schema is the ceiling. Actionable: if a specific preset
looks WORSE than hand-tuned, that's an extractor bug worth tracing (rate/phase/
counter-roll mapping). If all similar-or-better, Phase 1 pipeline validated,
Phase 2 is the next move.

### Next session starting points
- If user approves Phase 2 design answers: implement `scene/JointDriveLayer.kt`,
  extend `extract_preset.py` to pull per-joint channels with calibration, wire
  evaluation into FilamentModelActivity.kt dance loop (task #13, #2, #3).
- If on-device Phase 1 looks bad: trace the extractor's phase/rate mapping —
  `ar.phaseAtSnap(snap, rate)` in the runtime vs `phase_to_unit()` in extractor
  need sign/wrap conventions to match. Current best-guess alignment may be off.
- Independent of above: the existing commit (hash after push) is a safe restore
  point. `git revert HEAD` brings you back to pre-extraction TWERK.

## [Claude] 2026-06-10

### On-device verification (Quest 3, wireless adb @ 192.168.1.156)

- R6 color pass verified live: aniso 4x supported+active, sRGB baseColor upload OK (no AOSP black-model trap), PBR Neutral + fill-BRDF shaders compile and render.
- RECORD_AUDIO granted via `pm grant` — Visualizer/trigger box now has a data feed on this headset.
- Audit finding confirmed in logs: "disabling normal-map tangent-space shading on skinned model — flat PBR only" (R3/D11 is real, fix first).
- Rig has Foot + 14 twist bones (D6/D12 ready), auto budget caps 4096 textures to 2048px.

### BUG (pre-existing): renderer re-init breaks shadow FBO

Exiting/relaunching FilamentModelActivity in the same process destroys+recreates GlesModelRenderer, after which every frame logs "Shadow FBO broke: 0x0 / FBO incomplete: 0x0" (status 0 = check itself failing → GL objects created on a context/thread that isn't current on the new render thread). First cold start is always clean; `am force-stop` clears it. Fix: tie FBO (re)creation to the render thread's context lifecycle, not activity onCreate.

## [Claude] 2026-06-10 — R2 implemented (Follow Room Light)

R2 from RENDERING_REALISM_PLAN.md implemented end-to-end. `assembleDebug` BUILD
SUCCESSFUL, both flavors, native included. NOT yet device-verified — the whole
feature is gated on XR_ANDROID_light_estimation, so it needs the Galaxy XR
(inert on Quest by design; consumption sits inside the hasXrLight branch).

### What landed
- **Direction consumption** (FilamentModelActivity ~2216-2256): XR directional
  estimate steers lightAngleDeg/lightElevDeg when Follow Room Light is on.
  Gates: mean dirRGB > 0.3 AND length > 0.5 (the INVALID default is dir=(0,1,0)
  — length 1.0! — with intensity 0; only the intensity gate filters it),
  60-frame warm-up (~20 native polls), EMA aD=0.12 into the previously-dead
  smoothLightDir. Hysteresis is CLAMPED-CANDIDATE: build candidate az/el first
  (elev clamped [5..85], azimuth FROZEN when horizontal component < 5% of the
  vector — atan2 near zenith is pure noise), then 5° dot-test vs applied dir.
  Comparing the raw estimate instead deadlocks >5° for overhead/below-horizon
  lights and writes angles every frame → emitter gizmo + shadow crawl (review
  caught this; original implementation had it).
- **Follow Room Light toggle**: SettingsManager-persisted ("follow_room_light",
  default ON). No room for a new param row (hit rows ≥25 collide with the
  action-button band at by≥1054), so param 24 Auto Light now CYCLES
  OFF → ON+DIR → ON. Badge shows "ON+DIR*" when selected but XR light data
  isn't flowing. Flip-off contract (mirrors ambient-slider/autoAmbient):
  azimuth/elevation slider + thumbstick nudge + A-reset, emitter drag (grab
  start), lighting-preset apply. Scene load is EXEMPT (global preference).
- **colorCorrection wired into SH path** (GlesModelRenderer shader ~2926-2941):
  shIrrad diffuse + specEnv now multiply uAmbientColor (was fallback-only).
- **useSH un-stick**: drops to hemisphere fallback after 270 frames (~90 native
  polls) without valid SH, and immediately when Auto Light toggles off. Native
  side ages out lastLightEstimate_ after 10 consecutive failed real polls —
  without that, the 3-frame cache replay echoes valid=1/shValid=1 on 2 of 3
  frames forever, resetting the Kotlin counter so the un-stick could NEVER fire
  on hard outages (review caught this).
- **AMBIENT-kind SH** (xr_renderer.cpp pollLightEstimate, new
  queryLightEstimate() helper): while Follow is active, SH is queried with
  kind=AMBIENT so the explicit key light isn't double-counted (TOTAL already
  contains the directional term). Fallback to TOTAL covers ALL THREE rejection
  shapes — XR_FAILED, root-INVALID, sh-INVALID (original only covered the
  polite sh-INVALID shape; a hard-rejecting runtime would have silently killed
  ALL light estimation with Follow on — review caught this). Unsupported-kind
  latch: 30 evidence polls (TOTAL-valid-while-AMBIENT-invalid; both-invalid is
  neutral), guarded to skip the estimator's first ~2s, cleared on Follow
  re-engage so the param-24 cycle can retry a wrong latch. Pre-latch fallback
  polls deliver shValid=false so TOTAL coefficients never blend into the
  AMBIENT-basis smoothSH EMA (brightness pumping).
- **Dead duplicate struct chain deleted** (the old shLight/dirLight/ambLight at
  xr_renderer.cpp:1449-1463 incl. stale ":1457 padding" comment).
- Light-estimation function statics (frameCount/errCount/logCount) hoisted to
  instance members (lightFrameCount_/lightErrCount_/lightLogCount_) reset per
  estimator — process-lifetime statics survived the documented same-process
  renderer re-creation and suppressed warm-up + first-5 diagnostics.
- 'XR Light' logcat: first 5 valid estimates + (debug builds only) one line per
  ~150 valid polls (~6s) with a `sh=AMBIENT|TOTAL|off` tag for lamp-moving
  verification. Sensor HUD line now appends `FOLLOW az=.. el=..`.
- New JNI: nativeSetLightShAmbient(Boolean) — private external fun (Invariant
  8 pattern), called only on state change. **41-float buffer layout UNCHANGED**
  (no contract-test edit needed).

### Process note
Implementation was reviewed by a 20-agent adversarial workflow (4 lenses ×
verify-each-finding); 16 confirmed findings → 9 distinct fixes, all applied and
re-build-verified. The three majors (hard-fail fallback gap, cache-replay
defeating the un-stick, clamp-vs-hysteresis deadlock) are called out inline
above.

### ✅ VERIFIED ON GALAXY XR 2026-06-10 (same day, evening session)

R2 confirmed working on hardware. User verdict: "looks good!" Findings:

1. **Direction sign convention is INVERTED from the docs.** The runtime reports
   the direction light TRAVELS (ceiling-lit room → consistent -Y), NOT "toward
   the light" as developer.android.com claims. Fixed by negating at consumption
   (FilamentModelActivity, comment marks it HARDWARE-VERIFIED). Without the
   negation the key light pinned to the 5° elevation clamp floor.
2. **AMBIENT-kind SH WORKS on this runtime** — `sh=AMBIENT` streamed
   continuously, no fallback, no latch. Double-counting fix fully live. The
   latch-clear-on-re-engage also verified (AMBIENT resumed after a flip-off).
3. **Emitter-drag flip-off verified live** (fired twice, logged with reason).
   FOOTGUN: default emitterPos (0.3, 1.5, 0.5) sits in the model spawn zone —
   gripping near the girl can steal the grab and silently kill Follow.
   Consider relocating the default emitter or gating its grab on menuVisible.
4. NOT yet verified: scene-load exemption, useSH un-stick timing, long-run
   ceiling-light azimuth-freeze stability. All low-risk; verify opportunistically.

### Bonus runtime discoveries (startup extension dump, OTA'd runtime)
- **`XR_ANDROID_light_estimation_cubemap v1` IS enumerated** — R5 Tier A's
  "Revision 2 only" dismissal (xr_light_estimation.h:19) is WRONG, as the plan
  suspected. Specular IBL from a live room probe is on the table.
- **`XR_ANDROID_recommended_resolution v1` present** — R7 item 5 is real.
- Recommended per-eye res 1856x2160, max 3152x3682; refresh rates 60/72/90.

### Galaxy XR regressions found+fixed this session (NOT R2-related)
1. **Viewer bounced to desktop on every launch** ("welcome back" sound): a
   runtime OTA added a BoundarySetupActivity interceptor to every unmanaged-XR
   launch; when its empty task finished, focus returned to the LAUNCHING task,
   demoting the viewer (session killed ~0.5s after FOCUSED). FIX: removed
   FilamentModelActivity's separate android:taskAffinity (manifest) so the
   viewer shares MainActivity's task and IS the focus-return target. The
   affinity dated from c59e6e2 (March "focus loss" shotgun fix) — its
   lifecycle/session-state handling was the real cure back then.
2. **"Invisible walls" clipping the dancer**: SceneOcclusionManager's
   depth-only occluders persisted after Room Track OFF (stale plane geometry
   from a bad post-reboot room scan kept clipping models). FIX: param 17 now
   wires sceneOcclusion.enabled to follow roomTrackingEnabled (&& the
   occlusionEnabled setting). The "pixelated walls at distance" the user saw
   were the aliased shadow-catcher planes at the bogus plane positions (no
   MSAA yet — R1).
3. **MainActivity startup crash (~50% of launches, UNFIXED framework bug)**:
   SIGABRT in Samsung's android.extensions.xr.node.Node.setIsRenderableAndAttached
   (NPE: View.getWindowToken on null) when a CPM panel transform update races
   panel View attach. Jetpack XR runtime alpha12 + OTA'd shell. Workarounds:
   relaunch (race is ~50/50), or launch the viewer directly — manifest now sets
   FilamentModelActivity exported=true so
   `adb shell am start -n com.ashairfoil.prism/.FilamentModelActivity` works.
   Real fix candidates: delay panel/Session creation until after first
   onWindowFocusChanged, or update androidx.xr alphas.

### Device access notes
- Galaxy XR wireless adb: `adb tcpip 5555` from USB, then connect to its wlan
  IP (was .177, then .179 after reboot — DHCP, CHECK EACH TIME). tcpip mode
  does NOT survive reboot; needs USB re-arm. Quest 3 was at 192.168.1.156.
- adb.exe lives at %LOCALAPPDATA%\Android\Sdk\platform-tools\ (not on PATH).
- Native log tags are `ChloeVR-XRRenderer` / `ChloeVR-OpenXR` / `ChloeVR-ModelActivity` —
  CLAUDE.md's `logcat -s OpenXR:* ChloeVR:*` filter is STALE and misses them.
- Old (pre-2026-06-10) install was signed with the Kali machine's debug key —
  reinstalls from this Windows box needed one uninstall; scenes + prefs were
  backed up via run-as to C:\Users\coldb\ChloeVR\galaxyxr_backup_2026-06-10\
  and restored.
- Picker-crash workaround:
  `adb shell am start -n com.ashairfoil.prism/.FilamentModelActivity`
  (viewer is exported now) — skips MainActivity entirely.

## [Claude] 2026-06-10 — session close + go-forward queue

All of the above committed as 19648f8 and pushed; **master fast-forwarded to
the quest-port line and pushed — master is the main branch again** (quest-port
left as an identical label). Working tree clean except the untracked
galaxyxr_backup_2026-06-10/ (user data — never commit).

**USER DIRECTIVE: implement EVERYTHING — all of RENDERING_REALISM_PLAN.md and
all of DANCE_REALISM_PLAN.md. Agreed order:**
1. **R1 (4x MSAA + specular AA) — NEXT.** User specifically noticed the edge
   pixelation at distance on Galaxy XR today.
2. Then D1+D3 (dance quick wins: beat-grid epoch/Float fix + anticipation;
   skeletal breath + idle layer), then continue down both plans.

**WARNING for R1 (and all future recs):** both plans' line-number cites were
verified at audit time (2026-06-10 morning) — R2/R6 have since landed and
shifted GlesModelRenderer.kt, xr_renderer.cpp, and FilamentModelActivity.kt.
Re-verify every cite before editing. R1's verifier notes also reference attach
sites (renderUiOverlay, bloom passes) that must ALL be made MSAA-consistent.

R2 follow-ups parked (cheap, opportunistic): emitter default position overlaps
model spawn (grab steals → Follow flips off — relocate or gate on menuVisible);
scene-load exemption + useSH un-stick timing still unverified on device;
MainActivity panel-race crash (framework) — consider delaying panel/Session
creation until after first onWindowFocusChanged, or bumping androidx.xr alphas.

## [Claude] 2026-06-10 — R1 implemented (4x MSAA + specular AA)

R1 from RENDERING_REALISM_PLAN.md implemented end-to-end. `assembleDebug` BUILD
SUCCESSFUL both flavors; all three new JNI symbols confirmed exported in the
built arm64 .so via llvm-nm. NOT yet verified on-head: the galaxyxr APK was
installed on the Galaxy XR (wireless adb @ 192.168.1.179) and the viewer
launched, but the session stayed at IDLE — headset not being worn, so the GL
renderer (where MSAA init lives) never spun up. Quest 3 not connected.

### What landed
- **JNI shim** (renderer_jni_bridge.cpp, bottom of file): nativeInitMsaaExt
  (GL_EXT_multisampled_render_to_texture extension-string gate +
  eglGetProcAddress for glFramebufferTexture2DMultisampleEXT /
  glRenderbufferStorageMultisampleEXT + GL_MAX_SAMPLES_EXT query; returns max
  samples or 0), plus the two pass-through entry points. Pure GL, no
  g_renderer/g_mutex. Kotlin externals are `private external fun` per
  Invariant 8.
- **attachEyeColor() helper** — EVERY color attach to the shared eye FBO
  routes through it (renderEye, renderBloom pass 4, renderUiOverlay): with
  implicit resolve, ONE plain glFramebufferTexture2D mid-frame demotes the
  attachment to single-sample and the 4x depth RB makes the FBO incomplete.
  DISCOVERY: renderUiOverlay is DEAD CODE (zero call sites — the menu panel
  goes through the native UI quad composition layer); made consistent anyway.
  Bloom pass 4 was the only live mid-frame re-attach.
- **ensureEyeDepth() helper** — depth realloc gated on size AND sample-count
  change (lastDepthSamples), so the runtime fallback transition reallocates.
- **Clean fallback**: FBO-incomplete while MSAA on → log, msaaSamples=0, skip
  that eye; the other eye recovers the same frame (samples-mismatch realloc).
  Init caps at min(4, device max), 0 if EXT missing → plain single-sample.
- **Depth invalidate** (preallocated IntArray — Invariant 6): in
  finishEyePass (bind fbo → invalidate GL_DEPTH_ATTACHMENT → bind 0) and in
  renderBloom BEFORE the lazy-init/pass-1 FBO switch, so multisampled depth
  is never stored to memory.
- **Shader specular AA** (FRAGMENT_SHADER, right after the MR fetch, before
  `alpha = roughness * roughness`): fp16-safe floor `max(roughness, 0.089)` +
  Vlachos GDC2015 geometric term from dFdx/dFdy(vNormal). Single mutation
  point feeds key light D/G, fill light fD/fG, AND env specular consistently.
- xr_renderer.cpp UNCHANGED (swapchain sampleCount already 1 at both creation
  sites — required by the implicit-resolve path). Shadow-map FBO untouched.

### Process note
Reviewed by a 19-agent adversarial workflow (4 lenses × 3 refuter-verifiers
per finding): 5 findings raised, 0 confirmed — all refuted by majority vote.
One refuted-as-inconsequential ordering nit (bloom depth-invalidate landing
after the lazy-init FBO switch on bloom-(re)alloc frames) was fixed anyway
since the reorder was free.

### ON-HEAD VERIFICATION CHECKLIST (next time someone wears a headset)
Tag is ChloeVR-GLRenderer (Kotlin) / ChloeVR-XRRenderer (native shim). Expect:
1. `MSAA EXT ready: max N samples` (native) then `MSAA 4x enabled (EXT
   implicit resolve, device max N)` — confirms enumeration on BOTH devices.
2. `MSAA 4x eye FBO complete WxH (glGetError=0x0)` — one-shot, confirms the
   sRGB swapchain + implicit-resolve + foveated-swapchain (SCALED_BIN bit)
   combination actually composes. THE critical Samsung-runtime check.
3. If instead `Eye FBO incomplete with 4x MSAA: 0x... — disabling MSAA`
   appears: the clean fallback fired; capture the hex status. App keeps
   rendering single-sample (today's behavior) — not a regression, but R1's
   payoff is off. 0x8D56 = SAMPLE mismatch — would point at the foveation
   SCALED_BIN interaction.
4. Visual: edge pixelation at distance (the thing the user noticed on Galaxy
   XR) should be visibly reduced; specular highlights on skin should stop
   crawling/shimmering during dance motion (shader AA, active even if MSAA
   were to fall back).
5. Perf sanity: budget says +0.5-1.5 ms GPU. Watch the perf HUD / thermal
   ladder for unexpected escalation, esp. with bloom ON (early-resolve cost).

## [Claude] 2026-06-10 — D1 + D3 implemented (beat-grid epoch + anticipation; skeletal breath + idle layer)

Both recs from DANCE_REALISM_PLAN.md, same session as R1. `assembleDebug`
BUILD SUCCESSFUL both flavors; installed on Galaxy XR (@.179) alongside R1.
NOT yet verified on-head (session was IDLE — headset unworn).

### D1(A) — clocks/grids
- ALL bpmEpochMs sites migrated wall-clock → SystemClock.uptimeMillis
  (phaseAt, phaseInBars, halve/doubleBpm, tapTempo, update, snapshot).
  AudioReactor.lastCaptureMs deliberately stays WALL clock (UiRenderer pairs
  it with System.currentTimeMillis).
- Epoch now anchors to lastBassRiseMs when <2s old at lock (plan trap 3) —
  phase 0 sits on a real kick, not the update() tick that noticed the lock.
- FrameSnapshot += epochMs; the three glute grids divide
  (nowMs - epochMs) — the old absolute-ms division had 131s Float ULP
  (indices frozen ~2.2 min, then jumping thousands of sub-beats).
- **DISCOVERY (same bug class, audit missed it):** `tSec = snap.nowMs/1000f`
  also Float-froze — fBm amplitude drift, ±5% phase slop, warmth jitter, and
  the old breath were all stair-stepping in ~131s plateaus. Fixed via new
  monoClockBaseMs session base. Expect the dance to feel subtly less
  "static" on-head purely from this.

### D1(B) — anticipation
- Per-axis POSITIVE leads (leadMs = 60+60·physics ≈ 81ms default) on yaw,
  pitch, bob, pelvis-thrust, squash lookups. Old yaw-only -45ms·physics lead
  deleted (sign-inverted = a LAG, plan trap 2). **VERIFY DIRECTION ON HEAD.**
- Bob/squat weight-arrival recentered: +kSkew phase offset puts the DEEPEST
  point (root drop dominates) on the grid beat; descent = the fast kSkew
  segment INTO the beat. GATED on the squat actually driving (skinned +
  L/R_Calf + Root found) AND hasTempo — else the upward py bump's minimum
  already sits at lookup 0 and recentering would invert arrival.
- Impact kicks free-run the epoch grid shifted 72ms early (envelope peak
  sin(πk)(1-k) @ k≈0.36) so the PEAK lands on the beat; beatCounter fallback
  kept for no-lock. NOTE: kicks now fire through breakdowns when locked
  (same "her ass is the metronome" policy as the glute tick).
- Fill window lead-shifted (leaded phase4bar) so the fill rate-flip happens
  where ALL leaded lookups ≡ 0 — keeps the previously pop-free fill
  boundary pop-free with leads active.

### D3 — idle layer
- NEW `scene/IdleLayer.kt` (internal object), called for every skinned
  non-preview model AFTER the dance pass, outside the audioReactor gate, on
  its own uptime base: breath Spine01/02 X @0.22Hz + clavicle -X lift at 90°
  lag + Head X counterphase; Waist X breath-pitch @0.3Hz + Waist Z
  two-incommensurate-sine weight sway; Head Y micro-yaw ±1.5° retargeted
  4-8s (hash-decorrelated per model), eased 0.4s; Root X ±5mm CoM wander
  @0.05Hz (STATUE ONLY — dance owns Root while dancing). All ×(1+0.5·ml),
  ml=0 unless reactor enabled.
- Compose-ordering contract: dancing models get composes on the dance's
  fresh REPLACE writes; statues get reset-to-bind first each frame (no
  accumulation). Dance-stop falling edge does ONE resetAllToBind
  (m.idleWasDanced) — without it knees/thighs froze bent while Root
  re-bound, floating her feet cm above the passthrough floor.
- Whole-model breath bob capped 3mm → 1mm; model-space breath pitch now
  NON-skinned only (skinned get Waist X instead). Feet stay planted.
- SkeletonRuntime += composeJointTranslation; PlacedModel += 5 idle fields.

### Review process
4-lens × 3-refuter adversarial workflow (64 agents): 20 findings → 13
confirmed (7 distinct after cross-lens dedup) → ALL 7 fixed + re-built:
kick-fallback poisoning (epoch grid index vs beatCounter '>' = kicks dead
for minutes after BPM-lock toggle — now glute-shaped '!='), leadPhBob
rate-mismatch on thrust/squash (up to 8× lead during fills — leadPhBobBase),
recenter inversion on non-squat models (squatDrives gate), NEW fill-boundary
knee/root pop (lead-shifted fill window), dance-stop floating statue
(falling-edge resetAllToBind), head-retarget lockstep (h2 decorrelation),
tempo-less static pose = deepest squat (hasTempo gates).

### ON-HEAD VERIFICATION (D1/D3 additions to the R1 checklist)
1. Glute shaker/sync grids: burst should fire on the LAST beat of each bar
   every bar (was: rare bursts ~2min apart). Tap-tempo + no music should
   tick glute pops AND impact kicks on the metronome.
2. Anticipation direction: at default physics 0.35 she should read as
   landing ON/with the beat, not dragging after it. If she reads EARLY,
   reduce leadMs base (60+60·physics) before touching signs.
3. Idle: non-dancing model must breathe (chest/shoulders), sway, and glance
   — NOT a statue; feet rock-solid on the floor (no 3mm hover cycle).
   Stop a deep-squat dance → she stands up to bind pose, feet ON floor.
4. IMPROV fill bars: no knee/root snap at fill start/end.
5. Multi-model: heads must NOT flick in unison.
