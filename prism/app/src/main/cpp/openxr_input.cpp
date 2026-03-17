#include "openxr_input.h"
#include <cstring>
#include <vector>

#define XR_CHECK(call)                                           \
    do {                                                         \
        XrResult _r = (call);                                    \
        if (XR_FAILED(_r)) {                                     \
            LOGE("%s failed: %d", #call, (int)_r);               \
            return false;                                        \
        }                                                        \
    } while (0)

// Helper to create a typed action
static bool createAction(XrActionSet actionSet, XrAction& action,
                         const char* name, XrActionType type,
                         uint32_t numSubactions, const XrPath* subactions) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = type;
    ci.countSubactionPaths = numSubactions;
    ci.subactionPaths = subactions;
    strncpy(ci.actionName, name, XR_MAX_ACTION_NAME_SIZE);
    ci.actionName[XR_MAX_ACTION_NAME_SIZE - 1] = '\0';
    strncpy(ci.localizedActionName, name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE);
    ci.localizedActionName[XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1] = '\0';
    XrResult r = xrCreateAction(actionSet, &ci, &action);
    if (XR_FAILED(r)) {
        LOGE("xrCreateAction(%s) failed: %d", name, (int)r);
        return false;
    }
    return true;
}

bool OpenXRInput::init(JNIEnv* env, jobject activity) {
    if (!createInstance(env, activity)) return false;
    if (!getSystem()) return false;
    if (!createSession()) return false;
    if (!createActions()) return false;
    LOGI("OpenXR input initialized successfully");
    return true;
}

