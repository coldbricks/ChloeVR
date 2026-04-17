#include "xr_spatial_anchors.h"
#include <android/log.h>

#define LOG_TAG "ChloeVR-Anchors"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace chloe {

namespace {

// Helper: resolve a function pointer; returns false if missing.
template <typename PFN>
bool loadProc(XrInstance inst, const char* name, PFN& out) {
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = xrGetInstanceProcAddr(inst, name, &fn);
    if (XR_FAILED(r) || !fn) {
        ALOGE("xrGetInstanceProcAddr(%s) failed: %d", name, (int)r);
        return false;
    }
    out = reinterpret_cast<PFN>(fn);
    return true;
}

}  // namespace

bool XrSpatialAnchorManager::init(XrInstance instance, XrSession session,
                                   XrSystemId systemId, XrSpace appSpace) {
    std::lock_guard<std::mutex> lock(mu_);
    if (supported_) return true;  // already initialized
    instance_ = instance;
    session_  = session;
    systemId_ = systemId;
    appSpace_ = appSpace;

    // Load every entry point we need. Any missing ⇒ fall back to "unsupported".
    bool ok = true;
    ok &= loadProc(instance_, "xrEnumerateSpatialCapabilitiesEXT", xrEnumerateSpatialCapabilities_);
    ok &= loadProc(instance_, "xrCreateSpatialContextAsyncEXT",    xrCreateSpatialContextAsync_);
    ok &= loadProc(instance_, "xrCreateSpatialContextCompleteEXT", xrCreateSpatialContextComplete_);
    ok &= loadProc(instance_, "xrDestroySpatialContextEXT",        xrDestroySpatialContext_);
    ok &= loadProc(instance_, "xrCreateSpatialDiscoverySnapshotAsyncEXT",    xrCreateSpatialDiscoverySnapshotAsync_);
    ok &= loadProc(instance_, "xrCreateSpatialDiscoverySnapshotCompleteEXT", xrCreateSpatialDiscoverySnapshotComplete_);
    ok &= loadProc(instance_, "xrQuerySpatialComponentDataEXT",    xrQuerySpatialComponentData_);
    ok &= loadProc(instance_, "xrDestroySpatialSnapshotEXT",       xrDestroySpatialSnapshot_);
    ok &= loadProc(instance_, "xrCreateSpatialEntityFromIdEXT",    xrCreateSpatialEntityFromId_);
    ok &= loadProc(instance_, "xrDestroySpatialEntityEXT",         xrDestroySpatialEntity_);
    ok &= loadProc(instance_, "xrCreateSpatialPersistenceContextAsyncEXT",    xrCreateSpatialPersistenceContextAsync_);
    ok &= loadProc(instance_, "xrCreateSpatialPersistenceContextCompleteEXT", xrCreateSpatialPersistenceContextComplete_);
    ok &= loadProc(instance_, "xrDestroySpatialPersistenceContextEXT",        xrDestroySpatialPersistenceContext_);
    ok &= loadProc(instance_, "xrPersistSpatialEntityAsyncEXT",    xrPersistSpatialEntityAsync_);
    ok &= loadProc(instance_, "xrPersistSpatialEntityCompleteEXT", xrPersistSpatialEntityComplete_);
    ok &= loadProc(instance_, "xrUnpersistSpatialEntityAsyncEXT",  xrUnpersistSpatialEntityAsync_);
    ok &= loadProc(instance_, "xrUnpersistSpatialEntityCompleteEXT", xrUnpersistSpatialEntityComplete_);
    ok &= loadProc(instance_, "xrCreateSpatialAnchorEXT",          xrCreateSpatialAnchor_);
    ok &= loadProc(instance_, "xrPollFutureEXT",                   xrPollFuture_);

    if (!ok) {
        ALOGE("Spatial anchor extensions not available on this runtime; anchors disabled");
        supported_ = false;
        return false;
    }

    // Verify the system reports ANCHOR capability.
    uint32_t capCount = 0;
    XrResult r = xrEnumerateSpatialCapabilities_(instance_, systemId_, 0, &capCount, nullptr);
    if (XR_FAILED(r) || capCount == 0) {
        ALOGE("xrEnumerateSpatialCapabilitiesEXT count query failed: %d (count=%u)", (int)r, capCount);
        supported_ = false;
        return false;
    }
    std::vector<XrSpatialCapabilityEXT> caps(capCount);
    r = xrEnumerateSpatialCapabilities_(instance_, systemId_, capCount, &capCount, caps.data());
    if (XR_FAILED(r)) {
        ALOGE("xrEnumerateSpatialCapabilitiesEXT fetch failed: %d", (int)r);
        supported_ = false;
        return false;
    }
    bool hasAnchorCap = false;
    for (auto c : caps) {
        if (c == XR_SPATIAL_CAPABILITY_ANCHOR_EXT) hasAnchorCap = true;
    }
    if (!hasAnchorCap) {
        ALOGE("Runtime does not report XR_SPATIAL_CAPABILITY_ANCHOR_EXT; anchors disabled");
        supported_ = false;
        return false;
    }

    supported_ = true;
    ALOGI("Spatial anchor extensions loaded and capability present");

    // Kick off async spatial context creation immediately.
    XrSpatialComponentTypeEXT anchorEnabled[] = {
        XR_SPATIAL_COMPONENT_TYPE_ANCHOR_EXT,
        XR_SPATIAL_COMPONENT_TYPE_PERSISTENCE_EXT,
    };
    XrSpatialCapabilityConfigurationAnchorEXT anchorCfg = {};
    anchorCfg.type = XR_TYPE_SPATIAL_CAPABILITY_CONFIGURATION_ANCHOR_EXT;
    anchorCfg.capability = XR_SPATIAL_CAPABILITY_ANCHOR_EXT;
    anchorCfg.enabledComponentCount = 2;
    anchorCfg.enabledComponents = anchorEnabled;

    const XrSpatialCapabilityConfigurationBaseHeaderEXT* cfgs[] = {
        reinterpret_cast<const XrSpatialCapabilityConfigurationBaseHeaderEXT*>(&anchorCfg),
    };

    XrSpatialContextCreateInfoEXT ctxInfo = {};
    ctxInfo.type = XR_TYPE_SPATIAL_CONTEXT_CREATE_INFO_EXT;
    ctxInfo.capabilityConfigCount = 1;
    ctxInfo.capabilityConfigs = cfgs;

    r = xrCreateSpatialContextAsync_(session_, &ctxInfo, &pendingContextFuture_);
    if (XR_FAILED(r)) {
        ALOGE("xrCreateSpatialContextAsyncEXT failed: %d (disabling anchors)", (int)r);
        supported_ = false;
        return false;
    }
    contextInitStarted_ = true;
    ALOGI("Submitted spatial context create (future=%lld)", (long long)pendingContextFuture_);

    // Kick off async persistence context creation. Galaxy XR uses SYSTEM_MANAGED scope.
    XrSpatialPersistenceContextCreateInfoEXT perInfo = {};
    perInfo.type = XR_TYPE_SPATIAL_PERSISTENCE_CONTEXT_CREATE_INFO_EXT;
    perInfo.scope = XR_SPATIAL_PERSISTENCE_SCOPE_SYSTEM_MANAGED_EXT;
    r = xrCreateSpatialPersistenceContextAsync_(session_, &perInfo, &pendingPersistFuture_);
    if (XR_FAILED(r)) {
        ALOGE("xrCreateSpatialPersistenceContextAsyncEXT failed: %d", (int)r);
        // Non-fatal: creation still works without persistence, but persist() will fail.
        pendingPersistFuture_ = XR_NULL_FUTURE_EXT;
    } else {
        persistInitStarted_ = true;
        ALOGI("Submitted persistence context create (future=%lld)", (long long)pendingPersistFuture_);
    }

    return true;
}

