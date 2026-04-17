#pragma once
//
// ChloeVR — Persistent spatial anchors
//
// Targets XR_EXT_spatial_entity + XR_EXT_spatial_anchor + XR_EXT_spatial_persistence
// + XR_EXT_spatial_persistence_operations + XR_EXT_future
//
// Galaxy XR exposes the EXT family (verified in xr_renderer.cpp extension
// enumeration log). All entry points must be loaded via xrGetInstanceProcAddr.
//
// Flow (happy path):
//   1) createSpatialContextAsyncEXT(ANCHOR + PERSISTENCE caps)  → future
//   2) createSpatialPersistenceContextAsyncEXT(SYSTEM_MANAGED)  → future
//   3) poll futures every frame; when both are ready, manager is "ready"
//   4) on commit: xrCreateSpatialAnchorEXT (sync) → entityId; persistAsync → future;
//      on future.ready: record persistUuid ↔ entityId and expose via poll
//   5) on restore: createSpatialDiscoverySnapshotAsync filtered by persistedUuids;
//      complete → xrQuerySpatialComponentDataEXT with PERSISTENCE + ANCHOR lists;
//      extract (uuid, pose) pairs
//
// All public entry points are thread-safe (internal mutex). The tick() method must
// be called from the render thread each frame after xrWaitFrame (it needs a valid
// predictedDisplayTime and appSpace) but before xrSubmitFrame.
//

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <mutex>
#include <deque>
#include <vector>
#include <array>
#include <cstdint>
#include <cstring>

namespace chloe {

// 16-byte UUID wrapper (mirrors XrUuid.data layout).
struct AnchorUuid {
    uint8_t bytes[XR_UUID_SIZE] = {};

    bool isZero() const {
        for (int i = 0; i < XR_UUID_SIZE; i++) if (bytes[i] != 0) return false;
        return true;
    }
    bool operator==(const AnchorUuid& o) const {
        return memcmp(bytes, o.bytes, XR_UUID_SIZE) == 0;
    }
};

// Event emitted to Kotlin when anchor creation + persist completes.
struct AnchorCreateResult {
    int32_t  clientHandle = -1;     // opaque tag from submitCreate()
    bool     success = false;
    AnchorUuid uuid;
};

// Event emitted when a persisted anchor is resolved (pose known in appSpace).
struct AnchorResolveResult {
    AnchorUuid uuid;
    bool    valid = false;          // true => pose is meaningful; false => anchor lost
    XrPosef pose = {{0,0,0,1},{0,0,0}};
};

class XrSpatialAnchorManager {
public:
    // Call once, during XrRenderer::init() after session is READY.
    // Returns false if any required extension is missing — caller should treat that as
    // "anchors unavailable" and fall back to session-local poses.
    bool init(XrInstance instance, XrSession session, XrSystemId systemId, XrSpace appSpace);
    void shutdown();

    // Call every frame from the render thread, right before xrSubmitFrame.
    // Pumps all outstanding futures. `time` is the predicted display time of the current frame.
    void tick(XrTime time);

    // Is the manager fully initialized (both spatial context + persistence context ready)?
    bool isReady() const;

    // Lifetime-lookup: returns true once init() has been called and required extensions
    // were enabled. If false, caller must skip ALL other calls — they are no-ops.
    bool isSupported() const { return supported_; }

    // ── Creation (committed on grip release) ────────────────────────────
    // Creates a spatial anchor at the given world pose (in appSpace) at `time`,
    // immediately submits a persist request.
    // Returns a clientHandle (monotonic int) the caller uses to correlate the
    // async result produced later by popCreateResult().
    // Returns -1 if the manager isn't ready yet (caller should retry later).
    int32_t submitCreate(const XrPosef& pose, XrTime time);

    // Non-blocking — returns true and fills `out` if a completed create is pending.
    bool popCreateResult(AnchorCreateResult& out);

    // ── Persisted anchor resolution (app launch) ────────────────────────
    // Submit a query for the given persisted UUIDs. The manager creates a discovery
    // snapshot filtered to those UUIDs.
    // Multiple calls queue additional batches; call is idempotent w.r.t. duplicates.
    void submitResolve(const AnchorUuid* uuids, uint32_t count);

    // Non-blocking — returns true and fills `out` for each resolved (or failed) anchor.
    bool popResolveResult(AnchorResolveResult& out);

