package me.raddatz.veyq.mobile.android.core

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A text shared to veyq (inbox #1015, ADR 0150 § S): Android's share sheet hands `ACTION_SEND` with `EXTRA_TEXT`, which
 * [ShareIn.textOf] reads as a received link's way in — and only that: a file with a caption stays a file's share, a
 * start from the recents list opens nothing, and the text (which holds the key) is gone from the intent once read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ShareInTextTest {
    private val link = "https://photos.example.org/s/${"Q".repeat(42)}w#k=${"A".repeat(43)}"

    private fun send(text: String?) = Intent(Intent.ACTION_SEND).setType("text/plain").apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) } }

    @Test
    fun aTextAloneIsReadAndTakenOffTheIntent() {
        val intent = send("Look: $link")
        assertEquals("Look: $link", ShareIn.textOf(intent))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        // a second read (a recreation handing the same intent) finds nothing
        assertNull(ShareIn.textOf(intent))
    }

    @Test
    fun aFileWithACaptionIsAFilesShare() {
        val intent = send(link).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
        assertNull(ShareIn.textOf(intent))
    }

    @Test
    fun aStartFromTheRecentsListOpensNothing() {
        val intent = send(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(ShareIn.textOf(intent))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun anyOtherIntentIsNotAText() {
        assertNull(ShareIn.textOf(null))
        assertNull(ShareIn.textOf(send(null)))
        assertNull(ShareIn.textOf(Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_TEXT, link)))
        assertNull(ShareIn.textOf(Intent(Intent.ACTION_VIEW, Uri.parse("veyq://link")).putExtra(Intent.EXTRA_TEXT, link)))
    }
}
