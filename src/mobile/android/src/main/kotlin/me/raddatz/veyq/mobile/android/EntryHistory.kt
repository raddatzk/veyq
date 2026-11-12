package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.expenses.PotView

private fun localDateTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT))

/** An entry's history (`EntryHistory.vue`): every revision the Pot holds, newest first, what it said, when and by whom. */
@Composable
internal fun EntryHistory(view: PotView, entryId: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.history_hide else R.string.history_show)) }
    if (!open) return
    val palette = LocalPalette.current
    for (r in view.history(entryId)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(view.historyLine(r).map { piece(it) }.joinToString(" · "))
            Text(
                "${r.writer ?: stringResource(R.string.history_unknown_writer)}, ${localDateTime(r.at)}",
                style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
            )
        }
    }
}

/**
 * A deleted entry (`EntryEditor.vue` on a tombstone): it counts nowhere; restorable until its receipts are erased, then
 * a line in the history only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeletedEntrySheet(view: PotView, state: me.raddatz.veyq.mobile.expenses.PotModel.State, model: AppModel, row: PotView.EntryRow, onClose: () -> Unit) {
    // the window on the core's clock (`PotModel.restorable`), never the platform's
    val restorable = model.restorable(row.id)
    val purge = view.purgeAfter(row.id)
    ModalBottomSheet(onDismissRequest = { model.clearPotMessage(); onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.title ?: stringResource(R.string.pot_transfer), style = MaterialTheme.typography.titleLarge)
            row.amount?.let { Text(money(it, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold) }
            Text(stringResource(R.string.entry_deleted_hint))
            when {
                purge != null && restorable -> Text(stringResource(R.string.entry_restorable_until, localDateTime(purge)))
                purge != null -> Text(stringResource(R.string.entry_not_restorable))
            }
            HorizontalDivider()
            EntryHistory(view, row.id)
            state.message?.let { Text(potRefusal(it), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { model.clearPotMessage(); onClose() }) { Text(stringResource(R.string.entry_cancel)) }
                if (restorable) {
                    Button(onClick = { model.restoreEntry(row.id) { onClose() } }, enabled = !state.saving) {
                        Text(stringResource(R.string.entry_restore))
                    }
                }
            }
        }
    }
}
