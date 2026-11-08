package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** What the sign-in form starts with: a debug build points at the developer's dev tenant. */
object BuildDefaults {
    const val SERVER = "veyq.localhost:8080"
}
