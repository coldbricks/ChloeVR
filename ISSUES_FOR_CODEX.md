# ChloeVR Issues — Detailed Report for Codex

**Date:** 2026-03-18
**Branch:** master, commit e59b436
**Device:** Samsung Galaxy XR (Android XR)

---

## CRITICAL: Laser / Menu Interaction System is Broken

### 1. Laser passes through the menu panel
The laser beam renders through the menu panel instead of stopping at it. The hit dot should appear ON the panel surface, but instead the laser continues behind the panel and interacts with objects behind it.

**Root cause investigation so far:**
- `hitDistance` is set correctly when laser hits panel (line 323 in InputHandler.kt)
- But it was being overwritten by `hitDistance = -1f` at line 689 (model intersection else-branch)
- A fix was applied (`if (!laserOnPanel) hitDistance = -1f`) but it's still not working — the laser visual may not be using hitDistance correctly, or the fix doesn't cover all code paths

**Files:** `InputHandler.kt` lines 267-691, `GlesModelRenderer.kt` renderLaser()

### 2. No crosshair/dot on menu panel
When the laser hits the menu, there should be a visible hit dot/crosshair on the panel surface. Currently there's nothing — the user can't see where they're pointing.

**What's needed:** renderLaser() in GlesModelRenderer renders a dot at hitDistance. When `laserOnPanel` is true and hitDistance > 0, the dot should appear on the panel. Either the dot isn't being rendered, or it's rendering behind the panel due to depth test issues.

**Files:** `GlesModelRenderer.kt` renderLaser() (~line 1393), `InputHandler.kt` hitDistance/laserOnPanel

### 3. Panel Y coordinate mapping is wrong
The menu bitmap is 1024×1280 but the hit coordinate was calculated as `by = v * 1024f`. This was changed to `by = v * 1280f` but ALL the button hit regions throughout InputHandler.kt were written assuming the old `v * 1024f` mapping. Now they're all wrong by a factor of 1.25x.

**Affected hit regions that need recalculation:**
- Transport buttons: `by in 245f..300f` (line 355)
- Speed buttons: `by in 303f..331f` (line 363)
- A/B buttons: `by in 355f..383f` (line 368)
- Repeat button: `by in 405f..433f` (line 375)
- EQ presets: `by in 455f..483f` (line 377)
- Progress bar: `by in 200f..230f` (line 384)
- BROWSE/BACK: `by > (1024 - 80f)` (line 388) — this should be `by > (1280 - 80f)`
- File list: `startY = 140f` (line 346)
- Back button in picker: `by > (1024 - 80f)` (line 352) — should be 1280
- Param rows: `by in 130f..850f` (line 562) — these were designed for 1024 height
- Title bar: `by < 85f` (line 330)
- Action buttons, model list, all other hit zones

**The core issue:** Either the bitmap should be 1024×1024 to match the coordinate system, or ALL hit regions need to be recalculated for 1280 height. The safest fix is probably to keep the coordinate system as 1024×1024 (revert `by = v * 1024f`) since all hit regions were designed for that, and accept that the bottom of the 1280 bitmap maps to y=1024 in hit-test space.

OR: use `by = v * 1280f` and scale ALL hit regions by 1280/1024 = 1.25x.

**Files:** `InputHandler.kt` — every `by in ...` range check, `UiRenderer.kt` — all Y coordinates in renderUiToBitmap()

### 4. Menu buttons unclickable in audio player mode
User cannot click transport buttons (play/pause, seek, etc.) in the audio player. The trigger click either doesn't register or the hover detection is misaligned due to the Y coordinate issue above.

**Files:** `InputHandler.kt` lines 339-398 (audio button hover), lines 759-798 (audio button click)

### 5. Audio restarts unexpectedly
Audio playback restarts when interacting with the menu. Possible causes:
- Clicking in the audio player area when no button is hovered may trigger a re-play
- The `runOnUiThread` block at line 763 runs even when `btn == -1` (no button hovered) — the `when` block won't match but ExoPlayer access on the UI thread may cause side effects
- BeatReactor restart at line 746 happens every time a file is selected

**Files:** `InputHandler.kt` lines 735-798, `AudioPlayer.kt`

