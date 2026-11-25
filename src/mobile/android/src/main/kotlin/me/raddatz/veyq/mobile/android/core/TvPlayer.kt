package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.blob.RangeReader

/**
 * A medium played as it is read on a TV (ADR 0159 decision 7): the phone's player ([ReaderPlayer]) — the platform's
 * `MediaPlayer` over core's [RangeReader] through [ReaderSource], nothing decrypted written — with the remote's controls
 * instead of the touch-oriented `MediaController`: the player takes the focus; the centre key, Enter and the media
 * play/pause key pause and play, left and right (and rewind, fast forward) seek by [SEEK_MS], and its state shows as the
 * play or pause mark in the corner. [onPlaying] hears it start and stop (the music pauses through the core, the idle lock
 * waits); [failed] is drawn where it does not play.
 */
@Composable
internal fun TvReaderPlayer(
    reader: RangeReader?, key: Any, modifier: Modifier = Modifier, onPlaying: ((Boolean) -> Unit)? = null,
    failed: @Composable () -> Unit,
) {
    var broken by remember(key) { mutableStateOf(reader == null) }
    var aspect by remember(key) { mutableStateOf(16f / 9f) }
    var playing by remember(key) { mutableStateOf(false) }
    var focused by remember(key) { mutableStateOf(false) }
    if (broken || reader == null) {
        failed()
        return
    }
    val player = remember(key) { android.media.MediaPlayer() }
    val prepared = remember(key) { booleanArrayOf(false) }
    val tell = androidx.compose.runtime.rememberUpdatedState(onPlaying)
    fun said(on: Boolean) {
        if (playing == on) return
        playing = on
        tell.value?.invoke(on)
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
            said(false)
        }
    }
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
    fun toggle() = runCatching { if (player.isPlaying) { player.pause(); said(false) } else { player.start(); said(true) } }
    fun seek(by: Int) = runCatching { player.seekTo((player.currentPosition + by).coerceIn(0, player.duration.coerceAtLeast(0))) }
    val label = stringResource(if (playing) R.string.music_pause else R.string.memory_play)
    Box(
        modifier
            .aspectRatio(aspect)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> { toggle(); true }
                    Key.MediaPlay -> { if (!playing) toggle(); true }
                    Key.MediaPause -> { if (playing) toggle(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-SEEK_MS); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seek(SEEK_MS); true }
                    else -> false
                }
            }
            .focusable()
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent)
            .semantics { contentDescription = label }
            .testTag("tv-player"),
    ) {
        AndroidView(
            factory = { c ->
                android.view.TextureView(c).also { view ->
                    player.setDataSource(ReaderSource(reader))
                    player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
                    player.setOnErrorListener { _, _, _ ->
                        broken = true
                        said(false)
                        true
                    }
                    player.setOnCompletionListener { said(false) }
                    player.setOnPreparedListener {
                        it.start()
                        said(true)
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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // the state, where the remote's press shows it: paused shows the play mark, playing the pause mark
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape).padding(8.dp)) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** How far the remote's left and right seek a playing medium. */
private const val SEEK_MS = 10_000
