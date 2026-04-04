#include <jni.h>
#include <mutex>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "openxr_input.h"

static OpenXRInput gInput;
static JavaVM* gJavaVM = nullptr;
static std::mutex gInputMutex;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativeInit(
    JNIEnv* env, jobject thiz, jobject activity) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    return gInput.init(env, activity) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativeShutdown(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    gInput.shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_OpenXRInput_nativePoll(
    JNIEnv* env, jobject thiz, jfloatArray outState) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    if (!outState) return JNI_FALSE;
    jsize len = env->GetArrayLength(outState);
    if (len < ControllerState::SIZE) return JNI_FALSE;
    ControllerState state;
    if (!gInput.poll(state)) return JNI_FALSE;

    env->SetFloatArrayRegion(outState, 0, ControllerState::SIZE, state.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
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
