package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.account.KeyIntake
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.account.KitImportTest
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.Kit
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in's Secret Key control in the flows that ask for the key (ADR 0151 decisions 1 and 2): each flow owns one
 * [KeyIntake] — a pasted `veyqkit:` token read as its kit's key, a kit file or scan held by the intake —, a kit of another
 * account warns and proceeds, and the held key is zeroed once a call took it to success, and when the flow closes.
 */
class FlowKeyIntakeTest {
    private val p = testPrimitives
    private val next = "a much better password"

    private fun token(a: PlayedCredentials, email: String = a.email, server: String? = null) = EmergencyKit.write(p, email, a.secretKey, server)

    private fun zeroed(bytes: ByteArray) = bytes.all { it == 0.toByte() }

    /** The rotation's engine: its gate passes for [right] alone. */
    private class Engine(private val right: ByteArray) : KeyRotation.Engine {
        override suspend fun holdsModerationKey() = false
        override suspend fun prove(password: String, secretKey: ByteArray) = if (secretKey.contentEquals(right)) null else ColdCredential.Refusal.REJECTED
        override suspend fun resume(password: String, secretKey: ByteArray) = emptyList<RotationScopeState>()
        override suspend fun publish(password: String, secretKey: ByteArray, newSecretKey: ByteArray, newPassword: String?) =
            KeyRotation.Published(false, emptyList(), emptyList())
    }

    private suspend fun rotation(a: PlayedCredentials) = KeyRotation(Engine(a.secretKey), p, a.email, a.server).also {
        it.open()
        it.setNewPassword(false)
    }

    /**
     * R4: a `veyqkit:` token pasted into the Secret Key field is its kit's key, as at the sign-in (the web's paste swap).
     * Bite (a): `typed` reading the bare key first (`SecretKeyText`) → red here.
     */
    @Test
    fun aPastedKitTokenOpensEachFlow() = runTest {
        val a = PlayedCredentials(p)
        val change = PasswordChange(a, p)
        change.run(a.password, next, next, token(a))
        assertTrue(change.current.done, "the password change refused the pasted kit: ${change.current.refusal}")

        val address = AddressChange(a, p)
        address.request("new@b.example", a.password, token(a))
        assertEquals("new@b.example", address.current.sentTo, "the address change refused the pasted kit: ${address.current.refusal}")

        a.taken = true
        assertNotNull(AddressChange(a, p).kit(a.password, token(a)), "the address's kit refused the pasted kit")

        val rotation = rotation(a)
        rotation.prove(a.password, token(a))
        assertEquals(KeyRotation.Step.KIT, rotation.current.step, "the rotation's gate refused the pasted kit: ${rotation.current.refusal}")
        rotation.close()

        // a bare key still reads, and a text that is neither is a wrong key
        val typed = PasswordChange(a, p)
        typed.run(a.password, next, next, SecretKeyText.format(p, a.secretKey))
        assertTrue(typed.current.done)
        val wrong = PasswordChange(a, p)
        wrong.run(a.password, next, next, "veyqkit:not-a-kit")
        assertEquals(Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL, wrong.current.refusal)
    }

    @Test
    fun aKitFileReadInAFlowIsItsKeyAndTheStateCarriesNone() = runTest {
        val a = PlayedCredentials(p)
        val change = PasswordChange(a, p)
        val file = KitImportTest.pdf(KitImportTest.info("(${token(a)})"))
        val shown = change.intake.read(file, KitImportTest.PlayedPictures())
        assertEquals(KeyIntake.HeldKit(a.email, null, KitImport.ServerFrom.NONE), shown)
        assertTrue(zeroed(file), "the file's bytes outlived the read")
        val json = Json.encodeToString(KeyIntake.IntakeState.serializer(), change.intake.current)
        for (form in listOf(hex(a.secretKey), SecretKeyText.format(p, a.secretKey), "veyqkit:")) assertFalse(form in json, "the intake's state carries the key: $json")

        // a file with no kit says so and keeps the kit held before
        assertNull(change.intake.read("no kit here".encodeToByteArray(), KitImportTest.PlayedPictures(pages = null)))
        assertEquals(Msg.SIGNIN_KIT_UNREADABLE, change.intake.current.problem)
        assertTrue(change.intake.holds)

        change.run(a.password, next, next, "")
        assertTrue(change.current.done, "the held kit's key did not change the password: ${change.current.refusal}")
        assertNull(change.intake.current.kit, "the spent kit still shows")
    }

    /** Decision 2: a kit of another address warns, one of another server informs; neither blocks nor clears the key. */
    @Test
    fun anotherAccountsKitWarnsAndProceeds() = runTest {
        val a = PlayedCredentials(p)
        val change = PasswordChange(a, p)
        val kit = EmergencyKit.read(p, token(a, "Someone@Else.example", "https://other.example"))!!
        val shown = change.intake.take(KitImport.Found(kit, KitImport.ServerFrom.TOKEN))
        assertEquals(a.email, shown.otherAccount)
        assertEquals("http://a.example", shown.otherServer)
        assertTrue(change.intake.holds)
        change.run(a.password, next, next, "")
        assertTrue(change.current.done, "another account's kit blocked the change: ${change.current.refusal}")

        // the same address in another case is no other account
        val same = PasswordChange(a, p).intake.take(KitImport.Found(EmergencyKit.read(p, token(a, " P@A.EXAMPLE "))!!, KitImport.ServerFrom.NONE))
        assertNull(same.otherAccount)
        assertNull(same.otherServer)
    }

    /**
     * The held key is zeroed once a call took it to success, kept for the retry after a refusal (as the sign-in keeps
     * it), and zeroed when the flow closes. Bite (b): the zeroing after the call removed → red here.
     */
    @Test
    fun theHeldKeyIsZeroedAfterTheCallAndAtClose() = runTest {
        val a = PlayedCredentials(p)
        fun held(intake: KeyIntake): ByteArray = a.secretKeyCopy().also { intake.take(KitImport.Found(Kit(a.email, it, null), KitImport.ServerFrom.NONE)) }

        val change = PasswordChange(a, p)
        val key = held(change.intake)
        change.run("not the password", next, next, "")
        assertEquals(Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL, change.current.refusal)
        assertFalse(zeroed(key), "a refusal let the held kit go")
        change.run(a.password, next, next, "")
        assertTrue(change.current.done)
        assertTrue(zeroed(key), "the held key outlived the change that took it")

        val closing = PasswordChange(a, p)
        val closed = held(closing.intake)
        closing.close()
        assertTrue(zeroed(closed), "the held key outlived the password change's close")

        val address = AddressChange(a, p)
        val sent = held(address.intake)
        address.request("new@b.example", a.password, "")
        assertEquals("new@b.example", address.current.sentTo)
        assertTrue(zeroed(sent), "the held key outlived the request that took it")
        val left = AddressChange(a, p)
        val leftKey = held(left.intake)
        left.close()
        assertTrue(zeroed(leftKey), "the held key outlived the address change's close")

        val rotation = rotation(a)
        val gate = held(rotation.intake)
        rotation.prove(a.password, "")
        assertEquals(KeyRotation.Step.KIT, rotation.current.step)
        assertTrue(zeroed(gate), "the held key outlived the gate that took it")
        rotation.close()
        val abandoned = rotation(a)
        val abandonedKey = held(abandoned.intake)
        abandoned.close()
        assertTrue(zeroed(abandonedKey), "the held key outlived the rotation's close")
    }
}
