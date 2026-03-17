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
// Spec: extension 457, base type 1000456000
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_EYE_TRACKING_EXTENSION_NAME "XR_ANDROID_eye_tracking"

#define XR_TYPE_EYE_TRACKER_CREATE_INFO_ANDROID   ((XrStructureType)1000456000)
#define XR_TYPE_EYES_GET_INFO_ANDROID             ((XrStructureType)1000456001)
#define XR_TYPE_EYES_ANDROID                      ((XrStructureType)1000456002)

#define XR_EYE_MAX_ANDROID 3  // left, right, combined

XR_DEFINE_HANDLE(XrEyeTrackerANDROID)

typedef enum XrEyeStateANDROID {
    XR_EYE_STATE_INVALID_ANDROID = 0,
    XR_EYE_STATE_GAZING_ANDROID = 1,
    XR_EYE_STATE_SHUT_ANDROID = 2,
    XR_EYE_STATE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrEyeStateANDROID;

typedef enum XrEyeTrackingModeANDROID {
    XR_EYE_TRACKING_MODE_NOT_TRACKING_ANDROID = 0,
    XR_EYE_TRACKING_MODE_COARSE_ANDROID = 1,
    XR_EYE_TRACKING_MODE_FINE_ANDROID = 2,
    XR_EYE_TRACKING_MODE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrEyeTrackingModeANDROID;

typedef struct XrEyeTrackerCreateInfoANDROID {
    XrStructureType type;
    const void* next;
} XrEyeTrackerCreateInfoANDROID;

typedef struct XrEyesGetInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTime time;
    XrSpace baseSpace;
} XrEyesGetInfoANDROID;

typedef struct XrEyeANDROID {
    XrEyeStateANDROID eyeState;
    XrPosef eyePose;
} XrEyeANDROID;

typedef struct XrEyesANDROID {
    XrStructureType type;
    void* next;
    XrEyeANDROID eyes[XR_EYE_MAX_ANDROID]; // [0]=left, [1]=right, [2]=combined
    XrEyeTrackingModeANDROID mode;
} XrEyesANDROID;

typedef XrResult (XRAPI_PTR *PFN_xrCreateEyeTrackerANDROID)(
    XrSession session,
    const XrEyeTrackerCreateInfoANDROID* createInfo,
    XrEyeTrackerANDROID* eyeTracker);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyEyeTrackerANDROID)(
    XrEyeTrackerANDROID eyeTracker);

// Note: the spec has separate coarse/fine functions, but the Samsung runtime
// may use a single xrGetEyesANDROID. Try loading both names.
typedef XrResult (XRAPI_PTR *PFN_xrGetEyesANDROID)(
    XrEyeTrackerANDROID eyeTracker,
    const XrEyesGetInfoANDROID* getInfo,
    XrEyesANDROID* eyes);


// ═══════════════════════════════════════════════════════════════════════
// XR_ANDROID_face_tracking — blend shape weights
// Spec: extension 459, base type 1000458000
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_FACE_TRACKING_EXTENSION_NAME "XR_ANDROID_face_tracking"

#define XR_TYPE_FACE_TRACKER_CREATE_INFO_ANDROID   ((XrStructureType)1000458000)
#define XR_TYPE_FACE_STATE_GET_INFO_ANDROID         ((XrStructureType)1000458001)
#define XR_TYPE_FACE_STATE_ANDROID                  ((XrStructureType)1000458002)

// Max blend shape count — spec uses dynamic count but Samsung runtime returns 68
#define XR_FACE_BLEND_SHAPE_COUNT_ANDROID 68

XR_DEFINE_HANDLE(XrFaceTrackerANDROID)

