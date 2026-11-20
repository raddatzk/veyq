package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
