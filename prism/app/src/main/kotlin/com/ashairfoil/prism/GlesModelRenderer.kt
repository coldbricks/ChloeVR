package com.ashairfoil.prism

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
        const val GIZMO_AXIS_NONE = -1
        const val GIZMO_AXIS_X = 0
        const val GIZMO_AXIS_Y = 1
        const val GIZMO_AXIS_Z = 2
        const val GIZMO_LENGTH = 0.25f
        const val GIZMO_HIT_RADIUS = 0.04f
        const val LASER_BASE_LENGTH = 5.0f
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
        var hasNormalMap: Boolean = false
    )

    data class ShadowPlane(
        val posX: Float, val posY: Float, val posZ: Float,
        val rotX: Float, val rotY: Float, val rotZ: Float, val rotW: Float,
        val extentX: Float, val extentY: Float,
        val label: Int  // 0=unknown, 1=wall, 2=floor, 3=ceiling, 4=table
    )

    private val models = mutableListOf<GpuModel>()
    private var nextModelId = 1
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
    }

    private var initialized = false
    private var frameCount = 0

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
        val s = 5.0f
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
            if (glbBytes.size < 20) return -1
            val jsonLength = readU32(glbBytes, 12)
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

            val prim = json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
            val attrs = prim.getJSONObject("attributes")

            val (posBv, posCnt, _) = acc(attrs.getInt("POSITION"))
            val (posOff, _) = bv(posBv)
            val positions = extractFloats(glbBytes, posOff, posCnt * 3)

            var texCoords: FloatBuffer? = null
            if (attrs.has("TEXCOORD_0")) {
                val (tcBv, tcCnt, _) = acc(attrs.getInt("TEXCOORD_0"))
                val (tcOff, _) = bv(tcBv)
                texCoords = extractFloats(glbBytes, tcOff, tcCnt * 2)
            }
            var normals: FloatBuffer
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
            var tangents: FloatBuffer? = null
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
            val (idxBv, idxCnt, _) = acc(idxAccIdx)
            val idxCompType = accessors.getJSONObject(idxAccIdx).getInt("componentType")
            val (idxOff, _) = bv(idxBv)
            val indices = extractIndices(glbBytes, idxOff, idxCnt, idxCompType)

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

            fun loadGltfTex(texIdx: Int, label: String): Int {
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
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                Log.i(TAG, "$label: ${img.optString("name", "img[$source]")} (${bitmap.width}x${bitmap.height})")
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
                        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                        val imgName = img.optString("name", "image[$imgIdx]")
                        Log.i(TAG, "Base color texture: $imgName (${bitmap.width}x${bitmap.height}, image[$imgIdx])")
                        bitmap.recycle()
                    }
                }
            }

            // Load PBR texture maps
            model.normalMapTexId = loadGltfTex(normalTexIdx, "Normal map")
            model.metallicRoughnessTexId = loadGltfTex(metRoughTexIdx, "Metallic-roughness map")
            model.emissiveTexId = loadGltfTex(emissiveTexIdx, "Emissive map")
            model.hasNormalMap = model.normalMapTexId != 0

            // Compute tangents if normal map exists but glTF lacks TANGENT attribute
            // Skip on very dense meshes (>500K verts) — computed tangents create
            // checkerboard artifacts due to handedness flipping across shared vertices
            if (model.hasNormalMap && tangents == null && texCoords != null && posCnt <= 500000) {
                tangents = computeTangents(positions, normals, texCoords, indices, posCnt, idxCnt)
                Log.i(TAG, "Computed tangents for normal mapping ($posCnt verts)")
            } else if (model.hasNormalMap && tangents == null && posCnt > 500000) {
                Log.w(TAG, "Skipping tangent compute on dense mesh ($posCnt verts) — using vertex normals only")
                model.hasNormalMap = false
                model.normalMapTexId = 0
            }

            // Upload tangent VBO if available
            if (tangents != null) {
                GLES30.glBindVertexArray(model.vao)
                model.vboTangents = createVBO(tangents, 3, 4)
                GLES30.glBindVertexArray(0)
            }
            models.add(model)
            Log.i(TAG, "GLB loaded #${model.id}: $posCnt verts, $idxCnt idx")
            return model.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GLB", e)
            return -1
        }
    }

    fun getModel(id: Int): GpuModel? = models.find { it.id == id }
    fun removeModel(id: Int) {
        val m = models.find { it.id == id } ?: return
        if (m.vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
        if (m.vboPositions != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboPositions), 0)
        if (m.vboNormals != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboNormals), 0)
        if (m.vboTexCoords != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboTexCoords), 0)
        if (m.vboTangents != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboTangents), 0)
        if (m.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.ebo), 0)
        if (m.textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.textureId), 0)
        if (m.normalMapTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.normalMapTexId), 0)
        if (m.metallicRoughnessTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.metallicRoughnessTexId), 0)
        if (m.emissiveTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.emissiveTexId), 0)
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
        val dx = emitterPos[0] - sceneCenterX
        val dy = emitterPos[1] - sceneCenterY
        val dz = emitterPos[2] - sceneCenterZ
        val len = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.001f)
        lightDir = floatArrayOf(dx/len, dy/len, dz/len)
        lightElevDeg = Math.toDegrees(kotlin.math.asin((dy/len).toDouble())).toFloat()
        lightAngleDeg = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
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
        shadowLightMatrix = computeLightSpaceMatrix()

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

        for (plane in shadowPlanes) {
            // Samsung labels are unreliable — use pure geometry for colors.
            // normalY > 0.7 = horizontal, then classify by height relative to grid.
            val normalY = 1f - 2f * (plane.rotX * plane.rotX + plane.rotZ * plane.rotZ)
            val isHorizontal = normalY > 0.7f
            val heightAboveFloor = plane.posY - gridHeight
            val color = when {
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
                    hitDistance: Float, color: FloatArray = DEFAULT_LASER_COLOR) {
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
            // Nudge dot 2mm toward camera to prevent z-fighting
            val dotDist = hitDistance - 0.002f
            scratchDotModel[0] = 0.01f; scratchDotModel[1] = 0f; scratchDotModel[2] = 0f; scratchDotModel[3] = 0f
            scratchDotModel[4] = 0f; scratchDotModel[5] = 0.01f; scratchDotModel[6] = 0f; scratchDotModel[7] = 0f
            scratchDotModel[8] = 0f; scratchDotModel[9] = 0f; scratchDotModel[10] = 0.01f; scratchDotModel[11] = 0f
            scratchDotModel[12] = handPos[0] + scratchVec3[0] * dotDist
            scratchDotModel[13] = handPos[1] + scratchVec3[1] * dotDist
            scratchDotModel[14] = handPos[2] + scratchVec3[2] * dotDist
            scratchDotModel[15] = 1f
            multiplyMat4(viewMatrix, scratchDotModel, scratchMat4A)
            multiplyMat4(projection, scratchMat4A, scratchMat4B)
            GLES30.glUniformMatrix4fv(uLaserMVP, 1, false, scratchMat4B, 0)
            GLES30.glUniform3f(uLaserColor, 1f, 1f, 1f)
            GLES30.glBindVertexArray(dotVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 4)
            GLES30.glBindVertexArray(0)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
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
            if (m.vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            if (m.vboPositions != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboPositions), 0)
            if (m.vboNormals != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboNormals), 0)
            if (m.vboTexCoords != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboTexCoords), 0)
            if (m.vboTangents != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboTangents), 0)
            if (m.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.ebo), 0)
            if (m.textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.textureId), 0)
            if (m.normalMapTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.normalMapTexId), 0)
            if (m.metallicRoughnessTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.metallicRoughnessTexId), 0)
            if (m.emissiveTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.emissiveTexId), 0)
        }
        models.clear()
        if (programId != 0) GLES30.glDeleteProgram(programId)
        if (shadowDepthProgramId != 0) GLES30.glDeleteProgram(shadowDepthProgramId)
        if (laserProgramId != 0) GLES30.glDeleteProgram(laserProgramId)
        if (gridProgramId != 0) GLES30.glDeleteProgram(gridProgramId)
        if (gizmoProgramId != 0) GLES30.glDeleteProgram(gizmoProgramId)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        if (shadowMapFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(shadowMapFbo), 0)
        if (shadowMapTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(shadowMapTexId), 0)
        if (shadowColorRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(shadowColorRb), 0)
        if (laserVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(laserVao), 0)
        if (laserVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(laserVbo), 0)
        if (dotVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(dotVao), 0)
        if (dotVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(dotVbo), 0)
        if (gridVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(gridVao), 0)
        if (gridVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(gridVbo), 0)
        if (gizmoVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(gizmoVao), 0)
        if (gizmoVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(gizmoVbo), 0)
        if (gizmoEbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(gizmoEbo), 0)
        if (shadowPlaneProgramId != 0) GLES30.glDeleteProgram(shadowPlaneProgramId)
        if (planeVisProgramId != 0) GLES30.glDeleteProgram(planeVisProgramId)
        if (spVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(spVao), 0)
        if (spVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(spVbo), 0)
        if (uiProgramId != 0) GLES30.glDeleteProgram(uiProgramId)
        if (uiVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(uiVao), 0)
        if (uiVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(uiVbo), 0)
        // Bloom cleanup
        if (bloomBrightProgramId != 0) GLES30.glDeleteProgram(bloomBrightProgramId)
        if (bloomBlurProgramId != 0) GLES30.glDeleteProgram(bloomBlurProgramId)
        if (bloomCompositeProgramId != 0) GLES30.glDeleteProgram(bloomCompositeProgramId)
        if (bloomTexA != 0) GLES30.glDeleteTextures(1, intArrayOf(bloomTexA), 0)
        if (bloomTexB != 0) GLES30.glDeleteTextures(1, intArrayOf(bloomTexB), 0)
        if (bloomFboA != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFboA), 0)
        if (bloomFboB != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFboB), 0)
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

    private fun computeLightSpaceMatrix(): FloatArray {
        // lightDir points FROM surface TOWARD the light, so camera goes along +lightDir
        val ld = lightDir
        val len = kotlin.math.sqrt(ld[0]*ld[0]+ld[1]*ld[1]+ld[2]*ld[2])
        val nx = ld[0]/len; val ny = ld[1]/len; val nz = ld[2]/len
        val view = lookAt(nx*10f, ny*10f, nz*10f, 0f, 0f, 0f)
        val s = shadowSpread
        val proj = ortho(-s, s, -s, s, 0.1f, 30f)
        return multiplyMat4(proj, view)
    }

    private fun lookAt(eyeX: Float, eyeY: Float, eyeZ: Float,
                       cx: Float, cy: Float, cz: Float): FloatArray {
        var fx = cx-eyeX; var fy = cy-eyeY; var fz = cz-eyeZ
        val fl = kotlin.math.sqrt(fx*fx+fy*fy+fz*fz); fx/=fl; fy/=fl; fz/=fl
        // up = (0,1,0) unless forward is parallel
        var ux = 0f; var uy = 1f; var uz = 0f
        if (kotlin.math.abs(fy) > 0.99f) { ux = 1f; uy = 0f }
        // s = f × up
        var sx = fy*uz-fz*uy; var sy = fz*ux-fx*uz; var sz = fx*uy-fy*ux
        val sl = kotlin.math.sqrt(sx*sx+sy*sy+sz*sz); sx/=sl; sy/=sl; sz/=sl
        // u = s × f
        val uux = sy*fz-sz*fy; val uuy = sz*fx-sx*fz; val uuz = sx*fy-sy*fx
        return floatArrayOf(
            sx, uux, -fx, 0f,
            sy, uuy, -fy, 0f,
            sz, uuz, -fz, 0f,
            -(sx*eyeX+sy*eyeY+sz*eyeZ), -(uux*eyeX+uuy*eyeY+uuz*eyeZ), fx*eyeX+fy*eyeY+fz*eyeZ, 1f)
    }

    private fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        val rl=r-l; val tb=t-b; val fn=f-n
        return floatArrayOf(2f/rl,0f,0f,0f, 0f,2f/tb,0f,0f, 0f,0f,-2f/fn,0f,
            -(r+l)/rl, -(t+b)/tb, -(f+n)/fn, 1f)
    }
}

