package com.ashairfoil.prism

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.util.Log
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilamentRenderer(
    private val nativeEglContext: Long,
    private val nativeEglDisplay: Long
) {
    companion object {
        private const val TAG = "ChloeVR-Filament"
    }

    // Filament core
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var cameras: Array<Camera?> = arrayOfNulls(2) // left, right
    private var views: Array<View?> = arrayOfNulls(2)
    private var swapChain: SwapChain? = null

    // gltfio
    private var materialProvider: MaterialProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null

    // Offscreen render targets (Filament-owned, one per eye)
    private var offscreenWidth = 0
    private var offscreenHeight = 0
    private var renderTargets: Array<RenderTarget?> = arrayOfNulls(2)
    private var offscreenColorTextures: Array<Texture?> = arrayOfNulls(2)
    private var offscreenDepthTextures: Array<Texture?> = arrayOfNulls(2)
    private var offscreenGlTexIds = intArrayOf(0, 0)  // GL texture IDs for blit

    // Lighting
    @EntityInstance private var sunEntity = 0
    @EntityInstance private var iblEntity = 0

    // Loaded assets
    data class LoadedAsset(
        val asset: FilamentAsset,
        val rootEntity: Int
    )
    private val assets = mutableListOf<LoadedAsset>()

    fun init(): Boolean {
        try {
            // Filament's sharedContext() needs an android.opengl.EGLContext object.
            // The native OpenXR renderer made its EGL context current on this thread,
            // so EGL14.eglGetCurrentContext() returns it as the proper Java wrapper.
            val eglContext = EGL14.eglGetCurrentContext()
            Log.i(TAG, "EGL context for Filament: $eglContext (handle=${eglContext.nativeHandle})")

            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "No EGL context current on this thread! Cannot create Filament engine.")
                return false
            }

            // Create Filament engine with shared GL context.
            // CRITICAL: Must release the current context before Engine.create().
            val eglDisplay = EGL14.eglGetCurrentDisplay()
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            Log.i(TAG, "Released EGL context before Filament init")

            engine = Engine.Builder()
                .backend(Engine.Backend.OPENGL)
                .sharedContext(eglContext)
                .build()

            Log.i(TAG, "Filament engine created")

            renderer = engine!!.createRenderer()
            scene = engine!!.createScene()

            // Create headless swap chain (for Filament's internal frame bookkeeping)
            // Actual rendering goes to our RenderTargets (OpenXR swapchain textures)
            swapChain = engine!!.createSwapChain(16, 16, 0L)

            // Create views and cameras for each eye
            for (eye in 0..1) {
                cameras[eye] = engine!!.createCamera(EntityManager.get().create())
                views[eye] = engine!!.createView().apply {
                    this.scene = this@FilamentRenderer.scene
                    this.camera = cameras[eye]!!
                    // Transparent clear for passthrough
                    // Filament uses scene.skybox for background; null = clear color
                }
            }

            // Set up lighting: a single directional sun light
            sunEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.98f, 0.95f)
                .intensity(100_000f)
                .direction(0.2f, -1.0f, -0.5f)
                .castShadows(true)
                .build(engine!!, sunEntity)
            scene!!.addEntity(sunEntity)

            // Initialize gltfio for GLB loading
            materialProvider = UbershaderProvider(engine!!)
            assetLoader = AssetLoader(engine!!, materialProvider!!, EntityManager.get())
            resourceLoader = ResourceLoader(engine!!, true)

            Log.i(TAG, "Filament renderer initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Filament", e)
            return false
        }
    }

    fun loadGlb(glbBytes: ByteArray): LoadedAsset? {
        val engine = engine ?: return null
        val scene = scene ?: return null
        val loader = assetLoader ?: return null
        val resLoader = resourceLoader ?: return null

        try {
            val buffer = ByteBuffer.allocateDirect(glbBytes.size)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(glbBytes)
            buffer.flip()

            val asset = loader.createAsset(buffer)
            if (asset == null) {
                Log.e(TAG, "Failed to parse GLB")
                return null
            }

            resLoader.loadResources(asset)
            asset.releaseSourceData()

            // Add to scene
            scene.addEntities(asset.entities)

            val loaded = LoadedAsset(asset, asset.root)
            assets.add(loaded)

            Log.i(TAG, "GLB loaded: ${asset.entities.size} entities")
            return loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GLB", e)
            return null
        }
    }

    // We create our OWN GL textures, import them into Filament, render to them,
    // then blit to the OpenXR swapchain textures via raw GL (which we proved works).
    private var ownGlTexIds = intArrayOf(0, 0)
    private var ownGlDepthRBs = intArrayOf(0, 0)
    private var blitFboSrc = intArrayOf(0)
    private var blitFboDst = intArrayOf(0)

    fun ensureRenderTargets(width: Int, height: Int) {
        val engine = engine ?: return
        if (width == offscreenWidth && height == offscreenHeight && renderTargets[0] != null) return

        Log.i(TAG, "Creating render targets: ${width}x${height}")

        // Destroy old
        for (eye in 0..1) {
            renderTargets[eye]?.let { engine.destroyRenderTarget(it) }
            offscreenColorTextures[eye]?.let { engine.destroyTexture(it) }
            offscreenDepthTextures[eye]?.let { engine.destroyTexture(it) }
        }
        if (ownGlTexIds[0] != 0) {
            android.opengl.GLES30.glDeleteTextures(2, ownGlTexIds, 0)
            android.opengl.GLES30.glDeleteRenderbuffers(2, ownGlDepthRBs, 0)
        }
        if (blitFboSrc[0] != 0) {
            android.opengl.GLES30.glDeleteFramebuffers(1, blitFboSrc, 0)
            android.opengl.GLES30.glDeleteFramebuffers(1, blitFboDst, 0)
        }

        // Create our own GL textures and renderbuffers
        android.opengl.GLES30.glGenTextures(2, ownGlTexIds, 0)
        android.opengl.GLES30.glGenRenderbuffers(2, ownGlDepthRBs, 0)
        android.opengl.GLES30.glGenFramebuffers(1, blitFboSrc, 0)
        android.opengl.GLES30.glGenFramebuffers(1, blitFboDst, 0)

        for (eye in 0..1) {
            // Color texture
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ownGlTexIds[eye])
            android.opengl.GLES30.glTexImage2D(
                android.opengl.GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES30.GL_RGBA8,
                width, height, 0,
                android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, null)
            android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D,
                android.opengl.GLES30.GL_TEXTURE_MIN_FILTER, android.opengl.GLES30.GL_LINEAR)
            android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D,
                android.opengl.GLES30.GL_TEXTURE_MAG_FILTER, android.opengl.GLES30.GL_LINEAR)

            // Import into Filament
            val colorTex = Texture.Builder()
                .width(width).height(height).levels(1)
                .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
                .format(Texture.InternalFormat.RGBA8)
                .importTexture(ownGlTexIds[eye].toLong())
                .build(engine)

            val depthTex = Texture.Builder()
                .width(width).height(height).levels(1)
                .usage(Texture.Usage.DEPTH_ATTACHMENT)
                .format(Texture.InternalFormat.DEPTH24)
                .build(engine)

            val rt = RenderTarget.Builder()
                .texture(RenderTarget.AttachmentPoint.COLOR, colorTex)
                .texture(RenderTarget.AttachmentPoint.DEPTH, depthTex)
                .build(engine)

            renderTargets[eye] = rt
            offscreenColorTextures[eye] = colorTex
            offscreenDepthTextures[eye] = depthTex
        }

        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0)
        // CRITICAL: glFinish ensures our textures are fully created and visible
        // to Filament's shared GL context before we importTexture them
        android.opengl.GLES30.glFinish()
        offscreenWidth = width
        offscreenHeight = height
        Log.i(TAG, "Render targets created: GL textures ${ownGlTexIds[0]}, ${ownGlTexIds[1]}")
    }

    fun renderEye(eyeIndex: Int, swapchainTexId: Int, width: Int, height: Int,
                  projection: FloatArray, viewMatrix: FloatArray) {
        val engine = engine ?: return
        val renderer = renderer ?: return
        val view = views[eyeIndex] ?: return
        val camera = cameras[eyeIndex] ?: return
        val sc = swapChain ?: return

        ensureRenderTargets(width, height)

        view.renderTarget = renderTargets[eyeIndex]
        view.viewport = Viewport(0, 0, width, height)

        val projDouble = DoubleArray(16) { projection[it].toDouble() }
        camera.setCustomProjection(projDouble, 0.05, 100.0)
        val invView = invertMatrix4(viewMatrix)
        camera.setModelMatrix(invView)

        if (renderer.beginFrame(sc, 0)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun blitToSwapchain(eyeIndex: Int, swapchainTexId: Int, width: Int, height: Int) {
        // Blit from our GL texture to the OpenXR swapchain texture
        val srcTex = ownGlTexIds[eyeIndex]
        if (srcTex == 0) return

        // Source FBO: our texture
        android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, blitFboSrc[0])
        android.opengl.GLES30.glFramebufferTexture2D(
            android.opengl.GLES30.GL_READ_FRAMEBUFFER,
            android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
            android.opengl.GLES30.GL_TEXTURE_2D, srcTex, 0)

        // Dest FBO: OpenXR swapchain texture
        android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_DRAW_FRAMEBUFFER, blitFboDst[0])
        android.opengl.GLES30.glFramebufferTexture2D(
            android.opengl.GLES30.GL_DRAW_FRAMEBUFFER,
            android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
            android.opengl.GLES30.GL_TEXTURE_2D, swapchainTexId, 0)

        // Blit
        android.opengl.GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            android.opengl.GLES30.GL_COLOR_BUFFER_BIT,
            android.opengl.GLES30.GL_NEAREST)

        android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_FRAMEBUFFER, 0)
    }

    fun flushAndWait() {
        engine?.flushAndWait()
    }

    fun updateMaterial(asset: LoadedAsset, metallic: Float, roughness: Float,
                       baseColorR: Float, baseColorG: Float, baseColorB: Float, baseColorA: Float) {
        val engine = engine ?: return
        val rm = engine.renderableManager

        for (entity in asset.asset.entities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            val count = rm.getPrimitiveCount(ri)
            for (i in 0 until count) {
                val mi = rm.getMaterialInstanceAt(ri, i)
                try {
                    mi.setParameter("metallicFactor", metallic)
                } catch (_: Exception) {}
                try {
                    mi.setParameter("roughnessFactor", roughness)
                } catch (_: Exception) {}
                try {
                    mi.setParameter("baseColorFactor", baseColorR, baseColorG, baseColorB, baseColorA)
                } catch (_: Exception) {}
            }
        }
    }

    fun setExposure(ev: Float) {
        // Camera.setExposure(ev: Float) sets exposure as EV100
        // Default EV100 ~15 (f/16, 1/125s, ISO 100). Lower = brighter.
        // User ev is an offset: positive = brighter (lower EV100)
        val targetEv = 15f - ev
        for (camera in cameras) {
            camera?.setExposure(targetEv)
        }
    }

    fun setTransform(asset: LoadedAsset, matrix: FloatArray) {
        val engine = engine ?: return
        val tm = engine.transformManager
        val inst = tm.getInstance(asset.rootEntity)
        if (inst != 0) {
            tm.setTransform(inst, matrix)
        }
    }

    fun removeAsset(asset: LoadedAsset) {
        val scene = scene ?: return
        scene.removeEntities(asset.asset.entities)
        assetLoader?.destroyAsset(asset.asset)
        assets.remove(asset)
    }

    fun destroy() {
        val engine = engine ?: return

        for (asset in assets.toList()) {
            removeAsset(asset)
        }

        for (eye in 0..1) {
            renderTargets[eye]?.let { engine.destroyRenderTarget(it) }
            offscreenColorTextures[eye]?.let { engine.destroyTexture(it) }
            offscreenDepthTextures[eye]?.let { engine.destroyTexture(it) }
        }

        if (sunEntity != 0) {
            engine.destroyEntity(sunEntity)
            EntityManager.get().destroy(sunEntity)
        }

        resourceLoader?.destroy()
        assetLoader?.destroy()
        materialProvider?.destroyMaterials()
        (materialProvider as? UbershaderProvider)?.destroy()

        for (eye in 0..1) {
            views[eye]?.let { engine.destroyView(it) }
            cameras[eye]?.let {
                val e = it.entity
                engine.destroyCameraComponent(e)
                EntityManager.get().destroy(e)
            }
        }

        swapChain?.let { engine.destroySwapChain(it) }
        renderer?.let { engine.destroyRenderer(it) }
        scene?.let { engine.destroyScene(it) }

        if (ownGlTexIds[0] != 0) android.opengl.GLES30.glDeleteTextures(2, ownGlTexIds, 0)
        if (ownGlDepthRBs[0] != 0) android.opengl.GLES30.glDeleteRenderbuffers(2, ownGlDepthRBs, 0)
        if (blitFboSrc[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, blitFboSrc, 0)
        if (blitFboDst[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, blitFboDst, 0)

        engine.destroy()
        this.engine = null

        Log.i(TAG, "Filament renderer destroyed")
    }

    // 4x4 matrix inverse (column-major float array)
    private fun invertMatrix4(m: FloatArray): FloatArray {
        val inv = FloatArray(16)
        val det: Float

        inv[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] +
                 m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10]
        inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] -
                  m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10]
        inv[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] +
                 m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9]
        inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] -
                   m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9]
        inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] -
                  m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10]
        inv[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] +
                 m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10]
        inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] -
                  m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9]
        inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] +
                  m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9]
        inv[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] +
                 m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6]
        inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] -
                  m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6]
        inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] +
                  m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5]
        inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] -
                   m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5]
        inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] -
                  m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6]
        inv[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] +
                 m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6]
        inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] -
                   m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5]
        inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] +
                  m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5]

        det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12]
        if (det == 0f) return FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        val invDet = 1f / det
        for (i in 0..15) inv[i] *= invDet
        return inv
    }
}
