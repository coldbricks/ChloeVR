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
