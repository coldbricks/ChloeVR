package com.ashairfoil.prism.scene

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages persistent spatial anchors that pin 3D models to real-world positions across
 * app sessions. Backed by the OpenXR `XR_EXT_spatial_entity` family
 * (`XR_EXT_spatial_entity` + `XR_EXT_spatial_anchor` + `XR_EXT_spatial_persistence`
 * + `XR_EXT_spatial_persistence_operations` + `XR_EXT_future`).
 *
 * Architecture:
 *  - This class is a thin wrapper around the C++ [XrSpatialAnchorManager] accessed via JNI.
 *    Native side owns all XR handles; we push submit calls and drain completed futures.
 *  - [anchors.json] in filesDir stores the mapping `anchorUuid → { modelPath, local pose
 *    offset, material params }`. On launch we read it, submit resolve for every UUID,
 *    and on each [poll] update the SceneManager with any freshly resolved poses.
 *  - [use_spatial_anchors] setting gates ALL calls — when false, fall back to
 *    session-local poses (existing scene save/load). The C++ layer still initializes so
 *    we can surface diagnostics, but [submitCreate] and [submitResolve] will be bypassed.
 *
 * Thread-safety: all native calls are guarded internally by a mutex held by the renderer.
 * This Kotlin side must be called from either the render thread OR the main thread — not
 * both concurrently. In practice: [poll] on render thread, enqueue* on main thread.
 */
class SpatialAnchorManager(private val context: Context) {

    companion object {
        private const val TAG = "ChloeVR-Anchors"
        private const val ANCHORS_FILE = "anchors.json"
        private const val SCHEMA_VERSION = 1
        // Matches XR_UUID_SIZE (16 bytes).
        const val UUID_SIZE = 16

        // Nativestatus()  return values.
        const val STATUS_UNSUPPORTED = 0
        const val STATUS_NOT_READY = 1
        const val STATUS_READY = 2
    }

    /** An on-disk record persisted alongside the anchor UUID. */
    data class AnchorRecord(
        val uuid: ByteArray,          // 16 bytes
        val modelPath: String,        // absolute file path
        val offsetPosX: Float = 0f,   // local offset from anchor origin to model pivot
        val offsetPosY: Float = 0f,
        val offsetPosZ: Float = 0f,
        val offsetRotX: Float = 0f,
        val offsetRotY: Float = 0f,
        val offsetRotZ: Float = 0f,
        val offsetRotW: Float = 1f,
        val scale: Float = 1f,
        val metallic: Float = 0f,
        val roughness: Float = 0.9f,
        val exposure: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f
    )

    /** Emitted to the activity when [poll] surfaces a resolved anchor. */
    data class ResolveEvent(
        val uuid: ByteArray,
        val valid: Boolean,
        val posX: Float, val posY: Float, val posZ: Float,
        val rotX: Float, val rotY: Float, val rotZ: Float, val rotW: Float,
        val record: AnchorRecord?
    )

    /** Emitted to the activity when a create+persist operation completes. */
    data class CreateEvent(
        val clientHandle: Int,
        val success: Boolean,
        val uuid: ByteArray
    )

    // ── Persistent state ──
    private val records: MutableList<AnchorRecord> = mutableListOf()
    private val resolvePending: HashSet<String> = hashSetOf() // uuidHex strings awaiting resolution

    // Pre-allocated scratch for JNI to avoid per-call alloc on the render thread.
    private val scratchPoseIn = FloatArray(7)
    private val scratchCreateHandleFlag = IntArray(2)
    private val scratchCreateUuid = ByteArray(UUID_SIZE)
    private val scratchResolveUuid = ByteArray(UUID_SIZE)
    private val scratchResolvePose = FloatArray(8)

    init {
        loadFromDisk()
    }

