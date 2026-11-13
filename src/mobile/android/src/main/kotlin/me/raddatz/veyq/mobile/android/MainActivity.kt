package me.raddatz.veyq.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            devLogin(intent) // a recreation (rotation, dark mode) keeps its session
            receive(intent)
        }
        val devScan = DevScan.requested(intent) // a debug build's QR camera harness, no sign-in (DevScan)
        val devSettings = DevSettings.requested(intent) // a debug build's Settings page over a played account (DevSettings)
        val devMemory = DevMemory.requested(intent) // a debug build's Memory page over a played gallery (DevMemory)
        setContent {
            VeyqTheme {
                when {
                    devScan -> DevScan.Screen()
                    devSettings != null -> DevSettings.Screen(devSettings)
                    devMemory -> DevMemory.Screen(model)
                    else -> AppRoot(model)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.attach(this) // the unlock key's prompt is shown over this activity
        model.start()
    }

    override fun onPause() {
        model.attach(null)
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        devLogin(intent)
        receive(intent)
    }

    /** What another app shared, read now (its permission is this intent's) and handed to the model to be placed. */
    private fun receive(intent: android.content.Intent?) {
        ShareIn.from(this, intent)?.let(model::receiveShare)
    }

    private fun devLogin(intent: android.content.Intent?) {
        DevLogin.from(intent)?.let { c ->
            // `--remember` answers the offer with the presence level once the sign-in opened, as a person would
            model.signIn(c.server, c.email, c.password, c.secretKey, then = if (c.remember) suspend {
                model.answerOffer(me.raddatz.veyq.mobile.session.StaySignedIn.PRESENCE)
            } else null)
        }
        DevLogin.unlockPassword(intent)?.let {
            model.devUnlock(it)
            model.devFillPasswordTrust(it)
        }
    }
}
