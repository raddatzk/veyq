package me.raddatz.veyq.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.raddatz.veyq.mobile.sharing.DeepLink

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            devLogin(intent) // a recreation (rotation, dark mode) keeps its session
            receive(intent)
            deepLink(intent)
            // a debug build's format matrix of the music's decks, logged (DevAudio)
            if (DevAudio.requested(intent)) DevAudio.run(applicationContext)
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
        deepLink(intent)
    }

    /**
     * A `veyq:` link (ADR 0150 § O): `veyq://link` opens *Open a link* over whatever shows — the sign-in, the unlock or the
     * open tenant —, anything else only brings the app to the front, which the system already did. Nothing of the URI is
     * read but what core's [DeepLink.parse] reads, nothing of it is logged, and it is taken off the activity's intent so
     * nothing later finds it there. A start from the recents list hands the task's first intent again: it opens nothing.
     */
    private fun deepLink(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW || intent.scheme?.equals("veyq", ignoreCase = true) != true) return
        val uri = intent.dataString
        intent.data = null
        if (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 || uri == null) return
        // a field already open keeps what the person typed there
        if (DeepLink.parse(uri) is DeepLink.Link && model.guests.asking.value == null) model.guests.ask()
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
        DevLogin.kitPassword(intent)?.let(model::devSignInWithKit)
        DevLogin.openLink(intent)?.let { link ->
            model.guests.ask()
            model.guests.asking.value?.text(link)
        }
    }
}
