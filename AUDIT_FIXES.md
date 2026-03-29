# ChloeVR Audit Fix Specification — 63 Findings

**Generated:** 2026-03-28
**Source files:** 67 Kotlin + C++ + XML files (~30k lines)
**Target:** Zero-retry application — every fix must be correct on first attempt

---

## Execution Rules

1. Read `CLAUDE.md` first — it has build commands, architecture invariants, and platform traps
2. Apply fixes in PHASE ORDER (1→2→3→4). Build + verify after each phase.
3. For each file: **read the entire file first**, understand context, then apply ALL fixes for that file before moving to the next
4. Build command: `cd /home/kali/ChloeVR/prism && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`
5. After ALL phases complete, run `git diff` to review, then `./gradlew lint` for final check
6. **Do not refactor beyond what each fix specifies.** Smallest correct change only.
7. When a fix says "find X and change to Y," use the Edit tool with enough surrounding context to be unambiguous
8. For C++ files, the build compiles them as part of assembleDebug (CMake/NDK)

### Parallel Agent Strategy

These file groups are INDEPENDENT and can be fixed in parallel within each phase:
- **Group A (Kotlin scene/):** InputHandler.kt, UiRenderer.kt, SceneManager.kt, XrSensorPoller.kt
- **Group B (Kotlin core):** MainActivity.kt, FilamentModelActivity.kt, GlesModelRenderer.kt, FilamentRenderer.kt
- **Group C (Kotlin subsystems):** haptics/*, effects/*, data/*, playback/*, ui/*, settings/*, billing/*
- **Group D (Native C++):** openxr_input.cpp, xr_renderer.cpp, jni_bridge.cpp, renderer_jni_bridge.cpp
- **Group E (Config):** AndroidManifest.xml, build.gradle.kts files

---

## PHASE 1: CRITICAL — Safety, Crashes, Broken Functionality (7 fixes)

### C-1 | haptics/BleDeviceManager.kt | BLE disconnect must send Vibrate:0 BEFORE cleanup

**Problem:** `disconnect()` nulls `gatt` and `writeCharacteristic` before sending stop command. Motor runs indefinitely after disconnect.

**Fix:** In the `disconnect()` method, BEFORE the line `val g = gatt`, insert the stop command. The method currently starts with:
```kotlin
fun disconnect() {
    stopScan()
    val g = gatt
    gatt = null
```

Change to:
```kotlin
fun disconnect() {
    stopScan()
    // Safety: stop motors before tearing down connection
    try {
        val wc = writeCharacteristic
        val g0 = gatt
        if (wc != null && g0 != null) {
            wc.value = "Vibrate:0;".toByteArray(Charsets.US_ASCII)
            wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g0.writeCharacteristic(wc)
        }
    } catch (_: Exception) {}
    val g = gatt
    gatt = null
```

### C-2 | billing/ProUpgrade.kt | Harden purchase flag

**Problem:** `DEBUG_PRO_UNLOCKED` is a mutable `var` and `markPurchased` stores a plaintext boolean.

**Fix two things:**
1. Change `var DEBUG_PRO_UNLOCKED = BuildConfig.DEBUG` to `val DEBUG_PRO_UNLOCKED = BuildConfig.DEBUG`
2. Add a validation comment in `verifyPurchaseState()`:
```kotlin
// TODO: Replace with BillingClient.queryPurchasesAsync() for Play Store release
```

### C-3 | MainActivity.kt | Replace surfaceEntity!! force-unwraps with safe calls

**Problem:** Two `surfaceEntity!!` force-unwraps crash if surfaceEntity is null during rapid file switching or XR session failure.

**Fix:** Search for every `surfaceEntity!!` in the file. There should be ~2 occurrences. Replace each pattern like:
```kotlin
file, surfaceEntity!!.getSurface(),
```
with:
```kotlin
file, (surfaceEntity ?: return).getSurface(),
```

If surfaceEntity!! appears in other contexts (not as a return-guard), use `surfaceEntity?.let { ... } ?: return` pattern.

### C-4 | FilamentModelActivity.kt | Guard onDestroy against uninitialized sceneManager

**Problem:** If native renderer init fails, `sceneManager` (lateinit) is never initialized. `onDestroy` accesses `models` which delegates to sceneManager, crashing.

**Fix:** In `onDestroy()`, find where `models` or `sceneManager` is first accessed. Wrap ALL sceneManager/models access in:
```kotlin
if (::sceneManager.isInitialized) {
    // ... existing sceneManager/models usage ...
}
```

Also find `sensorPoller` access in onDestroy and guard similarly:
```kotlin
if (::sensorPoller.isInitialized) {
    sensorPoller.destroy()
}
```

### C-5 | scene/InputHandler.kt + scene/UiRenderer.kt | GLB picker maxVisible mismatch

**Problem:** InputHandler uses `maxVisible = 13` for GLB picker hit testing; UiRenderer renders only 10 rows. Clicking bottom rows hits wrong/invisible items.

**Fix in InputHandler.kt:** Find the GLB picker hit-test section. It will have code like:
```kotlin
val maxVisible = 13
val frac = (by - 140f) / (1128f - 140f)
val idx = activity.glbPickerScrollOffset + (frac * maxVisible).toInt()
```
Replace with row-based math:
```kotlin
val maxVisible = 10
val rowH = 76f
val vi = ((by - 140f) / rowH).toInt().coerceIn(0, maxVisible - 1)
val idx = activity.glbPickerScrollOffset + vi
```
Also find the scroll bounds that use 13 and change to 10:
```kotlin
// old: val maxScroll = (files.size - 13).coerceAtLeast(0)
val maxScroll = (files.size - 10).coerceAtLeast(0)
```

**Fix in UiRenderer.kt:** Verify `maxVisible = 10` is already used. If not, change to 10.

### C-6 | effects/ColorGradingEffect.kt | ACES tone mapping missing coefficient

**Problem:** The ACES fitting curve denominator is missing the `c=2.43` coefficient. Standard formula: `(x*(2.51x+0.03))/(x*(2.43x+0.59)+0.14)`. The shader has `x*(0.59x+0.14)` in the denominator.

**Fix:** Find the `acesToneMap` function in the FRAGMENT_SHADER string. It will look like:
```
float a = 2.51; float b = 0.03; float d = 0.59; float e = 0.14;
return clamp((c * (a * c + b)) / (c * (d * c + e) + vec3(0.0001)), 0.0, 1.0);
```
Change to:
```
float a = 2.51; float b = 0.03; float ac = 2.43; float d = 0.59; float e = 0.14;
return clamp((c * (a * c + b)) / (c * (ac * c + d) + vec3(e)), 0.0, 1.0);
```
Note: `c` is the vec3 input parameter, `ac` is the ACES coefficient (named to avoid collision).

### C-7 | GlesModelRenderer.kt | GLB parser bounds checking

**Problem:** `readU32` can return negative values (int overflow). `binChunkStart` computation can overflow. Buffer view offsets from untrusted JSON not validated.

**Fix:** In the `loadGlb` method, after `val jsonLength = readU32(glbBytes, 12)`, add:
```kotlin
if (jsonLength < 0 || jsonLength > glbBytes.size - 20) {
    Log.e(TAG, "Invalid GLB: jsonLength=$jsonLength exceeds bounds")
    return -1
}
```

After `val binChunkStart = 12 + 8 + jsonLength + 8`, add:
```kotlin
if (binChunkStart < 0 || binChunkStart >= glbBytes.size) {
    Log.e(TAG, "Invalid GLB: binChunkStart=$binChunkStart out of range")
    return -1
}
```

In the `bv()` helper function, add bounds validation:
```kotlin
fun bv(idx: Int): Pair<Int, Int> {
    val o = bufferViews.getJSONObject(idx)
    val offset = binChunkStart + o.optInt("byteOffset", 0)
    val length = o.getInt("byteLength")
    if (offset < 0 || length < 0 || offset + length > glbBytes.size) {
        throw IndexOutOfBoundsException("Buffer view $idx out of bounds: offset=$offset length=$length total=${glbBytes.size}")
    }
    return Pair(offset, length)
}
```

---

## PHASE 2: HIGH — Security, Correctness, Major Performance (13 fixes)

### H-1 | data/DeoVrApi.kt | URL scheme validation

**Problem:** `fetchScenes()` accepts any URL scheme. Auth tokens sent over HTTP.

**Fix:** In `fetchScenes()`, after `val url = URL(apiUrl)`, add:
```kotlin
if (url.protocol != "https" && url.protocol != "http") {
    Log.e(TAG, "Rejected non-HTTP URL scheme: ${url.protocol}")
    return emptyList()
}
```

Before the auth token attachment (`if (authToken != null)`), add:
```kotlin
if (authToken != null && url.protocol != "https") {
    Log.w(TAG, "Refusing to send auth token over non-HTTPS connection")
} else if (authToken != null) {
    conn.setRequestProperty("Authorization", "Bearer $authToken")
}
```
(Remove the old auth token attachment block)

### H-2 | Multiple files | Replace silent exception swallowing with logging

**Fix in MainActivity.kt:** Find every `} catch (e: Exception) {}` and `} catch (_: Exception) {}`. Replace the empty blocks with `Log.w("ChloeVR", "description", e)` or `Log.w("ChloeVR", "description")`. Key locations:
- XR session init (~line 412): `Log.e("ChloeVR", "XR session initialization failed", e)`
- PlacedModel.groundToFloor (~line 196): `Log.w("ChloeVR", "groundToFloor failed", e)`
- setPanelVisible: `Log.w("ChloeVR", "setPanelVisible failed", e)`

**Fix in FilamentModelActivity.kt:** Same pattern. Key locations:
- GLB cache load (~line 290): `Log.w("ChloeVR", "GLB cache read failed", e)`
- GLB cache write (~line 297): `Log.w("ChloeVR", "GLB cache write failed", e)`

### H-3 | openxr_input.cpp + xr_renderer.cpp | Check xrBeginSession return

**Fix in openxr_input.cpp:** Find `xrBeginSession(session_, &beginInfo);` followed by `sessionReady_ = true;`. Change to:
```cpp
XrResult beginResult = xrBeginSession(session_, &beginInfo);
if (XR_SUCCEEDED(beginResult)) {
    sessionReady_ = true;
} else {
    LOGE("xrBeginSession failed: %d", beginResult);
}
```

**Fix in xr_renderer.cpp:** Same pattern — find `xrBeginSession` call, check result before setting `sessionReady_ = true`.

### H-4 | haptics/BleDeviceManager.kt | Check GATT status in onConnectionStateChange

**Fix:** In `onConnectionStateChange`, before the `when (newState)` block, add:
```kotlin
if (status != BluetoothGatt.GATT_SUCCESS) {
    Log.e(TAG, "GATT error: status=$status newState=$newState")
    gatt.close()
    this@BleDeviceManager.gatt = null
    writeCharacteristic = null
    connectionState = ConnectionState.Disconnected
    handler.post { onConnectionStateChanged?.invoke(connectionState) }
    return
}
```

### H-5 | effects/SharpeningEffect.kt + effects/AnaglyphEffect.kt | Fix vertex shader

**Problem:** Both use non-standard vertex shader that ignores ExoPlayer texture transforms. Missing `setBufferAttribute` call.

**Fix for BOTH files:** Replace the VERTEX_SHADER constant with the standard pattern used by other effects (copy from ColorGradingEffect.kt or LensDistortionEffect.kt — it uses `uTransformationMatrix`, `uTexTransformationMatrix`, `aFramePosition`).

In `drawFrame()`, before `program.bindAttributesAndUniforms()`, add the standard attribute/uniform setup:
```kotlin
program.setFloatsUniform("uTransformationMatrix", transformMatrix)
program.setFloatsUniform("uTexTransformationMatrix", texTransformMatrix)
program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinates(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
```
NOTE: Check how other effects (like ColorGradingEffect) set these in `drawFrame` and follow that exact pattern.

### H-6 | scene/UiRenderer.kt | Double-buffer for pendingUiBitmap

**Problem:** Single `renderBitmap` can be written to by UI thread while GL thread uploads it.

**Fix:** Add a second bitmap buffer. Change the current pattern from:
```kotlin
private var renderBitmap: Bitmap? = null
private var renderCanvas: Canvas? = null
@Volatile var pendingUiBitmap: Bitmap? = null
```
to:
```kotlin
private var renderBitmapA: Bitmap? = null
private var renderBitmapB: Bitmap? = null
private var renderCanvasA: Canvas? = null
private var renderCanvasB: Canvas? = null
private var writeToA = true
@Volatile var pendingUiBitmap: Bitmap? = null
```

At the start of `renderUiToBitmap()`, where the bitmap is created/selected, use:
```kotlin
val bitmap: Bitmap
val canvas: Canvas
if (writeToA) {
    if (renderBitmapA == null || renderBitmapA!!.width != uiW || renderBitmapA!!.height != uiH) {
        renderBitmapA?.recycle()
        renderBitmapA = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
        renderCanvasA = Canvas(renderBitmapA!!)
    }
    bitmap = renderBitmapA!!
    canvas = renderCanvasA!!
} else {
    if (renderBitmapB == null || renderBitmapB!!.width != uiW || renderBitmapB!!.height != uiH) {
        renderBitmapB?.recycle()
        renderBitmapB = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
        renderCanvasB = Canvas(renderBitmapB!!)
    }
    bitmap = renderBitmapB!!
    canvas = renderCanvasB!!
}
```

At the end of the method where `pendingUiBitmap = bitmap` is set, add the swap:
```kotlin
pendingUiBitmap = bitmap
writeToA = !writeToA
```

### H-7 | haptics/BleDeviceManager.kt | Add auto-connect confirmation guard

**Problem:** Any device advertising "LVS-" is auto-connected without user confirmation.

**Fix:** Add a known-device allowlist. Add a field:
```kotlin
private val knownDeviceAddresses = mutableSetOf<String>()
```

In the scan callback, where `autoConnect` fires, change to:
```kotlin
if (isLovense && autoConnect && address in knownDeviceAddresses) {
    connect(result.device)
}
```

Add a method to approve a device:
```kotlin
fun approveDevice(address: String) {
    knownDeviceAddresses.add(address)
}
```

Update the existing `connect()` method to auto-approve:
```kotlin
fun connect(device: BluetoothDevice) {
    knownDeviceAddresses.add(device.address)
    // ... existing connect logic
```

### H-8 | scene/UiRenderer.kt | Pre-allocate Paint objects

**Problem:** 80-120 Paint objects allocated per render frame.

**Fix:** This is the largest single change. Read the entire `renderUiToBitmap()` method and identify every `Paint()` and `Paint(Paint.ANTI_ALIAS_FLAG)` construction. Extract them to class-level fields. Key fields to add at class level:

```kotlin
// Pre-allocated paints for zero-alloc rendering
private val paintBg = Paint().apply { style = Paint.Style.FILL }
private val paintBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
private val paintTextBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
private val paintFill = Paint().apply { style = Paint.Style.FILL }
private val paintGlow = Paint().apply { style = Paint.Style.FILL; maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL) }
private val paintBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val paintBtnBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
private val paintBtnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
private val paintSlider = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val paintCursor = Paint(Paint.ANTI_ALIAS_FLAG)
private val paintDim = Paint().apply { style = Paint.Style.FILL }
private val paintLine = Paint().apply { style = Paint.Style.STROKE }
```

Then throughout `renderUiToBitmap()`, replace `val somePaint = Paint().apply { color = 0xFF...; textSize = 24f }` with:
```kotlin
paintText.color = 0xFF...toInt()
paintText.textSize = 24f
canvas.drawText(..., paintText)
```

**IMPORTANT:** You MUST read the file to see the current set of pre-allocated paints (some already exist as `pBg`, `pBorder`, `pText`, `pFill`, `pGlow`, `pDim`) and extend that pattern. Don't duplicate existing fields. The key is: NO `Paint()` constructor calls inside `renderUiToBitmap()`.

Also fix the `drawLaserCursorOverlay` lambda — extract it to a proper method using the pre-allocated `paintCursor`.

### H-9 | scene/InputHandler.kt | Pre-allocate grab path FloatArrays

**Problem:** `floatArrayOf(rightHandPosX, ...)` and `floatArrayOf(rightAimRotX, ...)` allocated per-frame during model grabs at 120Hz.

**Fix:** Add class-level scratch arrays:
```kotlin
private val scratchGrabPos = FloatArray(3)
private val scratchGrabRot = FloatArray(4)
```

Replace the `floatArrayOf(...)` calls with writes into scratch arrays:
```kotlin
// old: val grabHandPos = floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
scratchGrabPos[0] = rightHandPosX; scratchGrabPos[1] = rightHandPosY; scratchGrabPos[2] = rightHandPosZ
val grabHandPos = scratchGrabPos
```

For `grabStartAimRot = grabAimRot.copyOf()`, replace with:
```kotlin
System.arraycopy(grabAimRot, 0, grabStartAimRot, 0, 4)
```

### H-10 | scene/InputHandler.kt | Pre-allocate beat corner arrays

**Problem:** `mutableListOf(floatArrayOf(...), ...)` creates list + 4-8 arrays per frame while dragging.

**Fix:** Add class-level:
```kotlin
private val cornerBuf = Array(8) { FloatArray(2) }
private var cornerCount = 0
```

Replace the mutable list construction with writes into the buffer:
```kotlin
cornerCount = 0
cornerBuf[cornerCount][0] = reactor.boxLeft; cornerBuf[cornerCount][1] = dispBoxTop; cornerCount++
cornerBuf[cornerCount][0] = reactor.boxRight; cornerBuf[cornerCount][1] = dispBoxTop; cornerCount++
cornerBuf[cornerCount][0] = reactor.boxLeft; cornerBuf[cornerCount][1] = dispBoxBot; cornerCount++
cornerBuf[cornerCount][0] = reactor.boxRight; cornerBuf[cornerCount][1] = dispBoxBot; cornerCount++
if (hasSplit) { /* same pattern for box2 corners */ }
```

Then the distance calculation loop iterates `0 until cornerCount` over `cornerBuf`.

### H-11 | GlesModelRenderer.kt | Shadow map dirty flag

**Problem:** `computeLightSpaceMatrix()` allocates 3 FloatArrays per frame and recomputes even when nothing moved.

**Fix:** Add fields:
```kotlin
private var shadowDirty = true
private var lastShadowLightDirX = 0f
private var lastShadowLightDirY = 0f
private var lastShadowLightDirZ = 0f
private var lastShadowSpread = 0f
```

Add a method to mark dirty (call this when models move, light changes, or shadowSpread changes):
```kotlin
fun markShadowDirty() { shadowDirty = true }
```

In `renderShadowMap()`, at the top:
```kotlin
if (!shadowDirty && lastShadowLightDirX == lightDirX && lastShadowLightDirY == lightDirY && lastShadowLightDirZ == lightDirZ && lastShadowSpread == shadowSpread) {
    return // reuse existing shadow map
}
shadowDirty = false
lastShadowLightDirX = lightDirX; lastShadowLightDirY = lightDirY; lastShadowLightDirZ = lightDirZ
lastShadowSpread = shadowSpread
```

Also pre-allocate scratch arrays for `computeLightSpaceMatrix`:
```kotlin
private val scratchLightView = FloatArray(16)
private val scratchLightProj = FloatArray(16)
private val scratchLightMatrix = FloatArray(16)
```
Change `lookAt()` and `ortho()` to write into these scratch buffers instead of allocating new arrays. Change `multiplyMat4()` to use the 3-arg overload that writes into a provided output array.

### H-12 | GlesModelRenderer.kt | Fix fill light BRDF Fresnel and Geometry

**Problem:** Fill light reuses F and G computed for main light half-vector.

**Fix:** In the PBR fragment shader (FRAGMENT_SHADER string), find the fill light section. It will have something like:
```glsl
vec3 fSpec = fD * F * G / (4.0 * NdotV * fNdotL + 0.0001);
```

Before that line, add proper fill light Fresnel and geometry:
```glsl
vec3 fF = F0 + (1.0 - F0) * pow(1.0 - max(dot(fillH, V), 0.0), 5.0);
float fG_L = fNdotL / (fNdotL * (1.0 - k) + k);
float fG_V = NdotV / (NdotV * (1.0 - k) + k);
float fG = fG_L * fG_V;
```

Then change:
```glsl
vec3 fSpec = fD * fF * fG / (4.0 * NdotV * fNdotL + 0.0001);
```

### H-13 | FileNameParser.kt | Add missing DeoVR conventions

**Fix:** Add to the companion regex patterns:
```kotlin
// After the existing TB_PATTERN:
private val OU_PATTERN = Regex("""[_.\-](OU)[_.\-]""", RegexOption.IGNORE_CASE)
```

In the stereo detection logic, add:
```kotlin
OU_PATTERN.containsMatchIn(name) -> StereoMode.TOP_BOTTOM
```

Add RF52 pattern:
```kotlin
private val RF52_PATTERN = Regex("""[_.\-](rf52|rf5\.2)[_.\-]""", RegexOption.IGNORE_CASE)
```

In the projection detection, add before the fisheye190 match:
```kotlin
RF52_PATTERN.containsMatchIn(name) -> ScreenType.RF52
```

---

## PHASE 3: MEDIUM — Reliability, Minor Bugs, Security (25 fixes)

### M-1 | MainActivity.kt | MediaMetadataRetriever leak
Find `showVideoInfoOverlay` or wherever a `MediaMetadataRetriever` is created and `release()` is inside `try`. Move `release()` to a `finally` block.

### M-2 | settings/SettingsManager.kt | Delimiter serialization
In `parseResumeMap()` and `serializeResumeMap()`, the `|` and `;;` delimiters can collide with file paths. Replace the pipe character with a character that is illegal in file paths on Android. Use `\u0000` (null) as the key-value separator and `\n` as the entry separator. Or switch to JSON. The simplest fix: use `\t` (tab) instead of `|` and `\n` instead of `;;` — neither can appear in Android file paths.

### M-3 | data/StreamingBrowser.kt | Log warning on EncryptedSharedPreferences fallback
Change `Log.w(TAG, "EncryptedSharedPreferences failed, falling back to standard prefs", e)` to `Log.e(TAG, "EncryptedSharedPreferences failed, auth tokens stored unencrypted", e)`.

### M-4 | FilamentModelActivity.kt | Guard reactor!!
Find `reactor!!.getBeatColor()` or similar. The local val capture `val reactor = audioReactor` should already make it safe if the `!!` is on the local. If the `!!` is on a field access, replace with the local val pattern:
```kotlin
val r = audioReactor ?: continue  // or return
r.getBeatColor()
```

### M-5 | xr_renderer.cpp | Static metricsEnabled → member variable
Find `static bool metricsEnabled = false;` in `pollPerfMetrics()`. Move it to a member variable of the `XrRenderer` class:
- In `xr_renderer.h`, add `bool metricsEnabled_ = false;` to the class
- In `xr_renderer.cpp`, change `static bool metricsEnabled` to `metricsEnabled_`

### M-6 | scene/SceneManager.kt | Path validation on scene load
In `loadScene()`, where `val file = File(obj.getString("path"))`, add:
```kotlin
val file = File(obj.getString("path"))
val canonical = file.canonicalPath
if (!canonical.startsWith("/storage/") && !canonical.startsWith(activity.filesDir.path)) {
    Log.w(TAG, "Rejected scene path outside storage: $canonical")
    continue
}
```

### M-7 | scene/SceneManager.kt | Thread-safe model list
Change `val models = mutableListOf<PlacedModel>()` to:
```kotlin
val models: MutableList<PlacedModel> = java.util.concurrent.CopyOnWriteArrayList()
```

### M-8 | AndroidManifest.xml | Remove unnecessary ACCESS_FINE_LOCATION
Remove `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />`.
Add `android:usesPermissionFlags="neverForLocation"` to the `BLUETOOTH_SCAN` permission.

### M-9 | app/build.gradle.kts | Downgrade security-crypto
Change `androidx.security:security-crypto:1.1.0-alpha06` to `androidx.security:security-crypto:1.1.0-alpha06` — actually keep this version since EncryptedSharedPreferences 1.0.0 may not support the APIs used. Instead, add a comment: `// alpha06 required for MasterKey.Builder; pin to this version`

