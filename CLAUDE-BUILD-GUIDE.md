# ChloeVR — Claude Code Build Guide
## The Definitive Development Playbook

> **This is the master guide for Claude Code sessions working on ChloeVR.**
> **Read this FIRST in every session. It replaces all prior instructions.**

---

## WHAT WE'RE BUILDING

ChloeVR is a VR180/360 video player for **Samsung Galaxy XR** (Android XR). It must match or exceed the feature sets of **DeoVR** and **HereSphere** — the two dominant players in this space. The Galaxy XR currently has NO good dedicated VR video player, making this a wide-open market opportunity.

**Package:** `com.ashairfoil.prism`
**Language:** Kotlin + C++ (OpenXR native layer)
**Build:** Gradle + CMake, arm64-v8a only
**Target:** Android XR (compileSdk 35, minSdk 34)

---

## ARCHITECTURE

### Dual XR System
- **Jetpack XR SDK** — Rendering (SurfaceEntity shapes in XR space)
- **Native OpenXR** — Controller input (41-float state buffer via JNI at 120Hz)
- **Init order matters:** Native OpenXR FIRST, then Jetpack XR SDK

### Current File Map

| File | Lines | Owner | Role |
|------|-------|-------|------|
| `MainActivity.kt` | ~2391 | ALL | Monolith — needs decomposition |
| `VideoPlayer.kt` | ~79 | CODEX | ExoPlayer wrapper |
| `FilePicker.kt` | ~79 | CODEX | Storage scanner |
| `FileNameParser.kt` | ~72 | CODEX | DeoVR filename convention parser |
| `DeoVrAlphaPackedEffect.kt` | ~131 | CLAUDE | GLSL alpha unpacking shader |
| `ChromaKeyEffect.kt` | ~117 | CLAUDE | GLSL chroma key shader |
| `OpenXRInput.kt` | ~120 | CLAUDE | JNI bridge, 120Hz polling |
| `openxr_input.cpp` | ~549 | CLAUDE | Full OpenXR lifecycle, controller state |
| `jni_bridge.cpp` | ~44 | CLAUDE | JNI glue |
| `openxr_input.h` | ~103 | CLAUDE | Headers, ControllerState struct |

---

## COMPETITIVE FEATURE MATRIX

### What DeoVR Has (that we need)

| Feature | DeoVR Status | ChloeVR Status | Priority |
|---------|-------------|---------------|----------|
| 8K playback | Yes | Depends on ExoPlayer/HW | P0 |
| 180/360 equirectangular | Yes | Yes | DONE |
| Fisheye (190/200/220) | Yes + correction | Hemisphere approx | P0 |
| Alpha packed passthrough | Yes | Yes | DONE |
| 6DOF depth simulation | Yes (X/Y/Z 0-0.4) | No | P1 |
| Zoom/Tilt/Height/Rotation | Yes | Partial | P0 |
| A-B Loop + slow-motion | Yes | Yes | DONE |
| Stereoscopic offset (IPD) | Yes (H + V) | No | P0 |
| Head-tracking per-axis | Yes (toggle X/Y/Z) | No | P1 |
| Auto-focus | Yes (experimental) | No | P2 |
| Favorite folders | Yes | No | P1 |
| Subtitle support | Yes | No | P1 |
| Skip time config | Yes | No | P1 |
| Environment switching | Yes | No | P2 |
| Viewport streaming (6K on mobile) | Yes | No | P3 |
| Remote launch from web | Yes | No | P3 |

### What HereSphere Has (that we need)

| Feature | HereSphere Status | ChloeVR Status | Priority |
|---------|------------------|---------------|----------|
| Lens distortion correction | Yes (dozens of presets) | No | P0 |
| Custom lens distortion curves | Yes | No | P1 |
| Color grading (saturation, contrast, brightness, tone mapping) | Yes | No | P0 |
| Sharpening filter | Yes | No | P0 |
| Keyframed settings per-timestamp | Yes | No | P1 |
| Software IPD adjustment | Yes | No | P0 |
| Emulated spatial audio | Yes | No | P1 |
| Video tagging + search | Yes | No | P2 |
| Resume time + play count | Yes | No | P1 |
| Multi-screen flat playback | Yes | No | P2 |
| Anaglyph view mode | Yes | No | P2 |
| Stereo alignment correction | Yes | No | P1 |
| Stitching error correction | Yes | No | P2 |
| Synchronized peripherals | Yes (WiFi/BT) | No | P3 |
| DeoVR API compatibility | Yes | No | P1 |
| Boolean search with tags | Yes | No | P2 |

