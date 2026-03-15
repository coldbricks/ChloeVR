#include "xr_renderer.h"
#include <cstring>
#include <cmath>
#include <unistd.h>

#define XR_CHK(call)                                             \
    do {                                                         \
        XrResult _r = (call);                                    \
        if (XR_FAILED(_r)) {                                     \
            XR_LOGE("%s failed: %d", #call, (int)_r);            \
            return false;                                        \
        }                                                        \
    } while (0)

// Helper: create a typed OpenXR action
static bool makeAction(XrActionSet actionSet, XrAction& action,
                        const char* name, XrActionType type,
                        uint32_t numSub, const XrPath* subs) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = type;
    ci.countSubactionPaths = numSub;
    ci.subactionPaths = subs;
    strncpy(ci.actionName, name, XR_MAX_ACTION_NAME_SIZE);
    strncpy(ci.localizedActionName, name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE);
    XrResult r = xrCreateAction(actionSet, &ci, &action);
    if (XR_FAILED(r)) {
        XR_LOGE("xrCreateAction(%s) failed: %d", name, (int)r);
        return false;
    }
    return true;
}

// ── Initialization ──

bool XrRenderer::init(JNIEnv* env, jobject activity) {
    if (!createInstance(env, activity)) return false;
    if (!getSystem()) return false;
    if (!initEGL()) return false;
    if (!createSession()) return false;
    if (!createSwapchains()) return false;
    if (!createActions()) return false;
    // Light estimation init deferred to first poll (needs runtime permission first)

    // Initialize all sensor extensions (deferred/lazy where needed)
    if (handTrackingSupported_) initHandTracking();
    if (eyeTrackingSupported_) initEyeTracking();
    if (faceTrackingSupported_) initFaceTracking();
    if (trackablesSupported_) initPlaneTracking();
    if (perfMetricsSupported_) initPerfMetrics();

    // Load refresh rate function pointers
    if (refreshRateSupported_) {
        xrGetInstanceProcAddr(instance_, "xrEnumerateDisplayRefreshRatesFB",
            (PFN_xrVoidFunction*)&xrEnumRefreshRates_);
        xrGetInstanceProcAddr(instance_, "xrGetDisplayRefreshRateFB",
            (PFN_xrVoidFunction*)&xrGetRefreshRate_);
        xrGetInstanceProcAddr(instance_, "xrRequestDisplayRefreshRateFB",
            (PFN_xrVoidFunction*)&xrRequestRefreshRate_);
        XR_LOGI("Refresh rate functions loaded");
    }

    // Pump events to catch IDLE→READY transition during init
    XR_LOGI("Pumping initial events...");
    for (int i = 0; i < 600; i++) { // up to 30 seconds
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
            XR_LOGI("Init event type: %d", (int)event.type);
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                auto* stateEvent = (XrEventDataSessionStateChanged*)&event;
                handleSessionStateChange(stateEvent->state);
            }
            event = {XR_TYPE_EVENT_DATA_BUFFER};
        }
        if (sessionReady_) break;
        usleep(50000); // 50ms
    }
    XR_LOGI("XrRenderer fully initialized, sessionReady=%d", sessionReady_ ? 1 : 0);
    return true;
}

bool XrRenderer::createInstance(JNIEnv* env, jobject activity) {
    // Initialize the Android OpenXR loader
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                          (PFN_xrVoidFunction*)&initLoader);
    if (initLoader) {
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        JavaVM* vm;
        env->GetJavaVM(&vm);
        loaderInfo.applicationVM = vm;
        loaderInfo.applicationContext = env->NewGlobalRef(activity);
        initLoader((const XrLoaderInitInfoBaseHeaderKHR*)&loaderInfo);
    }

    // Enumerate extensions
    uint32_t extCount = 0;
    xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
    std::vector<XrExtensionProperties> availExts(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, availExts.data());

    XR_LOGI("Available OpenXR extensions (%u):", extCount);
    for (auto& ext : availExts) {
        XR_LOGI("  %s v%u", ext.extensionName, ext.extensionVersion);
    }

    // Required extensions for full rendering
    std::vector<const char*> extensions = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
        "XR_KHR_convert_timespec_time",
    };

    // Check availability of all optional extensions
    auto hasExt = [&](const char* name) -> bool {
        for (auto& ext : availExts) {
            if (strcmp(ext.extensionName, name) == 0) return true;
        }
        return false;
    };

    // Light estimation
    if (hasExt(XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME)) {
        extensions.push_back(XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME);
        lightEstimationSupported_ = true;
        XR_LOGI("  + Light estimation");
    }

    // Hand tracking
    if (hasExt(XR_EXT_HAND_TRACKING_EXTENSION_NAME)) {
        extensions.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        handTrackingSupported_ = true;
        XR_LOGI("  + Hand tracking");
    }

    // Eye tracking
    if (hasExt(XR_ANDROID_EYE_TRACKING_EXTENSION_NAME)) {
        extensions.push_back(XR_ANDROID_EYE_TRACKING_EXTENSION_NAME);
        eyeTrackingSupported_ = true;
        XR_LOGI("  + Eye tracking");
    }

    // Face tracking
    if (hasExt(XR_ANDROID_FACE_TRACKING_EXTENSION_NAME)) {
        extensions.push_back(XR_ANDROID_FACE_TRACKING_EXTENSION_NAME);
        faceTrackingSupported_ = true;
        XR_LOGI("  + Face tracking");
    }

    // Trackables (plane detection)
    if (hasExt(XR_ANDROID_TRACKABLES_EXTENSION_NAME)) {
        extensions.push_back(XR_ANDROID_TRACKABLES_EXTENSION_NAME);
        trackablesSupported_ = true;
        XR_LOGI("  + Trackables (planes)");
    }

    // Display refresh rate
    if (hasExt(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)) {
        extensions.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
        refreshRateSupported_ = true;
        XR_LOGI("  + Display refresh rate");
    }

    // Performance metrics
    if (hasExt(XR_ANDROID_PERFORMANCE_METRICS_EXTENSION_NAME)) {
        extensions.push_back(XR_ANDROID_PERFORMANCE_METRICS_EXTENSION_NAME);
        perfMetricsSupported_ = true;
        XR_LOGI("  + Performance metrics");
    }

    // Foveated rendering
    if (hasExt("XR_FB_foveation") && hasExt("XR_FB_foveation_configuration") && hasExt("XR_FB_swapchain_update_state")) {
        extensions.push_back("XR_FB_foveation");
        extensions.push_back("XR_FB_foveation_configuration");
        extensions.push_back("XR_FB_swapchain_update_state");
        foveationSupported_ = true;
        XR_LOGI("  + Foveated rendering (FB)");
        // Don't request eye-tracked for now — may cause issues
    }

    // Passthrough camera state
    if (hasExt(XR_ANDROID_PASSTHROUGH_CAMERA_STATE_EXTENSION_NAME)) {
        passthroughStateSupported_ = true;
        extensions.push_back(XR_ANDROID_PASSTHROUGH_CAMERA_STATE_EXTENSION_NAME);
        XR_LOGI("  + Passthrough camera state");
    }

    // Create instance
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    JavaVM* vm;
    env->GetJavaVM(&vm);
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = env->NewGlobalRef(activity);

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.next = &androidInfo;
    createInfo.enabledExtensionCount = (uint32_t)extensions.size();
    createInfo.enabledExtensionNames = extensions.data();
    strncpy(createInfo.applicationInfo.applicationName, "ChloeVR-Renderer",
            XR_MAX_APPLICATION_NAME_SIZE);
    createInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;

    XR_CHK(xrCreateInstance(&createInfo, &instance_));

    // Get time conversion function
    xrGetInstanceProcAddr(instance_, "xrConvertTimespecTimeToTimeKHR",
                          (PFN_xrVoidFunction*)&convertTimeToXr_);

    // Get foveation function pointers
    if (foveationSupported_) {
        xrGetInstanceProcAddr(instance_, "xrCreateFoveationProfileFB", (PFN_xrVoidFunction*)&xrCreateFoveationProfileFB_);
        xrGetInstanceProcAddr(instance_, "xrDestroyFoveationProfileFB", (PFN_xrVoidFunction*)&xrDestroyFoveationProfileFB_);
        xrGetInstanceProcAddr(instance_, "xrUpdateSwapchainFB", (PFN_xrVoidFunction*)&xrUpdateSwapchainFB_);
    }

    XR_LOGI("OpenXR rendering instance created");
    return true;
}

bool XrRenderer::getSystem() {
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR_CHK(xrGetSystem(instance_, &systemInfo, &systemId_));
    XR_LOGI("OpenXR system acquired: %llu", (unsigned long long)systemId_);

    // Check environment blend modes
    uint32_t blendCount = 0;
    xrEnumerateEnvironmentBlendModes(instance_, systemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &blendCount, nullptr);
    std::vector<XrEnvironmentBlendMode> blendModes(blendCount);
    xrEnumerateEnvironmentBlendModes(instance_, systemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, blendCount, &blendCount,
        blendModes.data());

    XR_LOGI("Environment blend modes (%u):", blendCount);
    blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE; // default
    for (auto mode : blendModes) {
        const char* name = "UNKNOWN";
        if (mode == XR_ENVIRONMENT_BLEND_MODE_OPAQUE) name = "OPAQUE";
        else if (mode == XR_ENVIRONMENT_BLEND_MODE_ADDITIVE) name = "ADDITIVE";
        else if (mode == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) name = "ALPHA_BLEND";
        XR_LOGI("  %s (%d)", name, (int)mode);

        // Prefer ALPHA_BLEND for passthrough
        if (mode == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) {
            blendMode_ = mode;
        }
    }
    XR_LOGI("Selected blend mode: %d (ALPHA_BLEND=%d)",
             (int)blendMode_, (int)XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND);

    return true;
}