### M-10 | data/MediaLibrary.kt | Add HashMap index for O(1) lookups
Add a field: `private val entryIndex = HashMap<String, MediaEntry>()`
After `entries` is populated (in `buildFromFiles()`), populate the index:
```kotlin
entryIndex.clear()
entries.forEach { entryIndex[it.fileId] = it }
```
Replace all `entries.find { it.fileId == id }` with `entryIndex[id]`.

### M-11 | data/FunscriptParser.kt | Guard against oversized files
Before `val json = file.readText()`, add:
```kotlin
if (file.length() > 50 * 1024 * 1024) {
    Log.w(TAG, "Funscript too large: ${file.length()} bytes, skipping")
    return null
}
```

### M-12 | SensorHub.kt | Register sensors on background thread
Find where sensors are registered with `SENSOR_DELAY_GAME`. Add a HandlerThread:
```kotlin
private val sensorThread = HandlerThread("SensorHub").apply { start() }
private val sensorHandler = Handler(sensorThread.looper)
```
Pass `sensorHandler` to all `registerListener()` calls:
```kotlin
sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
```
In the destroy/cleanup method, add: `sensorThread.quitSafely()`

### M-13 | billing/ProUpgrade.kt | Already handled in C-2

### M-14 | settings/SettingsManager.kt | Safer init pattern
Change `ensureInit()` from `check(::prefs.isInitialized)` to a lazy init pattern:
```kotlin
private fun ensureInit() {
    if (!::prefs.isInitialized) {
        Log.w(TAG, "SettingsManager used before init(), attempting late init")
        // Cannot init without context — throw descriptive error
        throw IllegalStateException("SettingsManager.init(context) must be called in Application.onCreate() or Activity.onCreate() before use")
    }
}
```
(This makes the error message more descriptive without changing behavior.)

