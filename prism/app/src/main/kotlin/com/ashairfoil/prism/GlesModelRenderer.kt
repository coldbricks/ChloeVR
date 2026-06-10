package com.ashairfoil.prism

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class GlesModelRenderer {
    companion object {
        private const val TAG = "ChloeVR-GLRenderer"
        // GL_EXT_texture_filter_anisotropic — not in android.opengl.GLES30 bindings
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        const val GIZMO_AXIS_NONE = -1
        const val GIZMO_AXIS_X = 0
        const val GIZMO_AXIS_Y = 1
        const val GIZMO_AXIS_Z = 2
        const val GIZMO_LENGTH = 0.25f
        const val GIZMO_HIT_RADIUS = 0.04f
        const val LASER_BASE_LENGTH = 5.0f
        // Tier 4 — must match the `mat4 uJoint[N]` size in VERTEX_SHADER and
        // SHADOW_VERTEX_SHADER. 64 * 64 bytes = 4KB per model UBO. std140
        // guarantees 16KB minimum — we're well under.
        const val MAX_JOINTS_SHADER = 64
        // Shared joint UBO binding point, referenced by both programs.
        const val JOINT_UBO_BINDING = 0
        private val DEFAULT_LASER_COLOR = floatArrayOf(0f, 0.8f, 1f)
        private val GIZMO_AXES = arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f)
        )
        // Plane vis colors (floor, ceiling, table, wall)
        private val PV_COLOR_FLOOR   = floatArrayOf(0.06f, 0.92f, 0.5f, 0.5f)
        private val PV_COLOR_CEILING = floatArrayOf(0.35f, 0.35f, 0.45f, 0.25f)
        private val PV_COLOR_TABLE   = floatArrayOf(1.0f, 0.55f, 0.08f, 0.55f)
        private val PV_COLOR_WALL    = floatArrayOf(0.18f, 0.55f, 1.0f, 0.45f)
        private val PV_COLOR_SELECTED = floatArrayOf(1.0f, 0.3f, 0.7f, 0.8f)  // bright pink for selected
    }

    // ── Per-model GPU state ──
    data class GpuModel(
        val id: Int,
        var vao: Int = 0,
        var vboPositions: Int = 0,
        var vboNormals: Int = 0,
        var vboTexCoords: Int = 0,
        var ebo: Int = 0,
        var textureId: Int = 0,
        var indexCount: Int = 0,
        var modelMatrix: FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f,  0f, 1f, 0f, 0f,  0f, 0f, 1f, 0f,  0f, 0f, -1f, 1f
        ),
        var metallic: Float = 0f,
        var roughness: Float = 0.9f,
        var exposure: Float = 0f,
        var boundsCenterX: Float = 0f,
        var boundsCenterY: Float = 0f,
        var boundsCenterZ: Float = 0f,
        var boundsRadius: Float = 1f,
        var boundsMinY: Float = 0f,
        var selected: Boolean = false,
        var contrast: Float = 1f,
        var saturation: Float = 1f,
        // PBR texture maps
        var vboTangents: Int = 0,
        var normalMapTexId: Int = 0,
        var metallicRoughnessTexId: Int = 0,
        var emissiveTexId: Int = 0,
        var emissiveFactor: FloatArray = floatArrayOf(1f, 1f, 1f),
        var hasNormalMap: Boolean = false,
        // Tier 3 Feature 4 — glute deformation state (CPU-driven, shader-applied).
        // Positions are model-local; radius 0 = disabled. Push in mesh units
        // (pre-scale). The dance loop writes these each frame.
        var gluteAPos: FloatArray = floatArrayOf(0f, 0f, 0f),
        var gluteBPos: FloatArray = floatArrayOf(0f, 0f, 0f),
        var gluteARadius: Float = 0f,
        var gluteBRadius: Float = 0f,
        var gluteAPush: Float = 0f,
        var gluteBPush: Float = 0f,
        // Tier 4 — skeletal skinning state. Populated by loadGlb() when the
        // GLB contains a skin (skins[0].joints + inverseBindMatrices + the
        // mesh primitive's JOINTS_0 + WEIGHTS_0 attributes). If any piece is
        // missing, isSkinned stays false and the vertex shader's skin block
        // is gated off via the uSkinned uniform. All Tier 3 infrastructure
        // (glute deform, rigid-body dance) continues to work on unskinned
        // meshes — skinning is purely additive.
        var isSkinned: Boolean = false,
        var vboJoints: Int = 0,        // JOINTS_0 (UNSIGNED_BYTE vec4)
        var vboWeights: Int = 0,       // WEIGHTS_0 (FLOAT vec4)
        var uboJoints: Int = 0,        // per-model std140 UBO holding joint palette
        var skeleton: com.ashairfoil.prism.scene.SkeletonRuntime? = null
    )

    data class ShadowPlane(
        val posX: Float, val posY: Float, val posZ: Float,
        val rotX: Float, val rotY: Float, val rotZ: Float, val rotW: Float,
        val extentX: Float, val extentY: Float,
        val label: Int  // 0=unknown, 1=wall, 2=floor, 3=ceiling, 4=table
    )

    private val models = mutableListOf<GpuModel>()
    private val modelIndex = HashMap<Int, GpuModel>()
    private var nextModelId = 1
    private val glIdBuf = intArrayOf(0)
    private var maxTextureSize = 4096
    var textureMaxSize = 0  // 0=auto, else hard cap (4096/2048/1024)

    // GL shared resources
    private var programId = 0
    private var fbo = 0
    private var depthRb = 0
    private var lastDepthW = 0
    private var lastDepthH = 0

    // Shadow map resources
    private var shadowDepthProgramId = 0
    private var shadowMapFbo = 0
    private var shadowMapTexId = 0
    private var shadowColorRb = 0  // dummy color attachment for GPU compat
    private val shadowMapSize = 2048
    var shadowLightMatrix = FloatArray(16)
    var shadowDarkness = 0.7f
    var shadowSoftness = 2.0f
    var shadowSpread = 8f  // ortho extent in meters — larger = wider shadow area
    var lightSize = 1.0f   // PCSS light size — controls penumbra width
    var shadowEnabled = true
    var shadowPlanes: List<ShadowPlane> = emptyList()
    var occlusionEnabled = true  // room planes above floor block model rendering

    /**
     * Optional hook invoked from [renderEye] right before the model color
     * pass, with the current eye's projection and view matrices. Intended
     * for the real-world scene-occlusion manager to issue its depth-only
     * pre-pass using the same FBO / depth attachment. Set by the activity.
     */
    var sceneOcclusionHook: ((projection: FloatArray, viewMatrix: FloatArray) -> Unit)? = null

    // Laser resources
    private var laserProgramId = 0
    private var laserVao = 0
    private var laserVbo = 0
    private var laserIndexCount = 0
    private var dotVao = 0
    private var dotVbo = 0

    // Grid resources
    private var gridProgramId = 0
    private var gridVao = 0
    private var gridVbo = 0

    // Gizmo resources
    private var gizmoProgramId = 0
    private var gizmoVao = 0
    private var gizmoVbo = 0
    private var gizmoEbo = 0
    private val gizmoIndicesPerAxis = 12

    // Light emitter marker
    private var emitterVao = 0
    private var emitterVbo = 0
    private var emitterEbo = 0
    private var emitterIndexCount = 0
    var emitterVisible = true
    var emitterPos = floatArrayOf(0.3f, 1.5f, 0.5f)  // default: above-right-front
    val emitterRadius = 0.04f  // visual size
    // Scene point the emitter orbits; refreshed on every emitter drag so that
    // slider-driven repositioning keeps the marker at the user's chosen distance.
    private val lightPivot = floatArrayOf(0f, 0f, 0f)
    private var emitterSyncedAz = Float.NaN  // angles emitterPos currently reflects
    private var emitterSyncedEl = Float.NaN

    // Color wash (fullscreen tint overlay)
    private var washProgramId = 0
    private var washVao = 0

    // Bloom resources
    private var bloomBrightProgramId = 0
    private var bloomBlurProgramId = 0
    private var bloomCompositeProgramId = 0
    private var bloomFboA = 0
    private var bloomFboB = 0
    private var bloomTexA = 0
    private var bloomTexB = 0
    private var bloomW = 0
    private var bloomH = 0
    var bloomEnabled = false  // disabled by default — enable via menu when needed
    var bloomThreshold = 0.8f
    var bloomIntensity = 0.3f

    // Shadow plane resources
    private var shadowPlaneProgramId = 0
    private var planeVisProgramId = 0
    var showPlaneVisualization = false
    var selectedPlaneIndex = -1  // highlighted during room edit
    private var planeVisStartTime = 0L
    private var spVao = 0
    private var spVbo = 0

    // UI overlay resources
    private var uiProgramId = 0
    private var uiVao = 0
    private var uiVbo = 0
    private var uiUTex = -1
    private var uiUMvp = -1

    // Model shader uniforms (cached at init)
    private var uMVP = -1
    private var uModelView = -1
    private var uNormalMatrix = -1
    private var uBaseColor = -1
    private var uMetallic = -1
    private var uRoughness = -1
    private var uExposure = -1
    private var uLightDir = -1
    private var uLightColor = -1
    private var uLightIntensity = -1
    private var uFillLightDir = -1
    private var uFillLightColor = -1
    private var uFillLightIntensity = -1
    private var uAmbientColor = -1
    private var uAmbientIntensity = -1
    private var uSelected = -1
    private var uContrast = -1
    private var uSaturation = -1
    private var uClipY = -1
    private var uUseSH = -1
    private var uSH = -1
    private var uModelMat = -1  // "uModel" in main shader
    // Tier 3 Feature 4 — glute deformation uniforms
    private var uGluteAPos = -1
    private var uGluteBPos = -1
    private var uGluteARadius = -1
    private var uGluteBRadius = -1
    private var uGluteAPush = -1
    private var uGluteBPush = -1
    // Tier 4 — skinning uniforms (main + shadow programs)
    private var uSkinned = -1
    private var uShadowDepthSkinned = -1
    // Scratch palette buffer for UBO upload — sized for MAX_JOINTS_SHADER.
    private val jointPaletteScratch = FloatArray(MAX_JOINTS_SHADER * 16)
    // PBR map uniforms
    private var uHasNormalMap = -1
    private var uNormalMap = -1
    private var uHasMetRoughMap = -1
    private var uMetRoughMap = -1
    private var uHasEmissiveMap = -1
    private var uEmissiveMap = -1
    private var uEmissiveFactor = -1

    // Grid shader uniforms (cached at init)
    private var uGridMVP = -1
    private var uGridY = -1
    private var uGridScale = -1
    private var uGridAlpha = -1
    private var uGridShadowMVP = -1
    private var uGridShadowDarkness = -1
    private var uGridShadowSoftness = -1
    private var uGridLightSize = -1
    private var uGridShadowMap = -1

    // Laser shader uniforms (cached at init)
    private var uLaserMVP = -1
    private var uLaserColor = -1
    private var uLaserLenScale = -1

    // Gizmo shader uniforms (cached at init)
    private var uGizmoMVP = -1
    private var uGizmoHighlight = -1

    // Color wash uniform (cached at init)
    private var uWashColor = -1

    // Shadow depth uniform (cached at init)
    private var uShadowDepthMVP = -1

    // Shadow plane uniforms (cached at init)
    private var uSpShadowMap = -1
    private var uSpShadowMVP = -1
    private var uSpShadowDarkness = -1
    private var uSpShadowSoftness = -1
    private var uSpLightSize = -1
    private var uSpMVP = -1
    private var uSpModel = -1

    // Plane vis uniforms (cached at init)
    private var uPvMVP = -1
    private var uPvModel = -1
    private var uPvColor = -1
    private var uPvTime = -1

    // Bloom uniforms (cached at init)
    private var uBloomBrightScene = -1
    private var uBloomBrightThreshold = -1
    private var uBloomBlurTex = -1
    private var uBloomBlurDir = -1
    private var uBloomCompBloom = -1
    private var uBloomCompIntensity = -1

    // Lighting state
    var lightDir = floatArrayOf(0.3f, 1.0f, 0.5f)
    var lightColor = floatArrayOf(1.0f, 0.95f, 0.9f)
    var lightIntensity = 2.0f
    var fillLightDir = floatArrayOf(-0.5f, -0.3f, 0.3f)
    var fillLightColor = floatArrayOf(0.85f, 0.9f, 1.0f)
    var fillLightIntensity = 0.5f
    var ambientColor = floatArrayOf(1.0f, 1.0f, 1.0f)
    var ambientIntensity = 1.0f
    var lightAngleDeg = 0f    // azimuth in degrees
    var lightElevDeg = 60f   // elevation in degrees (90=overhead, 5=horizon=long shadows)
    var gridHeight = 0.0f   // Y=0 in stage space = floor level
    var shCoefficients = FloatArray(27) // 9 × RGB L2 spherical harmonics
    var useSH = false

    private fun updateFillLightDirFromMainLight() {
        if (fillLightDir.size < 3) fillLightDir = FloatArray(3)
        fillLightDir[0] = -lightDir[0]
        fillLightDir[1] = 0.2f
        fillLightDir[2] = -lightDir[2]
        val len = kotlin.math.sqrt(
            fillLightDir[0] * fillLightDir[0] +
                fillLightDir[1] * fillLightDir[1] +
                fillLightDir[2] * fillLightDir[2]
        ).coerceAtLeast(0.001f)
        fillLightDir[0] /= len
        fillLightDir[1] /= len
        fillLightDir[2] /= len
    }

    /** Recompute lightDir from azimuth + elevation angles */
    fun updateLightDirFromAngles() {
        val azRad = Math.toRadians(lightAngleDeg.toDouble())
        val elRad = Math.toRadians(lightElevDeg.toDouble())
        val cosEl = kotlin.math.cos(elRad).toFloat()
        val sinEl = kotlin.math.sin(elRad).toFloat()
        val sinAz = kotlin.math.sin(azRad).toFloat()
        val cosAz = kotlin.math.cos(azRad).toFloat()
        if (lightDir.size < 3) lightDir = FloatArray(3)
        lightDir[0] = cosEl * sinAz
        lightDir[1] = sinEl
        lightDir[2] = cosEl * cosAz
        updateFillLightDirFromMainLight()
        syncEmitterToAngles()
    }

    /** Keep the emitter marker on the lightDir ray when azimuth/elevation change
     *  via sliders, thumbstick nudge, reset, presets, or scene load. No-op while
     *  the angles already match what the emitter reflects (e.g. the per-frame
     *  auto-ambient call), so an in-progress emitter drag is never fought. */
    private fun syncEmitterToAngles() {
        if (lightAngleDeg == emitterSyncedAz && lightElevDeg == emitterSyncedEl) return
        val dx = emitterPos[0] - lightPivot[0]
        val dy = emitterPos[1] - lightPivot[1]
        val dz = emitterPos[2] - lightPivot[2]
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.5f)
        emitterPos[0] = lightPivot[0] + lightDir[0] * dist
        emitterPos[1] = lightPivot[1] + lightDir[1] * dist
        emitterPos[2] = lightPivot[2] + lightDir[2] * dist
        emitterSyncedAz = lightAngleDeg
        emitterSyncedEl = lightElevDeg
    }

    private var initialized = false
    private var frameCount = 0

    // ── Texture upload helpers ──
    // GLUtils.texImage2D's internalformat overload is unusable for sRGB: AOSP
    // passes the internalformat argument as the pixel FORMAT too, and
    // GL_SRGB8_ALPHA8 is not a legal format enum in ES 3.0 (black texture or
    // IllegalArgumentException depending on AOSP version). Upload explicitly.
    private fun uploadTexture(bitmap: Bitmap, isSrgb: Boolean) {
        val b = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (!isSrgb || b.rowBytes != b.width * 4) {
            // Linear data, or row padding we can't hand to glTexImage2D directly —
            // GLUtils picks the matching RGBA8 internalformat and handles stride.
            if (isSrgb) Log.w(TAG, "sRGB upload fell back to linear (rowBytes=${b.rowBytes}, w=${b.width})")
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        } else {
            val buf = ByteBuffer.allocateDirect(b.byteCount)
            b.copyPixelsToBuffer(buf)
            buf.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8,
                b.width, b.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        }
        if (b !== bitmap) b.recycle()
    }

    // 4x anisotropic filtering on the bound texture — skin at grazing angles
    // (thighs/arms in profile) blurs under plain trilinear at VR distances.
    private var anisoChecked = false
    private var anisoSupported = false
    private fun applyAnisotropy() {
        if (!anisoChecked) {
            anisoChecked = true
            anisoSupported = GLES30.glGetString(GLES30.GL_EXTENSIONS)
                ?.contains("GL_EXT_texture_filter_anisotropic") == true
            Log.i(TAG, "Anisotropic filtering: ${if (anisoSupported) "supported (4x)" else "unsupported"}")
        }
        if (anisoSupported) {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, 4f)
        }
    }

    fun init(): Boolean {
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) { Log.e(TAG, "Failed to create model shader"); return false }

        uMVP = GLES30.glGetUniformLocation(programId, "uMVP")
        uModelView = GLES30.glGetUniformLocation(programId, "uModelView")
        uNormalMatrix = GLES30.glGetUniformLocation(programId, "uNormalMatrix")
        uBaseColor = GLES30.glGetUniformLocation(programId, "uBaseColor")
        uMetallic = GLES30.glGetUniformLocation(programId, "uMetallic")
        uRoughness = GLES30.glGetUniformLocation(programId, "uRoughness")
        uExposure = GLES30.glGetUniformLocation(programId, "uExposure")
        uLightDir = GLES30.glGetUniformLocation(programId, "uLightDir")
        uLightColor = GLES30.glGetUniformLocation(programId, "uLightColor")
        uLightIntensity = GLES30.glGetUniformLocation(programId, "uLightIntensity")
        uFillLightDir = GLES30.glGetUniformLocation(programId, "uFillLightDir")
        uFillLightColor = GLES30.glGetUniformLocation(programId, "uFillLightColor")
        uFillLightIntensity = GLES30.glGetUniformLocation(programId, "uFillLightIntensity")
        uAmbientColor = GLES30.glGetUniformLocation(programId, "uAmbientColor")
        uAmbientIntensity = GLES30.glGetUniformLocation(programId, "uAmbientIntensity")
        uSelected = GLES30.glGetUniformLocation(programId, "uSelected")
        uContrast = GLES30.glGetUniformLocation(programId, "uContrast")
        uSaturation = GLES30.glGetUniformLocation(programId, "uSaturation")
        uHasNormalMap = GLES30.glGetUniformLocation(programId, "uHasNormalMap")
        uNormalMap = GLES30.glGetUniformLocation(programId, "uNormalMap")
        uHasMetRoughMap = GLES30.glGetUniformLocation(programId, "uHasMetRoughMap")
        uMetRoughMap = GLES30.glGetUniformLocation(programId, "uMetRoughMap")
        uHasEmissiveMap = GLES30.glGetUniformLocation(programId, "uHasEmissiveMap")
        uEmissiveMap = GLES30.glGetUniformLocation(programId, "uEmissiveMap")
        uEmissiveFactor = GLES30.glGetUniformLocation(programId, "uEmissiveFactor")
        uClipY = GLES30.glGetUniformLocation(programId, "uClipY")
        uUseSH = GLES30.glGetUniformLocation(programId, "uUseSH")
        uSH = GLES30.glGetUniformLocation(programId, "uSH")
        uModelMat = GLES30.glGetUniformLocation(programId, "uModel")
        // Tier 3 Feature 4 — glute deformation
        uGluteAPos = GLES30.glGetUniformLocation(programId, "uGluteAPos")
        uGluteBPos = GLES30.glGetUniformLocation(programId, "uGluteBPos")
        uGluteARadius = GLES30.glGetUniformLocation(programId, "uGluteARadius")
        uGluteBRadius = GLES30.glGetUniformLocation(programId, "uGluteBRadius")
        uGluteAPush = GLES30.glGetUniformLocation(programId, "uGluteAPush")
        uGluteBPush = GLES30.glGetUniformLocation(programId, "uGluteBPush")
        // Tier 4 — skinning uniforms + UBO block binding
        uSkinned = GLES30.glGetUniformLocation(programId, "uSkinned")
        val mainJointsBlock = GLES30.glGetUniformBlockIndex(programId, "Joints")
        if (mainJointsBlock != GLES30.GL_INVALID_INDEX) {
            GLES30.glUniformBlockBinding(programId, mainJointsBlock, JOINT_UBO_BINDING)
        }

        // Main FBO
        val fboBuf = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fboBuf, 0)
        fbo = fboBuf[0]
        val rbBuf = intArrayOf(0)
        GLES30.glGenRenderbuffers(1, rbBuf, 0)
        depthRb = rbBuf[0]

        // Shadow map
        shadowDepthProgramId = createProgram(SHADOW_VERTEX_SHADER, SHADOW_FRAGMENT_SHADER)
        if (shadowDepthProgramId == 0) { Log.e(TAG, "Failed to create shadow shader"); return false }
        uShadowDepthMVP = GLES30.glGetUniformLocation(shadowDepthProgramId, "uMVP")
        uShadowDepthSkinned = GLES30.glGetUniformLocation(shadowDepthProgramId, "uSkinned")
        val shadowJointsBlock = GLES30.glGetUniformBlockIndex(shadowDepthProgramId, "Joints")
        if (shadowJointsBlock != GLES30.GL_INVALID_INDEX) {
            GLES30.glUniformBlockBinding(shadowDepthProgramId, shadowJointsBlock, JOINT_UBO_BINDING)
        }
        initShadowMap()

        // Color wash overlay
        washProgramId = createProgram(WASH_VERTEX_SHADER, WASH_FRAGMENT_SHADER)
        if (washProgramId != 0) {
            uWashColor = GLES30.glGetUniformLocation(washProgramId, "uColor")
            val vaoBuf = intArrayOf(0)
            GLES30.glGenVertexArrays(1, vaoBuf, 0)
            washVao = vaoBuf[0]
            Log.i(TAG, "Color wash shader ready")
        }

        // Bloom shaders
        bloomBrightProgramId = createProgram(BLOOM_FULLSCREEN_VS, BLOOM_BRIGHT_FS)
        bloomBlurProgramId = createProgram(BLOOM_FULLSCREEN_VS, BLOOM_BLUR_FS)
        bloomCompositeProgramId = createProgram(BLOOM_FULLSCREEN_VS, BLOOM_COMPOSITE_FS)
        if (bloomBrightProgramId != 0 && bloomBlurProgramId != 0 && bloomCompositeProgramId != 0) {
            uBloomBrightScene = GLES30.glGetUniformLocation(bloomBrightProgramId, "uScene")
            uBloomBrightThreshold = GLES30.glGetUniformLocation(bloomBrightProgramId, "uThreshold")
            uBloomBlurTex = GLES30.glGetUniformLocation(bloomBlurProgramId, "uTex")
            uBloomBlurDir = GLES30.glGetUniformLocation(bloomBlurProgramId, "uDirection")
            uBloomCompBloom = GLES30.glGetUniformLocation(bloomCompositeProgramId, "uBloom")
            uBloomCompIntensity = GLES30.glGetUniformLocation(bloomCompositeProgramId, "uIntensity")
            Log.i(TAG, "Bloom shaders ready")
        } else {
            Log.w(TAG, "Bloom shader compilation failed, bloom disabled")
            bloomEnabled = false
        }

        // Laser
        laserProgramId = createProgram(LASER_VERTEX_SHADER, LASER_FRAGMENT_SHADER)
        if (laserProgramId == 0) { Log.e(TAG, "Failed to create laser shader"); return false }
        uLaserMVP = GLES30.glGetUniformLocation(laserProgramId, "uMVP")
        uLaserColor = GLES30.glGetUniformLocation(laserProgramId, "uColor")
        uLaserLenScale = GLES30.glGetUniformLocation(laserProgramId, "uLenScale")
        initLaserGeometry()

        // Grid (with shadow sampling)
        gridProgramId = createProgram(GRID_VERTEX_SHADER, GRID_FRAGMENT_SHADER)
        if (gridProgramId == 0) { Log.e(TAG, "Failed to create grid shader"); return false }
        uGridMVP = GLES30.glGetUniformLocation(gridProgramId, "uMVP")
        uGridY = GLES30.glGetUniformLocation(gridProgramId, "uGridY")
        uGridScale = GLES30.glGetUniformLocation(gridProgramId, "uGridScale")
        uGridAlpha = GLES30.glGetUniformLocation(gridProgramId, "uAlpha")
        uGridShadowMVP = GLES30.glGetUniformLocation(gridProgramId, "uShadowMVP")
        uGridShadowDarkness = GLES30.glGetUniformLocation(gridProgramId, "uShadowDarkness")
        uGridShadowSoftness = GLES30.glGetUniformLocation(gridProgramId, "uShadowSoftness")
        uGridLightSize = GLES30.glGetUniformLocation(gridProgramId, "uLightSize")
        uGridShadowMap = GLES30.glGetUniformLocation(gridProgramId, "uShadowMap")
        initGridGeometry()

        // Shadow planes
        shadowPlaneProgramId = createProgram(SP_VERTEX_SHADER, SP_FRAGMENT_SHADER)
        if (shadowPlaneProgramId == 0) { Log.e(TAG, "Failed to create shadow plane shader"); return false }
        uSpShadowMap = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowMap")
        uSpShadowMVP = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowMVP")
        uSpShadowDarkness = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowDarkness")
        uSpShadowSoftness = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowSoftness")
        uSpLightSize = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uLightSize")
        uSpMVP = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uMVP")
        uSpModel = GLES30.glGetUniformLocation(shadowPlaneProgramId, "uModel")

        // Plane visualization (reuses SP_VERTEX_SHADER for the same quad geometry)
        planeVisProgramId = createProgram(SP_VERTEX_SHADER, PLANE_VIS_FRAGMENT_SHADER)
        if (planeVisProgramId == 0) Log.w(TAG, "Plane vis shader failed — non-fatal")
        if (planeVisProgramId != 0) {
            uPvMVP = GLES30.glGetUniformLocation(planeVisProgramId, "uMVP")
            uPvModel = GLES30.glGetUniformLocation(planeVisProgramId, "uModel")
            uPvColor = GLES30.glGetUniformLocation(planeVisProgramId, "uPlaneColor")
            uPvTime = GLES30.glGetUniformLocation(planeVisProgramId, "uTime")
        }
        run {
            val quadVerts = floatArrayOf(
                -1f, 0f, -1f, 0f, 0f,
                 1f, 0f, -1f, 1f, 0f,
                 1f, 0f,  1f, 1f, 1f,
                -1f, 0f,  1f, 0f, 1f
            )
            val buf = ByteBuffer.allocateDirect(quadVerts.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(quadVerts).position(0)
            val vaoBuf = intArrayOf(0)
            GLES30.glGenVertexArrays(1, vaoBuf, 0)
            spVao = vaoBuf[0]
            GLES30.glBindVertexArray(spVao)
            val vboBuf = intArrayOf(0)
            GLES30.glGenBuffers(1, vboBuf, 0)
            spVbo = vboBuf[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVerts.size * 4, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glBindVertexArray(0)
        }

        // Gizmo
        gizmoProgramId = createProgram(GIZMO_VERTEX_SHADER, GIZMO_FRAGMENT_SHADER)
        if (gizmoProgramId == 0) { Log.e(TAG, "Failed to create gizmo shader"); return false }
        uGizmoMVP = GLES30.glGetUniformLocation(gizmoProgramId, "uMVP")
        uGizmoHighlight = GLES30.glGetUniformLocation(gizmoProgramId, "uHighlight")
        initGizmoGeometry()
        initEmitterGeometry()

        // UI overlay
        uiProgramId = createProgram(UI_VERTEX_SHADER, UI_FRAGMENT_SHADER)
        if (uiProgramId == 0) { Log.e(TAG, "Failed to create UI shader"); return false }
        uiUTex = GLES30.glGetUniformLocation(uiProgramId, "uTex")
        uiUMvp = GLES30.glGetUniformLocation(uiProgramId, "uMVP")

        val quadVerts = floatArrayOf(
            -0.5f, -0.5f, 0f, 1f,  0.5f, -0.5f, 1f, 1f,
            -0.5f,  0.5f, 0f, 0f,  0.5f,  0.5f, 1f, 0f
        )
        val quadBuf = ByteBuffer.allocateDirect(quadVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadBuf.put(quadVerts).position(0)

        val uiVaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, uiVaoBuf, 0)
        uiVao = uiVaoBuf[0]
        GLES30.glBindVertexArray(uiVao)
        val uiVboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, uiVboBuf, 0)
        uiVbo = uiVboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uiVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVerts.size * 4, quadBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)

        val maxTexBuf = intArrayOf(0)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexBuf, 0)
        maxTextureSize = if (maxTexBuf[0] > 0) maxTexBuf[0] else 4096
        Log.i(TAG, "GPU max texture size: $maxTextureSize")

        initialized = true
        Log.i(TAG, "GLES renderer initialized (shadows, gizmo, laser, grid)")
        return true
    }

    private fun initShadowMap() {
        // Color texture to store depth (avoids depth texture compat issues on mobile)
        val texBuf = intArrayOf(0)
        GLES30.glGenTextures(1, texBuf, 0)
        shadowMapTexId = texBuf[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowMapTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            shadowMapSize, shadowMapSize, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Depth renderbuffer for actual depth testing during shadow render
        val depthRbBuf = intArrayOf(0)
        GLES30.glGenRenderbuffers(1, depthRbBuf, 0)
        shadowColorRb = depthRbBuf[0]  // reusing field name
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, shadowColorRb)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, shadowMapSize, shadowMapSize)

        // Shadow FBO: color=R32F texture (stores depth), depth=renderbuffer (for z-test)
        val fboBuf = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fboBuf, 0)
        shadowMapFbo = fboBuf[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowMapFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, shadowMapTexId, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, shadowColorRb)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Shadow FBO incomplete: 0x${Integer.toHexString(status)}, shadows disabled")
            shadowEnabled = false
        } else {
            Log.i(TAG, "Shadow FBO complete (R32F + depth RB), ${shadowMapSize}x${shadowMapSize}")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun initLaserGeometry() {
        val hw = 0.001f
        val len = LASER_BASE_LENGTH
        val verts = floatArrayOf(
            -hw, 0f, 0f, 1f,  hw, 0f, 0f, 1f,  hw, 0f, -len, 0f,  -hw, 0f, -len, 0f,
            0f, -hw, 0f, 1f,  0f, hw, 0f, 1f,  0f, hw, -len, 0f,  0f, -hw, -len, 0f,
        )
        val indices = intArrayOf(0,1,2, 0,2,3, 4,5,6, 4,6,7)
        laserIndexCount = indices.size
        val vertBuf = allocFloatBuf(verts)
        val idxBuf = allocIntBuf(indices)

        val vaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vaoBuf, 0)
        laserVao = vaoBuf[0]
        GLES30.glBindVertexArray(laserVao)
        val vboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, vboBuf, 0)
        laserVbo = vboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, laserVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vertBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 16, 12)
        val eboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, eboBuf, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboBuf[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, idxBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)

        // Hit dot
        val dotHw = 0.008f
        val dotVerts = floatArrayOf(
            -dotHw,0f,0f,1f, dotHw,0f,0f,1f, 0f,-dotHw,0f,1f, 0f,dotHw,0f,1f
        )
        val dotBuf = allocFloatBuf(dotVerts)
        val dotVaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, dotVaoBuf, 0)
        dotVao = dotVaoBuf[0]
        GLES30.glBindVertexArray(dotVao)
        val dotVboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, dotVboBuf, 0)
        dotVbo = dotVboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dotVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, dotVerts.size * 4, dotBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 16, 12)
        GLES30.glBindVertexArray(0)
    }

    private fun initGridGeometry() {
        // Enlarged from 5×5m to 20×20m so shadows cast onto the floor are visible
        // throughout a normal room regardless of where the user wandered from the
        // origin. Grid-line fade (in the shader) still keeps the visible grid
        // contained; shadow fade decoupled separately.
        val s = 20.0f
        val gridVerts = floatArrayOf(-s,0f,-s, s,0f,-s, s,0f,s, -s,0f,s)
        val gridBuf = allocFloatBuf(gridVerts)
        val vaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vaoBuf, 0)
        gridVao = vaoBuf[0]
        GLES30.glBindVertexArray(gridVao)
        val vboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, vboBuf, 0)
        gridVbo = vboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, gridVerts.size * 4, gridBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun initGizmoGeometry() {
        val hw = 0.003f
        val len = GIZMO_LENGTH
        // 3 axes × 2 crossed quads × 4 verts = 24 verts, stride: pos(3)+color(3) = 6 floats
        val r = 1f; val g = 1f; val b = 1f
        val rt = 1f; val gt = 0.5f; val bt = 0.5f  // tip tints
        val verts = floatArrayOf(
            // X axis (red) - verts 0-7
            0f,-hw,0f, r,0f,0f,  0f,hw,0f, r,0f,0f,  len,hw,0f, rt,0.3f,0.3f,  len,-hw,0f, rt,0.3f,0.3f,
            0f,0f,-hw, r,0f,0f,  0f,0f,hw, r,0f,0f,  len,0f,hw, rt,0.3f,0.3f,  len,0f,-hw, rt,0.3f,0.3f,
            // Y axis (green) - verts 8-15
            -hw,0f,0f, 0f,g,0f,  hw,0f,0f, 0f,g,0f,  hw,len,0f, 0.3f,gt,0.3f,  -hw,len,0f, 0.3f,gt,0.3f,
            0f,0f,-hw, 0f,g,0f,  0f,0f,hw, 0f,g,0f,  0f,len,hw, 0.3f,gt,0.3f,  0f,len,-hw, 0.3f,gt,0.3f,
            // Z axis (blue) - verts 16-23
            -hw,0f,0f, 0f,0f,b,  hw,0f,0f, 0f,0f,b,  hw,0f,len, 0.3f,0.3f,bt,  -hw,0f,len, 0.3f,0.3f,bt,
            0f,-hw,0f, 0f,0f,b,  0f,hw,0f, 0f,0f,b,  0f,hw,len, 0.3f,0.3f,bt,  0f,-hw,len, 0.3f,0.3f,bt,
        )
        val indices = intArrayOf(
            0,1,2, 0,2,3, 4,5,6, 4,6,7,           // X
            8,9,10, 8,10,11, 12,13,14, 12,14,15,   // Y
            16,17,18, 16,18,19, 20,21,22, 20,22,23  // Z
        )
        val vertBuf = allocFloatBuf(verts)
        val idxBuf = allocIntBuf(indices)

        val vaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vaoBuf, 0)
        gizmoVao = vaoBuf[0]
        GLES30.glBindVertexArray(gizmoVao)

        val vboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, vboBuf, 0)
        gizmoVbo = vboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gizmoVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vertBuf, GLES30.GL_STATIC_DRAW)
        // pos at location 0, stride 24
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        // color at location 1, stride 24, offset 12
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)

        val eboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, eboBuf, 0)
        gizmoEbo = eboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gizmoEbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, idxBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    fun loadGlb(glbBytes: ByteArray): Int {
        try {
            if (glbBytes.size < 20) { Log.e(TAG, "Invalid GLB: too small (${glbBytes.size} bytes)"); return -1 }
            val jsonLength = readU32(glbBytes, 12)
            if (jsonLength < 0 || jsonLength > glbBytes.size - 20) {
                Log.e(TAG, "Invalid GLB: jsonLength=$jsonLength exceeds bounds")
                return -1
            }
            val jsonStr = String(glbBytes, 20, jsonLength.coerceAtMost(glbBytes.size - 20), Charsets.UTF_8)
            val json = org.json.JSONObject(jsonStr)
            val binChunkStart = 12 + 8 + jsonLength + 8
            if (binChunkStart >= glbBytes.size) { Log.e(TAG, "No binary chunk"); return -1 }

            val bufferViews = json.getJSONArray("bufferViews")
            fun bv(idx: Int): Pair<Int, Int> {
                val o = bufferViews.getJSONObject(idx)
                return Pair(binChunkStart + o.optInt("byteOffset", 0), o.getInt("byteLength"))
            }
            val accessors = json.getJSONArray("accessors")
            fun acc(idx: Int): Triple<Int, Int, String> {
                val a = accessors.getJSONObject(idx)
                return Triple(a.getInt("bufferView"), a.getInt("count"), a.getString("type"))
            }

            val positions: FloatBuffer
            var texCoords: FloatBuffer? = null
            var normals: FloatBuffer
            var tangents: FloatBuffer? = null
            val indices: IntBuffer
            val posCnt: Int
            val idxCnt: Int
            val attrs: org.json.JSONObject
            try {
                val prim = json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
                attrs = prim.getJSONObject("attributes")

                val (posBv, posCnt_, _) = acc(attrs.getInt("POSITION"))
                posCnt = posCnt_
                val (posOff, _) = bv(posBv)
                positions = extractFloats(glbBytes, posOff, posCnt * 3)

                if (attrs.has("TEXCOORD_0")) {
                    val (tcBv, tcCnt, _) = acc(attrs.getInt("TEXCOORD_0"))
                    val (tcOff, _) = bv(tcBv)
                    texCoords = extractFloats(glbBytes, tcOff, tcCnt * 2)
                }
                if (attrs.has("NORMAL")) {
                    val nAccIdx = attrs.getInt("NORMAL")
                    val nAcc = accessors.getJSONObject(nAccIdx)
                    val nCompType = nAcc.getInt("componentType")
                    val (nBv, nCnt, _) = acc(nAccIdx)
                    val (nOff, _) = bv(nBv)
                    val nBvObj = bufferViews.getJSONObject(nBv)
                    val nStride = nBvObj.optInt("byteStride", 0)
                    normals = when (nCompType) {
                        5120 -> { // SIGNED BYTE (KHR_mesh_quantization)
                            val stride = if (nStride > 0) nStride else 3
                            extractBytesAsFloats(glbBytes, nOff, nCnt, 3, stride)
                        }
                        5122 -> { // SHORT
                            val stride = if (nStride > 0) nStride else 6
                            extractShortsAsFloats(glbBytes, nOff, nCnt, 3, stride)
                        }
                        else -> extractFloats(glbBytes, nOff, nCnt * 3) // 5126 FLOAT
                    }
                } else {
                    normals = FloatBuffer.allocate(posCnt * 3)
                }

                // Tangents (vec4: xyz + w handedness) for normal mapping
                if (attrs.has("TANGENT")) {
                    val tAccIdx = attrs.getInt("TANGENT")
                    val tAcc = accessors.getJSONObject(tAccIdx)
                    val tCompType = tAcc.getInt("componentType")
                    val (tBv, tCnt, _) = acc(tAccIdx)
                    val (tOff, _) = bv(tBv)
                    val tBvObj = bufferViews.getJSONObject(tBv)
                    val tStride = tBvObj.optInt("byteStride", 0)
                    tangents = when (tCompType) {
                        5120 -> { val stride = if (tStride > 0) tStride else 4; extractBytesAsFloats(glbBytes, tOff, tCnt, 4, stride) }
                        5122 -> { val stride = if (tStride > 0) tStride else 8; extractShortsAsFloats(glbBytes, tOff, tCnt, 4, stride) }
                        else -> extractFloats(glbBytes, tOff, tCnt * 4)
                    }
                    Log.i(TAG, "Parsed TANGENT attribute ($tCnt vec4s)")
                }

                val idxAccIdx = prim.getInt("indices")
                val (idxBv, idxCnt_, _) = acc(idxAccIdx)
                idxCnt = idxCnt_
                val idxCompType = accessors.getJSONObject(idxAccIdx).getInt("componentType")
                val (idxOff, _) = bv(idxBv)
                indices = extractIndices(glbBytes, idxOff, idxCnt, idxCompType)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse GLB: ${e.message}")
                return -1
            }

            if (!attrs.has("NORMAL")) normals = computeNormals(positions, indices, posCnt, idxCnt)

            // Bounding sphere
            positions.position(0)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in 0 until posCnt) {
                val px = positions.get(); val py = positions.get(); val pz = positions.get()
                if (px < minX) minX = px; if (py < minY) minY = py; if (pz < minZ) minZ = pz
                if (px > maxX) maxX = px; if (py > maxY) maxY = py; if (pz > maxZ) maxZ = pz
            }
            positions.position(0)
            val cx = (minX+maxX)*0.5f; val cy = (minY+maxY)*0.5f; val cz = (minZ+maxZ)*0.5f
            val dx = maxX-minX; val dy = maxY-minY; val dz = maxZ-minZ
            val radius = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz) * 0.5f

            val model = GpuModel(id = nextModelId++)
            val vaoBuf = intArrayOf(0)
            GLES30.glGenVertexArrays(1, vaoBuf, 0)
            model.vao = vaoBuf[0]
            GLES30.glBindVertexArray(model.vao)
            model.vboPositions = createVBO(positions, 0, 3)
            model.vboNormals = createVBO(normals, 1, 3)
            if (texCoords != null) model.vboTexCoords = createVBO(texCoords, 2, 2)
            val eboBuf = intArrayOf(0)
            GLES30.glGenBuffers(1, eboBuf, 0)
            model.ebo = eboBuf[0]
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, model.ebo)
            indices.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idxCnt * 4, indices, GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)

            model.indexCount = idxCnt
            model.boundsCenterX = cx; model.boundsCenterY = cy; model.boundsCenterZ = cz
            model.boundsRadius = radius
            model.boundsMinY = minY

            // Material + texture: follow material → texture → image chain
            val images = json.optJSONArray("images")
            val textures = json.optJSONArray("textures")
            val mats = json.optJSONArray("materials")
            var baseColorImageIdx = -1
            var normalTexIdx = -1
            var metRoughTexIdx = -1
            var emissiveTexIdx = -1

            if (mats != null && mats.length() > 0) {
                val mat0 = mats.getJSONObject(0)
                val pbr = mat0.optJSONObject("pbrMetallicRoughness")
                if (pbr != null) {
                    model.metallic = pbr.optDouble("metallicFactor", 0.0).toFloat()
                    model.roughness = pbr.optDouble("roughnessFactor", 0.9).toFloat()
                    val bcTex = pbr.optJSONObject("baseColorTexture")
                    if (bcTex != null && textures != null) {
                        val texIdx = bcTex.getInt("index")
                        if (texIdx < textures.length()) {
                            baseColorImageIdx = textures.getJSONObject(texIdx).optInt("source", 0)
                        }
                    }
                    // Metallic-roughness map (G=roughness, B=metallic)
                    val mrTex = pbr.optJSONObject("metallicRoughnessTexture")
                    if (mrTex != null) metRoughTexIdx = mrTex.optInt("index", -1)
                }
                // Normal map
                val normTex = mat0.optJSONObject("normalTexture")
                if (normTex != null) normalTexIdx = normTex.optInt("index", -1)
                // Emissive map + factor
                val emiTex = mat0.optJSONObject("emissiveTexture")
                if (emiTex != null) emissiveTexIdx = emiTex.optInt("index", -1)
                val emiFactor = mat0.optJSONArray("emissiveFactor")
                if (emiFactor != null && emiFactor.length() >= 3) {
                    model.emissiveFactor = floatArrayOf(
                        emiFactor.optDouble(0, 0.0).toFloat(),
                        emiFactor.optDouble(1, 0.0).toFloat(),
                        emiFactor.optDouble(2, 0.0).toFloat()
                    )
                }
            }

            // Helper to load a glTF texture by texture array index
            // Texture cap: user override or adaptive budget
            val texCount = (if (images != null) images.length() else 0).coerceAtLeast(1)
            val vertexVram = posCnt.toLong() * 12 * 3 + idxCnt.toLong() * 4
            val texCap: Int
            if (textureMaxSize > 0) {
                // User-specified hard cap
                texCap = minOf(textureMaxSize, maxTextureSize)
                Log.i(TAG, "Texture cap: ${texCap}px (user setting)")
            } else {
                // Auto: budget ~128MB for textures, scale down heavy models
                val vramBudget = 128L * 1024 * 1024
                val perTexBudget = ((vramBudget - vertexVram) / texCount).coerceAtLeast(1)
                val maxDimFromBudget = kotlin.math.sqrt(perTexBudget / 4.0).toInt()
                var cap = maxTextureSize
                while (cap > maxDimFromBudget && cap > 512) cap /= 2
                texCap = cap
                Log.i(TAG, "Texture cap: ${texCap}px (auto, $texCount images, ${vertexVram/1024}KB vertex)")
            }

            fun clampBitmap(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
                if (bmp.width <= texCap && bmp.height <= texCap) return bmp
                val scale = texCap.toFloat() / maxOf(bmp.width, bmp.height)
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                Log.w(TAG, "Clamping texture ${bmp.width}x${bmp.height} → ${w}x${h} (cap $texCap)")
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                bmp.recycle()
                return scaled
            }

            fun decodeTexBytes(texBytes: ByteArray, texLen: Int): android.graphics.Bitmap? {
                // Probe dimensions first, then decode at capped size to save heap + VRAM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(texBytes, 0, texLen, opts)
                var sampleSize = 1
                while (opts.outWidth / sampleSize > texCap || opts.outHeight / sampleSize > texCap) {
                    sampleSize *= 2
                }
                val decOpts = if (sampleSize > 1) {
                    Log.i(TAG, "Decode texture ${opts.outWidth}x${opts.outHeight} with sampleSize=$sampleSize")
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                } else null
                return BitmapFactory.decodeByteArray(texBytes, 0, texLen, decOpts)
            }

            fun loadGltfTex(texIdx: Int, label: String, isSrgb: Boolean = false): Int {
                if (texIdx < 0 || textures == null || images == null) return 0
                if (texIdx >= textures.length()) return 0
                val source = textures.getJSONObject(texIdx).optInt("source", 0)
                if (source >= images.length()) return 0
                val img = images.getJSONObject(source)
                val texBvIdx = img.optInt("bufferView", -1)
                if (texBvIdx < 0) return 0
                val (texOff, texLen) = bv(texBvIdx)
                val texBytes = ByteArray(texLen)
                System.arraycopy(glbBytes, texOff, texBytes, 0, texLen)
                var bitmap = decodeTexBytes(texBytes, texLen) ?: return 0
                bitmap = clampBitmap(bitmap)
                val tb = intArrayOf(0)
                GLES30.glGenTextures(1, tb, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tb[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                uploadTexture(bitmap, isSrgb)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                Log.i(TAG, "$label: ${img.optString("name", "img[$source]")} (${bitmap.width}x${bitmap.height}${if (isSrgb) ", sRGB" else ""})")
                bitmap.recycle()
                return tb[0]
            }

            // Load base color texture
            if (images != null && images.length() > 0) {
                val imgIdx = if (baseColorImageIdx in 0 until images.length()) baseColorImageIdx else 0
                val img = images.getJSONObject(imgIdx)
                val texBvIdx = img.optInt("bufferView", -1)
                if (texBvIdx >= 0) {
                    val (texOff, texLen) = bv(texBvIdx)
                    val texBytes = ByteArray(texLen)
                    System.arraycopy(glbBytes, texOff, texBytes, 0, texLen)
                    var bitmap = decodeTexBytes(texBytes, texLen)
                    if (bitmap != null) {
                        bitmap = clampBitmap(bitmap)
                        val tb = intArrayOf(0)
                        GLES30.glGenTextures(1, tb, 0)
                        model.textureId = tb[0]
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, model.textureId)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                        applyAnisotropy()
                        uploadTexture(bitmap, isSrgb = true)
                        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                        val imgName = img.optString("name", "image[$imgIdx]")
                        Log.i(TAG, "Base color texture: $imgName (${bitmap.width}x${bitmap.height}, image[$imgIdx], sRGB)")
                        bitmap.recycle()
                    }
                }
            }

            // Load PBR texture maps — emissive is color data (sRGB); normal/MR stay linear
            model.normalMapTexId = loadGltfTex(normalTexIdx, "Normal map")
            model.metallicRoughnessTexId = loadGltfTex(metRoughTexIdx, "Metallic-roughness map")
            model.emissiveTexId = loadGltfTex(emissiveTexIdx, "Emissive map", isSrgb = true)
            model.hasNormalMap = model.normalMapTexId != 0

            // ── Tier 4: SKIN PARSE ─────────────────────────────────────────
            // Extract joint indices + weights + bind pose from skins[0]. If
            // anything is missing the model falls back to unskinned rendering
            // and Tier 3 rigid-body dance still works.
            //
            // Simplification vs full glTF compliance: we assume every ancestor
            // of a joint in the node tree is ALSO a joint (which is true for
            // Tripo v2.5+ rigs — Thigh/Calf/Foot are all joints). Old rigs
            // with intermediate non-joint nodes between joints will render
            // with slight skeleton drift; fine for unskinned fallback.
            var jointIndicesBytes: ByteArray? = null
            var jointWeightsFloats: FloatArray? = null
            if (json.has("skins") && attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
                try {
                    val skinsArr = json.getJSONArray("skins")
                    if (skinsArr.length() > 0) {
                        val skinObj = skinsArr.getJSONObject(0)
                        val jointNodes = skinObj.getJSONArray("joints")
                        val jointCount = jointNodes.length()
                        Log.i(TAG, "Tier 4 skin: $jointCount joints detected")

                        // ── JOINTS_0 (per-vert 4 joint indices) ──
                        val jAccIdx = attrs.getInt("JOINTS_0")
                        val jAcc = accessors.getJSONObject(jAccIdx)
                        val jCompType = jAcc.getInt("componentType")
                        val jCnt = jAcc.getInt("count")
                        val jBvIdx = jAcc.getInt("bufferView")
                        val (jOff, _) = bv(jBvIdx)
                        jointIndicesBytes = ByteArray(jCnt * 4)
                        when (jCompType) {
                            5121 -> { // UNSIGNED_BYTE (Tripo default)
                                System.arraycopy(glbBytes, jOff, jointIndicesBytes, 0, jCnt * 4)
                            }
                            5123 -> { // UNSIGNED_SHORT — decimate to byte (assume <256 joints)
                                for (i in 0 until jCnt) {
                                    for (c in 0 until 4) {
                                        val lo = glbBytes[jOff + i * 8 + c * 2].toInt() and 0xFF
                                        val hi = glbBytes[jOff + i * 8 + c * 2 + 1].toInt() and 0xFF
                                        val jn = ((hi shl 8) or lo).coerceAtMost(255)
                                        jointIndicesBytes!![i * 4 + c] = jn.toByte()
                                    }
                                }
                            }
                            else -> {
                                Log.e(TAG, "Unsupported JOINTS_0 componentType $jCompType")
                                jointIndicesBytes = null
                            }
                        }

                        // ── WEIGHTS_0 (per-vert 4 weights) ──
                        val wAccIdx = attrs.getInt("WEIGHTS_0")
                        val wAcc = accessors.getJSONObject(wAccIdx)
                        val wCompType = wAcc.getInt("componentType")
                        val wCnt = wAcc.getInt("count")
                        val wBvIdx = wAcc.getInt("bufferView")
                        val (wOff, _) = bv(wBvIdx)
                        jointWeightsFloats = FloatArray(wCnt * 4)
                        when (wCompType) {
                            5126 -> { // FLOAT (Tripo default)
                                val fb = extractFloats(glbBytes, wOff, wCnt * 4)
                                fb.position(0)
                                fb.get(jointWeightsFloats!!)
                            }
                            5121 -> { // UNSIGNED_BYTE normalized
                                for (i in 0 until wCnt * 4) {
                                    jointWeightsFloats!![i] = (glbBytes[wOff + i].toInt() and 0xFF) / 255f
                                }
                            }
                            5123 -> { // UNSIGNED_SHORT normalized
                                for (i in 0 until wCnt * 4) {
                                    val lo = glbBytes[wOff + i * 2].toInt() and 0xFF
                                    val hi = glbBytes[wOff + i * 2 + 1].toInt() and 0xFF
                                    jointWeightsFloats!![i] = ((hi shl 8) or lo) / 65535f
                                }
                            }
                            else -> {
                                Log.e(TAG, "Unsupported WEIGHTS_0 componentType $wCompType")
                                jointWeightsFloats = null
                            }
                        }

                        // ── Inverse-bind matrices (one 4x4 per joint) ──
                        val ibmAccIdx = skinObj.optInt("inverseBindMatrices", -1)
                        val invBind = FloatArray(jointCount * 16)
                        if (ibmAccIdx >= 0) {
                            val ibmAcc = accessors.getJSONObject(ibmAccIdx)
                            val ibmBvIdx = ibmAcc.getInt("bufferView")
                            val (ibmOff, _) = bv(ibmBvIdx)
                            val ibmFb = extractFloats(glbBytes, ibmOff, jointCount * 16)
                            ibmFb.position(0)
                            ibmFb.get(invBind)
                        } else {
                            for (jj in 0 until jointCount) android.opengl.Matrix.setIdentityM(invBind, jj * 16)
                        }

                        // ── Node hierarchy + bind-pose local transforms ──
                        val nodesArr = json.getJSONArray("nodes")
                        val nodeParent = IntArray(nodesArr.length()) { -1 }
                        for (nIdx in 0 until nodesArr.length()) {
                            val node = nodesArr.getJSONObject(nIdx)
                            if (node.has("children")) {
                                val ch = node.getJSONArray("children")
                                for (ci in 0 until ch.length()) nodeParent[ch.getInt(ci)] = nIdx
                            }
                        }
                        val nodeToJoint = HashMap<Int, Int>(jointCount * 2)
                        for (jj in 0 until jointCount) nodeToJoint[jointNodes.getInt(jj)] = jj

                        val parents = IntArray(jointCount) { -1 }
                        val bindLocal = FloatArray(jointCount * 16)
                        val names = Array(jointCount) { "" }
                        val scratchTRS = FloatArray(16)
                        val scratchR = FloatArray(16)
                        for (jj in 0 until jointCount) {
                            val nIdx = jointNodes.getInt(jj)
                            val node = nodesArr.getJSONObject(nIdx)
                            names[jj] = node.optString("name", "joint$jj")

                            // Walk up the node tree to find nearest joint ancestor
                            var walk = nodeParent[nIdx]
                            while (walk >= 0 && !nodeToJoint.containsKey(walk)) walk = nodeParent[walk]
                            parents[jj] = if (walk >= 0) nodeToJoint[walk]!! else -1

                            // Bind-pose local: matrix OR compose(T, R, S)
                            if (node.has("matrix")) {
                                val mArr = node.getJSONArray("matrix")
                                for (k in 0 until 16) bindLocal[jj * 16 + k] = mArr.getDouble(k).toFloat()
                            } else {
                                var tx = 0f; var ty = 0f; var tz = 0f
                                var rx = 0f; var ry = 0f; var rz = 0f; var rw = 1f
                                var sx = 1f; var sy = 1f; var sz = 1f
                                if (node.has("translation")) {
                                    val t = node.getJSONArray("translation")
                                    tx = t.getDouble(0).toFloat(); ty = t.getDouble(1).toFloat(); tz = t.getDouble(2).toFloat()
                                }
                                if (node.has("rotation")) {
                                    val r = node.getJSONArray("rotation")
                                    rx = r.getDouble(0).toFloat(); ry = r.getDouble(1).toFloat()
                                    rz = r.getDouble(2).toFloat(); rw = r.getDouble(3).toFloat()
                                }
                                if (node.has("scale")) {
                                    val s = node.getJSONArray("scale")
                                    sx = s.getDouble(0).toFloat(); sy = s.getDouble(1).toFloat(); sz = s.getDouble(2).toFloat()
                                }
                                // Compose: M = T * R * S into bindLocal[jj]
                                android.opengl.Matrix.setIdentityM(scratchTRS, 0)
                                scratchTRS[12] = tx; scratchTRS[13] = ty; scratchTRS[14] = tz
                                // Quaternion → rotation matrix
                                val xx = rx * rx; val yy = ry * ry; val zz = rz * rz
                                val xy = rx * ry; val xz = rx * rz; val yz = ry * rz
                                val wx = rw * rx; val wy = rw * ry; val wz = rw * rz
                                android.opengl.Matrix.setIdentityM(scratchR, 0)
                                scratchR[0] = 1f - 2f * (yy + zz); scratchR[1] = 2f * (xy + wz); scratchR[2]  = 2f * (xz - wy)
                                scratchR[4] = 2f * (xy - wz);      scratchR[5] = 1f - 2f * (xx + zz); scratchR[6]  = 2f * (yz + wx)
                                scratchR[8] = 2f * (xz + wy);      scratchR[9] = 2f * (yz - wx);      scratchR[10] = 1f - 2f * (xx + yy)
                                // Apply scale
                                scratchR[0] *= sx; scratchR[1] *= sx; scratchR[2]  *= sx
                                scratchR[4] *= sy; scratchR[5] *= sy; scratchR[6]  *= sy
                                scratchR[8] *= sz; scratchR[9] *= sz; scratchR[10] *= sz
                                // T * R (translation baked in)
                                android.opengl.Matrix.multiplyMM(bindLocal, jj * 16, scratchTRS, 0, scratchR, 0)
                            }
                        }

                        // ── Fold non-joint ancestor transforms into bindLocal ──
                        // If a joint has intermediate non-joint ancestors between
                        // itself and its joint-parent (e.g., a rotated Armature
                        // between the scene root and the skeleton Root joint),
                        // those ancestors' transforms MUST compose into the
                        // joint's bind-local or the palette will not be identity
                        // at bind pose — producing the "model rendered on its
                        // side" artifact seen with some Tripo exports where the
                        // Armature has a -90° X rotation.
                        val ancScratchT = FloatArray(16)
                        val ancScratchR = FloatArray(16)
                        val ancScratchMat = FloatArray(16)
                        val ancComposeTmp = FloatArray(16)
                        for (jj in 0 until jointCount) {
                            val myNode = jointNodes.getInt(jj)
                            val parentJNode = if (parents[jj] >= 0) jointNodes.getInt(parents[jj]) else -1
                            var anc = nodeParent[myNode]
                            while (anc >= 0 && anc != parentJNode && !nodeToJoint.containsKey(anc)) {
                                val ancNode = nodesArr.getJSONObject(anc)
                                // Read ancestor's local transform into ancScratchMat
                                if (ancNode.has("matrix")) {
                                    val mArr = ancNode.getJSONArray("matrix")
                                    for (k in 0 until 16) ancScratchMat[k] = mArr.getDouble(k).toFloat()
                                } else {
                                    var atx = 0f; var aty = 0f; var atz = 0f
                                    var arx = 0f; var ary = 0f; var arz = 0f; var arw = 1f
                                    var asx = 1f; var asy = 1f; var asz = 1f
                                    if (ancNode.has("translation")) {
                                        val t = ancNode.getJSONArray("translation")
                                        atx = t.getDouble(0).toFloat(); aty = t.getDouble(1).toFloat(); atz = t.getDouble(2).toFloat()
                                    }
                                    if (ancNode.has("rotation")) {
                                        val r = ancNode.getJSONArray("rotation")
                                        arx = r.getDouble(0).toFloat(); ary = r.getDouble(1).toFloat()
                                        arz = r.getDouble(2).toFloat(); arw = r.getDouble(3).toFloat()
                                    }
                                    if (ancNode.has("scale")) {
                                        val s = ancNode.getJSONArray("scale")
                                        asx = s.getDouble(0).toFloat(); asy = s.getDouble(1).toFloat(); asz = s.getDouble(2).toFloat()
                                    }
                                    android.opengl.Matrix.setIdentityM(ancScratchT, 0)
                                    ancScratchT[12] = atx; ancScratchT[13] = aty; ancScratchT[14] = atz
                                    val xx = arx * arx; val yy = ary * ary; val zz = arz * arz
                                    val xy = arx * ary; val xz = arx * arz; val yz = ary * arz
                                    val wx = arw * arx; val wy = arw * ary; val wz = arw * arz
                                    android.opengl.Matrix.setIdentityM(ancScratchR, 0)
                                    ancScratchR[0] = 1f - 2f * (yy + zz); ancScratchR[1] = 2f * (xy + wz); ancScratchR[2]  = 2f * (xz - wy)
                                    ancScratchR[4] = 2f * (xy - wz);      ancScratchR[5] = 1f - 2f * (xx + zz); ancScratchR[6]  = 2f * (yz + wx)
                                    ancScratchR[8] = 2f * (xz + wy);      ancScratchR[9] = 2f * (yz - wx);      ancScratchR[10] = 1f - 2f * (xx + yy)
                                    ancScratchR[0] *= asx; ancScratchR[1] *= asx; ancScratchR[2]  *= asx
                                    ancScratchR[4] *= asy; ancScratchR[5] *= asy; ancScratchR[6]  *= asy
                                    ancScratchR[8] *= asz; ancScratchR[9] *= asz; ancScratchR[10] *= asz
                                    android.opengl.Matrix.multiplyMM(ancScratchMat, 0, ancScratchT, 0, ancScratchR, 0)
                                }
                                // bindLocal[jj] = ancScratchMat × bindLocal[jj]
                                android.opengl.Matrix.multiplyMM(ancComposeTmp, 0, ancScratchMat, 0, bindLocal, jj * 16)
                                System.arraycopy(ancComposeTmp, 0, bindLocal, jj * 16, 16)
                                anc = nodeParent[anc]
                            }
                        }

                        // Verify topo order — bail to unskinned if violated.
                        var topoOk = true
                        for (jj in 0 until jointCount) {
                            if (parents[jj] >= jj) {
                                Log.w(TAG, "Joint $jj (${names[jj]}) has parent ${parents[jj]} >= $jj — topo violation, disabling skin")
                                topoOk = false
                                break
                            }
                        }
                        if (topoOk && jointIndicesBytes != null && jointWeightsFloats != null) {
                            model.skeleton = com.ashairfoil.prism.scene.SkeletonRuntime(
                                jointCount = jointCount,
                                parents = parents,
                                bindLocal = bindLocal,
                                invBind = invBind,
                                names = names
                            )
                            model.isSkinned = true
                            Log.i(TAG, "Tier 4 skin: initialised ${jointCount}-joint skeleton (Root='${names[0]}')")
                            // Dump full joint roster so Tier 4 can verify the Tripo-naming
                            // contract (Pelvis/Waist/Spine01/etc.) against what the GLB
                            // actually ships. Silent no-ops on missing joints were hiding
                            // a broken waist-yaw write.
                            Log.i(TAG, "Joint names [${jointCount}]: ${names.toList()}")
                        } else {
                            jointIndicesBytes = null
                            jointWeightsFloats = null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Skin parse failed: ${e.message} — falling back to unskinned", e)
                    jointIndicesBytes = null
                    jointWeightsFloats = null
                    model.isSkinned = false
                    model.skeleton = null
                }
            }

            // Tier1.5 FIX: normal-map tangents via MESH UNWELD.
            //
            // Prior implementation called computeTangents() which accumulated
            // per-vertex tangents at shared indices. At UV seams the adjacent
            // triangles' tangent vectors have OPPOSITE signs — summing them
            // cancels to ~0, Gram-Schmidt fails, handedness flips per-fragment.
            // Result: the "war paint" / "confetti" black-blotch rendering on
            // every Tripo GLB that shipped without baked TANGENT attribute.
            //
            // Fix (matches MikkTSpace semantics for the common case): expand
            // the mesh to triangle-soup (3 unique verts per triangle, no
            // sharing), compute per-triangle tangents with Gram-Schmidt
            // orthogonalization per-corner, and write an identity index
            // buffer. Tangent discontinuities at UV seams are preserved.
            // Mesh grows ~3× in vertex count; memory is cheap on XR, correct
            // shading is not. The old posCnt ≤ 500000 cap is removed — unweld
            // is safe at any density.
            // Skip the triangle-soup unweld for skinned meshes. The 3× vertex
            // expansion would require replicating JOINTS_0/WEIGHTS_0 across the
            // soup — extra work + risks breaking weights. Instead we disable
            // tangent-space normal mapping on the skinned model (set
            // hasNormalMap=false below) so the shader's TBN path never runs.
            // Without baked tangents + no unweld, tan would read as (0,0,0) →
            // normalize(zero)=NaN → fragment normal NaN → pitch-black lighting.
            // Trade: flat PBR on rigged meshes. Basecolor + metallic-roughness
            // still applied. Future: compute per-vertex averaged tangents for
            // skinned meshes (no unweld needed) if bump detail becomes critical.
            if (model.isSkinned && model.normalMapTexId != 0) {
                Log.w(TAG, "Tier 4: disabling normal-map tangent-space shading on skinned model " +
                    "(no baked TANGENT, unweld skipped) — flat PBR only")
                model.hasNormalMap = false
            }
            if (!model.isSkinned && model.hasNormalMap && tangents == null && texCoords != null) {
                val unweld = unweldWithTangents(positions, normals, texCoords, indices, posCnt, idxCnt)
                Log.i(TAG, "Unweld tangent fix: $posCnt verts / ${idxCnt/3} tris → ${unweld.vertCount} verts (triangle-soup)")

                // Replace the previously-uploaded welded VBOs + EBO with the
                // unwelded buffers so pos/norm/uv/tangent streams are 1:1.
                GLES30.glBindVertexArray(model.vao)
                if (model.vboPositions != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = model.vboPositions }, 0)
                if (model.vboNormals != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = model.vboNormals }, 0)
                if (model.vboTexCoords != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = model.vboTexCoords }, 0)
                if (model.ebo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = model.ebo }, 0)

                model.vboPositions = createVBO(unweld.positions, 0, 3)
                model.vboNormals = createVBO(unweld.normals, 1, 3)
                model.vboTexCoords = createVBO(unweld.texCoords, 2, 2)
                model.vboTangents = createVBO(unweld.tangents, 3, 4)

                val eboBufUw = intArrayOf(0)
                GLES30.glGenBuffers(1, eboBufUw, 0)
                model.ebo = eboBufUw[0]
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, model.ebo)
                unweld.indices.position(0)
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, unweld.indexCount * 4, unweld.indices, GLES30.GL_STATIC_DRAW)

                GLES30.glBindVertexArray(0)
                model.indexCount = unweld.indexCount
                tangents = unweld.tangents  // already uploaded, skip the else-branch below
            }

            // Upload tangent VBO when tangents came pre-baked in the GLB (unweld
            // path above already uploaded its own tangents).
            if (tangents != null && model.vboTangents == 0) {
                GLES30.glBindVertexArray(model.vao)
                model.vboTangents = createVBO(tangents, 3, 4)
                GLES30.glBindVertexArray(0)
            }

            // ── Tier 4: Upload JOINTS_0 + WEIGHTS_0 VBOs, create palette UBO ──
            if (model.isSkinned && jointIndicesBytes != null && jointWeightsFloats != null) {
                GLES30.glBindVertexArray(model.vao)

                // JOINTS_0 at location 4 — integer attribute (uvec4 UNSIGNED_BYTE).
                // MUST use glVertexAttribIPointer, not the normalized-float path,
                // or the shader reads zeros on most drivers.
                val jBuf = java.nio.ByteBuffer.allocateDirect(jointIndicesBytes!!.size)
                    .order(java.nio.ByteOrder.nativeOrder())
                jBuf.put(jointIndicesBytes!!); jBuf.position(0)
                val jVboBuf = intArrayOf(0)
                GLES30.glGenBuffers(1, jVboBuf, 0)
                model.vboJoints = jVboBuf[0]
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, model.vboJoints)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, jointIndicesBytes!!.size, jBuf, GLES30.GL_STATIC_DRAW)
                GLES30.glEnableVertexAttribArray(4)
                GLES30.glVertexAttribIPointer(4, 4, GLES30.GL_UNSIGNED_BYTE, 4, 0)

                // WEIGHTS_0 at location 5 — FLOAT vec4.
                val wByteLen = jointWeightsFloats!!.size * 4
                val wBuf = java.nio.ByteBuffer.allocateDirect(wByteLen)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                wBuf.put(jointWeightsFloats!!); wBuf.position(0)
                val wVboBuf = intArrayOf(0)
                GLES30.glGenBuffers(1, wVboBuf, 0)
                model.vboWeights = wVboBuf[0]
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, model.vboWeights)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, wByteLen, wBuf, GLES30.GL_STATIC_DRAW)
                GLES30.glEnableVertexAttribArray(5)
                GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, 16, 0)

                GLES30.glBindVertexArray(0)

                // Per-model UBO for joint palette. std140, 64 mat4s = 4KB. Well
                // under the 16KB guaranteed minimum for MAX_UNIFORM_BLOCK_SIZE.
                val uboBufArr = intArrayOf(0)
                GLES30.glGenBuffers(1, uboBufArr, 0)
                model.uboJoints = uboBufArr[0]
                GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, model.uboJoints)
                GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, MAX_JOINTS_SHADER * 64, null, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)

                Log.i(TAG, "Tier 4 skin VBOs + UBO uploaded for model #${model.id}")
            }

            models.add(model)
            modelIndex[model.id] = model
            val heightM = maxY - minY
            val maxYLog = maxY
            Log.i(TAG, "GLB loaded #${model.id}: $posCnt verts, $idxCnt idx")
            Log.i(TAG, "GLB bounds #${model.id}: minY=${"%.3f".format(minY)} maxY=${"%.3f".format(maxYLog)} " +
                "centerY=${"%.3f".format(model.boundsCenterY)} height=${"%.3f".format(heightM)}m " +
                "(origin at foot iff minY≈0; at hip iff minY≈-0.5·height; at head iff minY≈-height)")
            return model.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GLB", e)
            return -1
        }
    }

    fun getModel(id: Int): GpuModel? = modelIndex[id]
    fun removeModel(id: Int) {
        val m = modelIndex.remove(id) ?: return
        if (m.vao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = m.vao }, 0)
        if (m.vboPositions != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboPositions }, 0)
        if (m.vboNormals != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboNormals }, 0)
        if (m.vboTexCoords != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboTexCoords }, 0)
        if (m.vboTangents != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboTangents }, 0)
        if (m.ebo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.ebo }, 0)
        if (m.textureId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.textureId }, 0)
        if (m.normalMapTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.normalMapTexId }, 0)
        if (m.metallicRoughnessTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.metallicRoughnessTexId }, 0)
        if (m.emissiveTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.emissiveTexId }, 0)
        models.remove(m)
    }
    fun getAllModels(): List<GpuModel> = models.toList()

    // ── Light Emitter Marker ──

    private fun initEmitterGeometry() {
        // Simple octahedron (6 verts, 8 tris) — cheap and visible from all angles
        val r = emitterRadius
        val verts = floatArrayOf(
            0f, r, 0f,   0f,-r, 0f,   r, 0f, 0f,  -r, 0f, 0f,   0f, 0f, r,   0f, 0f,-r
        )
        val indices = intArrayOf(
            0,4,2, 0,2,5, 0,5,3, 0,3,4,  1,2,4, 1,5,2, 1,3,5, 1,4,3
        )
        emitterIndexCount = indices.size
        val vertBuf = allocFloatBuf(verts)
        val idxBuf = allocIntBuf(indices)
        val vaoBuf = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vaoBuf, 0)
        emitterVao = vaoBuf[0]
        GLES30.glBindVertexArray(emitterVao)
        val vboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, vboBuf, 0)
        emitterVbo = vboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, emitterVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vertBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        val eboBuf = intArrayOf(0)
        GLES30.glGenBuffers(1, eboBuf, 0)
        emitterEbo = eboBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, emitterEbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, idxBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        Log.i(TAG, "Light emitter geometry initialized")
    }

    fun renderEmitter(projection: FloatArray, viewMatrix: FloatArray, highlighted: Boolean) {
        if (!emitterVisible || emitterVao == 0 || gizmoProgramId == 0) return
        // Translate to emitter position
        scratchEmitterModel[0] = 1f; scratchEmitterModel[1] = 0f; scratchEmitterModel[2] = 0f; scratchEmitterModel[3] = 0f
        scratchEmitterModel[4] = 0f; scratchEmitterModel[5] = 1f; scratchEmitterModel[6] = 0f; scratchEmitterModel[7] = 0f
        scratchEmitterModel[8] = 0f; scratchEmitterModel[9] = 0f; scratchEmitterModel[10] = 1f; scratchEmitterModel[11] = 0f
        scratchEmitterModel[12] = emitterPos[0]; scratchEmitterModel[13] = emitterPos[1]; scratchEmitterModel[14] = emitterPos[2]; scratchEmitterModel[15] = 1f
        multiplyMat4(viewMatrix, scratchEmitterModel, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(gizmoProgramId)
        GLES30.glUniformMatrix4fv(uGizmoMVP, 1, false, scratchMat4B, 0)
        GLES30.glUniform1f(uGizmoHighlight, if (highlighted) 1f else 0f)
        // Override gizmo color to yellow/white glow
        GLES30.glBindVertexArray(emitterVao)
        // Use vertex attrib for color (yellow)
        GLES30.glVertexAttrib3f(1, 1f, 0.9f, 0.3f)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, emitterIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Test laser ray against emitter sphere. Returns hit distance or -1 */
    fun testEmitterHit(rayOrigin: FloatArray, rayDir: FloatArray): Float {
        val ocX = rayOrigin[0] - emitterPos[0]
        val ocY = rayOrigin[1] - emitterPos[1]
        val ocZ = rayOrigin[2] - emitterPos[2]
        val a = rayDir[0]*rayDir[0] + rayDir[1]*rayDir[1] + rayDir[2]*rayDir[2]
        val b = 2f * (ocX*rayDir[0] + ocY*rayDir[1] + ocZ*rayDir[2])
        val hitR = emitterRadius * 3f  // larger hit zone than visual
        val c = ocX*ocX + ocY*ocY + ocZ*ocZ - hitR*hitR
        val disc = b*b - 4f*a*c
        if (disc < 0f) return -1f
        val t = (-b - kotlin.math.sqrt(disc)) / (2f * a)
        return if (t > 0.01f) t else -1f
    }

    /** Update light direction from scene center toward emitter */
    fun updateLightFromEmitter(sceneCenterX: Float = 0f, sceneCenterY: Float = 0f, sceneCenterZ: Float = 0f) {
        lightPivot[0] = sceneCenterX; lightPivot[1] = sceneCenterY; lightPivot[2] = sceneCenterZ
        val dx = emitterPos[0] - sceneCenterX
        val dy = emitterPos[1] - sceneCenterY
        val dz = emitterPos[2] - sceneCenterZ
        val len = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.001f)
        lightDir = floatArrayOf(dx/len, dy/len, dz/len)
        updateFillLightDirFromMainLight()
        lightElevDeg = Math.toDegrees(kotlin.math.asin((dy/len).toDouble())).toFloat()
        var az = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        if (az < 0f) az += 360f
        lightAngleDeg = az
        // The emitter is already where the user put it — record the angles it
        // reflects so syncEmitterToAngles() doesn't move it out from under a drag.
        emitterSyncedAz = lightAngleDeg
        emitterSyncedEl = lightElevDeg
    }

    // ── Room Occlusion ──
    // Renders detected room planes (walls, tables, floor) as depth-only geometry
    // so that real-world surfaces block/occlude virtual model rendering.
    // Must be called AFTER renderEye() sets up the FBO but BEFORE models are drawn.
    // Since renderEye() draws models, we call this from within renderEye itself.

    fun renderOcclusionPlanes(projection: FloatArray, viewMatrix: FloatArray) {
        if (!occlusionEnabled || shadowPlanes.isEmpty()) return
        // Write ONLY to depth buffer — no color output
        GLES30.glColorMask(false, false, false, false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Use shadow depth shader (just transforms positions, no color)
        GLES30.glUseProgram(shadowDepthProgramId)

        for (plane in shadowPlanes) {
            // Only occlude with large planes above the floor (bed, table, furniture)
            // Skip floor, ceiling, and small/noisy planes (C++: 2=floor, 3=ceiling)
            if (plane.label == 2 || plane.label == 3) continue  // skip floor and ceiling
            if (plane.posY < gridHeight + 0.15f) continue  // skip floor-level planes
            if (plane.extentX < 0.25f || plane.extentY < 0.25f) continue  // skip small planes

            buildPlaneModel(plane)
            multiplyMat4(viewMatrix, scratchPlaneModel, scratchMat4A)
            multiplyMat4(projection, scratchMat4A, scratchMat4B)
            GLES30.glUniformMatrix4fv(uShadowDepthMVP, 1, false, scratchMat4B, 0)

            GLES30.glBindVertexArray(spVao)  // reuse shadow plane quad geometry
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        }

        GLES30.glBindVertexArray(0)
        // Re-enable color writes for model rendering
        GLES30.glColorMask(true, true, true, true)
    }

    // ── Shadow Map ──

    fun renderShadowMap() {
        if (!initialized || !shadowEnabled || models.isEmpty()) return
        // Compute average model XZ so shadow frustum travels with the scene.
        var cx = 0f; var cz = 0f; var n = 0
        for (m in models) {
            val mm = m.modelMatrix
            if (mm.size >= 16) { cx += mm[12]; cz += mm[14]; n++ }
        }
        if (n > 0) { cx /= n; cz /= n }
        shadowLightMatrix = computeLightSpaceMatrix(cx, cz)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowMapFbo)

        // Verify FBO is still valid
        val fboStat = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStat != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            if (frameCount < 5) Log.e(TAG, "Shadow FBO broke: 0x${Integer.toHexString(fboStat)}")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }

        GLES30.glViewport(0, 0, shadowMapSize, shadowMapSize)
        GLES30.glClearColor(1f, 1f, 1f, 1f)  // depth=1.0 = far = no shadow
        GLES30.glClearDepthf(1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        // Cull front faces to reduce shadow acne
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_FRONT)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(shadowDepthProgramId)

        for (m in models) {
            if (m.indexCount == 0) continue
            multiplyMat4(shadowLightMatrix, m.modelMatrix, scratchMat4A)
            GLES30.glUniformMatrix4fv(uShadowDepthMVP, 1, false, scratchMat4A, 0)
            // Tier 4 — shadow pass needs the same skinning as the color pass
            // or the silhouette detaches from the posed body. Bind the already-
            // uploaded UBO (color pass did the glBufferSubData) to the shared
            // binding point and toggle the skin gate.
            val skel = m.skeleton
            if (m.isSkinned && skel != null && m.uboJoints != 0) {
                GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, JOINT_UBO_BINDING, m.uboJoints)
                GLES30.glUniform1f(uShadowDepthSkinned, 1f)
            } else {
                GLES30.glUniform1f(uShadowDepthSkinned, 0f)
            }
            GLES30.glBindVertexArray(m.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR && frameCount < 5) {
            Log.e(TAG, "Shadow GL error: 0x${Integer.toHexString(err)}")
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ── Eye Render ──

    fun renderEye(swapchainTexId: Int, width: Int, height: Int,
                  projection: FloatArray, viewMatrix: FloatArray) {
        if (!initialized) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, swapchainTexId, 0)
        if (width != lastDepthW || height != lastDepthH) {
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, depthRb)
            lastDepthW = width; lastDepthH = height
        }
        GLES30.glViewport(0, 0, width, height)

        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            if (frameCount < 10 || frameCount % 500 == 0)
                Log.e(TAG, "FBO incomplete: 0x${Integer.toHexString(fboStatus)}")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }

        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Room occlusion: write detected planes to depth buffer (no color)
        // so real-world surfaces (walls, tables) block model rendering
        renderOcclusionPlanes(projection, viewMatrix)

        // Real-world scene-mesh occlusion (native polygon-plane depth pass).
        // Runs alongside the coarse rectangular occlusion above; the native
        // path uses polygon vertices for better-fitting occluders.
        sceneOcclusionHook?.invoke(projection, viewMatrix)

        if (models.isEmpty()) { frameCount++; return }

        GLES30.glUseProgram(programId)
        // Transform light directions from world space to view space
        // (normals are in view space, so lights must be too)
        val v = viewMatrix
        val vldX = v[0]*lightDir[0] + v[4]*lightDir[1] + v[8]*lightDir[2]
        val vldY = v[1]*lightDir[0] + v[5]*lightDir[1] + v[9]*lightDir[2]
        val vldZ = v[2]*lightDir[0] + v[6]*lightDir[1] + v[10]*lightDir[2]
        GLES30.glUniform3f(uLightDir, vldX, vldY, vldZ)
        GLES30.glUniform3f(uLightColor, lightColor[0], lightColor[1], lightColor[2])
        GLES30.glUniform1f(uLightIntensity, lightIntensity)
        val vflX = v[0]*fillLightDir[0] + v[4]*fillLightDir[1] + v[8]*fillLightDir[2]
        val vflY = v[1]*fillLightDir[0] + v[5]*fillLightDir[1] + v[9]*fillLightDir[2]
        val vflZ = v[2]*fillLightDir[0] + v[6]*fillLightDir[1] + v[10]*fillLightDir[2]
        GLES30.glUniform3f(uFillLightDir, vflX, vflY, vflZ)
        GLES30.glUniform3f(uFillLightColor, fillLightColor[0], fillLightColor[1], fillLightColor[2])
        GLES30.glUniform1f(uFillLightIntensity, fillLightIntensity)
        GLES30.glUniform3f(uAmbientColor, ambientColor[0], ambientColor[1], ambientColor[2])
        GLES30.glUniform1f(uAmbientIntensity, ambientIntensity)
        GLES30.glUniform1f(uClipY, gridHeight)
        GLES30.glUniform1f(uUseSH, if (useSH) 1.0f else 0.0f)
        if (useSH) {
            GLES30.glUniform3fv(uSH, 9, shCoefficients, 0)
        }

        for (m in models) {
            if (m.indexCount == 0) continue
            multiplyMat4(viewMatrix, m.modelMatrix, scratchMat4A) // modelView
            multiplyMat4(projection, scratchMat4A, scratchMat4B) // mvp
            extractNormalMatrix(scratchMat4A, scratchNormal)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, scratchMat4B, 0)
            GLES30.glUniformMatrix4fv(uModelView, 1, false, scratchMat4A, 0)
            GLES30.glUniformMatrix4fv(uModelMat, 1, false, m.modelMatrix, 0)
            GLES30.glUniformMatrix3fv(uNormalMatrix, 1, false, scratchNormal, 0)
            GLES30.glUniform1f(uMetallic, m.metallic)
            GLES30.glUniform1f(uRoughness, m.roughness)
            GLES30.glUniform1f(uExposure, m.exposure)
            GLES30.glUniform1f(uSelected, if (m.selected) 1.0f else 0.0f)
            GLES30.glUniform1f(uContrast, m.contrast)
            GLES30.glUniform1f(uSaturation, m.saturation)
            // Base color (unit 0)
            if (m.textureId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.textureId)
                GLES30.glUniform1i(uBaseColor, 0)
            }
            // Normal map (unit 1)
            GLES30.glUniform1f(uHasNormalMap, if (m.hasNormalMap) 1.0f else 0.0f)
            if (m.hasNormalMap) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.normalMapTexId)
                GLES30.glUniform1i(uNormalMap, 1)
            }
            // Metallic-roughness map (unit 2)
            val hasMR = m.metallicRoughnessTexId != 0
            GLES30.glUniform1f(uHasMetRoughMap, if (hasMR) 1.0f else 0.0f)
            if (hasMR) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.metallicRoughnessTexId)
                GLES30.glUniform1i(uMetRoughMap, 2)
            }
            // Emissive map (unit 3)
            val hasEmissive = m.emissiveTexId != 0
            GLES30.glUniform1f(uHasEmissiveMap, if (hasEmissive) 1.0f else 0.0f)
            if (hasEmissive) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.emissiveTexId)
                GLES30.glUniform1i(uEmissiveMap, 3)
                GLES30.glUniform3f(uEmissiveFactor, m.emissiveFactor[0], m.emissiveFactor[1], m.emissiveFactor[2])
            }
            // Tier 3 Feature 4 — glute deformation (radius 0 → no-op in shader).
            GLES30.glUniform3f(uGluteAPos, m.gluteAPos[0], m.gluteAPos[1], m.gluteAPos[2])
            GLES30.glUniform3f(uGluteBPos, m.gluteBPos[0], m.gluteBPos[1], m.gluteBPos[2])
            GLES30.glUniform1f(uGluteARadius, m.gluteARadius)
            GLES30.glUniform1f(uGluteBRadius, m.gluteBRadius)
            GLES30.glUniform1f(uGluteAPush, m.gluteAPush)
            GLES30.glUniform1f(uGluteBPush, m.gluteBPush)
            // Tier 4 — joint palette upload + shader skin gate. Scratch-buffer
            // upload: clamp to MAX_JOINTS_SHADER so a rig exceeding the palette
            // size doesn't overflow (we warn at parse time).
            val skel = m.skeleton
            if (m.isSkinned && skel != null && m.uboJoints != 0) {
                // Refresh the palette from the dance loop's joint writes.
                // Safe to call every frame — at bind pose this produces identity
                // jointMatrices (and the shader's LBS degenerates to pass-through).
                skel.update()
                val uploadJoints = kotlin.math.min(skel.jointCount, MAX_JOINTS_SHADER)
                System.arraycopy(skel.palette, 0, jointPaletteScratch, 0, uploadJoints * 16)
                if (uploadJoints < MAX_JOINTS_SHADER) {
                    // Zero-pad to avoid leaking a prior model's palette into unused slots.
                    java.util.Arrays.fill(jointPaletteScratch, uploadJoints * 16, MAX_JOINTS_SHADER * 16, 0f)
                }
                val fb = java.nio.ByteBuffer.allocateDirect(MAX_JOINTS_SHADER * 64)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(jointPaletteScratch); fb.position(0)
                GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, m.uboJoints)
                GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, MAX_JOINTS_SHADER * 64, fb)
                GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)
                GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, JOINT_UBO_BINDING, m.uboJoints)
                GLES30.glUniform1f(uSkinned, 1f)
            } else {
                GLES30.glUniform1f(uSkinned, 0f)
            }
            GLES30.glBindVertexArray(m.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
        if (frameCount < 3) Log.i(TAG, "Frame $frameCount: ${models.size} models")
        frameCount++
        GLES30.glBindVertexArray(0)
        // FBO stays bound
    }

    // ── Grid with Shadows ──

    fun renderGrid(swapchainTexId: Int, width: Int, height: Int,
                   projection: FloatArray, viewMatrix: FloatArray,
                   gridScale: Float = 0.25f, gridAlpha: Float = 0.3f) {
        if (!initialized || gridProgramId == 0) return
        // FBO should be bound from renderEye
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // Grid model matrix: translate to gridHeight
        scratchGridModel[0] = 1f; scratchGridModel[1] = 0f; scratchGridModel[2] = 0f; scratchGridModel[3] = 0f
        scratchGridModel[4] = 0f; scratchGridModel[5] = 1f; scratchGridModel[6] = 0f; scratchGridModel[7] = 0f
        scratchGridModel[8] = 0f; scratchGridModel[9] = 0f; scratchGridModel[10] = 1f; scratchGridModel[11] = 0f
        scratchGridModel[12] = 0f; scratchGridModel[13] = gridHeight; scratchGridModel[14] = 0f; scratchGridModel[15] = 1f
        multiplyMat4(viewMatrix, scratchGridModel, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)
        GLES30.glUseProgram(gridProgramId)
        GLES30.glUniformMatrix4fv(uGridMVP, 1, false, scratchMat4B, 0)
        GLES30.glUniform1f(uGridY, gridHeight)
        GLES30.glUniform1f(uGridScale, gridScale)
        GLES30.glUniform1f(uGridAlpha, gridAlpha)

        // Shadow uniforms
        GLES30.glUniformMatrix4fv(uGridShadowMVP, 1, false, shadowLightMatrix, 0)
        GLES30.glUniform1f(uGridShadowDarkness, if (shadowEnabled) shadowDarkness else 0f)
        GLES30.glUniform1f(uGridShadowSoftness, shadowSoftness)
        GLES30.glUniform1f(uGridLightSize, lightSize)

        // Bind shadow map to texture unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowMapTexId)
        GLES30.glUniform1i(uGridShadowMap, 0)

        GLES30.glBindVertexArray(gridVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ── Shadow Planes ──

    /** Render a fullscreen color wash with selectable blend mode */
    fun renderColorWash(r: Float, g: Float, b: Float, a: Float, blendMode: Int = 0) {
        if (a < 0.005f || washProgramId == 0) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        // Set blend function and compute fragment color based on mode
        var fr = r; var fg = g; var fb = b; var fa = a
        when (blendMode) {
            0 -> { // NORMAL: standard alpha blend
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            }
            1 -> { // ADD: additive — bright neon flash
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            }
            2 -> { // MULTIPLY: color gel — darkens with color tint
                GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_SRC_COLOR)
                // Fragment = mix(white, color, alpha) so at a=0 it's white (no change)
                fr = 1f - a * (1f - r); fg = 1f - a * (1f - g); fb = 1f - a * (1f - b); fa = 1f
            }
            3 -> { // SCREEN: bright but softer than additive
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
                fr = r * a; fg = g * a; fb = b * a; fa = a
            }
        }

        GLES30.glUseProgram(washProgramId)
        GLES30.glUniform4f(uWashColor, fr, fg, fb, fa)
        GLES30.glBindVertexArray(washVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA) // reset
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun renderShadowPlanes(projection: FloatArray, viewMatrix: FloatArray) {
        if (!initialized || shadowPlaneProgramId == 0 || shadowPlanes.isEmpty() || !shadowEnabled) return

        GLES30.glUseProgram(shadowPlaneProgramId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // Shadow map on texture unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowMapTexId)
        GLES30.glUniform1i(uSpShadowMap, 0)
        GLES30.glUniformMatrix4fv(uSpShadowMVP, 1, false, shadowLightMatrix, 0)
        GLES30.glUniform1f(uSpShadowDarkness, shadowDarkness)
        GLES30.glUniform1f(uSpShadowSoftness, shadowSoftness)
        GLES30.glUniform1f(uSpLightSize, lightSize)

        for (plane in shadowPlanes) {
            if (plane.label == 3) continue  // skip ceilings (C++: 3=ceiling)

            buildPlaneModel(plane)
            multiplyMat4(viewMatrix, scratchPlaneModel, scratchMat4A)
            multiplyMat4(projection, scratchMat4A, scratchMat4B)

            GLES30.glUniformMatrix4fv(uSpMVP, 1, false, scratchMat4B, 0)
            GLES30.glUniformMatrix4fv(uSpModel, 1, false, scratchPlaneModel, 0)

            GLES30.glBindVertexArray(spVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ── Plane Visualization ──

    fun renderPlaneVisualization(projection: FloatArray, viewMatrix: FloatArray) {
        if (!initialized || planeVisProgramId == 0 || shadowPlanes.isEmpty() || !showPlaneVisualization) return

        if (planeVisStartTime == 0L) planeVisStartTime = System.nanoTime()
        val time = (System.nanoTime() - planeVisStartTime) / 1_000_000_000f

        GLES30.glUseProgram(planeVisProgramId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ZERO, GLES30.GL_ONE) // preserve dest alpha for passthrough
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        GLES30.glUniform1f(uPvTime, time)

        for ((planeIdx, plane) in shadowPlanes.withIndex()) {
            // Selected plane gets bright highlight
            val isSelected = planeIdx == selectedPlaneIndex
            val normalY = 1f - 2f * (plane.rotX * plane.rotX + plane.rotZ * plane.rotZ)
            val isHorizontal = normalY > 0.7f
            val heightAboveFloor = plane.posY - gridHeight
            val color = if (isSelected) PV_COLOR_SELECTED else when {
                isHorizontal && heightAboveFloor < 0.3f -> PV_COLOR_FLOOR
                isHorizontal && heightAboveFloor > 1.8f -> PV_COLOR_CEILING
                isHorizontal -> PV_COLOR_TABLE
                else -> PV_COLOR_WALL
            }

            buildPlaneModel(plane)
            multiplyMat4(viewMatrix, scratchPlaneModel, scratchMat4A)
            multiplyMat4(projection, scratchMat4A, scratchMat4B)

            GLES30.glUniformMatrix4fv(uPvMVP, 1, false, scratchMat4B, 0)
            GLES30.glUniformMatrix4fv(uPvModel, 1, false, scratchPlaneModel, 0)
            GLES30.glUniform4fv(uPvColor, 1, color, 0)

            GLES30.glBindVertexArray(spVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ── Gizmo ──

    fun renderGizmo(projection: FloatArray, viewMatrix: FloatArray,
                    modelPos: FloatArray, modelRot: FloatArray, highlightAxis: Int) {
        if (!initialized || gizmoProgramId == 0) return

        // Gizmo model matrix: position + rotation, no scale
        quatToMatrix(modelRot, modelPos, scratchQuat)
        multiplyMat4(viewMatrix, scratchQuat, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(gizmoProgramId)
        GLES30.glUniformMatrix4fv(uGizmoMVP, 1, false, scratchMat4B, 0)

        GLES30.glBindVertexArray(gizmoVao)
        // Draw each axis with highlight
        for (axis in 0..2) {
            val hl = if (axis == highlightAxis) 1.0f else 0.0f
            GLES30.glUniform1f(uGizmoHighlight, hl)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gizmoIndicesPerAxis,
                GLES30.GL_UNSIGNED_INT, axis * gizmoIndicesPerAxis * 4)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Test laser ray against gizmo axes. Returns (axis, distance) or (NONE, -1) */
    fun testGizmoHit(rayOrigin: FloatArray, rayDir: FloatArray,
                     modelPos: FloatArray, modelRot: FloatArray): Pair<Int, Float> {
        var bestAxis = GIZMO_AXIS_NONE
        var bestDist = Float.MAX_VALUE

        for (i in 0..2) {
            // Transform axis to world space
            rotateVecByQuat(GIZMO_AXES[i], modelRot, scratchVec3)
            // Ray-line closest distance
            val result = rayLineClosest(rayOrigin, rayDir, modelPos, scratchVec3, GIZMO_LENGTH)
            if (result != null && result.first < GIZMO_HIT_RADIUS && result.second < bestDist) {
                bestAxis = i
                bestDist = result.second
            }
        }
        return Pair(bestAxis, if (bestAxis >= 0) bestDist else -1f)
    }

    /** Get world-space axis direction for a gizmo axis */
    private val scratchGizmoAxis = FloatArray(3)
    fun getGizmoWorldAxis(axis: Int, modelRot: FloatArray): FloatArray {
        val local = if (axis in 0..2) GIZMO_AXES[axis] else GIZMO_AXES[0]
        rotateVecByQuat(local, modelRot, scratchGizmoAxis)
        return scratchGizmoAxis
    }

    // ── Laser ──

    fun renderLaser(swapchainTexId: Int, width: Int, height: Int,
                    projection: FloatArray, viewMatrix: FloatArray,
                    handPos: FloatArray, aimQuat: FloatArray,
                    hitDistance: Float, color: FloatArray = DEFAULT_LASER_COLOR,
                    dotScale: Float = 0.01f, dotColor: FloatArray = DEFAULT_LASER_COLOR) {
        if (!initialized || laserProgramId == 0) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        quatToMatrix(aimQuat, handPos, scratchQuat)
        multiplyMat4(viewMatrix, scratchQuat, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)

        GLES30.glUseProgram(laserProgramId)
        GLES30.glUniformMatrix4fv(uLaserMVP, 1, false, scratchMat4B, 0)
        GLES30.glUniform3f(uLaserColor, color[0], color[1], color[2])
        val beamLength = if (hitDistance > 0f) hitDistance.coerceAtLeast(0.02f) else LASER_BASE_LENGTH
        val lenScale = beamLength / LASER_BASE_LENGTH
        GLES30.glUniform1f(uLaserLenScale, lenScale)

        GLES30.glBindVertexArray(laserVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, laserIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)

        if (hitDistance > 0f) {
            // Disable depth test so dot always renders on top of panel/models
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            quatForward(aimQuat, scratchVec3)
            val dotDist = hitDistance - 0.002f
            scratchDotModel[0] = dotScale; scratchDotModel[1] = 0f; scratchDotModel[2] = 0f; scratchDotModel[3] = 0f
            scratchDotModel[4] = 0f; scratchDotModel[5] = dotScale; scratchDotModel[6] = 0f; scratchDotModel[7] = 0f
            scratchDotModel[8] = 0f; scratchDotModel[9] = 0f; scratchDotModel[10] = dotScale; scratchDotModel[11] = 0f
            scratchDotModel[12] = handPos[0] + scratchVec3[0] * dotDist
            scratchDotModel[13] = handPos[1] + scratchVec3[1] * dotDist
            scratchDotModel[14] = handPos[2] + scratchVec3[2] * dotDist
            scratchDotModel[15] = 1f
            multiplyMat4(viewMatrix, scratchDotModel, scratchMat4A)
            multiplyMat4(projection, scratchMat4A, scratchMat4B)
            GLES30.glUniformMatrix4fv(uLaserMVP, 1, false, scratchMat4B, 0)
            GLES30.glUniform3f(uLaserColor, dotColor[0], dotColor[1], dotColor[2])
            GLES30.glBindVertexArray(dotVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 4)
            GLES30.glBindVertexArray(0)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Render a bright front-facing marker (hot-pink cross) at a world-space
     *  point. Used to show WHICH WAY is the model's front so the user doesn't
     *  have to guess when placing dance presets or marking anatomy. Reuses
     *  the laser dot VAO for cheap. Rendered on top of everything. */
    fun renderFacingMarker(projection: FloatArray, viewMatrix: FloatArray,
                           worldX: Float, worldY: Float, worldZ: Float,
                           scale: Float = 0.04f,
                           r: Float = 1f, g: Float = 0.2f, b: Float = 0.6f) {
        if (!initialized || laserProgramId == 0) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        scratchDotModel[0] = scale; scratchDotModel[1] = 0f; scratchDotModel[2] = 0f; scratchDotModel[3] = 0f
        scratchDotModel[4] = 0f; scratchDotModel[5] = scale; scratchDotModel[6] = 0f; scratchDotModel[7] = 0f
        scratchDotModel[8] = 0f; scratchDotModel[9] = 0f; scratchDotModel[10] = scale; scratchDotModel[11] = 0f
        scratchDotModel[12] = worldX; scratchDotModel[13] = worldY; scratchDotModel[14] = worldZ
        scratchDotModel[15] = 1f
        multiplyMat4(viewMatrix, scratchDotModel, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)
        GLES30.glUseProgram(laserProgramId)
        GLES30.glUniformMatrix4fv(uLaserMVP, 1, false, scratchMat4B, 0)
        GLES30.glUniform3f(uLaserColor, r, g, b)
        GLES30.glUniform1f(uLaserLenScale, 1f)
        GLES30.glBindVertexArray(dotVao)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun finishEyePass() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }

    // Panel position — settable for drag
    var panelX = 0f; var panelY = 0f; var panelZ = -1f
    var panelW = 0.9f; var panelH = 1.0f

    fun renderUiOverlay(swapchainTexId: Int, width: Int, height: Int, uiTexId: Int,
                        projection: FloatArray, viewMatrix: FloatArray) {
        if (uiProgramId == 0 || uiVao == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, swapchainTexId, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Billboard: extract camera position from view matrix (inverse translation)
        val camX = -(viewMatrix[0]*viewMatrix[12] + viewMatrix[1]*viewMatrix[13] + viewMatrix[2]*viewMatrix[14])
        val camY = -(viewMatrix[4]*viewMatrix[12] + viewMatrix[5]*viewMatrix[13] + viewMatrix[6]*viewMatrix[14])
        val camZ = -(viewMatrix[8]*viewMatrix[12] + viewMatrix[9]*viewMatrix[13] + viewMatrix[10]*viewMatrix[14])

        // Forward = camera → panel (panel's Z axis points away from camera)
        var fwdX = panelX - camX; var fwdY = panelY - camY; var fwdZ = panelZ - camZ
        val fwdLen = kotlin.math.sqrt(fwdX*fwdX + fwdY*fwdY + fwdZ*fwdZ).coerceAtLeast(0.001f)
        fwdX /= fwdLen; fwdY /= fwdLen; fwdZ /= fwdLen

        // Right = cross(fwd, worldUp) — left-to-right from viewer's perspective
        var rightX = fwdY*0f - fwdZ*1f  // fwd cross up=(0,1,0)
        var rightY = fwdZ*0f - fwdX*0f
        var rightZ = fwdX*1f - fwdY*0f
        val rLen = kotlin.math.sqrt(rightX*rightX + rightY*rightY + rightZ*rightZ).coerceAtLeast(0.001f)
        rightX /= rLen; rightY /= rLen; rightZ /= rLen

        // Up = cross(fwd, right)
        val bupX = fwdY*rightZ - fwdZ*rightY
        val bupY = fwdZ*rightX - fwdX*rightZ
        val bupZ = fwdX*rightY - fwdY*rightX

        // Column-major: right axis scaled by W, up axis (negated) scaled by H, fwd, translation
        scratchPanelModel[0] = rightX*panelW;  scratchPanelModel[1] = rightY*panelW;  scratchPanelModel[2] = rightZ*panelW;  scratchPanelModel[3] = 0f
        scratchPanelModel[4] = -bupX*panelH;   scratchPanelModel[5] = -bupY*panelH;   scratchPanelModel[6] = -bupZ*panelH;   scratchPanelModel[7] = 0f
        scratchPanelModel[8] = fwdX;           scratchPanelModel[9] = fwdY;           scratchPanelModel[10] = fwdZ;          scratchPanelModel[11] = 0f
        scratchPanelModel[12] = panelX;         scratchPanelModel[13] = panelY;         scratchPanelModel[14] = panelZ;        scratchPanelModel[15] = 1f

        multiplyMat4(viewMatrix, scratchPanelModel, scratchMat4A)
        multiplyMat4(projection, scratchMat4A, scratchMat4B)
        GLES30.glUseProgram(uiProgramId)
        GLES30.glUniformMatrix4fv(uiUMvp, 1, false, scratchMat4B, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uiTexId)
        GLES30.glUniform1i(uiUTex, 0)
        GLES30.glBindVertexArray(uiVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ── Bloom Post-Process ──

    private fun initBloomFBOs(width: Int, height: Int) {
        bloomW = width / 4; bloomH = height / 4
        // Clean up old if re-creating
        if (bloomTexA != 0) GLES30.glDeleteTextures(1, intArrayOf(bloomTexA), 0)
        if (bloomTexB != 0) GLES30.glDeleteTextures(1, intArrayOf(bloomTexB), 0)
        if (bloomFboA != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFboA), 0)
        if (bloomFboB != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFboB), 0)
        // Texture A
        val tBuf = intArrayOf(0)
        GLES30.glGenTextures(1, tBuf, 0); bloomTexA = tBuf[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bloomW, bloomH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // Texture B
        GLES30.glGenTextures(1, tBuf, 0); bloomTexB = tBuf[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexB)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bloomW, bloomH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // FBO A → Texture A
        val fBuf = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fBuf, 0); bloomFboA = fBuf[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTexA, 0)
        // FBO B → Texture B
        GLES30.glGenFramebuffers(1, fBuf, 0); bloomFboB = fBuf[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboB)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTexB, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        Log.i(TAG, "Bloom FBOs initialized ${bloomW}x${bloomH}")
    }

    fun renderBloom(swapchainTexId: Int, width: Int, height: Int) {
        if (!bloomEnabled || bloomBrightProgramId == 0 || bloomIntensity <= 0f) return
        // Lazy init bloom FBOs at correct resolution
        if (bloomW != width / 4 || bloomH != height / 4) initBloomFBOs(width, height)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Pass 1: Bright extraction (scene → bloom A, quarter res)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
        GLES30.glViewport(0, 0, bloomW, bloomH)
        GLES30.glUseProgram(bloomBrightProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, swapchainTexId)
        GLES30.glUniform1i(uBloomBrightScene, 0)
        GLES30.glUniform1f(uBloomBrightThreshold, bloomThreshold)
        GLES30.glBindVertexArray(washVao)  // reuse empty VAO for fullscreen triangle
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // Pass 2: Horizontal blur (bloom A → bloom B)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboB)
        GLES30.glUseProgram(bloomBlurProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
        GLES30.glUniform1i(uBloomBlurTex, 0)
        GLES30.glUniform2f(uBloomBlurDir, 1f / bloomW, 0f)
        GLES30.glBindVertexArray(washVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // Pass 3: Vertical blur (bloom B → bloom A)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexB)
        GLES30.glUniform2f(uBloomBlurDir, 0f, 1f / bloomH)
        GLES30.glBindVertexArray(washVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // Pass 4: Composite bloom onto scene (additive RGB only — preserve alpha for passthrough)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, swapchainTexId, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)  // additive
        GLES30.glColorMask(true, true, true, false)  // DON'T touch alpha — passthrough needs alpha=0
        GLES30.glUseProgram(bloomCompositeProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
        GLES30.glUniform1i(uBloomCompBloom, 0)
        GLES30.glUniform1f(uBloomCompIntensity, bloomIntensity)
        GLES30.glBindVertexArray(washVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        GLES30.glBindVertexArray(0)
        GLES30.glColorMask(true, true, true, true)  // restore
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA) // reset
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun destroy() {
        for (m in models) {
            if (m.vao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = m.vao }, 0)
            if (m.vboPositions != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboPositions }, 0)
            if (m.vboNormals != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboNormals }, 0)
            if (m.vboTexCoords != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboTexCoords }, 0)
            if (m.vboTangents != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.vboTangents }, 0)
            if (m.ebo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = m.ebo }, 0)
            if (m.textureId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.textureId }, 0)
            if (m.normalMapTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.normalMapTexId }, 0)
            if (m.metallicRoughnessTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.metallicRoughnessTexId }, 0)
            if (m.emissiveTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = m.emissiveTexId }, 0)
        }
        models.clear()
        modelIndex.clear()
        if (programId != 0) GLES30.glDeleteProgram(programId)
        if (shadowDepthProgramId != 0) GLES30.glDeleteProgram(shadowDepthProgramId)
        if (laserProgramId != 0) GLES30.glDeleteProgram(laserProgramId)
        if (gridProgramId != 0) GLES30.glDeleteProgram(gridProgramId)
        if (gizmoProgramId != 0) GLES30.glDeleteProgram(gizmoProgramId)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, glIdBuf.also { it[0] = fbo }, 0)
        if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, glIdBuf.also { it[0] = depthRb }, 0)
        if (shadowMapFbo != 0) GLES30.glDeleteFramebuffers(1, glIdBuf.also { it[0] = shadowMapFbo }, 0)
        if (shadowMapTexId != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = shadowMapTexId }, 0)
        if (shadowColorRb != 0) GLES30.glDeleteRenderbuffers(1, glIdBuf.also { it[0] = shadowColorRb }, 0)
        if (laserVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = laserVao }, 0)
        if (laserVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = laserVbo }, 0)
        if (dotVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = dotVao }, 0)
        if (dotVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = dotVbo }, 0)
        if (gridVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = gridVao }, 0)
        if (gridVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = gridVbo }, 0)
        if (gizmoVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = gizmoVao }, 0)
        if (gizmoVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = gizmoVbo }, 0)
        if (gizmoEbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = gizmoEbo }, 0)
        if (shadowPlaneProgramId != 0) GLES30.glDeleteProgram(shadowPlaneProgramId)
        if (planeVisProgramId != 0) GLES30.glDeleteProgram(planeVisProgramId)
        if (spVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = spVao }, 0)
        if (spVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = spVbo }, 0)
        if (uiProgramId != 0) GLES30.glDeleteProgram(uiProgramId)
        if (uiVao != 0) GLES30.glDeleteVertexArrays(1, glIdBuf.also { it[0] = uiVao }, 0)
        if (uiVbo != 0) GLES30.glDeleteBuffers(1, glIdBuf.also { it[0] = uiVbo }, 0)
        // Bloom cleanup
        if (bloomBrightProgramId != 0) GLES30.glDeleteProgram(bloomBrightProgramId)
        if (bloomBlurProgramId != 0) GLES30.glDeleteProgram(bloomBlurProgramId)
        if (bloomCompositeProgramId != 0) GLES30.glDeleteProgram(bloomCompositeProgramId)
        if (bloomTexA != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = bloomTexA }, 0)
        if (bloomTexB != 0) GLES30.glDeleteTextures(1, glIdBuf.also { it[0] = bloomTexB }, 0)
        if (bloomFboA != 0) GLES30.glDeleteFramebuffers(1, glIdBuf.also { it[0] = bloomFboA }, 0)
        if (bloomFboB != 0) GLES30.glDeleteFramebuffers(1, glIdBuf.also { it[0] = bloomFboB }, 0)
        Log.i(TAG, "GLES renderer destroyed")
    }

    // ── Helpers ──

    private fun allocFloatBuf(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        return buf
    }
    private fun allocIntBuf(data: IntArray): IntBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(data).position(0)
        return buf
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset+1].toInt() and 0xFF) shl 8) or
                ((bytes[offset+2].toInt() and 0xFF) shl 16) or
                ((bytes[offset+3].toInt() and 0xFF) shl 24)
    }
    private fun extractFloats(bytes: ByteArray, offset: Int, count: Int): FloatBuffer {
        val buf = ByteBuffer.wrap(bytes, offset, count*4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val fb = FloatBuffer.allocate(count); fb.put(buf); fb.position(0); return fb
    }
    /** Extract signed bytes as normalized floats [-1,1] with stride support (KHR_mesh_quantization) */
    private fun extractBytesAsFloats(bytes: ByteArray, offset: Int, elemCount: Int, components: Int, stride: Int): FloatBuffer {
        val fb = FloatBuffer.allocate(elemCount * components)
        for (i in 0 until elemCount) {
            val base = offset + i * stride
            for (c in 0 until components) {
                fb.put(bytes[base + c].toFloat() / 127f)
            }
        }
        fb.position(0); return fb
    }
    /** Extract signed shorts as normalized floats [-1,1] with stride support */
    private fun extractShortsAsFloats(bytes: ByteArray, offset: Int, elemCount: Int, components: Int, stride: Int): FloatBuffer {
        val fb = FloatBuffer.allocate(elemCount * components)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until elemCount) {
            val base = offset + i * stride
            for (c in 0 until components) {
                fb.put(bb.getShort(base + c * 2).toFloat() / 32767f)
            }
        }
        fb.position(0); return fb
    }
    private fun extractIndices(bytes: ByteArray, offset: Int, count: Int, componentType: Int): IntBuffer {
        val ib = IntBuffer.allocate(count)
        when (componentType) {
            5123 -> { val sb = ByteBuffer.wrap(bytes, offset, count*2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until count) ib.put(sb.get().toInt() and 0xFFFF) }
            5125 -> { val ub = ByteBuffer.wrap(bytes, offset, count*4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (i in 0 until count) ib.put(ub.get()) }
        }
        ib.position(0); return ib
    }
    private fun computeNormals(positions: FloatBuffer, indices: IntBuffer, vertCount: Int, idxCount: Int): FloatBuffer {
        val normals = FloatArray(vertCount * 3)
        positions.position(0); indices.position(0)
        val pos = FloatArray(vertCount * 3); positions.get(pos)
        for (i in 0 until idxCount step 3) {
            indices.position(i)
            val i0 = indices.get(); val i1 = indices.get(); val i2 = indices.get()
            val ax = pos[i1*3]-pos[i0*3]; val ay = pos[i1*3+1]-pos[i0*3+1]; val az = pos[i1*3+2]-pos[i0*3+2]
            val bx = pos[i2*3]-pos[i0*3]; val by = pos[i2*3+1]-pos[i0*3+1]; val bz = pos[i2*3+2]-pos[i0*3+2]
            val nx = ay*bz-az*by; val ny = az*bx-ax*bz; val nz = ax*by-ay*bx
            for (idx in intArrayOf(i0,i1,i2)) { normals[idx*3]+=nx; normals[idx*3+1]+=ny; normals[idx*3+2]+=nz }
        }
        for (v in 0 until vertCount) {
            val x=normals[v*3]; val y=normals[v*3+1]; val z=normals[v*3+2]
            val len = kotlin.math.sqrt(x*x+y*y+z*z)
            if (len > 0.0001f) { normals[v*3]=x/len; normals[v*3+1]=y/len; normals[v*3+2]=z/len }
        }
        positions.position(0); indices.position(0)
        return FloatBuffer.wrap(normals)
    }
    /** Unweld-based tangent generation — replaces the broken index-welded
     *  computeTangents for meshes with normal maps but no pre-baked TANGENT
     *  attribute. The old approach accumulated tangents at shared vertex
     *  indices, which cancels at UV seams and produces the "war paint" /
     *  "confetti" rendering visible on Tripo AI-generated GLBs.
     *
     *  Fix: expand to triangle-soup (3 unique verts per triangle, no sharing),
     *  compute per-triangle tangents with Gram-Schmidt orthogonalization and
     *  proper handedness encoding, re-index identity. Mesh grows ~3× in
     *  vertex count but tangents are correct at every UV seam.
     *
     *  Matches the MikkTSpace output semantics for the common case (t.w ∈ {±1}
     *  as handedness), which is what every PBR shader expects. */
    private data class UnweldResult(
        val positions: FloatBuffer, val normals: FloatBuffer,
        val texCoords: FloatBuffer, val tangents: FloatBuffer,
        val indices: IntBuffer, val vertCount: Int, val indexCount: Int
    )

    private fun unweldWithTangents(
        srcPositions: FloatBuffer, srcNormals: FloatBuffer,
        srcTexCoords: FloatBuffer, srcIndices: IntBuffer,
        srcVertCount: Int, srcIdxCount: Int
    ): UnweldResult {
        val triCount = srcIdxCount / 3
        val outVerts = triCount * 3
        val newPos = FloatArray(outVerts * 3)
        val newNorm = FloatArray(outVerts * 3)
        val newUv = FloatArray(outVerts * 2)
        val newTan = FloatArray(outVerts * 4)
        val newIdx = IntArray(outVerts)

        val pos = FloatArray(srcVertCount * 3); srcPositions.position(0); srcPositions.get(pos); srcPositions.position(0)
        val norm = FloatArray(srcVertCount * 3); srcNormals.position(0); srcNormals.get(norm); srcNormals.position(0)
        val uv = FloatArray(srcVertCount * 2); srcTexCoords.position(0); srcTexCoords.get(uv); srcTexCoords.position(0)
        srcIndices.position(0)

        for (t in 0 until triCount) {
            srcIndices.position(t * 3)
            val i0 = srcIndices.get()
            val i1 = srcIndices.get()
            val i2 = srcIndices.get()

            val p0x = pos[i0*3]; val p0y = pos[i0*3+1]; val p0z = pos[i0*3+2]
            val p1x = pos[i1*3]; val p1y = pos[i1*3+1]; val p1z = pos[i1*3+2]
            val p2x = pos[i2*3]; val p2y = pos[i2*3+1]; val p2z = pos[i2*3+2]
            val u0 = uv[i0*2]; val v0 = uv[i0*2+1]
            val u1 = uv[i1*2]; val v1 = uv[i1*2+1]
            val u2 = uv[i2*2]; val v2 = uv[i2*2+1]

            val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
            val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
            val du1 = u1 - u0; val dv1 = v1 - v0
            val du2 = u2 - u0; val dv2 = v2 - v0
            val det = du1 * dv2 - du2 * dv1

            var tx: Float; var ty: Float; var tz: Float
            var bx: Float; var by: Float; var bz: Float
            if (kotlin.math.abs(det) < 1e-6f) {
                // Degenerate UV triangle — pick arbitrary perpendicular to normal0
                val n0x = norm[i0*3]; val n0y = norm[i0*3+1]; val n0z = norm[i0*3+2]
                if (kotlin.math.abs(n0y) < 0.99f) { tx = n0z; ty = 0f; tz = -n0x }
                else { tx = 1f; ty = 0f; tz = 0f }
                bx = 0f; by = 0f; bz = 0f
            } else {
                val inv = 1f / det
                tx = (dv2 * e1x - dv1 * e2x) * inv
                ty = (dv2 * e1y - dv1 * e2y) * inv
                tz = (dv2 * e1z - dv1 * e2z) * inv
                bx = (-du2 * e1x + du1 * e2x) * inv
                by = (-du2 * e1y + du1 * e2y) * inv
                bz = (-du2 * e1z + du1 * e2z) * inv
            }

            // Emit 3 unwelded verts, each with tangent Gram-Schmidt'd to its own normal
            val src0 = i0 * 3; val src1 = i1 * 3; val src2 = i2 * 3
            val srcU0 = i0 * 2; val srcU1 = i1 * 2; val srcU2 = i2 * 2
            for (k in 0..2) {
                val src3 = when (k) { 0 -> src0; 1 -> src1; else -> src2 }
                val srcU = when (k) { 0 -> srcU0; 1 -> srcU1; else -> srcU2 }
                val dst = t * 3 + k
                val d3 = dst * 3
                val d2 = dst * 2
                val d4 = dst * 4
                newPos[d3] = pos[src3]; newPos[d3+1] = pos[src3+1]; newPos[d3+2] = pos[src3+2]
                val nx = norm[src3]; val ny = norm[src3+1]; val nz = norm[src3+2]
                newNorm[d3] = nx; newNorm[d3+1] = ny; newNorm[d3+2] = nz
                newUv[d2] = uv[srcU]; newUv[d2+1] = uv[srcU+1]

                // Gram-Schmidt: T' = normalize(T - N (N·T))
                val d = tx * nx + ty * ny + tz * nz
                var ox = tx - d * nx
                var oy = ty - d * ny
                var oz = tz - d * nz
                val len = kotlin.math.sqrt(ox * ox + oy * oy + oz * oz)
                if (len > 1e-5f) { ox /= len; oy /= len; oz /= len }
                else { ox = 1f; oy = 0f; oz = 0f }
                // Handedness w = sign(dot(cross(N, T'), B))
                val cx = ny * oz - nz * oy
                val cy = nz * ox - nx * oz
                val cz = nx * oy - ny * ox
                val w = if (cx * bx + cy * by + cz * bz < 0f) -1f else 1f
                newTan[d4] = ox; newTan[d4+1] = oy; newTan[d4+2] = oz; newTan[d4+3] = w
                newIdx[dst] = dst
            }
        }
        srcIndices.position(0)
        return UnweldResult(
            positions = FloatBuffer.wrap(newPos),
            normals = FloatBuffer.wrap(newNorm),
            texCoords = FloatBuffer.wrap(newUv),
            tangents = FloatBuffer.wrap(newTan),
            indices = IntBuffer.wrap(newIdx),
            vertCount = outVerts,
            indexCount = outVerts
        )
    }

    private fun computeTangents(positions: FloatBuffer, normals: FloatBuffer,
                                texCoords: FloatBuffer, indices: IntBuffer,
                                vertCount: Int, idxCount: Int): FloatBuffer {
        val tan = FloatArray(vertCount * 4)
        val bitan = FloatArray(vertCount * 3)
        val pos = FloatArray(vertCount * 3); positions.position(0); positions.get(pos); positions.position(0)
        val uv = FloatArray(vertCount * 2); texCoords.position(0); texCoords.get(uv); texCoords.position(0)
        val norm = FloatArray(vertCount * 3); normals.position(0); normals.get(norm); normals.position(0)
        indices.position(0)
        for (i in 0 until idxCount step 3) {
            indices.position(i)
            val i0 = indices.get(); val i1 = indices.get(); val i2 = indices.get()
            val e1x = pos[i1*3]-pos[i0*3]; val e1y = pos[i1*3+1]-pos[i0*3+1]; val e1z = pos[i1*3+2]-pos[i0*3+2]
            val e2x = pos[i2*3]-pos[i0*3]; val e2y = pos[i2*3+1]-pos[i0*3+1]; val e2z = pos[i2*3+2]-pos[i0*3+2]
            val du1 = uv[i1*2]-uv[i0*2]; val dv1 = uv[i1*2+1]-uv[i0*2+1]
            val du2 = uv[i2*2]-uv[i0*2]; val dv2 = uv[i2*2+1]-uv[i0*2+1]
            val det = du1*dv2 - du2*dv1
            if (kotlin.math.abs(det) < 0.00001f) continue
            val inv = 1f / det
            val tx = (dv2*e1x - dv1*e2x)*inv; val ty = (dv2*e1y - dv1*e2y)*inv; val tz = (dv2*e1z - dv1*e2z)*inv
            val bx = (-du2*e1x + du1*e2x)*inv; val by = (-du2*e1y + du1*e2y)*inv; val bz = (-du2*e1z + du1*e2z)*inv
            for (idx in intArrayOf(i0, i1, i2)) {
                tan[idx*4] += tx; tan[idx*4+1] += ty; tan[idx*4+2] += tz
                bitan[idx*3] += bx; bitan[idx*3+1] += by; bitan[idx*3+2] += bz
            }
        }
        for (v in 0 until vertCount) {
            val nx = norm[v*3]; val ny = norm[v*3+1]; val nz = norm[v*3+2]
            var tx = tan[v*4]; var ty = tan[v*4+1]; var tz = tan[v*4+2]
            // Gram-Schmidt orthogonalize: T = normalize(T - N * dot(N, T))
            val d = tx*nx + ty*ny + tz*nz
            tx -= d*nx; ty -= d*ny; tz -= d*nz
            val len = kotlin.math.sqrt(tx*tx + ty*ty + tz*tz)
            if (len > 0.0001f) { tx /= len; ty /= len; tz /= len }
            // Handedness: sign of dot(cross(N, T), B)
            val cx = ny*tz - nz*ty; val cy = nz*tx - nx*tz; val cz = nx*ty - ny*tx
            val w = if (cx*bitan[v*3] + cy*bitan[v*3+1] + cz*bitan[v*3+2] < 0f) -1f else 1f
            tan[v*4] = tx; tan[v*4+1] = ty; tan[v*4+2] = tz; tan[v*4+3] = w
        }
        indices.position(0)
        return FloatBuffer.wrap(tan)
    }

    private fun createVBO(data: FloatBuffer, location: Int, size: Int): Int {
        val buf = intArrayOf(0); GLES30.glGenBuffers(1, buf, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0]); data.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining()*4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 0, 0)
        return buf[0]
    }
    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs); GLES30.glLinkProgram(prog)
        val status = intArrayOf(0); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) { Log.e(TAG, "Link: ${GLES30.glGetProgramInfoLog(prog)}"); GLES30.glDeleteProgram(prog); return 0 }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs); return prog
    }
    private fun compileShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val status = intArrayOf(0); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { Log.e(TAG, "Shader: ${GLES30.glGetShaderInfoLog(s)}"); GLES30.glDeleteShader(s); return 0 }
        return s
    }

    // ── Math (zero-allocation: all scratch buffers reused on GL thread) ──

    // Scratch matrices/vectors — safe because all rendering is single-threaded on GL thread
    private val scratchMat4A = FloatArray(16)
    private val scratchMat4B = FloatArray(16)
    private val scratchMat4C = FloatArray(16)
    private val scratchNormal = FloatArray(9)
    private val scratchQuat = FloatArray(16)
    private val scratchVec3 = FloatArray(3)
    // Reusable model matrices for render passes (avoid per-frame alloc)
    private val scratchGridModel = FloatArray(16)
    private val scratchDotModel = FloatArray(16)
    private val scratchPanelModel = FloatArray(16)
    private val scratchEmitterModel = FloatArray(16)
    private val scratchPlaneModel = FloatArray(16)
    private val scratchRayW = FloatArray(3)
    private val shadowLookAt = FloatArray(16)
    private val shadowOrtho = FloatArray(16)
    private val shadowResult = FloatArray(16)

    private fun multiplyMat4(a: FloatArray, b: FloatArray, r: FloatArray): FloatArray {
        for (col in 0..3) for (row in 0..3)
            r[col*4+row] = a[row]*b[col*4] + a[4+row]*b[col*4+1] + a[8+row]*b[col*4+2] + a[12+row]*b[col*4+3]
        return r
    }
    /** Convenience: allocates result (for non-hot paths only) */
    private fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray =
        multiplyMat4(a, b, FloatArray(16))

    private fun extractNormalMatrix(mv: FloatArray, r: FloatArray): FloatArray {
        r[0]=mv[0];r[1]=mv[1];r[2]=mv[2]; r[3]=mv[4];r[4]=mv[5];r[5]=mv[6]; r[6]=mv[8];r[7]=mv[9];r[8]=mv[10]
        return r
    }

    private fun quatToMatrix(q: FloatArray, pos: FloatArray, r: FloatArray): FloatArray {
        val x=q[0];val y=q[1];val z=q[2];val w=q[3]
        val x2=x+x;val y2=y+y;val z2=z+z
        val xx=x*x2;val xy=x*y2;val xz=x*z2;val yy=y*y2;val yz=y*z2;val zz=z*z2
        val wx=w*x2;val wy=w*y2;val wz=w*z2
        r[0]=1f-(yy+zz); r[1]=xy+wz; r[2]=xz-wy; r[3]=0f
        r[4]=xy-wz; r[5]=1f-(xx+zz); r[6]=yz+wx; r[7]=0f
        r[8]=xz+wy; r[9]=yz-wx; r[10]=1f-(xx+yy); r[11]=0f
        r[12]=pos[0]; r[13]=pos[1]; r[14]=pos[2]; r[15]=1f
        return r
    }

    fun quatForward(q: FloatArray, r: FloatArray): FloatArray {
        val x=q[0];val y=q[1];val z=q[2];val w=q[3]
        r[0]=-(2f*(x*z+w*y)); r[1]=-(2f*(y*z-w*x)); r[2]=-(1f-2f*(x*x+y*y))
        return r
    }
    /** Convenience overload for non-hot paths (allocates) */
    fun quatForward(q: FloatArray): FloatArray = quatForward(q, FloatArray(3))

    fun rotateVecByQuat(v: FloatArray, q: FloatArray, r: FloatArray): FloatArray {
        val qx=q[0];val qy=q[1];val qz=q[2];val qw=q[3]
        val ix = qw*v[0] + qy*v[2] - qz*v[1]
        val iy = qw*v[1] + qz*v[0] - qx*v[2]
        val iz = qw*v[2] + qx*v[1] - qy*v[0]
        val iw = -qx*v[0] - qy*v[1] - qz*v[2]
        r[0]=ix*qw + iw*(-qx) + iy*(-qz) - iz*(-qy)
        r[1]=iy*qw + iw*(-qy) + iz*(-qx) - ix*(-qz)
        r[2]=iz*qw + iw*(-qz) + ix*(-qy) - iy*(-qx)
        return r
    }
    /** Convenience overload for non-hot paths (allocates) */
    fun rotateVecByQuat(v: FloatArray, q: FloatArray): FloatArray = rotateVecByQuat(v, q, FloatArray(3))

    /** Ray-line segment closest approach. Returns (distance, rayT) or null if segment param out of range */
    private fun rayLineClosest(rayO: FloatArray, rayD: FloatArray,
                               lineO: FloatArray, lineD: FloatArray, lineLen: Float): Pair<Float, Float>? {
        scratchRayW[0] = rayO[0]-lineO[0]; scratchRayW[1] = rayO[1]-lineO[1]; scratchRayW[2] = rayO[2]-lineO[2]
        val a = dot3(rayD, rayD); val b = dot3(rayD, lineD); val c = dot3(lineD, lineD)
        val d = dot3(rayD, scratchRayW); val e = dot3(lineD, scratchRayW)
        val denom = a*c - b*b
        if (kotlin.math.abs(denom) < 0.00001f) return null
        val sc = (b*e - c*d) / denom
        val tc = (a*e - b*d) / denom
        if (sc < 0f || tc < 0f || tc > lineLen) return null
        val dx = scratchRayW[0]+sc*rayD[0]-tc*lineD[0]
        val dy = scratchRayW[1]+sc*rayD[1]-tc*lineD[1]
        val dz = scratchRayW[2]+sc*rayD[2]-tc*lineD[2]
        return Pair(kotlin.math.sqrt(dx*dx+dy*dy+dz*dz), sc)
    }

    /** Build plane model matrix from quat + extents into scratchPlaneModel (zero-alloc) */
    private fun buildPlaneModel(p: ShadowPlane) {
        val sx = p.extentX; val sz = p.extentY
        val x2 = p.rotX*2; val y2 = p.rotY*2; val z2 = p.rotZ*2
        val xx = p.rotX*x2; val xy = p.rotX*y2; val xz = p.rotX*z2
        val yy = p.rotY*y2; val yz = p.rotY*z2; val zz = p.rotZ*z2
        val wx = p.rotW*x2; val wy = p.rotW*y2; val wz = p.rotW*z2
        scratchPlaneModel[0] = (1f-(yy+zz))*sx; scratchPlaneModel[1] = (xy+wz)*sx; scratchPlaneModel[2] = (xz-wy)*sx; scratchPlaneModel[3] = 0f
        scratchPlaneModel[4] = (xy-wz); scratchPlaneModel[5] = (1f-(xx+zz)); scratchPlaneModel[6] = (yz+wx); scratchPlaneModel[7] = 0f
        scratchPlaneModel[8] = (xz+wy)*sz; scratchPlaneModel[9] = (yz-wx)*sz; scratchPlaneModel[10] = (1f-(xx+yy))*sz; scratchPlaneModel[11] = 0f
        scratchPlaneModel[12] = p.posX; scratchPlaneModel[13] = p.posY; scratchPlaneModel[14] = p.posZ; scratchPlaneModel[15] = 1f
    }

    private fun dot3(a: FloatArray, b: FloatArray) = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

    private fun computeLightSpaceMatrix(centerX: Float = 0f, centerZ: Float = 0f): FloatArray {
        // lightDir points FROM surface TOWARD the light, so camera goes along +lightDir.
        // Shadow frustum CENTERS on the models (centerX, centerZ from renderShadowMap).
        // Before this, the light camera was hardcoded at world origin with ±shadowSpread
        // ortho → models placed in rooms far from origin fell outside the frustum and
        // cast no shadow. Now the frustum travels with the scene.
        val ld = lightDir
        val len = kotlin.math.sqrt(ld[0]*ld[0]+ld[1]*ld[1]+ld[2]*ld[2])
        val nx = ld[0]/len; val ny = ld[1]/len; val nz = ld[2]/len
        lookAt(centerX + nx*10f, ny*10f, centerZ + nz*10f, centerX, 0f, centerZ, shadowLookAt)
        val s = shadowSpread
        ortho(-s, s, -s, s, 0.1f, 30f, shadowOrtho)
        return multiplyMat4(shadowOrtho, shadowLookAt, shadowResult)
    }

    private fun lookAt(eyeX: Float, eyeY: Float, eyeZ: Float,
                       cx: Float, cy: Float, cz: Float, out: FloatArray) {
        var fx = cx-eyeX; var fy = cy-eyeY; var fz = cz-eyeZ
        val fl = kotlin.math.sqrt(fx*fx+fy*fy+fz*fz); fx/=fl; fy/=fl; fz/=fl
        // up = (0,1,0) unless forward is parallel
        var ux = 0f; var uy = 1f; var uz = 0f
        if (kotlin.math.abs(fy) > 0.99f) { ux = 1f; uy = 0f }
        // s = f x up
        var sx = fy*uz-fz*uy; var sy = fz*ux-fx*uz; var sz = fx*uy-fy*ux
        val sl = kotlin.math.sqrt(sx*sx+sy*sy+sz*sz); sx/=sl; sy/=sl; sz/=sl
        // u = s x f
        val uux = sy*fz-sz*fy; val uuy = sz*fx-sx*fz; val uuz = sx*fy-sy*fx
        out[0]=sx;  out[1]=uux;  out[2]=-fx;  out[3]=0f
        out[4]=sy;  out[5]=uuy;  out[6]=-fy;  out[7]=0f
        out[8]=sz;  out[9]=uuz;  out[10]=-fz; out[11]=0f
        out[12]=-(sx*eyeX+sy*eyeY+sz*eyeZ); out[13]=-(uux*eyeX+uuy*eyeY+uuz*eyeZ); out[14]=fx*eyeX+fy*eyeY+fz*eyeZ; out[15]=1f
    }

    private fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float, out: FloatArray) {
        val rl=r-l; val tb=t-b; val fn=f-n
        out[0]=2f/rl; out[1]=0f; out[2]=0f; out[3]=0f
        out[4]=0f; out[5]=2f/tb; out[6]=0f; out[7]=0f
        out[8]=0f; out[9]=0f; out[10]=-2f/fn; out[11]=0f
        out[12]=-(r+l)/rl; out[13]=-(t+b)/tb; out[14]=-(f+n)/fn; out[15]=1f
    }
}

