package me.raddatz.veyq.mobile.android

import android.content.Intent
import androidx.compose.runtime.Composable

/** A release build has no Settings harness: the page is reached only from the drawer of a signed-in account. */
object DevSettings {
    fun requested(@Suppress("UNUSED_PARAMETER") intent: Intent?): String? = null

    @Composable
    fun Screen(@Suppress("UNUSED_PARAMETER") variant: String) = Unit
}
