# For Codex: I Broke Things — Love, Claude

**Date:** 2026-03-28
**Branch:** master
**Current state:** Reverted to commit 1740d0a for 9 key files. The other ~23 files from the earlier session's 32-file change remain at HEAD (25d748e). Build is green.

---

## What Happened

An earlier Claude session today made a 32-file "optimization" pass. I (a later Claude session) committed and pushed it as `25d748e` without properly testing. Then I spent the entire session trying to patch bugs introduced by those optimizations, making things progressively worse. I have now reverted the 9 most critical files back to `1740d0a` (the last known working state from March 22).

**The reverted files are:**
1. `scene/UiRenderer.kt` — double-buffer attempt caused swapchain ghost trails
2. `FilamentModelActivity.kt` — swapchain upload ordering was broken
3. `GlesModelRenderer.kt` — shadow caching never invalidated, fill light hardcoded
4. `scene/InputHandler.kt` — scratch array changes, GLB picker row math
5. `MainActivity.kt` — activity transition flags, deprecated API migration
6. `playback/SubtitleRenderer.kt` — async loading broke caller
7. `openxr_input.cpp` — xrBeginSession error handling blocked session init
8. `xr_renderer.cpp` — same xrBeginSession issue
9. `AndroidManifest.xml` — task affinity/launch mode changes broke model viewer

**Files that were NOT reverted** (changes from 25d748e still present — believed safe):
- `build.gradle.kts`, `proguard-rules.pro`
- `AudioReactor.kt`, `FilamentRenderer.kt`, `FileNameParser.kt`, `SensorHub.kt`
- `billing/ProUpgrade.kt`, `data/DeoVrApi.kt`, `data/FunscriptParser.kt`
- `data/MediaLibrary.kt`, `data/StreamingBrowser.kt`, `data/KeyframeSettings.kt`
- `effects/AnaglyphEffect.kt`, `effects/ColorGradingEffect.kt`
- `effects/LensDistortionEffect.kt`, `effects/SharpeningEffect.kt`
- `haptics/BleDeviceManager.kt`, `haptics/SpectralAnalyzer.kt`
- `haptics/BeatDetector.kt`, `haptics/ChloeVibesEngine.kt`, etc.
- `playback/HeadTrackingConfig.kt`, `playback/ScreenController.kt`, `playback/PlaylistManager.kt`
- `scene/SceneManager.kt`, `scene/XrSensorPoller.kt`, `scene/VirtualKeyboard.kt`
- `settings/SettingsManager.kt`, `settings/LightingPresets.kt`
- `res/xml/network_security_config.xml`

---

## Bugs That Need Fixing (What I Failed At)

### BUG 1: Swapchain Ghost Trails on B Menu (CRITICAL)

**Symptom:** When the laser cursor moves across the B-button menu in FilamentModelActivity, you see 2-3 historical positions of the cursor trailing behind — like a radar sweep display.

**Root Cause:** The OpenXR UI quad compositor layer uses a swapchain with 2-3 rotating images. The current code only uploads the UI bitmap when `pendingUiBitmap != null`. On frames where the UI thread hasn't produced a new bitmap, the acquired swapchain image retains its STALE content from whenever it was last written (possibly 2-3 frames ago). As the swapchain rotates, each image shows the cursor at a different historical position.

**The Fix:** Every frame that the menu is visible, upload the most recent UI content to whichever swapchain image is acquired — even if the UI bitmap hasn't changed since last frame. Cache the last-uploaded bitmap (the flip buffer already serves this purpose) and re-upload it every frame.

**File:** `FilamentModelActivity.kt` ~line 768. The `if (bmp != null)` block needs restructuring so that the swapchain acquire + texSubImage2D happens EVERY frame when `menuVisible`, not only when a new bitmap is pending.

**Additional concern:** The single-bitmap `renderBitmap` is shared between the UI thread (writer) and render thread (reader) with no synchronization beyond `@Volatile` on the reference. The earlier session attempted double-buffering (A/B bitmaps) but the flip timing created a race where the render thread could read a buffer that the UI thread had wrapped back around to. If you implement double-buffering, the render thread must copy the bitmap to the flip buffer IMMEDIATELY upon capture — before `nativeAcquireUiImage()` which can block for a full frame.

---

### BUG 2: Shadow Map Doesn't Follow Model Movement (CRITICAL)

**Symptom:** When a model is grabbed and moved, its shadow stays frozen at the original position.

**Root Cause:** The earlier session added shadow map caching in `GlesModelRenderer.renderShadowMap()` that only invalidated on light direction change or model count change. Model transforms (position/rotation via `modelMatrix`) were never tracked. Since light doesn't change during a grab, the cache never invalidates.

**The Fix:** Remove the caching entirely — the shadow depth pass is cheap (a few models into a 2048 FBO, depth-only). OR cache the light-space matrix separately (it only depends on `lightDir` and `shadowSpread`) but always re-render the actual shadow depth map every frame.

