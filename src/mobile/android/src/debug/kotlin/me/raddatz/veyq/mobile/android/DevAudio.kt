package me.raddatz.veyq.mobile.android

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.audio.Deck
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.Sniff

/**
 * Debug builds only — the music's format matrix (ADR 0142 decision 9): launched with the extra `veyq.devAudio=matrix`, every
 * file in the app's external `files/audio` (`adb push … /sdcard/Android/data/app.veyq/files/audio/`) is typed by its
 * bytes (`Sniff.stream`, as an upload types it), handed to a [MediaDeck] through a core [RangeReader] cut into 256 KiB
 * frames — the path a track plays by —, loaded, played for 1.5 s and cleared; and its length read as an upload reads it
 * (`MediaMetadataRetriever`). One line per file to logcat (`veyq-audio`). A release build has no such door.
 */
object DevAudio {
    private const val TAG = "veyq-audio"
    private const val FRAME = 256 * 1024

    fun requested(intent: Intent?): Boolean = intent?.extras?.getString("veyq.devAudio") == "matrix"

    fun run(context: Context) {
        val dir = context.getExternalFilesDir("audio") ?: return
        CoroutineScope(Dispatchers.Default).launch {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val bytes = f.readBytes()
                val mime = Sniff.stream(bytes.copyOf(minOf(bytes.size, 4096)))?.mime
                val measured = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(f.path)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        r.release()
                    }
                }.getOrElse { null }
                val reader = RangeReader(bytes.size.toLong(), (bytes.size + FRAME - 1) / FRAME) { first, until, sink ->
                    for (i in first until until) sink(bytes.copyOfRange(i * FRAME, minOf(bytes.size, (i + 1) * FRAME)))
                }
                var ended = false
                var failed = false
                val deck = MediaDeck(context, object : Deck.Events {
                    override fun ended() { ended = true }
                    override fun failed() { failed = true }
                })
                val line = try {
                    val ms = deck.load(reader, mime ?: "application/octet-stream")
                    deck.play()
                    delay(1500)
                    val at = deck.positionMs()
                    "loads=yes duration=$ms playedTo=$at failed=$failed ended=$ended"
                } catch (e: Exception) {
                    "loads=NO ($e)"
                } finally {
                    deck.clear()
                    reader.close()
                }
                Log.i(TAG, "${f.name} sniff=$mime measure=$measured $line")
            }
            Log.i(TAG, "matrix done")
        }
    }
}
