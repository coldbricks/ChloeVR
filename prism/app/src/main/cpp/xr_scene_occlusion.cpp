// xr_scene_occlusion.cpp — depth-only pre-pass that makes virtual models
// respect real-world surfaces (bed, dresser, wall, table).
//
// Design notes:
//   * GL program writes depth only — fragment shader is a no-op with the
//     `discard` keyword suppressed. We use colorMask=false,false,false,false
//     to make the color output a no-op while depth writes still happen.
//   * VBO is sized once to the hard upper bound (64 planes × 33 verts × 3
//     floats each = 6336 floats ≈ 24.75 KB). Zero per-frame heap allocation.
//   * updateGeometry() builds triangle-fans in a scratch buffer and marks
//     vboDirty_; the next renderDepthOnly() flushes it with glBufferSubData.
//   * Planes labeled FLOOR (2) and CEILING (3) are skipped to avoid
//     self-occluding the ground a model is resting on. The grid height
//     passed in from Kotlin is used as a secondary safety cutoff.
#include "xr_scene_occlusion.h"

#include <android/log.h>
#include <cmath>
#include <cstring>

#define OCC_LOG_TAG "ChloeVR-SceneOcclusion"
#define OCC_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  OCC_LOG_TAG, __VA_ARGS__)
#define OCC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OCC_LOG_TAG, __VA_ARGS__)

namespace chloe_vr {

namespace {

// Depth-only vertex shader: transforms position by MVP.
constexpr const char* kVertexSrc = R"GLSL(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
}
)GLSL";

// Minimal fragment shader — writes nothing. We additionally set
// glColorMask(false,false,false,false) so the color attachment is
// untouched; only the depth attachment is updated.
constexpr const char* kFragmentSrc = R"GLSL(#version 300 es
precision mediump float;
void main() {
    // No color output — color mask is off during the occlusion pass.
    // gl_FragDepth is written implicitly from interpolated gl_Position.z.
}
)GLSL";

GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = GL_FALSE;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? len : 1);
        glGetShaderInfoLog(sh, (GLsizei)log.size(), nullptr, log.data());
        OCC_LOGE("shader compile failed (type=%u): %s", type, log.data());
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

// Multiply two column-major 4×4 matrices: out = a * b.
void multiplyMat4(const float* a, const float* b, float* out) {
    for (int c = 0; c < 4; ++c) {
        for (int r = 0; r < 4; ++r) {
            float s = 0.0f;
            for (int k = 0; k < 4; ++k) {
                s += a[k * 4 + r] * b[c * 4 + k];
            }
            out[c * 4 + r] = s;
        }
    }
}

}  // namespace

SceneOcclusion& getSceneOcclusion() {
    static SceneOcclusion instance;
    return instance;
}

bool SceneOcclusion::compileProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexSrc);
    if (vs == 0) return false;
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentSrc);
    if (fs == 0) {
        glDeleteShader(vs);
        return false;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glBindAttribLocation(prog, 0, "aPos");
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = GL_FALSE;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? len : 1);
        glGetProgramInfoLog(prog, (GLsizei)log.size(), nullptr, log.data());
        OCC_LOGE("program link failed: %s", log.data());
        glDeleteProgram(prog);
        return false;
    }
    program_ = prog;
    uMvpLoc_ = glGetUniformLocation(program_, "uMVP");
    aPosLoc_ = 0;  // bound explicitly
    return true;
}

bool SceneOcclusion::init() {
    if (ready_) return true;

    if (!compileProgram()) {
        OCC_LOGE("init failed: could not compile depth-only program");
        return false;
    }

    // Create VBO large enough for the hard upper bound of polygon fan verts.
    glGenBuffers(1, &vbo_);
    if (vbo_ == 0) {
        OCC_LOGE("init failed: glGenBuffers returned 0");
        releaseGl();
        return false;
    }
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER,
                 MAX_TOTAL_VERTS * POSITIONS_PER_VERTEX * sizeof(float),
                 nullptr, GL_DYNAMIC_DRAW);

    // Create VAO that references vbo_ with a single position attribute.
    glGenVertexArrays(1, &vao_);
    if (vao_ == 0) {
        OCC_LOGE("init failed: glGenVertexArrays returned 0");
        releaseGl();
        return false;
    }
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 3 * sizeof(float), nullptr);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        OCC_LOGE("init encountered GL error: 0x%x", (unsigned)err);
        // Not fatal — continue if the VAO/VBO actually bound.
    }

    OCC_LOGI("SceneOcclusion ready (VBO cap=%d floats, program=%u)",
             MAX_TOTAL_VERTS * POSITIONS_PER_VERTEX, program_);
    ready_ = true;
    return true;
}

