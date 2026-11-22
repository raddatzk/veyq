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
    }
}