// ── Shaders ──

private const val VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aTexCoord;
layout(location=3) in vec4 aTangent;
layout(location=4) in uvec4 aJoints;
layout(location=5) in vec4 aWeights;
layout(std140) uniform Joints {
    mat4 uJoint[64];
};
uniform mat4 uMVP, uModelView, uModel;
uniform mat3 uNormalMatrix;
uniform float uHasNormalMap;
uniform float uSkinned;
// Tier 3 Feature 4 — glute deformation. Two influence spheres in model-local
// space; vertices inside a radius displace outward along their own normal,
// smoothstep-falloff from inner 30% to edge. Radius = 0 disables the pair
// entirely (the CPU zeroes the radius when the feature is off). For skinned
// meshes the CPU updates uGluteA/BPos to POSED space each frame so falloff
// tracks the moving cheek; for unskinned meshes the anchors stay in model-
// local bind space (same as before Tier 4).
uniform vec3 uGluteAPos;
uniform vec3 uGluteBPos;
uniform float uGluteARadius;
uniform float uGluteBRadius;
uniform float uGluteAPush;
uniform float uGluteBPush;
out vec3 vNormal, vPosition;
out vec2 vTexCoord;
out float vWorldY;
out mat3 vTBN;
void main() {
    vec3 pos = aPosition;
    vec3 nrm = aNormal;
    vec3 tan = aTangent.xyz;

    // ── Tier 4: Skeletal skinning (LBS) ──
    // Gated by uSkinned so unskinned GLBs route through the identity path
    // with zero extra cost. Joint palette = globalPose * inverseBind; the
    // CPU walks the hierarchy once per frame then uploads to the UBO.
    if (uSkinned > 0.5) {
        mat4 S = uJoint[aJoints.x] * aWeights.x
               + uJoint[aJoints.y] * aWeights.y
               + uJoint[aJoints.z] * aWeights.z
               + uJoint[aJoints.w] * aWeights.w;
        pos = (S * vec4(pos, 1.0)).xyz;
        mat3 S3 = mat3(S);
        // Normals survive rigid rotation + uniform scale without inverse-transpose.
        // Tripo rigs are rotation-translation only, so 3x3(S) is orthogonal. If
        // future rigs bake non-uniform scale into IBMs, swap to transpose(inverse(S3)).
        nrm = normalize(S3 * nrm);
        // Only skin the tangent when a normal map is actually in use — skinned
        // Tripo rigs currently have no baked TANGENT attribute, so tan reads as
        // zero and normalize(S3 * 0) = NaN. Gating prevents the NaN from leaking
        // into downstream TBN math (and eventually the fragment color) even if
        // the compiler hoists this computation outside the conditional below.
        if (uHasNormalMap > 0.5) {
            tan = normalize(S3 * tan);
        }
    }

    // ── Tier 3: Glute push (AFTER skin so anchors track posed cheeks) ──
    if (uGluteARadius > 0.0001) {
        float dA = distance(pos, uGluteAPos);
        if (dA < uGluteARadius) {
            float fA = 1.0 - smoothstep(uGluteARadius * 0.3, uGluteARadius, dA);
            pos += nrm * (uGluteAPush * fA);
        }
    }
    if (uGluteBRadius > 0.0001) {
        float dB = distance(pos, uGluteBPos);
        if (dB < uGluteBRadius) {
            float fB = 1.0 - smoothstep(uGluteBRadius * 0.3, uGluteBRadius, dB);
            pos += nrm * (uGluteBPush * fB);
        }
    }
    gl_Position = uMVP * vec4(pos, 1.0);
    vec3 N = normalize(uNormalMatrix * nrm);
    vNormal = N;
    vTexCoord = aTexCoord;
    vPosition = (uModelView * vec4(pos, 1.0)).xyz;
    vWorldY = (uModel * vec4(pos, 1.0)).y;
    if (uHasNormalMap > 0.5) {
        vec3 T = normalize(uNormalMatrix * tan);
        T = normalize(T - dot(T, N) * N);
        vec3 B = cross(N, T) * aTangent.w;
        vTBN = mat3(T, B, N);
    }
}
"""

private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal, vPosition;
in vec2 vTexCoord;
in float vWorldY;
in mat3 vTBN;
uniform sampler2D uBaseColor;
uniform sampler2D uNormalMap;
uniform sampler2D uMetRoughMap;
uniform sampler2D uEmissiveMap;
uniform float uMetallic, uRoughness, uExposure, uLightIntensity, uFillLightIntensity, uAmbientIntensity;
uniform float uSelected, uClipY, uUseSH, uContrast, uSaturation;
uniform float uHasNormalMap, uHasMetRoughMap, uHasEmissiveMap;
uniform vec3 uLightDir, uLightColor, uFillLightDir, uFillLightColor, uAmbientColor;
uniform vec3 uSH[9];
uniform vec3 uEmissiveFactor;
out vec4 fragColor;

vec3 evalSH(vec3 n) {
    float c1 = 0.429043, c2 = 0.511664, c4 = 0.886227, c5 = 0.247708;
    return max(vec3(0.0),
        c4 * uSH[0] +
        2.0 * c2 * (uSH[1] * n.y + uSH[2] * n.z + uSH[3] * n.x) +
        2.0 * c1 * (uSH[4] * n.x * n.y + uSH[5] * n.y * n.z + uSH[7] * n.x * n.z) +
        c5 * uSH[6] * (3.0 * n.z * n.z - 1.0) +
        c1 * uSH[8] * (n.x * n.x - n.y * n.y));
}

void main() {
    if (vWorldY < uClipY) discard;

    // Normal: from normal map or vertex normal
    vec3 N;
    if (uHasNormalMap > 0.5) {
        vec3 nm = texture(uNormalMap, vTexCoord).rgb * 2.0 - 1.0;
        N = normalize(vTBN * nm);
    } else {
        N = normalize(vNormal);
    }

    vec3 V = normalize(-vPosition);
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(L + V);
    // Base color uploaded as SRGB8_ALPHA8 — hardware decodes to linear at sample
    // time (and filters mips in linear light, unlike the old pow(2.2) approximation).
    vec3 albedo = texture(uBaseColor, vTexCoord).rgb;

    // Metallic/roughness: from map or scalar uniforms
    float metallic = uMetallic;
    float roughness = uRoughness;
    if (uHasMetRoughMap > 0.5) {
        vec4 mr = texture(uMetRoughMap, vTexCoord);
        roughness *= mr.g;  // glTF: G = roughness
        metallic *= mr.b;   // glTF: B = metallic
    }

    float NdotL = max(dot(N, L), 0.0);
    float NdotH = max(dot(N, H), 0.0);
    float NdotV = max(dot(N, V), 0.001);
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float denom = NdotH * NdotH * (alpha2 - 1.0) + 1.0;
    float D = alpha2 / (3.14159265 * denom * denom + 0.0001);
    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - max(dot(H, V), 0.0), 5.0);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float G = (NdotV / (NdotV * (1.0 - k) + k)) * (NdotL / (NdotL * (1.0 - k) + k));
    vec3 spec = D * F * G / (4.0 * NdotV * NdotL + 0.0001);
    vec3 diffuse = albedo * (1.0 - metallic) * (vec3(1.0) - F) / 3.14159265;
    vec3 color = (diffuse + spec) * NdotL * uLightColor * uLightIntensity;

    // Fill light
    vec3 fillL = normalize(uFillLightDir);
    vec3 fillH = normalize(fillL + V);
    float fNdotL = max(dot(N, fillL), 0.0);
    float fNdotH = max(dot(N, fillH), 0.0);
    float fD = alpha2 / (3.14159265 * pow(fNdotH * fNdotH * (alpha2 - 1.0) + 1.0, 2.0) + 0.0001);
    // Fresnel + geometry terms for the FILL light's own angles — the key
    // light's F and G are wrong here (G even contains the key NdotL).
    vec3 fF = F0 + (1.0 - F0) * pow(1.0 - max(dot(fillH, V), 0.0), 5.0);
    float fG = (NdotV / (NdotV * (1.0 - k) + k)) * (fNdotL / (fNdotL * (1.0 - k) + k));
    vec3 fSpec = fD * fF * fG / (4.0 * NdotV * fNdotL + 0.0001);
    color += (diffuse + fSpec) * fNdotL * uFillLightColor * uFillLightIntensity;

    // Ambient: SH irradiance or hemisphere fallback. uAmbientColor carries the
    // XR colorCorrection (or the user tint) — apply it on BOTH branches so the
    // estimator's white balance isn't silently dropped whenever SH is valid.
    if (uUseSH > 0.5) {
        vec3 shIrrad = evalSH(N);
        color += albedo * shIrrad * uAmbientColor * uAmbientIntensity;
        // IBL specular: evaluate SH at reflection direction, weight by smoothness
        vec3 R = reflect(-V, N);
        vec3 specEnv = evalSH(R);
        vec3 F_env = F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - NdotV, 5.0);
        float smoothness = 1.0 - roughness;
        color += specEnv * F_env * smoothness * smoothness * uAmbientColor * uAmbientIntensity;
    } else {
        color += albedo * mix(vec3(0.02, 0.02, 0.04), vec3(0.08, 0.08, 0.06), N.y * 0.5 + 0.5) * uAmbientColor * uAmbientIntensity;
    }

    // Emissive
    if (uHasEmissiveMap > 0.5) {
        vec3 emissive = texture(uEmissiveMap, vTexCoord).rgb * uEmissiveFactor;
        color += emissive;
    }

    // Selection rim — subtle edge highlight only, no full glow
    if (uSelected > 0.5) { float rim = pow(1.0 - NdotV, 5.0); color += vec3(0.0, 0.6, 0.8) * rim * 0.25; }

    color *= exp2(uExposure);
    // Khronos PBR Neutral tone mapping — identity below peak 0.76, so authored
    // skin tones reach the display unshifted; ACES desaturated and pushed
    // skin reds toward orange. Highlights compress with gentle desaturation.
    {
        float tmOff = min(color.r, min(color.g, color.b));
        tmOff = tmOff < 0.08 ? tmOff - 6.25 * tmOff * tmOff : 0.04;
        color -= tmOff;
        float peak = max(color.r, max(color.g, color.b));
        if (peak > 0.76) {
            float newPeak = 1.0 - 0.0576 / (peak + 0.24 - 0.76);
            color *= newPeak / peak;
            float tmG = 1.0 - 1.0 / (0.15 * (peak - newPeak) + 1.0);
            color = mix(color, vec3(newPeak), tmG);
        }
    }
    color = clamp(color, 0.0, 1.0);
    // Gamma contrast: >1.0 darkens darks while barely touching highlights
    color = pow(color, vec3(uContrast));
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(vec3(luma), color, uSaturation), 0.0, 1.0);
    // Output linear — the OpenXR swapchain is GL_SRGB8_ALPHA8 so the driver
    // does the linear→sRGB encode on write. Doing pow(color, 1/2.2) here
    // too would double-gamma and produce a washed-out / lifted-midtone look.
    fragColor = vec4(color, 1.0);
}
"""