### M-15 | MainActivity.kt | readBytes() OOM guard
Before `original.file.readBytes()` in model duplication, add:
```kotlin
if (original.file.length() > 100 * 1024 * 1024) {
    showMessage("Model too large to duplicate (${original.file.length() / 1024 / 1024}MB)")
    return@launch
}
```

### M-16 | scene/UiRenderer.kt | Reduce String.format() in render path
Find all `"%.0f".format(...)` and `"%.1f".format(...)` calls in `renderUiToBitmap()`. Replace with direct Int conversion where possible:
```kotlin
// old: "FILL: %.0f%%".format(fillPct * 100)
// new: "FILL: ${(fillPct * 100).toInt()}%"
```
For float formatting that needs decimals, pre-allocate a StringBuilder:
```kotlin
private val fmtBuf = StringBuilder(16)
private fun fmtFloat1(v: Float): String { fmtBuf.setLength(0); fmtBuf.append((v * 10).toInt() / 10f); return fmtBuf.toString() }
```

### M-17 | GlesModelRenderer.kt | Remove per-frame FBO completeness check
Find `glCheckFramebufferStatus` in `renderEye()`. Gate it behind a frame counter:
```kotlin
if (frameCount < 3) {
    val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
        Log.e(TAG, "FBO incomplete: 0x${Integer.toHexString(fboStatus)}")
    }
}
```

