# ChloeVR Production Audit — 8-Pass Cycle

**Target**: Ship-ready in 2 hours. Every pass reads the entire program, documents issues, fixes them, builds.

**Build command**: `cd /home/kali/ChloeVR/prism && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`

---

## Pass 1: Compilation & Build Integrity
- [ ] Clean build succeeds (assembleDebug)
- [ ] No warnings that indicate bugs (deprecated API misuse, unchecked casts)
- [ ] ProGuard rules cover all JNI bridges and reflection
- [ ] C++ compiles clean (CMake/NDK)
- [ ] All imports resolve (no unused imports that suggest deleted code)

## Pass 2: Crash Surface Analysis
- [ ] Every `!!` force-unwrap justified or replaced with safe call
- [ ] Every `lateinit var` guarded with `::field.isInitialized` before access
- [ ] Every array index access bounds-checked or guarded
- [ ] Every JNI call has null/size validation
- [ ] Every file I/O wrapped in try-catch
- [ ] No division by zero possible in render math

## Pass 3: Thread Safety
- [ ] Every `@Volatile` field used correctly (no compound read-modify-write)
- [ ] Every cross-thread mutable state synchronized
- [ ] No UI operations on background threads
- [ ] No GL operations off GL thread
- [ ] Handler/Looper lifecycle matches Activity lifecycle

## Pass 4: Resource Lifecycle
- [ ] Every BLE GATT connection cleaned up in onDestroy
- [ ] Every Sensor listener unregistered in onDestroy
- [ ] Every coroutine Job cancelled in onDestroy
- [ ] Every Bitmap recycled when no longer needed
- [ ] Every MediaPlayer/ExoPlayer released
- [ ] OpenXR session shutdown called
- [ ] Native resources freed (JNI global refs)

## Pass 5: Security & Privacy
- [ ] No auth tokens in plaintext SharedPreferences
- [ ] No cleartext HTTP for auth-bearing requests
- [ ] No path traversal in file loading
- [ ] No SSRF in URL handling
- [ ] No command injection in any user input
- [ ] network_security_config enforced

## Pass 6: Performance & Memory
- [ ] Zero allocations in render loop (renderUiToBitmap, onControllerState, render thread)
- [ ] No String.format() in hot paths
- [ ] No new Paint() in render methods
- [ ] Scratch buffers reused, not allocated
- [ ] Shadow map dirty flag prevents unnecessary recomputation
- [ ] Texture uploads only when dirty

## Pass 7: UX & Visual Polish
- [ ] All colors reference ThemeManager (no hardcoded hex in UI code)
- [ ] Consistent spacing (8dp grid)
- [ ] All buttons have adequate touch targets (48dp minimum)
- [ ] Hover states work for all interactive elements
- [ ] Error states shown to user (not swallowed silently)
- [ ] Loading indicators shown during async operations

## Pass 8: Play Store Readiness
- [ ] versionCode/versionName correct
- [ ] release signing config functional
- [ ] ProGuard rules tested (assembleRelease builds)
- [ ] Privacy policy page exists
- [ ] App icon is custom (not system default)
- [ ] All permissions justified and documented
- [ ] No debug-only code paths enabled in release

---

## Findings Log

(Each pass appends findings here with fix status)
