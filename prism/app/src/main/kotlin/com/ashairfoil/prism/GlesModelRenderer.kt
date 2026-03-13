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
    }

    // GL resources
    private var programId = 0
    private var vao = 0
    private var vboPositions = 0
    private var vboNormals = 0
    private var vboTexCoords = 0
    private var ebo = 0
    private var textureId = 0
    private var indexCount = 0
    private var fbo = 0
    private var depthRb = 0

    // UI overlay resources
    private var uiProgramId = 0
    private var uiVao = 0
    private var uiVbo = 0
    private var uiUTex = -1
    private var uiUMvp = -1

    // Uniforms
    private var uMVP = -1
    private var uModelView = -1
    private var uNormalMatrix = -1
    private var uBaseColor = -1
    private var uMetallic = -1
    private var uRoughness = -1
    private var uExposure = -1
    private var uLightDir = -1

    // Material state
    var metallic = 0f
    var roughness = 0.9f
    var exposure = 0f

    // Model transform (column-major 4x4)
    var modelMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, -2f, 1f
    )

    private var initialized = false
    private var frameCount = 0

    fun init(): Boolean {
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            Log.e(TAG, "Failed to create shader program")
            return false
        }

        uMVP = GLES30.glGetUniformLocation(programId, "uMVP")
        uModelView = GLES30.glGetUniformLocation(programId, "uModelView")
        uNormalMatrix = GLES30.glGetUniformLocation(programId, "uNormalMatrix")
        uBaseColor = GLES30.glGetUniformLocation(programId, "uBaseColor")
        uMetallic = GLES30.glGetUniformLocation(programId, "uMetallic")
        uRoughness = GLES30.glGetUniformLocation(programId, "uRoughness")
        uExposure = GLES30.glGetUniformLocation(programId, "uExposure")
        uLightDir = GLES30.glGetUniformLocation(programId, "uLightDir")

        // Create FBO for rendering to swapchain textures
        val fboBuf = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fboBuf, 0)
        fbo = fboBuf[0]

        val rbBuf = intArrayOf(0)
        GLES30.glGenRenderbuffers(1, rbBuf, 0)
        depthRb = rbBuf[0]

        // ── UI overlay quad setup ──
        uiProgramId = createProgram(UI_VERTEX_SHADER, UI_FRAGMENT_SHADER)
        if (uiProgramId == 0) {
            Log.e(TAG, "Failed to create UI overlay shader program")
            return false
        }
        uiUTex = GLES30.glGetUniformLocation(uiProgramId, "uTex")
        uiUMvp = GLES30.glGetUniformLocation(uiProgramId, "uMVP")

        // Unit quad [-0.5, 0.5] — scaled/positioned by model matrix in the shader
        val quadVerts = floatArrayOf(
            // x,     y,    u,   v
            -0.5f, -0.5f, 0f, 1f,
             0.5f, -0.5f, 1f, 1f,
            -0.5f,  0.5f, 0f, 0f,
             0.5f,  0.5f, 1f, 0f
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

        // location 0 = position (xy), location 1 = texcoord (uv)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glBindVertexArray(0)

        initialized = true
        Log.i(TAG, "GLES renderer initialized")
        return true
    }

    fun loadGlb(glbBytes: ByteArray): Boolean {
        try {
            if (glbBytes.size < 20) return false

            // Parse GLB header
            val jsonLength = readU32(glbBytes, 12)
            val jsonStr = String(glbBytes, 20, jsonLength.coerceAtMost(glbBytes.size - 20), Charsets.UTF_8)
            val json = org.json.JSONObject(jsonStr)

            // Binary chunk offset
            val binChunkStart = 12 + 8 + jsonLength + 8
            if (binChunkStart >= glbBytes.size) {
                Log.e(TAG, "No binary chunk in GLB")
                return false
            }

            // Parse buffer views
            val bufferViews = json.getJSONArray("bufferViews")
            fun getBufferView(idx: Int): Pair<Int, Int> {
                val bv = bufferViews.getJSONObject(idx)
                val offset = bv.optInt("byteOffset", 0)
                val length = bv.getInt("byteLength")
                return Pair(binChunkStart + offset, length)
            }

            // Parse accessors
            val accessors = json.getJSONArray("accessors")
            fun getAccessor(idx: Int): Triple<Int, Int, String> {
                val acc = accessors.getJSONObject(idx)
                val bvIdx = acc.getInt("bufferView")
                val count = acc.getInt("count")
                val type = acc.getString("type")
                return Triple(bvIdx, count, type)
            }

            // Find the first mesh primitive
            val meshes = json.getJSONArray("meshes")
            val primitives = meshes.getJSONObject(0).getJSONArray("primitives")
            val prim = primitives.getJSONObject(0)
            val attributes = prim.getJSONObject("attributes")

            // Position
            val posAccIdx = attributes.getInt("POSITION")
            val (posBvIdx, posCount, _) = getAccessor(posAccIdx)
            val (posOffset, posLength) = getBufferView(posBvIdx)
            val positions = extractFloats(glbBytes, posOffset, posCount * 3)

            // Texture coordinates
            var texCoords: FloatBuffer? = null
            if (attributes.has("TEXCOORD_0")) {
                val tcAccIdx = attributes.getInt("TEXCOORD_0")
                val (tcBvIdx, tcCount, _) = getAccessor(tcAccIdx)
                val (tcOffset, _) = getBufferView(tcBvIdx)
                texCoords = extractFloats(glbBytes, tcOffset, tcCount * 2)
            }

            // Normals (compute if missing)
            var normals: FloatBuffer
            if (attributes.has("NORMAL")) {
                val nAccIdx = attributes.getInt("NORMAL")
                val (nBvIdx, nCount, _) = getAccessor(nAccIdx)
                val (nOffset, _) = getBufferView(nBvIdx)
                normals = extractFloats(glbBytes, nOffset, nCount * 3)
            } else {
                normals = FloatBuffer.allocate(posCount * 3) // will compute below
            }

            // Indices
            val idxAccIdx = prim.getInt("indices")
            val (idxBvIdx, idxCount, _) = getAccessor(idxAccIdx)
            val idxAcc = accessors.getJSONObject(idxAccIdx)
            val idxComponentType = idxAcc.getInt("componentType")
            val (idxOffset, _) = getBufferView(idxBvIdx)
            val indices = extractIndices(glbBytes, idxOffset, idxCount, idxComponentType)
            indexCount = idxCount

            // Compute normals if missing
            if (!attributes.has("NORMAL")) {
                normals = computeNormals(positions, indices, posCount, idxCount)
            }

            Log.i(TAG, "Mesh: $posCount vertices, $idxCount indices")

            // Upload to GL
            val vaoBuf = intArrayOf(0)
            GLES30.glGenVertexArrays(1, vaoBuf, 0)
            vao = vaoBuf[0]
            GLES30.glBindVertexArray(vao)

            // Positions (location 0)
            vboPositions = createVBO(positions, 0, 3)
            // Normals (location 1)
            vboNormals = createVBO(normals, 1, 3)
            // TexCoords (location 2)
            if (texCoords != null) {
                vboTexCoords = createVBO(texCoords, 2, 2)
            }

            // Index buffer
            val eboBuf = intArrayOf(0)
            GLES30.glGenBuffers(1, eboBuf, 0)
            ebo = eboBuf[0]
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
            indices.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idxCount * 4, indices, GLES30.GL_STATIC_DRAW)

            GLES30.glBindVertexArray(0)

            // Load texture
            val images = json.optJSONArray("images")
            if (images != null && images.length() > 0) {
                val img = images.getJSONObject(0)
                val texBvIdx = img.optInt("bufferView", -1)
                if (texBvIdx >= 0) {
                    val (texOff, texLen) = getBufferView(texBvIdx)
                    val texBytes = ByteArray(texLen)
                    System.arraycopy(glbBytes, texOff, texBytes, 0, texLen)
                    val bitmap = BitmapFactory.decodeByteArray(texBytes, 0, texLen)
                    if (bitmap != null) {
                        val texBuf = intArrayOf(0)
                        GLES30.glGenTextures(1, texBuf, 0)
                        textureId = texBuf[0]
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                        bitmap.recycle()
                        Log.i(TAG, "Texture loaded: ${bitmap.width}x${bitmap.height}")
                    }
                }
            }

            // Parse material properties
            val mats = json.optJSONArray("materials")
            if (mats != null && mats.length() > 0) {
                val pbr = mats.getJSONObject(0).optJSONObject("pbrMetallicRoughness")
                if (pbr != null) {
                    metallic = pbr.optDouble("metallicFactor", 0.0).toFloat()
                    roughness = pbr.optDouble("roughnessFactor", 0.9).toFloat()
                }
            }

            Log.i(TAG, "GLB loaded successfully: $posCount verts, $idxCount indices, metallic=$metallic, roughness=$roughness")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GLB", e)
            return false
        }
    }

    fun renderEye(swapchainTexId: Int, width: Int, height: Int,
                  projection: FloatArray, viewMatrix: FloatArray) {
        if (!initialized || indexCount == 0) return

        // Bind swapchain texture as FBO color attachment
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, swapchainTexId, 0)

        // Attach depth renderbuffer (resize if needed)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, depthRb)

        GLES30.glViewport(0, 0, width, height)

        // Check FBO status
        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            if (frameCount < 10 || frameCount % 500 == 0) {
                Log.e(TAG, "FBO incomplete: 0x${Integer.toHexString(fboStatus)} for tex $swapchainTexId, context=${android.opengl.EGL14.eglGetCurrentContext()}")
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return
        }

        // Clear to transparent for passthrough (alpha=0 → real world shows through)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Compute matrices
        val modelView = multiplyMat4(viewMatrix, modelMatrix)
        val mvp = multiplyMat4(projection, modelView)
        val normalMatrix = extractNormalMatrix(modelView)

        // Draw
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glUniformMatrix3fv(uNormalMatrix, 1, false, normalMatrix, 0)
        GLES30.glUniform1f(uMetallic, metallic)
        GLES30.glUniform1f(uRoughness, roughness)
        GLES30.glUniform1f(uExposure, exposure)
        GLES30.glUniform3f(uLightDir, 0.3f, 1.0f, 0.5f) // from above-right

        if (textureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glUniform1i(uBaseColor, 0)
        }

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error after draw: 0x${Integer.toHexString(err)}")
        } else if (frameCount < 3) {
            Log.i(TAG, "Frame $frameCount rendered: $indexCount tris, tex=$swapchainTexId, fbo=$fbo, vao=$vao")
        }
        frameCount++

        GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun renderUiOverlay(swapchainTexId: Int, width: Int, height: Int, uiTexId: Int,
                        projection: FloatArray, viewMatrix: FloatArray) {
        if (uiProgramId == 0 || uiVao == 0) return

        // Bind swapchain texture to our FBO (same FBO reused)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, swapchainTexId, 0)
        GLES30.glViewport(0, 0, width, height)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Compute MVP for a world-space quad (0.6m wide, at 1.2m in front, 1.5m up)
        val panelModel = floatArrayOf(
            0.6f, 0f, 0f, 0f,   // 0.6m wide
            0f, 0.6f, 0f, 0f,   // 0.6m tall
            0f, 0f, 1f, 0f,
            0f, 0f, -1.0f, 1f // position: center, origin height, 1.0m forward
        )
        val mv = multiplyMat4(viewMatrix, panelModel)
        val mvp = multiplyMat4(projection, mv)

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
        if (programId != 0) GLES30.glDeleteProgram(programId)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vboPositions != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboPositions), 0)
        if (vboNormals != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboNormals), 0)
        if (vboTexCoords != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboTexCoords), 0)
        if (ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(ebo), 0)
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        if (uiProgramId != 0) GLES30.glDeleteProgram(uiProgramId)
        if (uiVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(uiVao), 0)
        if (uiVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(uiVbo), 0)
        Log.i(TAG, "GLES renderer destroyed")
    }

    // ── Helpers ──

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun extractFloats(bytes: ByteArray, offset: Int, count: Int): FloatBuffer {
        val buf = ByteBuffer.wrap(bytes, offset, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val fb = FloatBuffer.allocate(count)
        fb.put(buf)
        fb.position(0)
        return fb
    }

    private fun extractIndices(bytes: ByteArray, offset: Int, count: Int, componentType: Int): IntBuffer {
        val ib = IntBuffer.allocate(count)
        when (componentType) {
            5123 -> { // UNSIGNED_SHORT
                val sb = ByteBuffer.wrap(bytes, offset, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until count) ib.put(sb.get().toInt() and 0xFFFF)
            }
            5125 -> { // UNSIGNED_INT
                val ub = ByteBuffer.wrap(bytes, offset, count * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (i in 0 until count) ib.put(ub.get())
            }
            else -> Log.e(TAG, "Unknown index type: $componentType")
        }
        ib.position(0)
        return ib
    }

    private fun computeNormals(positions: FloatBuffer, indices: IntBuffer, vertCount: Int, idxCount: Int): FloatBuffer {
        val normals = FloatArray(vertCount * 3)
        positions.position(0)
        indices.position(0)
        val pos = FloatArray(vertCount * 3)
        positions.get(pos)

        for (i in 0 until idxCount step 3) {
            indices.position(i)
            val i0 = indices.get(); val i1 = indices.get(); val i2 = indices.get()
            val ax = pos[i1*3] - pos[i0*3]; val ay = pos[i1*3+1] - pos[i0*3+1]; val az = pos[i1*3+2] - pos[i0*3+2]
            val bx = pos[i2*3] - pos[i0*3]; val by = pos[i2*3+1] - pos[i0*3+1]; val bz = pos[i2*3+2] - pos[i0*3+2]
            val nx = ay*bz - az*by; val ny = az*bx - ax*bz; val nz = ax*by - ay*bx
            for (idx in intArrayOf(i0, i1, i2)) {
                normals[idx*3] += nx; normals[idx*3+1] += ny; normals[idx*3+2] += nz
            }
        }
        // Normalize
        for (v in 0 until vertCount) {
            val x = normals[v*3]; val y = normals[v*3+1]; val z = normals[v*3+2]
            val len = kotlin.math.sqrt(x*x + y*y + z*z)
            if (len > 0.0001f) { normals[v*3] = x/len; normals[v*3+1] = y/len; normals[v*3+2] = z/len }
        }
        positions.position(0)
        indices.position(0)
        return FloatBuffer.wrap(normals)
    }

    private fun createVBO(data: FloatBuffer, location: Int, size: Int): Int {
        val buf = intArrayOf(0)
        GLES30.glGenBuffers(1, buf, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
        data.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining() * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 0, 0)
        return buf[0]
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = intArrayOf(0)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = intArrayOf(0)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    // ── Matrix Math (column-major) ──

    private fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            r[col*4+row] = a[0*4+row]*b[col*4+0] + a[1*4+row]*b[col*4+1] +
                           a[2*4+row]*b[col*4+2] + a[3*4+row]*b[col*4+3]
        }
        return r
    }

    private fun extractNormalMatrix(modelView: FloatArray): FloatArray {
        // Upper-left 3x3 of modelView, transposed inverse
        // For uniform scale, just use the 3x3 directly
        return floatArrayOf(
            modelView[0], modelView[1], modelView[2],
            modelView[4], modelView[5], modelView[6],
            modelView[8], modelView[9], modelView[10]
        )
    }
}

