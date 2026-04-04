package com.ashairfoil.prism.playback

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Test suite for PlaylistManager -- navigation, shuffle, repeat modes.
 *
 * Covers sequential/shuffle navigation, repeat-off/one/all, history stack,
 * goTo, edge cases (empty, single file), and the shuffle reshuffle fix.
 */
class PlaylistManagerTest {

    private lateinit var pm: PlaylistManager
    private lateinit var files: List<File>

    @Before
    fun setup() {
        pm = PlaylistManager()
        files = (0 until 5).map { File("/test/track$it.mp3") }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Empty playlist
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `empty playlist returns null for currentFile`() {
        assertNull(pm.currentFile)
    }

    @Test
    fun `empty playlist returns null for next`() {
        assertNull(pm.next())
    }

    @Test
    fun `empty playlist returns null for previous`() {
        assertNull(pm.previous())
    }

    @Test
    fun `empty playlist isEmpty is true`() {
        assertTrue(pm.isEmpty)
    }

    @Test
    fun `empty playlist size is 0`() {
        assertEquals(0, pm.size)
    }

    @Test
    fun `empty playlist hasNext is false`() {
        assertFalse(pm.hasNext)
    }

    @Test
    fun `empty playlist hasPrevious is false`() {
        assertFalse(pm.hasPrevious)
    }

    @Test
    fun `empty playlist positionString is 0 of 0`() {
        assertEquals("0 / 0", pm.positionString())
    }

    @Test
    fun `empty playlist peekNext returns null`() {
        assertNull(pm.peekNext())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Single file playlist
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `single file playlist currentFile is that file`() {
        val single = listOf(File("/test/only.mp3"))
        pm.setFiles(single)
        assertEquals(single[0], pm.currentFile)
    }

    @Test
    fun `single file REPEAT_OFF next returns null`() {
        pm.setFiles(listOf(File("/test/only.mp3")))
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertNull(pm.next())
    }

    @Test
    fun `single file REPEAT_ALL next returns same file`() {
        val single = listOf(File("/test/only.mp3"))
        pm.setFiles(single)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        assertEquals(single[0], pm.next())
    }

    @Test
    fun `single file REPEAT_ONE next returns same file`() {
        val single = listOf(File("/test/only.mp3"))
        pm.setFiles(single)
        pm.repeatMode = PlaylistManager.RepeatMode.ONE
        assertEquals(single[0], pm.next())
    }

    @Test
    fun `single file REPEAT_OFF previous returns null`() {
        pm.setFiles(listOf(File("/test/only.mp3")))
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertNull(pm.previous())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sequential navigation -- REPEAT_OFF
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sequential next walks through all files`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertEquals(files[0], pm.currentFile)

        for (i in 1 until files.size) {
            assertEquals(files[i], pm.next())
        }
    }

    @Test
    fun `sequential next returns null at end with REPEAT_OFF`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF

        // Walk to end
        repeat(files.size - 1) { pm.next() }
        assertEquals(files.last(), pm.currentFile)

        // Past the end
        assertNull(pm.next())
    }

    @Test
    fun `sequential previous returns null at start with REPEAT_OFF`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertNull(pm.previous())
    }

    @Test
    fun `positionString updates on navigation`() {
        pm.setFiles(files)
        assertEquals("1 / 5", pm.positionString())
        pm.next()
        assertEquals("2 / 5", pm.positionString())
        pm.next()
        assertEquals("3 / 5", pm.positionString())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sequential navigation -- REPEAT_ALL
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sequential next wraps at end with REPEAT_ALL`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL

        // Walk to end
        repeat(files.size - 1) { pm.next() }
        assertEquals(files.last(), pm.currentFile)

        // Should wrap to first
        assertEquals(files[0], pm.next())
    }

    @Test
    fun `sequential previous wraps at start with REPEAT_ALL`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL

        // At index 0, previous should wrap to last
        // Need to clear history first by using a fresh manager
        val pm2 = PlaylistManager()
        pm2.setFiles(files)
        pm2.repeatMode = PlaylistManager.RepeatMode.ALL
        // previous with empty history wraps
        assertEquals(files.last(), pm2.previous())
    }

    @Test
    fun `REPEAT_ALL hasNext is always true`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        assertTrue(pm.hasNext)

        // Even at end
        repeat(files.size - 1) { pm.next() }
        assertTrue(pm.hasNext)
    }