bool OpenXRInput::createInstance(JNIEnv* env, jobject activity) {
    // Initialize the loader
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

    // Enumerate available extensions
    uint32_t extCount = 0;
    xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
    std::vector<XrExtensionProperties> availExts(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, availExts.data());
    LOGI("Available OpenXR extensions (%u):", extCount);
    bool hasGLES = false;
    bool hasVulkan = false;
    for (auto& ext : availExts) {
        LOGI("  %s v%u", ext.extensionName, ext.extensionVersion);
        if (strcmp(ext.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0) hasGLES = true;
        if (strcmp(ext.extensionName, "XR_KHR_vulkan_enable2") == 0) hasVulkan = true;
    }
    LOGI("GLES extension: %s, Vulkan extension: %s",
         hasGLES ? "YES" : "NO", hasVulkan ? "YES" : "NO");

    // Required extensions
    std::vector<const char*> extensions = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
    };

    // Check if headless extension is available (no graphics binding needed)
    bool hasHeadless = false;
    for (auto& ext : availExts) {
        if (strcmp(ext.extensionName, "XR_MND_headless") == 0) {
            hasHeadless = true;
            break;
        }
    }
    if (hasHeadless) {
        extensions.push_back("XR_MND_headless");
        LOGI("Using XR_MND_headless for input-only session");
    } else {
        extensions.push_back(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
        LOGI("XR_MND_headless not available, using GLES binding");
    }
    headless_ = hasHeadless;

    // Time conversion for hand pose tracking
    extensions.push_back("XR_KHR_convert_timespec_time");

    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    JavaVM* vm;
    env->GetJavaVM(&vm);
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = env->NewGlobalRef(activity);

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.next = &androidInfo;
    createInfo.enabledExtensionCount = (uint32_t)extensions.size();
    createInfo.enabledExtensionNames = extensions.data();
    strncpy(createInfo.applicationInfo.applicationName, "ChloeVR",
            XR_MAX_APPLICATION_NAME_SIZE);
    createInfo.applicationInfo.applicationName[XR_MAX_APPLICATION_NAME_SIZE - 1] = '\0';
    createInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;

    XR_CHECK(xrCreateInstance(&createInfo, &instance_));

    // Look up time conversion function
    xrGetInstanceProcAddr(instance_, "xrConvertTimespecTimeToTimeKHR",
                          (PFN_xrVoidFunction*)&convertTimeToXr_);

    LOGI("OpenXR instance created");
    return true;
}

bool OpenXRInput::getSystem() {
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR_CHECK(xrGetSystem(instance_, &systemInfo, &systemId_));
    LOGI("OpenXR system acquired: %llu", (unsigned long long)systemId_);
    return true;
}

bool OpenXRInput::initEGL() {
    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(eglDisplay_, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
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
        LOGE("eglChooseConfig failed");
        return false;
    }

    EGLint contextAttribs[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    eglSurface_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pbufferAttribs);
    if (eglSurface_ == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed");
        return false;
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }

    LOGI("Minimal EGL context created for OpenXR");
    return true;
}

void OpenXRInput::shutdownEGL() {
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

bool OpenXRInput::createSession() {
    XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionInfo.systemId = systemId_;

    // Headless: no graphics binding needed
    // Non-headless: create minimal EGL context
    XrGraphicsBindingOpenGLESAndroidKHR gfxBinding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    if (headless_) {
        LOGI("Creating headless OpenXR session (no graphics binding)");
        sessionInfo.next = nullptr;
    } else {
        if (!initEGL()) {
            LOGE("Failed to create EGL context for OpenXR session");
            return false;
        }
        PFN_xrGetOpenGLESGraphicsRequirementsKHR getGLESReqs = nullptr;
        xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                              (PFN_xrVoidFunction*)&getGLESReqs);
        if (getGLESReqs) {
            XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
            getGLESReqs(instance_, systemId_, &reqs);
        }
        gfxBinding.display = eglDisplay_;
        gfxBinding.config = eglConfig_;
        gfxBinding.context = eglContext_;
        sessionInfo.next = &gfxBinding;
    }

    XrResult r = xrCreateSession(instance_, &sessionInfo, &session_);
    if (XR_FAILED(r)) {
        LOGE("xrCreateSession failed: %d", (int)r);
        return false;
    }
    LOGI("OpenXR session created with EGL binding");

    // Create reference space
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    XR_CHECK(xrCreateReferenceSpace(session_, &spaceInfo, &appSpace_));

    return true;
}

bool OpenXRInput::createActions() {
    // Create action set
    XrActionSetCreateInfo asci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strncpy(asci.actionSetName, "controller-input", XR_MAX_ACTION_SET_NAME_SIZE);
    asci.actionSetName[XR_MAX_ACTION_SET_NAME_SIZE - 1] = '\0';
    strncpy(asci.localizedActionSetName, "Controller Input",
            XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE);
    asci.localizedActionSetName[XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1] = '\0';
    asci.priority = 0;
    XR_CHECK(xrCreateActionSet(instance_, &asci, &actionSet_));

    // Hand subaction paths
    xrStringToPath(instance_, "/user/hand/left", &handPaths_[0]);
    xrStringToPath(instance_, "/user/hand/right", &handPaths_[1]);

    // Create all actions
    createAction(actionSet_, thumbstickAction_, "thumbstick",
                 XR_ACTION_TYPE_VECTOR2F_INPUT, 2, handPaths_);
    createAction(actionSet_, triggerAction_, "trigger",
                 XR_ACTION_TYPE_FLOAT_INPUT, 2, handPaths_);
    createAction(actionSet_, squeezeAction_, "squeeze",
                 XR_ACTION_TYPE_FLOAT_INPUT, 2, handPaths_);
    createAction(actionSet_, thumbstickClickAction_, "thumbstick-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, aClickAction_, "a-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, bClickAction_, "b-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, xClickAction_, "x-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, yClickAction_, "y-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, menuClickAction_, "menu-click",
                 XR_ACTION_TYPE_BOOLEAN_INPUT, 2, handPaths_);
    createAction(actionSet_, gripPoseAction_, "grip-pose",
                 XR_ACTION_TYPE_POSE_INPUT, 2, handPaths_);
    createAction(actionSet_, aimPoseAction_, "aim-pose",
                 XR_ACTION_TYPE_POSE_INPUT, 2, handPaths_);

    // Suggest bindings for Oculus Touch Controller (Galaxy XR uses this profile)
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
    XR_CHECK(xrSuggestInteractionProfileBindings(instance_, &suggested));

    // Attach action set to session
    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet_;
    XR_CHECK(xrAttachSessionActionSets(session_, &attachInfo));

    // Create hand action spaces for pose tracking (grip + aim)
    for (int hand = 0; hand < 2; hand++) {
        XrActionSpaceCreateInfo spaceCI{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        spaceCI.action = gripPoseAction_;
        spaceCI.subactionPath = handPaths_[hand];
        spaceCI.poseInActionSpace = {{0, 0, 0, 1}, {0, 0, 0}};
        XR_CHECK(xrCreateActionSpace(session_, &spaceCI, &handSpaces_[hand]));

        spaceCI.action = aimPoseAction_;
        XR_CHECK(xrCreateActionSpace(session_, &spaceCI, &aimSpaces_[hand]));
    }

    LOGI("OpenXR actions created and attached");
    return true;
}

bool OpenXRInput::poll(ControllerState& state) {
    if (!session_) return false;

    memset(&state, 0, sizeof(state));

    // Process events (need to handle session state changes)
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* stateEvent = (XrEventDataSessionStateChanged*)&event;
            handleSessionStateChange(stateEvent->state);
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }

    if (!sessionReady_) return false;

    // Sync actions
    XrActiveActionSet activeAS{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeAS;
    XrResult r = xrSyncActions(session_, &syncInfo);
    if (XR_FAILED(r)) return false;

    XrActionStateGetInfo getInfo{XR_TYPE_ACTION_STATE_GET_INFO};

    // Thumbstick (Vector2f)
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = thumbstickAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateVector2f v2{XR_TYPE_ACTION_STATE_VECTOR2F};
        if (XR_SUCCEEDED(xrGetActionStateVector2f(session_, &getInfo, &v2)) && v2.isActive) {
            state.thumbstickX[hand] = v2.currentState.x;
            state.thumbstickY[hand] = v2.currentState.y;
        }
    }

    // Trigger (Float)
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = triggerAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
        if (XR_SUCCEEDED(xrGetActionStateFloat(session_, &getInfo, &f)) && f.isActive) {
            state.trigger[hand] = f.currentState;
        }
    }

    // Squeeze/Grip (Float)
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = squeezeAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
        if (XR_SUCCEEDED(xrGetActionStateFloat(session_, &getInfo, &f)) && f.isActive) {
            state.squeeze[hand] = f.currentState;
        }
    }

    // Thumbstick click
    for (int hand = 0; hand < 2; hand++) {
        getInfo.action = thumbstickClickAction_;
        getInfo.subactionPath = handPaths_[hand];
        XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &b)) && b.isActive) {
            state.thumbstickClick[hand] = b.currentState ? 1.0f : 0.0f;
        }
    }

    // A button (right hand)
    getInfo.action = aClickAction_;
    getInfo.subactionPath = handPaths_[1];
    XrActionStateBoolean ab{XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.aClick = ab.currentState ? 1.0f : 0.0f;

    // B button (right hand)
    getInfo.action = bClickAction_;
    getInfo.subactionPath = handPaths_[1];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.bClick = ab.currentState ? 1.0f : 0.0f;

    // X button (left hand)
    getInfo.action = xClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.xClick = ab.currentState ? 1.0f : 0.0f;

    // Y button (left hand)
    getInfo.action = yClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.yClick = ab.currentState ? 1.0f : 0.0f;

    // Menu (left hand)
    getInfo.action = menuClickAction_;
    getInfo.subactionPath = handPaths_[0];
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &getInfo, &ab)) && ab.isActive)
        state.menuClick = ab.currentState ? 1.0f : 0.0f;

    // Hand poses — locate relative to app (local) space
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

    static int logCounter = 0;
    for (int hand = 0; hand < 2; hand++) {
        if (handSpaces_[hand] == XR_NULL_HANDLE) continue;
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        XrResult lr = xrLocateSpace(handSpaces_[hand], appSpace_, now, &loc);
        if (XR_SUCCEEDED(lr)) {
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
            // Debug: log hand pose every ~2 seconds
            if (logCounter % 120 == 0 && (state.trigger[hand] > 0.5f)) {
                LOGI("Hand[%d] flags=0x%llx pos=(%.3f,%.3f,%.3f) valid=%d",
                     hand, (unsigned long long)loc.locationFlags,
                     loc.pose.position.x, loc.pose.position.y, loc.pose.position.z,
                     posValid ? 1 : 0);
            }
        } else {
            if (logCounter % 120 == 0) {
                LOGE("xrLocateSpace hand[%d] failed: %d", hand, (int)lr);
            }
        }
    }
    // Aim poses
    for (int hand = 0; hand < 2; hand++) {
        if (aimSpaces_[hand] == XR_NULL_HANDLE) continue;
        XrSpaceLocation aimLoc{XR_TYPE_SPACE_LOCATION};
        XrResult ar = xrLocateSpace(aimSpaces_[hand], appSpace_, now, &aimLoc);
        if (XR_SUCCEEDED(ar)) {
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
    logCounter++;

    return true;
}

void OpenXRInput::handleSessionStateChange(XrSessionState newState) {
    LOGI("Session state changed: %d", (int)newState);
    switch (newState) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
            beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            xrBeginSession(session_, &beginInfo);
            sessionReady_ = true;
            running_ = true;
            LOGI("Session ready and begun");
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

void OpenXRInput::shutdown() {
    for (int i = 0; i < 2; i++) {
        if (handSpaces_[i] != XR_NULL_HANDLE) {
            xrDestroySpace(handSpaces_[i]);
            handSpaces_[i] = XR_NULL_HANDLE;
        }
        if (aimSpaces_[i] != XR_NULL_HANDLE) {
            xrDestroySpace(aimSpaces_[i]);
            aimSpaces_[i] = XR_NULL_HANDLE;
        }
    }
    if (appSpace_ != XR_NULL_HANDLE) {
        xrDestroySpace(appSpace_);
        appSpace_ = XR_NULL_HANDLE;
    }
    if (session_ != XR_NULL_HANDLE) {
        xrDestroySession(session_);
        session_ = XR_NULL_HANDLE;
    }
    if (instance_ != XR_NULL_HANDLE) {
        xrDestroyInstance(instance_);
        instance_ = XR_NULL_HANDLE;
    }
    sessionReady_ = false;
    running_ = false;
    shutdownEGL();
    LOGI("OpenXR shut down");
}
