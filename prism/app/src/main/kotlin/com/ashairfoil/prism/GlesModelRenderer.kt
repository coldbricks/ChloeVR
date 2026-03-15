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
            1f, 0f, 0f, 0f,  0f, 1f, 0f, 0f,  0f, 0f, 1f, 0f,  0f, 0f, -2f, 1f
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
        var saturation: Float = 1f
    )

    data class ShadowPlane(
        val posX: Float, val posY: Float, val posZ: Float,
        val rotX: Float, val rotY: Float, val rotZ: Float, val rotW: Float,
        val extentX: Float, val extentY: Float,
        val label: Int  // 0=unknown, 1=floor, 2=ceiling, 3=wall, 4=table
    )

    private val models = mutableListOf<GpuModel>()
    private var nextModelId = 1

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
    var shadowEnabled = true
    var shadowPlanes: List<ShadowPlane> = emptyList()

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

    // Shadow plane resources
    private var shadowPlaneProgramId = 0
    private var spVao = 0
    private var spVbo = 0

    // UI overlay resources
    private var uiProgramId = 0
    private var uiVao = 0
    private var uiVbo = 0
    private var uiUTex = -1
    private var uiUMvp = -1

    // Model shader uniforms
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
    var gridHeight = -2.0f   // Y offset for ground grid
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
        lightDir = floatArrayOf(cosEl * sinAz, sinEl, cosEl * cosAz)
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
        initShadowMap()

        // Laser
        laserProgramId = createProgram(LASER_VERTEX_SHADER, LASER_FRAGMENT_SHADER)
        if (laserProgramId == 0) { Log.e(TAG, "Failed to create laser shader"); return false }
        initLaserGeometry()

        // Grid (with shadow sampling)
        gridProgramId = createProgram(GRID_VERTEX_SHADER, GRID_FRAGMENT_SHADER)
        if (gridProgramId == 0) { Log.e(TAG, "Failed to create grid shader"); return false }
        initGridGeometry()

        // Shadow planes
        shadowPlaneProgramId = createProgram(SP_VERTEX_SHADER, SP_FRAGMENT_SHADER)
        if (shadowPlaneProgramId == 0) { Log.e(TAG, "Failed to create shadow plane shader"); return false }
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
        initGizmoGeometry()

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
        val len = 5.0f
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

            if (mats != null && mats.length() > 0) {
                val mat0 = mats.getJSONObject(0)
                val pbr = mat0.optJSONObject("pbrMetallicRoughness")
                if (pbr != null) {
                    model.metallic = pbr.optDouble("metallicFactor", 0.0).toFloat()
                    model.roughness = pbr.optDouble("roughnessFactor", 0.9).toFloat()
                    // Follow: baseColorTexture.index → textures[idx].source → image index
                    val bcTex = pbr.optJSONObject("baseColorTexture")
                    if (bcTex != null && textures != null) {
                        val texIdx = bcTex.getInt("index")
                        if (texIdx < textures.length()) {
                            baseColorImageIdx = textures.getJSONObject(texIdx).optInt("source", 0)
                        }
                    }
                }
            }

            // Load the correct image as base color texture
            if (images != null && images.length() > 0) {
                // Use material-referenced image, or fall back to first image
                val imgIdx = if (baseColorImageIdx in 0 until images.length()) baseColorImageIdx else 0
                val img = images.getJSONObject(imgIdx)
                val texBvIdx = img.optInt("bufferView", -1)
                if (texBvIdx >= 0) {
                    val (texOff, texLen) = bv(texBvIdx)
                    val texBytes = ByteArray(texLen)
                    System.arraycopy(glbBytes, texOff, texBytes, 0, texLen)
                    val bitmap = BitmapFactory.decodeByteArray(texBytes, 0, texLen)
                    if (bitmap != null) {
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
        if (m.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.ebo), 0)
        if (m.textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.textureId), 0)
        models.remove(m)
    }
    fun getAllModels(): List<GpuModel> = models.toList()

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
        val uLightMVP = GLES30.glGetUniformLocation(shadowDepthProgramId, "uMVP")

        for (m in models) {
            if (m.indexCount == 0) continue
            val mvp = multiplyMat4(shadowLightMatrix, m.modelMatrix)
            GLES30.glUniformMatrix4fv(uLightMVP, 1, false, mvp, 0)
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

        if (models.isEmpty()) { frameCount++; return }

        GLES30.glUseProgram(programId)
        GLES30.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2])
        GLES30.glUniform3f(uLightColor, lightColor[0], lightColor[1], lightColor[2])
        GLES30.glUniform1f(uLightIntensity, lightIntensity)
        GLES30.glUniform3f(uFillLightDir, fillLightDir[0], fillLightDir[1], fillLightDir[2])
        GLES30.glUniform3f(uFillLightColor, fillLightColor[0], fillLightColor[1], fillLightColor[2])
        GLES30.glUniform1f(uFillLightIntensity, fillLightIntensity)
        GLES30.glUniform3f(uAmbientColor, ambientColor[0], ambientColor[1], ambientColor[2])
        GLES30.glUniform1f(uAmbientIntensity, ambientIntensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uClipY"), gridHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uUseSH"), if (useSH) 1.0f else 0.0f)
        if (useSH) {
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uSH"), 9, shCoefficients, 0)
        }

        for (m in models) {
            if (m.indexCount == 0) continue
            val modelView = multiplyMat4(viewMatrix, m.modelMatrix)
            val mvp = multiplyMat4(projection, modelView)
            val normalMatrix = extractNormalMatrix(modelView)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uModel"), 1, false, m.modelMatrix, 0)
            GLES30.glUniformMatrix3fv(uNormalMatrix, 1, false, normalMatrix, 0)
            GLES30.glUniform1f(uMetallic, m.metallic)
            GLES30.glUniform1f(uRoughness, m.roughness)
            GLES30.glUniform1f(uExposure, m.exposure)
            GLES30.glUniform1f(uSelected, if (m.selected) 1.0f else 0.0f)
            GLES30.glUniform1f(uContrast, m.contrast)
            GLES30.glUniform1f(uSaturation, m.saturation)
            if (m.textureId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.textureId)
                GLES30.glUniform1i(uBaseColor, 0)
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
        val gridModel = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,gridHeight,0f,1f)
        val mvp = multiplyMat4(projection, multiplyMat4(viewMatrix, gridModel))
        GLES30.glUseProgram(gridProgramId)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgramId, "uMVP"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gridProgramId, "uGridY"), gridHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gridProgramId, "uGridScale"), gridScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gridProgramId, "uAlpha"), gridAlpha)

        // Shadow uniforms
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gridProgramId, "uShadowMVP"), 1, false, shadowLightMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gridProgramId, "uShadowDarkness"), if (shadowEnabled) shadowDarkness else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gridProgramId, "uShadowSoftness"), shadowSoftness)

        // Bind shadow map to texture unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowMapTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gridProgramId, "uShadowMap"), 0)

        GLES30.glBindVertexArray(gridVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ── Shadow Planes ──

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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowMap"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowMVP"), 1, false, shadowLightMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowDarkness"), shadowDarkness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uShadowSoftness"), shadowSoftness)

        for (plane in shadowPlanes) {
            if (plane.label == 2) continue  // skip ceilings

            // Build model matrix: translate + rotate(quat) + scale(extents)
            val q = plane
            val sx = q.extentX; val sz = q.extentY
            val x2 = q.rotX*2; val y2 = q.rotY*2; val z2 = q.rotZ*2
            val xx = q.rotX*x2; val xy = q.rotX*y2; val xz = q.rotX*z2
            val yy = q.rotY*y2; val yz = q.rotY*z2; val zz = q.rotZ*z2
            val wx = q.rotW*x2; val wy = q.rotW*y2; val wz = q.rotW*z2

            // Column-major: rotation columns scaled by extents, then translation
            val model = floatArrayOf(
                (1f-(yy+zz))*sx, (xy+wz)*sx, (xz-wy)*sx, 0f,
                (xy-wz), (1f-(xx+zz)), (yz+wx), 0f,
                (xz+wy)*sz, (yz-wx)*sz, (1f-(xx+yy))*sz, 0f,
                q.posX, q.posY, q.posZ, 1f
            )
            val mvp = multiplyMat4(projection, multiplyMat4(viewMatrix, model))

            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uMVP"), 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(shadowPlaneProgramId, "uModel"), 1, false, model, 0)

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
        val gizmoModel = quatToMatrix(modelRot, modelPos)
        val mv = multiplyMat4(viewMatrix, gizmoModel)
        val mvp = multiplyMat4(projection, mv)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(gizmoProgramId)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(gizmoProgramId, "uMVP"), 1, false, mvp, 0)

        GLES30.glBindVertexArray(gizmoVao)
        // Draw each axis with highlight
        for (axis in 0..2) {
            val hl = if (axis == highlightAxis) 1.0f else 0.0f
            GLES30.glUniform1f(GLES30.glGetUniformLocation(gizmoProgramId, "uHighlight"), hl)
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
        val axes = arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f)
        )
        var bestAxis = GIZMO_AXIS_NONE
        var bestDist = Float.MAX_VALUE

        for (i in 0..2) {
            // Transform axis to world space
            val worldAxis = rotateVecByQuat(axes[i], modelRot)
            // Ray-line closest distance
            val result = rayLineClosest(rayOrigin, rayDir, modelPos, worldAxis, GIZMO_LENGTH)
            if (result != null && result.first < GIZMO_HIT_RADIUS && result.second < bestDist) {
                bestAxis = i
                bestDist = result.second
            }
        }
        return Pair(bestAxis, if (bestAxis >= 0) bestDist else -1f)
    }

    /** Get world-space axis direction for a gizmo axis */
    fun getGizmoWorldAxis(axis: Int, modelRot: FloatArray): FloatArray {
        val local = when (axis) {
            GIZMO_AXIS_X -> floatArrayOf(1f, 0f, 0f)
            GIZMO_AXIS_Y -> floatArrayOf(0f, 1f, 0f)
            GIZMO_AXIS_Z -> floatArrayOf(0f, 0f, 1f)
            else -> floatArrayOf(0f, 0f, 0f)
        }
        return rotateVecByQuat(local, modelRot)
    }

    // ── Laser ──

    fun renderLaser(swapchainTexId: Int, width: Int, height: Int,
                    projection: FloatArray, viewMatrix: FloatArray,
                    handPos: FloatArray, aimQuat: FloatArray,
                    hitDistance: Float, color: FloatArray = floatArrayOf(0f, 0.8f, 1f)) {
        if (!initialized || laserProgramId == 0) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)

        val laserModel = quatToMatrix(aimQuat, handPos)
        val mv = multiplyMat4(viewMatrix, laserModel)
        val mvp = multiplyMat4(projection, mv)

        GLES30.glUseProgram(laserProgramId)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(laserProgramId, "uMVP"), 1, false, mvp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(laserProgramId, "uColor"), color[0], color[1], color[2])

        GLES30.glBindVertexArray(laserVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, laserIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)

        if (hitDistance >= 0f) {
            val fwd = quatForward(aimQuat)
            val dotPos = floatArrayOf(
                handPos[0] + fwd[0] * hitDistance,
                handPos[1] + fwd[1] * hitDistance,
                handPos[2] + fwd[2] * hitDistance
            )
            val dotModel = floatArrayOf(
                0.01f,0f,0f,0f, 0f,0.01f,0f,0f, 0f,0f,0.01f,0f,
                dotPos[0], dotPos[1], dotPos[2], 1f
            )
            val dotMvp = multiplyMat4(projection, multiplyMat4(viewMatrix, dotModel))
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(laserProgramId, "uMVP"), 1, false, dotMvp, 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(laserProgramId, "uColor"), 1f, 1f, 1f)
            GLES30.glBindVertexArray(dotVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 4)
            GLES30.glBindVertexArray(0)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun finishEyePass() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }

    // Panel position — settable for drag
    var panelX = 0f; var panelY = 0f; var panelZ = -1f
    var panelW = 1.1f; var panelH = 1.2f

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
        val panelModel = floatArrayOf(
            rightX*panelW,  rightY*panelW,  rightZ*panelW,  0f,
            -bupX*panelH,  -bupY*panelH,  -bupZ*panelH,   0f,
            fwdX,          fwdY,          fwdZ,          0f,
            panelX,        panelY,        panelZ,        1f
        )

        val mvp = multiplyMat4(projection, multiplyMat4(viewMatrix, panelModel))
        GLES30.glUseProgram(uiProgramId)
        GLES30.glUniformMatrix4fv(uiUMvp, 1, false, mvp, 0)
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

    fun destroy() {
        for (m in models) {
            if (m.vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            if (m.vboPositions != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboPositions), 0)
            if (m.vboNormals != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboNormals), 0)
            if (m.vboTexCoords != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.vboTexCoords), 0)
            if (m.ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(m.ebo), 0)
            if (m.textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(m.textureId), 0)
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
        if (spVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(spVao), 0)
        if (spVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(spVbo), 0)
        if (uiProgramId != 0) GLES30.glDeleteProgram(uiProgramId)
        if (uiVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(uiVao), 0)
        if (uiVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(uiVbo), 0)
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

    // ── Math ──

    private fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0..3) for (row in 0..3)
            r[col*4+row] = a[row]*b[col*4] + a[4+row]*b[col*4+1] + a[8+row]*b[col*4+2] + a[12+row]*b[col*4+3]
        return r
    }
    private fun extractNormalMatrix(mv: FloatArray) = floatArrayOf(
        mv[0],mv[1],mv[2], mv[4],mv[5],mv[6], mv[8],mv[9],mv[10])

    private fun quatToMatrix(q: FloatArray, pos: FloatArray): FloatArray {
        val x=q[0];val y=q[1];val z=q[2];val w=q[3]
        val x2=x+x;val y2=y+y;val z2=z+z
        val xx=x*x2;val xy=x*y2;val xz=x*z2;val yy=y*y2;val yz=y*z2;val zz=z*z2
        val wx=w*x2;val wy=w*y2;val wz=w*z2
        return floatArrayOf(
            1f-(yy+zz), xy+wz, xz-wy, 0f,
            xy-wz, 1f-(xx+zz), yz+wx, 0f,
            xz+wy, yz-wx, 1f-(xx+yy), 0f,
            pos[0], pos[1], pos[2], 1f)
    }

    fun quatForward(q: FloatArray): FloatArray {
        val x=q[0];val y=q[1];val z=q[2];val w=q[3]
        return floatArrayOf(-(2f*(x*z+w*y)), -(2f*(y*z-w*x)), -(1f-2f*(x*x+y*y)))
    }

    fun rotateVecByQuat(v: FloatArray, q: FloatArray): FloatArray {
        val qx=q[0];val qy=q[1];val qz=q[2];val qw=q[3]
        val ix = qw*v[0] + qy*v[2] - qz*v[1]
        val iy = qw*v[1] + qz*v[0] - qx*v[2]
        val iz = qw*v[2] + qx*v[1] - qy*v[0]
        val iw = -qx*v[0] - qy*v[1] - qz*v[2]
        return floatArrayOf(
            ix*qw + iw*(-qx) + iy*(-qz) - iz*(-qy),
            iy*qw + iw*(-qy) + iz*(-qx) - ix*(-qz),
            iz*qw + iw*(-qz) + ix*(-qy) - iy*(-qx))
    }

    /** Ray-line segment closest approach. Returns (distance, rayT) or null if segment param out of range */
    private fun rayLineClosest(rayO: FloatArray, rayD: FloatArray,
                               lineO: FloatArray, lineD: FloatArray, lineLen: Float): Pair<Float, Float>? {
        val w = floatArrayOf(rayO[0]-lineO[0], rayO[1]-lineO[1], rayO[2]-lineO[2])
        val a = dot3(rayD, rayD); val b = dot3(rayD, lineD); val c = dot3(lineD, lineD)
        val d = dot3(rayD, w); val e = dot3(lineD, w)
        val denom = a*c - b*b
        if (kotlin.math.abs(denom) < 0.00001f) return null
        val sc = (b*e - c*d) / denom
        val tc = (a*e - b*d) / denom
        if (sc < 0f || tc < 0f || tc > lineLen) return null
        val dx = w[0]+sc*rayD[0]-tc*lineD[0]
        val dy = w[1]+sc*rayD[1]-tc*lineD[1]
        val dz = w[2]+sc*rayD[2]-tc*lineD[2]
        return Pair(kotlin.math.sqrt(dx*dx+dy*dy+dz*dz), sc)
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
uniform mat4 uMVP, uModelView, uModel;
uniform mat3 uNormalMatrix;
out vec3 vNormal, vPosition;
out vec2 vTexCoord;
out float vWorldY;
void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    vNormal = normalize(uNormalMatrix * aNormal);
    vTexCoord = aTexCoord;
    vPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
    vWorldY = (uModel * vec4(aPosition, 1.0)).y;
}
"""

private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal, vPosition;
in vec2 vTexCoord;
in float vWorldY;
uniform sampler2D uBaseColor;
uniform float uMetallic, uRoughness, uExposure, uLightIntensity, uFillLightIntensity, uAmbientIntensity, uSelected, uClipY, uUseSH, uContrast, uSaturation;
uniform vec3 uLightDir, uLightColor, uFillLightDir, uFillLightColor, uAmbientColor;
uniform vec3 uSH[9]; // L2 spherical harmonics (9 RGB coefficients)
out vec4 fragColor;

// Evaluate L2 spherical harmonics irradiance at direction n
vec3 evalSH(vec3 n) {
    float c1 = 0.429043; float c2 = 0.511664;
    float c3 = 0.743125; float c4 = 0.886227; float c5 = 0.247708;
    return max(vec3(0.0),
        c4 * uSH[0] +
        2.0 * c2 * (uSH[1] * n.y + uSH[2] * n.z + uSH[3] * n.x) +
        2.0 * c1 * (uSH[4] * n.x * n.y + uSH[5] * n.y * n.z + uSH[7] * n.x * n.z) +
        c5 * uSH[6] * (3.0 * n.z * n.z - 1.0) +
        c1 * uSH[8] * (n.x * n.x - n.y * n.y));
}

void main() {
    if (vWorldY < uClipY) discard;
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vPosition);
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(L + V);
    vec3 albedo = pow(texture(uBaseColor, vTexCoord).rgb, vec3(2.2));
    float NdotL = max(dot(N, L), 0.0);
    float NdotH = max(dot(N, H), 0.0);
    float NdotV = max(dot(N, V), 0.001);
    float alpha = uRoughness * uRoughness;
    float alpha2 = alpha * alpha;
    float denom = NdotH * NdotH * (alpha2 - 1.0) + 1.0;
    float D = alpha2 / (3.14159265 * denom * denom + 0.0001);
    vec3 F0 = mix(vec3(0.04), albedo, uMetallic);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - max(dot(H, V), 0.0), 5.0);
    float k = (uRoughness + 1.0) * (uRoughness + 1.0) / 8.0;
    float G = (NdotV / (NdotV * (1.0 - k) + k)) * (NdotL / (NdotL * (1.0 - k) + k));
    vec3 spec = D * F * G / (4.0 * NdotV * NdotL + 0.0001);
    vec3 diffuse = albedo * (1.0 - uMetallic) * (vec3(1.0) - F) / 3.14159265;
    vec3 color = (diffuse + spec) * NdotL * uLightColor * uLightIntensity;
    vec3 fillL = normalize(uFillLightDir);
    vec3 fillH = normalize(fillL + V);
    float fNdotL = max(dot(N, fillL), 0.0);
    float fNdotH = max(dot(N, fillH), 0.0);
    float fD = alpha2 / (3.14159265 * pow(fNdotH * fNdotH * (alpha2 - 1.0) + 1.0, 2.0) + 0.0001);
    vec3 fSpec = fD * F * G / (4.0 * NdotV * fNdotL + 0.0001);
    color += (diffuse + fSpec * 0.5) * fNdotL * uFillLightColor * uFillLightIntensity;
    // Ambient: use SH irradiance when available, fallback to hemisphere
    if (uUseSH > 0.5) {
        vec3 shIrrad = evalSH(N);
        color += albedo * shIrrad * uAmbientIntensity;
    } else {
        color += albedo * mix(vec3(0.05, 0.05, 0.08), vec3(0.15, 0.15, 0.12), N.y * 0.5 + 0.5) * uAmbientColor * uAmbientIntensity;
    }
    if (uSelected > 0.5) { float rim = pow(1.0 - NdotV, 3.0); color += vec3(0.0, 0.8, 1.0) * rim * 0.8; }
    color *= exp2(uExposure);
    color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
    color = clamp(color, 0.0, 1.0);
    // Contrast: smooth S-curve (gentler than linear pivot, preserves detail)
    color = mix(vec3(0.5), color, uContrast);
    // Saturation: lerp between luminance and color
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
out float vAlpha;
void main() { gl_Position = uMVP * vec4(aPosition, 1.0); vAlpha = aAlpha; }
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
uniform float uGridScale, uAlpha, uShadowDarkness, uShadowSoftness;
uniform mat4 uShadowMVP;
uniform sampler2D uShadowMap;
out vec4 fragColor;

float getShadow() {
    vec4 lp = uShadowMVP * vec4(vWorldPos, 1.0);
    vec3 pc = lp.xyz / lp.w * 0.5 + 0.5;
    if (pc.x < 0.0 || pc.x > 1.0 || pc.y < 0.0 || pc.y > 1.0 || pc.z > 1.0) return 0.0;
    float bias = 0.0;
    float shadow = 0.0;
    vec2 ts = vec2(1.0) / vec2(textureSize(uShadowMap, 0));
    float r = uShadowSoftness;
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * r).r;
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
    float shadowFade = 1.0 - smoothstep(3.0, 8.0, dist);  // shadows visible further than grid
    float shadow = uShadowDarkness > 0.0 ? getShadow() : 0.0;
    float shadowA = shadow * uShadowDarkness * shadowFade;

    float totalA = max(gridA, shadowA);
    if (totalA < 0.01) discard;

    // Grid lines are gray, shadow is dark
    vec3 color = gridA > shadowA ? vec3(0.5) : vec3(0.0);
    // In shadow + grid overlap, darken the grid slightly
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
uniform float uShadowDarkness;
uniform float uShadowSoftness;
out vec4 fragColor;

void main() {
    // Shadow map lookup
    vec4 lp = uShadowMVP * vec4(vWorldPos, 1.0);
    vec3 pc = lp.xyz / lp.w * 0.5 + 0.5;
    if (pc.x < 0.0 || pc.x > 1.0 || pc.y < 0.0 || pc.y > 1.0 || pc.z > 1.0) discard;

    // 5x5 PCF with variable softness
    float shadow = 0.0;
    vec2 ts = 1.0 / vec2(textureSize(uShadowMap, 0));
    float r = uShadowSoftness;
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            float d = texture(uShadowMap, pc.xy + vec2(float(x), float(y)) * ts * r).r;
            shadow += pc.z > d ? 1.0 : 0.0;
        }
    }
    shadow /= 25.0;

    // Edge fade - smooth falloff at plane boundaries (no hard clip)
    float ef = smoothstep(0.0, 0.05, vUV.x) * smoothstep(0.0, 0.05, 1.0 - vUV.x)
             * smoothstep(0.0, 0.05, vUV.y) * smoothstep(0.0, 0.05, 1.0 - vUV.y);

    float a = shadow * ef * uShadowDarkness;
    if (a < 0.005) discard;

    // Pure black shadow over passthrough - the most realistic approach for AR
    fragColor = vec4(0.0, 0.0, 0.0, a);
}
"""
