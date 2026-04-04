package com.ashairfoil.prism.data

import android.content.Context
import android.util.Log
import com.ashairfoil.prism.FileNameParser
import com.ashairfoil.prism.ScreenType
import com.ashairfoil.prism.StereoMode
import com.ashairfoil.prism.VideoMetadata
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaLibrary — Indexed media collection with metadata, tags, resume positions,
 * favorites, play counts, and search.
 *
 * Builds on top of FilePicker's scanning but adds a persistent index layer.
 * Stores per-file metadata in SharedPreferences (lightweight, no Room dependency).
 *
 * Features matching DeoVR/HereSphere:
 * - Resume position per file
 * - Play count tracking
 * - Favorite files
 * - Last played timestamp
 * - User tags per file
 * - Sort by: name, date modified, size, last played, play count, rating
 * - Filter by: projection, stereo mode, favorites, tags, has subtitles
 * - Search across filename, path, and tags
 */
class MediaLibrary(private val context: Context) {

    companion object {
        private const val TAG = "MediaLibrary"
        private const val PREFS_NAME = "chloe_media_library"
        private const val KEY_PREFIX_RESUME = "resume_"
        private const val KEY_PREFIX_PLAYCOUNT = "playcount_"
        private const val KEY_PREFIX_LASTPLAYED = "lastplayed_"
        private const val KEY_PREFIX_FAVORITE = "fav_"
        private const val KEY_PREFIX_TAGS = "tags_"
        private const val KEY_PREFIX_RATING = "rating_"
        private const val KEY_RECENT_FILES = "recent_files"
        private const val MAX_RECENT = 50
    }

