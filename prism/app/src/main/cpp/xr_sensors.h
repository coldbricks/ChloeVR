// XR Sensor Extensions for Samsung Galaxy XR / Android XR
// Only defines types NOT already present in the OpenXR SDK headers.
// XR_EXT_hand_tracking and XR_FB_display_refresh_rate are in the SDK.
#pragma once

#include <openxr/openxr.h>

// ═══════════════════════════════════════════════════════════════════════
// XR_EXT_hand_tracking — already in SDK, just ensure constants available
// ═══════════════════════════════════════════════════════════════════════

// These are defined in openxr.h: XrHandTrackerEXT, XrHandJointLocationEXT, etc.
// Just ensure the extension name is accessible:
#ifndef XR_EXT_HAND_TRACKING_EXTENSION_NAME
#define XR_EXT_HAND_TRACKING_EXTENSION_NAME "XR_EXT_hand_tracking"
#endif


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_eye_tracking — gaze pose for each eye + combined
// Not in SDK as of openxr_loader 1.1.49
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_EYE_TRACKING_EXTENSION_NAME "XR_ANDROID_eye_tracking"

#define XR_TYPE_EYE_TRACKER_CREATE_INFO_ANDROID          ((XrStructureType)1000800000)
#define XR_TYPE_EYE_TRACKER_GET_INFO_ANDROID              ((XrStructureType)1000800001)
#define XR_TYPE_EYE_STATE_ANDROID                         ((XrStructureType)1000800002)
#define XR_TYPE_SYSTEM_EYE_TRACKING_PROPERTIES_ANDROID    ((XrStructureType)1000800003)

XR_DEFINE_HANDLE(XrEyeTrackerANDROID)

typedef enum XrEyeValidityANDROID {
    XR_EYE_VALIDITY_VALID_ANDROID = 0,
    XR_EYE_VALIDITY_INVALID_ANDROID = 1,
    XR_EYE_VALIDITY_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrEyeValidityANDROID;

typedef struct XrEyeTrackerCreateInfoANDROID {
    XrStructureType type;
    const void* next;
} XrEyeTrackerCreateInfoANDROID;

typedef struct XrEyeTrackerGetInfoANDROID {
    XrStructureType type;
    const void* next;
    XrSpace baseSpace;
    XrTime time;
} XrEyeTrackerGetInfoANDROID;

typedef struct XrEyeStateDataANDROID {
    XrStructureType type;
    void* next;
    XrEyeValidityANDROID leftEyeState;
    XrPosef leftEyePose;
    XrEyeValidityANDROID rightEyeState;
    XrPosef rightEyePose;
    XrEyeValidityANDROID combinedEyeState;
    XrPosef combinedEyePose;
} XrEyeStateDataANDROID;

typedef XrResult (XRAPI_PTR *PFN_xrCreateEyeTrackerANDROID)(
    XrSession session,
    const XrEyeTrackerCreateInfoANDROID* createInfo,
    XrEyeTrackerANDROID* eyeTracker);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyEyeTrackerANDROID)(
    XrEyeTrackerANDROID eyeTracker);

typedef XrResult (XRAPI_PTR *PFN_xrGetEyeStateANDROID)(
    XrEyeTrackerANDROID eyeTracker,
    const XrEyeTrackerGetInfoANDROID* getInfo,
    XrEyeStateDataANDROID* eyeState);


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_face_tracking — 68 blend shape weights
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_FACE_TRACKING_EXTENSION_NAME "XR_ANDROID_face_tracking"

#define XR_FACE_BLEND_SHAPE_COUNT_ANDROID 68

#define XR_TYPE_FACE_TRACKER_CREATE_INFO_ANDROID          ((XrStructureType)1000810000)
#define XR_TYPE_FACE_STATE_GET_INFO_ANDROID                ((XrStructureType)1000810001)
#define XR_TYPE_FACE_STATE_ANDROID                         ((XrStructureType)1000810002)
#define XR_TYPE_SYSTEM_FACE_TRACKING_PROPERTIES_ANDROID    ((XrStructureType)1000810003)

