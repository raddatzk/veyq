package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.account.AppleKit
import me.raddatz.veyq.mobile.audio.AppleAudio
import me.raddatz.veyq.mobile.blob.FileSave
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.memories.music.MusicModel
import me.raddatz.veyq.mobile.memories.music.PcmWav
import platform.Foundation.NSData

// Memories' part of Swift's music adapter ([AppleAudio]): a track's save and a picked track's WAV wrap, as extensions of it,
// so Swift calls them where it did (`AppleAudio.shared.save(…)`, a category of `AppleAudio`).

/**
 * Track [mediaId] of [music] saved at [path] (`MusicModel.save` into core's [FileSink], which leaves nothing at [path]
 * when it did not finish), awaited to its end; null where it may not be saved.
 */
@Throws(Exception::class)
suspend fun AppleAudio.save(music: MusicModel, mediaId: String, path: String): FileSave.End? {
    val save = music.save(mediaId) { produce ->
        withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
    } ?: return null
    return save.state.first { it.end != null }.end
}

/**
 * A decoded end of a picked track ([pcm]: interleaved 32-bit float, as `AVAudioFile` reads it) as the WAV the core's
 * silence scan reads (inbox #932, `MusicUpload.Measure.ends`), copied once.
 */
fun AppleAudio.floatWav(pcm: NSData, channels: Int, sampleRate: Int): ByteArray =
    PcmWav.of(AppleKit.bytes(pcm), channels, sampleRate, 32, float = true)

