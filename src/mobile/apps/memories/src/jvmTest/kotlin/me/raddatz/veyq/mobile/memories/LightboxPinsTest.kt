package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.navigation.ShellModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The clocks Memories' models run are the web's, read here against the web's own files so neither side moves alone: the
 * slideshow's step (`MediaLightbox.vue`'s `SLIDESHOW_INTERVAL_MS`) and a trashed medium's Undo window
 * (`useMediaGalleryLive.ts`' `useUndo` timeout, the node trash's `TRASH_UNDO_MS`).
 */
class LightboxPinsTest {
    private val memories = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories")

    @Test
    fun theSlideshowStepsAtTheWebsInterval() {
        val web = File(memories, "components/MediaLightbox.vue").readText()
        val m = assertNotNull(Regex("""const SLIDESHOW_INTERVAL_MS = (\d+)""").find(web), "SLIDESHOW_INTERVAL_MS in MediaLightbox.vue")
        assertEquals(m.groupValues[1].toLong(), Lightbox.SLIDESHOW_MS)
    }

    @Test
    fun aTrashedMediumsUndoLastsTheWebsWindow() {
        val web = File(memories, "useMediaGalleryLive.ts").readText()
        // the window is the model's on the web too (ADR 0155 § The gallery model's move): what is pinned is the toast's life
        val m = assertNotNull(Regex("""onUndo: onUndoDelete, life: (\d+)""").find(web), "the Undo toast's life in useMediaGalleryLive.ts")
        assertEquals(m.groupValues[1].toLong(), ShellModel.TRASH_UNDO_MS)
    }
}
