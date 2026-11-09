package me.raddatz.veyq.mobile.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The web's `archiveName.test.ts`, case for case: the same name yields the same safe path in either client. */
class ArchiveNameTest {
    private fun one(name: String) = ArchiveName.of(name, mutableSetOf())

    @Test
    fun theHostileCases() {
        listOf(
            ".." to "_", "." to "_", "" to "_", "   " to "_", "a/b" to "a_b", "a\\b" to "a_b", "CON.txt" to "_CON.txt",
            "con" to "_con", "Lpt1.tar.gz" to "_Lpt1.tar.gz", "name. " to "name", "trailing..." to "trailing",
            "Alfama: day 1" to "Alfama_ day 1", "what?<>|*\"" to "what______", "tab\there\u0000" to "tab_here_",
            ".env" to ".env", "Trip 2025" to "Trip 2025", "Übersicht" to "Übersicht",
        ).forEach { (name, safe) -> assertEquals(safe, one(name), name) }
    }

    @Test
    fun aLongNameIsCutOnACharacterKeepingItsExtension() {
        val safe = one("ä".repeat(300) + ".jpg")
        assertTrue(safe.encodeToByteArray().size <= ArchiveName.MAX_BYTES)
        assertTrue(safe.endsWith(".jpg") && safe.startsWith("ää"))
    }

    @Test
    fun namesThatDifferInCaseAreNumberedBeforeTheExtension() {
        val taken = mutableSetOf<String>()
        assertEquals("IMG.jpg", ArchiveName.of("IMG.jpg", taken))
        assertEquals("img (2).JPG", ArchiveName.of("img.JPG", taken))
        assertEquals("Img (3).jpg", ArchiveName.of("Img.jpg", taken))
        assertEquals("notes", ArchiveName.of("notes", taken))
        assertEquals("NOTES (2)", ArchiveName.of("NOTES", taken))
    }

    @Test
    fun namesThatBecomeEqualOnceSafeClashToo() {
        val taken = mutableSetOf<String>()
        assertEquals("a_b", ArchiveName.of("a/b", taken))
        assertEquals("a_b (2)", ArchiveName.of("a:b", taken))
    }

    @Test
    fun theTwoSpellingsOfOneNameAreOneName() {
        val taken = mutableSetOf<String>()
        assertEquals("Café", ArchiveName.of("Café", taken))
        assertEquals("Café (2)", ArchiveName.of("Café", taken))
    }

    @Test
    fun theManifestDirectoryIsNoNodesName() {
        assertEquals(".VEYQ (2)", ArchiveName.of(".VEYQ", mutableSetOf(ArchiveName.MANIFEST_DIR)))
    }
}
