package me.raddatz.veyq.mobile.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A veyq code is told by its scheme alone; any other code is none. */
class QrSchemesTest {
    @Test
    fun eachFixturesCodeIsItsScheme() {
        assertEquals("veyqpair", QrSchemes.of(QrFixtures.texts.getValue("pair")))
        assertEquals("veyqsignin", QrSchemes.of(QrFixtures.texts.getValue("signin")))
        assertEquals("veyqkit", QrSchemes.of(QrFixtures.texts.getValue("kit")))
    }

    @Test
    fun anyOtherCodeIsNone() {
        listOf("https://veyq.example", "veyqpair", "veyqpair:", "VEYQPAIR:abc", "xveyqkit:abc", "veyqkitx:abc", "").forEach {
            assertNull(QrSchemes.of(it), it)
        }
    }
}