private const val SHADOW_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=4) in uvec4 aJoints;
layout(location=5) in vec4 aWeights;
layout(std140) uniform Joints {
    mat4 uJoint[64];
};
uniform mat4 uMVP;
uniform float uSkinned;
void main() {
    vec3 p = aPosition;
    if (uSkinned > 0.5) {
        mat4 S = uJoint[aJoints.x] * aWeights.x
               + uJoint[aJoints.y] * aWeights.y
               + uJoint[aJoints.z] * aWeights.z
               + uJoint[aJoints.w] * aWeights.w;
        p = (S * vec4(p, 1.0)).xyz;
    }
    gl_Position = uMVP * vec4(p, 1.0);
}
"""

private const val SHADOW_FRAGMENT_SHADER = """#version 300 es
precision highp float;
out vec4 fragColor;
void main() { fragColor = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0); }
"""

private const val LASER_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in float aAlpha;
uniform mat4 uMVP;
uniform float uLenScale;
out float vAlpha;
void main() {
    vec3 laserPos = vec3(aPosition.xy, aPosition.z * uLenScale);
    gl_Position = uMVP * vec4(laserPos, 1.0);
    vAlpha = aAlpha;
}
"""

private const val LASER_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in float vAlpha;
uniform vec3 uColor;
out vec4 fragColor;
void main() { fragColor = vec4(uColor, vAlpha * 0.8); }
"""

private const val GRID_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
uniform mat4 uMVP;
uniform float uGridY;
out vec3 vWorldPos;
void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    vWorldPos = vec3(aPosition.x, aPosition.y + uGridY, aPosition.z);
}
"""

