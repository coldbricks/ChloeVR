package com.ashairfoil.prism

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object FilePicker {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val MODEL_EXTENSIONS = setOf("glb", "gltf")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "aac", "opus", "m4a", "wma")
    private val MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + IMAGE_EXTENSIONS + MODEL_EXTENSIONS + AUDIO_EXTENSIONS

    fun isImageFile(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS
    fun isModelFile(file: File): Boolean = file.extension.lowercase() in MODEL_EXTENSIONS
    fun isAudioFile(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

    /**
     * Rigged-GLB detection. Fast paths first: a `RIGGED` folder anywhere in
     * the path or a `RIGGED_` / `_rigged` name marker (DeoVR-style). Falls
     * back to CONTENT: a real `"skins"` + `"joints"` definition in the GLB's
     * JSON chunk — required since the rename feature gave the dancers clean
     * names ("Chelsea.glb") and the whole rigged library vanished from the
     * filter. Content verdicts are cached per (path, lastModified); the
     * background GLB scan pre-warms the cache so the picker never pays the
     * read on the render thread.
     */
    private val riggedSniffCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun isRiggedGlb(file: File): Boolean {
        if (!isModelFile(file)) return false
        val path = file.absolutePath.replace('\\', '/').lowercase()
        if ("/rigged/" in path) return true
        val name = file.nameWithoutExtension.lowercase()
        if (name.startsWith("rigged_") || name.endsWith("_rigged") ||
            name.contains("+rigged") || name.contains(" rigged")) return true

        val mod = file.lastModified()
        riggedSniffCache[file.absolutePath]?.let { (m, r) -> if (m == mod) return r }
        val rigged = try {
            file.inputStream().use { ins ->
                // GLB layout: 12-byte header, then the JSON chunk. 256KB covers
                // the structural keys of every Tripo/Mixamo rig we ship; the
                // folder/name conventions above remain the override for outliers.
                val buf = ByteArray(262144)
                var off = 0
                while (off < buf.size) {
                    val n = ins.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                val text = String(buf, 0, off, Charsets.ISO_8859_1)
                text.contains("\"skins\"") && text.contains("\"joints\"")
            }
        } catch (e: Exception) {
            false
        }
        riggedSniffCache[file.absolutePath] = mod to rigged
        return rigged
    }

    fun listVideoFiles(context: Context): List<File> {
        return listVideoFilesProgressive(context) { _, _, _ -> }
    }

    fun listVideoFilesProgressive(
        context: Context,
        onProgress: (files: List<File>, scannedRoots: Int, totalRoots: Int) -> Unit
    ): List<File> {
        val roots = listRoots(context)
        if (roots.isEmpty()) {
            onProgress(emptyList(), 0, 0)
            return emptyList()
        }

        val results = mutableListOf<File>()
        val seenPaths = mutableSetOf<String>()

        for ((index, root) in roots.withIndex()) {
            scanDirectory(root, results, seenPaths, depth = 0, maxDepth = 6)
            onProgress(
                results.sortedBy { it.name.lowercase() },
                index + 1,
                roots.size
            )
        }
        return results.sortedBy { it.name.lowercase() }
    }

    private fun listRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()

        Environment.getExternalStorageDirectory()?.let { roots.add(it) }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in storageManager.storageVolumes) {
            volume.directory?.let { roots.add(it) }
        }

        return roots.distinctBy { it.absolutePath }
    }

    private fun scanDirectory(
        dir: File,
        results: MutableList<File>,
        seenPaths: MutableSet<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth || !dir.isDirectory || !dir.canRead()) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Skip hidden dirs and Android system dirs
                if (!file.name.startsWith(".") && file.name != "Android") {
                    scanDirectory(file, results, seenPaths, depth + 1, maxDepth)
                }
            } else if (file.extension.lowercase() in MEDIA_EXTENSIONS && file.length() > 0) {
                if (seenPaths.add(file.absolutePath)) {
                    results.add(file)
                }
            }
        }
    }
}
