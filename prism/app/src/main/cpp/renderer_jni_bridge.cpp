#include "xr_renderer.h"
#include <jni.h>

static XrRenderer* g_renderer = nullptr;
static FrameData g_frameData;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeRendererInit(
        JNIEnv* env, jobject thiz, jobject activity) {
    if (g_renderer) {
        XR_LOGE("Renderer already initialized, shutting down first");
        g_renderer->shutdown();
        delete g_renderer;
    }
    g_renderer = new XrRenderer();
    if (!g_renderer->init(env, activity)) {
        XR_LOGE("Failed to initialize XrRenderer");
        delete g_renderer;
        g_renderer = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetEglContext(
        JNIEnv* env, jobject thiz) {
    if (!g_renderer) return 0;
    return (jlong)g_renderer->getEglContext();
}

JNIEXPORT jlong JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetEglDisplay(
        JNIEnv* env, jobject thiz) {
    if (!g_renderer) return 0;
    return (jlong)g_renderer->getEglDisplay();
}

JNIEXPORT jintArray JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetSwapchainSize(
        JNIEnv* env, jobject thiz) {
    jintArray result = env->NewIntArray(2);
    if (!g_renderer) return result;
    jint size[2] = {(jint)g_renderer->getSwapchainWidth(),
                    (jint)g_renderer->getSwapchainHeight()};
    env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

// Frame data layout (float array):
// [0]      shouldRender (1.0 or 0.0)
// [1]      leftTextureId
// [2]      rightTextureId
// [3..18]  leftProjection (16 floats, column-major)
// [19..34] rightProjection (16 floats, column-major)
// [35..50] leftViewMatrix (16 floats, column-major)
// [51..66] rightViewMatrix (16 floats, column-major)
// [67]     width
// [68]     height
// Total: 69 floats

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeWaitFrame(
        JNIEnv* env, jobject thiz, jfloatArray outFrameData) {
    if (!g_renderer || !g_renderer->isRunning()) return JNI_FALSE;

    if (!g_renderer->waitFrame(g_frameData)) return JNI_FALSE;

    float data[69];
    data[0] = g_frameData.shouldRender ? 1.0f : 0.0f;
    data[1] = (float)g_frameData.eyes[0].textureId;
    data[2] = (float)g_frameData.eyes[1].textureId;
    memcpy(&data[3],  g_frameData.eyes[0].projection, 16 * sizeof(float));
    memcpy(&data[19], g_frameData.eyes[1].projection, 16 * sizeof(float));
    memcpy(&data[35], g_frameData.eyes[0].viewMatrix, 16 * sizeof(float));
    memcpy(&data[51], g_frameData.eyes[1].viewMatrix, 16 * sizeof(float));
    data[67] = (float)g_frameData.eyes[0].width;
    data[68] = (float)g_frameData.eyes[0].height;

    env->SetFloatArrayRegion(outFrameData, 0, 69, data);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSubmitFrame(
        JNIEnv* env, jobject thiz) {
    if (!g_renderer) return;
    g_renderer->submitFrame(g_frameData);
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollInput(
        JNIEnv* env, jobject thiz, jfloatArray outState) {
    if (!g_renderer) return JNI_FALSE;
    ControllerState state;
    if (!g_renderer->pollInput(state)) return JNI_FALSE;
    env->SetFloatArrayRegion(outState, 0, ControllerState::SIZE, state.data());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeRendererShutdown(
        JNIEnv* env, jobject thiz) {
    if (g_renderer) {
        g_renderer->shutdown();
        delete g_renderer;
        g_renderer = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeMakeGLContextCurrent(
        JNIEnv* env, jobject thiz) {
    if (!g_renderer) return JNI_FALSE;
    EGLDisplay display = g_renderer->getEglDisplay();
    EGLContext context = g_renderer->getEglContext();
    EGLSurface surface = g_renderer->getEglSurface();
    if (!eglMakeCurrent(display, surface, surface, context)) {
        XR_LOGE("eglMakeCurrent on render thread failed: 0x%x", eglGetError());
        return JNI_FALSE;
    }
    XR_LOGI("EGL context made current on render thread");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeInitUiQuad(
        JNIEnv* env, jobject thiz, jint width, jint height) {
    if (!g_renderer) return JNI_FALSE;
    return g_renderer->initUiQuad(width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeAcquireUiImage(
        JNIEnv* env, jobject thiz) {
    if (!g_renderer) return 0;
    return (jint)g_renderer->acquireUiImage();
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeReleaseUiImage(
        JNIEnv* env, jobject thiz) {
    if (g_renderer) g_renderer->releaseUiImage();
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSetUiVisible(
        JNIEnv* env, jobject thiz, jboolean visible) {
    if (g_renderer) g_renderer->setUiVisible(visible);
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeIsRunning(
        JNIEnv* env, jobject thiz) {
    return (g_renderer && g_renderer->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
