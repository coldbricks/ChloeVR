# ChloeVR

**The VR video player Samsung Galaxy XR deserves.**

ChloeVR is a full-featured VR180/360 video and image player built natively for Android XR. It plays local files and streams from DeoVR-compatible sites with stereoscopic projection, passthrough compositing, color grading, lens correction, spatial audio, eye tracking autofocus, and full 6DOF head-tracking depth simulation.

## Features

### Playback
- **VR180 / VR360 / Flat** — Hemisphere, sphere, and quad SurfaceEntity projection
- **Stereoscopic** — Side-by-side, top-bottom, and mono with software IPD adjustment
- **Fisheye** — MKX200, VRCA220, RF52, and custom lens profiles with Brown-Conrady distortion correction
- **8K support** — Hardware-decoded via ExoPlayer (MediaCodec)
- **All containers** — MP4, MKV, WebM (whatever the device hardware supports)
- **Images** — JPG, PNG, WebP, HEIC, BMP in VR space with chroma key support
- **Subtitles** — SRT and ASS/SSA auto-detection and display

### Video Effects (GLSL Pipeline)
- **Color grading** — Brightness, contrast, saturation, sharpening, gamma, hue shift
- **Tone mapping** — Reinhard, ACES Film presets
- **Lens distortion correction** — 10+ camera lens presets with adjustable k1/k2/FOV
- **DeoVR alpha packed passthrough** — Fisheye alpha channel extraction
- **Chroma key** — Green/blue screen removal with color picker, tolerance, softness
- **Stereo alignment** — Horizontal IPD + vertical offset for camera misalignment correction

### Interaction
- **Full controller support** — Grab/reposition, roll (wrist twist), zoom (trigger + thumbstick)
- **A-B repeat** — Loop and boomerang (ping-pong) modes
- **Speed control** — 0.25x to 4.0x
- **6DOF depth simulation** — Head position parallax with X/Y/Z sensitivity
- **Eye tracking** — Gaze-based autofocus via XR_ANDROID_eye_tracking
- **Spatial audio** — Head-tracking stereo balance shift

### Library
- **Media scanner** — Recursive scan of internal + external + USB OTG storage
- **DeoVR filename conventions** — Auto-detect projection and stereo from filename
- **Resume playback** — Per-file position persistence
- **Favorites, ratings, tags** — Persistent library metadata
- **Search + filter + sort** — By name, date, size, rating, play count, projection type
- **DeoVR API** — Browse and stream from DeoVR-compatible content sites
- **Playlist** — Sequential, shuffle, repeat modes

### Settings
- **Per-video settings** — Color grading, lens, IPD, screen adjustments saved per file
- **Color grading presets** — 6 built-in + unlimited user-created
- **All settings persist** — SharedPreferences-backed SettingsManager

## Architecture

```
com.ashairfoil.prism/
├── MainActivity.kt              — XR session, UI, input processing
├── VideoPlayer.kt               — ExoPlayer wrapper with effects pipeline
├── FilePicker.kt                — Storage scanner
├── FileNameParser.kt            — DeoVR filename convention parser
├── OpenXRInput.kt               — JNI bridge (120Hz controller polling)
├── settings/
│   └── SettingsManager.kt       — SharedPreferences persistence
├── effects/
│   ├── ColorGradingEffect.kt    — GLSL color grading
│   ├── LensDistortionEffect.kt  — Brown-Conrady lens correction
│   ├── StereoAdjustmentEffect.kt — IPD + vertical alignment
│   ├── DeoVrAlphaPackedEffect.kt — Alpha channel extraction
│   ├── ChromaKeyEffect.kt       — Green/blue screen removal
│   ├── DepthSimulationEffect.kt — 6DOF parallax depth
│   └── SpatialAudioEffect.kt   — Head-tracked stereo balance
├── playback/
│   ├── SubtitleRenderer.kt      — SRT/ASS parser + display
│   ├── PlaylistManager.kt       — Navigation, shuffle, repeat
│   └── BufferingIndicator.kt   — Loading spinner
├── input/
│   └── GazeController.kt       — Eye tracking + autofocus
├── data/
│   ├── MediaLibrary.kt          — Indexed collection with metadata
│   ├── DeoVrApi.kt              — DeoVR JSON API client
│   └── VideoMetadata.kt         — Per-video settings + lens presets
├── ui/
│   └── ThemeManager.kt          — Colors, spacing, typography
└── cpp/
    ├── openxr_input.cpp         — OpenXR lifecycle + controller state
    ├── openxr_input.h           — ControllerState struct
    └── jni_bridge.cpp           — JNI glue
```

## Build

```bash
# Requires Android SDK 35, NDK, CMake
cd prism
./gradlew assembleDebug

# Install on connected Galaxy XR
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Samsung Galaxy XR (Android XR)
- Android SDK 35 (compileSdk), minSdk 34
- NDK with C++17 support
- CMake
- arm64-v8a architecture only

## Credits

Built by Ash Airfoil. Powered by Jetpack XR SDK + OpenXR.
