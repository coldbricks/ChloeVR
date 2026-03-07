#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "openxr_input.h"

static OpenXRInput gInput;
static JavaVM* gJavaVM = nullptr;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativeInit(
    JNIEnv* env, jobject thiz, jobject activity) {
    return gInput.init(env, activity) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativeShutdown(
    JNIEnv* env, jobject thiz) {
    gInput.shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativePoll(
    JNIEnv* env, jobject thiz, jfloatArray outState) {
    ControllerState state;
    if (!gInput.poll(state)) return JNI_FALSE;

    env->SetFloatArrayRegion(outState, 0, ControllerState::SIZE, state.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativeSetSurfaceSize(
    JNIEnv* env, jobject thiz, jobject surface, jint width, jint height) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window) {
        ANativeWindow_setBuffersGeometry(window, width, height, 0);
        ANativeWindow_release(window);
    }
}