bool XrSpatialAnchorManager::isReady() const {
    std::lock_guard<std::mutex> lock(mu_);
    return supported_ && contextReady_ && persistReady_;
}

void XrSpatialAnchorManager::shutdown() {
    std::lock_guard<std::mutex> lock(mu_);
    // Cancel/drop any pending futures — no explicit cancel needed per spec; they simply go stale.
    for (auto& p : pendingPersists_) {
        if (p.entity != XR_NULL_HANDLE && xrDestroySpatialEntity_) {
            xrDestroySpatialEntity_(p.entity);
        }
    }
    pendingPersists_.clear();
    pendingUnpersists_.clear();
    pendingResolves_.clear();
    createResults_.clear();
    resolveResults_.clear();
    if (persistContext_ != XR_NULL_HANDLE && xrDestroySpatialPersistenceContext_) {
        xrDestroySpatialPersistenceContext_(persistContext_);
        persistContext_ = XR_NULL_HANDLE;
    }
    if (spatialContext_ != XR_NULL_HANDLE && xrDestroySpatialContext_) {
        xrDestroySpatialContext_(spatialContext_);
        spatialContext_ = XR_NULL_HANDLE;
    }
    supported_ = false;
    contextInitStarted_ = contextReady_ = false;
    persistInitStarted_ = persistReady_ = false;
}

