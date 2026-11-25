package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.R

/**
 * A TV's PIN pad (ADR 0160 decision 1: six digits, digits only — a remote's number pad): the digits typed show as
 * filled dots, never as digits; the remote's number keys type, its Delete or Backspace takes the last one back, and the
 * on-screen pad does the same by focus and the centre key. The sixth digit hands the PIN to [onPin] and empties the pad.
 * [title] heads it, [hint] and [error] stand under it; while [working] nothing is taken.
 */
@Composable
internal fun TvPinPad(title: String, hint: String? = null, error: String? = null, working: Boolean = false, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun type(d: Char) {
        if (working || pin.length >= PIN_LENGTH) return
        pin += d
        if (pin.length == PIN_LENGTH) {
            val typed = pin
            pin = ""
            onPin(typed)
        }
    }
    fun delete() {
        if (!working) pin = pin.dropLast(1)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        Modifier.widthIn(max = 520.dp).testTag("tv-pin").onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val digit = digitOf(e.key)
            when {
                digit != null -> { type(digit); true }
                e.key == Key.Backspace || e.key == Key.Delete -> { delete(); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // the digits typed so far, as dots: what a screen reader hears is the count, never a digit
        Row(
            Modifier.semantics { contentDescription = "${pin.length}/$PIN_LENGTH" }.testTag("tv-pin-dots"),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (i in 0 until PIN_LENGTH) {
                val filled = i < pin.length
                Box(
                    Modifier.size(18.dp)
                        .background(if (filled) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("tv-pin-error")) }
        // the pad: 1–9, then Delete and 0, as a phone's dial pad lays them out
        for (row in PAD) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (cell in row) {
                    when (cell) {
                        null -> Spacer(Modifier.size(PAD_KEY))
                        DELETE -> Button(onClick = ::delete, modifier = Modifier.size(PAD_KEY).testTag("tv-pin-delete")) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.tv_pin_delete))
                        }
                        else -> Button(
                            onClick = { type(cell) },
                            modifier = Modifier.size(PAD_KEY).testTag("tv-pin-digit-$cell").let { if (cell == '1') it.focusRequester(first) else it },
                        ) { Text(cell.toString(), style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
    }
}

/** The digit a remote's or a keyboard's key types, or null. */
private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** A PIN's length (ADR 0160 decision 1; core's `VeyqAccount.PIN`). */
internal const val PIN_LENGTH = 6

private const val DELETE = '⌫'
private val PAD_KEY = 64.dp
private val PAD: List<List<Char?>> = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf(DELETE, '0', null),
)
