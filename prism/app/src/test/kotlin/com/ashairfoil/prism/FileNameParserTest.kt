package com.ashairfoil.prism

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for FileNameParser — validates stereo mode, screen type, alpha,
 * and spatial audio detection from video file name tags.
 *
 * File name convention: tags are delimited by _ . or - separators.
 * The parser wraps the name in underscores ("_name_") so that tags at
 * the start/end of the name are still bounded by delimiters.
 */
class FileNameParserTest {

    // ── Helpers ──

    private fun parse(name: String): VideoMetadata =
        FileNameParser.parse(File(name))

    // ── SBS stereo detection ──

    @Test
    fun sbs_tag_gives_side_by_side() {
        val m = parse("video_SBS_180.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun lr_tag_gives_side_by_side() {
        val m = parse("video_LR_360.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun tag_3DH_gives_side_by_side() {
        val m = parse("video_3DH_180.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    // ── TB / OU stereo detection ──

    @Test
    fun tb_tag_gives_top_bottom() {
        val m = parse("video_TB.mp4")
        assertEquals(StereoMode.TOP_BOTTOM, m.stereoMode)
    }

    @Test
    fun ou_tag_maps_to_top_bottom() {
        val m = parse("video_OU.mp4")
        assertEquals(StereoMode.TOP_BOTTOM, m.stereoMode)
    }

    @Test
    fun tag_3DV_gives_top_bottom() {
        val m = parse("video_3DV_180.mp4")
        assertEquals(StereoMode.TOP_BOTTOM, m.stereoMode)
    }

    @Test
    fun overunder_tag_gives_top_bottom() {
        val m = parse("video_OverUnder_180.mp4")
        assertEquals(StereoMode.TOP_BOTTOM, m.stereoMode)
    }

    // ── MONO fallback ──

    @Test
    fun no_stereo_tag_gives_mono() {
        val m = parse("video.mp4")
        assertEquals(StereoMode.MONO, m.stereoMode)
    }

    // ── Screen type: 180 / 360 ──

    @Test
    fun sbs_with_180_gives_dome_180() {
        val m = parse("video_SBS_180.mp4")
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun lr_with_360_gives_sphere_360() {
        val m = parse("video_LR_360.mp4")
        assertEquals(ScreenType.SPHERE_360, m.screenType)
    }

    @Test
    fun tag_3DH_with_180_gives_dome_180() {
        val m = parse("video_3DH_180.mp4")
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun tag_3DV_with_180_gives_dome_180() {
        val m = parse("video_3DV_180.mp4")
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    // ── Screen type: stereo without projection defaults to 180 ──

    @Test
    fun tb_without_projection_defaults_to_dome_180() {
        val m = parse("video_TB.mp4")
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun ou_without_projection_defaults_to_dome_180() {
        val m = parse("video_OU.mp4")
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    // ── Screen type: FLAT for mono with no projection ──

    @Test
    fun no_tags_gives_flat() {
        val m = parse("video.mp4")
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    // ── Screen type: special projections ──

    @Test
    fun mkx200_lr_gives_mkx200() {
        val m = parse("video_mkx200_LR.mp4")
        assertEquals(ScreenType.MKX200, m.screenType)
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun vrca220_lr_gives_vrca220() {
        val m = parse("video_vrca220_LR.mp4")
        assertEquals(ScreenType.VRCA220, m.screenType)
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun rf52_sbs_gives_rf52() {
        val m = parse("video_rf52_SBS.mp4")
        assertEquals(ScreenType.RF52, m.screenType)
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun fisheye190_maps_to_rf52() {
        val m = parse("video_fisheye190_SBS.mp4")
        assertEquals(ScreenType.RF52, m.screenType)
    }

    @Test
    fun fisheye_gives_fisheye() {
        val m = parse("video_fisheye_SBS.mp4")
        assertEquals(ScreenType.FISHEYE, m.screenType)
    }

    // ── Alpha channel detection ──

    @Test
    fun alpha_tag_sets_hasAlpha() {
        val m = parse("video_ALPHA_SBS.mp4")
        assertTrue(m.hasAlpha)
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
    }

    @Test
    fun no_alpha_tag_clears_hasAlpha() {
        val m = parse("video_SBS_180.mp4")
        assertFalse(m.hasAlpha)
    }

    // ── Spatial audio detection ──

    @Test
    fun fb360_tag_sets_hasSpatialAudio() {
        val m = parse("video_FB360.mp4")
        assertTrue(m.hasSpatialAudio)
    }

    @Test
    fun fb360_with_hyphen_delimiter() {
        val m = parse("video-FB360.mp4")
        assertTrue(m.hasSpatialAudio)
    }

    @Test
    fun no_fb360_tag_clears_hasSpatialAudio() {
        val m = parse("video_SBS_180.mp4")
        assertFalse(m.hasSpatialAudio)
    }

    // ── Edge cases: resolution numbers must NOT match projection ──

    @Test
    fun resolution_1800p_does_not_match_180() {
        // "1800p" contains "180" but is a resolution, not a projection tag.
        // The regex requires delimiters on both sides: [_.\-]180[_.\-]
        val m = parse("video_1800p_SBS.mp4")
        // P180 regex doesn't match 1800p, but SBS stereo defaults to DOME_180 via fallback
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun resolution_1800p_mono_does_not_match_180() {
        // MONO with 1800p — no stereo fallback, no projection match → FLAT
        val m = parse("video_1800p.mp4")
        assertEquals(StereoMode.MONO, m.stereoMode)
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    @Test
    fun resolution_3600p_does_not_match_360() {
        // "3600p" contains "360" but the regex needs delimiters: [_.\-]360[_.\-]
        val m = parse("video_3600p.mp4")
        // MONO + no projection match = FLAT
        assertEquals(StereoMode.MONO, m.stereoMode)
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    // ── Edge cases: degenerate file names ──

    @Test
    fun empty_name_gives_mono_flat() {
        val m = parse(".mp4")
        assertEquals(StereoMode.MONO, m.stereoMode)
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    @Test
    fun trailing_underscore_gives_mono_flat() {
        val m = parse("video_.mp4")
        assertEquals(StereoMode.MONO, m.stereoMode)
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    @Test
    fun only_underscores_gives_mono_flat() {
        val m = parse("___.mp4")
        assertEquals(StereoMode.MONO, m.stereoMode)
        assertEquals(ScreenType.FLAT, m.screenType)
    }

    // ── Case insensitivity ──

    @Test
    fun sbs_case_insensitive() {
        assertEquals(StereoMode.SIDE_BY_SIDE, parse("video_sbs_180.mp4").stereoMode)
        assertEquals(StereoMode.SIDE_BY_SIDE, parse("video_Sbs_180.mp4").stereoMode)
    }

    @Test
    fun tb_case_insensitive() {
        assertEquals(StereoMode.TOP_BOTTOM, parse("video_tb.mp4").stereoMode)
    }

    @Test
    fun alpha_case_insensitive() {
        assertTrue(parse("video_alpha_SBS.mp4").hasAlpha)
    }

    // ── Dot and hyphen as delimiters ──

    @Test
    fun dot_delimited_tags() {
        val m = parse("video.SBS.180.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun hyphen_delimited_tags() {
        val m = parse("video-SBS-180.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    @Test
    fun mixed_delimiters() {
        val m = parse("video_SBS.180-LR.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
        assertEquals(ScreenType.DOME_180, m.screenType)
    }

    // ── Projection priority (specific lens > generic projection) ──

    @Test
    fun mkx200_takes_priority_over_180() {
        // Both _mkx200_ and _180_ present — mkx200 should win
        val m = parse("video_mkx200_180_SBS.mp4")
        assertEquals(ScreenType.MKX200, m.screenType)
    }

    @Test
    fun vrca220_takes_priority_over_180() {
        val m = parse("video_vrca220_180_SBS.mp4")
        assertEquals(ScreenType.VRCA220, m.screenType)
    }

    @Test
    fun rf52_takes_priority_over_180() {
        val m = parse("video_rf52_180_SBS.mp4")
        assertEquals(ScreenType.RF52, m.screenType)
    }

    // ── Combined flags ──

    @Test
    fun all_flags_combined() {
        val m = parse("video_ALPHA_SBS_180_FB360.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
        assertEquals(ScreenType.DOME_180, m.screenType)
        assertTrue(m.hasAlpha)
        assertTrue(m.hasSpatialAudio)
    }

    // ── File reference preserved ──

    @Test
    fun file_reference_is_preserved() {
        val file = File("some/path/video_SBS_180.mp4")
        val m = FileNameParser.parse(file)
        assertEquals(file, m.file)
    }

    // ── MKX220 — verify regex matches correctly ──

    @Test
    fun mkx220_not_recognized_falls_through() {
        // mkx220 is not in the parser patterns (only mkx200, vrca220)
        // so it should fall through to 180 default via stereo fallback
        val m = parse("video_mkx220_LR.mp4")
        assertEquals(StereoMode.SIDE_BY_SIDE, m.stereoMode)
        // No MKX220 enum — check it does NOT crash and falls through
        // With SBS and no recognized projection, defaults to DOME_180
        assertEquals(ScreenType.DOME_180, m.screenType)
    }
}