### 6. Model grabs activate through the menu
When the user grips the controller while in the menu (e.g., to drag the panel), the model grab system can activate on the same frame or the next frame after the menu closes. The menu's `return` at line 1394 prevents grab during menu visibility, but:
- If grip is held when B closes the menu, the next frame immediately starts a grab
- Panel drag uses `inputBuffer[7]` (right grip) but model grab uses `rightSqueeze` — these may be the same value, causing both to trigger

**Files:** `InputHandler.kt` lines 1398-1416 (model grab), lines 578-599 (panel drag)

---

## MEDIUM: Panel Interaction UX

### 7. Panel drag only works well from title bar
The grip-to-drag was expanded from title-bar-only to anywhere on the panel, but this conflicts with button interactions. Gripping to click a button also starts a panel drag. Need to differentiate: trigger = click, grip = drag.

**Files:** `InputHandler.kt` lines 329-337

### 8. Panel push/pull via thumbstick
The thumbstick Y-axis panel distance adjustment works during drag (`panelGrabDist += stickY * 0.03f` at line 584) but the range and sensitivity may need tuning.

**Files:** `InputHandler.kt` lines 578-599

---

## MEDIUM: Audio Player UX

### 9. Audio player UI refresh is expensive
When audio is playing, the UI refreshes every 5 render frames (~14Hz). Each refresh allocates a 1024×1280 ARGB bitmap (5.2MB), draws the full UI with Canvas, and uploads via texSubImage2D. This causes frame timing jitter.

**Possible fixes:**
- Only refresh the progress bar region (partial texture update)
- Use a lower refresh rate for the audio player (every 18 frames like sensor HUD)
- Reuse the bitmap (attempted but caused cross-thread race — needs double-buffering)

**Files:** `FilamentModelActivity.kt` line 745, `UiRenderer.kt` renderUiToBitmap()

### 10. Audio seek bar hit region
The progress/seek bar hover at `by in 200f..230f` may be misaligned with the actual rendered bar position. The seek drag (`audioSeekDragging`) may not track smoothly.

**Files:** `InputHandler.kt` line 384, `UiRenderer.kt` seek bar rendering

---

## LOW: Visual Polish

### 11. Laser dot not visible on dark backgrounds
The hit dot is rendered as white lines (GL_LINES) which can be invisible against bright or white surfaces. Should use a contrasting color or have an outline.

**Files:** `GlesModelRenderer.kt` renderLaser()

### 12. Panel hover highlight doesn't match hit region
The visual highlight (glow rectangle) rendered in UiRenderer may not align with the actual hit detection regions in InputHandler, especially after the Y coordinate change.

**Files:** `UiRenderer.kt` row rendering, `InputHandler.kt` hover detection

---

## Architecture Notes for Codex

### Coordinate System
- Panel bitmap: 1024×1280 pixels (UiRenderer creates this)
- Panel world size: PANEL_WIDTH × PANEL_HEIGHT meters (0.9 × 1.0 default, scaled by panelScale)
- Hit test maps ray intersection to UV [0,1] then to pixel coordinates
- `bx = u * 1024` (width) — correct
- `by = v * 1280` (height) — CHANGED from `v * 1024`, all hit regions need updating OR revert

### Thread Model
- Render thread: GL rendering, input polling (InputHandler.handle())
- UI thread: renderUiToBitmap() (Canvas drawing), ExoPlayer access
- pendingUiBitmap: set on UI thread, consumed on render thread (must not reuse same bitmap)

### Key Files
- `InputHandler.kt` — all controller input, laser, menu interaction, model grab
- `UiRenderer.kt` — Canvas-based menu rendering to bitmap
- `GlesModelRenderer.kt` — GL rendering (laser, models, grid, shadows, bloom)
- `FilamentModelActivity.kt` — render loop, lifecycle, UI bitmap upload to compositor
- `xr_renderer.cpp` — native OpenXR session, frame submission, UI quad compositor layer

### Recent Changes That May Have Introduced Issues
- Joystick sliders changed from Y-axis to X-axis (may have broken other stick bindings)
- Panel Y coordinate changed from 1024 to 1280 (broke all hit regions)
- Grip-anywhere panel drag (conflicts with button clicks)
- `hitDistance` preservation fix (may not cover all paths)