void XrSpatialAnchorManager::tick(XrTime time) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!supported_ || !xrPollFuture_) return;

    // 1) Poll spatial context future
    if (contextInitStarted_ && !contextReady_ && pendingContextFuture_ != XR_NULL_FUTURE_EXT) {
        XrFuturePollInfoEXT info = { XR_TYPE_FUTURE_POLL_INFO_EXT, nullptr, pendingContextFuture_ };
        XrFuturePollResultEXT res = { XR_TYPE_FUTURE_POLL_RESULT_EXT };
        XrResult r = xrPollFuture_(instance_, &info, &res);
        if (XR_SUCCEEDED(r) && res.state == XR_FUTURE_STATE_READY_EXT) {
            finishContextInit_locked();
        }
    }

    // 2) Poll persistence context future
    if (persistInitStarted_ && !persistReady_ && pendingPersistFuture_ != XR_NULL_FUTURE_EXT) {
        XrFuturePollInfoEXT info = { XR_TYPE_FUTURE_POLL_INFO_EXT, nullptr, pendingPersistFuture_ };
        XrFuturePollResultEXT res = { XR_TYPE_FUTURE_POLL_RESULT_EXT };
        XrResult r = xrPollFuture_(instance_, &info, &res);
        if (XR_SUCCEEDED(r) && res.state == XR_FUTURE_STATE_READY_EXT) {
            finishPersistInit_locked();
        }
    }

    // 3) Poll all pending persist operations
    for (auto it = pendingPersists_.begin(); it != pendingPersists_.end();) {
        XrFuturePollInfoEXT info = { XR_TYPE_FUTURE_POLL_INFO_EXT, nullptr, it->future };
        XrFuturePollResultEXT res = { XR_TYPE_FUTURE_POLL_RESULT_EXT };
        XrResult r = xrPollFuture_(instance_, &info, &res);
        if (XR_SUCCEEDED(r) && res.state == XR_FUTURE_STATE_READY_EXT) {
            handlePersistComplete_locked(*it);
            it = pendingPersists_.erase(it);
        } else {
            ++it;
        }
    }

    // 4) Poll pending unpersist ops (fire-and-forget; consume future when done)
    for (auto it = pendingUnpersists_.begin(); it != pendingUnpersists_.end();) {
        XrFuturePollInfoEXT info = { XR_TYPE_FUTURE_POLL_INFO_EXT, nullptr, *it };
        XrFuturePollResultEXT res = { XR_TYPE_FUTURE_POLL_RESULT_EXT };
        XrResult r = xrPollFuture_(instance_, &info, &res);
        if (XR_SUCCEEDED(r) && res.state == XR_FUTURE_STATE_READY_EXT) {
            if (persistContext_ != XR_NULL_HANDLE && xrUnpersistSpatialEntityComplete_) {
                XrUnpersistSpatialEntityCompletionEXT comp = {};
                comp.type = XR_TYPE_UNPERSIST_SPATIAL_ENTITY_COMPLETION_EXT;
                XrResult cr = xrUnpersistSpatialEntityComplete_(persistContext_, *it, &comp);
                if (XR_FAILED(cr)) {
                    ALOGE("xrUnpersistSpatialEntityCompleteEXT failed: %d", (int)cr);
                }
            }
            it = pendingUnpersists_.erase(it);
        } else {
            ++it;
        }
    }

    // 5) Poll pending resolve snapshots
    for (auto it = pendingResolves_.begin(); it != pendingResolves_.end();) {
        XrFuturePollInfoEXT info = { XR_TYPE_FUTURE_POLL_INFO_EXT, nullptr, it->future };
        XrFuturePollResultEXT res = { XR_TYPE_FUTURE_POLL_RESULT_EXT };
        XrResult r = xrPollFuture_(instance_, &info, &res);
        if (XR_SUCCEEDED(r) && res.state == XR_FUTURE_STATE_READY_EXT) {
            handleResolveComplete_locked(*it, time);
            it = pendingResolves_.erase(it);
        } else {
            ++it;
        }
    }
}

