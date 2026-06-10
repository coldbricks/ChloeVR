# ChloeVR — CLAUDE.md

<system-reminder>
IMPORTANT: These are architectural facts and hard-won lessons, not suggestions. Every instruction below was earned through debugging on real Samsung Galaxy XR hardware. Violations cause crashes, silent data corruption, or 5-minute sideload cycles for broken APKs. Read everything before writing a single line of code.
</system-reminder>

## Identity

| Field | Value |
|-------|-------|
| **App** | ChloeVR — VR180/360 video player + 3D model viewer for Samsung Galaxy XR |
| **Package** | `com.ashairfoil.prism` |
| **Repo** | `/home/kali/ChloeVR` (git: `https://github.com/coldbricks/ChloeVR.git`, master) |
| **Build root** | `/home/kali/ChloeVR/prism/` |
| **Stack** | Kotlin 2.1.0 + C++17, AGP 8.7.3, NDK 27.2.12479018, compileSdk 35, minSdk 34, arm64-v8a ONLY |
| **XR SDKs** | Jetpack XR SDK (managed space) + Native OpenXR 1.1.49 (unmanaged space, 120Hz input via JNI) |
| **Rendering** | Filament 1.69.5, custom OpenGL ES 3.0 PBR pipeline, Media3/ExoPlayer 1.5.1 |

## Build and Deploy

```powershell
# Windows dev box (since 2026-06; old Kali paths are dead)
cd C:\Users\coldb\ChloeVR\prism; .\gradlew assembleDebug   # builds BOTH flavors
# APKs: app\build\outputs\apk\{galaxyxr,quest}\debug\app-<flavor>-debug.apk
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"   # not on PATH
& $adb install -r app\build\outputs\apk\galaxyxr\debug\app-galaxyxr-debug.apk
& $adb logcat -s ChloeVR-XRRenderer:* ChloeVR-OpenXR:* ChloeVR-ModelActivity:* ChloeVR:* AndroidRuntime:E
```

