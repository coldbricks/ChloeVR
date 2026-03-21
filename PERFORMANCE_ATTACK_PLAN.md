# ChloeVR Performance Attack Plan

**Target:** Samsung Galaxy XR, 120Hz stereo, 8.3ms frame budget
**Methodology:** Profile-driven, tier-by-tier. Build + verify after every tier.

---

## Tier 0 — Render Loop (120Hz x 2 eyes = 240 calls/sec per function)

These live in the hottest code path. Every microsecond matters.

### T0-1: Cache ALL shader uniform locations at init

**Files:** `GlesModelRenderer.kt`
**Impact:** Eliminates ~50 per-frame `glGetUniformLocation` calls (string hash + lookup).

Currently cached at `init()` (lines 217-241): main model shader uniforms (uMVP, uModelView, etc.)
NOT cached — fetched inline every render call:

| Function | Line(s) | Uniform | Calls/frame |
|----------|---------|---------|-------------|
| `renderEye` | 1074 | `uClipY` | 2 |
| `renderEye` | 1075 | `uUseSH` | 2 |
| `renderEye` | 1077 | `uSH` | 2 (when SH active) |
| `renderEye` | 1087 | `uModel` | 2 x N models |
| `renderGrid` | 1151-1165 | uMVP, uGridY, uGridScale, uAlpha, uShadowMVP, uShadowDarkness, uShadowSoftness, uLightSize, uShadowMap | 2 x 9 = 18 |
| `renderColorWash` | 1204 | `uColor` | 2 |
| `renderShadowPlanes` | 1226-1230, 1253-1254 | uShadowMap, uShadowMVP, uShadowDarkness, uShadowSoftness, uLightSize, uMVP, uModel | 2 x (5 + 2*N planes) |
| `renderGizmo` | 1350, 1356 | uMVP, uHighlight | 2 x (1 + 3 axes) |
| `renderEmitter` | 880-881 | uMVP, uHighlight (reuses gizmoProgramId) | 2 x 2 |
| `renderLaser` | 1418-1422, 1442-1443 | uMVP, uColor, uLenScale (x2 for dot) | 2 x 5 |
| `renderUiOverlay` | — | Already cached (uiUMvp, uiUTex) | 0 |
| `renderBloom` | 1569-1604 | uScene, uThreshold, uTex, uDirection(x2), uBloom, uIntensity | 2 x 7 |
| `renderOcclusionPlanes` | 935 | uMVP (via shadowDepthProgramId) | 2 |
| `renderPlaneVisualization` | 1281-1284 | uMVP, uModel, uPlaneColor, uTime | 2 x 4 |

**Action:** Add ~30 new cached uniform fields. Fetch once in `init()` after each program is created.

### T0-2: Eliminate per-frame FloatArray allocations in render functions

**Files:** `GlesModelRenderer.kt`
**Impact:** Removes GC pressure from 120Hz render loop.

| Function | Line | Allocation | Fix |
|----------|------|-----------|-----|
| `renderGrid` | 1147 | `floatArrayOf(...)` — 16-float gridModel matrix | Preallocate `scratchGridModel` field |
| `renderLaser` | 1407 | `floatArrayOf(0f, 0.8f, 1f)` — default color param | Preallocate `DEFAULT_LASER_COLOR` companion const |
| `renderLaser` | 1434-1438 | `floatArrayOf(...)` — 16-float dotModel matrix | Preallocate `scratchDotModel` field |
| `renderUiOverlay` | 1493-1498 | `floatArrayOf(...)` — 16-float panelModel | Preallocate `scratchPanelModel` field |
| `renderEmitter` | 870-872 | `floatArrayOf(...)` — 16-float emitter model | Preallocate `scratchEmitterModel` field |
| `renderOcclusionPlanes` | 952 | `floatArrayOf(...)` per plane (loop) | Preallocate `scratchPlaneModel` field |
| `renderShadowPlanes` | 1244 | `floatArrayOf(...)` per plane (loop) | Reuse same `scratchPlaneModel` |
| `renderPlaneVisualization` | 1296-1302 | `floatArrayOf(...)` per-plane color | Preallocate 4 color constants |
| `renderPlaneVisualization` | 1312 | `floatArrayOf(...)` per plane model | Reuse `scratchPlaneModel` |
| `testGizmoHit` | 1368-1371 | 3x `floatArrayOf` axes | Preallocate companion const arrays |
| `getGizmoWorldAxis` | 1391-1395 | `floatArrayOf` per call + result array | Preallocate + write into caller-provided buffer |
| `rayLineClosest` (if exists) | ? | Pair return | Reuse result container |

### T0-3: Skip disabled render passes entirely

**Files:** `FilamentModelActivity.kt` (caller, lines 791-843), `GlesModelRenderer.kt`
**Impact:** Each skipped pass saves draw calls + state changes.

