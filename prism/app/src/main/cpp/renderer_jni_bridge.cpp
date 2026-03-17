#include "xr_renderer.h"
#include <jni.h>
#include <mutex>

static XrRenderer* g_renderer = nullptr;
static FrameData g_frameData;
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeRendererInit(
        JNIEnv* env, jobject thiz, jobject activity) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return 0;
    return (jlong)g_renderer->getEglContext();
}

JNIEXPORT jlong JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetEglDisplay(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return 0;
    return (jlong)g_renderer->getEglDisplay();
}

JNIEXPORT jintArray JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetSwapchainSize(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
    std::lock_guard<std::mutex> lock(g_mutex);
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
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return;
    g_renderer->submitFrame(g_frameData);
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollInput(
        JNIEnv* env, jobject thiz, jfloatArray outState) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    ControllerState state;
    if (!g_renderer->pollInput(state)) return JNI_FALSE;
    env->SetFloatArrayRegion(outState, 0, ControllerState::SIZE, state.data());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeRendererShutdown(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) {
        g_renderer->shutdown();
        delete g_renderer;
        g_renderer = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeMakeGLContextCurrent(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    return g_renderer->initUiQuad(width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeAcquireUiImage(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return 0;
    return (jint)g_renderer->acquireUiImage();
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeReleaseUiImage(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) g_renderer->releaseUiImage();
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSetUiVisible(
        JNIEnv* env, jobject thiz, jboolean visible) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) g_renderer->setUiVisible(visible);
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSetUiPose(
        JNIEnv* env, jobject thiz,
        jfloat px, jfloat py, jfloat pz,
        jfloat rx, jfloat ry, jfloat rz, jfloat rw) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) g_renderer->setUiPose(px, py, pz, rx, ry, rz, rw);
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSetUiSize(
        JNIEnv* env, jobject thiz, jfloat w, jfloat h) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) g_renderer->setUiSize(w, h);
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeIsRunning(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_renderer && g_renderer->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

// Light estimation output layout (float array, 41 floats):
// [0]  valid (1.0 or 0.0)
// [1-3]   ambientR/G/B
// [4-6]   colorCorrR/G/B
// [7-9]   dirIntensityR/G/B
// [10-12] dirX/Y/Z
// [13]    shValid (1.0 or 0.0)
// [14-40] SH coefficients (9 × RGB = 27 floats)

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollLightEstimate(
        JNIEnv* env, jobject thiz, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::LightEstimate est;
    if (!g_renderer->pollLightEstimate(est)) return JNI_FALSE;

    float data[41];
    data[0] = est.valid ? 1.0f : 0.0f;
    data[1] = est.ambientR; data[2] = est.ambientG; data[3] = est.ambientB;
    data[4] = est.colorCorrR; data[5] = est.colorCorrG; data[6] = est.colorCorrB;
    data[7] = est.dirIntensityR; data[8] = est.dirIntensityG; data[9] = est.dirIntensityB;
    data[10] = est.dirX; data[11] = est.dirY; data[12] = est.dirZ;
    data[13] = est.shValid ? 1.0f : 0.0f;
    memcpy(&data[14], est.sh, 27 * sizeof(float));
    env->SetFloatArrayRegion(outData, 0, 41, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Hand tracking JNI — 209 floats per hand
// [0]=active, then 26 joints × 8 floats (posX,posY,posZ, rotX,rotY,rotZ,rotW, radius)
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollHandTracking(
        JNIEnv* env, jobject thiz, jint hand, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::HandJointData hjd;
    if (!g_renderer->pollHandTracking(hand, hjd)) return JNI_FALSE;

    float data[XrRenderer::HandJointData::SIZE];
    data[0] = hjd.active ? 1.0f : 0.0f;
    for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
        int off = 1 + j * 8;
        data[off+0] = hjd.joints[j].posX;
        data[off+1] = hjd.joints[j].posY;
        data[off+2] = hjd.joints[j].posZ;
        data[off+3] = hjd.joints[j].rotX;
        data[off+4] = hjd.joints[j].rotY;
        data[off+5] = hjd.joints[j].rotZ;
        data[off+6] = hjd.joints[j].rotW;
        data[off+7] = hjd.joints[j].radius;
    }
    env->SetFloatArrayRegion(outData, 0, XrRenderer::HandJointData::SIZE, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Eye tracking JNI — 25 floats
// [0]=valid, L:[1-7](valid,pos3,rot4), R:[8-14], Comb:[15-21]...
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollEyeTracking(
        JNIEnv* env, jobject thiz, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::EyeTrackingData etd;
    if (!g_renderer->pollEyeTracking(etd)) return JNI_FALSE;

    float data[XrRenderer::EyeTrackingData::SIZE];
    data[0] = etd.valid ? 1.0f : 0.0f;
    // Left eye: valid + pos(3) + rot(4) = 8
    data[1] = etd.leftValid ? 1.0f : 0.0f;
    data[2] = etd.leftPosX; data[3] = etd.leftPosY; data[4] = etd.leftPosZ;
    data[5] = etd.leftRotX; data[6] = etd.leftRotY; data[7] = etd.leftRotZ; data[8] = etd.leftRotW;
    // Right eye
    data[9] = etd.rightValid ? 1.0f : 0.0f;
    data[10] = etd.rightPosX; data[11] = etd.rightPosY; data[12] = etd.rightPosZ;
    data[13] = etd.rightRotX; data[14] = etd.rightRotY; data[15] = etd.rightRotZ; data[16] = etd.rightRotW;
    // Combined gaze
    data[17] = etd.combinedValid ? 1.0f : 0.0f;
    data[18] = etd.combPosX; data[19] = etd.combPosY; data[20] = etd.combPosZ;
    data[21] = etd.combRotX; data[22] = etd.combRotY; data[23] = etd.combRotZ; data[24] = etd.combRotW;
    env->SetFloatArrayRegion(outData, 0, XrRenderer::EyeTrackingData::SIZE, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Face tracking JNI — 69 floats: [0]=valid, [1-68]=blend shapes
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollFaceTracking(
        JNIEnv* env, jobject thiz, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::FaceTrackingData ftd;
    if (!g_renderer->pollFaceTracking(ftd)) return JNI_FALSE;

    float data[XrRenderer::FaceTrackingData::SIZE];
    data[0] = ftd.valid ? 1.0f : 0.0f;
    memcpy(&data[1], ftd.blendShapes, sizeof(float) * XR_FACE_BLEND_SHAPE_COUNT_ANDROID);
    env->SetFloatArrayRegion(outData, 0, XrRenderer::FaceTrackingData::SIZE, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Plane detection JNI — 322 floats
// [0]=valid, [1]=count, then count × 10 (pos3,rot4,ext2,label)
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollPlanes(
        JNIEnv* env, jobject thiz, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::PlaneData pd;
    if (!g_renderer->pollPlanes(pd)) return JNI_FALSE;

    float data[XrRenderer::PlaneData::SIZE];
    memset(data, 0, sizeof(data));
    data[0] = pd.valid ? 1.0f : 0.0f;
    data[1] = (float)pd.planeCount;
    for (int i = 0; i < pd.planeCount && i < XrRenderer::PlaneData::MAX_PLANES; i++) {
        int off = 2 + i * 10;
        data[off+0] = pd.planes[i].posX;
        data[off+1] = pd.planes[i].posY;
        data[off+2] = pd.planes[i].posZ;
        data[off+3] = pd.planes[i].rotX;
        data[off+4] = pd.planes[i].rotY;
        data[off+5] = pd.planes[i].rotZ;
        data[off+6] = pd.planes[i].rotW;
        data[off+7] = pd.planes[i].extentX;
        data[off+8] = pd.planes[i].extentY;
        data[off+9] = (float)pd.planes[i].label;
    }
    env->SetFloatArrayRegion(outData, 0, XrRenderer::PlaneData::SIZE, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Performance metrics JNI — 5 floats
// [0]=valid, [1]=gpu_ms, [2]=cpu_ms, [3]=dropped, [4]=refreshRate
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativePollPerfMetrics(
        JNIEnv* env, jobject thiz, jfloatArray outData) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return JNI_FALSE;
    XrRenderer::PerfMetrics pm;
    if (!g_renderer->pollPerfMetrics(pm)) return JNI_FALSE;

    float data[XrRenderer::PerfMetrics::SIZE];
    data[0] = pm.valid ? 1.0f : 0.0f;
    data[1] = pm.gpuFrameTimeMs;
    data[2] = pm.cpuFrameTimeMs;
    data[3] = pm.compositorDroppedFrames;
    data[4] = pm.displayRefreshRate;
    env->SetFloatArrayRegion(outData, 0, XrRenderer::PerfMetrics::SIZE, data);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════════
// Extension availability query — returns bitmask of available XR sensors
// Bit 0=hand, 1=eye, 2=face, 3=planes, 4=refreshRate, 5=perfMetrics, 6=passthrough, 7=light
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jint JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetSensorCapabilities(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return 0;
    int caps = 0;
    if (g_renderer->hasHandTracking())     caps |= (1 << 0);
    if (g_renderer->hasEyeTracking())      caps |= (1 << 1);
    if (g_renderer->hasFaceTracking())      caps |= (1 << 2);
    if (g_renderer->hasPlaneDetection())    caps |= (1 << 3);
    if (g_renderer->hasRefreshRateControl()) caps |= (1 << 4);
    if (g_renderer->hasPerfMetrics())       caps |= (1 << 5);
    if (g_renderer->hasPassthroughState())  caps |= (1 << 6);
    caps |= (1 << 7); // light estimation always attempted
    return caps;
}

JNIEXPORT jint JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetPassthroughState(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_renderer) return 0;
    return g_renderer->getPassthroughState();
}

// ═══════════════════════════════════════════════════════════════════════
// Foveated rendering JNI
// ═══════════════════════════════════════════════════════════════════════

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeHasFoveation(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_renderer && g_renderer->hasFoveation()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeSetFoveationLevel(
        JNIEnv* env, jobject thiz, jint level) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_renderer) g_renderer->setFoveationLevel(level);
}

JNIEXPORT jint JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeGetFoveationLevel(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_renderer ? g_renderer->getFoveationLevel() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeIsFocused(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_renderer && g_renderer->isFocused()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ashairfoil_prism_FilamentModelActivity_nativeIsUsingStageSpace(
        JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_renderer && g_renderer->isUsingStageSpace()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
