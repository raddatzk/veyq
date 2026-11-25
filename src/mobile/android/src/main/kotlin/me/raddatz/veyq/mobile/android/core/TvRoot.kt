package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.BuildDefaults
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SignInEntry
import me.raddatz.veyq.mobile.session.SignInPlan
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.words.Msg

/**
 * Compose for TV's Material over the app's own scheme (ADR 0159 decision 6, ADR 0123 decision 7): the same roles as the
 * phone's — the web's — handed to tv-material, whose focus look (the scale, the glow, the border) is the platform's.
 */
@Composable
internal fun TvTheme(content: @Composable () -> Unit) {
    val m = androidx.compose.material3.MaterialTheme.colorScheme
    val scheme = androidx.tv.material3.darkColorScheme(
        primary = m.primary, onPrimary = m.onPrimary, primaryContainer = m.primaryContainer, onPrimaryContainer = m.onPrimaryContainer,
        secondary = m.secondary, onSecondary = m.onSecondary, secondaryContainer = m.secondaryContainer, onSecondaryContainer = m.onSecondaryContainer,
        tertiary = m.tertiary, onTertiary = m.onTertiary,
        background = m.background, onBackground = m.onBackground, surface = m.surface, onSurface = m.onSurface,
        surfaceVariant = m.surfaceVariant, onSurfaceVariant = m.onSurfaceVariant,
        error = m.error, onError = m.onError, errorContainer = m.errorContainer, onErrorContainer = m.onErrorContainer,
        border = m.outline, borderVariant = m.outlineVariant, scrim = m.scrim,
    )
    androidx.tv.material3.MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The veyq app on a TV (ADR 0159): the same model as the phone's ([AppModel], core's `SessionModel`), drawn for focus and
 * the remote. The way in is a TV's — the picker, a PIN, a QR sign-in (ADR 0159 decision 4, ADR 0160) —, then the shell
 * ([TvShell]); the device link's ceremony is the phone's own screen ([DeviceLinkScreen]), whose code and six digits fit a
 * TV as they are.
 */
@Composable
internal fun TvRoot(model: AppModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val offer by model.offer.collectAsStateWithLifecycle()
    val link by model.link.collectAsStateWithLifecycle()
    TvTheme {
        Surface(Modifier.fillMaxSize()) {
            when (val s = screen) {
                Screen.Starting -> StartingScreen()
                is Screen.SignIn -> TvSignIn(s, model)
                is Screen.Picker -> TvPicker(s, model)
                is Screen.Unlock -> if (s.asksPin) TvPinUnlock(s, model) else StartingScreen()
                // a TV's sign-in is its QR code only (`SignInPlan.tvEntries`): the locked sign-in and the reset are not reached
                is Screen.Locked, is Screen.Recover -> StartingScreen()
                is Screen.Signed -> offer?.let { TvOffer(it, model) } ?: TvShell(s, model)
            }
            link?.let { DeviceLinkScreen(it, onAgain = model::linkAgain, onClose = model::closeLink, tv = true) }
        }
    }
}

/** A way-in page's frame: the brand, then [content] centred and scrolled where it is taller than the screen. */
@Composable
private fun TvPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 58.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Brand()
        content()
    }
}

/**
 * A TV's sign-in (ADR 0159 decision 4): the server, typed through the system's input method (its voice too), and the
 * entries of core's TV plan (`SignInPlan.tvEntries`: *Sign in with a QR code*, Cancel while adding) — a TV never sees a
 * password or a Secret Key.
 */