void XrSpatialAnchorManager::finishContextInit_locked() {
    XrCreateSpatialContextCompletionEXT comp = {};
    comp.type = XR_TYPE_CREATE_SPATIAL_CONTEXT_COMPLETION_EXT;
    XrResult r = xrCreateSpatialContextComplete_(session_, pendingContextFuture_, &comp);
    pendingContextFuture_ = XR_NULL_FUTURE_EXT;
    if (XR_FAILED(r) || XR_FAILED(comp.futureResult)) {
        ALOGE("Spatial context create complete failed: r=%d futureResult=%d",
              (int)r, (int)comp.futureResult);
        supported_ = false;
        return;
    }
    spatialContext_ = comp.spatialContext;
    contextReady_ = true;
    ALOGI("Spatial context ready: %p", (void*)spatialContext_);
}

void XrSpatialAnchorManager::finishPersistInit_locked() {
    XrCreateSpatialPersistenceContextCompletionEXT comp = {};
    comp.type = XR_TYPE_CREATE_SPATIAL_PERSISTENCE_CONTEXT_COMPLETION_EXT;
    XrResult r = xrCreateSpatialPersistenceContextComplete_(session_, pendingPersistFuture_, &comp);
    pendingPersistFuture_ = XR_NULL_FUTURE_EXT;
    if (XR_FAILED(r) || XR_FAILED(comp.futureResult)) {
        ALOGE("Persistence context create failed: r=%d futureResult=%d createResult=%d",
              (int)r, (int)comp.futureResult, (int)comp.createResult);
        return; // anchors still work for current session, just no persistence
    }
    persistContext_ = comp.persistenceContext;
    persistReady_ = true;
    ALOGI("Persistence context ready: %p", (void*)persistContext_);
}

int32_t XrSpatialAnchorManager::submitCreate(const XrPosef& pose, XrTime time) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!supported_ || !contextReady_ || spatialContext_ == XR_NULL_HANDLE) return -1;
    if (!persistReady_ || persistContext_ == XR_NULL_HANDLE) return -1;

    XrSpatialAnchorCreateInfoEXT info = {};
    info.type = XR_TYPE_SPATIAL_ANCHOR_CREATE_INFO_EXT;
    info.baseSpace = appSpace_;
    info.time = time;
    info.pose = pose;

    XrSpatialEntityIdEXT entityId = XR_NULL_SPATIAL_ENTITY_ID_EXT;
    XrSpatialEntityEXT   entity   = XR_NULL_HANDLE;
    XrResult r = xrCreateSpatialAnchor_(spatialContext_, &info, &entityId, &entity);
    if (XR_FAILED(r) || entityId == XR_NULL_SPATIAL_ENTITY_ID_EXT) {
        ALOGE("xrCreateSpatialAnchorEXT failed: %d", (int)r);
        return -1;
    }

    // Submit a persist request for this entity immediately.
    XrSpatialEntityPersistInfoEXT persistInfo = {};
    persistInfo.type = XR_TYPE_SPATIAL_ENTITY_PERSIST_INFO_EXT;
    persistInfo.spatialContext = spatialContext_;
    persistInfo.spatialEntityId = entityId;

    XrFutureEXT future = XR_NULL_FUTURE_EXT;
    r = xrPersistSpatialEntityAsync_(persistContext_, &persistInfo, &future);
    if (XR_FAILED(r) || future == XR_NULL_FUTURE_EXT) {
        ALOGE("xrPersistSpatialEntityAsyncEXT failed: %d", (int)r);
        if (entity != XR_NULL_HANDLE) xrDestroySpatialEntity_(entity);
        return -1;
    }

    PendingPersist pp;
    pp.future = future;
    pp.entity = entity;
    pp.clientHandle = nextClientHandle_++;
    pendingPersists_.push_back(pp);
    ALOGI("Submitted persist for entityId=%llu future=%lld handle=%d",
          (unsigned long long)entityId, (long long)future, pp.clientHandle);
    return pp.clientHandle;
}

