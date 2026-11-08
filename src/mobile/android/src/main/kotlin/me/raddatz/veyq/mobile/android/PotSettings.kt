package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.RevisionFormat

/** A refusal of a Pot write, in words. */
@Composable
internal fun potRefusal(outcome: String): String = stringResource(
    when (outcome) {
        "STALE" -> R.string.entry_stale
        "NOT_SETTLED" -> R.string.people_settle_first
        "BUSY" -> R.string.pot_busy
        "BLOCKED" -> R.string.pot_blocked
        "INVALID" -> R.string.people_invalid
        else -> R.string.people_failed
    },
)

/**
 * An open Pot's Settings tab (the web's `SettingsPanel.vue` and `PeoplePanel.vue`): its base currency and own categories,
 * and its people, each with their balance in short and what may be done to them (`PotView.PersonEntry`).
 */
@Composable
internal fun PotSettings(view: PotView, state: AppModel.PotState, model: AppModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!view.ready) {
            Text(stringResource(if (view.canChangeSettings) R.string.settings_missing else R.string.settings_missing_read_only))
        }
        BaseCurrency(view, state, model)
        if (view.ready) {
            People(view, state, model)
            Categories(view, state, model)
        } else {
            state.message?.let { Text(potRefusal(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The currencies this device knows, by code (`java.util.Currency`; the web asks `Intl` the same). */
internal val currencies: List<String> by lazy {
    java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.filter { RevisionFormat.CURRENCY.matches(it) }.distinct().sorted()
}

/** The minor-unit digits of [code] (the euro 2, the yen 0, the dinar 3), within the format's 0..4. */
internal fun minorUnitsOf(code: String): Int =
    runCatching { java.util.Currency.getInstance(code).defaultFractionDigits }.getOrDefault(2).let { if (it < 0) 2 else it.coerceIn(0, 4) }

/** The base currency: chosen while the Pot holds no money entry, fixed from its first one on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseCurrency(view: PotView, state: AppModel.PotState, model: AppModel) {
    val palette = LocalPalette.current
    Section(stringResource(R.string.pot_base_currency))
    if (!view.canChangeSettings || view.currencyFixed) {
        Text(view.currency ?: "")
        if (view.currencyFixed) Text(stringResource(R.string.settings_currency_fixed), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        return
    }
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { if (!state.saving) open = it }) {
        OutlinedTextField(
            view.currency ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.pot_base_currency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for (code in currencies) DropdownMenuItem(text = { Text(code) }, onClick = {
                open = false
                if (code != view.currency) model.potWrite({ it.setBaseCurrency(code, minorUnitsOf(code)) })
            })
        }
    }
    Text(stringResource(R.string.settings_currency_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
}

/** The Pot's own categories beside the default set: added and renamed, never removed in the first cut. */
@Composable
private fun Categories(view: PotView, state: AppModel.PotState, model: AppModel) {
    if (!view.canChangeSettings && view.ownCategories.isEmpty()) return
    val palette = LocalPalette.current
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    Section(stringResource(R.string.pot_own_categories))
    if (view.canChangeSettings) Text(stringResource(R.string.settings_categories_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
    for (c in view.ownCategories) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.second, Modifier.weight(1f))
            if (view.canChangeSettings) {
                IconButton(onClick = { renaming = c }, enabled = !state.saving) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_rename_category, c.second))
                }
            }
        }
    }
    if (view.canChangeSettings && view.ownCategories.size < 200) {
        var name by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_category_name)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { model.potWrite({ it.addCategory(name) }) { name = "" } }, enabled = name.isNotBlank() && !state.saving) {
                Text(stringResource(R.string.settings_add_category))
            }
        }
    }
    renaming?.let { c ->
        var name by remember(c.first) { mutableStateOf(c.second) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.settings_rename_category, c.second)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_category_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { model.potWrite({ it.renameCategory(c.first, name) }) { renaming = null } }, enabled = name.isNotBlank() && !state.saving) {
                    Text(stringResource(R.string.entry_save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun People(view: PotView, state: AppModel.PotState, model: AppModel) {
    val palette = LocalPalette.current
    var renaming by remember { mutableStateOf<PotView.PersonEntry?>(null) }
    var erasing by remember { mutableStateOf<PotView.PersonEntry?>(null) }
    Section(stringResource(R.string.pot_people))
    for (p in view.personEntries) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(p.name, p.userId)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                val facts = buildList {
                    add(if (p.settled) stringResource(R.string.pot_settled) else money(p.balance, view.currency, view.minorUnits))
                    if (!p.active) add(stringResource(R.string.people_inactive))
                    if (p.standing == PotView.Standing.FORMER) add(stringResource(R.string.people_former_member))
                }
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
            if (p.renamable) {
                IconButton(onClick = { renaming = p }, enabled = !state.saving) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.people_rename, p.name))
                }
            }
            if (p.canToggle) {
                TextButton(onClick = { model.potWrite({ it.setActive(p.id, !p.active) }) }, enabled = !state.saving) {
                    Text(stringResource(if (p.active) R.string.people_deactivate else R.string.people_activate))
                }
            }
            if (p.canErase) {
                IconButton(onClick = { erasing = p }, enabled = !state.saving) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(if (p.named) R.string.people_delete else R.string.people_remove, p.name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (view.deletedPeople > 0) {
        Text(pluralStringResource(R.plurals.people_deleted_summary, view.deletedPeople, view.deletedPeople), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
    }
    if (view.canWrite && view.anyUnsettled) {
        Text(stringResource(R.string.people_settle_first), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
    }
    if (view.canWrite) {
        var name by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.people_name)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { model.potWrite({ it.addPerson(name) }) { name = "" } }, enabled = name.isNotBlank() && !state.saving) {
                Text(stringResource(R.string.people_add))
            }
        }
    }
    if (view.canWrite && view.addableMembers.isNotEmpty()) {
        var chosen by remember { mutableStateOf<PotView.Member?>(null) }
        var open by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(open, { open = it }, Modifier.weight(1f)) {
                OutlinedTextField(
                    chosen?.name ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.people_choose_member)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(open, { open = false }) {
                    for (m in view.addableMembers) DropdownMenuItem(text = { Text(m.name) }, onClick = { chosen = m; open = false })
                }
            }
            OutlinedButton(onClick = { chosen?.let { m -> model.potWrite({ it.addMember(m.userId) }) { chosen = null } } }, enabled = chosen != null && !state.saving) {
                Text(stringResource(R.string.people_add_member))
            }
        }
    }
    state.message?.let { Text(potRefusal(it), color = MaterialTheme.colorScheme.error) }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.people_rename, p.name)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.people_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { model.potWrite({ it.rename(p.id, name) }) { renaming = null } }, enabled = name.isNotBlank() && !state.saving) {
                    Text(stringResource(R.string.entry_save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
    erasing?.let { p ->
        AlertDialog(
            onDismissRequest = { erasing = null },
            title = { Text(stringResource(if (p.named) R.string.people_delete else R.string.people_remove, p.name)) },
            text = { Text(stringResource(if (p.named) R.string.people_delete_confirm else R.string.people_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = { model.potWrite({ it.erase(p.id) }); erasing = null }) {
                    Text(stringResource(if (p.named) R.string.people_delete_accept else R.string.people_remove_accept), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { erasing = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}