@Composable
private fun TvSignIn(state: Screen.SignIn, model: AppModel) {
    var server by rememberSaveable { mutableStateOf(state.server ?: BuildDefaults.SERVER) }
    val show = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { show.requestFocus() } }
    TvPage {
        Text(
            stringResource(if (state.adding) R.string.signin_add_title else R.string.signin_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        androidx.compose.material3.OutlinedTextField(
            server, { server = it }, label = { androidx.compose.material3.Text(stringResource(R.string.signin_server)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().tvField().testTag("tv-signin-server"),
        )
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        state.notice?.let { Text(say(it), textAlign = TextAlign.Center) }
        // every entry of the TV's plan, in its order (ADR 0154 decision 2); the rest are a phone's and never in it
        for (entry in SignInPlan.tvEntries(adding = state.adding)) when (entry) {
            SignInEntry.LINK_SHOW -> Button(onClick = { model.showForSignIn(server) }, enabled = !state.working, modifier = Modifier.focusRequester(show).testTag("tv-signin-link-show")) {
                Icon(Icons.Outlined.QrCode2, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.signin_link_show))
            }
            SignInEntry.CANCEL -> OutlinedButton(onClick = model::cancelAdd, enabled = !state.working, modifier = Modifier.testTag("tv-signin-cancel")) {
                Text(stringResource(R.string.signin_cancel))
            }
            SignInEntry.SUBMIT, SignInEntry.LOCKED, SignInEntry.LINK_SCAN, SignInEntry.KIT_OPEN, SignInEntry.KIT_SCAN,
            SignInEntry.FORGOT, SignInEntry.OPEN_LINK -> Unit
        }
    }
}

/**
 * A TV's account picker (ADR 0159 decision 4): every account kept here under its name on this TV and its server, the
 * most recently used first — picking one asks its PIN —, then *Add someone*, the QR sign-in. [Screen.Picker.error] says
 * why it shows again (the account removed after the tenth wrong PIN).
 */
@Composable
private fun TvPicker(state: Screen.Picker, model: AppModel) = TvPickerPage(state, onPick = model::pick, onAdd = model::addServer)

/** [TvPicker] without the model: [onPick] gets the tile's account (`PickerTile.account`), [onAdd] is *Add someone*. */
@Composable
internal fun TvPickerPage(state: Screen.Picker, onPick: (String) -> Unit, onAdd: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.tiles) { runCatching { first.requestFocus() } }
    TvPage {
        Text(stringResource(R.string.tv_picker_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("tv-picker-title"))
        state.error?.let { Text(say(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("tv-picker-error")) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("tv-picker-tiles")) {
            items(state.tiles, key = { it.account }) { tile ->
                PickerCard(
                    tile.label, tile.host,
                    Modifier.testTag("tv-picker-tile").let { if (tile == state.tiles.first()) it.focusRequester(first) else it },
                ) { onPick(tile.account) }
            }
            item(key = "add") {
                Card(onClick = onAdd, modifier = Modifier.width(PICKER_TILE).testTag("tv-picker-add")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(40.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.tv_picker_add), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** One account's tile: its initial in a circle, its name on this TV, its server. */
@Composable
private fun PickerCard(label: String, host: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.width(PICKER_TILE)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(label.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
            Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private val PICKER_TILE = 200.dp

/**
 * The PIN unlock of the account picked (ADR 0160 decision 3): its name, the pad; a wrong PIN says how many tries are
 * left (core's `Gate.Unlock.attemptsLeft`). Back returns to the picker.
 */
@Composable
private fun TvPinUnlock(state: Screen.Unlock, model: AppModel) {
    BackHandler(enabled = !state.working) { model.backToPicker() }
    TvPage {
        Text(state.record.pickerLabel, style = MaterialTheme.typography.titleLarge)
        val error = state.error?.let { e -> if (e == Msg.TV_PIN_WRONG && state.attemptsLeft != null) say(e, state.attemptsLeft) else say(e) }
        TvPinPad(stringResource(R.string.tv_pin_enter_title), error = error, working = state.working, onPin = model::unlockWithPin)
        if (state.working) androidx.compose.material3.CircularProgressIndicator()
    }
}

/**
 * The offer after a TV's QR sign-in (ADR 0160 decision 1, core's `Offer.pin`): a PIN asked twice, then kept
 * ([AppModel.stayWithPin]); *Don't keep me signed in* declines (the account is held for this run only). Where this TV
 * cannot keep an account ([SessionModel.Offer.pin] false: no hardware factor) it says so, and OK declines.
 */
@Composable
private fun TvOffer(offer: SessionModel.Offer, model: AppModel) {
    val working by model.trustWorking.collectAsStateWithLifecycle()
    TvPage {
        if (!offer.pin) {
            Text(stringResource(R.string.tv_pin_not_offered), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp).testTag("tv-pin-not-offered"))
            val ok = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
            Button(onClick = { model.answerOffer(null) }, modifier = Modifier.focusRequester(ok).testTag("tv-offer-ok")) { Text(stringResource(R.string.ok)) }
            return@TvPage
        }
        // the first PIN, held here until the second matches it; a mismatch or a refusal starts again
        var first by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        val mismatch = stringResource(R.string.tv_pin_mismatch)
        val failed = say(Msg.TRUST_FAILED)
        val held = first
        if (held == null) {
            TvPinPad(stringResource(R.string.tv_pin_set_title), hint = stringResource(R.string.tv_pin_set_hint), error = error, working = working) { pin ->
                error = null
                first = pin
            }
        } else {
            TvPinPad(stringResource(R.string.tv_pin_confirm_title), working = working) { pin ->
                first = null
                if (pin != held) {
                    error = mismatch
                    return@TvPinPad
                }
                model.stayWithPin(pin) { change -> if (change != TrustChange.DONE) error = failed }
            }
        }
        if (working) androidx.compose.material3.CircularProgressIndicator()
        OutlinedButton(onClick = { model.answerOffer(null) }, enabled = !working, modifier = Modifier.testTag("tv-pin-skip")) {
            Text(stringResource(R.string.tv_pin_skip))
        }
    }
}

/**
 * A text field the remote can leave: down and up move the focus on. Compose's field keeps those keys for itself, so the
 * focus never left the server's, the search's or a code's field by the D-pad (measured on the TV emulator).
 */
@Composable
internal fun Modifier.tvField(): Modifier {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        e.type == KeyEventType.KeyDown && when (e.key) {
            Key.DirectionDown -> focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
            else -> false
        }
    }
}