void SceneOcclusion::releaseGl() {
    if (vao_ != 0) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
    if (vbo_ != 0) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
}

void SceneOcclusion::shutdown() {
    std::lock_guard<std::mutex> lock(uploadMutex_);
    releaseGl();
    ready_ = false;
    batchCount_ = 0;
    uploadedPlaneCount_ = 0;
    totalUploadedVertices_ = 0;
    totalUploadedTriangles_ = 0;
    vboDirty_ = false;
}

int SceneOcclusion::buildPlaneTriangleFan(const float* planeRow, float* outVerts) {
    // planeRow layout (75 floats):
    //   0..2:   centroid xyz
    //   3..6:   quaternion xyzw
    //   7..8:   half-extents x,y (plane-local)
    //   9:      label
    //   10:     vertex count
    //   11..:   2D vertex pairs (plane-local x,y)
    const float px = planeRow[0];
    const float py = planeRow[1];
    const float pz = planeRow[2];
    const float qx = planeRow[3];
    const float qy = planeRow[4];
    const float qz = planeRow[5];
    const float qw = planeRow[6];
    const float ex = planeRow[7];
    const float ey = planeRow[8];
    int vertexCount = (int)planeRow[10];
    if (vertexCount < 0) vertexCount = 0;
    if (vertexCount > MAX_VERTICES_PER_PLANE) vertexCount = MAX_VERTICES_PER_PLANE;

    // Quaternion → rotation matrix (3×3).
    const float x2 = qx + qx, y2 = qy + qy, z2 = qz + qz;
    const float xx = qx * x2, xy = qx * y2, xz = qx * z2;
    const float yy = qy * y2, yz = qy * z2, zz = qz * z2;
    const float wx = qw * x2, wy = qw * y2, wz = qw * z2;
    // Basis vectors of the plane-local axes in world space.
    const float axX = 1.0f - (yy + zz), axY = xy + wz,      axZ = xz - wy;
    const float ayX = xy - wz,          ayY = 1.0f - (xx + zz), ayZ = yz + wx;

    // If no polygon vertices were supplied (or a degenerate count < 3),
    // fall back to a rectangular quad built from half-extents.
    auto emitVert = [&](float localX, float localY, int idx) {
        outVerts[idx * 3 + 0] = px + axX * localX + ayX * localY;
        outVerts[idx * 3 + 1] = py + axY * localX + ayY * localY;
        outVerts[idx * 3 + 2] = pz + axZ * localX + ayZ * localY;
    };

    if (vertexCount < 3) {
        // Rectangular fallback: 4 vertices, built as a triangle-fan-compatible
        // quad centered on (0,0) with half-extents (ex, ey).
        emitVert(-ex, -ey, 0);
        emitVert( ex, -ey, 1);
        emitVert( ex,  ey, 2);
        emitVert(-ex,  ey, 3);
        return 4;
    }

    // Polygon-fan: copy provided vertices directly.
    for (int i = 0; i < vertexCount; ++i) {
        const float lx = planeRow[11 + i * 2 + 0];
        const float ly = planeRow[11 + i * 2 + 1];
        emitVert(lx, ly, i);
    }
    return vertexCount;
}