// ── Shaders ──

private const val VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aTexCoord;
layout(location=3) in vec4 aTangent;
uniform mat4 uMVP, uModelView, uModel;
uniform mat3 uNormalMatrix;
uniform float uHasNormalMap;
out vec3 vNormal, vPosition;
out vec2 vTexCoord;
out float vWorldY;
out mat3 vTBN;
void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    vec3 N = normalize(uNormalMatrix * aNormal);
    vNormal = N;
    vTexCoord = aTexCoord;
    vPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
    vWorldY = (uModel * vec4(aPosition, 1.0)).y;
    if (uHasNormalMap > 0.5) {
        vec3 T = normalize(uNormalMatrix * aTangent.xyz);
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
    vec3 albedo = pow(texture(uBaseColor, vTexCoord).rgb, vec3(2.2));

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
    vec3 fSpec = fD * F * G / (4.0 * NdotV * fNdotL + 0.0001);
    color += (diffuse + fSpec * 0.5) * fNdotL * uFillLightColor * uFillLightIntensity;

    // Ambient: SH irradiance or hemisphere fallback
    if (uUseSH > 0.5) {
        vec3 shIrrad = evalSH(N);
        color += albedo * shIrrad * uAmbientIntensity;
        // IBL specular: evaluate SH at reflection direction, weight by smoothness
        vec3 R = reflect(-V, N);
        vec3 specEnv = evalSH(R);
        vec3 F_env = F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - NdotV, 5.0);
        float smoothness = 1.0 - roughness;
        color += specEnv * F_env * smoothness * smoothness * uAmbientIntensity;
    } else {
        color += albedo * mix(vec3(0.05, 0.05, 0.08), vec3(0.15, 0.15, 0.12), N.y * 0.5 + 0.5) * uAmbientColor * uAmbientIntensity;
    }

    // Emissive
    if (uHasEmissiveMap > 0.5) {
        vec3 emissive = pow(texture(uEmissiveMap, vTexCoord).rgb, vec3(2.2)) * uEmissiveFactor;
        color += emissive;
    }

    // Selection rim
    if (uSelected > 0.5) { float rim = pow(1.0 - NdotV, 3.0); color += vec3(0.0, 0.8, 1.0) * rim * 0.8; }

    color *= exp2(uExposure);
    // ACES tone mapping
    color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
    color = clamp(color, 0.0, 1.0);
    // Gamma contrast: >1.0 darkens darks while barely touching highlights
    color = pow(color, vec3(uContrast));
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(vec3(luma), color, uSaturation), 0.0, 1.0);
    color = pow(color, vec3(1.0/2.2));
    fragColor = vec4(color, 1.0);
}
"""

private const val SHADOW_VERTEX_SHADER = """#version 300 es
layout(location=0) in vec3 aPosition;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPosition, 1.0); }
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
    float shadowFade = 1.0 - smoothstep(3.0, 8.0, dist);
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
