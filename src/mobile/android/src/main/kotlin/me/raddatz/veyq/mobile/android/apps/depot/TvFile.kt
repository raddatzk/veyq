package me.raddatz.veyq.mobile.android.apps.depot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.TvReaderPlayer
import me.raddatz.veyq.mobile.android.core.joined
import me.raddatz.veyq.mobile.android.core.piece
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.depot.FileView
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A Depot file on a TV (ADR 0159 decision 3): its facts, then the file in the same viewers where it is an image, a video
 * or audio — a medium in [TvReaderPlayer], the remote's keys its controls —; any other file (a text, a PDF, one too
 * large or of no type the viewers take) stays listed and says it opens on another device. Nothing is downloaded or
 * handed on: core's file page offers neither on a TV (`FileRights.of(…, viewOnly)`).
 */
@Composable
internal fun TvFilePage(ref: ObjectRef, model: AppModel) {
    val file = remember(ref.at) { model.depot.fileModel(ref.at) }
    if (file == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    val state by file.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.facts.isNotEmpty()) {
            Text(state.facts.map { piece(it) }.joined(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.uploader?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (val s = state.shown) {
                FileModel.Shown.Loading -> CircularProgressIndicator()
                FileModel.Shown.Failed -> Note(stringResource(R.string.file_open_failed))
                FileModel.Shown.NotUploaded -> Note(stringResource(R.string.depot_not_uploaded))
                // what a TV's viewers do not show: listed, never downloaded here
                is FileModel.Shown.Message, is FileModel.Shown.Text, is FileModel.Shown.Pdf -> Note(stringResource(R.string.tv_opens_elsewhere), "tv-opens-elsewhere")
                is FileModel.Shown.Image -> ShownImage(s, ref.name)
                is FileModel.Shown.Media -> TvReaderPlayer(s.reader, s, Modifier.fillMaxSize(), onPlaying = { model.playing(FILE_PLAYS, it) }) {
                    Note(say(FileView.message(FileView.Kind.UNSUPPORTED)))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, tag: String = "tv-file-note") {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag))
}

/** A file's medium as a source of what plays (`AppModel.playing`). */
private const val FILE_PLAYS = "file"
