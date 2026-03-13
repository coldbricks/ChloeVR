package com.ashairfoil.prism

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object FilePicker {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val MODEL_EXTENSIONS = setOf("glb", "gltf")
    private val MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + IMAGE_EXTENSIONS + MODEL_EXTENSIONS

    fun isImageFile(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS
    fun isModelFile(file: File): Boolean = file.extension.lowercase() in MODEL_EXTENSIONS

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
