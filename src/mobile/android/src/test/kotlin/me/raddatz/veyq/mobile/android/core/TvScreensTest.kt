package me.raddatz.veyq.mobile.android.core

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import me.raddatz.veyq.mobile.session.SessionModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A TV's way in (ADR 0159 decision 4, ADR 0160 decision 1), held without a TV: the PIN pad hands over exactly six digits
 * — not before the sixth, Delete taking the last one back — and empties itself; the picker draws a tile for every account
 * kept here, and the tile picked names its own account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w1280dp-h720dp-television")
class TvScreensTest {
    @get:Rule val compose = createComposeRule()

    /** A press as the remote makes it: Compose for TV's surfaces take the click action (the centre key), not a touch. */
    private fun press(vararg tags: String) = tags.forEach { compose.onNodeWithTag(it).performSemanticsAction(SemanticsActions.OnClick) }

    @Test
    fun thePinPadHandsOverSixDigitsAndEmptiesItself() {
        val pins = mutableListOf<String>()
        compose.setContent { VeyqTheme { TvTheme { TvPinPad("PIN") { pins += it } } } }
        press("tv-pin-digit-1", "tv-pin-digit-2", "tv-pin-delete", "tv-pin-digit-3", "tv-pin-digit-4", "tv-pin-digit-5", "tv-pin-digit-6")
        assertEquals("five digits are no PIN yet", emptyList<String>(), pins)
        press("tv-pin-digit-7")
        assertEquals(listOf("134567"), pins)
        // the pad is empty again: the next six make the next PIN, nothing of the last one carried over
        press("tv-pin-digit-0", "tv-pin-digit-0", "tv-pin-digit-0", "tv-pin-digit-0", "tv-pin-digit-0", "tv-pin-digit-9")
        assertEquals(listOf("134567", "000009"), pins)
    }

    @Test
    fun thePickerDrawsEveryAccountAndPicksTheOneChosen() {
        val tiles = listOf(
            SessionModel.PickerTile("veyq.example#u1", "Ada", "veyq.example"),
            SessionModel.PickerTile("veyq.example#u2", "Grace", "veyq.example"),
            SessionModel.PickerTile("other.example#u3", "Linus", "other.example"),
        )
        val picked = mutableListOf<String>()
        var added = 0
        compose.setContent { VeyqTheme { TvTheme { TvPickerPage(Screen.Picker(tiles), onPick = { picked += it }, onAdd = { added++ }) } } }
        compose.onAllNodesWithTag("tv-picker-tile").assertCountEquals(3)
        compose.onAllNodesWithTag("tv-picker-tile")[1].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("veyq.example#u2"), picked)
        press("tv-picker-add")
        assertEquals(1, added)
    }
}