bool XrRenderer::initEGL() {
    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        XR_LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(eglDisplay_, nullptr, nullptr)) {
        XR_LOGE("eglInitialize failed");
        return false;
    }

    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) || numConfigs < 1) {
        XR_LOGE("eglChooseConfig failed");
        return false;
    }

    EGLint contextAttribs[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        XR_LOGE("eglCreateContext failed");
        return false;
    }

    EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    eglSurface_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pbufferAttribs);
    if (eglSurface_ == EGL_NO_SURFACE) {
        XR_LOGE("eglCreatePbufferSurface failed");
        return false;
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        XR_LOGE("eglMakeCurrent failed");
        return false;
    }

    XR_LOGI("EGL context created for OpenXR rendering");
    return true;
}

void XrRenderer::shutdownEGL() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) eglDestroySurface(eglDisplay_, eglSurface_);
        if (eglContext_ != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay_, eglContext_);
        eglTerminate(eglDisplay_);
    }
    eglDisplay_ = EGL_NO_DISPLAY;
    eglContext_ = EGL_NO_CONTEXT;
    eglSurface_ = EGL_NO_SURFACE;
}

bool XrRenderer::createSession() {
    // GLES graphics binding (always — we're rendering, not headless)
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGLESReqs = nullptr;
    xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                          (PFN_xrVoidFunction*)&getGLESReqs);
    if (getGLESReqs) {
        XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        getGLESReqs(instance_, systemId_, &reqs);
        XR_LOGI("GLES requirements: min=%lx max=%lx", (unsigned long)reqs.minApiVersionSupported,
                 (unsigned long)reqs.maxApiVersionSupported);
    }

    XrGraphicsBindingOpenGLESAndroidKHR gfxBinding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    gfxBinding.display = eglDisplay_;
    gfxBinding.config = eglConfig_;
    gfxBinding.context = eglContext_;

    XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionInfo.systemId = systemId_;
    sessionInfo.next = &gfxBinding;

    XR_CHK(xrCreateSession(instance_, &sessionInfo, &session_));
    XR_LOGI("OpenXR rendering session created");

    // Create LOCAL reference space (origin at floor level on Galaxy XR)
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    XR_CHK(xrCreateReferenceSpace(session_, &spaceInfo, &appSpace_));

    return true;
}

