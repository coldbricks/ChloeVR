package com.ashairfoil.prism

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object FilePicker {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")

    fun listVideoFiles(context: Context): List<File> {
        val roots = mutableListOf<File>()

        // Internal storage
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }

        // USB OTG and other mounted volumes
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in storageManager.storageVolumes) {
            volume.directory?.let { roots.add(it) }
        }

        val results = mutableListOf<File>()
        for (root in roots.distinct()) {
            scanDirectory(root, results, depth = 0, maxDepth = 6)
        }
        return results.sortedBy { it.name.lowercase() }
    }

    private fun scanDirectory(dir: File, results: MutableList<File>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || !dir.isDirectory || !dir.canRead()) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Skip hidden dirs and Android system dirs
                if (!file.name.startsWith(".") && file.name != "Android") {
                    scanDirectory(file, results, depth + 1, maxDepth)
                }
            } else if (file.extension.lowercase() in VIDEO_EXTENSIONS && file.length() > 0) {
                results.add(file)
            }
        }
    }
}
