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
#include "xr_sensors.h"
#include "xr_spatial_anchors.h"

#define XRLOG_TAG "ChloeVR-XRRenderer"
#define XR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, XRLOG_TAG, __VA_ARGS__)
#define XR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, XRLOG_TAG, __VA_ARGS__)

struct EyeRenderInfo {
    uint32_t textureId;      // GL texture from swapchain
    uint32_t imageIndex;     // swapchain image index
    XrPosef pose;            // eye pose for layer submission
    XrFovf fov;              // eye fov for layer submission
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
    // When the key light is driven from the XR directional estimate ("Follow Room
    // Light"), request AMBIENT-kind SH so the explicit key light isn't counted
    // twice (TOTAL-kind SH already contains the directional contribution).
    // Re-engaging clears the unsupported-latch so a wrong latch (e.g. from a
    // degraded-tracking stretch) is recoverable from the UI cycle; a runtime
    // that truly lacks AMBIENT kind simply re-latches within ~30 polls.
    void setShKindAmbient(bool ambient) {
        if (ambient && !shKindAmbient_) {
            ambientShKindFails_ = 0;
            ambientShKindUnsupported_ = false;
        }
        shKindAmbient_ = ambient;
    }

    // ── Hand tracking ──
    struct HandJointData {
        bool active = false;
        struct Joint {
            float posX = 0, posY = 0, posZ = 0;
            float rotX = 0, rotY = 0, rotZ = 0, rotW = 1;
            float radius = 0;
            bool valid = false;
        };
        Joint joints[XR_HAND_JOINT_COUNT_EXT]; // 26 joints
        // JNI buffer: [0]=active, then 26×8 floats (pos xyz, rot xyzw, radius)
        static constexpr int SIZE = 1 + XR_HAND_JOINT_COUNT_EXT * 8; // 209
    };
    bool pollHandTracking(int hand, HandJointData& data); // hand: 0=left, 1=right

    // ── Eye tracking ──
    struct EyeTrackingData {
        bool valid = false;
        // Left eye
        bool leftValid = false;
        float leftPosX = 0, leftPosY = 0, leftPosZ = 0;
        float leftRotX = 0, leftRotY = 0, leftRotZ = 0, leftRotW = 1;
        // Right eye
        bool rightValid = false;
        float rightPosX = 0, rightPosY = 0, rightPosZ = 0;
        float rightRotX = 0, rightRotY = 0, rightRotZ = 0, rightRotW = 1;
        // Combined gaze
        bool combinedValid = false;
        float combPosX = 0, combPosY = 0, combPosZ = 0;
        float combRotX = 0, combRotY = 0, combRotZ = 0, combRotW = 1;
        // JNI: 1 + 3×(1 + 7) = 25 floats
        static constexpr int SIZE = 25;
    };
    bool pollEyeTracking(EyeTrackingData& data);

    // ── Face tracking ──
    struct FaceTrackingData {
        bool valid = false;
        float blendShapes[XR_FACE_BLEND_SHAPE_COUNT_ANDROID] = {}; // 68 weights
        // JNI: 1 + 68 = 69 floats
        static constexpr int SIZE = 1 + XR_FACE_BLEND_SHAPE_COUNT_ANDROID;
    };
    bool pollFaceTracking(FaceTrackingData& data);

    // ── Plane detection ──
    struct DetectedPlane {
        float posX = 0, posY = 0, posZ = 0;
        float rotX = 0, rotY = 0, rotZ = 0, rotW = 1;
        float extentX = 0, extentY = 0; // half-extents
        int label = 0; // 0=unknown, 1=wall, 2=floor, 3=ceiling, 4=table
        int planeType = 0; // 0=horiz_down, 1=horiz_up, 2=vertical, 3=arbitrary
        // Polygon vertices (2D in plane-local space)
        static constexpr int MAX_VERTICES = 32;
        float vertices[MAX_VERTICES * 2] = {}; // x,y pairs
        int vertexCount = 0;
    };
    struct PlaneData {
        bool valid = false;
        int planeCount = 0;
        static constexpr int MAX_PLANES = 64; // was 32
        DetectedPlane planes[MAX_PLANES];
        // JNI: 1 + 1 + 64×(10 + 1 + 1 + 32*2) = 1 + 1 + 64×76 = 4866 floats
        static constexpr int FLOATS_PER_PLANE = 10 + 1 + 1 + DetectedPlane::MAX_VERTICES * 2; // 76
        static constexpr int SIZE = 2 + MAX_PLANES * FLOATS_PER_PLANE;
    };
    bool pollPlanes(PlaneData& data);