Wireless adb: `tcpip 5555` from USB, then connect to the headset's wlan IP
(DHCP — check each time; does not survive reboot). Direct viewer launch
(bypasses MainActivity's flaky panel startup):
`am start -n com.ashairfoil.prism/.FilamentModelActivity`

**Build after every change. Fix before continuing. Think through data flow with realistic values BEFORE building — each failed APK costs 5+ minutes of sideloading and testing on hardware.**

---

## Platform Traps (Samsung Galaxy XR)

<system-reminder>
The Samsung Galaxy XR runtime DIVERGES from Khronos spec drafts. API signatures, struct layouts, enum values, and XrStructureType constants differ. NEVER trust spec drafts — validate against actual runtime behavior via `adb logcat`. This has burned us multiple times.
</system-reminder>

- **Init order is load-bearing:** Native OpenXR initializes FIRST, then Jetpack XR SDK. Reversing this breaks session coexistence.
- `xrGetAllTrackablesANDROID` uses 4-param signature (no getInfo), not 5-param Khronos spec.
- `vertexCountOutput` in `XrTrackablePlaneANDROID` must be a valid pointer even when capacity=0.
- Eye/face tracking type constants differ from Khronos spec on Samsung runtime.
- Extensions have hidden dependency chains — if `xrCreateInstance` fails, read the FULL error for dependency info.
- The B button fires BOTH an OpenXR action AND Android `KEYCODE_BACK`. Handle ONLY via `dispatchKeyEvent`.
- `SurfaceEntity.create` param order: session, pose, shape, stereoMode. Wrong order compiles but crashes at runtime.
- `Quaternion.fromEulerAngles` takes DEGREES not radians.
- **NEVER modify Galaxy XR system settings** (`adb shell settings put`, `am start` on system components) without explicit permission — this froze the headset.

---

## Architecture Invariants

Violating any of these requires explicit user approval. They exist because alternatives were tried and failed empirically.

1. **No Jetpack Compose, no XML layouts.** All UI is Canvas-based bitmap rendering (3D panels) or programmatic Views (managed space). XR compatibility constraint.
2. **ControllerState = exactly 41 floats.** Changes require simultaneous updates to `openxr_input.h`, `openxr_input.cpp`, `jni_bridge.cpp`, `OpenXRInput.kt` buffer size, AND `InputHandler.kt` index mapping. Missing any one → silent data corruption.
3. **Roll = twist-swing decomposition** around aim forward axis, sign negated (`grabStartScreenRoll - rollDelta`). Euler-based and world-up approaches both fail empirically. Do not simplify.
4. **Video effects pipeline is conditional.** No effects → direct MediaCodec → SurfaceEntity (bypasses ExoPlayer GL pipeline, avoids "EglImage dataspace changed" spam). Effects enabled → chain through `setVideoEffects()`.
5. **BLE haptic writes gated:** 20Hz max, 50ms min interval, 200ms auto-clear. Send `Vibrate:0;` before disconnect/pause/destroy.
6. **Zero-alloc render loop.** Scratch FloatArray buffers, System.arraycopy, double-buffered AudioReactor spectrum. No per-frame allocations.
7. **XR sensor polling disabled by default.** Hand/eye/face tracking only polled when enabled. Plane detection: every 10 frames. Perf metrics: every 15 frames.
8. **Foveation JNI: `private external fun` with public wrapper**, NOT `internal external fun` (causes `$app_debug` suffix mangling in debug builds).
9. **New features go in new files.** Decomposed modules: `scene/`, `effects/`, `haptics/`, `playback/`, `data/`, `input/`, `ui/`, `settings/`, `billing/`.

---

## File Map

Paths relative to `prism/app/src/main/kotlin/com/ashairfoil/prism/`. **Consult before searching.**

| Domain | Key Files |
|--------|-----------|
| Video playback | `MainActivity.kt` (2391 lines), `VideoPlayer.kt` |
| 3D model viewer | `FilamentModelActivity.kt`, `GlesModelRenderer.kt`, `FilamentRenderer.kt` |
| Scene (decomposed) | `scene/SceneManager.kt`, `scene/InputHandler.kt` (1540 lines), `scene/UiRenderer.kt` (1673 lines), `scene/XrSensorPoller.kt` |
| Native OpenXR | `../../cpp/openxr_input.{h,cpp}`, `../../cpp/xr_renderer.{h,cpp}`, `../../cpp/jni_bridge.cpp`, `../../cpp/renderer_jni_bridge.cpp` |
| Video effects | `effects/{ColorGrading,LensDistortion,StereoAdjustment,DepthSimulation,SpatialAudio}Effect.kt` |
| Shaders | `ChromaKeyEffect.kt`, `DeoVrAlphaPackedEffect.kt` — GLES 300 es, follow these as template for new shaders |
| BLE haptics | `haptics/BleDeviceManager.kt`, `haptics/LovenseProtocol.kt` |
| Playback | `playback/{ScreenController,PlaylistManager,SubtitleRenderer,HeadTrackingConfig,AudioTrackSelector,BufferingIndicator,ScreenshotCapture}.kt` |
| Data/parsing | `data/{MediaLibrary,VideoMetadata,FunscriptParser,HspParser,DeoVrApi,StreamingBrowser,KeyframeSettings}.kt` |
| Input | `OpenXRInput.kt`, `input/GazeController.kt`, `SensorHub.kt` |
| UI | `ui/{EnvironmentManager,ThemeManager,ThumbnailCache,VideoInfoOverlay}.kt` |
| Audio | `AudioPlayer.kt`, `AudioReactor.kt` |
| Config | `settings/SettingsManager.kt`, `billing/ProUpgrade.kt` |
| Build config | `../../build.gradle.kts`, `../../../build.gradle.kts`, `../../cpp/CMakeLists.txt`, `../../AndroidManifest.xml` |
| **DO NOT EDIT** | `build/`, `.gradle/`, `*.iml` |

---

## Activity Modes

| Activity | XR Mode | Rendering | Use Case |
|----------|---------|-----------|----------|
| `MainActivity` | `FULL_SPACE_MANAGED` | SurfaceEntity (Quad/Hemisphere/Sphere) | Video playback |
| `FilamentModelActivity` | `FULL_SPACE_UNMANAGED` | Direct OpenXR frame submission, custom PBR | 3D model viewer |

---

## Coding Standards

- 4-space indent, no tabs, no wildcard imports, functions under 50 lines
- Boolean vars: `is`/`has`/`should`/`can` prefix
- `Result<T>` for fallible ops, never null-as-error
- Structured coroutines only, no `GlobalScope`
- All file I/O on coroutine dispatchers, never main thread
- `internal` visibility for cross-module fields in `scene/` package
- GLES shader pattern: class implements `GlEffect`, companion `FRAGMENT_SHADER`, override `configure` for uniforms — follow `ChromaKeyEffect.kt` exactly

---

## Workflow Protocol

### Before Writing Code
1. **Read first.** Glob to locate, Read to understand. Never guess structure.
2. **Trace data flow** from input to output for the feature being modified.
3. **Identify every file** that needs changes before making the first Edit.
4. **If >5 files change**, state the plan and wait for approval.

### While Writing Code
- Smallest correct change. No drive-by refactoring.
- Enough `old_string` context to be unambiguous in Edit.
- Re-read the changed region after editing to verify.

### After Writing Code
- Run the build. Fix failures immediately.
- `git diff` to review before reporting completion.
- No debug prints, commented-out code, or hardcoded paths left behind.

### Debugging — The Iron Rule
1. Find the exact error or behavior.
2. Read the **FULL** error output — every line, stack traces, logcat, warnings.
3. Hypothesize root cause.
4. Verify by reading the code path.
5. Fix the root cause, not the symptom.
6. **Never say "platform limitation"** without first: (a) reading EVERY available API/extension, (b) checking what code IS vs ISN'T using, (c) looking for hidden dependency chains, (d) actually trying it. This was a real failure mode that cost us days.

---

## Agent Strategy

| Task Type | Agent Pattern | Why |
|-----------|--------------|-----|
| Finding code/understanding flow | `Explore` agent, thoroughness "very thorough" | 48 Kotlin + 8 C++ files in deep hierarchies, quick searches miss things |
| Architecture/multi-file features | `Plan` agent first | Prevents the 5-broken-APK pattern |
| Independent Kotlin + C++ changes | Parallel `general-purpose` agents | Layers are loosely coupled through JNI |
| **Touching 41-float contract** | **Sequential ONLY** | Read all 5 files in order, verify buffer sizes match before AND after |
| Code review/hardening | Ultrathink iteration (3 passes, each amplifying) | 3rd pass should be exhaustive and domain-specific |

---

## Compaction Survival

<system-reminder>
CRITICAL: When this conversation is compacted, the following MUST survive in the 9-section summary with full detail.

**Section 1 (Request/Intent):** This user builds VR apps for Samsung Galaxy XR. They expect working builds, not theoretical fixes. They test on real hardware.
**Section 2 (Key Concepts):** Dual XR system (init order matters), Samsung runtime diverges from Khronos, 41-float ControllerState binary contract, conditional video effects pipeline, foveation JNI mangling.
**Section 3 (Files):** Preserve the file map and build/deploy commands.
**Section 5 (Problem Solving):** Never claim "platform limitation" without exhaustive API enumeration — this was a real failure mode.
**Section 6 (User Messages):** User gives direct feedback. If they say it doesn't work, believe them.
**Section 9 (Next Step):** Check `NOTES.md` and `git log --oneline -10` at session start.
</system-reminder>

---

## Active Work Context

**Current task:** Check `NOTES.md` and `git log --oneline -10` for latest state.
**Latest verified commit:** 19648f8 on master (R2 Follow Room Light, Galaxy XR verified)
**Standing directive (2026-06-10):** implement ALL of `RENDERING_REALISM_PLAN.md`
(R1–R16) and ALL of `DANCE_REALISM_PLAN.md` (D1–D12). R1 (4x MSAA + specular
AA) implemented + installed on Galaxy XR — on-head logcat verification pending
(checklist in NOTES.md). Next: **D1+D3 (dance quick wins)**, then down both
plans. Re-verify plan line cites before editing — R1/R2/R6 landed after the
audit and shifted files.

**FIXED in this session (BUILD SUCCESSFUL):**
- Panel Y coordinate mapping: changed `by = v * 1024f` to `by = v * 1280f`, all hit regions now match UiRenderer's 1280-tall bitmap
- Laser dot on panel: disabled depth test for dot rendering, nudged 2mm toward camera to prevent z-fighting
- Audio player buttons: all Y hit regions now match drawn positions (transport, speed, A/B, repeat, EQ, browse/back)
- Model grabs through menu: changed from snapshot `gripSuppressionActive` to live `blockGripInteractionsUntilRelease` flag — prevents same-frame grab on menu close
- Bottom button hit regions fixed in ALL modes: main menu, beat settings, audio picker, GLB picker, scene picker, save name editor
- Audio trigger guard: wrapped `runOnUiThread` in `applyAudioAction` check so btn=-1 doesn't execute

**Architecture gaps:**
- `XR_KHR_composition_layer_cylinder` not implemented — curved screen uses `SurfaceEntity.Shape.Hemisphere(radius)` approximation where curvature 0→1 maps to radius 20→3m via `shapeForScreenType()` in MainActivity. Works but isn't true cylindrical projection.
- `screenCurvature` not persisted in SettingsManager — resets to 0 on restart, not wired into ScreenController state
- Funscript/HSP parsers exist but not wired to haptic output
- Missing INTERNET + READ_MEDIA_AUDIO permissions in manifest
- 32 lint errors (restricted API, @UnstableApi opt-ins)

---

## Domain Knowledge

- **"Session"** = Jetpack XR `Session` (managed) OR OpenXR `XrSession` (unmanaged). They coexist but are separate.
- **Lovense BLE:** ASCII commands (`Vibrate:N;`, N=0-20) over Nordic UART or Lovense GATT. Write-gated at 20Hz.
- **DeoVR filename convention:** `_180_sbs`, `_360_tb` etc. → parsed by `FileNameParser.kt` for projection/stereo auto-detection.
- **Projection types:** Equirectangular (180/360), Fisheye (MKX200/VRCA220), Flat → different SurfaceEntity shapes.
- **Curved screen:** `screenCurvature` (0=flat Quad, 1=full wrap Hemisphere r=3m). Formula: `radius = 3 + (1 - curvature) * 17`. Changing curvature calls `restartCurrentVideo()` to recreate SurfaceEntity. No XR_KHR_composition_layer_cylinder — hemisphere approximation only.
- **PBR pipeline:** Custom GLES 3.0 in GlesModelRenderer (metallic/roughness, normals, IBL, bloom, PCF shadows). NOT Filament's material system.
- **Intensity:** 0-20 Lovense scale, mapped linearly. Dedupe + rounding prevents BLE write spam.

---

## Environment

- **Dev:** Windows 11 (ROG SCAR 18), repo at `C:\Users\coldb\ChloeVR\`, PowerShell
  (the old Kali setup is retired — ignore any /home/kali paths in history)
- **SDK/adb:** `%LOCALAPPDATA%\Android\Sdk\` (adb not on PATH)
- **Devices:** Samsung Galaxy XR (SM-I610) + Meta Quest 3, both via wireless adb
- **User:** coldbricks (Ash Airfoil)
