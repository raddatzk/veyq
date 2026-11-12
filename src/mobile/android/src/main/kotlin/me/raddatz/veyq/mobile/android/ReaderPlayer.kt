package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.blob.RangeReader

/**
 * A medium played as it is read (the web's range playback, ADR 0047), for every application: the platform's player reads
 * it through a [MediaDataSource] over core's [RangeReader] — each range from the frames that hold it, opened on the
 * device —, so the plaintext is never held whole nor written anywhere. The platform's media controls; a tap shows or
 * hides them. Drawn into a texture, not a surface view, so it pages and fades with a dialog's window (src/mobile/CLAUDE.md
 * § Traps). [key] names the medium (a new one starts a new player); [failed] is drawn where it does not play.
 */
@Composable
internal fun ReaderPlayer(reader: RangeReader?, key: Any, initialAspect: Float, modifier: Modifier = Modifier, failed: @Composable () -> Unit) {
    val context = LocalContext.current
    var broken by remember(key) { mutableStateOf(reader == null) }
    var aspect by remember(key) { mutableStateOf(initialAspect) }
    if (broken || reader == null) {
        failed()
        return
    }
    val player = remember(key) { android.media.MediaPlayer() }
    val controller = remember(key) { android.widget.MediaController(context) }
    val prepared = remember(key) { booleanArrayOf(false) }
    DisposableEffect(player) {
        onDispose {
            controller.hide()
            player.release()
        }
    }
    Box(
        modifier.aspectRatio(aspect).clickable(interactionSource = null, indication = null) {
            if (controller.isShowing) controller.hide() else controller.show()
        },
    ) {
        AndroidView(
            factory = { c ->
                android.view.TextureView(c).also { view ->
                    player.setDataSource(ReaderSource(reader))
                    player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
                    player.setOnErrorListener { _, _, _ ->
                        broken = true
                        true
                    }
                    player.setOnPreparedListener {
                        it.start()
                        controller.show()
                    }
                    view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            runCatching { player.setSurface(android.view.Surface(texture)) }
                            if (!prepared[0]) {
                                prepared[0] = true
                                player.prepareAsync()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                    }
                    controller.setMediaPlayer(PlayerControl(player))
                    controller.setAnchorView(view)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The player reads its ranges from [reader] (on its own thread, which waits for the frames). */
internal class ReaderSource(private val reader: RangeReader) : android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        val bytes = try {
            runBlocking { reader.read(position, size) }
        } catch (e: Exception) {
            throw java.io.IOException(e)
        }
        if (bytes.isEmpty()) return -1
        bytes.copyInto(buffer, offset)
        return bytes.size
    }

    override fun getSize(): Long = reader.size
    override fun close() = Unit
}

/** The platform's media controls over [player]; a call after it was released does nothing. */
private class PlayerControl(private val player: android.media.MediaPlayer) : android.widget.MediaController.MediaPlayerControl {
    override fun start() { runCatching { player.start() } }
    override fun pause() { runCatching { player.pause() } }
    override fun getDuration(): Int = runCatching { player.duration }.getOrDefault(0)
    override fun getCurrentPosition(): Int = runCatching { player.currentPosition }.getOrDefault(0)
    override fun seekTo(pos: Int) { runCatching { player.seekTo(pos) } }
    override fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
    override fun getBufferPercentage(): Int = 0
    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = true
    override fun canSeekForward(): Boolean = true
    override fun getAudioSessionId(): Int = runCatching { player.audioSessionId }.getOrDefault(0)
}
