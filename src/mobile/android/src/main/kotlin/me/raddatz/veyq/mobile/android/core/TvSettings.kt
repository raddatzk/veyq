package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg

/**
 * A TV's Settings (ADR 0159 decision 4, ADR 0160 decision 7): its own few, not the account's page — *Lock*, *Add
 * someone*, and for an account kept here behind a PIN its name on the picker and *Change PIN*; *Remove from this TV* signs
 * the account out here and forgets its PIN. Everything else of the account is another device's to change.
 */
@Composable
internal fun TvSettings(model: AppModel) {
    var changing by remember { mutableStateOf(false) }
    if (changing) {
        TvChangePin(model) { changing = false }
        return
    }
    val label = remember { model.pickerLabel() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("tv-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.drawer_settings), style = MaterialTheme.typography.headlineSmall)
        if (label != null) {
            var name by remember { mutableStateOf(label) }
            androidx.compose.material3.OutlinedTextField(
                name, { name = it }, label = { androidx.compose.material3.Text(stringResource(R.string.tv_picker_name)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.renameOnPicker(name) }),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().tvField().testTag("tv-picker-name"),
            )
            OutlinedButton(onClick = { model.renameOnPicker(name) }, modifier = Modifier.testTag("tv-picker-name-save")) {
                Text(stringResource(R.string.tv_picker_name_save))
            }
            SettingsButton(Icons.Outlined.Password, stringResource(R.string.tv_change_pin), "tv-change-pin") { changing = true }
        }
        SettingsButton(Icons.Outlined.Lock, stringResource(R.string.tv_lock), "tv-lock", model::lock)
        SettingsButton(Icons.Outlined.PersonAdd, stringResource(R.string.tv_picker_add), "tv-add-someone", model::addServer)
        SettingsButton(Icons.Outlined.PersonRemove, stringResource(R.string.tv_remove_here), "tv-remove-here", model::removeHere)
    }
}

@Composable
private fun SettingsButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, null, Modifier.padding(end = 8.dp))
        Text(label)
    }
}

/**
 * *Change PIN* (ADR 0160 decision 7): the current PIN, counted by the server as an unlock is — a wrong one says the tries
 * left, the tenth removes the account and the TV locks —, then the new one twice.
 */
@Composable
private fun TvChangePin(model: AppModel, onDone: () -> Unit) {
    val working by model.trustWorking.collectAsStateWithLifecycle()
    var old by remember { mutableStateOf<String?>(null) }
    var new by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.tv_pin_mismatch)
    val failed = say(Msg.TRUST_FAILED)
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.activity.compose.BackHandler(enabled = !working, onBack = onDone)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val o = old
        val n = new
        when {
            o == null -> TvPinPad(stringResource(R.string.tv_pin_old_title), error = error, working = working) { old = it; error = null }
            n == null -> TvPinPad(stringResource(R.string.tv_pin_set_title), hint = stringResource(R.string.tv_pin_set_hint), error = error, working = working) { new = it; error = null }
            else -> TvPinPad(stringResource(R.string.tv_pin_confirm_title), working = working) { again ->
                if (again != n) {
                    new = null
                    error = mismatch
                    return@TvPinPad
                }
                model.changePin(o, n) { change ->
                    when (change.outcome) {
                        VeyqAccount.PinChange.Outcome.CHANGED -> {
                            model.said(me.raddatz.veyq.mobile.words.Notice(Msg.TV_PIN_CHANGED))
                            onDone()
                        }
                        VeyqAccount.PinChange.Outcome.WRONG -> {
                            old = null
                            new = null
                            error = context.words(Msg.TV_PIN_WRONG, change.attemptsLeft ?: 0)
                        }
                        // the account left this TV: the model locked it, the picker says so
                        VeyqAccount.PinChange.Outcome.GONE -> onDone()
                        VeyqAccount.PinChange.Outcome.FAILED -> {
                            old = null
                            new = null
                            error = failed
                        }
                    }
                }
            }
        }
        if (working) androidx.compose.material3.CircularProgressIndicator()
        OutlinedButton(onClick = onDone, enabled = !working) { Text(stringResource(R.string.entry_cancel)) }
    }
}
