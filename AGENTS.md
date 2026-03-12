# ChloeVR Multi-Agent Coordination

## What This Project Is

ChloeVR is a VR180/360 video player for the Samsung Galaxy XR headset. It plays local video files (mp4, mkv, webm) from device storage or USB OTG in immersive stereoscopic VR. Package name: `com.ashairfoil.prism`.

The app is **fully functional**. All core features work: file picking, stereoscopic playback (SBS, top-bottom, mono), projection modes (flat, 180, 360, fisheye variants), grab/reposition with aim-pose-based laser tracking, roll via twist-swing decomposition, A/B repeat looping, scrub bar, thumbstick seek/zoom, and speed control.

## Architecture

Single-activity Android app using two coexisting XR systems:

1. **Jetpack XR SDK** (`androidx.xr.*`) - Creates the XR session, manages `SurfaceEntity` for rendering video onto hemisphere/sphere/quad shapes, handles stereo mode and panel visibility.
2. **Native OpenXR** (C++ via JNI) - Runs a parallel headless OpenXR session (`XR_MND_headless`) for controller input only. Polls at 120Hz for grip pose, aim pose, thumbstick, trigger, squeeze, and all buttons.

Init order matters: native OpenXR initializes first, then Jetpack XR SDK.

### Pipeline: File Selection -> Playback

1. `MainActivity.onCreate` -> request storage permission -> `onPermissionGranted`
2. `initOpenXRInput()` starts the native OpenXR session for controller input
3. `initXrSession()` creates the Jetpack XR `Session`
4. `showFilePicker()` scans storage via `FilePicker.listVideoFiles()`, builds a programmatic UI (no XML layouts) grouped by directory
5. User taps a file -> `playVideo(file)`:
   - `FileNameParser.parse(file)` detects projection/stereo from DeoVR filename conventions
   - Creates `SurfaceEntity` with the appropriate shape and stereo mode
   - Creates `VideoPlayer` (ExoPlayer wrapper), passes the entity's surface
   - Hides the Android panel, enters immersive playback
6. During playback, the 120Hz `onControllerState` callback handles all interaction:
   - Grip+trigger = grab/reposition (aim direction delta drives movement, wrist twist drives roll)
   - Trigger only = zoom (hand lateral movement)
   - Thumbstick = seek (horizontal) / zoom (vertical)
   - A = play/pause, X = A/B repeat cycle, B/Back = scrub bar, Menu = full control panel

### File Map

```
prism/app/src/main/
  kotlin/com/ashairfoil/prism/
    MainActivity.kt     (~2391 lines) - Orchestrator: XR session, file picker UI, control panel,
                         scrub bar, playback, grab mechanics, controller input handling
    VideoPlayer.kt      (~105 lines)  - ExoPlayer wrapper with full effects pipeline chain
    FileNameParser.kt   (~72 lines)   - DeoVR filename convention parser (projection + stereo detection)
    FilePicker.kt       (~45 lines)   - Recursive storage scanner for video files
    OpenXRInput.kt      (~117 lines)  - Kotlin JNI bridge: 41-float state buffer, 120Hz polling
    DeoVrAlphaPackedEffect.kt (~131 lines) - GLSL alpha unpacking shader for DeoVR packed content
    ChromaKeyEffect.kt  (~117 lines)  - GLSL chroma key shader with YCbCr distance keying
    settings/
      SettingsManager.kt (~290 lines) - SharedPreferences singleton: resume positions, screen
                         adjustments, color grading presets, lens/stereo/chroma settings, favorites
    effects/
      ColorGradingEffect.kt (~190 lines) - GLSL brightness/contrast/saturation/sharpening/gamma/
                         hue shift with Reinhard and ACES tone mapping
      LensDistortionEffect.kt (~170 lines) - Brown-Conrady radial distortion correction with
                         camera lens presets (MKX200, VRCA220, RF52, etc.)
      StereoAdjustmentEffect.kt (~150 lines) - Software IPD offset and vertical stereo alignment
                         correction for SBS and top-bottom layouts
  cpp/
    openxr_input.h      (~103 lines)  - C++ header: ControllerState struct (41 floats), OpenXRInput class
    openxr_input.cpp    (~550 lines)  - Full OpenXR lifecycle: instance, session, actions, polling, hand/aim poses
    jni_bridge.cpp      (~32 lines)   - JNI glue: init/shutdown/poll
    CMakeLists.txt      (~19 lines)   - Builds openxr_input shared lib
  res/values/strings.xml              - Just app_name
  AndroidManifest.xml                 - Permissions, XR features, full-space launch mode

prism/
  build.gradle.kts      - AGP 8.7.3, Kotlin 2.1.0
  app/build.gradle.kts  - compileSdk 35, minSdk 34, dependencies (Jetpack XR, OpenXR loader, Media3)
  settings.gradle.kts   - Single :app module
  gradle.properties     - Standard Android config
```

### Video Effects Pipeline

Effects are chained in ExoPlayer's `setVideoEffects()` in this order:

1. **DeoVrAlphaPackedEffect** - Unpacks DeoVR red-mask alpha layout (if alpha-packed content)
2. **ChromaKeyEffect** - Real-time chroma keying in YCbCr space (if enabled)
3. **LensDistortionEffect** - Brown-Conrady radial correction to undistort fisheye lenses (if enabled)
4. **ColorGradingEffect** - Brightness, contrast, saturation, sharpening (unsharp mask), gamma, hue shift, tone mapping (if enabled)
5. **StereoAdjustmentEffect** - Software IPD offset and vertical alignment correction (if enabled, must be last)

