package me.raddatz.veyq.mobile.android

import android.content.Intent
import androidx.compose.runtime.Composable
import me.raddatz.veyq.mobile.android.core.Screen

/** A release build has no QR test harness: the camera is reached only through a caller's screen. */
object DevScan {
    fun requested(@Suppress("UNUSED_PARAMETER") intent: Intent?): Boolean = false

    @Composable
    fun Screen() = Unit
}
