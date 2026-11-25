package me.raddatz.veyq.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.TvRoot
import me.raddatz.veyq.mobile.android.core.VeyqTheme

/**
 * The veyq app on Android TV and Google TV (ADR 0159 decision 1): the same APK and the same model as the phone's
 * [MainActivity], started from the TV's launcher (`LEANBACK_LAUNCHER`, its banner the brand's), drawn with Compose for TV
 * ([TvRoot]). It feeds the core's idle lock (ADR 0160 decision 6): every key the remote sends is input, and an open
 * account locks when the app comes back from the background and when [AppModel.idle] says it is due — asked once a
 * [IDLE_POLL_MS], while the activity is started.
 */
class TvActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Applications.register() // core's kinds and each application's UI into the core UI's registry, before anything is drawn
        setContent { VeyqTheme { TvRoot(model) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(IDLE_POLL_MS)
                    if (model.screen.value is Screen.Signed && model.idle.due()) model.lock()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // back from the background: the open account locks (ADR 0160 decision 6)
        if (model.idle.foreground()) model.lock()
    }

    override fun onStop() {
        model.idle.background()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        model.attach(this)
        model.start()
    }

    override fun onPause() {
        model.attach(null)
        super.onPause()
    }

    /** Every key the remote sends — the platform calls this before it dispatches one — is the person being there (core's `IdleLock.input`). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        model.idle.input()
    }

    private companion object {
        /** How often the idle lock is asked: its rule is minutes (15, owner 2026-11-25), so a minute is near enough. */
        const val IDLE_POLL_MS = 60_000L
    }
}