### M-18 | GlesModelRenderer.kt | Move sampler uniform bindings before model loop
In `renderEye()`, find the per-model loop. Before the loop, set sampler bindings once:
```kotlin
GLES30.glUniform1i(uBaseColor, 0)
GLES30.glUniform1i(uNormalMap, 1)
GLES30.glUniform1i(uMetRoughMap, 2)
GLES30.glUniform1i(uEmissiveMap, 3)
```
Remove these same `glUniform1i` calls from inside the loop body.

### M-19 | Build config | Create proguard-rules.pro
Create file `prism/app/proguard-rules.pro`:
```
# JNI methods
-keepclasseswithmembers class com.ashairfoil.prism.OpenXRInput { native <methods>; }
-keepclasseswithmembers class com.ashairfoil.prism.GlesModelRenderer { native <methods>; }
-keepclasseswithmembers class com.ashairfoil.prism.FilamentModelActivity { native <methods>; }

# Media3 unstable APIs
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
```

### M-20 | AndroidManifest.xml | Add network security config
Create file `prism/app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">192.168.0.0/16</domain>
        <domain includeSubdomains="true">10.0.0.0/8</domain>
    </domain-config>
</network-security-config>
```
In AndroidManifest.xml, add to `<application>`: `android:networkSecurityConfig="@xml/network_security_config"`

