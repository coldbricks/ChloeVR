// XR_ANDROID_light_estimation extension types
// Manually defined — not yet in stock OpenXR 1.1.49 headers
#pragma once

#include <openxr/openxr.h>

#define XR_ANDROID_light_estimation 1
#define XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME "XR_ANDROID_light_estimation"

// Structure types (extension number 701)
#define XR_TYPE_LIGHT_ESTIMATOR_CREATE_INFO_ANDROID       ((XrStructureType)1000700000)
#define XR_TYPE_LIGHT_ESTIMATE_GET_INFO_ANDROID           ((XrStructureType)1000700001)
#define XR_TYPE_LIGHT_ESTIMATE_ANDROID                    ((XrStructureType)1000700002)
#define XR_TYPE_DIRECTIONAL_LIGHT_ANDROID                 ((XrStructureType)1000700003)
#define XR_TYPE_AMBIENT_LIGHT_ANDROID                     ((XrStructureType)1000700004)
#define XR_TYPE_SPHERICAL_HARMONICS_ANDROID               ((XrStructureType)1000700005)
#define XR_TYPE_SYSTEM_LIGHT_ESTIMATION_PROPERTIES_ANDROID ((XrStructureType)1000700007)

XR_DEFINE_HANDLE(XrLightEstimatorANDROID)

typedef enum XrLightEstimationStateANDROID {
    XR_LIGHT_ESTIMATION_STATE_VALID_ANDROID = 0,
    XR_LIGHT_ESTIMATION_STATE_INVALID_ANDROID = 1,
} XrLightEstimationStateANDROID;

typedef enum XrSphericalHarmonicsBandANDROID {
    XR_SPHERICAL_HARMONICS_BAND_TOTAL_ANDROID = 0,
    XR_SPHERICAL_HARMONICS_BAND_AMBIENT_ANDROID = 1,
} XrSphericalHarmonicsBandANDROID;

typedef struct XrLightEstimatorCreateInfoANDROID {
    XrStructureType type;
    const void* next;
} XrLightEstimatorCreateInfoANDROID;

typedef struct XrLightEstimateGetInfoANDROID {
    XrStructureType type;
    const void* next;
    XrTime time;
    XrSpace baseSpace;
} XrLightEstimateGetInfoANDROID;

typedef struct XrLightEstimateANDROID {
    XrStructureType type;
    void* next;
} XrLightEstimateANDROID;

typedef struct XrAmbientLightANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimationStateANDROID state;
    XrColor3f intensity;
    XrColor3f colorCorrection;
} XrAmbientLightANDROID;

typedef struct XrDirectionalLightANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimationStateANDROID state;
    XrColor3f intensity;
    XrVector3f direction;
} XrDirectionalLightANDROID;

typedef struct XrSphericalHarmonicsANDROID {
    XrStructureType type;
    void* next;
    XrLightEstimationStateANDROID state;
    XrSphericalHarmonicsBandANDROID band;
    XrColor3f coefficients[9]; // L2: 9 RGB coefficients
} XrSphericalHarmonicsANDROID;

// Function pointer types
typedef XrResult (XRAPI_PTR *PFN_xrCreateLightEstimatorANDROID)(
    XrSession session,
    const XrLightEstimatorCreateInfoANDROID* createInfo,
    XrLightEstimatorANDROID* lightEstimator);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyLightEstimatorANDROID)(
    XrLightEstimatorANDROID lightEstimator);

typedef XrResult (XRAPI_PTR *PFN_xrGetLightEstimateANDROID)(
    XrLightEstimatorANDROID lightEstimator,
    const XrLightEstimateGetInfoANDROID* getInfo,
    XrLightEstimateANDROID* lightEstimate);