private const val GRID_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vWorldPos;
uniform float uGridScale, uAlpha, uShadowDarkness, uShadowSoftness, uLightSize;
uniform mat4 uShadowMVP;
uniform sampler2D uShadowMap;
out vec4 fragColor;

float getShadow() {
    vec4 lp = uShadowMVP * vec4(vWorldPos, 1.0);
    vec3 pc = lp.xyz / lp.w * 0.5 + 0.5;
    if (pc.x < 0.0 || pc.x > 1.0 || pc.y < 0.0 || pc.y > 1.0 || pc.z > 1.0) return 0.0;
    vec2 ts = 1.0 / vec2(textureSize(uShadowMap, 0));
    float bias = 0.001;

    // PCSS: blocker search in small neighborhood
    float searchR = uShadowSoftness * 2.0;
    float blockerSum = 0.0, blockerCount = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * searchR).r;
            if (d < pc.z - bias) { blockerSum += d; blockerCount += 1.0; }
        }
    }
    if (blockerCount < 0.5) return 0.0;

    // Penumbra: wider PCF when far from blocker, tighter when close
    float avgBlocker = blockerSum / blockerCount;
    float penumbraScale = clamp((pc.z - avgBlocker) * uLightSize * 10.0, 1.0, uShadowSoftness * 3.0);

    // Variable-radius PCF (5x5)
    float shadow = 0.0;
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * penumbraScale).r;
            shadow += pc.z - bias > d ? 1.0 : 0.0;
        }
    }
    return shadow / 25.0;
}

