package me.raddatz.veyq.mobile.blob

import kotlin.test.Test
import kotlin.test.assertEquals

/** The type a desktop's picked path is claimed as ([MediaTypes]): by its extension, case aside, else octet-stream. */
class MediaTypesTest {
    @Test
    fun aPathIsClaimedByItsExtensionAsABrowserClaimsIt() {
        assertEquals("image/jpeg", MediaTypes.byName("Urlaub.JPG"))
        assertEquals("video/webm", MediaTypes.byName("big.webm"))
        assertEquals("video/mp4", MediaTypes.byName("clip.mp4"))
        assertEquals("application/pdf", MediaTypes.byName("Rechnung 2026.pdf"))
        assertEquals("text/plain", MediaTypes.byName("long.txt"))
        assertEquals("application/zip", MediaTypes.byName("archive.zip"))
        assertEquals("video/quicktime", MediaTypes.byName("prores.mov"))
        // the last extension counts; none, a dot file or an unknown one is octet-stream
        assertEquals("application/gzip", MediaTypes.byName("site.tar.gz"))
        assertEquals(MediaTypes.UNKNOWN, MediaTypes.byName("README"))
        assertEquals(MediaTypes.UNKNOWN, MediaTypes.byName(".hidden"))
        assertEquals(MediaTypes.UNKNOWN, MediaTypes.byName("big.bin"))
    }
}
