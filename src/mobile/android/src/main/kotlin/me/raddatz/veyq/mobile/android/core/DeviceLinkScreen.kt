package me.raddatz.veyq.mobile.android.core

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.DeviceLink
import me.raddatz.veyq.mobile.session.DeviceLink.Refusal
import me.raddatz.veyq.mobile.session.DeviceLink.Role
import me.raddatz.veyq.mobile.session.DeviceLink.Step

/**
 * One device-linking ceremony on screen (ADR 0059), in any of its four roles: core's
 * [DeviceLink] decides every step, this draws its state and hands the person's acts back — the camera into its scan, the
 * six digits typed ([DeviceLink.enterCode]), the slide's end ([DeviceLink.confirm]), [onClose] (which closes the link).
 *
 * **A secret on screen** (`src/mobile/CLAUDE.md` § Secrets on screen): the code it shows is the channel's secret and its
 * camera may show the other side's, so the screen is a full-screen dialog of its own flagged secure, as the kit's scanner
 * is — no Recents thumbnail and no screenshot holds a frame of it; the rest of the app stays capturable. (A pass shoots
 * it through the emulator's own framebuffer, `adb emu screenrecord screenshot`, which the flag does not reach.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkScreen(link: DeviceLink, onAgain: () -> Unit, onClose: () -> Unit, tv: Boolean = false) {
    val shown by link.state.collectAsStateWithLifecycle()
    val title = when (link.role) {
        Role.SHOW_FOR_NEW_DEVICE, Role.SCAN_SIGN_IN_CODE -> R.string.account_settings_add_device
        Role.SCAN_FROM_TRUSTED -> R.string.link_join_title
        Role.SHOW_FOR_SIGN_IN -> R.string.signin_link_show
    }
    // the whole screen, edge to edge: a dialog's window otherwise sits beside the cutout in landscape (#936)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        EdgeToEdgeDialogWindow()
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    title = { Text(stringResource(title)) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.signin_cancel)) } },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("link"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinkStep(link, shown, onAgain, onClose, tv)
                }
            }
        }
    }
}

@Composable
private fun LinkStep(link: DeviceLink, shown: DeviceLink.Shown, onAgain: () -> Unit, onClose: () -> Unit, tv: Boolean = false) {
    val trusted = link.role == Role.SHOW_FOR_NEW_DEVICE || link.role == Role.SCAN_SIGN_IN_CODE
    when (shown.step) {
        Step.OPENING -> Waiting(if (link.role == Role.SHOW_FOR_SIGN_IN || link.role == Role.SHOW_FOR_NEW_DEVICE) R.string.link_sign_in_opening else null)
        Step.SHOWING -> if (link.role == Role.SHOW_FOR_NEW_DEVICE) {
            Copy(R.string.link_scan_hint)
            Code(link)
            Waiting(R.string.link_waiting)
            SameServer(shown)
        } else {
            Copy(R.string.link_sign_in_intro)
            Code(link)
            // a TV says where the code is scanned and the six digits come from (its sign-in is this code only, ADR 0159 decision 4)
            Copy(if (tv) R.string.tv_signin_hint else R.string.link_sign_in_scan_hint)
            SixDigits(link)
        }
        Step.SCANNING -> Scanning(link, shown)
        Step.CODE -> {
            Copy(R.string.link_code_prompt)
            Digits(shown.code.orEmpty(), "link-code")
            Consequence()
            Waiting(R.string.link_code_waiting)
            SameServer(shown)
        }
        Step.WAITING -> if (link.role == Role.SCAN_FROM_TRUSTED) {
            Copy(R.string.link_join_intro)
            Digits(shown.number.orEmpty(), "link-number")
            Copy(R.string.link_join_compare)
            Waiting(R.string.link_join_waiting)
            SameServer(shown)
        } else {
            Waiting(R.string.link_sign_in_waiting)
            SameServer(shown)
        }
        Step.CONFIRMING -> Confirming(link, shown, onClose)
        Step.SEALING -> Waiting(R.string.link_sealing)
        Step.FINISHING -> Waiting(R.string.link_finishing)
        Step.DONE -> if (trusted) {
            Text(stringResource(R.string.link_sealed), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("link-sealed"))
            Copy(R.string.link_sealed_hint)
            Button(onClick = onClose) { Text(stringResource(R.string.link_close)) }
        } else {
            Waiting(R.string.link_finishing)
        }
        Step.ENDED -> Ended(link.role, shown, onAgain, onClose)
    }
}

/** A line of the ceremony's words. */
@Composable
private fun Copy(text: Int, color: Color = Color.Unspecified) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = color, modifier = Modifier.fillMaxWidth())
}