void main() {
    vec2 coord = vWorldPos.xz / uGridScale;
    vec2 grid = abs(fract(coord - 0.5) - 0.5) / fwidth(coord);
    float gridVal = 1.0 - min(min(grid.x, grid.y), 1.0);
    float dist = length(vWorldPos.xz);
    float fade = 1.0 - smoothstep(2.0, 5.0, dist);

    float gridA = gridVal * fade * uAlpha;
    // Shadow fade decoupled from grid-line fade: shadows render anywhere on the
    // enlarged grid plane so models placed far from origin still cast onto the
    // floor. Only a gentle fade at the grid's edge (17-20m) to avoid hard cutoff.
    float shadowFade = 1.0 - smoothstep(17.0, 20.0, dist);
    float shadow = uShadowDarkness > 0.0 ? getShadow() : 0.0;
    float shadowA = shadow * uShadowDarkness * shadowFade;

    float totalA = max(gridA, shadowA);
    if (totalA < 0.01) discard;

    vec3 color = gridA > shadowA ? vec3(0.5) : vec3(0.0);
    if (gridA > 0.01 && shadowA > 0.01) {
        color = vec3(0.5) * (1.0 - shadowA * 0.5);
    }
    fragColor = vec4(color, totalA);
}
"""

private const val GIZMO_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aColor;
uniform mat4 uMVP;
out vec3 vColor;
void main() { gl_Position = uMVP * vec4(aPosition, 1.0); vColor = aColor; }
"""

