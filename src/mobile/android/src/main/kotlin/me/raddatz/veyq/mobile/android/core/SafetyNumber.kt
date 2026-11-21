package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.PersonTrust

/**
 * A person's safety number on their page (uc.spaces.enforce-verified-identities; ADR 0148 decision 3), the web's
 * `MySafetyNumber.vue` and `PersonSafetyNumber.vue` over core's [PersonTrust]: on one's own page *Your safety number* →
 * the digits, the chain code and the code to scan; on another member's their state — verified (by whom), not verified,
 * new, can't confirm — and *Compare* → *Scan their code* or the digits read aloud ("They match" / "They don't match"),
 * a match verifying them. A safety number is no secret: no secure window.
 */
@Composable
internal fun SafetyNumberSection(userId: String, name: String, model: AppModel) {
    val trust = model.personTrust() ?: return
    if (userId == model.ownUserId()) MySafetyNumber(trust) else TheirSafetyNumber(trust, userId, name, model)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MySafetyNumber(trust: PersonTrust) {
    var showing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("safety-mine")) {
        SectionTitle(stringResource(R.string.safety_mine_title))
        OutlinedButton(onClick = { showing = true }, modifier = Modifier.padding(top = 4.dp).testTag("safety-mine-show")) {
            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp))
            Text(stringResource(R.string.safety_show), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (showing) MineDialog(trust) { showing = false }
}

@Composable
private fun MineDialog(trust: PersonTrust, onClose: () -> Unit) {
    var mine by remember { mutableStateOf<PersonTrust.Mine?>(null) }
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(trust) {
        mine = runCatching { trust.mine() }.getOrNull()
        read = true
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.safety_mine_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("safety-mine-dialog")) {
                Text(stringResource(R.string.safety_mine_intro), style = MaterialTheme.typography.bodyMedium)
                val m = mine
                when {
                    m != null -> {
                        Digits(m.number, "safety-mine-number")
                        Text(stringResource(R.string.safety_chain_code), style = MaterialTheme.typography.labelLarge)
                        Digits(m.chainCode, "safety-mine-chain-code")
                        Text(stringResource(R.string.safety_mine_chain_code_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Code(m.payload, stringResource(R.string.safety_qr_alt)) }
                    }
                    read -> Text(stringResource(R.string.safety_mine_unavailable), color = MaterialTheme.colorScheme.error)
                    else -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) } },
    )
}

/** The digits, large and monospaced, as the web sets them (groups of five). */
@Composable
private fun Digits(digits: String, tag: String) {
    Text(
        digits, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 1.sp,
        modifier = Modifier.testTag(tag),
    )
}

