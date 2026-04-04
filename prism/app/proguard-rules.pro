# ChloeVR ProGuard / R8 Rules
#
# PHILOSOPHY: Only keep what R8 cannot trace on its own. Direct Kotlin
# references, interface implementations, and lambda callbacks are all
# traceable. Keep rules are needed for:
#   1. JNI — native code calls Java/Kotlin via name strings
#   2. Reflection — libraries that load classes/methods by name at runtime
#   3. Serialization — if field names must survive minification
#
# Everything else (effects, data classes, UI, scene, input, settings,
# playback) is directly referenced from Kotlin and does NOT need keep rules.

# ── JNI bridges ──────────────────────────────────────────────────────────
# Native C++ code (jni_bridge.cpp, renderer_jni_bridge.cpp) calls these
# classes and methods by name. R8 cannot see across the JNI boundary.

-keep class com.ashairfoil.prism.OpenXRInput { *; }
-keepclassmembers class com.ashairfoil.prism.OpenXRInput {
    native <methods>;
}

-keepclasseswithmembers class com.ashairfoil.prism.FilamentModelActivity { native <methods>; }
-keepclasseswithmembers class com.ashairfoil.prism.GlesModelRenderer { native <methods>; }

# Catch-all: any class with native methods must keep those method signatures
# so the JNI RegisterNatives / dynamic lookup can find them.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Tink crypto (reflection-heavy) ──────────────────────────────────────
# EncryptedSharedPreferences → MasterKey → Tink KeysetManager uses
# reflection to instantiate key managers, AEAD primitives, etc.
# Stripping these causes runtime NoSuchMethodException / ClassNotFoundException.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── EncryptedSharedPreferences (security-crypto) ────────────────────────
# Uses Tink internally via reflection. Keep the facade class so R8 doesn't
# inline away the entry point that Tink reflects into.
-keep class androidx.security.crypto.EncryptedSharedPreferences { *; }
-keep class androidx.security.crypto.MasterKey { *; }
-keep class androidx.security.crypto.MasterKey$Builder { *; }
-dontwarn androidx.security.crypto.**

# ── Billing (future BillingClient integration) ─────────────────────────
# ProUpgrade is currently a stub. When BillingClient is added, Google Play
# Billing uses AIDL + reflection for purchase verification. Keep the class
# so the future migration doesn't hit minification issues.
-keep class com.ashairfoil.prism.billing.ProUpgrade { *; }

# ── Filament 3D engine ─────────────────────────────────────────────────
# Filament uses JNI internally (nObject pointers, native destroy calls).
# The public Java API classes must survive for its own JNI to work.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# ── Library -dontwarn (suppress warnings for optional platform classes) ──
# These libraries reference classes that may not exist on all devices or
# at compile time. The -dontwarn prevents build failures; the libraries
# handle missing classes gracefully at runtime.
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.media3.**
-dontwarn androidx.xr.**
-dontwarn org.khronos.openxr.**
-dontwarn com.android.extensions.xr.**
-dontwarn com.google.androidxr.**
-dontwarn com.google.imp.splitengine.**
-dontwarn com.google.common.util.concurrent.**