XR_DEFINE_HANDLE(XrFaceTrackerANDROID)

typedef enum XrFaceStateValidFlagsANDROID {
    XR_FACE_STATE_BLEND_SHAPES_VALID_BIT_ANDROID = 0x00000001,
    XR_FACE_STATE_VALID_FLAGS_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrFaceStateValidFlagsANDROID;

typedef struct XrFaceTrackerCreateInfoANDROID {
    XrStructureType type;
    const void* next;
} XrFaceTrackerCreateInfoANDROID;

typedef struct XrFaceStateGetInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTime time;
} XrFaceStateGetInfoANDROID;

typedef struct XrFaceStateANDROID {
    XrStructureType type;
    void* next;
    XrFaceStateValidFlagsANDROID validFlags;
    uint32_t blendShapeCount;
    float* blendShapeWeights;
    XrTime sampleTime;
} XrFaceStateANDROID;

typedef XrResult (XRAPI_PTR *PFN_xrCreateFaceTrackerANDROID)(
    XrSession session,
    const XrFaceTrackerCreateInfoANDROID* createInfo,
    XrFaceTrackerANDROID* faceTracker);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyFaceTrackerANDROID)(
    XrFaceTrackerANDROID faceTracker);

typedef XrResult (XRAPI_PTR *PFN_xrGetFaceStateANDROID)(
    XrFaceTrackerANDROID faceTracker,
    const XrFaceStateGetInfoANDROID* getInfo,
    XrFaceStateANDROID* faceState);


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_passthrough_camera_state
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_PASSTHROUGH_CAMERA_STATE_EXTENSION_NAME "XR_ANDROID_passthrough_camera_state"

typedef enum XrPassthroughCameraStateValueANDROID {
    XR_PASSTHROUGH_CAMERA_STATE_DISABLED_ANDROID = 0,
    XR_PASSTHROUGH_CAMERA_STATE_INITIALIZING_ANDROID = 1,
    XR_PASSTHROUGH_CAMERA_STATE_ENABLED_ANDROID = 2,
    XR_PASSTHROUGH_CAMERA_STATE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrPassthroughCameraStateValueANDROID;


// ═══════════════════════════════════════════════════════════════════════
// XR_FB_display_refresh_rate — already in SDK, just ensure name
// ═══════════════════════════════════════════════════════════════════════

#ifndef XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME
#define XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME "XR_FB_display_refresh_rate"
#endif


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_trackables — plane detection
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_TRACKABLES_EXTENSION_NAME "XR_ANDROID_trackables"

// Values from Khronos registry (NOT the Android docs — those were wrong)
#define XR_TYPE_TRACKABLE_GET_INFO_ANDROID              ((XrStructureType)1000455000)
#define XR_TYPE_TRACKABLE_PLANE_ANDROID                 ((XrStructureType)1000455003)
#define XR_TYPE_TRACKABLE_TRACKER_CREATE_INFO_ANDROID   ((XrStructureType)1000455004)
// Not in registry — try the next sequential value
#define XR_TYPE_ALL_TRACKABLES_GET_INFO_ANDROID         ((XrStructureType)1000455001)

XR_DEFINE_ATOM(XrTrackableANDROID)

typedef enum XrTrackableTypeANDROID {
    XR_TRACKABLE_TYPE_NOT_VALID_ANDROID = 0,
    XR_TRACKABLE_TYPE_PLANE_ANDROID = 1,
    XR_TRACKABLE_TYPE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrTrackableTypeANDROID;

typedef enum XrPlaneLabelANDROID {
    XR_PLANE_LABEL_UNKNOWN_ANDROID = 0,
    XR_PLANE_LABEL_FLOOR_ANDROID = 1,
    XR_PLANE_LABEL_CEILING_ANDROID = 2,
    XR_PLANE_LABEL_WALL_ANDROID = 3,
    XR_PLANE_LABEL_TABLE_ANDROID = 4,
    XR_PLANE_LABEL_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrPlaneLabelANDROID;

typedef struct XrTrackableTrackerCreateInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTrackableTypeANDROID trackableType;
} XrTrackableTrackerCreateInfoANDROID;

typedef struct XrAllTrackablesGetInfoANDROID {
    XrStructureType type;
    const void* next;
} XrAllTrackablesGetInfoANDROID;

typedef struct XrTrackableGetInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTrackableANDROID trackable;
    XrSpace baseSpace;
    XrTime time;
} XrTrackableGetInfoANDROID;

typedef struct XrTrackablePlaneANDROID {
    XrStructureType type;
    void* next;
    XrTrackableTypeANDROID trackableType;
    XrPosef centerPose;
    XrExtent2Df extents;
    XrPlaneLabelANDROID planeLabel;
    XrTrackableANDROID subsumedByPlane;
    uint32_t vertexCapacityInput;
    uint32_t vertexCountOutput;
    XrVector2f* vertices;
} XrTrackablePlaneANDROID;

XR_DEFINE_HANDLE(XrTrackableTrackerANDROID)

typedef XrResult (XRAPI_PTR *PFN_xrCreateTrackableTrackerANDROID)(
    XrSession session,
    const XrTrackableTrackerCreateInfoANDROID* createInfo,
    XrTrackableTrackerANDROID* tracker);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyTrackableTrackerANDROID)(
    XrTrackableTrackerANDROID tracker);