### What NEITHER has (our differentiators)

| Feature | Notes | Priority |
|---------|-------|----------|
| Eye tracking autofocus | Galaxy XR supports XR_ANDROID_eye_tracking | P1 |
| Gaze-based UI interaction | Natural for Galaxy XR | P1 |
| Chroma key with color picker | We already have this! DeoVR/HereSphere don't | DONE |
| Native Android XR integration | First-mover on Galaxy XR | P0 |

---

## DEVELOPMENT PRIORITIES (in order)

### Phase 1: Core Quality (make what exists excellent)
1. **Decompose MainActivity.kt** — Extract into: SettingsManager, UIBuilder, PlaybackController, ScreenController, InputHandler, ColorPickerController
2. **Settings persistence** — SharedPreferences for all user settings
3. **Proper fisheye projection** — Custom mesh generation for MKX200/VRCA220 lens profiles
4. **Color grading controls** — Brightness, contrast, saturation, sharpening, tone mapping (GLSL)
5. **IPD adjustment** — Software stereo offset (horizontal + vertical)

### Phase 2: Feature Parity with DeoVR/HereSphere
6. **Lens distortion correction** — Lens profile presets + custom curves
7. **6DOF depth simulation** — Head position offset maps to virtual camera movement
8. **Subtitle support** — SRT/ASS parsing, positioned in VR space
9. **Zoom/Tilt/Height/Rotation** controls with persistence per-video
10. **Resume playback** — Remember position per file
11. **Favorites + recently played**
12. **Skip time configuration**
13. **Spatial audio emulation** — Shift stereo balance based on head orientation

### Phase 3: Galaxy XR Differentiators
14. **Eye tracking** — `XR_ANDROID_eye_tracking` extension for autofocus + gaze UI
15. **Gaze-based menu interaction** — Look at controls to highlight, pinch to activate
16. **DeoVR API compatibility** — Support DeoVR's JSON playlist format for site integration
17. **Keyframed settings** — Save per-timestamp adjustments
18. **Media library** — Scan, tag, search, filter, sort with metadata

### Phase 4: Polish
19. **Loading/buffering indicator**
20. **Video info overlay** (resolution, codec, bitrate, fps)
21. **Multi-screen mode** for flat content
22. **Custom environments** (void, theater, etc.)
23. **Audio track selection**
24. **Performance optimization** — Hardware decoder hints, tile-based streaming

---

## HOW TO DEVELOP (Claude Code methodology)

### Build System
The project uses Gradle with CMake for native. On a dev machine without Android Studio:
```bash
# Install Android command-line tools
# Set ANDROID_HOME
# Run: ./gradlew assembleDebug
```

**If Android SDK is not available:** Focus on writing/editing Kotlin and C++ source files. The build can be verified on the user's Windows workstation with Android Studio.

### File Editing Strategy
1. **Never rewrite files from scratch** — always read first, edit targeted sections
2. **New features go in NEW FILES** — stop growing MainActivity.kt
3. **Each new .kt file** should be a focused component with a clear single responsibility
4. **Test by reading back** — verify edits didn't break surrounding code

