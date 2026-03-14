// XR_ANDROID_light_estimation — exact struct definitions from official docs
// https://developer.android.com/develop/xr/openxr/extensions/XR_ANDROID_light_estimation
#pragma once

#include <openxr/openxr.h>

#define XR_ANDROID_light_estimation 1
#define XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME "XR_ANDROID_light_estimation"

// Structure type constants (extension 701)
#define XR_TYPE_LIGHT_ESTIMATOR_CREATE_INFO_ANDROID        ((XrStructureType)1000700000)
#define XR_TYPE_LIGHT_ESTIMATE_GET_INFO_ANDROID             ((XrStructureType)1000700001)
#define XR_TYPE_LIGHT_ESTIMATE_ANDROID                      ((XrStructureType)1000700002)
// Corrected from Chromium xr_android.h — developer.android.com docs had these SWAPPED
#define XR_TYPE_DIRECTIONAL_LIGHT_ANDROID                   ((XrStructureType)1000700003)
#define XR_TYPE_SPHERICAL_HARMONICS_ANDROID                 ((XrStructureType)1000700004)
#define XR_TYPE_AMBIENT_LIGHT_ANDROID                       ((XrStructureType)1000700005)
#define XR_TYPE_SYSTEM_LIGHT_ESTIMATION_PROPERTIES_ANDROID  ((XrStructureType)1000700006)
// Note: XR_TYPE_ENVIRONMENT_LIGHTING_CUBEMAP_ANDROID is Revision 2 only, not in v1 runtime

XR_DEFINE_HANDLE(XrLightEstimatorANDROID)

typedef enum XrLightEstimateStateANDROID {
    XR_LIGHT_ESTIMATE_STATE_VALID_ANDROID = 0,
    XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID = 1,
    XR_LIGHT_ESTIMATE_STATE_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrLightEstimateStateANDROID;

typedef enum XrSphericalHarmonicsKindANDROID {
    XR_SPHERICAL_HARMONICS_KIND_TOTAL_ANDROID = 0,
    XR_SPHERICAL_HARMONICS_KIND_AMBIENT_ANDROID = 1,
    XR_SPHERICAL_HARMONICS_KIND_MAX_ENUM_ANDROID = 0x7FFFFFFF
} XrSphericalHarmonicsKindANDROID;

typedef struct XrSystemLightEstimationPropertiesANDROID {
    XrStructureType type;
    void* next;
    XrBool32 supportsLightEstimation;
} XrSystemLightEstimationPropertiesANDROID;

typedef struct XrLightEstimatorCreateInfoANDROID {
    XrStructureType type;
    void* next;
} XrLightEstimatorCreateInfoANDROID;

typedef struct XrLightEstimateGetInfoANDROID {
    XrStructureType type;
    void* next;
    XrSpace space;   // NOTE: space before time
    XrTime time;
} XrLightEstimateGetInfoANDROID;

typedef struct XrLightEstimateANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimateStateANDROID state;
    XrTime lastUpdatedTime;
} XrLightEstimateANDROID;

typedef struct XrAmbientLightANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimateStateANDROID state;
    XrVector3f intensity;       // RGB as XrVector3f
    XrVector3f colorCorrection; // RGB as XrVector3f
} XrAmbientLightANDROID;

typedef struct XrDirectionalLightANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimateStateANDROID state;
    XrVector3f intensity;   // RGB intensity
    XrVector3f direction;   // direction toward light
} XrDirectionalLightANDROID;

typedef struct XrSphericalHarmonicsANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimateStateANDROID state;
    XrSphericalHarmonicsKindANDROID kind;
    float coefficients[9][3]; // 9 L2 coefficients × 3 (RGB)
} XrSphericalHarmonicsANDROID;

// Function pointer types
typedef XrResult (XRAPI_PTR *PFN_xrCreateLightEstimatorANDROID)(
    XrSession session,
    XrLightEstimatorCreateInfoANDROID* createInfo,
    XrLightEstimatorANDROID* outHandle);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyLightEstimatorANDROID)(
    XrLightEstimatorANDROID estimator);

typedef XrResult (XRAPI_PTR *PFN_xrGetLightEstimateANDROID)(
    XrLightEstimatorANDROID estimator,
    const XrLightEstimateGetInfoANDROID* input,
    XrLightEstimateANDROID* output);
