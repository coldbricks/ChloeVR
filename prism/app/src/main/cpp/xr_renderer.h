#pragma once

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <android/log.h>
#include <vector>
#include "openxr_input.h"  // reuse ControllerState
#include "xr_light_estimation.h"

#define XRLOG_TAG "ChloeVR-XRRenderer"
#define XR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, XRLOG_TAG, __VA_ARGS__)
#define XR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, XRLOG_TAG, __VA_ARGS__)

struct EyeRenderInfo {
    uint32_t textureId;      // GL texture from swapchain
    uint32_t imageIndex;     // swapchain image index
    float projection[16];    // 4x4 column-major projection matrix
    float viewMatrix[16];    // 4x4 column-major view matrix
    uint32_t width;
    uint32_t height;
};

struct FrameData {
    EyeRenderInfo eyes[2];   // 0=left, 1=right
    XrTime predictedDisplayTime;
    bool shouldRender;
};

class XrRenderer {
public:
    bool init(JNIEnv* env, jobject activity);
    void shutdown();

    // Frame lifecycle (call from render thread)
    bool waitFrame(FrameData& frame);
    void submitFrame(const FrameData& frame);

    // Input (same session)
    bool pollInput(ControllerState& state);

    // Light estimation
    struct LightEstimate {
        bool valid = false;
        float ambientR = 0, ambientG = 0, ambientB = 0;
        float colorCorrR = 1, colorCorrG = 1, colorCorrB = 1;
        float dirIntensityR = 0, dirIntensityG = 0, dirIntensityB = 0;
        float dirX = 0, dirY = 1, dirZ = 0;
        bool shValid = false;
        float sh[27] = {}; // 9 × RGB coefficients
        static constexpr int SIZE = 41; // total floats in JNI buffer
    };
    bool pollLightEstimate(LightEstimate& estimate);

    // UI quad
    bool initUiQuad(uint32_t width, uint32_t height);
    uint32_t acquireUiImage();  // returns GL texture ID
    void releaseUiImage();
    void setUiVisible(bool visible) { uiVisible_ = visible; }
    bool isUiVisible() const { return uiVisible_; }

    // EGL handles for shared context
    EGLDisplay getEglDisplay() const { return eglDisplay_; }
    EGLContext getEglContext() const { return eglContext_; }
    EGLSurface getEglSurface() const { return eglSurface_; }
    uint32_t getSwapchainWidth() const { return swapchainWidth_; }
    uint32_t getSwapchainHeight() const { return swapchainHeight_; }
    bool isRunning() const { return running_; }

private:
    bool createInstance(JNIEnv* env, jobject activity);
    bool getSystem();
    bool initEGL();
    void shutdownEGL();
    bool createSession();
    bool createSwapchains();
    bool createActions();
    void handleSessionStateChange(XrSessionState newState);

    // Pose/projection helpers
    void xrPoseToViewMatrix(const XrPosef& pose, float* out);
    void xrFovToProjection(const XrFovf& fov, float nearZ, float farZ, float* out);

    // OpenXR core
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace appSpace_ = XR_NULL_HANDLE;
    bool sessionReady_ = false;
    bool running_ = false;

    // View configuration
    XrViewConfigurationView viewConfigs_[2] = {};
    uint32_t swapchainWidth_ = 0;
    uint32_t swapchainHeight_ = 0;

    // Swapchains (one per eye)
    XrSwapchain swapchains_[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    std::vector<uint32_t> swapchainImages_[2];

    // UI quad panel
    XrSwapchain uiSwapchain_ = XR_NULL_HANDLE;
    std::vector<uint32_t> uiSwapchainImages_;
    uint32_t uiWidth_ = 0, uiHeight_ = 0;
    bool uiVisible_ = false;
    bool createUiSwapchain(uint32_t width, uint32_t height);

    // Environment blend mode
    XrEnvironmentBlendMode blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;

    // EGL
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;

    // Time conversion
    PFN_xrVoidFunction convertTimeToXr_ = nullptr;
    XrTime lastPredictedTime_ = 0;

    // Light estimation
    bool lightEstimationSupported_ = false;
    int lightEstRetryCount_ = 0;
    XrLightEstimatorANDROID lightEstimator_ = XR_NULL_HANDLE;
    PFN_xrCreateLightEstimatorANDROID xrCreateLightEstimator_ = nullptr;
    PFN_xrDestroyLightEstimatorANDROID xrDestroyLightEstimator_ = nullptr;
    PFN_xrGetLightEstimateANDROID xrGetLightEstimate_ = nullptr;
    bool initLightEstimation();

    LightEstimate lastLightEstimate_;

    // Actions (controller input) — same structure as OpenXRInput
    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrPath handPaths_[2];
    XrAction thumbstickAction_ = XR_NULL_HANDLE;
    XrAction triggerAction_ = XR_NULL_HANDLE;
    XrAction squeezeAction_ = XR_NULL_HANDLE;
    XrAction aClickAction_ = XR_NULL_HANDLE;
    XrAction bClickAction_ = XR_NULL_HANDLE;
    XrAction xClickAction_ = XR_NULL_HANDLE;
    XrAction yClickAction_ = XR_NULL_HANDLE;
    XrAction menuClickAction_ = XR_NULL_HANDLE;
    XrAction thumbstickClickAction_ = XR_NULL_HANDLE;
    XrAction gripPoseAction_ = XR_NULL_HANDLE;
    XrAction aimPoseAction_ = XR_NULL_HANDLE;
    XrSpace handSpaces_[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    XrSpace aimSpaces_[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
};