### New File Naming Convention
```
com/ashairfoil/prism/
├── MainActivity.kt          (orchestrator — SHRINK this)
├── VideoPlayer.kt           (ExoPlayer wrapper)
├── FilePicker.kt            (storage scanner)
├── FileNameParser.kt        (filename convention parser)
├── DeoVrAlphaPackedEffect.kt (GLSL alpha shader)
├── ChromaKeyEffect.kt       (GLSL chroma key shader)
├── OpenXRInput.kt           (JNI bridge)
├── settings/
│   └── SettingsManager.kt   (SharedPreferences persistence)
├── ui/
│   ├── ControlPanel.kt      (all control panel UI building)
│   ├── FilePickerUI.kt      (file browser UI)
│   ├── ScrubBar.kt          (playback scrub bar)
│   └── ThemeManager.kt      (colors, styling)
├── playback/
│   ├── PlaybackController.kt (play/pause/seek/speed state machine)
│   ├── ScreenController.kt   (SurfaceEntity shape, position, rotation)
│   └── SubtitleRenderer.kt   (SRT/ASS parsing + display)
├── effects/
│   ├── ColorGradingEffect.kt (brightness/contrast/saturation/sharpening)
│   ├── LensDistortionEffect.kt (lens profile correction)
│   └── SpatialAudioEffect.kt (head-tracking stereo shift)
├── input/
│   └── GazeController.kt    (eye tracking + gaze UI)
└── data/
    ├── MediaLibrary.kt       (scan, index, metadata, tags)
    └── VideoMetadata.kt      (per-video settings, resume position)
```

### GLSL Shader Pattern (for new effects)
Follow the existing `ChromaKeyEffect.kt` / `DeoVrAlphaPackedEffect.kt` pattern:
```kotlin
class MyEffect(private val state: MyState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return MyShaderProgram(state)
    }
}

class MyShaderProgram(private val state: MyState) : BaseGlShaderProgram(false, 1) {
    companion object {
        private const val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTexSampler0;
            in vec2 vTexCoords;
            out vec4 fragColor;
            uniform float uBrightness;
            uniform float uContrast;
            void main() {
                vec4 color = texture(uTexSampler0, vTexCoords);
                // ... transform ...
                fragColor = color;
            }
        """
    }
    // override configure() to set uniforms
}
```

---

## KEY TECHNICAL DETAILS

### SurfaceEntity Shapes
```kotlin
// Flat screen (default for 2D/flat content)
SurfaceEntity.create(xrSession, Shape.Quad(8f, 4.5f), ...)

// 180° hemisphere (for VR180 content)
SurfaceEntity.create(xrSession, Shape.Hemisphere(50f), ...)

// 360° sphere (for 360 content)
SurfaceEntity.create(xrSession, Shape.Sphere(50f), ...)
```

### Controller State Buffer (41 floats)
```
[0-1]   Left/Right thumbstick X
[2-3]   Left/Right thumbstick Y
[4-5]   Left/Right trigger (0-1)
[6-7]   Left/Right squeeze (0-1)
[8-14]  Button booleans (A, B, X, Y, menu, LThumb, RThumb)
[15-20] Left/Right hand position (x, y, z)
[21-28] Left/Right hand rotation (quaternion x, y, z, w)
[29-30] Left/Right hand valid
[31-38] Left/Right aim rotation (quaternion x, y, z, w)
[39-40] Left/Right aim valid
```

### Eye Tracking (XR_ANDROID_eye_tracking)
```cpp
// Extension functions to request
PFN_xrCreateEyeTrackerANDROID
PFN_xrDestroyEyeTrackerANDROID
PFN_xrGetEyeTrackerInfoANDROID

// Permissions required
"com.google.android.xr.permission.EYE_TRACKING_FINE"
"com.google.android.xr.permission.EYE_TRACKING_COARSE"
```

---

## WHAT NOT TO DO

1. **Don't use Jetpack Compose** — the XR SDK uses programmatic views, stay consistent
2. **Don't add XML layouts** — all UI is programmatic, keep it that way
3. **Don't break the init order** — Native OpenXR → Jetpack XR SDK
4. **Don't use blocking I/O on main thread** — all file/network ops on coroutine dispatchers
5. **Don't hardcode paths** — use ContentResolver / StorageManager
6. **Don't forget the B button quirk** — fires both OpenXR action AND KEYCODE_BACK

---

## GIT CONVENTIONS

```bash
git config user.email "coldbricks@gmail.com"
git config user.name "Ash Airfoil"
# Commit with descriptive messages
# Push to: https://github.com/coldbricks/ChloeVR.git
```
