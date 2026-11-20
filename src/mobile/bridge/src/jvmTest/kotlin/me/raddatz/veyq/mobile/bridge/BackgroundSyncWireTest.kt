@file:OptIn(ExperimentalSerializationApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import me.raddatz.veyq.mobile.session.AccountSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *Sync in the background* is absent from the wire (ADR 0153 decision 8): the desktops
 * schedule no run, so the bridge — every host of which is a desktop — carries neither the switch nor its reason, and no
 * call sets it. The core's page state has both (the phones draw them), so the absence is the bridge's, not an accident.
 *
 * Bite: carry `backgroundSync` in `SettingsShown` → red.
 */
class BackgroundSyncWireTest {
    private val background = Regex("background", RegexOption.IGNORE_CASE)

    @Test
    fun theSwitchIsNeverOnTheWire() {
        val core = AccountSettings.State.serializer().descriptor.elementNames.filter { background.containsMatchIn(it) }
        assertEquals(listOf("backgroundSync", "backgroundSyncMissing"), core, "the core's page no longer holds the switch")
        val schema = Schema(Roots.all)
        val names = schema.types.values.flatMap { t -> listOf(t.serialName) + t.variants.map { it.serialName } + schema.fields(t).map { "${it.owner}.${it.name}" } }
        assertTrue(names.none { background.containsMatchIn(it) }, "the switch is on the wire: ${names.filter { background.containsMatchIn(it) }}")
    }
}