    // ── Display refresh rate ──
    float getDisplayRefreshRate();
    int getAvailableRefreshRates(float* out, int maxCount);
    // Request a specific refresh rate (Hz). Passing 0 selects the highest supported
    // rate automatically. Called after session is READY; safe to call any time
    // thereafter (a no-op if unsupported or session not yet running).
    bool requestDisplayRefreshRate(float rate);
    float getRequestedDisplayRefreshRate() const { return requestedRefreshRate_; }

    // ── Performance metrics ──
    struct PerfMetrics {
        bool valid = false;
        float gpuFrameTimeMs = 0;
        float cpuFrameTimeMs = 0;
        float compositorDroppedFrames = 0;
        float displayRefreshRate = 0;
        static constexpr int SIZE = 5;
    };
    bool pollPerfMetrics(PerfMetrics& data);

    // ── Passthrough state ──
    int getPassthroughState(); // 0=disabled, 1=initializing, 2=enabled

    // ── Thermal / perf-settings notifications ──
    // Highest thermal notification level seen for each domain (CPU=0, GPU=1).
    // Value matches XrPerfSettingsNotificationLevelEXT (0=normal, 25=warning, 75=impaired).
    int getThermalNotificationLevel(int domain) const;
    // Consume the latest perf-settings event; returns true if one was available
    // and fills out domain + level. Non-allocating: one event per call.
    bool consumeThermalEvent(int* outDomain, int* outFromLevel, int* outToLevel);
    bool hasPerfSettings() const { return perfSettingsSupported_; }
    // Effective render scale (1.0 = full resolution). Thermal auto-downgrade may
    // reduce this via setRenderScale; Kotlin reads it per-frame to set viewport.
    float getRenderScale() const { return renderScale_; }
    void setRenderScale(float scale);

    // ── Extension availability ──
    bool hasHandTracking() const { return handTrackingSupported_; }
    bool hasEyeTracking() const { return eyeTrackingSupported_; }
    bool hasFaceTracking() const { return faceTrackingSupported_; }
    bool hasPlaneDetection() const { return trackablesSupported_; }
    bool hasRefreshRateControl() const { return refreshRateSupported_; }
    bool hasPerfMetrics() const { return perfMetricsSupported_; }
    bool hasPassthroughState() const { return passthroughStateSupported_; }
    bool hasFbPassthrough() const { return fbPassthroughSupported_; }
    bool hasFoveation() const { return foveationSupported_; }
    bool hasEyeTrackedFoveation() const { return eyeTrackedFoveationSupported_; }
    void setFoveationLevel(int level); // 0=off, 1=low, 2=medium, 3=high
    int getFoveationLevel() const { return foveationLevel_; }
    // AUTO refresh-rate policy: 72Hz (the Galaxy XR platform default), not
    // highest-enumerated — see the definition for the budget math.
    float autoRefreshTarget() const;

    // Explicitly push "no foveation" to both eye swapchains. The SCALED_BIN
    // create flag alone leaves the Samsung runtime's DEFAULT center-fixated
    // profile active (hardware-verified 2026-06-10: blocky binning on content
    // away from lens center while the app believed FFR was off), and
    // setFoveationLevel(0) can't clear it because the level is already 0.
    // Returns false while the session isn't ready yet (caller retries);
    // true once pushed, or when there's nothing to do / on hard failure.
    bool forceFoveationOff();
    // Toggle eye-tracked foveation on subsequent setFoveationLevel calls.
    // Returns the actual state (may be forced false if system lacks support).
    bool setEyeTrackedFoveation(bool enabled);
    bool isEyeTrackedFoveationEnabled() const { return eyeTrackedFoveation_; }

    // UI quad
    bool initUiQuad(uint32_t width, uint32_t height);
    uint32_t acquireUiImage();  // returns GL texture ID
    void releaseUiImage();
    void setUiVisible(bool visible) { uiVisible_ = visible; }
    bool isUiVisible() const { return uiVisible_; }
    void setUiPose(float px, float py, float pz, float rx, float ry, float rz, float rw) {
        uiPoseX_ = px; uiPoseY_ = py; uiPoseZ_ = pz;
        uiRotX_ = rx; uiRotY_ = ry; uiRotZ_ = rz; uiRotW_ = rw;
    }
    void setUiSize(float width, float height) { uiWorldW_ = width; uiWorldH_ = height; }

    // EGL handles for shared context
    EGLDisplay getEglDisplay() const { return eglDisplay_; }
    EGLContext getEglContext() const { return eglContext_; }
    EGLSurface getEglSurface() const { return eglSurface_; }
    uint32_t getSwapchainWidth() const { return swapchainWidth_; }
    uint32_t getSwapchainHeight() const { return swapchainHeight_; }
    bool isRunning() const { return running_; }
    bool isFocused() const { return focused_; }
    bool isVisible() const { return visible_; }
    bool isUsingStageSpace() const { return usingStageSpace_; }

