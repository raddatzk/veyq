package me.raddatz.veyq.mobile.android

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import me.raddatz.veyq.mobile.qr.QrScan

/**
 * The sign-in's "Scan Emergency Kit": a full-screen dialog (Material 3's for a task of its own) over [QrCamera], whose
 * scan the core made with the kit's parser as its `accepts` (`SessionModel.scanKit`). A code that is not a kit is
 * skipped by the core and the camera goes on; a kit is taken by the core (the form fills from the gate) and the dialog
 * closes ([onClose]). Where the device turns out to have no camera, [onNoCamera] lets the sign-in drop its entry and the
 * dialog closes; a refused camera is said here, the kit's file and the typed key still offered on the form beneath. The
 * scan closes with the dialog. Its states go to logcat (`veyq-scan`): where the found kit's server came from, never a
 * code's text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitScanner(scan: QrScan<*>, onClose: () -> Unit, onNoCamera: () -> Unit) {
    DisposableEffect(scan) {
        val watch = scan.watch { Log.i("veyq-scan", "kit scan: state $it") }
        onDispose {
            watch.stop()
            scan.close()
        }
    }
    LaunchedEffect(scan) {
        scan.state.collect {
            when (it) {
                is QrScan.State.Found -> onClose()
                QrScan.State.NoCamera -> {
                    onNoCamera()
                    onClose()
                }
                else -> Unit
            }
        }
    }
    // secure: the camera shows the kit's code — the Secret Key —, so no screenshot and no recents thumbnail holds a frame of it
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.signin_kit_scan)) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.signin_cancel)) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    QrCamera(scan, Modifier.fillMaxSize()) { state ->
                        if (state == QrScan.State.Denied) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.signin_kit_scan_denied),
                                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.signin_kit_scan_hint),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}
