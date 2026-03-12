# ChloeVR ProGuard Rules

# Keep OpenXR JNI bridge
-keep class com.ashairfoil.prism.OpenXRInput { *; }
-keepclassmembers class com.ashairfoil.prism.OpenXRInput {
    native <methods>;
}

# Keep all GLSL effects (they use reflection for shader uniform binding)
-keep class com.ashairfoil.prism.effects.** { *; }
-keep class com.ashairfoil.prism.DeoVrAlphaPackedEffect** { *; }
-keep class com.ashairfoil.prism.ChromaKeyEffect** { *; }

# Keep data classes (used in JSON parsing and SharedPreferences)
-keep class com.ashairfoil.prism.data.** { *; }
-keep class com.ashairfoil.prism.FileNameParser$VideoMetadata { *; }

# Keep Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Jetpack XR SDK
-keep class androidx.xr.** { *; }
-dontwarn androidx.xr.**

# OpenXR loader
-keep class org.khronos.openxr.** { *; }
-dontwarn org.khronos.openxr.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
