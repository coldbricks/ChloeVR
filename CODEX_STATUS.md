# Codex Fix Report
**Date:** 2026-03-29 00:30 UTC
**Commit:** 25d748e (with local uncommitted fixes)

## Fixed
- [x] BUG 1: Swapchain ghost trails — UI swapchain image now uploads every visible frame using cached flipped bitmap, not only on new pending bitmap.
- [x] BUG 2: Shadow map caching — verified `renderShadowMap()` currently recomputes light matrix and re-renders depth every frame (no stale transform cache path in current code).
- [x] BUG 3: Fill light coordination — fill light direction now recomputes from main light in both `updateLightDirFromAngles()` and `updateLightFromEmitter()`.
- [x] BUG 4: Per-frame allocations — preallocated main-loop UI gradient, blur filters, cursor paints, parameter/action-button paints, and removed hot-path HSV `floatArrayOf` allocations.
- [x] BUG 5: xrBeginSession error handling — `openxr_input.cpp` now logs failed `xrBeginSession` result but still sets session as ready/running for Samsung runtime compatibility (`xr_renderer.cpp` already behaved this way).

## Changes Made
- `prism/app/src/main/kotlin/com/ashairfoil/prism/FilamentModelActivity.kt`
  - Reworked compositor quad upload path to:
  - Copy latest pending bitmap to flip buffer before acquire.
  - Upload flip buffer on every frame while menu is visible.
- `prism/app/src/main/kotlin/com/ashairfoil/prism/GlesModelRenderer.kt`
  - Added `updateFillLightDirFromMainLight()`.
  - Called helper from `updateLightDirFromAngles()` and `updateLightFromEmitter()`.
- `prism/app/src/main/kotlin/com/ashairfoil/prism/scene/UiRenderer.kt`
  - Added preallocated shaders/paint/filter objects for hot paths.
  - Removed per-frame `Paint()` allocations in cursor/title/theme/action-button/sensor sections.
  - Replaced per-frame `BlurMaskFilter(...)` calls with preallocated fields.
  - Replaced per-frame `floatArrayOf(...)` in HSV conversion with reusable scratch array.
- `prism/app/src/main/cpp/openxr_input.cpp`
  - Added `xrBeginSession` result logging on failure while preserving ready/running behavior.

## Build Status
```text
BUILD SUCCESSFUL in 17s
39 actionable tasks: 8 executed, 31 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.0.0/userguide/configuration_cache_enabling.html
```

## Testing Notes
- Built successfully with `cd /home/kali/ChloeVR/prism && ./gradlew assembleDebug`.
- Hardware validation still required on Galaxy XR for:
  - B-menu cursor trail regression check.
  - Shadow behavior while grabbing/moving models.
  - Session startup behavior across multiple cold launches.