    data class MediaEntry(
        val file: File,
        val fileId: String,              // Stable ID (hash of path)
        val metadata: VideoMetadata,
        val sizeBytes: Long,
        val lastModified: Long,
        // Persisted data (loaded from prefs)
        var resumePositionMs: Long = 0,
        var playCount: Int = 0,
        var lastPlayedMs: Long = 0,
        var isFavorite: Boolean = false,
        var tags: Set<String> = emptySet(),
        var rating: Int = 0,             // 0-5 stars
        var hasSubtitles: Boolean = false
    ) {
        val displayName: String get() = file.nameWithoutExtension
        val folderName: String get() = file.parentFile?.name ?: ""
        val sizeFormatted: String get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }
        val durationFormatted: String get() {
            // Would need ExoPlayer to get actual duration; placeholder
            return ""
        }
    }

    enum class SortBy {
        NAME, DATE_MODIFIED, SIZE, LAST_PLAYED, PLAY_COUNT, RATING
    }

    enum class FilterBy {
        ALL, FAVORITES, VR180, VR360, FISHEYE, FLAT, SBS, TOP_BOTTOM, MONO, HAS_SUBTITLES, UNPLAYED
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var entries: MutableList<MediaEntry> = java.util.concurrent.CopyOnWriteArrayList()
    private val entryIndex = ConcurrentHashMap<String, MediaEntry>()
    private val pathIndex = ConcurrentHashMap<String, MediaEntry>()

    /**
     * Build media entries from a list of files (from FilePicker scan).
     * Loads persisted metadata for each file.
     */
    fun buildFromFiles(files: List<File>): List<MediaEntry> {
        val built = files.map { file ->
            val fileId = fileIdFor(file)
            val meta = FileNameParser.parse(file)
            val entry = MediaEntry(
                file = file,
                fileId = fileId,
                metadata = meta,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
            )
            // Load persisted data
            entry.resumePositionMs = prefs.getLong("$KEY_PREFIX_RESUME$fileId", 0)
            entry.playCount = prefs.getInt("$KEY_PREFIX_PLAYCOUNT$fileId", 0)
            entry.lastPlayedMs = prefs.getLong("$KEY_PREFIX_LASTPLAYED$fileId", 0)
            entry.isFavorite = prefs.getBoolean("$KEY_PREFIX_FAVORITE$fileId", false)
            entry.tags = prefs.getStringSet("$KEY_PREFIX_TAGS$fileId", emptySet()) ?: emptySet()
            entry.rating = prefs.getInt("$KEY_PREFIX_RATING$fileId", 0)
            // Check for subtitle files
            entry.hasSubtitles = checkSubtitles(file)
            entry
        }
        // Atomic swap: build full list, then replace reference in one operation
        entries = java.util.concurrent.CopyOnWriteArrayList(built)
        entryIndex.clear()
        pathIndex.clear()
        entries.forEach {
            entryIndex[it.fileId] = it
            pathIndex[it.file.absolutePath] = it
        }

        Log.i(TAG, "Built library: ${entries.size} entries, " +
                "${entries.count { it.isFavorite }} favorites, " +
                "${entries.count { it.playCount > 0 }} played")
        return entries
    }

    // -----------------------------------------------------------------------
    // Persistence — per-file metadata
    // -----------------------------------------------------------------------

    fun saveResumePosition(file: File, positionMs: Long) {
        val id = fileIdFor(file)
        prefs.edit().putLong("$KEY_PREFIX_RESUME$id", positionMs).apply()
        entryIndex[id]?.resumePositionMs = positionMs
    }

    fun getResumePosition(file: File): Long {
        return prefs.getLong("$KEY_PREFIX_RESUME${fileIdFor(file)}", 0)
    }

    fun recordPlayback(file: File) {
        val id = fileIdFor(file)
        val entry = entryIndex[id]
        val newCount = (entry?.playCount ?: 0) + 1
        val now = System.currentTimeMillis()
        prefs.edit()
            .putInt("$KEY_PREFIX_PLAYCOUNT$id", newCount)
            .putLong("$KEY_PREFIX_LASTPLAYED$id", now)
            .apply()
        entry?.playCount = newCount
        entry?.lastPlayedMs = now
        addToRecent(file)
    }

    fun toggleFavorite(file: File): Boolean {
        val id = fileIdFor(file)
        val entry = entryIndex[id]
        val newVal = !(entry?.isFavorite ?: false)
        prefs.edit().putBoolean("$KEY_PREFIX_FAVORITE$id", newVal).apply()
        entry?.isFavorite = newVal
        return newVal
    }

    fun setRating(file: File, rating: Int) {
        val id = fileIdFor(file)
        val clamped = rating.coerceIn(0, 5)
        prefs.edit().putInt("$KEY_PREFIX_RATING$id", clamped).apply()
        entryIndex[id]?.rating = clamped
    }

    fun addTag(file: File, tag: String) {
        val id = fileIdFor(file)
        val entry = entryIndex[id]
        val current = entry?.tags?.toMutableSet() ?: mutableSetOf()
        current.add(tag.trim().lowercase())
        prefs.edit().putStringSet("$KEY_PREFIX_TAGS$id", current).apply()
        entry?.tags = current
    }

    fun removeTag(file: File, tag: String) {
        val id = fileIdFor(file)
        val entry = entryIndex[id]
        val current = entry?.tags?.toMutableSet() ?: return
        current.remove(tag.trim().lowercase())
        prefs.edit().putStringSet("$KEY_PREFIX_TAGS$id", current).apply()
        entry.tags = current
    }

    // -----------------------------------------------------------------------
    // Recent files
    // -----------------------------------------------------------------------

    private fun addToRecent(file: File) {
        val path = file.absolutePath
        val recent = getRecentPaths().toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > MAX_RECENT) recent.subList(MAX_RECENT, recent.size).clear()
        prefs.edit().putString(KEY_RECENT_FILES, JSONArray(recent).toString()).apply()
    }

    fun getRecentFiles(): List<MediaEntry> {
        val recentPaths = getRecentPaths()
        return recentPaths.mapNotNull { path -> pathIndex[path] }
    }

    private fun getRecentPaths(): List<String> {
        val json = prefs.getString(KEY_RECENT_FILES, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // -----------------------------------------------------------------------
    // Search, Sort, Filter
    // -----------------------------------------------------------------------

    fun search(query: String): List<MediaEntry> {
        if (query.isBlank()) return entries
        val q = query.trim().lowercase()
        return entries.filter { entry ->
            entry.displayName.lowercase().contains(q) ||
            entry.folderName.lowercase().contains(q) ||
            entry.tags.any { it.contains(q) } ||
            entry.metadata.screenType.displayName.lowercase().contains(q) ||
            entry.metadata.stereoMode.name.lowercase().contains(q)
        }
    }

    fun sort(list: List<MediaEntry>, by: SortBy, ascending: Boolean = true): List<MediaEntry> {
        val sorted = when (by) {
            SortBy.NAME -> list.sortedBy { it.displayName.lowercase() }
            SortBy.DATE_MODIFIED -> list.sortedBy { it.lastModified }
            SortBy.SIZE -> list.sortedBy { it.sizeBytes }
            SortBy.LAST_PLAYED -> list.sortedBy { it.lastPlayedMs }
            SortBy.PLAY_COUNT -> list.sortedBy { it.playCount }
            SortBy.RATING -> list.sortedBy { it.rating }
        }
        return if (ascending) sorted else sorted.reversed()
    }

    fun filter(list: List<MediaEntry>, by: FilterBy): List<MediaEntry> {
        return when (by) {
            FilterBy.ALL -> list
            FilterBy.FAVORITES -> list.filter { it.isFavorite }
            FilterBy.VR180 -> list.filter { it.metadata.screenType == ScreenType.DOME_180 }
            FilterBy.VR360 -> list.filter { it.metadata.screenType == ScreenType.SPHERE_360 }
            FilterBy.FISHEYE -> list.filter {
                it.metadata.screenType == ScreenType.FISHEYE ||
                it.metadata.screenType == ScreenType.MKX200 ||
                it.metadata.screenType == ScreenType.RF52 ||
                it.metadata.screenType == ScreenType.VRCA220
            }
            FilterBy.FLAT -> list.filter { it.metadata.screenType == ScreenType.FLAT }
            FilterBy.SBS -> list.filter { it.metadata.stereoMode == StereoMode.SIDE_BY_SIDE }
            FilterBy.TOP_BOTTOM -> list.filter { it.metadata.stereoMode == StereoMode.TOP_BOTTOM }
            FilterBy.MONO -> list.filter { it.metadata.stereoMode == StereoMode.MONO }
            FilterBy.HAS_SUBTITLES -> list.filter { it.hasSubtitles }
            FilterBy.UNPLAYED -> list.filter { it.playCount == 0 }
        }
    }

    /**
     * Combined search + filter + sort — the main query method.
     */
    fun query(
        searchQuery: String = "",
        filterBy: FilterBy = FilterBy.ALL,
        sortBy: SortBy = SortBy.NAME,
        ascending: Boolean = true
    ): List<MediaEntry> {
        var result = entries.toList()
        if (searchQuery.isNotBlank()) result = search(searchQuery)
        result = filter(result, filterBy)
        result = sort(result, sortBy, ascending)
        return result
    }

    // -----------------------------------------------------------------------
    // All unique tags across the library
    // -----------------------------------------------------------------------

    fun allTags(): Set<String> {
        return entries.flatMap { it.tags }.toSet()
    }

    fun allFolders(): List<String> {
        return entries.map { it.folderName }.distinct().sorted()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private val HEX = "0123456789abcdef".toCharArray()
    private val sha256 = MessageDigest.getInstance("SHA-256")

    private fun bytesToHex(bytes: ByteArray, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val b = bytes[i].toInt()
            sb.append(HEX[b shr 4 and 0xf])
            sb.append(HEX[b and 0xf])
        }
        return sb.toString()
    }

    private fun fileIdFor(file: File): String = synchronized(sha256) {
        // Use SHA-256 of absolute path for stable ID (survives renames of parent dirs)
        sha256.reset()
        val hash = sha256.digest(file.absolutePath.toByteArray())
        bytesToHex(hash, 8) // 8 bytes = 16 hex chars
    }

    private fun checkSubtitles(videoFile: File): Boolean {
        val baseName = videoFile.nameWithoutExtension
        val dir = videoFile.parentFile ?: return false
        return listOf(".srt", ".SRT", ".ass", ".ASS", ".ssa", ".SSA").any {
            File(dir, "$baseName$it").exists()
        }
    }
}
