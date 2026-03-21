#pragma once

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <android/native_activity.h>
#include <android/log.h>

#define LOG_TAG "ChloeVR-OpenXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Controller state passed to Kotlin via JNI
// Layout: flat float array for efficient marshalling
struct ControllerState {
    // Thumbstick [0]=left, [1]=right
    float thumbstickX[2];     // indices 0,1
    float thumbstickY[2];     // indices 2,3
    // Triggers
    float trigger[2];         // indices 4,5  (index finger, 0.0-1.0)
    float squeeze[2];         // indices 6,7  (grip/middle finger, 0.0-1.0)
    // Buttons (1.0 = pressed, 0.0 = released)
    float aClick;             // index 8   (right hand)
    float bClick;             // index 9   (right hand)
    float xClick;             // index 10  (left hand)
    float yClick;             // index 11  (left hand)
    float menuClick;          // index 12  (left hand)
    float thumbstickClick[2]; // indices 13,14
    // Hand poses [0]=left, [1]=right
    float handPosX[2];        // indices 15,16
    float handPosY[2];        // indices 17,18
    float handPosZ[2];        // indices 19,20
    float handRotX[2];        // indices 21,22
    float handRotY[2];        // indices 23,24
    float handRotZ[2];        // indices 25,26
    float handRotW[2];        // indices 27,28
    float handValid[2];       // indices 29,30 (1.0 = valid pose)
    // Aim pose rotation (for laser pointer direction)
    float aimRotX[2];         // indices 31,32
    float aimRotY[2];         // indices 33,34
    float aimRotZ[2];         // indices 35,36
    float aimRotW[2];         // indices 37,38
    float aimValid[2];        // indices 39,40

    static constexpr int SIZE = 41;

    float* data() { return &thumbstickX[0]; }
};

class OpenXRInput {
public:
    bool init(JNIEnv* env, jobject activity);
    void shutdown();
    bool poll(ControllerState& state);
    bool isReady() const { return sessionReady_; }

private:
    bool createInstance(JNIEnv* env, jobject activity);
    bool getSystem();
    bool createSession();
    bool createActions();
    void handleSessionStateChange(XrSessionState state);

    // JNI global refs (must be freed in shutdown)
    JavaVM* javaVM_ = nullptr;
    jobject loaderGlobalRef_ = nullptr;
    jobject activityGlobalRef_ = nullptr;

    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace appSpace_ = XR_NULL_HANDLE;
    bool sessionReady_ = false;
    bool running_ = false;
    bool headless_ = false;
    // xrConvertTimespecTimeToTimeKHR function pointer (stored as generic, cast on use)
    PFN_xrVoidFunction convertTimeToXr_ = nullptr;

    // Minimal EGL context for graphics binding
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;
    bool initEGL();
    void shutdownEGL();

    // Action set and actions
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