bool XrRenderer::createSwapchains() {
    // Get view configuration for stereo
    uint32_t viewCount = 0;
    XR_CHK(xrEnumerateViewConfigurationViews(instance_, systemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr));

    if (viewCount != 2) {
        XR_LOGE("Expected 2 stereo views, got %u", viewCount);
        return false;
    }

    viewConfigs_[0] = {XR_TYPE_VIEW_CONFIGURATION_VIEW};
    viewConfigs_[1] = {XR_TYPE_VIEW_CONFIGURATION_VIEW};
    XR_CHK(xrEnumerateViewConfigurationViews(instance_, systemId_,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &viewCount, viewConfigs_));

    swapchainWidth_ = viewConfigs_[0].recommendedImageRectWidth;
    swapchainHeight_ = viewConfigs_[0].recommendedImageRectHeight;
    XR_LOGI("Recommended resolution per eye: %ux%u (max %ux%u, samples=%u)",
             swapchainWidth_, swapchainHeight_,
             viewConfigs_[0].maxImageRectWidth, viewConfigs_[0].maxImageRectHeight,
             viewConfigs_[0].recommendedSwapchainSampleCount);

    // Enumerate supported swapchain formats
    uint32_t fmtCount = 0;
    xrEnumerateSwapchainFormats(session_, 0, &fmtCount, nullptr);
    std::vector<int64_t> formats(fmtCount);
    xrEnumerateSwapchainFormats(session_, fmtCount, &fmtCount, formats.data());

    XR_LOGI("Swapchain formats (%u):", fmtCount);
    int64_t selectedFormat = GL_RGBA8;
    for (auto fmt : formats) {
        XR_LOGI("  0x%llx", (unsigned long long)fmt);
        // Prefer SRGB8_ALPHA8 for correct color space
        if (fmt == GL_SRGB8_ALPHA8) selectedFormat = fmt;
    }
    // If no sRGB available, try GL_RGBA8
    if (selectedFormat == GL_RGBA8) {
        for (auto fmt : formats) {
            if (fmt == GL_RGBA8) { selectedFormat = fmt; break; }
        }
    }
    XR_LOGI("Selected swapchain format: 0x%llx", (unsigned long long)selectedFormat);

    // Create one swapchain per eye
    for (int eye = 0; eye < 2; eye++) {
        XrSwapchainCreateInfo swapchainCI{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        swapchainCI.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                                 XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        swapchainCI.format = selectedFormat;
        swapchainCI.width = swapchainWidth_;
        swapchainCI.height = swapchainHeight_;
        swapchainCI.sampleCount = 1;
        swapchainCI.faceCount = 1;
        swapchainCI.arraySize = 1;
        swapchainCI.mipCount = 1;

        XR_CHK(xrCreateSwapchain(session_, &swapchainCI, &swapchains_[eye]));

        // Enumerate swapchain images (GL textures)
        uint32_t imgCount = 0;
        xrEnumerateSwapchainImages(swapchains_[eye], 0, &imgCount, nullptr);
        std::vector<XrSwapchainImageOpenGLESKHR> images(
            imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(swapchains_[eye], imgCount, &imgCount,
            (XrSwapchainImageBaseHeader*)images.data());

        swapchainImages_[eye].resize(imgCount);
        for (uint32_t i = 0; i < imgCount; i++) {
            swapchainImages_[eye][i] = images[i].image;
            XR_LOGI("Eye %d swapchain image %u: GL texture %u", eye, i, images[i].image);
        }
    }

    XR_LOGI("Swapchains created: %ux%u, %zu images per eye",
             swapchainWidth_, swapchainHeight_, swapchainImages_[0].size());
    return true;
}

bool XrRenderer::createActions() {
    // Create action set
    XrActionSetCreateInfo asci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strncpy(asci.actionSetName, "renderer-input", XR_MAX_ACTION_SET_NAME_SIZE);
    strncpy(asci.localizedActionSetName, "Renderer Input",
            XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE);
    asci.priority = 0;
    XR_CHK(xrCreateActionSet(instance_, &asci, &actionSet_));

    xrStringToPath(instance_, "/user/hand/left", &handPaths_[0]);
    xrStringToPath(instance_, "/user/hand/right", &handPaths_[1]);

    // Create all actions (same as OpenXRInput)
    makeAction(actionSet_, thumbstickAction_, "thumbstick",
               XR_ACTION_TYPE_VECTOR2F_INPUT, 2, handPaths_);
    makeAction(actionSet_, triggerAction_, "trigger",
               XR_ACTION_TYPE_FLOAT_INPUT, 2, handPaths_);
    makeAction(actionSet_, squeezeAction_, "squeeze",
               XR_ACTION_TYPE_FLOAT_INPUT, 2, handPaths_);
    makeAction(actionSet_, thumbstickClickAction_, "thumbstick-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, aClickAction_, "a-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, bClickAction_, "b-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, xClickAction_, "x-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, yClickAction_, "y-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, menuClickAction_, "menu-click",
               XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    makeAction(actionSet_, gripPoseAction_, "grip-pose",
               XR_ACTION_TYPE_POSE_INPUT, 2, handPaths_);
    makeAction(actionSet_, aimPoseAction_, "aim-pose",
               XR_ACTION_TYPE_POSE_INPUT, 2, handPaths_);

    // Suggest bindings for Oculus Touch Controller (Galaxy XR profile)
    auto path = [this](const char* p) -> XrPath {
        XrPath xrPath;
        xrStringToPath(instance_, p, &xrPath);
        return xrPath;
    };

    std::vector<XrActionSuggestedBinding> bindings = {
        {thumbstickAction_, path("/user/hand/left/input/thumbstick")},
        {thumbstickAction_, path("/user/hand/right/input/thumbstick")},
        {thumbstickClickAction_, path("/user/hand/left/input/thumbstick/click")},
        {thumbstickClickAction_, path("/user/hand/right/input/thumbstick/click")},
        {triggerAction_, path("/user/hand/left/input/trigger/value")},
        {triggerAction_, path("/user/hand/right/input/trigger/value")},
        {squeezeAction_, path("/user/hand/left/input/squeeze/value")},
        {squeezeAction_, path("/user/hand/right/input/squeeze/value")},
        {aClickAction_, path("/user/hand/right/input/a/click")},
        {bClickAction_, path("/user/hand/right/input/b/click")},
        {xClickAction_, path("/user/hand/left/input/x/click")},
        {yClickAction_, path("/user/hand/left/input/y/click")},
        {menuClickAction_, path("/user/hand/left/input/menu/click")},
        {gripPoseAction_, path("/user/hand/left/input/grip/pose")},
        {gripPoseAction_, path("/user/hand/right/input/grip/pose")},
        {aimPoseAction_, path("/user/hand/left/input/aim/pose")},
        {aimPoseAction_, path("/user/hand/right/input/aim/pose")},
    };

    XrInteractionProfileSuggestedBinding suggested{
        XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggested.interactionProfile =
        path("/interaction_profiles/oculus/touch_controller");
    suggested.suggestedBindings = bindings.data();
    suggested.countSuggestedBindings = (uint32_t)bindings.size();
    XR_CHK(xrSuggestInteractionProfileBindings(instance_, &suggested));

    // Attach action set
    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet_;
    XR_CHK(xrAttachSessionActionSets(session_, &attachInfo));

    // Create action spaces for hand poses
    for (int hand = 0; hand < 2; hand++) {
        XrActionSpaceCreateInfo spaceCI{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        spaceCI.action = gripPoseAction_;
        spaceCI.subactionPath = handPaths_[hand];
        spaceCI.poseInActionSpace = {{0, 0, 0, 1}, {0, 0, 0}};
        XR_CHK(xrCreateActionSpace(session_, &spaceCI, &handSpaces_[hand]));

        spaceCI.action = aimPoseAction_;
        XR_CHK(xrCreateActionSpace(session_, &spaceCI, &aimSpaces_[hand]));
    }

    XR_LOGI("Actions created and attached");
    return true;
}

// ── Session State ──

void XrRenderer::handleSessionStateChange(XrSessionState newState) {
    XR_LOGI("=== Session state change: %d (IDLE=1,READY=2,SYNC=3,VIS=4,FOCUS=5,STOP=6)", (int)newState);
    switch (newState) {
        case XR_SESSION_STATE_IDLE:
            XR_LOGI("Session IDLE — waiting for READY");
            break;
        case XR_SESSION_STATE_READY: {
            XR_LOGI("Session READY — calling xrBeginSession");
            XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
            beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            XrResult r = xrBeginSession(session_, &beginInfo);
            XR_LOGI("xrBeginSession result: %d", (int)r);
            sessionReady_ = true;
            running_ = true;
            XR_LOGI("Session begun and ready for frames");
            break;
        }
        case XR_SESSION_STATE_STOPPING:
            xrEndSession(session_);
            sessionReady_ = false;
            running_ = false;
            break;
        case XR_SESSION_STATE_EXITING:
        case XR_SESSION_STATE_LOSS_PENDING:
            running_ = false;
            sessionReady_ = false;
            break;
        default:
            break;
    }
}

// ── Frame Loop ──

bool XrRenderer::waitFrame(FrameData& frame) {
    if (!session_) return false;

    memset(&frame, 0, sizeof(frame));

    // Process events
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
        XR_LOGI("Event type: %d", (int)event.type);
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* stateEvent = (XrEventDataSessionStateChanged*)&event;
            XR_LOGI("Session state changed to: %d", (int)stateEvent->state);
            handleSessionStateChange(stateEvent->state);
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }

    if (!sessionReady_) {
        // Not ready yet — keep polling events, don't burn CPU
        usleep(5000); // 5ms
        return false;
    }

    // Wait for the next frame
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrResult r = xrWaitFrame(session_, &waitInfo, &frameState);
    if (XR_FAILED(r)) {
        XR_LOGE("xrWaitFrame failed: %d", (int)r);
        return false;
    }

    frame.predictedDisplayTime = frameState.predictedDisplayTime;
    lastPredictedTime_ = frameState.predictedDisplayTime;
    frame.shouldRender = frameState.shouldRender;

    // Begin frame
    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    r = xrBeginFrame(session_, &beginInfo);
    if (XR_FAILED(r)) {
        XR_LOGE("xrBeginFrame failed: %d", (int)r);
        return false;
    }

    if (!frame.shouldRender) return true; // still need to call submitFrame

    // Locate views (eye poses + projections)
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    XrViewState viewState{XR_TYPE_VIEW_STATE};
    XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = frameState.predictedDisplayTime;
    locateInfo.space = appSpace_;
    uint32_t viewCount = 0;
    r = xrLocateViews(session_, &locateInfo, &viewState, 2, &viewCount, views);
    if (XR_FAILED(r) || viewCount != 2) {
        XR_LOGE("xrLocateViews failed: %d, count=%u", (int)r, viewCount);
        return true; // still submit empty frame
    }

    // Acquire swapchain images and fill frame data
    for (int eye = 0; eye < 2; eye++) {
        XrSwapchainImageAcquireInfo acqInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        uint32_t imageIndex = 0;
        r = xrAcquireSwapchainImage(swapchains_[eye], &acqInfo, &imageIndex);
        if (XR_FAILED(r)) {
            XR_LOGE("xrAcquireSwapchainImage eye %d failed: %d", eye, (int)r);
            return true;
        }

        XrSwapchainImageWaitInfo waitImageInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        waitImageInfo.timeout = XR_INFINITE_DURATION;
        r = xrWaitSwapchainImage(swapchains_[eye], &waitImageInfo);
        if (XR_FAILED(r)) {
            XR_LOGE("xrWaitSwapchainImage eye %d failed: %d", eye, (int)r);
            return true;
        }

        frame.eyes[eye].textureId = swapchainImages_[eye][imageIndex];
        frame.eyes[eye].imageIndex = imageIndex;
        frame.eyes[eye].width = swapchainWidth_;
        frame.eyes[eye].height = swapchainHeight_;

        // Convert XR pose/fov to matrices
        xrPoseToViewMatrix(views[eye].pose, frame.eyes[eye].viewMatrix);
        xrFovToProjection(views[eye].fov, 0.05f, 100.0f, frame.eyes[eye].projection);
    }

    return true;
}

void XrRenderer::submitFrame(const FrameData& frame) {
    if (!session_ || !sessionReady_) return;

    // Release swapchain images
    if (frame.shouldRender) {
        for (int eye = 0; eye < 2; eye++) {
            XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(swapchains_[eye], &releaseInfo);
        }
    }

    // Build composition layers
    XrCompositionLayerProjectionView projViews[2] = {};
    XrCompositionLayerProjection projLayer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};

    if (frame.shouldRender) {
        // Locate views again for the sub-image info
        XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        XrViewState viewState{XR_TYPE_VIEW_STATE};
        XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
        locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locateInfo.displayTime = frame.predictedDisplayTime;
        locateInfo.space = appSpace_;
        uint32_t viewCount = 0;
        xrLocateViews(session_, &locateInfo, &viewState, 2, &viewCount, views);

        for (int eye = 0; eye < 2; eye++) {
            projViews[eye] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
            projViews[eye].pose = views[eye].pose;
            projViews[eye].fov = views[eye].fov;
            projViews[eye].subImage.swapchain = swapchains_[eye];
            projViews[eye].subImage.imageRect.offset = {0, 0};
            projViews[eye].subImage.imageRect.extent = {
                (int32_t)swapchainWidth_, (int32_t)swapchainHeight_};
            projViews[eye].subImage.imageArrayIndex = 0;
        }

        projLayer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        projLayer.space = appSpace_;
        projLayer.viewCount = 2;
        projLayer.views = projViews;
    }

    // UI quad composition layer (floating panel in front of user)
    XrCompositionLayerQuad quadLayer{XR_TYPE_COMPOSITION_LAYER_QUAD};
    if (frame.shouldRender && uiVisible_ && uiSwapchain_ != XR_NULL_HANDLE) {
        quadLayer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        quadLayer.space = appSpace_;
        quadLayer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        quadLayer.subImage.swapchain = uiSwapchain_;
        quadLayer.subImage.imageRect.offset = {0, 0};
        quadLayer.subImage.imageRect.extent = {(int32_t)uiWidth_, (int32_t)uiHeight_};
        quadLayer.subImage.imageArrayIndex = 0;
        // Position: set from Kotlin via setUiPose()
        quadLayer.pose = {{uiRotX_, uiRotY_, uiRotZ_, uiRotW_},
                          {uiPoseX_, uiPoseY_, uiPoseZ_}};
        quadLayer.size = {uiWorldW_, uiWorldH_};
    }

    const XrCompositionLayerBaseHeader* layers[2] = {};
    uint32_t layerCount = 0;
    if (frame.shouldRender) {
        layers[layerCount++] = (const XrCompositionLayerBaseHeader*)&projLayer;
        if (uiVisible_ && uiSwapchain_ != XR_NULL_HANDLE) {
            layers[layerCount++] = (const XrCompositionLayerBaseHeader*)&quadLayer;
        }
    }

    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frame.predictedDisplayTime;
    endInfo.environmentBlendMode = blendMode_;
    endInfo.layerCount = layerCount;
    endInfo.layers = layers;

    XrResult r = xrEndFrame(session_, &endInfo);
    if (XR_FAILED(r)) {
        XR_LOGE("xrEndFrame failed: %d", (int)r);
    }
}

// ── Input Polling ──

bool XrRenderer::pollInput(ControllerState& state) {
    if (!session_ || !sessionReady_) return false;

    memset(&state, 0, sizeof(state));

    // Sync actions
    XrActiveActionSet activeAS{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeAS;
    XrResult r = xrSyncActions(session_, &syncInfo);
    if (XR_FAILED(r)) return false;

    XrActionStateGetInfo getInfo{XR_TYPE_ACTION_STATE_GET_INFO};

    // Thumbstick
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = thumbstickAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateVector2f v2{XR_TYPE_ACTION_STATE_VECTOR2F};
        if (XR_SUCCEEDED(xrGetActionStateVector2f(session_, &getInfo, &v2)) && v2.isActive) {
            state.thumbstickX[hand] = v2.currentState.x;
            state.thumbstickY[hand] = v2.currentState.y;
        }
    }
    // Trigger
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = triggerAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
        if (XR_SUCCEEDED(xrGetActionStateFloat(session_, &getInfo, &f)) && f.isActive)
            state.trigger[hand] = f.currentState;
    }
    // Squeeze
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = squeezeAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
        if (XR_SUCCEEDED(xrGetActionStateFloat(session_, &getInfo, &f)) && f.isActive)
            state.squeeze[hand] = f.currentState;
    }
    // Thumbstick click
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = thumbstickClickAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &b)) && b.isActive)
            state.thumbstickClick[hand] = b.currentState ? 1.0f : 0.0f;
    }
    // Buttons
    XrActionStateBoolean ab{XR_TYPE_ACTION_STATE_BOOLEAN};
    getInfo.action = aClickAction_;
    getInfo.subactionPath = handPaths_[1];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.aClick = ab.currentState ? 1.0f : 0.0f;
    getInfo.action = bClickAction_;
    getInfo.subactionPath = handPaths_[1];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.bClick = ab.currentState ? 1.0f : 0.0f;
    getInfo.action = xClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.xClick = ab.currentState ? 1.0f : 0.0f;
    getInfo.action = yClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.yClick = ab.currentState ? 1.0f : 0.0f;
    getInfo.action = menuClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.menuClick = ab.currentState ? 1.0f : 0.0f;

    // Hand poses
    XrTime now = 0;
    {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        if (convertTimeToXr_) {
            typedef XrResult (*ConvertFn)(XrInstance, const struct timespec*, XrTime*);
            ((ConvertFn)convertTimeToXr_)(instance_, &ts, &now);
        } else {
            now = (XrTime)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        }
    }

    for (int hand = 0; hand < 2; hand++) {
        if (handSpaces_[hand] == XR_NULL_HANDLE) continue;
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        if (XR_SUCCEEDED(xrLocateSpace(handSpaces_[hand], appSpace_, now, &loc))) {
            bool posValid = (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
            bool oriValid = (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0;
            state.handValid[hand] = (posValid && oriValid) ? 1.0f : 0.0f;
            if (posValid) {
                state.handPosX[hand] = loc.pose.position.x;
                state.handPosY[hand] = loc.pose.position.y;
                state.handPosZ[hand] = loc.pose.position.z;
            }
            if (oriValid) {
                state.handRotX[hand] = loc.pose.orientation.x;
                state.handRotY[hand] = loc.pose.orientation.y;
                state.handRotZ[hand] = loc.pose.orientation.z;
                state.handRotW[hand] = loc.pose.orientation.w;
            }
        }
    }
    for (int hand = 0; hand < 2; hand++) {
        if (aimSpaces_[hand] == XR_NULL_HANDLE) continue;
        XrSpaceLocation aimLoc{XR_TYPE_SPACE_LOCATION};
        if (XR_SUCCEEDED(xrLocateSpace(aimSpaces_[hand], appSpace_, now, &aimLoc))) {
            bool oriValid = (aimLoc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0;
            state.aimValid[hand] = oriValid ? 1.0f : 0.0f;
            if (oriValid) {
                state.aimRotX[hand] = aimLoc.pose.orientation.x;
                state.aimRotY[hand] = aimLoc.pose.orientation.y;
                state.aimRotZ[hand] = aimLoc.pose.orientation.z;
                state.aimRotW[hand] = aimLoc.pose.orientation.w;
            }
        }
    }

    return true;
}

// ── Math Helpers ──

void XrRenderer::xrPoseToViewMatrix(const XrPosef& pose, float* out) {
    // View matrix = inverse of the pose (position + orientation)
    // Quaternion to rotation matrix, then invert (transpose rotation, negate translation)
    float x = pose.orientation.x, y = pose.orientation.y;
    float z = pose.orientation.z, w = pose.orientation.w;

    float x2 = x + x, y2 = y + y, z2 = z + z;
    float xx = x * x2, xy = x * y2, xz = x * z2;
    float yy = y * y2, yz = y * z2, zz = z * z2;
    float wx = w * x2, wy = w * y2, wz = w * z2;

    // Rotation part (transposed for inverse)
    float r00 = 1.0f - (yy + zz), r01 = xy + wz,        r02 = xz - wy;
    float r10 = xy - wz,          r11 = 1.0f - (xx + zz), r12 = yz + wx;
    float r20 = xz + wy,          r21 = yz - wx,          r22 = 1.0f - (xx + yy);

    float px = pose.position.x, py = pose.position.y, pz = pose.position.z;

    // Column-major 4x4 view matrix
    out[0]  = r00;  out[1]  = r10;  out[2]  = r20;  out[3]  = 0.0f;
    out[4]  = r01;  out[5]  = r11;  out[6]  = r21;  out[7]  = 0.0f;
    out[8]  = r02;  out[9]  = r12;  out[10] = r22;  out[11] = 0.0f;
    out[12] = -(r00*px + r01*py + r02*pz);
    out[13] = -(r10*px + r11*py + r12*pz);
    out[14] = -(r20*px + r21*py + r22*pz);
    out[15] = 1.0f;
}

void XrRenderer::xrFovToProjection(const XrFovf& fov, float nearZ, float farZ, float* out) {
    // Asymmetric frustum projection from OpenXR field of view angles
    float tanL = tanf(fov.angleLeft);
    float tanR = tanf(fov.angleRight);
    float tanU = tanf(fov.angleUp);
    float tanD = tanf(fov.angleDown);

    float w = tanR - tanL;
    float h = tanU - tanD;

    memset(out, 0, 16 * sizeof(float));

    // Column-major
    out[0]  = 2.0f / w;
    out[5]  = 2.0f / h;
    out[8]  = (tanR + tanL) / w;
    out[9]  = (tanU + tanD) / h;
    out[10] = -(farZ + nearZ) / (farZ - nearZ);
    out[11] = -1.0f;
    out[14] = -(2.0f * farZ * nearZ) / (farZ - nearZ);
}

// ── UI Quad ──

bool XrRenderer::initUiQuad(uint32_t width, uint32_t height) {
    return createUiSwapchain(width, height);
}

bool XrRenderer::createUiSwapchain(uint32_t width, uint32_t height) {
    if (uiSwapchain_ != XR_NULL_HANDLE) {
        xrDestroySwapchain(uiSwapchain_);
        uiSwapchain_ = XR_NULL_HANDLE;
    }

    XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    ci.format = GL_SRGB8_ALPHA8;
    ci.width = width;
    ci.height = height;
    ci.sampleCount = 1;
    ci.faceCount = 1;
    ci.arraySize = 1;
    ci.mipCount = 1;

    XrResult r = xrCreateSwapchain(session_, &ci, &uiSwapchain_);
    if (XR_FAILED(r)) {
        XR_LOGE("Failed to create UI swapchain: %d", (int)r);
        return false;
    }

    uint32_t imgCount = 0;
    xrEnumerateSwapchainImages(uiSwapchain_, 0, &imgCount, nullptr);
    std::vector<XrSwapchainImageOpenGLESKHR> images(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    xrEnumerateSwapchainImages(uiSwapchain_, imgCount, &imgCount,
        (XrSwapchainImageBaseHeader*)images.data());

    uiSwapchainImages_.resize(imgCount);
    for (uint32_t i = 0; i < imgCount; i++) {
        uiSwapchainImages_[i] = images[i].image;
    }

    uiWidth_ = width;
    uiHeight_ = height;
    XR_LOGI("UI quad swapchain created: %ux%u, %u images", width, height, imgCount);
    return true;
}

uint32_t XrRenderer::acquireUiImage() {
    if (uiSwapchain_ == XR_NULL_HANDLE) return 0;
    XrSwapchainImageAcquireInfo acqInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    uint32_t idx = 0;
    if (XR_FAILED(xrAcquireSwapchainImage(uiSwapchain_, &acqInfo, &idx))) return 0;
    XrSwapchainImageWaitInfo waitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    waitInfo.timeout = XR_INFINITE_DURATION;
    if (XR_FAILED(xrWaitSwapchainImage(uiSwapchain_, &waitInfo))) return 0;
    return uiSwapchainImages_[idx];
}

void XrRenderer::releaseUiImage() {
    if (uiSwapchain_ == XR_NULL_HANDLE) return;
    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    xrReleaseSwapchainImage(uiSwapchain_, &ri);
}

// ── Shutdown ──

void XrRenderer::shutdown() {
    for (int i = 0; i < 2; i++) {
        if (handSpaces_[i] != XR_NULL_HANDLE) xrDestroySpace(handSpaces_[i]);
        if (aimSpaces_[i] != XR_NULL_HANDLE) xrDestroySpace(aimSpaces_[i]);
        handSpaces_[i] = XR_NULL_HANDLE;
        aimSpaces_[i] = XR_NULL_HANDLE;
    }
    for (int i = 0; i < 2; i++) {
        if (swapchains_[i] != XR_NULL_HANDLE) xrDestroySwapchain(swapchains_[i]);
        swapchains_[i] = XR_NULL_HANDLE;
    }
    if (uiSwapchain_ != XR_NULL_HANDLE) {
        xrDestroySwapchain(uiSwapchain_);
        uiSwapchain_ = XR_NULL_HANDLE;
    }
    if (lightEstimator_ != XR_NULL_HANDLE && xrDestroyLightEstimator_) {
        xrDestroyLightEstimator_(lightEstimator_);
        lightEstimator_ = XR_NULL_HANDLE;
    }
    // Destroy hand trackers
    for (int i = 0; i < 2; i++) {
        if (handTrackers_[i] != XR_NULL_HANDLE && xrDestroyHandTracker_) {
            xrDestroyHandTracker_(handTrackers_[i]);
            handTrackers_[i] = XR_NULL_HANDLE;
        }
    }
    // Destroy eye tracker
    if (eyeTracker_ != XR_NULL_HANDLE && xrDestroyEyeTracker_) {
        xrDestroyEyeTracker_(eyeTracker_);
        eyeTracker_ = XR_NULL_HANDLE;
    }
    // Destroy face tracker
    if (faceTracker_ != XR_NULL_HANDLE && xrDestroyFaceTracker_) {
        xrDestroyFaceTracker_(faceTracker_);
        faceTracker_ = XR_NULL_HANDLE;
    }
    // Destroy plane tracker
    if (planeTracker_ != XR_NULL_HANDLE && xrDestroyTrackableTracker_) {
        xrDestroyTrackableTracker_(planeTracker_);
        planeTracker_ = XR_NULL_HANDLE;
    }
    // Destroy foveation profile
    if (foveationProfile_ != XR_NULL_HANDLE && xrDestroyFoveationProfileFB_) {
        xrDestroyFoveationProfileFB_(foveationProfile_);
        foveationProfile_ = XR_NULL_HANDLE;
    }
    // Disable perf metrics
    if (perfMetricsSupported_ && xrSetPerfState_ && session_) {
        XrPerformanceMetricsStateANDROID state = {};
        state.type = XR_TYPE_PERFORMANCE_METRICS_STATE_ANDROID;
        state.enabled = XR_FALSE;
        xrSetPerfState_(session_, &state);
    }
    if (appSpace_ != XR_NULL_HANDLE) xrDestroySpace(appSpace_);
    if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
    if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
    appSpace_ = XR_NULL_HANDLE;
    session_ = XR_NULL_HANDLE;
    instance_ = XR_NULL_HANDLE;
    sessionReady_ = false;
    running_ = false;
    shutdownEGL();
    XR_LOGI("XrRenderer shut down");
}

// ── Light Estimation ──

bool XrRenderer::initLightEstimation() {
    // Load function pointers
    XrResult r;
    r = xrGetInstanceProcAddr(instance_, "xrCreateLightEstimatorANDROID",
        (PFN_xrVoidFunction*)&xrCreateLightEstimator_);
    if (XR_FAILED(r) || !xrCreateLightEstimator_) {
        XR_LOGE("Failed to load xrCreateLightEstimatorANDROID");
        lightEstimationSupported_ = false;
        return false;
    }
    r = xrGetInstanceProcAddr(instance_, "xrDestroyLightEstimatorANDROID",
        (PFN_xrVoidFunction*)&xrDestroyLightEstimator_);
    r = xrGetInstanceProcAddr(instance_, "xrGetLightEstimateANDROID",
        (PFN_xrVoidFunction*)&xrGetLightEstimate_);
    if (!xrDestroyLightEstimator_ || !xrGetLightEstimate_) {
        XR_LOGE("Failed to load light estimation functions");
        lightEstimationSupported_ = false;
        return false;
    }

    // Create light estimator
    XrLightEstimatorCreateInfoANDROID createInfo;
    createInfo.type = (XrStructureType)XR_TYPE_LIGHT_ESTIMATOR_CREATE_INFO_ANDROID;
    createInfo.next = nullptr;

    r = xrCreateLightEstimator_(session_, &createInfo, &lightEstimator_);
    if (XR_FAILED(r)) {
        XR_LOGE("xrCreateLightEstimatorANDROID failed: %d", (int)r);
        lightEstimationSupported_ = false;
        return false;
    }

    XR_LOGI("Light estimation initialized successfully");
    return true;
}

bool XrRenderer::pollLightEstimate(LightEstimate& estimate) {
    estimate.valid = false;
    if (!lightEstimationSupported_) return false;
    if (!sessionReady_ || appSpace_ == XR_NULL_HANDLE) return false;

    // Lazy init: create estimator on first poll (after permission granted)
    if (lightEstimator_ == XR_NULL_HANDLE) {
        // Retry every ~5 seconds (360 frames at 72fps)
        if (lightEstRetryCount_++ % 360 != 0) return false;
        if (!initLightEstimation()) {
            XR_LOGI("Light estimator not ready yet (attempt %d), will retry", lightEstRetryCount_);
            return false;
        }
    }
    if (!xrGetLightEstimate_) return false;

    // Use last predicted display time
    if (lastPredictedTime_ == 0) return false;
    XrTime now = lastPredictedTime_;

    // Query light estimate
    // Step 1: Try root-only call first to verify basic function works
    XrLightEstimateGetInfoANDROID getInfo = {};
    getInfo.type = (XrStructureType)XR_TYPE_LIGHT_ESTIMATE_GET_INFO_ANDROID;
    getInfo.next = nullptr;
    getInfo.space = appSpace_;
    getInfo.time = now;

    // Chain output structs onto root.next
    XrSphericalHarmonicsANDROID shLight = {};
    shLight.type = (XrStructureType)XR_TYPE_SPHERICAL_HARMONICS_ANDROID;
    shLight.next = nullptr;
    shLight.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;
    shLight.kind = XR_SPHERICAL_HARMONICS_KIND_TOTAL_ANDROID;

    XrDirectionalLightANDROID dirLight = {};
    dirLight.type = (XrStructureType)XR_TYPE_DIRECTIONAL_LIGHT_ANDROID;
    dirLight.next = nullptr;  // SH disabled until padding issue resolved
    dirLight.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;

    XrAmbientLightANDROID ambLight = {};
    ambLight.type = (XrStructureType)XR_TYPE_AMBIENT_LIGHT_ANDROID;
    ambLight.next = &dirLight;
    ambLight.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;

    // Skip first 200 frames (~3 seconds) to let runtime warm up, then poll every 10 frames
    static int frameCount = 0;
    frameCount++;
    if (frameCount < 200) return false;
    if (frameCount % 10 != 0) {
        estimate = lastLightEstimate_;
        return estimate.valid;
    }

    // Full chain: ambient → directional → spherical harmonics
    // (Fixed: type enum values were swapped in developer.android.com docs vs actual runtime)
    XrSphericalHarmonicsANDROID sh = {};
    sh.type = (XrStructureType)XR_TYPE_SPHERICAL_HARMONICS_ANDROID;
    sh.next = nullptr;
    sh.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;
    sh.kind = XR_SPHERICAL_HARMONICS_KIND_TOTAL_ANDROID;

    XrDirectionalLightANDROID dir = {};
    dir.type = (XrStructureType)XR_TYPE_DIRECTIONAL_LIGHT_ANDROID;
    dir.next = &sh;
    dir.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;

    XrAmbientLightANDROID amb = {};
    amb.type = (XrStructureType)XR_TYPE_AMBIENT_LIGHT_ANDROID;
    amb.next = &dir;
    amb.state = XR_LIGHT_ESTIMATE_STATE_INVALID_ANDROID;

    XrLightEstimateANDROID out = {};
    out.type = (XrStructureType)XR_TYPE_LIGHT_ESTIMATE_ANDROID;
    out.next = &amb;

    // Log struct sizes for debugging
    static bool loggedSizes = false;
    if (!loggedSizes) {
        XR_LOGI("Struct sizes: LightEst=%zu Ambient=%zu Dir=%zu SH=%zu GetInfo=%zu",
            sizeof(XrLightEstimateANDROID), sizeof(XrAmbientLightANDROID),
            sizeof(XrDirectionalLightANDROID), sizeof(XrSphericalHarmonicsANDROID),
            sizeof(XrLightEstimateGetInfoANDROID));
        XR_LOGI("  appSpace=%llu time=%lld", (unsigned long long)appSpace_, (long long)now);
        loggedSizes = true;
    }

    XrResult r = xrGetLightEstimate_(lightEstimator_, &getInfo, &out);

    if (XR_FAILED(r)) {
        static int errCount = 0;
        if (errCount++ < 5) XR_LOGE("xrGetLightEstimate failed: %d", (int)r);
        return false;
    }

    if (out.state != XR_LIGHT_ESTIMATE_STATE_VALID_ANDROID) return false;

    if (amb.state == XR_LIGHT_ESTIMATE_STATE_VALID_ANDROID) {
        estimate.ambientR = amb.intensity.x;
        estimate.ambientG = amb.intensity.y;
        estimate.ambientB = amb.intensity.z;
        estimate.colorCorrR = amb.colorCorrection.x;
        estimate.colorCorrG = amb.colorCorrection.y;
        estimate.colorCorrB = amb.colorCorrection.z;
        estimate.valid = true;
    }

    // Directional light
    if (dir.state == XR_LIGHT_ESTIMATE_STATE_VALID_ANDROID) {
        estimate.dirIntensityR = dir.intensity.x;
        estimate.dirIntensityG = dir.intensity.y;
        estimate.dirIntensityB = dir.intensity.z;
        estimate.dirX = dir.direction.x;
        estimate.dirY = dir.direction.y;
        estimate.dirZ = dir.direction.z;
    }

    // Spherical harmonics
    if (sh.state == XR_LIGHT_ESTIMATE_STATE_VALID_ANDROID) {
        estimate.shValid = true;
        for (int i = 0; i < 9; i++) {
            estimate.sh[i * 3 + 0] = sh.coefficients[i][0];
            estimate.sh[i * 3 + 1] = sh.coefficients[i][1];
            estimate.sh[i * 3 + 2] = sh.coefficients[i][2];
        }
    }

    lastLightEstimate_ = estimate;

    static int logCount = 0;
    if (estimate.valid && logCount++ < 5) {
        XR_LOGI("XR Light: amb=(%.3f,%.3f,%.3f) dir=(%.2f,%.2f,%.2f) int=(%.3f,%.3f,%.3f)",
            estimate.ambientR, estimate.ambientG, estimate.ambientB,
            estimate.dirX, estimate.dirY, estimate.dirZ,
            estimate.dirIntensityR, estimate.dirIntensityG, estimate.dirIntensityB);
    }

    return estimate.valid;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Hand Tracking ──
// ═══════════════════════════════════════════════════════════════════════

bool XrRenderer::initHandTracking() {
    XrResult r;
    r = xrGetInstanceProcAddr(instance_, "xrCreateHandTrackerEXT",
        (PFN_xrVoidFunction*)&xrCreateHandTracker_);
    r = xrGetInstanceProcAddr(instance_, "xrDestroyHandTrackerEXT",
        (PFN_xrVoidFunction*)&xrDestroyHandTracker_);
    r = xrGetInstanceProcAddr(instance_, "xrLocateHandJointsEXT",
        (PFN_xrVoidFunction*)&xrLocateHandJoints_);

    if (!xrCreateHandTracker_ || !xrDestroyHandTracker_ || !xrLocateHandJoints_) {
        XR_LOGE("Failed to load hand tracking functions");
        handTrackingSupported_ = false;
        return false;
    }

    // Create hand trackers (deferred until session is ready — called from init after session)
    // Will be lazily created on first poll if session isn't ready yet
    XR_LOGI("Hand tracking functions loaded");
    return true;
}

bool XrRenderer::pollHandTracking(int hand, HandJointData& data) {
    data.active = false;
    if (!handTrackingSupported_ || !session_ || !sessionReady_) return false;
    if (!xrLocateHandJoints_ || !xrCreateHandTracker_) return false;
    if (hand < 0 || hand > 1) return false;

    // Lazy create tracker
    if (handTrackers_[hand] == XR_NULL_HANDLE) {
        XrHandTrackerCreateInfoEXT ci = {};
        ci.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
        ci.hand = (hand == 0) ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
        ci.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
        XrResult r = xrCreateHandTracker_(session_, &ci, &handTrackers_[hand]);
        if (XR_FAILED(r)) {
            static int logCount = 0;
            if (logCount++ < 3) XR_LOGE("xrCreateHandTrackerEXT hand=%d failed: %d", hand, (int)r);
            return false;
        }
        XR_LOGI("Hand tracker created: hand=%d", hand);
    }

    if (lastPredictedTime_ == 0) return false;

    XrHandJointsLocateInfoEXT locateInfo = {};
    locateInfo.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
    locateInfo.baseSpace = appSpace_;
    locateInfo.time = lastPredictedTime_;

    XrHandJointLocationEXT jointLocs[XR_HAND_JOINT_COUNT_EXT] = {};
    XrHandJointLocationsEXT locations = {};
    locations.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
    locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
    locations.jointLocations = jointLocs;

    XrResult r = xrLocateHandJoints_(handTrackers_[hand], &locateInfo, &locations);
    if (XR_FAILED(r)) return false;

    data.active = locations.isActive;
    if (!data.active) return false;

    for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
        auto& src = jointLocs[j];
        auto& dst = data.joints[j];
        dst.valid = (src.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
        dst.posX = src.pose.position.x;
        dst.posY = src.pose.position.y;
        dst.posZ = src.pose.position.z;
        dst.rotX = src.pose.orientation.x;
        dst.rotY = src.pose.orientation.y;
        dst.rotZ = src.pose.orientation.z;
        dst.rotW = src.pose.orientation.w;
        dst.radius = src.radius;
    }

    return true;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Eye Tracking ──
// ═══════════════════════════════════════════════════════════════════════

bool XrRenderer::initEyeTracking() {
    xrGetInstanceProcAddr(instance_, "xrCreateEyeTrackerANDROID",
        (PFN_xrVoidFunction*)&xrCreateEyeTracker_);
    xrGetInstanceProcAddr(instance_, "xrDestroyEyeTrackerANDROID",
        (PFN_xrVoidFunction*)&xrDestroyEyeTracker_);
    xrGetInstanceProcAddr(instance_, "xrGetEyeStateANDROID",
        (PFN_xrVoidFunction*)&xrGetEyeState_);

    if (!xrCreateEyeTracker_ || !xrDestroyEyeTracker_ || !xrGetEyeState_) {
        XR_LOGE("Failed to load eye tracking functions");
        eyeTrackingSupported_ = false;
        return false;
    }

    XR_LOGI("Eye tracking functions loaded");
    return true;
}

bool XrRenderer::pollEyeTracking(EyeTrackingData& data) {
    data.valid = false;
    if (!eyeTrackingSupported_ || !session_ || !sessionReady_) return false;
    if (!xrGetEyeState_ || !xrCreateEyeTracker_) return false;

    // Lazy create
    if (eyeTracker_ == XR_NULL_HANDLE) {
        XrEyeTrackerCreateInfoANDROID ci = {};
        ci.type = XR_TYPE_EYE_TRACKER_CREATE_INFO_ANDROID;
        XrResult r = xrCreateEyeTracker_(session_, &ci, &eyeTracker_);
        if (XR_FAILED(r)) {
            static int logCount = 0;
            if (logCount++ < 3) XR_LOGE("xrCreateEyeTrackerANDROID failed: %d", (int)r);
            return false;
        }
        XR_LOGI("Eye tracker created");
    }

    if (lastPredictedTime_ == 0) return false;

    XrEyeTrackerGetInfoANDROID getInfo = {};
    getInfo.type = XR_TYPE_EYE_TRACKER_GET_INFO_ANDROID;
    getInfo.baseSpace = appSpace_;
    getInfo.time = lastPredictedTime_;

    XrEyeStateDataANDROID eyeState = {};
    eyeState.type = XR_TYPE_EYE_STATE_ANDROID;

    XrResult r = xrGetEyeState_(eyeTracker_, &getInfo, &eyeState);
    if (XR_FAILED(r)) return false;

    data.leftValid = (eyeState.leftEyeState == XR_EYE_VALIDITY_VALID_ANDROID);
    if (data.leftValid) {
        data.leftPosX = eyeState.leftEyePose.position.x;
        data.leftPosY = eyeState.leftEyePose.position.y;
        data.leftPosZ = eyeState.leftEyePose.position.z;
        data.leftRotX = eyeState.leftEyePose.orientation.x;
        data.leftRotY = eyeState.leftEyePose.orientation.y;
        data.leftRotZ = eyeState.leftEyePose.orientation.z;
        data.leftRotW = eyeState.leftEyePose.orientation.w;
    }

    data.rightValid = (eyeState.rightEyeState == XR_EYE_VALIDITY_VALID_ANDROID);
    if (data.rightValid) {
        data.rightPosX = eyeState.rightEyePose.position.x;
        data.rightPosY = eyeState.rightEyePose.position.y;
        data.rightPosZ = eyeState.rightEyePose.position.z;
        data.rightRotX = eyeState.rightEyePose.orientation.x;
        data.rightRotY = eyeState.rightEyePose.orientation.y;
        data.rightRotZ = eyeState.rightEyePose.orientation.z;
        data.rightRotW = eyeState.rightEyePose.orientation.w;
    }

    data.combinedValid = (eyeState.combinedEyeState == XR_EYE_VALIDITY_VALID_ANDROID);
    if (data.combinedValid) {
        data.combPosX = eyeState.combinedEyePose.position.x;
        data.combPosY = eyeState.combinedEyePose.position.y;
        data.combPosZ = eyeState.combinedEyePose.position.z;
        data.combRotX = eyeState.combinedEyePose.orientation.x;
        data.combRotY = eyeState.combinedEyePose.orientation.y;
        data.combRotZ = eyeState.combinedEyePose.orientation.z;
        data.combRotW = eyeState.combinedEyePose.orientation.w;
    }

    data.valid = data.leftValid || data.rightValid || data.combinedValid;

    static int eyeLogCount = 0;
    if (data.valid && eyeLogCount++ < 3) {
        XR_LOGI("Eye gaze: combined=(%.3f,%.3f,%.3f) rot=(%.3f,%.3f,%.3f,%.3f)",
            data.combPosX, data.combPosY, data.combPosZ,
            data.combRotX, data.combRotY, data.combRotZ, data.combRotW);
    }

    return data.valid;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Face Tracking ──
// ═══════════════════════════════════════════════════════════════════════

bool XrRenderer::initFaceTracking() {
    xrGetInstanceProcAddr(instance_, "xrCreateFaceTrackerANDROID",
        (PFN_xrVoidFunction*)&xrCreateFaceTracker_);
    xrGetInstanceProcAddr(instance_, "xrDestroyFaceTrackerANDROID",
        (PFN_xrVoidFunction*)&xrDestroyFaceTracker_);
    xrGetInstanceProcAddr(instance_, "xrGetFaceStateANDROID",
        (PFN_xrVoidFunction*)&xrGetFaceState_);

    if (!xrCreateFaceTracker_ || !xrDestroyFaceTracker_ || !xrGetFaceState_) {
        XR_LOGE("Failed to load face tracking functions");
        faceTrackingSupported_ = false;
        return false;
    }

    XR_LOGI("Face tracking functions loaded");
    return true;
}

bool XrRenderer::pollFaceTracking(FaceTrackingData& data) {
    data.valid = false;
    if (!faceTrackingSupported_ || !session_ || !sessionReady_) return false;
    if (!xrGetFaceState_ || !xrCreateFaceTracker_) return false;

    // Lazy create
    if (faceTracker_ == XR_NULL_HANDLE) {
        XrFaceTrackerCreateInfoANDROID ci = {};
        ci.type = XR_TYPE_FACE_TRACKER_CREATE_INFO_ANDROID;
        XrResult r = xrCreateFaceTracker_(session_, &ci, &faceTracker_);
        if (XR_FAILED(r)) {
            static int logCount = 0;
            if (logCount++ < 3) XR_LOGE("xrCreateFaceTrackerANDROID failed: %d", (int)r);
            return false;
        }
        XR_LOGI("Face tracker created");
    }

    if (lastPredictedTime_ == 0) return false;

    XrFaceStateGetInfoANDROID getInfo = {};
    getInfo.type = XR_TYPE_FACE_STATE_GET_INFO_ANDROID;
    getInfo.time = lastPredictedTime_;

    XrFaceStateANDROID faceState = {};
    faceState.type = XR_TYPE_FACE_STATE_ANDROID;
    faceState.blendShapeCount = XR_FACE_BLEND_SHAPE_COUNT_ANDROID;
    faceState.blendShapeWeights = faceBlendShapes_;

    XrResult r = xrGetFaceState_(faceTracker_, &getInfo, &faceState);
    if (XR_FAILED(r)) return false;

    if (faceState.validFlags & XR_FACE_STATE_BLEND_SHAPES_VALID_BIT_ANDROID) {
        data.valid = true;
        memcpy(data.blendShapes, faceBlendShapes_, sizeof(float) * XR_FACE_BLEND_SHAPE_COUNT_ANDROID);
    }

    static int faceLogCount = 0;
    if (data.valid && faceLogCount++ < 3) {
        XR_LOGI("Face: %d blend shapes, jaw=%.2f, smile_L=%.2f, smile_R=%.2f",
            XR_FACE_BLEND_SHAPE_COUNT_ANDROID,
            data.blendShapes[0], data.blendShapes[1], data.blendShapes[2]);
    }

    return data.valid;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Plane Detection (Trackables) ──
// ═══════════════════════════════════════════════════════════════════════

bool XrRenderer::initPlaneTracking() {
    xrGetInstanceProcAddr(instance_, "xrCreateTrackableTrackerANDROID",
        (PFN_xrVoidFunction*)&xrCreateTrackableTracker_);
    xrGetInstanceProcAddr(instance_, "xrDestroyTrackableTrackerANDROID",
        (PFN_xrVoidFunction*)&xrDestroyTrackableTracker_);
    xrGetInstanceProcAddr(instance_, "xrGetAllTrackablesANDROID",
        (PFN_xrVoidFunction*)&xrGetAllTrackables_);
    xrGetInstanceProcAddr(instance_, "xrGetTrackablePlaneANDROID",
        (PFN_xrVoidFunction*)&xrGetTrackablePlane_);

    if (!xrCreateTrackableTracker_ || !xrGetAllTrackables_ || !xrGetTrackablePlane_) {
        XR_LOGE("Failed to load trackable functions");
        trackablesSupported_ = false;
        return false;
    }

    XR_LOGI("Trackable (plane) functions loaded");
    return true;
}

bool XrRenderer::pollPlanes(PlaneData& data) {
    data.valid = false;
    data.planeCount = 0;
    if (!trackablesSupported_ || !session_ || !sessionReady_) return false;
    if (!xrGetAllTrackables_ || !xrGetTrackablePlane_ || !xrCreateTrackableTracker_) return false;

    // Lazy create
    if (planeTracker_ == XR_NULL_HANDLE) {
        XrTrackableTrackerCreateInfoANDROID ci = {};
        ci.type = XR_TYPE_TRACKABLE_TRACKER_CREATE_INFO_ANDROID;
        ci.trackableType = XR_TRACKABLE_TYPE_PLANE_ANDROID;
        XrResult r = xrCreateTrackableTracker_(session_, &ci, &planeTracker_);
        if (XR_FAILED(r)) {
            static int logCount = 0;
            if (logCount++ < 3) XR_LOGE("xrCreateTrackableTrackerANDROID failed: %d", (int)r);
            return false;
        }
        XR_LOGI("Plane tracker created");
    }

    if (lastPredictedTime_ == 0) return false;

    // Get all trackable IDs
    XrAllTrackablesGetInfoANDROID allInfo = {};
    allInfo.type = XR_TYPE_ALL_TRACKABLES_GET_INFO_ANDROID;

    uint32_t trackableCount = 0;
    XrResult r = xrGetAllTrackables_(planeTracker_, &allInfo, 0, &trackableCount, nullptr);
    if (XR_FAILED(r) || trackableCount == 0) return false;

    uint32_t maxGet = (trackableCount > PlaneData::MAX_PLANES) ? PlaneData::MAX_PLANES : trackableCount;
    XrTrackableANDROID trackableIds[PlaneData::MAX_PLANES];
    r = xrGetAllTrackables_(planeTracker_, &allInfo, maxGet, &trackableCount, trackableIds);
    if (XR_FAILED(r)) return false;

    uint32_t count = (trackableCount > PlaneData::MAX_PLANES) ? PlaneData::MAX_PLANES : trackableCount;

    for (uint32_t i = 0; i < count; i++) {
        XrTrackableGetInfoANDROID getInfo = {};
        getInfo.type = XR_TYPE_TRACKABLE_GET_INFO_ANDROID;
        getInfo.trackable = trackableIds[i];
        getInfo.baseSpace = appSpace_;
        getInfo.time = lastPredictedTime_;

        XrTrackablePlaneANDROID plane = {};
        plane.type = XR_TYPE_TRACKABLE_PLANE_ANDROID;
        plane.vertexCapacityInput = 0;  // don't need polygon vertices
        plane.vertices = nullptr;

        r = xrGetTrackablePlane_(planeTracker_, &getInfo, &plane);
        if (XR_FAILED(r)) continue;

        auto& dst = data.planes[data.planeCount];
        dst.posX = plane.centerPose.position.x;
        dst.posY = plane.centerPose.position.y;
        dst.posZ = plane.centerPose.position.z;
        dst.rotX = plane.centerPose.orientation.x;
        dst.rotY = plane.centerPose.orientation.y;
        dst.rotZ = plane.centerPose.orientation.z;
        dst.rotW = plane.centerPose.orientation.w;
        dst.extentX = plane.extents.width * 0.5f;
        dst.extentY = plane.extents.height * 0.5f;
        dst.label = (int)plane.planeLabel;
        data.planeCount++;
    }

    data.valid = (data.planeCount > 0);

    static int planeLogCount = 0;
    if (data.valid && planeLogCount++ < 3) {
        XR_LOGI("Detected %d planes", data.planeCount);
        for (int i = 0; i < data.planeCount && i < 5; i++) {
            const char* labels[] = {"unknown", "floor", "ceiling", "wall", "table"};
            int lbl = data.planes[i].label;
            XR_LOGI("  Plane %d: %s (%.2f,%.2f,%.2f) ext=(%.2f,%.2f)",
                i, (lbl >= 0 && lbl <= 4) ? labels[lbl] : "?",
                data.planes[i].posX, data.planes[i].posY, data.planes[i].posZ,
                data.planes[i].extentX, data.planes[i].extentY);
        }
    }

    return data.valid;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Display Refresh Rate ──
// ═══════════════════════════════════════════════════════════════════════

float XrRenderer::getDisplayRefreshRate() {
    if (!refreshRateSupported_ || !xrGetRefreshRate_ || !session_) return 0;
    float rate = 0;
    xrGetRefreshRate_(session_, &rate);
    return rate;
}

int XrRenderer::getAvailableRefreshRates(float* out, int maxCount) {
    if (!refreshRateSupported_ || !xrEnumRefreshRates_ || !session_) return 0;
    uint32_t count = 0;
    xrEnumRefreshRates_(session_, 0, &count, nullptr);
    if (count == 0) return 0;
    uint32_t get = (count > (uint32_t)maxCount) ? (uint32_t)maxCount : count;
    xrEnumRefreshRates_(session_, get, &count, out);
    return (int)count;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Performance Metrics ──
// ═══════════════════════════════════════════════════════════════════════

bool XrRenderer::initPerfMetrics() {
    xrGetInstanceProcAddr(instance_, "xrEnumeratePerformanceMetricsCounterPathsANDROID",
        (PFN_xrVoidFunction*)&xrEnumPerfPaths_);
    xrGetInstanceProcAddr(instance_, "xrSetPerformanceMetricsStateANDROID",
        (PFN_xrVoidFunction*)&xrSetPerfState_);
    xrGetInstanceProcAddr(instance_, "xrQueryPerformanceMetricsCounterANDROID",
        (PFN_xrVoidFunction*)&xrQueryPerfCounter_);

    if (!xrEnumPerfPaths_ || !xrSetPerfState_ || !xrQueryPerfCounter_) {
        XR_LOGE("Failed to load performance metrics functions");
        perfMetricsSupported_ = false;
        return false;
    }

    // Enumerate available counter paths
    uint32_t pathCount = 0;
    xrEnumPerfPaths_(instance_, 0, &pathCount, nullptr);
    if (pathCount > 0) {
        std::vector<XrPath> paths(pathCount);
        xrEnumPerfPaths_(instance_, pathCount, &pathCount, paths.data());
        XR_LOGI("Performance metrics: %u counter paths available", pathCount);

        // Log all available paths
        char pathStr[256];
        for (uint32_t i = 0; i < pathCount; i++) {
            uint32_t len = 0;
            xrPathToString(instance_, paths[i], sizeof(pathStr), &len, pathStr);
            XR_LOGI("  Perf path: %s", pathStr);

            // Try to match common paths
            if (strstr(pathStr, "gpu") && strstr(pathStr, "time"))
                perfPathGpuTime_ = paths[i];
            else if (strstr(pathStr, "cpu") && strstr(pathStr, "time"))
                perfPathCpuTime_ = paths[i];
            else if (strstr(pathStr, "drop"))
                perfPathDropped_ = paths[i];
        }
    }

    XR_LOGI("Performance metrics initialized");
    return true;
}

bool XrRenderer::pollPerfMetrics(PerfMetrics& data) {
    data.valid = false;
    if (!perfMetricsSupported_ || !session_ || !sessionReady_) return false;
    if (!xrQueryPerfCounter_ || !xrSetPerfState_) return false;

    // Enable metrics collection (idempotent) — must pass struct pointer, not raw bool
    static bool metricsEnabled = false;
    if (!metricsEnabled) {
        XrPerformanceMetricsStateANDROID state = {};
        state.type = XR_TYPE_PERFORMANCE_METRICS_STATE_ANDROID;
        state.enabled = XR_TRUE;
        XrResult r = xrSetPerfState_(session_, &state);
        if (XR_FAILED(r)) {
            XR_LOGE("xrSetPerformanceMetricsState failed: %d", (int)r);
        } else {
            metricsEnabled = true;
            XR_LOGI("Performance metrics enabled");
        }
    }

    if (metricsEnabled) {
        XrPerformanceMetricsCounterANDROID counter = {};
        counter.type = XR_TYPE_PERFORMANCE_METRICS_COUNTER_ANDROID;

        if (perfPathGpuTime_ != XR_NULL_PATH) {
            if (XR_SUCCEEDED(xrQueryPerfCounter_(session_, perfPathGpuTime_, &counter)))
                data.gpuFrameTimeMs = counter.floatValue;
        }
        if (perfPathCpuTime_ != XR_NULL_PATH) {
            counter = {};
            counter.type = XR_TYPE_PERFORMANCE_METRICS_COUNTER_ANDROID;
            if (XR_SUCCEEDED(xrQueryPerfCounter_(session_, perfPathCpuTime_, &counter)))
                data.cpuFrameTimeMs = counter.floatValue;
        }
        if (perfPathDropped_ != XR_NULL_PATH) {
            counter = {};
            counter.type = XR_TYPE_PERFORMANCE_METRICS_COUNTER_ANDROID;
            if (XR_SUCCEEDED(xrQueryPerfCounter_(session_, perfPathDropped_, &counter)))
                data.compositorDroppedFrames = (float)counter.uintValue;
        }
    }

    data.displayRefreshRate = getDisplayRefreshRate();
    data.valid = true;
    return true;
}

// ═══════════════════════════════════════════════════════════════════════
// ── Passthrough Camera State ──
// ═══════════════════════════════════════════════════════════════════════

int XrRenderer::getPassthroughState() {
    // Passthrough state is inferred from blend mode selection
    // ALPHA_BLEND = passthrough enabled, OPAQUE = passthrough disabled
    if (blendMode_ == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) return 2; // enabled
    return 0; // disabled
}

// ═══════════════════════════════════════════════════════════════════════
// ── Foveated Rendering ──
// ═══════════════════════════════════════════════════════════════════════

void XrRenderer::setFoveationLevel(int level) {
    if (!foveationSupported_ || !xrCreateFoveationProfileFB_ || !xrUpdateSwapchainFB_) {
        XR_LOGE("Foveation not supported");
        return;
    }
    foveationLevel_ = level;

    // Destroy old profile if exists
    if (foveationProfile_ != XR_NULL_HANDLE) {
        xrDestroyFoveationProfileFB_(foveationProfile_);
        foveationProfile_ = XR_NULL_HANDLE;
    }

    if (level == 0) {
        XR_LOGI("Foveation disabled");
        // Apply no-foveation to swapchains
        for (int eye = 0; eye < 2; eye++) {
            if (swapchains_[eye] == XR_NULL_HANDLE) continue;
            XrSwapchainStateFoveationFB fovState{};
            fovState.type = XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB;
            fovState.profile = XR_NULL_HANDLE;
            xrUpdateSwapchainFB_(swapchains_[eye], (XrSwapchainStateBaseHeaderFB*)&fovState);
        }
        return;
    }

    // Map level to FB foveation level enum
    XrFoveationLevelFB fbLevel;
    switch (level) {
        case 1: fbLevel = XR_FOVEATION_LEVEL_LOW_FB; break;
        case 2: fbLevel = XR_FOVEATION_LEVEL_MEDIUM_FB; break;
        case 3: default: fbLevel = XR_FOVEATION_LEVEL_HIGH_FB; break;
    }

    // Create foveation level profile
    XrFoveationLevelProfileCreateInfoFB levelCI{};
    levelCI.type = XR_TYPE_FOVEATION_LEVEL_PROFILE_CREATE_INFO_FB;
    levelCI.level = fbLevel;
    levelCI.verticalOffset = 0;
    levelCI.dynamic = XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB; // let runtime adjust based on perf

    // If eye-tracked foveation is available, chain it
    XrFoveationEyeTrackedProfileCreateInfoMETA eyeTrackedCI{};
    if (eyeTrackedFoveation_) {
        eyeTrackedCI.type = XR_TYPE_FOVEATION_EYE_TRACKED_PROFILE_CREATE_INFO_META;
        eyeTrackedCI.next = nullptr;
        levelCI.next = &eyeTrackedCI;
    }

    XrFoveationProfileCreateInfoFB profileCI{};
    profileCI.type = XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB;
    profileCI.next = &levelCI;

    XrResult r = xrCreateFoveationProfileFB_(session_, &profileCI, &foveationProfile_);
    if (XR_FAILED(r)) {
        XR_LOGE("Failed to create foveation profile: %d", (int)r);
        return;
    }

    // Apply to both eye swapchains
    for (int eye = 0; eye < 2; eye++) {
        if (swapchains_[eye] == XR_NULL_HANDLE) continue;
        XrSwapchainStateFoveationFB fovState{};
        fovState.type = XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB;
        fovState.profile = foveationProfile_;
        XrResult ur = xrUpdateSwapchainFB_(swapchains_[eye], (XrSwapchainStateBaseHeaderFB*)&fovState);
        if (XR_FAILED(ur)) {
            XR_LOGE("Failed to apply foveation to eye %d: %d", eye, (int)ur);
        }
    }

    XR_LOGI("Foveation set to level %d%s", level, eyeTrackedFoveation_ ? " (eye-tracked)" : "");
}
