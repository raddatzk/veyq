package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A guest's session in the real Credential Locker (ADR 0150 decision 3): `run.py test` runs the binary over ssh, in
 * session 0, whose user's vault WinRT's PasswordVault opens there too (measured 2026-11-19). Each store's credentials
 * carry a namespace of their own, and each case removes what it wrote.
 */
class WindowsGuestSessionsTest {
    private fun namespace() = "veyq-test.${hex(LibsodiumPrimitives.randomBytes(8))}/"
    private val names = GuestNames(LibsodiumPrimitives)
    private val session = GuestSessionJson.encode(GuestSession("identity-1", "token-1", "Ben", uploaderKey = "a2V5"))

    @Test
    fun aSessionIsKeptReadByANewStartAndRemoved() {
        val ns = namespace()
        val name = names.session("https://veyq.example", "share-1")
        WindowsGuestSessions(ns).write(name, session)
        // what a new start reads: another object over the same vault
        assertEquals(session, WindowsGuestSessions(ns).read(name))
        assertEquals("Ben", GuestSessionJson.decode(WindowsGuestSessions(ns).read(name))?.displayName)
        WindowsGuestSessions(ns).remove(name)
        assertNull(WindowsGuestSessions(ns).read(name))
    }

    @Test
    fun aSecondWriteReplacesTheFirstAndAnUnusualNameAndALongValueComeBackWhole() {
        val ns = namespace()
        val name = names.session("https://veyq.example", "share-2")
        WindowsGuestSessions(ns).write(name, "first")
        // longer than the first read's buffer, and beyond the Basic Multilingual Plane: read again at its length
        val long = "Grüße 😀 " + "x".repeat(WindowsGuestSessions.CAPACITY + 10)
        WindowsGuestSessions(ns).write(name, long)
        assertEquals(long, WindowsGuestSessions(ns).read(name))
        WindowsGuestSessions(ns).remove(name)
    }

    @Test
    fun itIsKeptUnderTheHashedNameNeverTheShareId() {
        val ns = namespace()
        val name = names.session("https://veyq.example", "share-3")
        assertFalse(name.contains("share-3"))
        WindowsGuestSessions(ns).write(name, session)
        assertNull(WindowsGuestSessions(ns).read("share-3"))
        assertNull(WindowsGuestSessions(namespace()).read(name))
        assertNull(WindowsGuestSessions(ns).read(names.session("https://other.example", "share-3")))
        WindowsGuestSessions(ns).remove(name)
    }

    @Test
    fun aRemoveOrAReadOfNothingIsQuiet() {
        val ns = namespace()
        assertNull(WindowsGuestSessions(ns).read("nothing"))
        WindowsGuestSessions(ns).remove("nothing")
    }
}