NOTE: The domain-config for local IPs allows cleartext for LAN streaming (DeoVR local servers). Verify this doesn't break the build — if domain-config with IP ranges isn't supported, just use the base-config alone.

### M-21 | data/MediaLibrary.kt | Fix recent files ordering
Change `prefs.edit().putStringSet(KEY_RECENT_FILES, recent.toSet()).apply()` to store as a JSON array string:
```kotlin
prefs.edit().putString(KEY_RECENT_FILES, JSONArray(recent).toString()).apply()
```
And the corresponding read to:
```kotlin
val json = prefs.getString(KEY_RECENT_FILES, "[]") ?: "[]"
val arr = JSONArray(json)
val recent = (0 until arr.length()).map { arr.getString(it) }
```

### M-22 | GlesModelRenderer.kt | Delete emitter GL resources in destroy()
In `destroy()`, add before the return:
```kotlin
if (emitterVao != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(emitterVao), 0); emitterVao = 0 }
if (emitterVbo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(emitterVbo), 0); emitterVbo = 0 }
if (emitterEbo != 0) { GLES30.glDeleteBuffers(1, intArrayOf(emitterEbo), 0); emitterEbo = 0 }
```

### M-23 | FilamentRenderer.kt | Delete GL resources in destroy()
In `destroy()`, add:
```kotlin
if (ownGlTexIds[0] != 0) GLES30.glDeleteTextures(2, ownGlTexIds, 0)
if (ownGlDepthRBs[0] != 0) GLES30.glDeleteRenderbuffers(2, ownGlDepthRBs, 0)
if (blitFboSrc[0] != 0) GLES30.glDeleteFramebuffers(1, blitFboSrc, 0)
if (blitFboDst[0] != 0) GLES30.glDeleteFramebuffers(1, blitFboDst, 0)
```

