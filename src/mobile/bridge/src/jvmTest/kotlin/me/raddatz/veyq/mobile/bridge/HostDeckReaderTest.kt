package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.audio.Deck
import me.raddatz.veyq.mobile.blob.RangeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * A deck lets its held reader go when the host refuses the load, a small id and a large one alike. The deck once held
 * its id in an AtomicReference<Long?>, whose compareAndSet compares boxes by identity: it missed, and the reader stayed
 * held for good (measured red at id 5).
 */
class HostDeckReaderTest {
    @Test
    fun aRefusedLoadDropsItsReaderPastTheBoxCache() = runBlocking {
        for (heldId in listOf(5L, 1_000L)) {
            val dropped = mutableListOf<Long>()
            lateinit var host: Host
            host = Host { token, _ -> host.reply(token, """{"type":"Failed","error":"refused"}""") }
            val readers = object : HeldReaders {
                override fun hold(reader: RangeReader): Long = heldId
                override fun drop(id: Long) { dropped += id }
            }
            val deck = HostDeck(host, 1, readers, object : Deck.Events {
                override fun ended() {}
                override fun failed() {}
            })
            assertFails { deck.load(RangeReader(0, 1) { _, _, _ -> }, "audio/mpeg") }
            assertEquals(listOf(heldId), dropped, "reader $heldId let go after a refused load")
        }
    }
}