private const val GIZMO_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vColor;
uniform float uHighlight;
out vec4 fragColor;
void main() {
    vec3 c = mix(vColor, vec3(1.0, 1.0, 0.5), uHighlight * 0.6);
    fragColor = vec4(c, 0.9);
}
"""

private const val UI_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
uniform mat4 uMVP;
out vec2 vUV;
void main() { gl_Position = uMVP * vec4(aPos, 0.0, 1.0); vUV = aUV; }
"""

private const val UI_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() { fragColor = texture(uTex, vUV); }
"""

private const val SP_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec2 aUV;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vWorldPos;
out vec2 vUV;
void main() {
    vec4 wp = uModel * vec4(aPosition, 1.0);
    vWorldPos = wp.xyz;
    vUV = aUV;
    gl_Position = uMVP * vec4(aPosition, 1.0);
}
"""

private const val SP_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vWorldPos;
in vec2 vUV;
uniform mat4 uShadowMVP;
uniform sampler2D uShadowMap;
uniform float uShadowDarkness, uShadowSoftness, uLightSize;
out vec4 fragColor;

void main() {
    vec4 lp = uShadowMVP * vec4(vWorldPos, 1.0);
    vec3 pc = lp.xyz / lp.w * 0.5 + 0.5;
    if (pc.x < 0.0 || pc.x > 1.0 || pc.y < 0.0 || pc.y > 1.0 || pc.z > 1.0) discard;

    vec2 ts = 1.0 / vec2(textureSize(uShadowMap, 0));
    float bias = 0.001;

    // PCSS blocker search
    float searchR = uShadowSoftness * 2.0;
    float blockerSum = 0.0, blockerCount = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * searchR).r;
            if (d < pc.z - bias) { blockerSum += d; blockerCount += 1.0; }
        }
    }
    if (blockerCount < 0.5) discard;

    float avgBlocker = blockerSum / blockerCount;
    float penumbraScale = clamp((pc.z - avgBlocker) * uLightSize * 10.0, 1.0, uShadowSoftness * 3.0);

    float shadow = 0.0;
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * penumbraScale).r;
            shadow += pc.z - bias > d ? 1.0 : 0.0;
        }
    }
    shadow /= 25.0;

    float ef = smoothstep(0.0, 0.05, vUV.x) * smoothstep(0.0, 0.05, 1.0 - vUV.x)
             * smoothstep(0.0, 0.05, vUV.y) * smoothstep(0.0, 0.05, 1.0 - vUV.y);

    float a = shadow * ef * uShadowDarkness;
    if (a < 0.005) discard;
    fragColor = vec4(0.0, 0.0, 0.0, a);
}
"""

// Plane visualization — sci-fi spatial mapping aesthetic
private const val PLANE_VIS_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vWorldPos;
in vec2 vUV;
uniform vec4 uPlaneColor;
uniform float uTime;
out vec4 fragColor;
void main() {
    vec3 col = uPlaneColor.rgb;

    // Edge glow: bright neon border that fades inward
    float edgeDist = min(min(vUV.x, 1.0 - vUV.x), min(vUV.y, 1.0 - vUV.y));
    float edgeGlow = smoothstep(0.0, 0.02, edgeDist) * (1.0 - smoothstep(0.02, 0.08, edgeDist));
    float innerFade = smoothstep(0.0, 0.015, edgeDist);

    // World-space grid (10cm cells) — stable regardless of plane size
    vec2 worldGrid = abs(fract(vWorldPos.xz * 10.0) - 0.5);
    float gridLine = 1.0 - smoothstep(0.0, 0.04, min(worldGrid.x, worldGrid.y));

    // Coarse grid (50cm cells) — structural lines, slightly brighter
    vec2 coarseGrid = abs(fract(vWorldPos.xz * 2.0) - 0.5);
    float coarseLine = 1.0 - smoothstep(0.0, 0.03, min(coarseGrid.x, coarseGrid.y));

    // Scanning pulse — sweeps across the plane in world space
    float scanPhase = fract(uTime * 0.3);
    float scanPos = mix(-0.2, 1.2, scanPhase);
    float scanLine = smoothstep(0.0, 0.01, abs(vUV.x - scanPos))
                   * smoothstep(0.0, 0.15, abs(vUV.x - scanPos));
    float scanGlow = 1.0 - scanLine;

    // Combine layers
    float fillAlpha = uPlaneColor.a * 0.06 * innerFade;             // very subtle fill
    float gridAlpha = uPlaneColor.a * gridLine * 0.25 * innerFade;  // fine grid
    float coarseAlpha = uPlaneColor.a * coarseLine * 0.4 * innerFade; // structural grid
    float edgeAlpha = edgeGlow * 0.8;                                // bright neon edge
    float scanAlpha = scanGlow * 0.3 * innerFade;                   // scan sweep

    float totalAlpha = fillAlpha + gridAlpha + coarseAlpha + edgeAlpha + scanAlpha;

    // Edge is pure white-hot, inner grid uses plane color
    vec3 finalColor = col;
    if (edgeAlpha > gridAlpha + coarseAlpha) {
        finalColor = mix(col, vec3(1.0), 0.6); // white-hot edge
    }
    if (scanGlow > 0.1) {
        finalColor = mix(finalColor, vec3(1.0), scanGlow * 0.3);
    }

    fragColor = vec4(finalColor, clamp(totalAlpha, 0.0, 0.85));
}
"""

