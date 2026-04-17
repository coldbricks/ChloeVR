// xr_scene_occlusion.h — real-world scene occlusion for placed 3D models.
//
// Renders detected scene geometry (plane polygons) as depth-only triangles
// before the main color pass, so virtual models get z-tested against the
// real world: portions behind the bed / table / wall are discarded and the
// passthrough camera shows through instead.
//
// Tiering (from highest fidelity to lowest):
//   1. XR_MSFT_scene_understanding / XR_META_scene_mesh — triangle mesh
//      (NOT available on Samsung Galaxy XR at the time of writing; the
//      `XR_ANDROID_trackables` extension only exposes `XR_TRACKABLE_TYPE_PLANE_ANDROID`).
//   2. Polygon-vertex planes from `XR_ANDROID_trackables` — what we ship.
//      Each plane provides up to 32 vertices in a triangle fan approximating
//      the detected surface contour (L-shaped desks, curved counters, etc.).
//   3. Rectangular plane quads from extent — fallback when no polygon vertices.
//
// All resources are pre-allocated in init(); the per-frame render() path does
// zero heap allocation to respect the repo's zero-alloc render loop invariant.
#pragma once

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace chloe_vr {

class SceneOcclusion {
public:
    static constexpr int MAX_PLANES = 64;
    static constexpr int MAX_VERTICES_PER_PLANE = 32;
    static constexpr int POSITIONS_PER_VERTEX = 3;  // x,y,z

    SceneOcclusion() = default;
    ~SceneOcclusion() = default;

    // Create the GL program, VBOs, and VAO. Must be called on a thread that
    // owns the shared EGL context (the render thread).
    // Returns true on success.
    bool init();

    // Release GL resources. Must be called on the GL thread.
    void shutdown();

    // True once init() has succeeded and before shutdown().
    bool isReady() const { return ready_; }

    // Enable or disable the depth-only pass. When disabled, renderDepthOnly()
    // is a no-op.
    void setEnabled(bool enabled) { enabled_.store(enabled); }
    bool isEnabled() const { return enabled_.load(); }

    // Upload a fresh batch of plane polygons.
    //
    // `flat` layout, row-major — one row per plane:
    //   [px, py, pz,            // plane centroid (world space)
    //    qx, qy, qz, qw,        // plane orientation (world space)
    //    ex, ey,                // half-extents (plane-local X/Z)
    //    label,                 // 0=unknown,1=wall,2=floor,3=ceiling,4=table
    //    vertexCount,           // polygon vertex count (0 ... 32)
    //    v0x, v0y,              // 2D polygon vertices in plane-local space
    //    v1x, v1y, ...          // (x,y) pairs; exactly MAX_VERTICES_PER_PLANE*2 reserved
    //   ]
    // Stride: 11 + MAX_VERTICES_PER_PLANE*2 = 75 floats per plane.
    //
    // The plane labels let us optionally skip floor/ceiling surfaces (label 2/3),
    // which otherwise cause unwanted culling (e.g. your model's shoes get
    // occluded by the very floor they're standing on).
    void updateGeometry(const float* planeData, int planeCount, float gridHeight);

    // Render all uploaded plane polygons as depth-only triangles with the
    // supplied view/projection matrices (column-major, same layout as the
    // main pass). Writes to the currently bound framebuffer's depth
    // attachment, leaves color channels untouched.
    //
    // Call *before* the main color pass for a given eye. Must be called on
    // the thread that owns the GL context.
    //
    // Returns false and skips the pass if:
    //   - init() was never called or failed
    //   - occlusion is disabled
    //   - no geometry has been uploaded yet (empty-geometry safeguard)
    //   - the GL program failed to compile
    bool renderDepthOnly(const float* projection, const float* viewMatrix);

    // Diagnostics.
    int uploadedPlaneCount() const { return uploadedPlaneCount_; }
    int totalUploadedVertices() const { return totalUploadedVertices_; }
    int totalUploadedTriangles() const { return totalUploadedTriangles_; }
    int frameRenderCount() const { return frameRenderCount_; }

private:
    struct PlaneBatch {
        // First-vertex offset into the pre-allocated scratchVerts_ buffer.
        int vertexStart = 0;
        // Triangle count in this plane's strip (triangle-fan topology).
        int triangleCount = 0;
        // Precomputed local→world transform (column-major 4x4).
        float modelMatrix[16] = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
        };
    };

    bool compileProgram();
    void releaseGl();
    // Upload helper: converts a single plane's polygon into triangle fan
    // vertices inside scratchVerts_. Returns the number of vertices emitted.
    int buildPlaneTriangleFan(const float* planeRow, float* outVerts);

    // GL resources (owned).
    GLuint program_ = 0;
    GLint uMvpLoc_ = -1;
    GLint aPosLoc_ = -1;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    // Pre-allocated scratch — zero per-frame heap allocation.
    // Each plane has up to MAX_VERTICES_PER_PLANE + 1 (fan center) positions.
    static constexpr int MAX_TOTAL_VERTS = MAX_PLANES * (MAX_VERTICES_PER_PLANE + 1);
    float scratchVerts_[MAX_TOTAL_VERTS * POSITIONS_PER_VERTEX] = {};

    // Batched plane draw list.
    PlaneBatch batches_[MAX_PLANES] = {};
    int batchCount_ = 0;

    // Guards updateGeometry() vs. renderDepthOnly() when they run on
    // different threads (sensor poller may update from the render thread
    // too — cheap mutex just in case).
    std::mutex uploadMutex_;

    // State.
    bool ready_ = false;
    std::atomic<bool> enabled_{true};
    bool vboDirty_ = false;
    int uploadedPlaneCount_ = 0;
    int totalUploadedVertices_ = 0;
    int totalUploadedTriangles_ = 0;
    int frameRenderCount_ = 0;
};

// Process-wide singleton accessor. Lazily constructs one instance.
SceneOcclusion& getSceneOcclusion();

}  // namespace chloe_vr

// ═══════════════════════════════════════════════════════════════════════
// JNI layout constants (must match Kotlin SceneOcclusionManager)
// ═══════════════════════════════════════════════════════════════════════
#define CHLOE_OCC_FLOATS_PER_PLANE 75
#define CHLOE_OCC_VERTICES_OFFSET  11  // (pos3 + quat4 + ext2 + label + count)
