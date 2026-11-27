package me.raddatz.veyq.mobile.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The terms' and the operator section's Markdown as the app draws it (`markdownBlocks`, the desktops' `blocks`). */
class MarkdownBlocksTest {
    @Test
    fun aHeadingIsItsLineAListIsBulletedAndAnEmphasisIsItsWords() {
        val b = markdownBlocks("## Rules\n\nBe kind.\n- one\n* **two**\nUse *Report* on what you see.\n2 * 3 * 4 stays\n\n\n# Contact\nWrite to us.")
        assertEquals(
            listOf(
                "Rules" to true,
                "Be kind.\n• one\n• two\nUse Report on what you see.\n2 * 3 * 4 stays" to false,
                "Contact" to true,
                "Write to us." to false,
            ),
            b,
        )
    }
}