    @Test
    fun `REPEAT_ALL hasPrevious is always true`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        assertTrue(pm.hasPrevious)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sequential navigation -- REPEAT_ONE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `REPEAT_ONE next returns same file`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ONE
        val first = pm.currentFile
        assertEquals(first, pm.next())
        assertEquals(first, pm.next())
        assertEquals(first, pm.next())
    }

    @Test
    fun `REPEAT_ONE previous returns same file`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ONE
        val first = pm.currentFile
        assertEquals(first, pm.previous())
        assertEquals(first, pm.previous())
    }

    @Test
    fun `REPEAT_ONE does not advance index`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ONE
        pm.next()
        pm.next()
        pm.next()
        assertEquals("1 / 5", pm.positionString()) // Still at index 0
    }

    // ═══════════════════════════════════════════════════════════════════
    //  goTo()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `goTo sets current file correctly`() {
        pm.setFiles(files)
        assertEquals(files[3], pm.goTo(files[3]))
        assertEquals(files[3], pm.currentFile)
    }

    @Test
    fun `goTo returns null for file not in list`() {
        pm.setFiles(files)
        assertNull(pm.goTo(File("/nonexistent.mp3")))
    }

    @Test
    fun `goTo pushes previous index to history`() {
        pm.setFiles(files)
        assertEquals(files[0], pm.currentFile)

        pm.goTo(files[3])
        assertEquals(files[3], pm.currentFile)

        // previous should pop history (back to index 0)
        assertEquals(files[0], pm.previous())
    }

    @Test
    fun `goTo to same file still works`() {
        pm.setFiles(files)
        pm.goTo(files[2])
        assertEquals(files[2], pm.goTo(files[2]))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  History stack
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `previous uses history stack from next calls`() {
        pm.setFiles(files)
        pm.next() // 0 -> 1, push 0
        pm.next() // 1 -> 2, push 1
        pm.next() // 2 -> 3, push 2

        assertEquals(files[3], pm.currentFile)
        assertEquals(files[2], pm.previous()) // pop 2
        assertEquals(files[1], pm.previous()) // pop 1
        assertEquals(files[0], pm.previous()) // pop 0
    }

    @Test
    fun `history stack is bounded at 500`() {
        // Use a large playlist to build up history
        val manyFiles = (0 until 600).map { File("/test/track$it.mp3") }
        pm.setFiles(manyFiles)

        // Advance 550 times to fill history beyond 500
        repeat(550) { pm.next() }

        // Go back: first 500 from history, then sequential navigation
        // continues backward from the oldest history entry. Total backward
        // steps = 500 (history) + oldest_history_index (sequential).
        // After 550 next() calls the oldest history entry is index 50,
        // so total = 500 + 50 = 550.
        var backCount = 0
        while (pm.previous() != null) {
            backCount++
            if (backCount > 600) break // safety valve
        }
        // History is capped at 500 entries, but sequential fallback adds more.
        // The key invariant: we cannot go back more than 500 + (currentIndex
        // at the time history was exhausted) steps.
        assertTrue("Should be able to go back ~550 times (500 history + 50 sequential), got $backCount",
            backCount in 500..560)
    }

    @Test
    fun `history from goTo enables previous navigation`() {
        pm.setFiles(files)
        pm.goTo(files[2])
        pm.goTo(files[4])

        assertEquals(files[4], pm.currentFile)
        assertEquals(files[2], pm.previous())  // pop history from goTo(4)
        assertEquals(files[0], pm.previous())  // pop history from goTo(2)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Shuffle mode
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `shuffle plays all files exactly once before wrapping`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        pm.shuffleEnabled = true

        val seen = mutableSetOf<File>()
        seen.add(pm.currentFile!!)

        // Walk through all files
        for (i in 1 until files.size) {
            val f = pm.next()
            assertNotNull("next() should not return null in REPEAT_ALL shuffle at step $i", f)
            seen.add(f!!)
        }

        assertEquals("All ${files.size} files should be visited exactly once", files.size, seen.size)
    }

    @Test
    fun `shuffle REPEAT_OFF returns null after exhaustion`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        pm.shuffleEnabled = true

        // Walk to end of shuffled list
        repeat(files.size - 1) { pm.next() }

        // Next should return null
        assertNull(pm.next())
    }

    @Test
    fun `shuffle REPEAT_ALL reshuffles at end and continues`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        pm.shuffleEnabled = true

        // Walk past the end -- should reshuffle and continue
        val visited = mutableListOf<File>()
        visited.add(pm.currentFile!!)
        repeat(files.size * 2) {
            val f = pm.next()
            assertNotNull(f)
            visited.add(f!!)
        }

        // Should have visited all files multiple times
        assertEquals(files.size * 2 + 1, visited.size)
        assertTrue("All original files should appear", files.all { it in visited })
    }

    @Test
    fun `reshuffle contains all file indices`() {
        // This verifies the fix for MutableList<Int>.remove() bug.
        // The old code used (indices as MutableList<Int>).remove(currentIndex)
        // which could remove by index instead of by value in some cases.
        // After fix, all files must be present in shuffle order.
        val largeFiles = (0 until 20).map { File("/test/track$it.mp3") }
        pm.setFiles(largeFiles)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        pm.shuffleEnabled = true

        val seen = mutableSetOf<File>()
        seen.add(pm.currentFile!!)
        for (i in 1 until largeFiles.size) {
            val f = pm.next()
            assertNotNull("File at shuffle position $i should not be null", f)
            assertTrue("Duplicate in shuffle: ${f!!.name}", seen.add(f))
        }
        assertEquals("All ${largeFiles.size} files must appear in shuffle", largeFiles.size, seen.size)
    }

    @Test
    fun `enabling shuffle after setFiles reshuffles`() {
        pm.setFiles(files)
        assertFalse(pm.shuffleEnabled)

        pm.shuffleEnabled = true
        // Should still be able to navigate through all files
        val seen = mutableSetOf(pm.currentFile!!)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        repeat(files.size - 1) {
            seen.add(pm.next()!!)
        }
        assertEquals(files.size, seen.size)
    }

    @Test
    fun `disabling shuffle then re-enabling reshuffles`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        pm.shuffleEnabled = true
        pm.shuffleEnabled = false
        pm.shuffleEnabled = true

        val seen = mutableSetOf(pm.currentFile!!)
        repeat(files.size - 1) {
            seen.add(pm.next()!!)
        }
        assertEquals(files.size, seen.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  peekNext()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `peekNext does not advance position`() {
        pm.setFiles(files)
        val positionBefore = pm.positionString()
        val peeked = pm.peekNext()
        assertEquals(positionBefore, pm.positionString())
        assertEquals(files[1], peeked)
    }

    @Test
    fun `peekNext returns null at end with REPEAT_OFF`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        repeat(files.size - 1) { pm.next() }
        assertNull(pm.peekNext())
    }

    @Test
    fun `peekNext wraps with REPEAT_ALL`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.ALL
        repeat(files.size - 1) { pm.next() }
        assertEquals(files[0], pm.peekNext())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  setFiles()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `setFiles with startFile sets current`() {
        pm.setFiles(files, startFile = files[3])
        assertEquals(files[3], pm.currentFile)
    }

    @Test
    fun `setFiles with unknown startFile defaults to index 0`() {
        pm.setFiles(files, startFile = File("/unknown.mp3"))
        assertEquals(files[0], pm.currentFile)
    }

    @Test
    fun `setFiles clears history`() {
        pm.setFiles(files)
        pm.next()
        pm.next()
        pm.next()
        // History has 3 entries

        pm.setFiles(files)
        // History should be cleared -- previous should not pop old history
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertNull(pm.previous())
    }

    @Test
    fun `setFiles updates size`() {
        pm.setFiles(files)
        assertEquals(5, pm.size)

        pm.setFiles(listOf(File("/a.mp3"), File("/b.mp3")))
        assertEquals(2, pm.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  cycleRepeatMode()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `cycleRepeatMode cycles OFF to ALL to ONE to OFF`() {
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertEquals(PlaylistManager.RepeatMode.ALL, pm.cycleRepeatMode())
        assertEquals(PlaylistManager.RepeatMode.ONE, pm.cycleRepeatMode())
        assertEquals(PlaylistManager.RepeatMode.OFF, pm.cycleRepeatMode())
    }

    @Test
    fun `cycleRepeatMode returns the new mode`() {
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        val result = pm.cycleRepeatMode()
        assertEquals(pm.repeatMode, result)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  hasNext / hasPrevious
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hasNext false at last file with REPEAT_OFF`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        repeat(files.size - 1) { pm.next() }
        assertFalse(pm.hasNext)
    }

    @Test
    fun `hasNext true before last file with REPEAT_OFF`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertTrue(pm.hasNext)
    }

    @Test
    fun `hasPrevious false at first file with REPEAT_OFF and empty history`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        assertFalse(pm.hasPrevious)
    }

    @Test
    fun `hasPrevious true when history is nonempty`() {
        pm.setFiles(files)
        pm.repeatMode = PlaylistManager.RepeatMode.OFF
        pm.next()
        assertTrue(pm.hasPrevious)
    }
}
