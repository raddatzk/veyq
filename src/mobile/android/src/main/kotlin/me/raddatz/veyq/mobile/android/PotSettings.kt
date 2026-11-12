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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.RevisionFormat

/** A refusal of a Pot write, in words. */
@Composable
internal fun potRefusal(outcome: String): String = say(me.raddatz.veyq.mobile.words.Notices.potRefused(outcome))

/**
 * An open Pot's Settings tab (the web's `SettingsPanel.vue` and `PeoplePanel.vue`): its base currency and own categories,
 * and its people, each with their balance in short and what may be done to them (`PotView.PersonEntry`).
 */
@Composable
internal fun PotSettings(view: PotView, state: me.raddatz.veyq.mobile.expenses.PotModel.State, model: AppModel) {
    // core's `PotSettings` decides what may be changed and says a refusal; this draws it
    val settings = androidx.compose.runtime.remember(state.ref) { model.potSettings() } ?: return
    androidx.compose.runtime.DisposableEffect(settings) { onDispose { settings.clear() } }
    val said by settings.state.collectAsStateWithLifecycle()
    val ui = SettingsUi(settings, said, state.saving, model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalNodeHero.current(0.dp)
        if (!view.ready) {
            Text(stringResource(if (view.canChangeSettings) R.string.settings_missing else R.string.settings_missing_read_only))
        }
        BaseCurrency(view, ui)
        if (view.ready) {
            People(view, ui)
            Categories(view, ui)
        } else {
            said.notice?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** What the settings' rows draw from: core's model, its state, and whether a write is saving. */
private class SettingsUi(
    val settings: me.raddatz.veyq.mobile.expenses.PotSettings,
    val said: me.raddatz.veyq.mobile.expenses.PotSettings.State,
    val saving: Boolean,
    private val model: AppModel,
) {
    /** One of [settings]' actions, [done] once it was stored (a field emptied, a dialog closed). */
    fun write(op: suspend me.raddatz.veyq.mobile.expenses.PotSettings.() -> Boolean, done: () -> Unit = {}) = model.settingsWrite({ settings.op() }, done)
}

/** The currencies offered, by code: core's one table (the web's `Intl`). */
internal val currencies: List<String> get() = me.raddatz.veyq.mobile.expenses.Currencies.known

/** The base currency: chosen while the Pot holds no money entry, fixed from its first one on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseCurrency(view: PotView, ui: SettingsUi) {
    val palette = LocalPalette.current
    Section(stringResource(R.string.pot_base_currency))
    if (!view.currencyEditable) {
        Text(view.currency ?: "")
        if (view.currencyFixed) Text(stringResource(R.string.settings_currency_fixed), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
        return
    }
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { if (!ui.saving) open = it }) {
        OutlinedTextField(
            view.currency ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.pot_base_currency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for (code in currencies) DropdownMenuItem(text = { Text(code) }, onClick = {
                open = false
                ui.write({ setBaseCurrency(code) })
            })
        }
    }
    Text(stringResource(R.string.settings_currency_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
}

/** The Pot's own categories beside the default set: added and renamed, never removed in the first cut. */
@Composable
private fun Categories(view: PotView, ui: SettingsUi) {
    if (!view.categoriesShown) return
    val palette = LocalPalette.current
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    Section(stringResource(R.string.pot_own_categories))
    if (view.canChangeSettings) Text(stringResource(R.string.settings_categories_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
    for (c in view.ownCategories) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.second, Modifier.weight(1f))
            if (view.canChangeSettings) {
                IconButton(onClick = { renaming = c }, enabled = !ui.saving) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_rename_category, c.second))
                }
            }
        }
    }
    if (view.canAddCategory) {
        var name by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_category_name)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ui.write({ addCategory(name) }) { name = "" } }, enabled = name.isNotBlank() && !ui.saving) {
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
                TextButton(onClick = { ui.write({ renameCategory(c.first, name) }) { renaming = null } }, enabled = name.isNotBlank() && !ui.saving) {
                    Text(stringResource(R.string.entry_save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun People(view: PotView, ui: SettingsUi) {
    val palette = LocalPalette.current
    var renaming by remember { mutableStateOf<PotView.PersonEntry?>(null) }
    Section(stringResource(R.string.pot_people))
    for (p in view.personEntries) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(p.name, p.userId)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                val facts = view.personFacts(p).map { piece(it) }
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
            }
            if (p.renamable) {
                IconButton(onClick = { renaming = p }, enabled = !ui.saving) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.people_rename, p.name))
                }
            }
            if (p.canToggle) {
                TextButton(onClick = { ui.write({ toggleActive(p.id) }) }, enabled = !ui.saving) {
                    Text(stringResource(if (p.active) R.string.people_deactivate else R.string.people_activate))
                }
            }
            if (p.canErase) {
                IconButton(onClick = { ui.settings.askErase(p.id) }, enabled = !ui.saving) {
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
            OutlinedButton(onClick = { ui.write({ addPerson(name) }) { name = "" } }, enabled = name.isNotBlank() && !ui.saving) {
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
            OutlinedButton(onClick = { chosen?.let { m -> ui.write({ addMember(m.userId) }) { chosen = null } } }, enabled = chosen != null && !ui.saving) {
                Text(stringResource(R.string.people_add_member))
            }
        }
    }
    ui.said.notice?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.people_rename, p.name)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.people_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { ui.write({ rename(p.id, name) }) { renaming = null } }, enabled = name.isNotBlank() && !ui.saving) {
                    Text(stringResource(R.string.entry_save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
    // the erase asked is core's state: done only on its confirm, cancelled by its Cancel
    ui.said.asking?.let { p ->
        AlertDialog(
            onDismissRequest = { ui.settings.cancelAsk() },
            title = { Text(stringResource(if (p.named) R.string.people_delete else R.string.people_remove, p.name)) },
            text = { Text(stringResource(if (p.named) R.string.people_delete_confirm else R.string.people_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = { ui.write({ erase() }) }) {
                    Text(stringResource(if (p.named) R.string.people_delete_accept else R.string.people_remove_accept), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { ui.settings.cancelAsk() }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}
