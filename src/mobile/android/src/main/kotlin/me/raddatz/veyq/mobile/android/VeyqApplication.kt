package me.raddatz.veyq.mobile.android

import android.app.Application
import me.raddatz.veyq.mobile.composition.AppComposition

/**
 * The app's process: the core's applications installed into its registry (`AppComposition.install`) before anything
 * runs — an activity, or a worker the system starts without one (`BackgroundWork`, `OfflineWork`, `UploadWork`).
 */
class VeyqApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppComposition.install()
        // this install's appearance handed to the system again, so its kept copy and the system's never part (ADR 0162)
        me.raddatz.veyq.mobile.android.core.Appearance.restore(this)
    }
}