void XrSpatialAnchorManager::handlePersistComplete_locked(PendingPersist& p) {
    XrPersistSpatialEntityCompletionEXT comp = {};
    comp.type = XR_TYPE_PERSIST_SPATIAL_ENTITY_COMPLETION_EXT;
    XrResult r = xrPersistSpatialEntityComplete_(persistContext_, p.future, &comp);
    AnchorCreateResult out = {};
    out.clientHandle = p.clientHandle;
    if (XR_SUCCEEDED(r) && XR_SUCCEEDED(comp.futureResult) &&
        comp.persistResult == XR_SPATIAL_PERSISTENCE_CONTEXT_RESULT_SUCCESS_EXT) {
        out.success = true;
        std::memcpy(out.uuid.bytes, comp.persistUuid.data, XR_UUID_SIZE);
        ALOGI("Persist success handle=%d", p.clientHandle);
    } else {
        ALOGE("Persist failed handle=%d r=%d fr=%d pr=%d",
              p.clientHandle, (int)r, (int)comp.futureResult, (int)comp.persistResult);
        out.success = false;
    }
    createResults_.push_back(out);
    // Entity is no longer needed from our side once persisted; clean up.
    if (p.entity != XR_NULL_HANDLE && xrDestroySpatialEntity_) {
        xrDestroySpatialEntity_(p.entity);
        p.entity = XR_NULL_HANDLE;
    }
}

bool XrSpatialAnchorManager::popCreateResult(AnchorCreateResult& out) {
    std::lock_guard<std::mutex> lock(mu_);
    if (createResults_.empty()) return false;
    out = createResults_.front();
    createResults_.pop_front();
    return true;
}

void XrSpatialAnchorManager::submitResolve(const AnchorUuid* uuids, uint32_t count) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!supported_ || !contextReady_ || spatialContext_ == XR_NULL_HANDLE || count == 0) return;
    if (!persistReady_) return;  // need persistence context before we can filter by persisted UUID

    PendingResolve res;
    res.uuids.assign(uuids, uuids + count);
    res.xrUuids.resize(count);
    for (uint32_t i = 0; i < count; i++) {
        std::memcpy(res.xrUuids[i].data, uuids[i].bytes, XR_UUID_SIZE);
    }
    res.componentTypes = {
        XR_SPATIAL_COMPONENT_TYPE_ANCHOR_EXT,
        XR_SPATIAL_COMPONENT_TYPE_PERSISTENCE_EXT,
    };

    // Filter chain: snapshot createInfo → UUID filter
    res.filter = {};
    res.filter.type = XR_TYPE_SPATIAL_DISCOVERY_PERSISTENCE_UUID_FILTER_EXT;
    res.filter.persistedUuidCount = (uint32_t)res.xrUuids.size();
    res.filter.persistedUuids = res.xrUuids.data();

    res.createInfo = {};
    res.createInfo.type = XR_TYPE_SPATIAL_DISCOVERY_SNAPSHOT_CREATE_INFO_EXT;
    res.createInfo.next = &res.filter;
    res.createInfo.componentTypeCount = (uint32_t)res.componentTypes.size();
    res.createInfo.componentTypes = res.componentTypes.data();

    // Push to deque FIRST so the backing storage of createInfo/filter stays live by reference.
    pendingResolves_.push_back(std::move(res));
    auto& stored = pendingResolves_.back();
    // Fix pointers now that the vectors have moved into the deque entry.
    stored.filter.persistedUuids = stored.xrUuids.data();
    stored.filter.persistedUuidCount = (uint32_t)stored.xrUuids.size();
    stored.createInfo.next = &stored.filter;
    stored.createInfo.componentTypes = stored.componentTypes.data();

    XrResult r = xrCreateSpatialDiscoverySnapshotAsync_(
        spatialContext_, &stored.createInfo, &stored.future);
    if (XR_FAILED(r) || stored.future == XR_NULL_FUTURE_EXT) {
        ALOGE("xrCreateSpatialDiscoverySnapshotAsyncEXT failed: %d", (int)r);
        // Emit "not found" results for every UUID so caller can clean up.
        for (auto& u : stored.uuids) {
            AnchorResolveResult rr{};
            rr.uuid = u;
            rr.valid = false;
            resolveResults_.push_back(rr);
        }
        pendingResolves_.pop_back();
        return;
    }
    ALOGI("Submitted resolve for %u UUIDs (future=%lld)", count, (long long)stored.future);
}