### M-24 | playback/SubtitleRenderer.kt | Move file I/O off main thread
Wrap `parseSrt()` and `parseAss()` calls in `loadForVideo()` with coroutine:
```kotlin
suspend fun loadForVideo(videoFile: File) = withContext(Dispatchers.IO) {
    // ... existing file I/O logic
}
```
Or if the method is not already suspend, change the caller to use a coroutine scope.

### M-25 | AudioReactor.kt | Make specWriteToA volatile
Change `private var specWriteToA = true` to `@Volatile private var specWriteToA = true`

---

## PHASE 4: LOW — Cleanup, Dead Code, Minor Polish (18 fixes)

### L-1 | MainActivity.kt | Remove dead fields
Remove these unused declarations (verify each is truly unused by searching for references first):
- `filePickerFilterBy` (if unused)
- `grabStartAimDir` (if replaced by `grabStartAimRot`)
- `modelGrabStartAimDir` (if unused)
- `triggerDownTime` and `TAP_THRESHOLD_MS` (if unused)
- `modelGrabDistance` (if never read)

### L-2 | MainActivity.kt | Replace deprecated startActivityForResult
Replace `startActivityForResult(intent, 101)` with `ActivityResultContracts` pattern. Add a launcher field:
```kotlin
private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    // move onActivityResult logic here
}
```
Replace `startActivityForResult(intent, 101)` with `filePickerLauncher.launch(intent)`.