// Fullscreen color wash — tints everything including passthrough
private const val WASH_VERTEX_SHADER = """#version 300 es
void main() {
    // Fullscreen triangle: 3 vertices cover the entire NDC quad
    vec2 pos;
    if (gl_VertexID == 0) pos = vec2(-1.0, -1.0);
    else if (gl_VertexID == 1) pos = vec2(3.0, -1.0);
    else pos = vec2(-1.0, 3.0);
    gl_Position = vec4(pos, 0.0, 1.0);
}
"""

private const val WASH_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""

// ── Bloom Shaders ──

private const val BLOOM_FULLSCREEN_VS = """#version 300 es
out vec2 vUV;
void main() {
    vec2 pos;
    if (gl_VertexID == 0) { pos = vec2(-1.0, -1.0); vUV = vec2(0.0, 0.0); }
    else if (gl_VertexID == 1) { pos = vec2(3.0, -1.0); vUV = vec2(2.0, 0.0); }
    else { pos = vec2(-1.0, 3.0); vUV = vec2(0.0, 2.0); }
    gl_Position = vec4(pos, 0.0, 1.0);
}
"""

private const val BLOOM_BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uScene;
uniform float uThreshold;
out vec4 fragColor;
void main() {
    vec3 color = texture(uScene, vUV).rgb;
    float brightness = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (brightness > uThreshold) {
        fragColor = vec4(color * (brightness - uThreshold) / brightness, 1.0);
    } else {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    }
}
"""

private const val BLOOM_BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uDirection;
out vec4 fragColor;
void main() {
    float w[5];
    w[0] = 0.227027; w[1] = 0.1945946; w[2] = 0.1216216; w[3] = 0.054054; w[4] = 0.016216;
    vec3 result = texture(uTex, vUV).rgb * w[0];
    for (int i = 1; i < 5; i++) {
        vec2 off = uDirection * float(i);
        result += texture(uTex, vUV + off).rgb * w[i];
        result += texture(uTex, vUV - off).rgb * w[i];
    }
    fragColor = vec4(result, 1.0);
}
"""

private const val BLOOM_COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uBloom;
uniform float uIntensity;
out vec4 fragColor;
void main() {
    vec3 bloom = texture(uBloom, vUV).rgb;
    fragColor = vec4(bloom * uIntensity, 1.0);
}
"""
