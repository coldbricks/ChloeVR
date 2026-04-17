package com.ashairfoil.prism.playback

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * FontManager — resolves `{\fn<name>}` requests into cached Typeface instances.
 *
 * Fallback chain:
 *   1. Named typeface already cached (from bundled asset or previous system lookup)
 *   2. Bundled asset in `assets/fonts/<name>.ttf` or `.otf`
 *   3. Android system font lookup via `Typeface.create(familyName, style)`
 *   4. A system scan of `/system/fonts` for case-insensitive filename match
 *   5. `Typeface.DEFAULT` with the requested style
 *
 * Typefaces are cached by (name, styleBits) to avoid allocating new Typeface objects
 * during render — ASS override tags that toggle bold/italic mid-line otherwise force
 * a fresh create() call every frame, which is GC-toxic in the render loop.
 */
class FontManager(context: Context?) {

    companion object {
        private const val TAG = "FontManager"
        private const val ASSET_DIR = "fonts"
        private const val SYSTEM_FONT_DIR = "/system/fonts"
        private val FONT_EXTENSIONS = listOf(".ttf", ".otf")
    }

    private val appContext = context?.applicationContext
    private val cache = HashMap<Long, Typeface>(32)
    private val missingFonts = HashSet<String>(8)
    private var assetIndex: Map<String, String>? = null
    private var systemIndex: Map<String, File>? = null

    /**
     * Return a Typeface matching [fontName] and style flags. Never returns null —
     * falls back all the way down to `Typeface.DEFAULT` if nothing matches.
     * Safe to call from the render thread; all allocations happen on first miss only.
     */
    fun resolve(fontName: String?, isBold: Boolean, isItalic: Boolean): Typeface {
        val name = if (fontName.isNullOrBlank()) "" else fontName.trim()
        val styleBits = styleBits(isBold, isItalic)
        val key = cacheKey(name, styleBits)
        cache[key]?.let { return it }

        val base = lookupBase(name)
        val styled = applyStyle(base, styleBits)
        cache[key] = styled
        return styled
    }

    /** Clear all cached typefaces. Called on renderer teardown. */
    fun clear() {
        cache.clear()
        missingFonts.clear()
    }

    // -----------------------------------------------------------------------
    // Lookup pipeline
    // -----------------------------------------------------------------------

    private fun lookupBase(name: String): Typeface {
        if (name.isEmpty()) return Typeface.DEFAULT

        loadFromAssets(name)?.let { return it }
        loadFromSystemFamily(name)?.let { return it }
        loadFromSystemScan(name)?.let { return it }

        if (name !in missingFonts) {
            missingFonts.add(name)
            Log.i(TAG, "Font not found, using default: $name")
        }
        return Typeface.DEFAULT
    }

    private fun applyStyle(base: Typeface, styleBits: Int): Typeface {
        if (base.style == styleBits) return base
        return try {
            Typeface.create(base, styleBits)
        } catch (e: Exception) {
            Log.w(TAG, "Typeface.create failed, using base: ${e.message}")
            base
        }
    }

    private fun loadFromAssets(name: String): Typeface? {
        val ctx = appContext ?: return null
        val index = assetIndex ?: indexAssets(ctx).also { assetIndex = it }
        val path = index[name.lowercase()] ?: return null
        return try {
            Typeface.createFromAsset(ctx.assets, path)
        } catch (e: Exception) {
            Log.w(TAG, "Asset font load failed for $name: ${e.message}")
            null
        }
    }

    private fun loadFromSystemFamily(name: String): Typeface? {
        return try {
            val tf = Typeface.create(name, Typeface.NORMAL)
            if (tf === Typeface.DEFAULT) null else tf
        } catch (e: Exception) {
            null
        }
    }

    private fun loadFromSystemScan(name: String): Typeface? {
        val index = systemIndex ?: indexSystemFonts().also { systemIndex = it }
        val file = index[name.lowercase()] ?: return null
        return try {
            Typeface.createFromFile(file)
        } catch (e: Exception) {
            Log.w(TAG, "System font load failed for $name: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // Index builders (one-shot on first miss)
    // -----------------------------------------------------------------------

    private fun indexAssets(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val entries = ctx.assets.list(ASSET_DIR) ?: return emptyMap()
            for (entry in entries) {
                val lower = entry.lowercase()
                if (FONT_EXTENSIONS.none { lower.endsWith(it) }) continue
                val base = lower.substringBeforeLast('.')
                out[base] = "$ASSET_DIR/$entry"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset font index failed: ${e.message}")
        }
        return out
    }

    private fun indexSystemFonts(): Map<String, File> {
        val out = HashMap<String, File>()
        try {
            val dir = File(SYSTEM_FONT_DIR)
            val files = dir.listFiles() ?: return emptyMap()
            for (f in files) {
                val lower = f.name.lowercase()
                if (FONT_EXTENSIONS.none { lower.endsWith(it) }) continue
                val base = lower.substringBeforeLast('.')
                out[base] = f
            }
        } catch (e: Exception) {
            Log.w(TAG, "System font scan failed: ${e.message}")
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun styleBits(isBold: Boolean, isItalic: Boolean): Int = when {
        isBold && isItalic -> Typeface.BOLD_ITALIC
        isBold -> Typeface.BOLD
        isItalic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun cacheKey(name: String, styleBits: Int): Long {
        val nameHash = name.lowercase().hashCode().toLong() and 0xFFFFFFFFL
        return (nameHash shl 4) or (styleBits.toLong() and 0xF)
    }
}
