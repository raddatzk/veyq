package me.raddatz.veyq.mobile.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's client window (ADR 0085 decision 5) and THE TRIPWIRE (owner 2026-11-09, inbox #436): no adapter is built
 * before a major 2 is released, and this keeps that from being forgotten. [PLATFORM_MAJOR] is the repository's VERSION
 * (generated from it, VERSION a declared input), so this is red in the commit that moves VERSION to 2 until the app's
 * core carries major 1's adapter and lists it in [ClientWindow.MAJORS] — the web's `shareInstance.test.ts` twin.
 */
class ClientWindowTest {
    @Test
    fun the_window_is_two_majors_back_and_released_majors_only() {
        assertEquals(
            mapOf(0 to emptyList(), 1 to emptyList(), 2 to listOf(1), 3 to listOf(2, 1), 4 to listOf(3, 2)),
            (0..4).associateWith { ClientWindow.olderMajorsInWindow(it) },
        )
    }

    @Test
    fun the_app_speaks_its_own_major_first_and_none_newer() {
        assertEquals(PLATFORM_MAJOR, ClientWindow.MAJORS.first())
        assertTrue(ClientWindow.MAJORS.all { it <= PLATFORM_MAJOR }, "a major newer than VERSION in ${ClientWindow.MAJORS}")
        assertTrue(ClientWindow.speaks(PLATFORM_MAJOR))
        assertTrue(!ClientWindow.speaks(PLATFORM_MAJOR + 1))
    }

    @Test
    fun every_older_major_the_window_holds_has_its_adapter() {
        for (m in ClientWindow.olderMajorsInWindow(PLATFORM_MAJOR)) {
            assertTrue(
                m in ClientWindow.MAJORS,
                "VERSION is at major $PLATFORM_MAJOR, whose window (ADR 0085 decision 5) still holds major $m: build the app " +
                    "core's major-$m adapter (its sign-in, session and every application's wire as major $m serves them), test " +
                    "it against a major-$m server image, and add $m to ClientWindow.MAJORS",
            )
        }
    }
}
