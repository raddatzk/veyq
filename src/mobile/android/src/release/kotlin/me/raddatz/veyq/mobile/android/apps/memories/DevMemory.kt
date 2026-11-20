package me.raddatz.veyq.mobile.android.apps.memories

import android.content.Intent
import androidx.compose.runtime.Composable
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.Screen

/** A release build has no Memory harness: a Memory's page is reached only from a signed-in account. */
object DevMemory {
    fun requested(@Suppress("UNUSED_PARAMETER") intent: Intent?): Boolean = false

    @Composable
    fun Screen(@Suppress("UNUSED_PARAMETER") model: AppModel) = Unit
}