### L-3 | MainActivity.kt | Don't reset playbackSpeed on file open
Find `playbackSpeed = 1.0f` inside `playFile()` and remove it, or change to only reset if no saved per-file speed exists.

### L-4 | MainActivity.kt | Persist depthSimulation and spatialAudio state
In `loadGlobalSettings()`, add:
```kotlin
depthSimulation.enabled = SettingsManager.getBoolean("depth_sim_enabled", false)
spatialAudio.enabled = SettingsManager.getBoolean("spatial_audio_enabled", false)
```
When these are toggled, add corresponding `SettingsManager.setBoolean(...)` calls.

### L-5 | scene/XrSensorPoller.kt | Fix capabilities check retry
Change the initial value: `private var xrSensorCaps = -1` (use -1 as "not queried" sentinel).
Change the check: `if (xrSensorCaps < 0) { xrSensorCaps = getSensorCaps() }`. This way 0 (no caps) is a valid result.

### L-6 | playback/HeadTrackingConfig.kt | Remove unused smoothing field
If `smoothing` is declared but never used in `filterRotation()` or `filterPosition()`, remove the field entirely.

### L-7 | playback/ScreenController.kt | Add scale check to isModified()
Add `state.scale != def.scale` to the comparison in `isModified()`.

### L-8 | scene/UiRenderer.kt | Remove dead buildModelPanelView
If `buildModelPanelView()` is not called anywhere, delete the entire method.