    fun status(): Int = try { nativeStatus() } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "Native anchor lib missing: ${e.message}")
        STATUS_UNSUPPORTED
    }

    fun isSupported(): Boolean = status() != STATUS_UNSUPPORTED
    fun isReady(): Boolean = status() == STATUS_READY

    /** Return all persisted records (read-only snapshot). */
    fun allRecords(): List<AnchorRecord> = records.toList()

    /** Find a record by UUID, or null. */
    fun findRecord(uuid: ByteArray): AnchorRecord? =
        records.firstOrNull { it.uuid.contentEquals(uuid) }

    /** Return the anchor UUID associated with a given model file path (if any). */
    fun uuidForPath(path: String): ByteArray? =
        records.firstOrNull { it.modelPath == path }?.uuid

    // ── Creation ─────────────────────────────────────────────────────────

    /**
     * Submit an anchor at the given world pose (in appSpace). Returns a clientHandle (>0)
     * the caller should store; on completion, [pollCreate] will surface the result with the
     * same handle + the assigned UUID. Returns -1 if the manager isn't ready yet.
     */
    fun submitCreate(
        posX: Float, posY: Float, posZ: Float,
        rotX: Float, rotY: Float, rotZ: Float, rotW: Float
    ): Int {
        if (!isReady()) return -1
        scratchPoseIn[0] = posX; scratchPoseIn[1] = posY; scratchPoseIn[2] = posZ
        scratchPoseIn[3] = rotX; scratchPoseIn[4] = rotY; scratchPoseIn[5] = rotZ; scratchPoseIn[6] = rotW
        return nativeSubmitCreate(scratchPoseIn)
    }

    /** Pop a completed create event, or null. */
    fun pollCreate(): CreateEvent? {
        if (!isSupported()) return null
        if (!nativePollCreateResult(scratchCreateHandleFlag, scratchCreateUuid)) return null
        val uuid = scratchCreateUuid.copyOf()
        val ev = CreateEvent(scratchCreateHandleFlag[0], scratchCreateHandleFlag[1] == 1, uuid)
        Log.i(TAG, "Create complete handle=${ev.clientHandle} success=${ev.success} uuid=${uuidHex(uuid)}")
        return ev
    }

    /** Persist a record to anchors.json (replacing any existing entry with same UUID). */
    fun upsertRecord(record: AnchorRecord) {
        val idx = records.indexOfFirst { it.uuid.contentEquals(record.uuid) }
        if (idx >= 0) records[idx] = record else records.add(record)
        saveToDisk()
    }

    // ── Resolution on launch ─────────────────────────────────────────────

    /**
     * Submit a batch resolve for every record currently on disk. Returns the count submitted.
     * Call once, shortly after [isReady] becomes true.
     */
    fun submitResolveAll(): Int {
        if (!isReady() || records.isEmpty()) return 0
        val count = records.size
        val flat = ByteArray(count * UUID_SIZE)
        records.forEachIndexed { i, r ->
            System.arraycopy(r.uuid, 0, flat, i * UUID_SIZE, UUID_SIZE)
            resolvePending.add(uuidHex(r.uuid))
        }
        nativeSubmitResolve(flat, count)
        Log.i(TAG, "Submitted resolve for $count persisted anchors")
        return count
    }

    /** Pop one resolve event, or null. Call this every frame. */
    fun pollResolve(): ResolveEvent? {
        if (!isSupported()) return null
        if (!nativePollResolveResult(scratchResolveUuid, scratchResolvePose)) return null
        val uuid = scratchResolveUuid.copyOf()
        val valid = scratchResolvePose[0] > 0.5f
        val rec = findRecord(uuid)
        resolvePending.remove(uuidHex(uuid))
        return ResolveEvent(
            uuid = uuid,
            valid = valid,
            posX = scratchResolvePose[1], posY = scratchResolvePose[2], posZ = scratchResolvePose[3],
            rotX = scratchResolvePose[4], rotY = scratchResolvePose[5], rotZ = scratchResolvePose[6],
            rotW = scratchResolvePose[7],
            record = rec
        )
    }

    fun hasPendingResolutions(): Boolean = resolvePending.isNotEmpty()

    // ── Deletion ─────────────────────────────────────────────────────────

    fun deleteRecord(uuid: ByteArray) {
        if (isSupported()) nativeUnpersist(uuid)
        records.removeAll { it.uuid.contentEquals(uuid) }
        saveToDisk()
    }

    /** Nuke all anchor records (both local and runtime-persisted). */
    fun clearAll() {
        if (isSupported()) {
            for (r in records) nativeUnpersist(r.uuid)
        }
        records.clear()
        saveToDisk()
    }

    // ── Disk I/O ────────────────────────────────────────────────────────

    private fun anchorsFile(): File = File(context.filesDir, ANCHORS_FILE)

    private fun loadFromDisk() {
        val f = anchorsFile()
        if (!f.exists()) return
        try {
            val json = JSONObject(f.readText())
            val version = json.optInt("version", 1)
            if (version > SCHEMA_VERSION) {
                Log.w(TAG, "anchors.json version $version newer than supported ($SCHEMA_VERSION); ignoring")
                return
            }
            val arr = json.optJSONArray("anchors") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uuidHex = obj.optString("uuid", "")
                val bytes = hexToUuid(uuidHex) ?: continue
                val path = obj.optString("path", "")
                if (path.isBlank()) continue
                records.add(AnchorRecord(
                    uuid = bytes,
                    modelPath = path,
                    offsetPosX = obj.optDouble("offsetPosX", 0.0).toFloat(),
                    offsetPosY = obj.optDouble("offsetPosY", 0.0).toFloat(),
                    offsetPosZ = obj.optDouble("offsetPosZ", 0.0).toFloat(),
                    offsetRotX = obj.optDouble("offsetRotX", 0.0).toFloat(),
                    offsetRotY = obj.optDouble("offsetRotY", 0.0).toFloat(),
                    offsetRotZ = obj.optDouble("offsetRotZ", 0.0).toFloat(),
                    offsetRotW = obj.optDouble("offsetRotW", 1.0).toFloat(),
                    scale = obj.optDouble("scale", 1.0).toFloat(),
                    metallic = obj.optDouble("metallic", 0.0).toFloat(),
                    roughness = obj.optDouble("roughness", 0.9).toFloat(),
                    exposure = obj.optDouble("exposure", 0.0).toFloat(),
                    contrast = obj.optDouble("contrast", 1.0).toFloat(),
                    saturation = obj.optDouble("saturation", 1.0).toFloat()
                ))
            }
            Log.i(TAG, "Loaded ${records.size} anchor records from disk")
        } catch (e: Exception) {
            Log.e(TAG, "anchors.json read failed: ${e.message}", e)
        }
    }

    private fun saveToDisk() {
        try {
            val json = JSONObject()
            json.put("version", SCHEMA_VERSION)
            val arr = JSONArray()
            for (r in records) {
                val obj = JSONObject()
                obj.put("uuid", uuidHex(r.uuid))
                obj.put("path", r.modelPath)
                obj.put("offsetPosX", r.offsetPosX.toDouble())
                obj.put("offsetPosY", r.offsetPosY.toDouble())
                obj.put("offsetPosZ", r.offsetPosZ.toDouble())
                obj.put("offsetRotX", r.offsetRotX.toDouble())
                obj.put("offsetRotY", r.offsetRotY.toDouble())
                obj.put("offsetRotZ", r.offsetRotZ.toDouble())
                obj.put("offsetRotW", r.offsetRotW.toDouble())
                obj.put("scale", r.scale.toDouble())
                obj.put("metallic", r.metallic.toDouble())
                obj.put("roughness", r.roughness.toDouble())
                obj.put("exposure", r.exposure.toDouble())
                obj.put("contrast", r.contrast.toDouble())
                obj.put("saturation", r.saturation.toDouble())
                arr.put(obj)
            }
            json.put("anchors", arr)
            anchorsFile().writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "anchors.json write failed: ${e.message}", e)
        }
    }

    // ── Hex helpers for JSON transport ─────────────────────────────────

    private fun uuidHex(bytes: ByteArray): String {
        val sb = StringBuilder(UUID_SIZE * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    private fun hexToUuid(hex: String): ByteArray? {
        if (hex.length != UUID_SIZE * 2) return null
        val out = ByteArray(UUID_SIZE)
        for (i in 0 until UUID_SIZE) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // ── JNI (private external fun with public wrapper per CLAUDE.md rule #8) ──
    private external fun nativeStatus(): Int
    private external fun nativeSubmitCreate(posRot: FloatArray): Int
    private external fun nativePollCreateResult(outHandleFlag: IntArray, outUuid: ByteArray): Boolean
    private external fun nativeSubmitResolve(uuidsFlat: ByteArray, count: Int): Unit
    private external fun nativePollResolveResult(outUuid: ByteArray, outPoseFlag: FloatArray): Boolean
    private external fun nativeUnpersist(uuid: ByteArray): Unit
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
