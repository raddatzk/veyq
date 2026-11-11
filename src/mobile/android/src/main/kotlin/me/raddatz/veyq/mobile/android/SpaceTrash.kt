package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.session.NodeTrash

/**
 * The space's trash (the web's `SpaceTrashDialog.vue`): the root of each deleted subtree, with the day it was deleted and
 * the day it goes for good; Restore brings it back complete — where something took its name meanwhile, the row asks for
 * another and restores under it — and "Delete forever" erases it now, after the question that names it. A row someone
 * else restored or purged meanwhile leaves through a re-read. A kind the app cannot name offers no Restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceTrashSheet(scopeId: String, model: AppModel, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val sheet = remember(scopeId) { model.trashSheet(scopeId) } ?: return
    val st by sheet.state.collectAsState()
    LaunchedEffect(scopeId) { sheet.load() }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.trash_hint), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
            Spacer(Modifier.padding(6.dp))
            val list = st.rows
            when {
                st.failed -> Text(stringResource(R.string.trash_load_failed), color = role(palette.danger))
                list == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                list.isEmpty() -> Text(stringResource(R.string.trash_empty), color = role(palette.muted), modifier = Modifier.padding(vertical = 12.dp))
                else -> LazyColumn {
                    items(list, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(kindIcon(row.kind), null, tint = role(palette.accent))
                                Spacer(Modifier.width(10.dp))
                                Text(row.name, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                stringResource(R.string.trash_deleted_on, day(row.deletedAt)) + " · " + stringResource(R.string.trash_purge_on, day(row.purgeAfter)),
                                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
                            )
                            if (st.errorRow == row.id) st.error?.let { Text(say(it), style = MaterialTheme.typography.bodySmall, color = role(palette.danger)) }
                            val busy = st.busy == row.id
                            if (st.renaming == row.id) {
                                OutlinedTextField(
                                    st.retryName, { sheet.type(it) }, label = { Text(stringResource(R.string.trash_retry_name)) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy,
                                )
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { sheet.cancelRename() }, enabled = !busy) { Text(stringResource(R.string.trash_cancel)) }
                                    TextButton(onClick = { scope.launch { sheet.retry(row) } }, enabled = st.retryable(row)) { Text(stringResource(R.string.trash_restore)) }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    if (sheet.canRestore(row)) TextButton(onClick = { scope.launch { sheet.restore(row) } }, enabled = !busy) { Text(stringResource(R.string.trash_restore)) }
                                    TextButton(onClick = { sheet.askPurge(row) }, enabled = !busy) { Text(stringResource(R.string.trash_purge), color = role(palette.danger)) }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    st.purging?.let { row ->
        AlertDialog(
            onDismissRequest = { sheet.cancelPurge() },
            title = { Text(stringResource(R.string.trash_purge_confirm_title)) },
            text = { Text(stringResource(R.string.trash_purge_confirm, row.name)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { sheet.purge(row) } }) { Text(stringResource(R.string.trash_purge), color = role(palette.danger)) }
            },
            dismissButton = { TextButton(onClick = { sheet.cancelPurge() }) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
}

/** The day of a server instant (ISO 8601), in the device's words. */
private fun day(instant: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .format(java.time.Instant.parse(instant).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
}.getOrDefault(instant.take(10))
