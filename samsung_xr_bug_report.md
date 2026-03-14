# OpenXR Runtime Bug Report — Samsung Galaxy XR (SM-I610)

## Summary

Two OpenXR extension functions in `libopenxr_android.so` crash with SIGSEGV when called according to the published API specification on developer.android.com. Both crashes occur inside the runtime binary itself, not in application code.

## Device Information

- **Device:** Samsung Galaxy XR (SM-I610)
- **Build:** UML1.250710.002.A1.I610UEU1AYKE
- **Fingerprint:** samsung/xrvst2ue/xrvst2:14/UML1.250710.002.A1/I610UEU1AYKE:user/release-keys
- **Android:** 14
- **Runtime:** libopenxr_android.so (BuildId: 41d6550fc9cda83fa95461655d9c985a)
- **Runtime SHA1:** f0060c6e51844c8de6f6512992920c252e67049b
- **Runtime size:** 2,250,696 bytes
- **OpenXR Loader:** org.khronos.openxr:openxr_loader_for_android:1.1.49
- **App ABI:** arm64-v8a, C++17, OpenGL ES 3.x

---

## Bug 1: xrGetLightEstimateANDROID crashes when XrDirectionalLightANDROID is chained

### Extension
`XR_ANDROID_light_estimation` — https://developer.android.com/develop/xr/openxr/extensions/XR_ANDROID_light_estimation

### Expected Behavior
Chaining `XrDirectionalLightANDROID` onto `XrAmbientLightANDROID.next` (or directly onto `XrLightEstimateANDROID.next`) should populate the directional light data when the runtime has a valid estimate.

### Actual Behavior
`xrGetLightEstimateANDROID` crashes with SIGSEGV inside `oxr_xrGetLightEstimateANDROID` (offset +760 in libopenxr_android.so) when ANY struct beyond `XrAmbientLightANDROID` is chained to the output.

### Crash Signature
```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xbe853e433dae1c65
backtrace:
  #00 pc 000000000011fac0 /system/lib64/libopenxr_android.so (oxr_xrGetLightEstimateANDROID+760)
```

### Reproduction

Ambient-only chain **works** (stable 72fps):
```c
XrAmbientLightANDROID amb = {
    .type = XR_TYPE_AMBIENT_LIGHT_ANDROID,
    .next = NULL
};
XrLightEstimateANDROID out = {
    .type = XR_TYPE_LIGHT_ESTIMATE_ANDROID,
    .next = &amb
};
xrGetLightEstimate(estimator, &getInfo, &out);  // OK
```

Adding directional **crashes**:
```c
XrDirectionalLightANDROID dir = {
    .type = XR_TYPE_DIRECTIONAL_LIGHT_ANDROID,
    .next = NULL
};
XrAmbientLightANDROID amb = {
    .type = XR_TYPE_AMBIENT_LIGHT_ANDROID,
    .next = &dir  // <-- causes crash
};
XrLightEstimateANDROID out = {
    .type = XR_TYPE_LIGHT_ESTIMATE_ANDROID,
    .next = &amb
};
xrGetLightEstimate(estimator, &getInfo, &out);  // SIGSEGV
```

### What was tried
- memset all structs to zero before initialization
- Heap-allocate all structs (new/malloc)
- Designated initializers vs manual field assignment
- Adding MAX_ENUM = 0x7FFFFFFF sentinels to all enums to force 32-bit width
- Chaining directional directly on root.next (without ambient)
- Chaining only directional (no ambient)
- XrSphericalHarmonicsANDROID alone — also crashes

**All crash at the same location in libopenxr_android.so.**

### Suspected Cause
The runtime binary's internal struct layout for `XrDirectionalLightANDROID` and `XrSphericalHarmonicsANDROID` does not match the published specification. The runtime may have been compiled with different struct definitions than what is documented at developer.android.com.

---

## Bug 2: xrSetPerformanceMetricsStateANDROID function signature mismatch (RESOLVED)

### Extension
`XR_ANDROID_performance_metrics`

### Issue
The developer.android.com documentation did not clearly specify that `xrSetPerformanceMetricsStateANDROID` takes a `const XrPerformanceMetricsStateANDROID*` (pointer to struct) rather than a raw `XrBool32`. Passing a raw boolean value caused the runtime to dereference the value as a pointer, crashing at address 0x1.

### Crash Signature
```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000001
backtrace:
  #00 pc 000000000011aa7c /system/lib64/libopenxr_android.so (oxr_xrSetPerformanceMetricsStateANDROID+168)
```

### Resolution
Using the correct struct-pointer signature resolved this crash. However, the documentation should be clarified.

---

## Impact

- **XR_ANDROID_light_estimation:** Directional light and spherical harmonics are completely unusable on SM-I610. Only ambient light works. This means apps cannot get real-world light direction for proper shadow/highlight placement in mixed reality.
- Affects any native C/C++ OpenXR application using the extension according to the published specification.
- Unity/Godot users may not encounter this because their SDKs may not chain these structs, or may have internal workarounds.

## Request

1. Please verify that the struct layouts in `libopenxr_android.so` match the published specification for `XrDirectionalLightANDROID`, `XrSphericalHarmonicsANDROID`, and `XrEnvironmentLightingCubemapANDROID`.
2. If the structs differ, please either update the runtime binary or publish the correct struct definitions.
3. Consider publishing official C headers for all `XR_ANDROID_*` extensions (like the Godot team's `godot_openxr_vendors/thirdparty/androidxr/include/` headers) so native developers don't have to reverse-engineer struct layouts from documentation.

## Contact

Developer: Ash Airfoil
App: ChloeVR (com.ashairfoil.prism)
GitHub: github.com/coldbricks/ChloeVR