Current state:
- `renderColorWash`: Already skips when `a < 0.005f` (line 1178) -- GOOD
- `renderBloom`: Already skips when `!bloomEnabled || intensity <= 0` (line 1555) -- GOOD
- `renderGrid`: Called unconditionally at line 817/833 with `gridAlpha` param. Should skip at caller when `!gridVisible` or `gridAlpha <= 0`
- `renderPlaneVisualization`: Already skips when `!showPlaneVisualization` (line 1268) -- GOOD

**Action:** In `FilamentModelActivity.kt`, guard `renderGrid` call with visibility check (likely already has `gridVisible` or similar). Verify no render passes run when their output is invisible.

---

## Tier 1 — Input & UI (per-frame, lower cost per call)

### T1-1: Preallocate arrays in InputHandler.kt

**Files:** `scene/InputHandler.kt` (1736 lines)
**Impact:** Removes per-frame allocs from input processing.

Items to find and fix:
- `floatArrayOf(...)` in per-frame input processing methods
- `Pair` creation in gizmo hit tests — replace with mutable result holder
- `mutableListOf(...)` in hot paths

### T1-2: Preallocate arrays in OpenXRInput.kt

**Files:** `OpenXRInput.kt` (120 lines)
**Impact:** Removes allocs from 120Hz polling path.

Items to find:
- Hand/aim array creation per poll
- FloatArray buffer creation

### T1-3: UiRenderer — skip texture upload when unchanged

**Files:** `scene/UiRenderer.kt` (1777 lines)
**Impact:** Avoids redundant `glTexSubImage2D` for identical pixels.

Items to investigate:
- Is the bitmap created fresh each render or reused?
- Are Paint objects cached?
- Can we track a dirty flag and skip upload?
- Coalesce multiple `requestUiRender()` into one dirty-frame render

### T1-4: Reuse Paint objects in UiRenderer

**Files:** `scene/UiRenderer.kt`
**Impact:** Eliminates per-draw Paint allocations (each Paint = object + native alloc).

---

## Tier 2 — File Picker & Media (event-driven, not per-frame)

### T2-1: Stop sorting full file list on every progressive callback

**Files:** Find file picker code (likely `data/MediaLibrary.kt` or `MainActivity.kt`)
**Impact:** Reduces CPU spikes during scan.

### T2-2: Seek bar loop singleton + cleanup

**Files:** `MainActivity.kt`
**Impact:** Prevents duplicate handler chains, orphan handlers.

### T2-3: Move MediaMetadataRetriever off main thread

**Files:** Various
**Impact:** Prevents UI jank during metadata extraction.

---

## Tier 3 — Thread Safety & Correctness

### T3-1: Make cross-thread flags atomic

**Files:** Multiple
**Impact:** Prevents race conditions.

Items:
- `uiNeedsRefresh` — should be `@Volatile` or `AtomicBoolean`
- Pending load flags
- Sensor enable flags

### T3-2: Fix permission declarations

**Files:** `AndroidManifest.xml`
**Impact:** Prevents runtime crashes on Android 12+.

Items:
- `ACCESS_COARSE_LOCATION` must accompany `ACCESS_FINE_LOCATION`
- Verify `INTERNET`, `READ_MEDIA_AUDIO` present
- `MANAGE_EXTERNAL_STORAGE` → scoped storage / SAF for Play compliance

### T3-3: Add Locale to String.format calls

**Files:** Multiple
**Impact:** Prevents locale-sensitive formatting bugs.

---

## Tier 4 — Architecture Quick Wins

### T4-1: Replace magic numbers with named constants

**Files:** Multiple
**Impact:** Readability + easier tuning.

Candidates: poll intervals, thresholds, colors, scales, buffer sizes.

### T4-2: Extract plane model matrix builder

**Files:** `GlesModelRenderer.kt` (lines 944-957, 1236-1249, 1305-1317)
**Impact:** Same matrix construction repeated 3 times — extract to shared method writing into scratch buffer.

---

## Execution Order

```
Phase 1 (Render hot path — highest ROI):
  T0-1 → T0-2 → T0-3
  BUILD + VERIFY

Phase 2 (Input/UI):
  T1-1 → T1-2 → T1-3 → T1-4
  BUILD + VERIFY

Phase 3 (File picker + media):
  T2-1 → T2-2 → T2-3
  BUILD + VERIFY

Phase 4 (Correctness):
  T3-1 → T3-2 → T3-3
  BUILD + VERIFY

Phase 5 (Architecture):
  T4-1 → T4-2
  BUILD + VERIFY
```

---

## Not Doing (and why)

| Item | Reason |
|------|--------|
| Split large classes | Massive refactor, high risk, low perf impact |
| RecyclerView for file picker | Would require significant UI rewrite (current is Canvas-based VR panels) |
| Dynamic quality scaling | Needs frame-time instrumentation first — add in future pass |
| Frustum culling | Model counts are low (1-5 typically) |
| Baseline profiles | Requires physical device + macrobenchmark setup |
| Move OpenXR polling off main thread | Architecture change, currently coupled to render frame |
| Partial UI texture updates | Complex region tracking, diminishing returns after dirty-flag skip |
