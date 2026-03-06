# Claude Code Standing Orders — ChloeVR

You are Claude Code. You built this codebase. You have the deepest context on it.

## Read First

1. `AGENTS.md` at the repo root — full architecture and coordination protocol
2. `NOTES.md` at the repo root — recent messages from other agents

## What You Own

- **Native C++/OpenXR layer**: `openxr_input.cpp`, `openxr_input.h`, `jni_bridge.cpp`, `CMakeLists.txt` in `prism/app/src/main/cpp/`
- **JNI bridge**: `OpenXRInput.kt` — the Kotlin side of the native input system
- **Grab/reposition mechanics**: `onControllerState()` in `MainActivity.kt` lines 1137-1298
- **Roll decomposition**: `relativeRollDeg()` at line 738
- **Quaternion math helpers**: `quatForward()`, `quatUp()` at lines 715-734
- **Architecture decisions**: You decide how to decompose `MainActivity.kt` if/when refactoring happens
- **Build system**: Both `build.gradle.kts` files, `CMakeLists.txt`, `settings.gradle.kts`
- **Coordination files**: `AGENTS.md`, `NOTES.md`, `.agent-roles/`

## What You Don't Touch (Without Coordinating)

- UI layout code in the file picker or control panel — Codex's domain
- `VideoPlayer.kt` enhancements — Codex's domain
- `FileNameParser.kt` pattern additions — Codex's domain
- Documentation/README — Gemini's domain

## Critical Knowledge

- **Init order**: Native OpenXR FIRST, then Jetpack XR SDK. This is required for coexistence.
- **ControllerState is 41 floats**: 31 original + 8 aim rotation + 2 aim valid. Any change to the struct requires updating `OpenXRInput.kt` buffer size and index mapping simultaneously.
- **Roll math**: Uses twist-swing decomposition around the aim forward axis. The sign is NEGATED (`grabStartScreenRoll - rollDelta`). Euler-based and world-up approaches both fail. Do not "simplify" this.
- **B button quirk**: B fires both an OpenXR action AND Android KEYCODE_BACK. Only handle via KEYCODE_BACK in `dispatchKeyEvent`, never in the OpenXR handler.
- **Quaternion.fromEulerAngles** takes DEGREES, not radians.
- **SurfaceEntity.create** param order: session, pose, shape, stereoMode.

## How To Build

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd prism && ./gradlew assembleDebug
```

ADB: `C:/Users/coldb/AppData/Local/Android/Sdk/platform-tools/adb.exe`

## Memory

Your persistent memory is at `C:\Users\coldb\.claude\projects\C--Users-coldb-Desktop-ChloeVR\memory\MEMORY.md`. Consult it — it has verified SDK API details and hard-won debugging lessons.

## Before Every Session

1. Read `NOTES.md` for anything other agents have done
2. Check `git log --oneline -10` for recent commits
3. If you're going to edit `MainActivity.kt`, note which line-range section in NOTES.md first
