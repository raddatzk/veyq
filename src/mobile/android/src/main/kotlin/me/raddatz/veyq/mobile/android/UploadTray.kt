package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.blob.UploadQueue

/**
 * The shell's uploads (the web's `UploadStatusMenu.vue`): a row under the top bar while the app's upload queue holds a
 * batch — what is under way and how far, or that all is in, or how many did not go up —, which opens a sheet listing
 * every batch with each file's state, its cancel while it may still go up and its retry where one helps. A batch all in
 * leaves on its own after a moment; one with a failure stays until it is closed.
 */
@Composable
internal fun UploadTray(model: AppModel) {
    val state by model.uploadState.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val batches = state.batches
    // a batch all in is acknowledged after a moment; one with a failure waits for its close
    LaunchedEffect(batches.map { it.id to it.finished }) {
        val clean = state.clean
        if (clean.isNotEmpty()) {
            kotlinx.coroutines.delay(UploadQueue.ACKNOWLEDGE_AFTER_MS)
            clean.forEach { model.uploads.acknowledge(it.id) }
        }
    }
    if (preparing != null && batches.isEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.upload_preparing_folder, preparing!!), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    LaunchedEffect(batches.isEmpty()) { if (batches.isEmpty()) open = false }
    if (batches.isEmpty()) return
    val failed = state.failed
    val progress = state.progress.toFloat()
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth(), onClick = { open = true }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.active > 0 -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                failed > 0 -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(12.dp))
            Text(
                when {
                    state.active > 0 -> stringResource(R.string.upload_active, state.active)
                    failed > 0 -> stringResource(R.string.upload_failed_count, failed)
                    else -> stringResource(R.string.upload_status_done)
                },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.upload_title))
        }
    }
    if (open) UploadSheet(state, model) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSheet(state: UploadQueue.State, model: AppModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.upload_title), style = MaterialTheme.typography.titleLarge)
                    if (state.active > 0) Text(stringResource(R.string.upload_active, state.active), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.active > 0) TextButton(onClick = { model.uploads.cancelAll() }) { Text(stringResource(R.string.upload_cancel_all)) }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                for (batch in state.batches) {
                    item(key = "b:" + batch.id) {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(batch.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (batch.finished) TextButton(onClick = { model.uploads.acknowledge(batch.id) }) { Text(stringResource(R.string.upload_close)) }
                        }
                    }
                    items(batch.items, key = { it.id }) { item -> UploadRow(item, model) }
                }
            }
        }
    }
}

@Composable
private fun UploadRow(item: UploadQueue.Item, model: AppModel) {
    val status = androidx.compose.ui.platform.LocalContext.current.text(me.raddatz.veyq.mobile.words.Notices.uploadStatus(item))
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.status == UploadQueue.Status.UPLOADING) {
                    LinearProgressIndicator(progress = { item.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else if (item.status == UploadQueue.Status.PREPARING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    status,
                    color = if (item.status == UploadQueue.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                item.active -> IconButton(onClick = { model.uploads.cancel(item.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.upload_cancel)) }
                item.retryable -> IconButton(onClick = { model.uploads.retry(item.id) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.upload_retry)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
