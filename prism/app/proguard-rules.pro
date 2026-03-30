# ChloeVR ProGuard Rules

# ── JNI bridges ──────────────────────────────────────────────────────────
# Keep OpenXR JNI bridge (called from native via JNI)
-keep class com.ashairfoil.prism.OpenXRInput { *; }
-keepclassmembers class com.ashairfoil.prism.OpenXRInput {
    native <methods>;
}

# Keep native JNI methods for model viewer
-keepclasseswithmembers class com.ashairfoil.prism.GlesModelRenderer { native <methods>; }
-keepclasseswithmembers class com.ashairfoil.prism.FilamentModelActivity { native <methods>; }

# ── Effects (reflection for shader uniform binding) ──────────────────────
-keep class com.ashairfoil.prism.effects.** { *; }
-keep class com.ashairfoil.prism.DeoVrAlphaPackedEffect** { *; }
-keep class com.ashairfoil.prism.ChromaKeyEffect** { *; }

# ── Data classes (JSON parsing, SharedPreferences serialization) ─────────
-keep class com.ashairfoil.prism.data.** { *; }
-keep class com.ashairfoil.prism.FileNameParser$VideoMetadata { *; }

# ── Filament 3D rendering engine ────────────────────────────────────────
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# ── Haptics (BLE callbacks, GATT characteristic writes) ─────────────────
-keep class com.ashairfoil.prism.haptics.** { *; }

# ── Billing / licensing ─────────────────────────────────────────────────
-keep class com.ashairfoil.prism.billing.** { *; }

# ── UI classes (Canvas-based rendering, ViewHolder pattern) ─────────────
-keep class com.ashairfoil.prism.ui.** { *; }

# ── Input / sensors (callback-based, SensorEventListener) ───────────────
-keep class com.ashairfoil.prism.input.** { *; }
-keep class com.ashairfoil.prism.SensorHub { *; }
-keep class com.ashairfoil.prism.AudioReactor { *; }
-keep class com.ashairfoil.prism.AudioPlayer { *; }

# ── Scene management ────────────────────────────────────────────────────
-keep class com.ashairfoil.prism.scene.** { *; }

# ── Settings ────────────────────────────────────────────────────────────
-keep class com.ashairfoil.prism.settings.** { *; }

# ── Playback components ────────────────────────────────────────────────
-keep class com.ashairfoil.prism.playback.** { *; }

# ── Kotlin coroutines ──────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── ExoPlayer / Media3 ─────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Jetpack XR SDK ──────────────────────────────────────────────────────
-keep class androidx.xr.** { *; }
-dontwarn androidx.xr.**

# ── OpenXR loader ───────────────────────────────────────────────────────
-keep class org.khronos.openxr.** { *; }
-dontwarn org.khronos.openxr.**

# ── EncryptedSharedPreferences (security-crypto) ────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Guava ListenableFuture (required by XR SDK) ────────────────────────
-keep class com.google.common.util.concurrent.** { *; }
-dontwarn com.google.common.util.concurrent.**

# ── Tink crypto (transitive dep of security-crypto) ────────────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── Android XR extensions (platform classes, present on-device only) ────
-dontwarn com.android.extensions.xr.**
-dontwarn com.google.androidxr.**
-dontwarn com.google.imp.splitengine.**

# ── Catch-all: keep all native methods ─────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}
