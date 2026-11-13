package me.raddatz.veyq.mobile.memories

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The tiers' spec is the core's, the web's numbers (`src/web/src/shared/image/mediaDeriver.ts`, which
 * `MediaUpload.Derived` names): every platform derives with [MediaUpload]'s constants, and these are read here against the
 * web's file itself, so neither side moves alone.
 */
class MediaTierSpecTest {
    private val web = File(System.getProperty("veyq.repoRoot"), "src/web/src/shared/image/mediaDeriver.ts").readText()

    private fun tier(name: String): Pair<Int, Double> {
        val m = assertNotNull(Regex("""$name:\s*\{\s*maxEdge:\s*(\d+),\s*quality:\s*([0-9.]+)\s*}""").find(web), "$name in TIER_SPECS")
        return m.groupValues[1].toInt() to m.groupValues[2].toDouble()
    }

    @Test
    fun theTiersAreTheWebs() {
        assertEquals(tier("PREVIEW"), MediaUpload.PREVIEW_EDGE to MediaUpload.PREVIEW_QUALITY)
        assertEquals(tier("THUMBNAIL"), MediaUpload.THUMBNAIL_EDGE to MediaUpload.THUMBNAIL_QUALITY)
    }

    @Test
    fun thePosterIsTakenWhereTheWebTakesIt() {
        val at = assertNotNull(Regex("""currentTime = Math\.min\(([0-9.]+), \(video\.duration \|\| 0\) / 2\)""").find(web), "the poster's seek")
        assertEquals((at.groupValues[1].toDouble() * 1000).toLong(), MediaUpload.POSTER_AT_MS)
        assertEquals(100L, MediaUpload.posterAtMs(60_000))
        assertEquals(40L, MediaUpload.posterAtMs(80), "a clip shorter than twice the seek: its middle")
        assertEquals(0L, MediaUpload.posterAtMs(-1))
    }
}
