# Gemini CLI Standing Orders — ChloeVR

You are Gemini CLI. This file and `AGENTS.md` (repo root) are your onboarding. Read both before doing anything.

## Read First

1. `AGENTS.md` — full project architecture, file map, pipeline, current state
2. `NOTES.md` — recent inter-agent messages (check for conflicts or context)

## What You Own

- **README.md** — does not exist yet. You should create it.
- **Documentation** — user-facing docs, developer setup guide, architecture docs
- **Test infrastructure** — setting up the test directory structure, test runner config, writing tests
- **Code quality** — linting config, code style, identifying issues and suggesting fixes
- **Performance analysis** — profiling recommendations, memory/CPU concerns

## What You Don't Touch

- **Native C++ layer**: Everything in `prism/app/src/main/cpp/` — that's Claude's
- **OpenXRInput.kt** — that's Claude's
- **Grab/roll/zoom mechanics** in `onControllerState()` — that's Claude's
- **UI implementation code** — that's Codex's domain (though you can review and file issues)
- **Build system changes** — coordinate with Claude

## Key Context

### Project Structure
```
ChloeVR/
  AGENTS.md              - Multi-agent onboarding (you read this)
  NOTES.md               - Inter-agent message bus
  .agent-roles/          - Per-agent standing orders
  .gitignore             - Covers gradle, IDE, build artifacts
  prism/                 - Android project root
    app/
      build.gradle.kts   - App module: compileSdk 35, minSdk 34, dependencies
      src/main/
        AndroidManifest.xml
        kotlin/com/ashairfoil/prism/
          MainActivity.kt     - 1335 lines, the monolith (XR + UI + input + playback)
          VideoPlayer.kt      - 54 lines, ExoPlayer wrapper
          FileNameParser.kt   - 72 lines, DeoVR filename convention parser
          FilePicker.kt       - 45 lines, recursive storage scanner
          OpenXRInput.kt      - 117 lines, JNI bridge for native controller input
        cpp/
          openxr_input.h      - C++ header for OpenXR input
          openxr_input.cpp    - 550 lines, full OpenXR lifecycle
          jni_bridge.cpp      - 32 lines, JNI glue
          CMakeLists.txt      - Native build config
    build.gradle.kts      - Root: AGP 8.7.3, Kotlin 2.1.0
    settings.gradle.kts   - Single :app module
```

### No Tests Exist
There is no `src/test/` or `src/androidTest/` directory. If setting up tests:
- Unit tests go in `prism/app/src/test/java/com/ashairfoil/prism/` (or kotlin/)
- Good candidates for unit tests: `FileNameParser` (pure function, lots of edge cases), `FilePicker` (mock filesystem)
- `VideoPlayer` is hard to unit test (ExoPlayer dependency) but could have integration tests
- `MainActivity` would need instrumented tests and an XR-capable device

### No README Exists
The project has no README.md. A good README should cover:
- What ChloeVR is (VR180/360 player for Samsung Galaxy XR)
- Supported formats and filename conventions (DeoVR standard)
- Controller mapping (all buttons, gestures)
- Build instructions
- Known limitations (fisheye approximation, no persistence, etc.)

### Technology Stack
- **Android**: API 34-35, Kotlin 2.1.0
- **XR**: Jetpack XR SDK (runtime 1.0.0-alpha11, scenecore 1.0.0-alpha12) + native OpenXR 1.1.49
- **Media**: ExoPlayer (Media3 1.5.1)
- **Build**: Gradle 9.0.0, AGP 8.7.3, CMake for native code
- **Target device**: Samsung Galaxy XR headset

### Known Issues To Document
1. Debug logging in native layer (hand pose every ~2s when trigger held)
2. Fisheye/MKX200/RF52/VRCA220 use hemisphere approximation
3. No settings persistence
4. `MainActivity.kt` is a 1335-line monolith
5. All UI is programmatic (no XML layouts, no Compose)
6. B button fires both OpenXR action and Android KEYCODE_BACK

## Likely Tasks For You

- Create README.md with user guide and developer docs
- Set up test infrastructure and write tests for FileNameParser
- Review codebase for potential issues (memory leaks, thread safety, error handling)
- Document the controller mapping comprehensively
- Investigate Jetpack XR SDK API surface for capabilities not yet used
- Add linting/formatting config (ktlint or detekt)

## Build

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd prism && ./gradlew assembleDebug
```

## Before Every Session

1. Read `NOTES.md` for recent changes
2. `git log --oneline -10` for recent commits
3. After your session, append to NOTES.md with `## [Gemini] YYYY-MM-DD` and a summary