private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;
uniform mat4 uMVP;
uniform mat4 uModelView;
uniform mat3 uNormalMatrix;
out vec3 vNormal;
out vec2 vTexCoord;
out vec3 vPosition;
void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    vNormal = normalize(uNormalMatrix * aNormal);
    vTexCoord = aTexCoord;
    vPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
}
"""

private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec2 vTexCoord;
in vec3 vPosition;
uniform sampler2D uBaseColor;
uniform float uMetallic;
uniform float uRoughness;
uniform float uExposure;
uniform vec3 uLightDir;
out vec4 fragColor;

void main() {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vPosition);
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(L + V);

    vec3 albedo = texture(uBaseColor, vTexCoord).rgb;
    // sRGB to linear
    albedo = pow(albedo, vec3(2.2));

    float NdotL = max(dot(N, L), 0.0);
    float NdotH = max(dot(N, H), 0.0);
    float NdotV = max(dot(N, V), 0.001);

    // GGX-ish specular
    float alpha = uRoughness * uRoughness;
    float alpha2 = alpha * alpha;
    float denom = NdotH * NdotH * (alpha2 - 1.0) + 1.0;
    float D = alpha2 / (3.14159265 * denom * denom + 0.0001);

    // Fresnel (Schlick)
    vec3 F0 = mix(vec3(0.04), albedo, uMetallic);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - max(dot(H, V), 0.0), 5.0);

    // Geometry (Smith-Schlick)
    float k = (uRoughness + 1.0) * (uRoughness + 1.0) / 8.0;
    float G = (NdotV / (NdotV * (1.0 - k) + k)) * (NdotL / (NdotL * (1.0 - k) + k));

    vec3 spec = D * F * G / (4.0 * NdotV * NdotL + 0.0001);
    vec3 diffuse = albedo * (1.0 - uMetallic) * (vec3(1.0) - F) / 3.14159265;

    vec3 color = (diffuse + spec) * NdotL * vec3(2.0);

    // Ambient (simple hemisphere)
    vec3 ambient = albedo * mix(vec3(0.05, 0.05, 0.08), vec3(0.15, 0.15, 0.12), N.y * 0.5 + 0.5);
    color += ambient;

    // Exposure
    color *= exp2(uExposure);

    // ACES tone mapping
    color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);

    // Linear to sRGB
    color = pow(clamp(color, 0.0, 1.0), vec3(1.0/2.2));

    fragColor = vec4(color, 1.0);
}
"""

private const val UI_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
uniform mat4 uMVP;
out vec2 vUV;
void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vUV = aUV;
}
"""

private const val UI_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV);
}
"""