/** Core's code drawn dark on light whatever the theme (a scanner reads that), its quiet zone included. */
@Composable
private fun Code(payload: String, alt: String) {
    val matrix = remember(payload) { runCatching { QrEncoder.encode(payload) }.getOrNull() }
    if (matrix == null) {
        Text(stringResource(R.string.safety_qr_failed), color = MaterialTheme.colorScheme.error)
        return
    }
    Canvas(Modifier.size(220.dp).semantics { contentDescription = alt }.testTag("safety-qr")) {
        val n = matrix.side
        val cell = size.minDimension / n
        drawRect(Color.White)
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.dark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun TheirSafetyNumber(trust: PersonTrust, userId: String, name: String, model: AppModel) {
    var shown by remember { mutableStateOf<PersonTrust.Shown?>(null) }
    var read by remember { mutableStateOf(false) }
    var reads by remember { mutableIntStateOf(0) }
    var comparing by remember { mutableStateOf(false) }
    LaunchedEffect(userId, reads) {
        shown = runCatching { trust.person(userId) }.getOrNull()
        read = true
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("safety-theirs")) {
        SectionTitle(stringResource(R.string.safety_theirs_title))
        val s = shown
        if (s == null) {
            if (read) Text(stringResource(R.string.safety_load_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val (label, color) = when (s.status) {
            PersonTrust.Status.VERIFIED -> stringResource(R.string.safety_state_verified) to MaterialTheme.colorScheme.primary
            PersonTrust.Status.NEW -> stringResource(R.string.safety_state_new) to MaterialTheme.colorScheme.tertiary
            PersonTrust.Status.CANT_CONFIRM -> stringResource(R.string.trust_anomaly) to MaterialTheme.colorScheme.error
            PersonTrust.Status.NOT_VERIFIED -> stringResource(R.string.safety_state_unverified) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp).testTag("safety-state"))
        if (s.anomaly) Text(stringResource(R.string.trust_anomaly_note), style = MaterialTheme.typography.bodySmall)
        if (s.verified) {
            val names = (if (s.verifiedByMe) listOf(context.getString(R.string.safety_verified_by_you)) else emptyList()) + s.verifiedBy
            Text(stringResource(R.string.safety_verified_by, names.joinToString(", ")), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("safety-verified-by"))
        }
        s.rotatedAt?.let { at ->
            val date = me.raddatz.veyq.mobile.words.Shown.dayOf(at, language())
            Text(stringResource(R.string.trust_rotated, date), style = MaterialTheme.typography.bodySmall)
        }
        if (s.replaced) Text(stringResource(R.string.safety_replaced), style = MaterialTheme.typography.bodySmall)
        if (s.number != null) OutlinedButton(onClick = { comparing = true }, modifier = Modifier.padding(top = 4.dp).testTag("safety-compare-open")) {
            Text(stringResource(R.string.safety_open))
        } else Text(stringResource(R.string.safety_unreadable), style = MaterialTheme.typography.bodySmall)
    }
    val s = shown
    if (comparing && s != null) CompareDialog(trust, s, name, model, onClose = { comparing = false; reads++ })
}

/** *Compare*: their digits and chain code, then a scan of their code or "They match" / "They don't match" — the outcome said. */
@Composable
private fun CompareDialog(trust: PersonTrust, shown: PersonTrust.Shown, name: String, model: AppModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<PersonTrust.Outcome?>(null) }
    var working by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    fun settle(run: suspend () -> PersonTrust.Outcome) {
        working = true
        scope.launch {
            outcome = runCatching { run() }.getOrDefault(PersonTrust.Outcome.FAILED)
            working = false
        }
    }
    val camera = LocalContext.current.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.safety_theirs_dialog_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("safety-compare")) {
                val o = outcome
                if (o != null) {
                    Text(outcomeText(o, name), color = if (o == PersonTrust.Outcome.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("safety-outcome"))
                    return@Column
                }
                Text(stringResource(R.string.safety_theirs_intro, name), style = MaterialTheme.typography.bodyMedium)
                shown.number?.let { Digits(it, "safety-their-number") }
                shown.chainCode?.let {
                    Text(stringResource(R.string.safety_chain_code), style = MaterialTheme.typography.labelLarge)
                    Digits(it, "safety-their-chain-code")
                    Text(stringResource(R.string.safety_chain_code_hint, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.safety_compare_hint, name), style = MaterialTheme.typography.bodySmall)
                if (working) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!shown.anomaly) {
                    if (camera) OutlinedButton(onClick = { scanning = true }, enabled = !working, modifier = Modifier.testTag("safety-scan")) {
                        Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.safety_scan), modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { settle { trust.compareDigits(shown.userId, true) } }, enabled = !working, modifier = Modifier.testTag("safety-they-match")) {
                            Text(stringResource(R.string.safety_they_match))
                        }
                        TextButton(onClick = { settle { trust.compareDigits(shown.userId, false) } }, enabled = !working, modifier = Modifier.testTag("safety-they-differ")) {
                            Text(stringResource(R.string.safety_they_differ), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !working) { Text(stringResource(R.string.ok)) } },
    )
    if (scanning) SafetyScanner(name, model, onClose = { scanning = false }) { text ->
        scanning = false
        settle { trust.compareScan(shown.userId, text) }
    }
}

@Composable
private fun outcomeText(o: PersonTrust.Outcome, name: String): String = when (o) {
    PersonTrust.Outcome.VERIFIED -> stringResource(R.string.safety_verified, name)
    PersonTrust.Outcome.MISMATCH -> stringResource(R.string.safety_mismatch, name)
    PersonTrust.Outcome.FOREIGN -> stringResource(R.string.safety_foreign)
    PersonTrust.Outcome.OUTDATED -> stringResource(R.string.safety_outdated, name)
    PersonTrust.Outcome.MOVED -> stringResource(R.string.safety_moved, name)
    PersonTrust.Outcome.UNANCHORED -> stringResource(R.string.safety_unanchored, name)
    PersonTrust.Outcome.FAILED -> stringResource(R.string.safety_failed)
}

/**
 * *Scan their code*: the camera over a scan the core made to take a `veyqid:` code only (any other is skipped and the
 * camera goes on); the code found is handed to [onFound] for the core's compare. Full screen, not secure: a safety code
 * is no secret.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyScanner(name: String, model: AppModel, onClose: () -> Unit, onFound: (String) -> Unit) {
    val scan = remember { model.safetyScan() }
    DisposableEffect(scan) { onDispose { scan.close() } }
    LaunchedEffect(scan) {
        scan.state.collect {
            when (it) {
                is QrScan.State.Found -> onFound(it.value)
                else -> Unit
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow()
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    title = { Text(stringResource(R.string.safety_scan)) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.signin_cancel)) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    QrCamera(scan, Modifier.fillMaxSize()) { state ->
                        if (state == QrScan.State.Denied || state == QrScan.State.NoCamera) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.safety_scan_error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.safety_scan_hint, name), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}
