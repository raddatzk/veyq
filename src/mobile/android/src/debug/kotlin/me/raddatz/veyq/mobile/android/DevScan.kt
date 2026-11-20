package me.raddatz.veyq.mobile.android

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.raddatz.veyq.mobile.android.core.QrCamera
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.qrDecodeTiming
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.qr.QrFrames
import me.raddatz.veyq.mobile.qr.QrScan

/**
 * Debug builds only — the QR camera's test harness (the QR CR, phase 4), reached without a sign-in: launched with the
 * extra `veyq.devScan=1`, the app shows [QrCamera] alone over a scan that accepts any veyq code by its scheme
 * (`veyqpair`, `veyqsignin`, `veyqkit`) and nothing else. Its states and each decode's time go to logcat (`veyq-scan`);
 * the scheme is all that leaves the core, never the code's text. On an emulator the camera is the virtual scene, a
 * fixture code on its wall as a poster (`emulator -virtualscene-poster wall=<png>`). A release build has no such door.
 */
object DevScan {
    fun requested(intent: Intent?): Boolean = intent?.extras?.getString("veyq.devScan") == "1"

    @Composable
    fun Screen() {
        val scan = remember {
            QrScan(
                accepts = { text -> SCHEMES.firstOrNull { text.startsWith("$it:") } },
                decode = QrFrames.timedDecode(::qrDecodeTiming),
            )
        }
        DisposableEffect(scan) {
            val watch = scan.watch { Log.i("veyq-scan", "state ${it.logged()}") }
            onDispose {
                watch.stop()
                scan.close()
            }
        }
        Surface(Modifier.fillMaxSize()) {
            QrCamera(scan, Modifier.fillMaxSize()) { state ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (state) {
                        is QrScan.State.Found -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.CheckCircle, null)
                            Text(state.value, style = MaterialTheme.typography.titleMedium)
                        }
                        QrScan.State.NoCamera -> Icon(Icons.Outlined.NoPhotography, null)
                        QrScan.State.Denied -> Icon(Icons.Outlined.Block, null)
                        else -> Unit
                    }
                }
            }
        }
    }

    private val SCHEMES = listOf("veyqpair", "veyqsignin", "veyqkit")

    private fun QrScan.State<String>.logged() = when (this) {
        is QrScan.State.Found -> "Found($value)"
        else -> toString()
    }
}