### L-9 | haptics/SpectralAnalyzer.kt | Mark analyze() as unused or remove
If `analyze()` and `fft()` are not called, either remove them or add a `@Suppress("unused")` annotation.

### L-10 | xr_renderer.cpp | Remove dead light estimation variables
Find and remove the unused `shLight`, `dirLight`, `ambLight` variables (superseded by later `sh`, `dir`, `amb` variables). They are dead code wasting stack space.

### L-11 | openxr_input.cpp + xr_renderer.cpp | Log failed createAction calls
In both files, find the block of `createAction(...)` / `makeAction(...)` calls. Wrap each in a check:
```cpp
if (!createAction(actionSet_, thumbstickAction_, "thumbstick", ...)) {
    LOGE("Failed to create thumbstick action");
}
```

### L-12 | MainActivity.kt | Change PlacedModel from data class to regular class
Find `data class PlacedModel(` and change to `class PlacedModel(`. This removes auto-generated equals/hashCode/copy that don't account for entity side effects.

### L-13 | effects/LensDistortionEffect.kt | Document FOV clamp behavior
Add a comment before the FOV clamp in the shader:
```kotlin
// Note: FOV clamped to 179 in shader. Presets with FOV > 179 (MKX200, VRCA220)
// are effectively limited. Brown-Conrady model breaks down at ultra-wide FOV.
```

### L-14 | Multiple files | Change INFO logs to DEBUG for file paths
In `MediaLibrary.kt`, `DeoVrApi.kt`, `StreamingBrowser.kt`, `FunscriptParser.kt`: change `Log.i(TAG, ...)` to `Log.d(TAG, ...)` for messages containing file paths or API URLs.

### L-15 | app/build.gradle.kts | Increment versionCode
Change `versionCode = 1` to `versionCode = 2` and `versionName = "1.0"` to `versionName = "1.1"`.

### L-16 | AndroidManifest.xml | Disable backup
Add to `<application>`: `android:allowBackup="false"`

### L-17 | GlesModelRenderer.kt | Document normal matrix limitation
Add comment above `extractNormalMatrix`:
```kotlin
// Note: Assumes uniform scaling. Non-uniform scale requires transpose(inverse(mat3(mv))).
```

### L-18 | GlesModelRenderer.kt | Remove allocating convenience overloads if unused
Check if `quatForward(q: FloatArray): FloatArray` (the allocating 1-arg overload) is called from outside the class. If only the 2-arg output-buffer overload is used, remove the 1-arg version. Same for `rotateVecByQuat`.

---

## Post-Fix Verification

After all phases:
1. `cd /home/kali/ChloeVR/prism && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`
2. `cd /home/kali/ChloeVR/prism && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew lint`
3. `git diff --stat` to see all changed files
4. `git diff` to review all changes
5. Report: which fixes were applied, which needed adaptation, any that couldn't be applied
