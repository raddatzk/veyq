package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.raddatz.veyq.mobile.audio.NowPlaying
import me.raddatz.veyq.mobile.audio.PlayerCommands
import me.raddatz.veyq.mobile.audio.PlayerNotice
import me.raddatz.veyq.mobile.words.Msg

/** How long the bar says that none of a queue's tracks played (the web's 5 s). */
private const val NOTHING_PLAYS_MS = 5_000L

/**
 * The music bar (the web's `AudioBar.vue`, ADR 0142 decision 5), at the shell's bottom above the application's bar — the
 * mini-player Material's own music apps draw there — while the account's player holds a queue: the title, the node's name
 * (it opens the node that started the queue), previous, play/pause, next, close (the queue stops), and the position over
 * the trimmed span, which seeks when it is let go, with the time played and the span's length. What it shows is the core's
 * `NowPlaying`; it decides nothing. When none of a queue played on this device it says so for 5 s (ADR 0142 decision 9).
 */
@Composable
internal fun MusicBar(model: AppModel) {
    val player = model.music.collectAsStateWithLifecycle().value ?: return
    val state by player.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val notice = state.notice
    var saying by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        saying = notice == PlayerNotice.UNPLAYABLE
        if (saying) {
            delay(NOTHING_PLAYS_MS)
            saying = false
        }
    }
    val now = state.nowPlaying
    if (now == null && !saying) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (saying) Text(say(Msg.MUSIC_NOTHING_PLAYS), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            if (now == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null, tint = role(palette.accent), modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val album = now.album
                    val route = now.route
                    if (album != null) {
                        val opens = say(Msg.MUSIC_OPEN_NODE, album)
                        Text(
                            album, style = MaterialTheme.typography.bodySmall, color = role(palette.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (route != null) Modifier.clickable(onClickLabel = opens) { model.open(route) } else Modifier,
                        )
                    }
                }
                MusicButtons(now, player)
                IconButton(onClick = { player.stop() }) { Icon(Icons.Filled.Close, say(Msg.MUSIC_CLOSE)) }
            }
            MusicPosition(now, player)
        }
    }
}

/** Previous, play/pause, next over the core's player ([commands]): the bar's and the lightbox's compact controls'. */
@Composable
private fun MusicButtons(now: NowPlaying, commands: PlayerCommands, tint: Color = Color.Unspecified) {
    IconButton(onClick = commands::previous) { Icon(Icons.Filled.SkipPrevious, say(Msg.MUSIC_PREVIOUS), tint = tint) }
    if (now.playing) IconButton(onClick = commands::pause) { Icon(Icons.Filled.Pause, say(Msg.MUSIC_PAUSE), tint = tint) }
    else IconButton(onClick = commands::resume) { Icon(Icons.Filled.PlayArrow, say(Msg.MUSIC_RESUME), tint = tint) }
    IconButton(onClick = commands::next) { Icon(Icons.Filled.SkipNext, say(Msg.MUSIC_NEXT), tint = tint) }
}

/** Where the track stands in its span: a slider that seeks once let go (the web's range), the times as `m:ss`. */
@Composable
private fun MusicPosition(now: NowPlaying, commands: PlayerCommands) {
    var dragged by remember(now.trackId) { mutableStateOf<Float?>(null) }
    val total = now.durationMs.coerceAtLeast(1).toFloat()
    val at = dragged ?: now.positionMs.toFloat().coerceIn(0f, total)
    val label = say(Msg.MUSIC_PROGRESS, now.title)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(me.raddatz.veyq.mobile.words.Shown.length(at.toLong()), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = at,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { commands.seek(it.toLong()) }
                dragged = null
            },
            valueRange = 0f..total,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = label },
        )
        Text(if (now.durationMs > 0) me.raddatz.veyq.mobile.words.Shown.length(now.durationMs) else "", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The lightbox's compact controls (the web's `AudioMiniControls.vue`, ADR 0142 decision 5): the title, previous,
 * play/pause, next — a row of its own under the lightbox's bar on a phone. Closing the lightbox stops nothing.
 */
@Composable
internal fun MusicCompactControls(now: NowPlaying, commands: PlayerCommands) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.MusicNote, null, tint = Color.White, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        Text(now.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        MusicButtons(now, commands, tint = Color.White)
    }
}