    // Spatial anchors (persistent model positions across sessions)
    chloe::XrSpatialAnchorManager& anchors() { return spatialAnchors_; }
    const chloe::XrSpatialAnchorManager& anchors() const { return spatialAnchors_; }
    XrTime lastPredictedTime() const { return lastPredictedTime_; }

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
    bool focused_ = false;
    bool visible_ = false;
    bool usingStageSpace_ = false;

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
    bool uiHasContent_ = false;  // true after first successful acquire+release cycle
    bool createUiSwapchain(uint32_t width, uint32_t height);
    float uiPoseX_ = 0, uiPoseY_ = 1.6f, uiPoseZ_ = -1.2f;
    float uiRotX_ = 0, uiRotY_ = 0, uiRotZ_ = 0, uiRotW_ = 1;
    float uiWorldW_ = 0.6f, uiWorldH_ = 0.6f;

    // Environment blend mode
    XrEnvironmentBlendMode blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;

    // EGL
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;

    // JNI refs (must be freed in shutdown to prevent GlobalRef leak)
    JavaVM* javaVM_ = nullptr;
    jobject loaderGlobalRef_ = nullptr;
    jobject activityGlobalRef_ = nullptr;

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
    XrResult queryLightEstimate(XrTime time, XrSphericalHarmonicsKindANDROID kind,
        XrAmbientLightANDROID& amb, XrDirectionalLightANDROID& dir,
        XrSphericalHarmonicsANDROID& sh, XrLightEstimateANDROID& out);
    LightEstimate lastLightEstimate_;
    // Instance members (not function statics) so a recreated renderer/estimator
    // re-arms warm-up, cache aging, and diagnostics — same-process activity
    // relaunch destroys+recreates XrRenderer (see NOTES.md).
    int lightFrameCount_ = 0;   // polls since estimator creation (warm-up + cadence)
    int lightQueryFails_ = 0;   // consecutive failed real polls (ages out the cache)
    int lightErrCount_ = 0;     // error log throttle
    int lightLogCount_ = 0;     // 'XR Light' log throttle
    bool shKindAmbient_ = false;          // Kotlin-requested SH kind (Follow Room Light)
    int ambientShKindFails_ = 0;          // AMBIENT-invalid-but-TOTAL-valid polls since
                                          // the last AMBIENT success
    bool ambientShKindUnsupported_ = false; // latched after repeated failures — Samsung
                                            // runtime may not implement AMBIENT kind

