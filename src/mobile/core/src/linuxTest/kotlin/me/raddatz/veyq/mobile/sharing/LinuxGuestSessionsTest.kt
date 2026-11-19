package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.SecretService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull


/**
 * A guest's session in the real Secret Service (ADR 0150 decision 3): `run.py test` runs the binary under its own session
 * bus with gnome-keyring-daemon, as `LinuxDeviceKeysTest` does. Each store's items carry a namespace of their own.
 */
class LinuxGuestSessionsTest {
    private fun namespace() = "test.${hex(LibsodiumPrimitives.randomBytes(8))}/"
    private fun service() = SecretService.system ?: error("no libsecret: run through `linux/run.py test`")
    private val names = GuestNames(LibsodiumPrimitives)
    private val session = GuestSessionJson.encode(GuestSession("identity-1", "token-1", "Ben", uploaderKey = "a2V5"))

    @Test
    fun aSessionIsKeptReadByANewStartAndRemoved() {
        val ns = namespace()
        val name = names.session("https://veyq.example", "share-1")
        LinuxGuestSessions(service(), ns).write(name, session)
        // what a new start reads: another object over the same items
        assertEquals(session, LinuxGuestSessions(service(), ns).read(name))
        assertEquals("Ben", GuestSessionJson.decode(LinuxGuestSessions(service(), ns).read(name))?.displayName)
        LinuxGuestSessions(service(), ns).remove(name)
        assertNull(LinuxGuestSessions(service(), ns).read(name))
    }

    @Test
    fun itIsAnItemOfTheSecretServiceUnderTheHashedNameNeverTheShareId() {
        val ns = namespace()
        val name = names.session("https://veyq.example", "share-2")
        assertFalse(name.contains("share-2"))
        LinuxGuestSessions(service(), ns).write(name, session)
        // the item itself, as `secret-tool search alias <alias>` finds it: the device keys' schema, alias `guest/<name>`
        assertEquals(session, service().lookup(ns + "guest/" + name))
        assertNull(service().lookup(ns + "guest/share-2"))
        LinuxGuestSessions(service(), ns).remove(name)
    }

    @Test
    fun anotherNamespaceAndAnotherLinkFindNothing() {
        val ns = namespace()
        val a = names.session("https://veyq.example", "share-3")
        LinuxGuestSessions(service(), ns).write(a, session)
        assertNull(LinuxGuestSessions(service(), namespace()).read(a))
        assertNull(LinuxGuestSessions(service(), ns).read(names.session("https://other.example", "share-3")))
        LinuxGuestSessions(service(), ns).remove(a)
    }

    @Test
    fun withoutLibsecretAWriteThrowsAReadFindsNothingAndARemoveDoesNothing() {
        val absent = LinuxGuestSessions(SecretService.load("libveyq-absent-secret.so.0"), "absent/")
        val e = assertFailsWith<GuestSessionNotKept> { absent.write("n", session) }
        assertFalse(e.message!!.contains("token-1"), "the refusal carried the session")
        assertNull(absent.read("n"))
        absent.remove("n")
    }
}