    // ── Deletion ────────────────────────────────────────────────────────
    void submitUnpersist(const AnchorUuid& uuid);
    // No result channel needed — fire-and-forget from the app's perspective.

private:
    // Extension entry points (loaded in init()).
    PFN_xrEnumerateSpatialCapabilitiesEXT            xrEnumerateSpatialCapabilities_ = nullptr;
    PFN_xrCreateSpatialContextAsyncEXT               xrCreateSpatialContextAsync_    = nullptr;
    PFN_xrCreateSpatialContextCompleteEXT            xrCreateSpatialContextComplete_ = nullptr;
    PFN_xrDestroySpatialContextEXT                   xrDestroySpatialContext_        = nullptr;
    PFN_xrCreateSpatialDiscoverySnapshotAsyncEXT     xrCreateSpatialDiscoverySnapshotAsync_    = nullptr;
    PFN_xrCreateSpatialDiscoverySnapshotCompleteEXT  xrCreateSpatialDiscoverySnapshotComplete_ = nullptr;
    PFN_xrQuerySpatialComponentDataEXT               xrQuerySpatialComponentData_    = nullptr;
    PFN_xrDestroySpatialSnapshotEXT                  xrDestroySpatialSnapshot_       = nullptr;
    PFN_xrCreateSpatialEntityFromIdEXT               xrCreateSpatialEntityFromId_    = nullptr;
    PFN_xrDestroySpatialEntityEXT                    xrDestroySpatialEntity_         = nullptr;
    PFN_xrCreateSpatialPersistenceContextAsyncEXT    xrCreateSpatialPersistenceContextAsync_    = nullptr;
    PFN_xrCreateSpatialPersistenceContextCompleteEXT xrCreateSpatialPersistenceContextComplete_ = nullptr;
    PFN_xrDestroySpatialPersistenceContextEXT        xrDestroySpatialPersistenceContext_        = nullptr;
    PFN_xrPersistSpatialEntityAsyncEXT               xrPersistSpatialEntityAsync_    = nullptr;
    PFN_xrPersistSpatialEntityCompleteEXT            xrPersistSpatialEntityComplete_ = nullptr;
    PFN_xrUnpersistSpatialEntityAsyncEXT             xrUnpersistSpatialEntityAsync_  = nullptr;
    PFN_xrUnpersistSpatialEntityCompleteEXT          xrUnpersistSpatialEntityComplete_ = nullptr;

    // Common helpers (also needed to poll futures and create anchors in the spatial context)
    // xrCreateSpatialAnchorEXT + xrPollFutureEXT
    typedef XrResult (XRAPI_PTR *PFN_xrCreateSpatialAnchor)(
        XrSpatialContextEXT, const XrSpatialAnchorCreateInfoEXT*,
        XrSpatialEntityIdEXT*, XrSpatialEntityEXT*);
    PFN_xrCreateSpatialAnchor xrCreateSpatialAnchor_ = nullptr;
    PFN_xrPollFutureEXT       xrPollFuture_          = nullptr;

    XrInstance instance_  = XR_NULL_HANDLE;
    XrSession  session_   = XR_NULL_HANDLE;
    XrSystemId systemId_  = XR_NULL_SYSTEM_ID;
    XrSpace    appSpace_  = XR_NULL_HANDLE;

    bool supported_            = false; // extensions + system caps present
    bool contextInitStarted_   = false;
    bool contextReady_         = false;
    bool persistInitStarted_   = false;
    bool persistReady_         = false;

    XrSpatialContextEXT            spatialContext_  = XR_NULL_HANDLE;
    XrSpatialPersistenceContextEXT persistContext_  = XR_NULL_HANDLE;
    XrFutureEXT                    pendingContextFuture_    = XR_NULL_FUTURE_EXT;
    XrFutureEXT                    pendingPersistFuture_    = XR_NULL_FUTURE_EXT;

    // Pending persist operations: entity created, awaiting persist future completion.
    struct PendingPersist {
        XrFutureEXT      future = XR_NULL_FUTURE_EXT;
        int32_t          clientHandle = -1;
        XrSpatialEntityEXT entity = XR_NULL_HANDLE;
    };
    std::deque<PendingPersist> pendingPersists_;

    // Pending unpersist operations (fire-and-forget; we clean up the future when done).
    std::deque<XrFutureEXT> pendingUnpersists_;

    // Pending resolve batches: discovery snapshot in flight, will yield pose list on complete.
    struct PendingResolve {
        XrFutureEXT           future = XR_NULL_FUTURE_EXT;
        std::vector<AnchorUuid> uuids;     // echoed back alongside pose list
        std::vector<XrUuid>     xrUuids;   // the actual native filter payload; ref-stable
        // Scratch — kept alive until completion + query are done.
        XrSpatialDiscoveryPersistenceUuidFilterEXT filter;
        XrSpatialDiscoverySnapshotCreateInfoEXT    createInfo;
        std::array<XrSpatialComponentTypeEXT, 2>   componentTypes;
    };
    std::deque<PendingResolve> pendingResolves_;

    // Completed results, awaiting pop by Kotlin.
    std::deque<AnchorCreateResult>  createResults_;
    std::deque<AnchorResolveResult> resolveResults_;

    int32_t nextClientHandle_ = 1;

    mutable std::mutex mu_;

    // Called under mu_ when contextFuture is ready.
    void finishContextInit_locked();
    void finishPersistInit_locked();
    // Called under mu_ to handle a completed persist future.
    void handlePersistComplete_locked(PendingPersist& p);
    // Called under mu_ to handle a completed resolve (discovery) future.
    void handleResolveComplete_locked(PendingResolve& r, XrTime time);
};

} // namespace chloe