void XrSpatialAnchorManager::handleResolveComplete_locked(PendingResolve& r, XrTime time) {
    XrCreateSpatialDiscoverySnapshotCompletionInfoEXT info = {};
    info.type = XR_TYPE_CREATE_SPATIAL_DISCOVERY_SNAPSHOT_COMPLETION_INFO_EXT;
    info.baseSpace = appSpace_;
    info.time = time;
    info.future = r.future;

    XrCreateSpatialDiscoverySnapshotCompletionEXT comp = {};
    comp.type = XR_TYPE_CREATE_SPATIAL_DISCOVERY_SNAPSHOT_COMPLETION_EXT;
    XrResult cr = xrCreateSpatialDiscoverySnapshotComplete_(spatialContext_, &info, &comp);
    if (XR_FAILED(cr) || XR_FAILED(comp.futureResult) || comp.snapshot == XR_NULL_HANDLE) {
        ALOGE("Discovery snapshot complete failed: cr=%d fr=%d", (int)cr, (int)comp.futureResult);
        // Emit not-found for every UUID so the app can clean up stale entries.
        for (auto& u : r.uuids) {
            AnchorResolveResult rr{};
            rr.uuid = u;
            rr.valid = false;
            resolveResults_.push_back(rr);
        }
        return;
    }

    // First pass: query how many entities match (same components we requested).
    XrSpatialComponentDataQueryConditionEXT cond = {};
    cond.type = XR_TYPE_SPATIAL_COMPONENT_DATA_QUERY_CONDITION_EXT;
    cond.componentTypeCount = (uint32_t)r.componentTypes.size();
    cond.componentTypes = r.componentTypes.data();

    // Sizing pass: ask for capacityInput=0 to learn counts.
    XrSpatialComponentDataQueryResultEXT sizingResult = {};
    sizingResult.type = XR_TYPE_SPATIAL_COMPONENT_DATA_QUERY_RESULT_EXT;
    XrResult qr = xrQuerySpatialComponentData_(comp.snapshot, &cond, &sizingResult);
    uint32_t nEntities = sizingResult.entityIdCountOutput;
    if (XR_FAILED(qr) || nEntities == 0) {
        // No entities — everything requested is gone.
        for (auto& u : r.uuids) {
            AnchorResolveResult rr{};
            rr.uuid = u;
            rr.valid = false;
            resolveResults_.push_back(rr);
        }
        if (xrDestroySpatialSnapshot_) xrDestroySpatialSnapshot_(comp.snapshot);
        return;
    }

    std::vector<XrSpatialEntityIdEXT> ids(nEntities);
    std::vector<XrSpatialEntityTrackingStateEXT> states(nEntities);
    std::vector<XrPosef> poses(nEntities);
    std::vector<XrSpatialPersistenceDataEXT> persistData(nEntities);

    // Second pass: fetch results with chained per-component lists.
    XrSpatialComponentAnchorListEXT anchorList = {};
    anchorList.type = XR_TYPE_SPATIAL_COMPONENT_ANCHOR_LIST_EXT;
    anchorList.locationCount = nEntities;
    anchorList.locations = poses.data();

    XrSpatialComponentPersistenceListEXT persistList = {};
    persistList.type = XR_TYPE_SPATIAL_COMPONENT_PERSISTENCE_LIST_EXT;
    persistList.persistDataCount = nEntities;
    persistList.persistData = persistData.data();
    persistList.next = &anchorList;

    XrSpatialComponentDataQueryResultEXT result = {};
    result.type = XR_TYPE_SPATIAL_COMPONENT_DATA_QUERY_RESULT_EXT;
    result.next = &persistList;
    result.entityIdCapacityInput = nEntities;
    result.entityIds = ids.data();
    result.entityStateCapacityInput = nEntities;
    result.entityStates = states.data();

    qr = xrQuerySpatialComponentData_(comp.snapshot, &cond, &result);
    if (XR_FAILED(qr)) {
        ALOGE("xrQuerySpatialComponentDataEXT data pass failed: %d", (int)qr);
        for (auto& u : r.uuids) {
            AnchorResolveResult rr{};
            rr.uuid = u;
            rr.valid = false;
            resolveResults_.push_back(rr);
        }
        if (xrDestroySpatialSnapshot_) xrDestroySpatialSnapshot_(comp.snapshot);
        return;
    }

    // Build UUID → (pose, state) map for fast lookup.
    std::vector<bool> matched(r.uuids.size(), false);
    for (uint32_t i = 0; i < nEntities; i++) {
        if (persistData[i].persistState != XR_SPATIAL_PERSISTENCE_STATE_LOADED_EXT) continue;
        AnchorUuid foundUuid;
        std::memcpy(foundUuid.bytes, persistData[i].persistUuid.data, XR_UUID_SIZE);
        bool tracking = (states[i] == XR_SPATIAL_ENTITY_TRACKING_STATE_TRACKING_EXT);
        // Match to requested list
        for (size_t j = 0; j < r.uuids.size(); j++) {
            if (matched[j]) continue;
            if (r.uuids[j] == foundUuid) {
                AnchorResolveResult rr{};
                rr.uuid = foundUuid;
                rr.valid = tracking;
                rr.pose = poses[i];
                resolveResults_.push_back(rr);
                matched[j] = true;
                break;
            }
        }
    }
    // Emit not-found results for any unmatched UUIDs.
    for (size_t j = 0; j < r.uuids.size(); j++) {
        if (matched[j]) continue;
        AnchorResolveResult rr{};
        rr.uuid = r.uuids[j];
        rr.valid = false;
        resolveResults_.push_back(rr);
    }

    if (xrDestroySpatialSnapshot_) xrDestroySpatialSnapshot_(comp.snapshot);
}

bool XrSpatialAnchorManager::popResolveResult(AnchorResolveResult& out) {
    std::lock_guard<std::mutex> lock(mu_);
    if (resolveResults_.empty()) return false;
    out = resolveResults_.front();
    resolveResults_.pop_front();
    return true;
}

void XrSpatialAnchorManager::submitUnpersist(const AnchorUuid& uuid) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!supported_ || !persistReady_ || persistContext_ == XR_NULL_HANDLE) return;

    XrSpatialEntityUnpersistInfoEXT info = {};
    info.type = XR_TYPE_SPATIAL_ENTITY_UNPERSIST_INFO_EXT; // guard: may not exist on this runtime
    std::memcpy(info.persistUuid.data, uuid.bytes, XR_UUID_SIZE);

    XrFutureEXT future = XR_NULL_FUTURE_EXT;
    XrResult r = xrUnpersistSpatialEntityAsync_(persistContext_, &info, &future);
    if (XR_FAILED(r) || future == XR_NULL_FUTURE_EXT) {
        ALOGE("xrUnpersistSpatialEntityAsyncEXT failed: %d", (int)r);
        return;
    }
    pendingUnpersists_.push_back(future);
}

} // namespace chloe