Each effect has a companion `*State` class with `@Volatile` fields for safe cross-thread access between the UI thread and the GL rendering thread. The UI updates the state object; the shader reads it every frame.

### Build

- Gradle 9.0.0, AGP 8.7.3, Kotlin 2.1.0
- Target: arm64-v8a only (Galaxy XR)
- Build: `./gradlew assembleDebug` from `prism/` directory
- JAVA_HOME: `/c/Program Files/Android/Android Studio/jbr`
- ADB: `C:/Users/coldb/AppData/Local/Android/Sdk/platform-tools/adb.exe`

## Current State (2026-03-12)

**Working:** All core features. The app is usable for VR180/360 video playback with full controller interaction. Video effects pipeline now supports color grading, lens distortion correction, and IPD/stereo adjustment in addition to existing chroma key and alpha unpacking.

**Known issues:**
- Debug logging in `openxr_input.cpp` (hand pose logged every ~2 seconds when trigger is held, lines 462-467) should be cleaned up
- Fisheye projection variants (MKX200, RF52, VRCA220) use hemisphere as approximation - correct rendering would need custom meshes (LensDistortionEffect provides shader-level correction but proper mesh generation is still needed)
- Mono stereo mode handling may need refinement
- All UI is programmatic (no XML layouts, no Compose) - `MainActivity.kt` is the main complexity bottleneck
- No tests exist
- No README
- Settings persistence (SettingsManager) exists but is not yet wired into MainActivity UI controls
- New effects (color grading, lens distortion, stereo adjustment) have state objects but no UI panels yet

**Architectural debt:**
- `MainActivity.kt` is a monolith handling UI, playback, input, XR session, and grab mechanics
- All state is instance variables on the activity - no ViewModel, no state management
- SettingsManager needs to be initialized in MainActivity.onCreate and wired to save/restore user preferences

## Agent Roles

### Claude (Claude Code)
**Owns: Architecture, native layer, complex algorithms, coordination**

Claude has deep context on this codebase from building it. Best suited for:
- The native C++/OpenXR layer (`openxr_input.cpp/h`, `jni_bridge.cpp`, `CMakeLists.txt`)
- Complex interaction logic (grab mechanics, roll decomposition, controller state machine in `onControllerState`)
- Architectural refactoring of `MainActivity.kt` into smaller components
- The `OpenXRInput.kt` JNI bridge
- Cross-cutting changes that touch multiple layers (Kotlin <-> JNI <-> C++)
- Build system issues

### Codex (Codex 5.3)
**Owns: UI layer, new feature implementation, ExoPlayer integration**

Best suited for:
- File picker UI improvements (`showFilePicker()`, `showControlPanel()`, `showScrubBar()`)
- New UI features (settings panel, video info overlay, thumbnails)
- `VideoPlayer.kt` enhancements (audio track selection, subtitle support, buffering indicators)
- `FileNameParser.kt` improvements (new filename patterns, metadata extraction)
- `FilePicker.kt` enhancements (sorting, filtering, favorites)
- Writing unit tests for pure Kotlin modules

### Gemini (Gemini CLI)
**Owns: Documentation, testing infrastructure, code quality**

Best suited for:
- README and user documentation
- Test infrastructure setup and test writing
- Code review and identifying issues
- Linting, code style enforcement
- Performance analysis and profiling recommendations
- Investigating and documenting the Jetpack XR SDK API surface

## Coordination Protocol

1. **Read AGENTS.md** (this file) at the start of every session
2. **Read NOTES.md** for recent inter-agent messages
3. **Read your role file** in `.agent-roles/` for standing orders
4. **Do your work**
5. **Append to NOTES.md** at the end of your session with your agent tag, date, and what you did/found

### Conflict Avoidance

- `MainActivity.kt` is the hotspot. If you need to edit it, state which section (line ranges) in NOTES.md before starting. Sections:
  - Lines 1-99: State declarations
  - Lines 100-160: Lifecycle, permissions, init
  - Lines 161-326: File picker UI
  - Lines 327-670: Control panel and UI helpers
  - Lines 671-790: Shape/stereo mapping, screen pose, adjustments
  - Lines 791-855: Seek bar loop, A/B repeat logic
  - Lines 856-1012: Panel visibility, scrub bar UI
  - Lines 1013-1065: `playVideo` (entity creation, ExoPlayer start)
  - Lines 1066-1110: Key/motion events
  - Lines 1111-1298: `onControllerState` (grab, zoom, thumbstick, button handling)
  - Lines 1299-1334: Lifecycle, helpers

- The native C++ layer (`cpp/`) is Claude's domain. Others should not modify it without coordinating in NOTES.md.
- `VideoPlayer.kt`, `FileNameParser.kt`, `FilePicker.kt` are safe for any agent to work on independently - they're small and self-contained.
- `settings/SettingsManager.kt` is safe for any agent — it's a standalone singleton with no dependencies on other app code.
- `effects/*.kt` files (ColorGradingEffect, LensDistortionEffect, StereoAdjustmentEffect) are safe for any agent — they're self-contained GLSL shader wrappers. If adding a new effect, also update the pipeline chain in `VideoPlayer.kt`.
