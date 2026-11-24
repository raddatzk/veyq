package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.offline.QueuedItem
import me.raddatz.veyq.mobile.offline.QueuedWrite

/**
 * The uploads tray's writes made offline (ADR 0153 decision 5; core's `QueuedTray`): a row under the top bar while any is
 * kept — core's count —, which opens a sheet listing each under the place it writes into, with what core offers on it:
 * *Discard* always, *Open* for a held one, *Save a copy* (the system's save dialog) for each file a refused one carries.
 * Every line is core's; the sheet decides nothing.
 */
@Composable
internal fun QueuedTrayRow(model: AppModel) {
    val items by model.queuedItems.collectAsStateWithLifecycle()
    val count by model.queuedBanner.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) open = false }
    val said = count ?: return
    if (items.isEmpty()) return
    val needsYou = items.any { it.state != QueuedWrite.State.WAITING }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("queued-tray"), onClick = { open = true }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (needsYou) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudUpload, null, Modifier.size(20.dp),
                tint = if (needsYou) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Text(say(said), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.upload_title))
        }
    }
    if (open) QueuedSheet(items, model) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuedSheet(items: List<QueuedItem>, model: AppModel, onDismiss: () -> Unit) {
    // *Save a copy*: the system's save dialog names the document, core writes the file's plaintext into it alone
    var saving by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (id, index) = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri != null) model.saveQueuedCopy(id, index, uri)
    }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(stringResource(R.string.upload_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                // under the destination's label, as the uploads' batches are
                for ((where, group) in items.groupBy { it.where }) {
                    if (where != null) item(key = "w:$where") {
                        Text(
                            where, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 12.dp),
                        )
                    }
                    items(group, key = { it.id }) { item ->
                        QueuedRow(
                            item,
                            onDiscard = { model.discardQueued(item.id) },
                            onOpen = { onDismiss(); model.openQueued(item.id) },
                            onSave = { index ->
                                scope.launch {
                                    saving = item.id to index
                                    save.launch(model.queuedCopyName(item.id, index) ?: "")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QueuedRow(item: QueuedItem, onDiscard: () -> Unit, onOpen: () -> Unit, onSave: (Int) -> Unit) {
    val waiting = item.state == QueuedWrite.State.WAITING
    ListItem(
        headlineContent = { Text(say(item.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(say(item.said), color = if (waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                FlowRow {
                    if (item.opens) TextButton(onClick = onOpen, modifier = Modifier.testTag("queued-open")) { Text(stringResource(R.string.offline_open)) }
                    for (index in 0 until item.copies) {
                        TextButton(onClick = { onSave(index) }, modifier = Modifier.testTag("queued-save-copy")) { Text(stringResource(R.string.offline_save_copy)) }
                    }
                    TextButton(onClick = onDiscard, modifier = Modifier.testTag("queued-discard")) {
                        Text(stringResource(R.string.offline_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

/**
 * A level's node made offline and not sent yet (core's `ShellModel.queuedHere`): its name as core words it, marked with
 * core's line — *Waiting to send*, or why it is held or refused. A Memory made offline opens its page ([onOpen] with core's
 * `QueuedItem.page`, #1040), a folder made offline is entered ([onEnter] with core's `QueuedItem.folder`, #1095).
 */
@Composable
internal fun QueuedLevelRow(
    item: QueuedItem,
    onOpen: (me.raddatz.veyq.mobile.navigation.ObjectRef) -> Unit = {},
    onEnter: (me.raddatz.veyq.mobile.navigation.FolderRef) -> Unit = {},
) {
    val waiting = item.state == QueuedWrite.State.WAITING
    ListItem(
        headlineContent = { Text(say(item.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(say(item.said), color = if (waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) },
        leadingContent = { Icon(if (waiting) Icons.Outlined.CloudUpload else Icons.Outlined.ErrorOutline, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.testTag("queued-level-row").let { m ->
            item.page?.let { p -> m.clickableRow { onOpen(p) } } ?: item.folder?.let { f -> m.clickableRow { onEnter(f) } } ?: m
        },
    )
}

/**
 * The ask before a sign-out, "Stop trusting this device" or "Remove all offline content" deletes writes made offline that
 * wait (ADR 0153 decision 2; core's `QueuedAsk`): its question naming how many, its own confirm, *Cancel* beside it.
 */
@Composable
internal fun QueuedLossDialog(model: AppModel) {
    val asking by model.lossAsk.collectAsStateWithLifecycle()
    val a = asking ?: return
    AlertDialog(
        onDismissRequest = { model.answerLoss(false) },
        text = { Text(say(a.ask.question)) },
        confirmButton = {
            TextButton(onClick = { model.answerLoss(true) }, modifier = Modifier.testTag("queued-loss-confirm")) {
                Text(say(a.ask.confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { model.answerLoss(false) }) { Text(stringResource(R.string.entry_cancel)) } },
    )
}

/** A held rename's *Open* (core's `QueuedOpen.Rename`): the rename dialog with the person's name in it, core's answer drawn. */
@Composable
internal fun HeldRenameDialog(model: AppModel) {
    val held by model.heldRename.collectAsStateWithLifecycle()
    val open = held ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    NodeRenameDialog(open.name, onRename = { name ->
        val answer = model.renameHeld(open, name)
        answer.notice?.let(model::said)
        answer.problem?.let { context.text(it) }
    }, onClose = model::heldRenameSeen)
}