/** Work under way, and what it waits for. */
@Composable
private fun Waiting(text: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (text != null) Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The hint core raises once the other device has been silent for the first window (both devices must use the same server, ADR 0059 §4). */
@Composable
private fun SameServer(shown: DeviceLink.Shown) {
    if (shown.hint == DeviceLink.Hint.SAME_SERVER) Copy(R.string.link_same_server, MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The consequence the slide stands against (ADR 0059 §7): signing the device out later does not take the key back. */
@Composable
private fun Consequence() {
    Text(
        stringResource(R.string.link_consequence), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(),
    )
}

/** Five or six digits to compare or to type, large and spaced. */
@Composable
private fun Digits(digits: String, tag: String) {
    Text(
        digits, style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
        letterSpacing = 6.sp, modifier = Modifier.testTag(tag),
    )
}

/** This side's code: core's matrix drawn dark on light whatever the theme (a scanner reads that), its quiet zone included. */
@Composable
private fun Code(link: DeviceLink) {
    val matrix = remember(link) { link.matrix() } ?: return
    val alt = stringResource(if (link.role == Role.SHOW_FOR_SIGN_IN) R.string.link_code_alt_sign_in else R.string.link_code_alt)
    Canvas(Modifier.size(280.dp).semantics { contentDescription = alt }.testTag("link-qr")) {
        val n = matrix.side
        val cell = size.minDimension / n
        drawRect(Color.White)
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.dark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The new device's six digits, typed and sent once (§6): editable until sent, one try. */
@Composable
private fun SixDigits(link: DeviceLink) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(
        code, { typed -> code = typed.filter(Char::isDigit).take(6) },
        label = { Text(stringResource(R.string.link_sign_in_code)) },
        supportingText = { Text(stringResource(R.string.link_sign_in_code_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { link.enterCode(code) }),
        // a remote's down leaves the field for the send button (a TV, ADR 0159; measured: the field kept the key)
        modifier = Modifier.fillMaxWidth().testTag("link-code-field").onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        },
    )
    Button(onClick = { link.enterCode(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().testTag("link-send")) {
        Text(stringResource(R.string.link_sign_in_send))
    }
}

/** The camera into the link's own scan; the other direction's code said with the other entry's name, the scan going on. */
@Composable
private fun Scanning(link: DeviceLink, shown: DeviceLink.Shown) {
    val scan = link.scan ?: return
    val newDevice = link.role == Role.SCAN_FROM_TRUSTED
    DisposableEffect(scan) { onDispose { scan.close() } }
    Box(Modifier.fillMaxWidth().height(360.dp).clipToBounds()) {
        QrCamera(scan, Modifier.fillMaxSize()) { state ->
            if (state == QrScan.State.Denied || state == QrScan.State.NoCamera) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (newDevice) R.string.link_join_scan_denied else R.string.link_scan_denied),
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    Copy(if (newDevice) R.string.link_join_scan_hint else R.string.link_scan_sign_in_hint)
    if (shown.hint == DeviceLink.Hint.OTHER_DIRECTION) {
        Copy(if (newDevice) R.string.link_other_direction_new else R.string.link_other_direction_trusted, MaterialTheme.colorScheme.error)
    }
}

/** The trusted device's confirmation (§5, §7): the number compared or the proof held, the name, then the slide. */
@Composable
private fun Confirming(link: DeviceLink, shown: DeviceLink.Shown, onClose: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    val compared = link.role == Role.SHOW_FOR_NEW_DEVICE
    Copy(if (compared) R.string.link_compare_prompt else R.string.link_proven)
    if (compared) Digits(shown.number.orEmpty(), "link-number")
    OutlinedTextField(
        label, { label = it },
        label = { Text(stringResource(R.string.link_label)) },
        supportingText = { Text(stringResource(R.string.link_label_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("link-label"),
    )
    val name = label.trim().ifEmpty { stringResource(R.string.link_slide_no_name) }
    Slide(stringResource(R.string.link_slide, name), enabled = label.isNotBlank()) { link.confirm(label) }
    Consequence()
    if (compared) {
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("link-mismatch")) { Text(stringResource(R.string.link_mismatch)) }
    } else {
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_cancel)) }
    }
}

/**
 * The slide that confirms (ADR 0059 §7, ADR 0161's Consequences): Material's [Slider] that confirms only at its end and springs back
 * otherwise — a deliberate gesture, not a tap. Keyboard: the arrow keys move it, Return at the end confirms (a key that
 * moves it never confirms by itself). A screen reader hears [sentence] and confirms through the custom action of that
 * name. [onConfirm] answers whether the link moved on; one that did not (a blank name) springs back.
 */
@Composable
private fun Slide(sentence: String, enabled: Boolean, onConfirm: () -> Boolean) {
    var value by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // the spring back, stopped by the next gesture or key (it would otherwise pull the knob back under the finger)
    var springing by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val back: () -> Unit = { springing = scope.launch { animate(value, 0f) { v, _ -> value = v } } }
    val confirm: () -> Unit = { if (!onConfirm()) back() }
    // where the pointer's gesture began: only one carried from the start confirms — a tap at the track's end jumps there
    // in one move and springs back, as does a screen reader's adjustment (its confirmation is the named action below)
    var from by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(sentence, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Slider(
            value = value,
            onValueChange = {
                if (from == null) {
                    springing?.cancel()
                    from = it
                }
                value = it
            },
            // a drag ends here; the keys below never reach the slider's own handling
            onValueChangeFinished = {
                val carried = (from ?: 1f) <= START
                from = null
                if (carried && value >= END) confirm() else back()
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (!enabled || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key in KEYS
                    if (e.key in KEYS) springing?.cancel()
                    when (e.key) {
                        // ten steps, as the web's slide: deliberate, never one key to the end
                        Key.DirectionRight, Key.DirectionUp -> value = (Math.round(value * STEPS) + 1).coerceAtMost(STEPS) / STEPS.toFloat()
                        Key.DirectionLeft, Key.DirectionDown -> value = (Math.round(value * STEPS) - 1).coerceAtLeast(0) / STEPS.toFloat()
                        Key.Enter, Key.NumPadEnter -> if (value >= END) confirm() else return@onPreviewKeyEvent false
                        // a key that jumps to the end would be the tap the slide refuses: taken, nothing moves
                        else -> return@onPreviewKeyEvent e.key in KEYS
                    }
                    true
                }
                .semantics {
                    contentDescription = sentence
                    customActions = listOf(CustomAccessibilityAction(sentence) { onConfirm() })
                }
                .testTag("link-slide"),
        )
        Text(stringResource(R.string.link_slide_keys), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
}

private const val END = 0.98f
private const val START = 0.15f
private const val STEPS = 10

/** The keys the slide takes itself (the slider's own would move it a percent, or jump to an end). */
private val KEYS = setOf(
    Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd,
)

/** How the ceremony ended, in this role's words, and the way to start over. */
@Composable
private fun Ended(role: Role, shown: DeviceLink.Shown, onAgain: () -> Unit, onClose: () -> Unit) {
    val newDevice = role == Role.SCAN_FROM_TRUSTED || role == Role.SHOW_FOR_SIGN_IN
    val (said, hint) = when (shown.refusal) {
        Refusal.OFF -> R.string.link_off to null
        Refusal.GONE -> R.string.link_gone to when (role) {
            Role.SCAN_FROM_TRUSTED -> R.string.link_join_gone_hint
            Role.SHOW_FOR_SIGN_IN -> R.string.link_sign_in_gone_hint
            else -> null
        }
        Refusal.REFUSED -> when (role) {
            Role.SHOW_FOR_NEW_DEVICE -> R.string.link_answer_refused to R.string.link_answer_refused_hint
            Role.SCAN_SIGN_IN_CODE -> R.string.link_scan_refused to R.string.link_scan_refused_hint
            else -> R.string.link_join_refused to null
        }
        Refusal.FAILED -> (if (newDevice) R.string.link_join_failed else R.string.link_grant_failed) to null
        null -> null to null
    }
    if (said != null) {
        Text(
            stringResource(said), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().testTag("link-ended"),
        )
    }
    hint?.let { Copy(it) }
    SameServer(shown)
    if (shown.refusal != Refusal.OFF) Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().testTag("link-again")) { Text(stringResource(R.string.link_again)) }
    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_close)) }
}
