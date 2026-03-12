package com.ashairfoil.prism.playback

import java.io.File

/**
 * PlaylistManager — Manages ordered file navigation and shuffle/repeat modes.
 *
 * Features:
 * - Sequential navigation (next/previous)
 * - Shuffle mode with Fisher-Yates shuffle
 * - Repeat modes: off, repeat-one, repeat-all
 * - Filter integration (only navigate within filtered results)
 * - History stack for "go back" after shuffle
 */
class PlaylistManager {

    enum class RepeatMode { OFF, ONE, ALL }

    private var files: List<File> = emptyList()
    private var shuffledIndices: List<Int> = emptyList()
    private var currentIndex: Int = -1
    private var history: MutableList<Int> = mutableListOf()

    var shuffleEnabled: Boolean = false
        set(value) {
            field = value
            if (value) reshuffle()
        }

    var repeatMode: RepeatMode = RepeatMode.OFF

    val currentFile: File? get() = if (currentIndex in files.indices) files[currentIndex] else null
    val size: Int get() = files.size
    val isEmpty: Boolean get() = files.isEmpty()
    val hasNext: Boolean get() {
        if (files.isEmpty()) return false
        if (repeatMode == RepeatMode.ALL) return true
        val idx = if (shuffleEnabled) shuffledIndices.indexOf(currentIndex) else currentIndex
        return idx < files.size - 1
    }
    val hasPrevious: Boolean get() {
        if (files.isEmpty()) return false
        if (repeatMode == RepeatMode.ALL) return true
        if (history.isNotEmpty()) return true
        val idx = if (shuffleEnabled) shuffledIndices.indexOf(currentIndex) else currentIndex
        return idx > 0
    }

    /**
     * Set the file list. Optionally set the current file.
     */
    fun setFiles(fileList: List<File>, startFile: File? = null) {
        files = fileList
        currentIndex = if (startFile != null) files.indexOf(startFile).coerceAtLeast(0) else 0
        history.clear()
        if (shuffleEnabled) reshuffle()
    }

    /**
     * Go to a specific file by reference.
     */
    fun goTo(file: File): File? {
        val idx = files.indexOf(file)
        if (idx < 0) return null
        if (currentIndex >= 0) history.add(currentIndex)
        currentIndex = idx
        return files[currentIndex]
    }

    /**
     * Get next file in sequence (or shuffled order).
     */
    fun next(): File? {
        if (files.isEmpty()) return null

        if (repeatMode == RepeatMode.ONE) return currentFile

        if (currentIndex >= 0) history.add(currentIndex)

        if (shuffleEnabled) {
            val shufflePos = shuffledIndices.indexOf(currentIndex)
            val nextShufflePos = shufflePos + 1
            if (nextShufflePos >= shuffledIndices.size) {
                if (repeatMode == RepeatMode.ALL) {
                    reshuffle()
                    currentIndex = shuffledIndices.firstOrNull() ?: 0
                } else {
                    return null // End of shuffled list
                }
            } else {
                currentIndex = shuffledIndices[nextShufflePos]
            }
        } else {
            val nextIdx = currentIndex + 1
            if (nextIdx >= files.size) {
                if (repeatMode == RepeatMode.ALL) {
                    currentIndex = 0
                } else {
                    return null // End of list
                }
            } else {
                currentIndex = nextIdx
            }
        }

        return files.getOrNull(currentIndex)
    }

    /**
     * Get previous file. Uses history stack if available (for shuffle mode).
     */
    fun previous(): File? {
        if (files.isEmpty()) return null

        if (repeatMode == RepeatMode.ONE) return currentFile

        if (history.isNotEmpty()) {
            currentIndex = history.removeAt(history.size - 1)
            return files.getOrNull(currentIndex)
        }

        if (shuffleEnabled) {
            val shufflePos = shuffledIndices.indexOf(currentIndex)
            val prevShufflePos = shufflePos - 1
            if (prevShufflePos < 0) {
                if (repeatMode == RepeatMode.ALL) {
                    currentIndex = shuffledIndices.lastOrNull() ?: 0
                } else {
                    return null
                }
            } else {
                currentIndex = shuffledIndices[prevShufflePos]
            }
        } else {
            val prevIdx = currentIndex - 1
            if (prevIdx < 0) {
                if (repeatMode == RepeatMode.ALL) {
                    currentIndex = files.size - 1
                } else {
                    return null
                }
            } else {
                currentIndex = prevIdx
            }
        }

        return files.getOrNull(currentIndex)
    }

    /**
     * Peek at next file without advancing.
     */
    fun peekNext(): File? {
        if (files.isEmpty()) return null
        if (shuffleEnabled) {
            val pos = shuffledIndices.indexOf(currentIndex) + 1
            return if (pos < shuffledIndices.size) files[shuffledIndices[pos]]
            else if (repeatMode == RepeatMode.ALL) files[shuffledIndices.first()]
            else null
        }
        val next = currentIndex + 1
        return if (next < files.size) files[next]
        else if (repeatMode == RepeatMode.ALL) files[0]
        else null
    }

    /**
     * Get position string like "3 / 47"
     */
    fun positionString(): String {
        if (files.isEmpty()) return "0 / 0"
        return "${currentIndex + 1} / ${files.size}"
    }

    private fun reshuffle() {
        val indices = files.indices.toMutableList()
        // Fisher-Yates shuffle
        for (i in indices.size - 1 downTo 1) {
            val j = (0..i).random()
            indices[i] = indices[j].also { indices[j] = indices[i] }
        }
        // Move current to front so we don't replay it immediately
        if (currentIndex >= 0) {
            indices.remove(currentIndex)
            indices.add(0, currentIndex)
        }
        shuffledIndices = indices
    }

    /**
     * Cycle repeat mode: OFF → ALL → ONE → OFF
     */
    fun cycleRepeatMode(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }
}