// Actual runtime signature (4 params, no getInfo struct)
typedef XrResult (XRAPI_PTR *PFN_xrGetAllTrackablesANDROID)(
    XrTrackableTrackerANDROID tracker,
    uint32_t trackableCapacityInput,
    uint32_t* trackableCountOutput,
    XrTrackableANDROID* trackables);

typedef XrResult (XRAPI_PTR *PFN_xrGetTrackablePlaneANDROID)(
    XrTrackableTrackerANDROID tracker,
    const XrTrackableGetInfoANDROID* getInfo,
    XrTrackablePlaneANDROID* plane);


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_performance_metrics
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_PERFORMANCE_METRICS_EXTENSION_NAME "XR_ANDROID_performance_metrics"

#define XR_TYPE_PERFORMANCE_METRICS_STATE_ANDROID         ((XrStructureType)1000710000)
#define XR_TYPE_PERFORMANCE_METRICS_COUNTER_ANDROID       ((XrStructureType)1000710001)

typedef enum XrPerformanceMetricsCounterUnitANDROID {
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_GENERIC_ANDROID = 0,
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_PERCENTAGE_ANDROID = 1,
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_MILLISECONDS_ANDROID = 2,
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_BYTES_ANDROID = 3,
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_HERTZ_ANDROID = 4,
    XR_PERFORMANCE_METRICS_COUNTER_UNIT_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrPerformanceMetricsCounterUnitANDROID;

typedef struct XrPerformanceMetricsStateANDROID {
    XrStructureType type;  // XR_TYPE_PERFORMANCE_METRICS_STATE_ANDROID
    void* next;
    XrBool32 enabled;
} XrPerformanceMetricsStateANDROID;

typedef struct XrPerformanceMetricsCounterANDROID {
    XrStructureType type;
    void* next;
    XrFlags64 counterFlags;
    XrPerformanceMetricsCounterUnitANDROID counterUnit;
    uint32_t uintValue;
    float floatValue;
} XrPerformanceMetricsCounterANDROID;

typedef XrResult (XRAPI_PTR *PFN_xrEnumeratePerformanceMetricsCounterPathsANDROID)(
    XrInstance instance,
    uint32_t counterPathCapacityInput,
    uint32_t* counterPathCountOutput,
    XrPath* counterPaths);

typedef XrResult (XRAPI_PTR *PFN_xrSetPerformanceMetricsStateANDROID)(
    XrSession session,
    const XrPerformanceMetricsStateANDROID* state);

typedef XrResult (XRAPI_PTR *PFN_xrQueryPerformanceMetricsCounterANDROID)(
    XrSession session,
    XrPath counterPath,
    XrPerformanceMetricsCounterANDROID* counter);