    // ── Hand tracking ──
    bool handTrackingSupported_ = false;
    XrHandTrackerEXT handTrackers_[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    PFN_xrCreateHandTrackerEXT xrCreateHandTracker_ = nullptr;
    PFN_xrDestroyHandTrackerEXT xrDestroyHandTracker_ = nullptr;
    PFN_xrLocateHandJointsEXT xrLocateHandJoints_ = nullptr;
    bool initHandTracking();

    // ── Eye tracking ──
    bool eyeTrackingSupported_ = false;
    XrEyeTrackerANDROID eyeTracker_ = XR_NULL_HANDLE;
    PFN_xrCreateEyeTrackerANDROID xrCreateEyeTracker_ = nullptr;
    PFN_xrDestroyEyeTrackerANDROID xrDestroyEyeTracker_ = nullptr;
    PFN_xrGetEyesANDROID xrGetEyes_ = nullptr;
    bool initEyeTracking();

    // ── Face tracking ──
    bool faceTrackingSupported_ = false;
    XrFaceTrackerANDROID faceTracker_ = XR_NULL_HANDLE;
    PFN_xrCreateFaceTrackerANDROID xrCreateFaceTracker_ = nullptr;
    PFN_xrDestroyFaceTrackerANDROID xrDestroyFaceTracker_ = nullptr;
    PFN_xrGetFaceStateANDROID xrGetFaceState_ = nullptr;
    bool initFaceTracking();
    float faceBlendShapes_[XR_FACE_BLEND_SHAPE_COUNT_ANDROID] = {};

    // ── Plane tracking ──
    bool trackablesSupported_ = false;
    XrTrackableTrackerANDROID planeTracker_ = XR_NULL_HANDLE;
    PFN_xrCreateTrackableTrackerANDROID xrCreateTrackableTracker_ = nullptr;
    PFN_xrDestroyTrackableTrackerANDROID xrDestroyTrackableTracker_ = nullptr;
    PFN_xrGetAllTrackablesANDROID xrGetAllTrackables_ = nullptr;
    PFN_xrGetTrackablePlaneANDROID xrGetTrackablePlane_ = nullptr;
    bool initPlaneTracking();

    // ── Display refresh rate ──
    bool refreshRateSupported_ = false;
    PFN_xrEnumerateDisplayRefreshRatesFB xrEnumRefreshRates_ = nullptr;
    PFN_xrGetDisplayRefreshRateFB xrGetRefreshRate_ = nullptr;
    PFN_xrRequestDisplayRefreshRateFB xrRequestRefreshRate_ = nullptr;
    // Cached list of supported rates populated once the session is running.
    // Fixed-size to avoid per-frame allocations.
    static constexpr int MAX_REFRESH_RATES = 16;
    float availableRefreshRates_[MAX_REFRESH_RATES] = {};
    int availableRefreshRateCount_ = 0;
    float requestedRefreshRate_ = 0.0f; // last value passed to xrRequestDisplayRefreshRateFB
    float preferredRefreshRate_ = 0.0f; // 0 = auto/highest
    bool refreshRatePrimed_ = false;    // true after first xrRequest at session start
    void primeDisplayRefreshRate();     // called once session enters READY

    // ── Performance metrics ──
    bool perfMetricsSupported_ = false;
    PFN_xrEnumeratePerformanceMetricsCounterPathsANDROID xrEnumPerfPaths_ = nullptr;
    PFN_xrSetPerformanceMetricsStateANDROID xrSetPerfState_ = nullptr;
    PFN_xrQueryPerformanceMetricsCounterANDROID xrQueryPerfCounter_ = nullptr;
    bool initPerfMetrics();
    XrPath perfPathGpuTime_ = XR_NULL_PATH;
    XrPath perfPathCpuTime_ = XR_NULL_PATH;
    XrPath perfPathDropped_ = XR_NULL_PATH;

    // ── Foveated rendering ──
    bool foveationSupported_ = false;
    bool eyeTrackedFoveation_ = false;             // user preference (may be off even if supported)
    bool eyeTrackedFoveationSupported_ = false;    // system-capability flag (runtime + eye tracking available)
    bool eyeTrackedFoveationExtEnabled_ = false;   // XR_META_foveation_eye_tracked successfully enabled
    bool foveationSwapchainConfigured_ = false;
    int foveationLevel_ = 0; // 0=off, 1=low, 2=med, 3=high
    XrFoveationProfileFB foveationProfile_ = XR_NULL_HANDLE;
    PFN_xrCreateFoveationProfileFB xrCreateFoveationProfileFB_ = nullptr;
    PFN_xrDestroyFoveationProfileFB xrDestroyFoveationProfileFB_ = nullptr;
    PFN_xrUpdateSwapchainFB xrUpdateSwapchainFB_ = nullptr;
    PFN_xrGetFoveationEyeTrackedStateMETA xrGetFoveationEyeTrackedState_ = nullptr;

    // ── Performance metrics state ──
    bool metricsEnabled_ = false;

    // ── Passthrough camera state ──
    bool passthroughStateSupported_ = false;

    // ── FB passthrough (Quest mixed reality) ──
    bool fbPassthroughSupported_ = false;
    XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
    PFN_xrCreatePassthroughFB xrCreatePassthroughFB_ = nullptr;
    PFN_xrDestroyPassthroughFB xrDestroyPassthroughFB_ = nullptr;
    PFN_xrPassthroughStartFB xrPassthroughStartFB_ = nullptr;
    PFN_xrCreatePassthroughLayerFB xrCreatePassthroughLayerFB_ = nullptr;
    PFN_xrDestroyPassthroughLayerFB xrDestroyPassthroughLayerFB_ = nullptr;
    PFN_xrPassthroughLayerResumeFB xrPassthroughLayerResumeFB_ = nullptr;
    bool initFbPassthrough();

    // ── Thermal notifications via XR_EXT_performance_settings ──
    bool perfSettingsSupported_ = false;
    // Latest notification level by domain (0=CPU, 1=GPU). Uses the
    // XrPerfSettingsNotificationLevelEXT enum values directly.
    int thermalLevel_[2] = {0, 0};
    // Single-slot ring so Kotlin can consume one perf-settings event per call
    // without allocating. Populated in waitFrame() event drain.
    struct ThermalEventSlot {
        int domain = -1;   // -1 = empty
        int fromLevel = 0;
        int toLevel = 0;
    } thermalEvent_;

    // ── Render-scale factor (for thermal downgrade stage 3) ──
    // 1.0 = full swapchain resolution, < 1.0 = smaller viewport (renders to a
    // sub-rect of the swapchain; composition layer rect is reported accordingly).
    float renderScale_ = 1.0f;

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

    // ── Spatial anchor manager (XR_EXT_spatial_entity family) ──
    // Initialized after session becomes READY (inside handleSessionStateChange or init
    // post-session). Owns persistent anchor lifecycle and future polling.
    chloe::XrSpatialAnchorManager spatialAnchors_;
    bool spatialAnchorsInitAttempted_ = false;
};