typedef enum XrFaceTrackingStateANDROID {
    XR_FACE_TRACKING_STATE_PAUSED_ANDROID = 0,
    XR_FACE_TRACKING_STATE_STOPPED_ANDROID = 1,
    XR_FACE_TRACKING_STATE_TRACKING_ANDROID = 2,
    XR_FACE_TRACKING_STATE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrFaceTrackingStateANDROID;

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
    uint32_t parametersCapacityInput;
    uint32_t parametersCountOutput;
    float* parameters;
    XrFaceTrackingStateANDROID faceTrackingState;
    XrTime sampleTime;
    XrBool32 isValid;
    uint32_t regionConfidencesCapacityInput;
    uint32_t regionConfidencesCountOutput;
    float* regionConfidences;
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
// Spec: extension 474, base type 1000473000
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_PASSTHROUGH_CAMERA_STATE_EXTENSION_NAME "XR_ANDROID_passthrough_camera_state"

#define XR_TYPE_PASSTHROUGH_CAMERA_STATE_ANDROID ((XrStructureType)1000473000)

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
// Spec: extension 456, base type 1000455000
// ═══════════════════════════════════════════════════════════════════════

#define XR_ANDROID_TRACKABLES_EXTENSION_NAME "XR_ANDROID_trackables"

#define XR_TYPE_TRACKABLE_GET_INFO_ANDROID              ((XrStructureType)1000455000)
#define XR_TYPE_TRACKABLE_PLANE_ANDROID                 ((XrStructureType)1000455003)
#define XR_TYPE_TRACKABLE_TRACKER_CREATE_INFO_ANDROID   ((XrStructureType)1000455004)

XR_DEFINE_ATOM(XrTrackableANDROID)

typedef enum XrTrackableTypeANDROID {
    XR_TRACKABLE_TYPE_NOT_VALID_ANDROID = 0,
    XR_TRACKABLE_TYPE_PLANE_ANDROID = 1,
    XR_TRACKABLE_TYPE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrTrackableTypeANDROID;

typedef enum XrTrackingStateANDROID {
    XR_TRACKING_STATE_PAUSED_ANDROID = 0,
    XR_TRACKING_STATE_STOPPED_ANDROID = 1,
    XR_TRACKING_STATE_TRACKING_ANDROID = 2,
    XR_TRACKING_STATE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrTrackingStateANDROID;

typedef enum XrPlaneTypeANDROID {
    XR_PLANE_TYPE_HORIZONTAL_DOWNWARD_FACING_ANDROID = 0,
    XR_PLANE_TYPE_HORIZONTAL_UPWARD_FACING_ANDROID = 1,
    XR_PLANE_TYPE_VERTICAL_ANDROID = 2,
    XR_PLANE_TYPE_ARBITRARY_ANDROID = 3,
    XR_PLANE_TYPE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrPlaneTypeANDROID;

typedef enum XrPlaneLabelANDROID {
    XR_PLANE_LABEL_UNKNOWN_ANDROID = 0,
    XR_PLANE_LABEL_WALL_ANDROID = 1,
    XR_PLANE_LABEL_FLOOR_ANDROID = 2,
    XR_PLANE_LABEL_CEILING_ANDROID = 3,
    XR_PLANE_LABEL_TABLE_ANDROID = 4,
    XR_PLANE_LABEL_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrPlaneLabelANDROID;

typedef struct XrTrackableTrackerCreateInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTrackableTypeANDROID trackableType;
} XrTrackableTrackerCreateInfoANDROID;

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
    XrTrackingStateANDROID trackingState;
    XrPosef centerPose;
    XrExtent2Df extents;
    XrPlaneTypeANDROID planeType;
    XrPlaneLabelANDROID planeLabel;
    XrTrackableANDROID subsumedByPlane;
    XrTime lastUpdatedTime;
    uint32_t vertexCapacityInput;
    uint32_t* vertexCountOutput;
    XrVector2f* vertices;
} XrTrackablePlaneANDROID;

XR_DEFINE_HANDLE(XrTrackableTrackerANDROID)

typedef XrResult (XRAPI_PTR *PFN_xrCreateTrackableTrackerANDROID)(
    XrSession session,
    const XrTrackableTrackerCreateInfoANDROID* createInfo,
    XrTrackableTrackerANDROID* tracker);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyTrackableTrackerANDROID)(
    XrTrackableTrackerANDROID tracker);

// Samsung Galaxy XR runtime: 4-parameter signature (no getInfo struct)
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

#define XR_TYPE_PERFORMANCE_METRICS_STATE_ANDROID         ((XrStructureType)1000465000)
#define XR_TYPE_PERFORMANCE_METRICS_COUNTER_ANDROID       ((XrStructureType)1000465001)

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
