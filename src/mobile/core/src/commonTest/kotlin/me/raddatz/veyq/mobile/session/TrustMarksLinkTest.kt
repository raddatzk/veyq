package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.PairingMarks
import kotlin.test.Test
import kotlin.test.assertEquals

/** The marks a link hands over (ADR 0028 Decision 7, ADR 0059): read as the grant carries them, raised on the new device, never lowered. */
class TrustMarksLinkTest {
    private val key = ByteArray(32) { 5 }

    @Test
    fun a_linker_hands_over_its_marks_and_the_new_device_takes_each_maximum() = runTest {
        val store = MemoryDeviceStore()
        val linker = TrustMarks(store, testPrimitives, key)
        linker.raisePins(7)
        linker.raiseRegister(3)
        linker.hold("s1", 4, "A".repeat(43) + "=")
        assertEquals(PairingMarks(7, 3, mapOf("s1" to 4L)), linker.forLink())

        val device = MemoryDeviceStore()
        val joined = TrustMarks(device, testPrimitives, key)
        joined.raiseRegister(5)
        joined.hold("s2", 9, "B".repeat(43) + "=")
        joined.raise(PairingMarks(7, 3, mapOf("s1" to 4L, "s2" to 2L, "s3" to 0L)))
        assertEquals(7, joined.pins())
        assertEquals(5, joined.register(), "a handed-over mark lowered this device's")
        assertEquals(4, joined.floor("s1"))
        assertEquals(9, joined.floor("s2"), "a handed-over floor lowered this device's")
        assertEquals(0, joined.floor("s3"))
        assertEquals(HeldEpochKey(9, "B".repeat(43) + "="), joined.held("s2"), "the device's own held key was lost")
    }
}
