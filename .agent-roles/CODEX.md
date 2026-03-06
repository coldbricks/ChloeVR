# Codex 5.3 Standing Orders — ChloeVR

You are Codex 5.3. This file and `AGENTS.md` (repo root) are your onboarding. Read both before doing anything.

## Read First

1. `AGENTS.md` — full project architecture, file map, pipeline, current state
2. `NOTES.md` — recent inter-agent messages (check for conflicts or context)

## What You Own

- **File picker UI**: `showFilePicker()` in `MainActivity.kt` lines 163-326
- **Control panel UI**: `showControlPanel()` lines 329-483, and all UI helper methods (toggle rows, speed row, button rows, adjust rows) lines 485-670
- **Scrub bar UI**: `showScrubBar()` lines 895-1012
- **VideoPlayer.kt** (all of it) — ExoPlayer wrapper at `prism/app/src/main/kotlin/com/ashairfoil/prism/VideoPlayer.kt`
- **FileNameParser.kt** (all of it) — filename convention parser at same package path
- **FilePicker.kt** (all of it) — storage scanner at same package path
- **Unit tests** for the above modules

## What You Don't Touch

- **Native C++ layer**: Everything in `prism/app/src/main/cpp/` — that's Claude's
- **OpenXRInput.kt** — that's Claude's
- **Grab/roll/zoom mechanics** in `onControllerState()` — that's Claude's
- **Build system** (gradle files, CMakeLists.txt) — coordinate with Claude
- **Coordination files** (AGENTS.md, NOTES.md structure, .agent-roles/) — Claude manages these

## Key Context

### All UI Is Programmatic
There are no XML layouts and no Jetpack Compose. Everything is built in Kotlin with `LinearLayout`, `ScrollView`, `Button`, `TextView`, `SeekBar` etc. The `setContentView()` call replaces the entire view tree each time. If you add new UI, follow this pattern.

### The Android Panel
`xrSession?.scene?.mainPanelEntity` is the floating Android UI panel in XR space. `setPanelVisible(visible)` toggles it. When hidden, a black background `View` replaces the content to prevent stale content from flashing on next show.

### VideoPlayer.kt
Thin ExoPlayer wrapper. Has: `start(file, surface)`, `togglePlayPause()`, `seekBy(deltaMs)`, `seekTo(posMs)`, `setSurface(surface)`, `speed` property, `release()`. Properties: `isPlaying`, `currentPositionMs`, `durationMs`.

### FileNameParser.kt
Parses DeoVR filename conventions. Returns `VideoMetadata(file, stereoMode, screenType, hasAlpha, hasSpatialAudio)`. Stereo detection: `_SBS_`, `_LR_`, `_3DH_` = side-by-side; `_TB_`, `_3DV_`, `_OverUnder_` = top-bottom. Projection: `_180_`, `_360_`, `_fisheye_`, `_mkx200_`, `_vrca220_`, `_fisheye190_`.

### FilePicker.kt
Scans `Environment.getExternalStorageDirectory()` and all `StorageManager` volumes. Recurses up to depth 6. Skips hidden dirs and `Android/`. Finds `.mp4`, `.mkv`, `.webm`. Returns sorted by name.

### Screen Types and Shapes
```
FLAT       -> SurfaceEntity.Shape.Quad(8f x 4.5f)
DOME_180   -> SurfaceEntity.Shape.Hemisphere(50f)
SPHERE_360 -> SurfaceEntity.Shape.Sphere(50f)
FISHEYE, MKX200, RF52, VRCA220 -> Hemisphere(50f)  // approximation
```

### StereoMode Mapping
```
MONO          -> SurfaceEntity.StereoMode.MONO
SIDE_BY_SIDE  -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
TOP_BOTTOM    -> SurfaceEntity.StereoMode.TOP_BOTTOM
```

## Likely Tasks For You

- Add settings persistence (SharedPreferences for seek increment, playback speed, last played file)
- Add video metadata display (resolution, duration, codec) via MediaExtractor
- Improve file picker (sorting options, search/filter, recently played)
- Add audio track selection to VideoPlayer
- Add subtitle/caption support
- Add a loading/buffering indicator
- Write unit tests for FileNameParser and FilePicker

## Build

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd prism && ./gradlew assembleDebug
```

## Before Every Session

1. Read `NOTES.md` for recent changes
2. `git log --oneline -10` for recent commits
3. If editing `MainActivity.kt`, note which section (see line ranges in AGENTS.md) in NOTES.md
4. After your session, append to NOTES.md with `## [Codex] YYYY-MM-DD` and a summary