void SceneOcclusion::updateGeometry(const float* planeData, int planeCount, float gridHeight) {
    std::lock_guard<std::mutex> lock(uploadMutex_);
    batchCount_ = 0;
    int scratchOffsetVerts = 0;
    totalUploadedVertices_ = 0;
    totalUploadedTriangles_ = 0;
    uploadedPlaneCount_ = 0;

    if (planeData == nullptr || planeCount <= 0) {
        vboDirty_ = true;  // clears previous content on next render
        return;
    }
    if (planeCount > MAX_PLANES) planeCount = MAX_PLANES;

    for (int i = 0; i < planeCount; ++i) {
        const float* row = planeData + i * CHLOE_OCC_FLOATS_PER_PLANE;

        const int label = (int)row[9];
        // Skip floor (2) and ceiling (3). These cause self-occlusion of
        // models resting on the ground.
        if (label == 2 || label == 3) continue;

        const float centroidY = row[1];
        // Secondary safety cutoff: skip anything too close to the grid height.
        // Only the grid floor typically has this property after the FLOOR
        // label filter is bypassed due to unknown-label floors.
        if (centroidY < gridHeight + 0.05f) continue;

        const float halfEx = row[7];
        const float halfEy = row[8];
        // Skip tiny noisy planes (< 15 cm on either axis).
        if (halfEx < 0.075f || halfEy < 0.075f) continue;

        // Ensure we still have room in the scratch buffer.
        if (scratchOffsetVerts + (MAX_VERTICES_PER_PLANE + 1) > MAX_TOTAL_VERTS) break;

        float* dst = &scratchVerts_[scratchOffsetVerts * POSITIONS_PER_VERTEX];
        int emitted = buildPlaneTriangleFan(row, dst);
        if (emitted < 3) continue;  // defensive

        PlaneBatch& b = batches_[batchCount_];
        b.vertexStart = scratchOffsetVerts;
        b.triangleCount = emitted - 2;  // triangle-fan triangle count
        // Model matrix is identity: vertices are already in world space.
        for (int k = 0; k < 16; ++k) b.modelMatrix[k] = (k % 5 == 0) ? 1.0f : 0.0f;
        ++batchCount_;

        scratchOffsetVerts += emitted;
        totalUploadedVertices_ += emitted;
        totalUploadedTriangles_ += b.triangleCount;
        ++uploadedPlaneCount_;
    }

    vboDirty_ = true;
}

bool SceneOcclusion::renderDepthOnly(const float* projection, const float* viewMatrix) {
    if (!ready_) return false;
    if (!enabled_.load()) return false;

    std::lock_guard<std::mutex> lock(uploadMutex_);
    if (batchCount_ == 0) {
        // First few frames (or after a failed space scan) — skip silently.
        return false;
    }
    if (program_ == 0 || vao_ == 0 || vbo_ == 0) return false;

    // Flush VBO if geometry changed since last frame.
    if (vboDirty_) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        // Only upload the bytes we actually populated.
        const int vertUsed = totalUploadedVertices_;
        if (vertUsed > 0) {
            glBufferSubData(GL_ARRAY_BUFFER, 0,
                            vertUsed * POSITIONS_PER_VERTEX * sizeof(float),
                            scratchVerts_);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        vboDirty_ = false;
    }

    // Save the relevant GL state bits we're about to modify.
    GLboolean prevColorMask[4];
    glGetBooleanv(GL_COLOR_WRITEMASK, prevColorMask);
    GLboolean prevDepthMask = GL_TRUE;
    glGetBooleanv(GL_DEPTH_WRITEMASK, &prevDepthMask);
    GLint prevDepthFunc = GL_LESS;
    glGetIntegerv(GL_DEPTH_FUNC, &prevDepthFunc);
    GLboolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
    GLboolean prevBlend = glIsEnabled(GL_BLEND);
    GLboolean prevCullFace = glIsEnabled(GL_CULL_FACE);

    // Depth-only mode.
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);  // room planes are double-sided

    glUseProgram(program_);
    glBindVertexArray(vao_);

    // Compute projection * view once (identity model, since verts are in world space).
    float mvp[16];
    multiplyMat4(projection, viewMatrix, mvp);
    glUniformMatrix4fv(uMvpLoc_, 1, GL_FALSE, mvp);

    for (int i = 0; i < batchCount_; ++i) {
        const PlaneBatch& b = batches_[i];
        // Vertex count for the fan = triangleCount + 2.
        const int vertCount = b.triangleCount + 2;
        glDrawArrays(GL_TRIANGLE_FAN, b.vertexStart, vertCount);
    }

    glBindVertexArray(0);
    glUseProgram(0);

    // Restore GL state.
    glColorMask(prevColorMask[0], prevColorMask[1], prevColorMask[2], prevColorMask[3]);
    glDepthMask(prevDepthMask);
    glDepthFunc(prevDepthFunc);
    if (!prevDepthTest) glDisable(GL_DEPTH_TEST);
    if (prevBlend) glEnable(GL_BLEND);
    if (prevCullFace) glEnable(GL_CULL_FACE);

    ++frameRenderCount_;
    if (frameRenderCount_ < 4) {
        OCC_LOGI("frame %d: %d planes, %d verts, %d tris",
                 frameRenderCount_, uploadedPlaneCount_,
                 totalUploadedVertices_, totalUploadedTriangles_);
    }
    return true;
}

}  // namespace chloe_vr
