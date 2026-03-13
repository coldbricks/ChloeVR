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

    // Optional: light estimation
    bool hasLightEst = false;
    for (auto& ext : availExts) {
        if (strcmp(ext.extensionName, XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME) == 0) {
            hasLightEst = true;
            break;
        }
    }
    if (hasLightEst) {
        extensions.push_back(XR_ANDROID_LIGHT_ESTIMATION_EXTENSION_NAME);
        XR_LOGI("Light estimation extension available, enabling");
    } else {
        XR_LOGI("Light estimation extension not available");
    }
    lightEstimationSupported_ = hasLightEst;

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
        // Position: 1.2m in front, at eye level (~1.6m up), 0.6m wide
        quadLayer.pose = {{0, 0, 0, 1}, {0, 1.6f, -1.2f}};
        quadLayer.size = {0.6f, 0.6f * (float)uiHeight_ / (float)uiWidth_};
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
    XrLightEstimateGetInfoANDROID getInfo;
    getInfo.type = (XrStructureType)XR_TYPE_LIGHT_ESTIMATE_GET_INFO_ANDROID;
    getInfo.next = nullptr;
    getInfo.time = now;
    getInfo.baseSpace = appSpace_;

    // Chain: directional → ambient → root (SH disabled until struct layout verified)
    XrDirectionalLightANDROID dirLight = {};
    dirLight.type = (XrStructureType)XR_TYPE_DIRECTIONAL_LIGHT_ANDROID;
    dirLight.next = nullptr;
    dirLight.state = XR_LIGHT_ESTIMATION_STATE_INVALID_ANDROID;

    XrAmbientLightANDROID ambLight = {};
    ambLight.type = (XrStructureType)XR_TYPE_AMBIENT_LIGHT_ANDROID;
    ambLight.next = &dirLight;
    ambLight.state = XR_LIGHT_ESTIMATION_STATE_INVALID_ANDROID;

    XrLightEstimateANDROID lightEst = {};
    lightEst.type = (XrStructureType)XR_TYPE_LIGHT_ESTIMATE_ANDROID;
    lightEst.next = &ambLight;

    XrResult r = xrGetLightEstimate_(lightEstimator_, &getInfo, &lightEst);
    if (XR_FAILED(r)) {
        static int errCount = 0;
        if (errCount++ < 5) XR_LOGE("xrGetLightEstimateANDROID failed: %d", (int)r);
        return false;
    }

    // Ambient
    if (ambLight.state == XR_LIGHT_ESTIMATION_STATE_VALID_ANDROID) {
        estimate.ambientR = ambLight.intensity.r;
        estimate.ambientG = ambLight.intensity.g;
        estimate.ambientB = ambLight.intensity.b;
        estimate.colorCorrR = ambLight.colorCorrection.r;
        estimate.colorCorrG = ambLight.colorCorrection.g;
        estimate.colorCorrB = ambLight.colorCorrection.b;
        estimate.valid = true;
    }

    // Directional
    if (dirLight.state == XR_LIGHT_ESTIMATION_STATE_VALID_ANDROID) {
        estimate.dirIntensityR = dirLight.intensity.r;
        estimate.dirIntensityG = dirLight.intensity.g;
        estimate.dirIntensityB = dirLight.intensity.b;
        estimate.dirX = dirLight.direction.x;
        estimate.dirY = dirLight.direction.y;
        estimate.dirZ = dirLight.direction.z;
        estimate.valid = true;
    }

    return estimate.valid;
}
