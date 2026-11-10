package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.session.Person

/**
 * "New pot" (the web's `PotCreateDialog.vue`): a name, the space it goes to the root of, the base currency — sealed into
 * the node's meta at creation, fixed once the first entry exists — and its first people, names typed or members of that
 * space chosen (a member at most once, a name at most once, case aside).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PotCreateSheet(s: Screen.Signed, model: AppModel, onClose: () -> Unit) {
    val creating by model.creating.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var scopeId by rememberSaveable { mutableStateOf(PotCreate.defaultSpace(s.shell, s.spaces) ?: "") }
    var currency by rememberSaveable { mutableStateOf(PotCreate.defaultCurrency(java.util.Locale.getDefault().language)) }
    var typed by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf(PotCreate.People()) }
    var members by remember { mutableStateOf<List<Person>>(emptyList()) }
    LaunchedEffect(scopeId) {
        members = if (scopeId.isEmpty()) emptyList() else model.membersOf(scopeId).orEmpty()
        // A member chosen from another space is no member here.
        people = people.keep(members)
    }
    fun takeTyped() {
        people = people.typed(typed)
        typed = ""
    }

    ModalBottomSheet(onDismissRequest = { model.clearCreating(); onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.create_pot), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.create_pot_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (s.spaces.size > 1) {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(
                        s.spaces.firstOrNull { it.scopeId == scopeId }?.let { spaceName(it) } ?: "", {}, readOnly = true,
                        label = { Text(stringResource(R.string.create_pot_space)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(open, { open = false }) {
                        for (sp in s.spaces) DropdownMenuItem(text = { Text(spaceName(sp)) }, onClick = { scopeId = sp.scopeId; open = false })
                    }
                }
            }
            var currencyOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(currencyOpen, { currencyOpen = it }) {
                OutlinedTextField(
                    currency, {}, readOnly = true, label = { Text(stringResource(R.string.pot_base_currency)) },
                    supportingText = { Text(stringResource(R.string.settings_currency_hint)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(currencyOpen) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(currencyOpen, { currencyOpen = false }) {
                    for (code in currencies) DropdownMenuItem(text = { Text(code) }, onClick = { currency = code; currencyOpen = false })
                }
            }
            Text(stringResource(R.string.pot_people), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in people.list) {
                    InputChip(
                        selected = false, onClick = { people = people.remove(p) }, label = { Text(p.name) },
                        trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.people_remove, p.name)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.people_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = ::takeTyped, enabled = typed.isNotBlank()) { Text(stringResource(R.string.people_add)) }
            }
            val offered = people.offered(members)
            if (offered.isNotEmpty()) {
                Text(stringResource(R.string.people_choose_member), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in offered) AssistChip(onClick = { people = people.add(PotCreate.NewPerson.member(m.userId, m.displayName)) }, label = { Text(m.displayName) })
                }
            }
            creating.message?.let { Text(createRefusal(it), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { model.clearCreating(); onClose() }) { Text(stringResource(R.string.entry_cancel)) }
                Button(
                    onClick = {
                        takeTyped()
                        // in the folder on screen, where the sheet creates in the space shown (the web's "New" at a level)
                        val parent = PotCreate.parentIn(s.shell, scopeId)
                        model.createPot(scopeId, name, currency, minorUnitsOf(currency), people.list, parent) { onClose() }
                    },
                    enabled = name.isNotBlank() && scopeId.isNotEmpty() && !creating.saving,
                ) { Text(stringResource(R.string.create_pot_create)) }
            }
        }
    }
}

@Composable
private fun createRefusal(outcome: String): String = say(me.raddatz.veyq.mobile.words.Notices.potCreateRefused(outcome))