**File:** `GlesModelRenderer.kt` `renderShadowMap()` ~line 1066

---

### BUG 3: Fill Light Not Coordinated With Main Light (MEDIUM)

**Symptom:** Changing azimuth/elevation or dragging the sun emitter only moves the main light. The fill light stays in a fixed hardcoded direction, so lighting looks wrong from certain angles.

**Root Cause:** `fillLightDir` is initialized to `floatArrayOf(-0.5f, -0.3f, 0.3f)` and NEVER updated. `updateLightDirFromAngles()` and `updateLightFromEmitter()` only set `lightDir`. LightingPresets store `fillLightIntensity` but not `fillLightDir`.

**The Fix:** In `updateLightDirFromAngles()` and `updateLightFromEmitter()`, after setting `lightDir`, compute `fillLightDir` as the normalized opposite direction with a slight upward bias:
```kotlin
fillLightDir[0] = -lightDir[0]
fillLightDir[1] = 0.2f
fillLightDir[2] = -lightDir[2]
// normalize
```

**File:** `GlesModelRenderer.kt` `updateLightDirFromAngles()` ~line 268, `updateLightFromEmitter()` ~line 1030

---

### BUG 4: Per-Frame Allocations in Render Loop (MEDIUM)

**Symptom:** Menu rendering causes GC pressure. Each frame allocates:
- `BlurMaskFilter` objects (3-9 per frame, native allocations)
- `LinearGradient` for background (new shader object per frame)
- `intArrayOf` / `floatArrayOf` for gradient parameters
- `Paint()` objects in some sub-menu paths

**The Fix:** Pre-allocate all `BlurMaskFilter` variants as class fields. Pre-allocate the background `LinearGradient` (dimensions are fixed at 1024x1280). Replace inline `Paint()` with reusable pre-allocated instances. See the zero-alloc render loop requirement in CLAUDE.md.

**File:** `UiRenderer.kt` — field declarations and all `BlurMaskFilter(...)`, `LinearGradient(...)`, `Paint()` calls inside `renderUiToBitmap()`

---

### BUG 5: xrBeginSession Error Handling (LOW)

**Symptom:** Intermittent "failed to initialize" on some launches.

**Root Cause:** The earlier session added `XR_SUCCEEDED(r)` checks around `xrBeginSession` in both `openxr_input.cpp` and `xr_renderer.cpp`. When the call returns a non-zero code (which Samsung's runtime occasionally does), the new code skips setting `sessionReady_ = true`, causing the render loop to never start.

**The Fix:** Log the non-success code but always set `sessionReady_` and `running_` to true — same as the original behavior. The Samsung Galaxy XR runtime's return codes don't always match Khronos spec expectations.

**Files:** `openxr_input.cpp` `handleSessionStateChange()`, `xr_renderer.cpp` `handleSessionStateChange()`

---

## How to Notify Claude That Bugs Are Fixed

When you fix a bug, update or create the file:

```
/home/kali/ChloeVR/CODEX_STATUS.md
```

Use this format:

```markdown
# Codex Fix Report
**Date:** YYYY-MM-DD HH:MM
**Commit:** <short hash>

## Fixed
- [ ] BUG 1: Swapchain ghost trails — <one-line description of fix>
- [ ] BUG 2: Shadow map caching — <one-line description of fix>
- [ ] BUG 3: Fill light coordination — <one-line description of fix>
- [ ] BUG 4: Per-frame allocations — <one-line description of fix>
- [ ] BUG 5: xrBeginSession error handling — <one-line description of fix>

## Changes Made
<list of files changed with brief description>

## Build Status
<output of ./gradlew assembleDebug | tail -3>

## Testing Notes
<any notes about what was tested or what still needs hardware testing>
```

Claude will read `CODEX_STATUS.md` at the start of the next session to understand what was fixed. Mark each bug with `[x]` when complete.

---

## Reference

- **CLAUDE.md** — Architecture invariants, platform traps, build commands, file map
- **NOTES.md** — Inter-agent session notes, SDK limitations
- **ISSUES_FOR_CODEX.md** — Older issue list (March 18), some items may be resolved
- **Build:** `cd /home/kali/ChloeVR/prism && ./gradlew assembleDebug`
- **Install:** `/home/kali/android-sdk/platform-tools/adb install -r prism/app/build/outputs/apk/debug/app-debug.apk`
- **Logs:** `/home/kali/android-sdk/platform-tools/adb logcat -s ChloeVR:* OpenXR:* AndroidRuntime:*`
- **Android XR Docs:** https://developer.android.com/develop/xr/jetpack-xr-sdk/build-immersive
- **OpenXR headers:** `~/.gradle/caches/9.0.0/transforms/*/openxr_loader_for_android-1.1.49/prefab/modules/headers/include/openxr/`

---

*Written with humility by Claude Opus 4.6, who learned the hard way that premature optimization is the root of all evil, and that you should ALWAYS test on hardware before committing 32 files of untested changes.*
