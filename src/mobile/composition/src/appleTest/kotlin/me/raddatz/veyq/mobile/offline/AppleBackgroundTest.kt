package me.raddatz.veyq.mobile.offline

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.AppleBackground
import me.raddatz.veyq.mobile.session.AppleDeviceKeys
import me.raddatz.veyq.mobile.session.BackgroundRun
import me.raddatz.veyq.mobile.session.DeviceAuthFailed
import me.raddatz.veyq.mobile.session.KeychainBridge
import me.raddatz.veyq.mobile.session.KeychainRead
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.VeyqAccount
import platform.Foundation.NSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS app-refresh task's side of a run in the background (ADR 0153 decision 8): the
 * expiration handler's cancel stops core's run between writes, the account it opened still closed with its keys zeroed and
 * `done` called once with no report; a run left alone reports its outcome; and the Keychain locked
 * (`errSecInteractionNotAllowed`) is a closed key, never a missing one — no new unlock key stored over it, no kept session
 * taken for gone.
 *
 * Bites (each one edit, seen red, then restored): `AppleBackground.Run.cancel` doing nothing → [the_expiration_cancels_the_run_between_writes_and_the_keys_are_zeroed]
 * red (the second write sent, a report); `AppleDeviceKeys.unlockKey` without its `read.locked` check →
 * [a_locked_keychain_is_a_closed_key_never_a_missing_one] red (a new login key stored); `AppleDeviceKeys.read` without its
 * throw → the same test red (the refresh token read as none).
 */
class AppleBackgroundTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private fun HttpRequestData.body(): String = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun isCreate(r: HttpRequestData) = r.method == HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))

    @Test
    fun the_expiration_cancels_the_run_between_writes_and_the_keys_are_zeroed() = runBlocking {
        val t = PlayedTenant(backgroundRuns = true).also { played += it }
        val s = t.scope("s1")
        t.remember()
        val app = t.resume()
        val first = assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "First", 1))
        assertNotNull(QueuedNodes.folder(app, "t1", s.id, null, "Second", 2))
        app.retire()

        val handle = CompletableDeferred<AppleBackground.Run>()
        val created = mutableListOf<String>()
        // the system takes the time back while the first write is answered
        t.route = { r ->
            if (!isCreate(r)) null else {
                created += Json.parseToJsonElement(r.body()).jsonObject["nodeId"]!!.jsonPrimitive.content
                runBlocking { handle.await() }.cancel()
                201 to """{"nodeId":"${created.last()}"}"""
            }
        }
        val opened = mutableListOf<VeyqAccount>()
        val run = BackgroundRun(t.p, t.device, t.client(), AppKinds.pins).also { it.onOpened = { a -> opened += a } }
        val done = CompletableDeferred<String?>()
        handle.complete(AppleBackground.start(run, deadline = Long.MAX_VALUE) { done.complete(it) })

        assertNull(withTimeout(30_000) { done.await() }, "a cancelled run reported as finished")
        assertEquals(listOf(first.id), created, "the run went on after the system took its time back")
        val a = opened.single()
        assertNull(a.masterKeyCopy(), "the cancelled run's account is still open")
        assertTrue(a.masterKey.all { it == 0.toByte() }, "the Master Key outlived the cancelled run")
        Unit
    }

    @Test
    fun a_run_left_alone_reports_its_outcome() = runBlocking {
        val t = PlayedTenant(backgroundRuns = true).also { played += it }
        t.scope("s1")
        t.remember()
        val done = CompletableDeferred<String?>()
        AppleBackground.start(BackgroundRun(t.p, t.device, t.client(), AppKinds.pins), deadline = Long.MAX_VALUE) { done.complete(it) }
        assertEquals("DONE, sent 0", withTimeout(30_000) { done.await() })
    }

    /** A Keychain that holds every item but answers as a locked device does: `errSecInteractionNotAllowed`. */
    private class LockedKeychain : KeychainBridge {
        val stored = mutableListOf<String>()
        override fun store(account: String, data: NSData, protected: Boolean, passcode: Boolean): Boolean = true.also { stored += account }
        override fun load(account: String, prompt: String, protected: Boolean) = KeychainRead(null, cancelled = false, locked = true)
        override fun remove(account: String) {}
    }

    @Test
    fun a_locked_keychain_is_a_closed_key_never_a_missing_one() = runBlocking {
        val keychain = LockedKeychain()
        val keys = AppleDeviceKeys(keychain, "prompt")
        assertFailsWith<DeviceAuthFailed> { keys.openUnlockable(ByteArray(12), ByteArray(32), ByteArray(0), StaySignedIn.LOGIN) }
        assertFailsWith<DeviceAuthFailed> { keys.sealUnlockable(ByteArray(32), ByteArray(0), StaySignedIn.LOGIN) }
        assertEquals(emptyList(), keychain.stored, "a new unlock key was stored over the locked one")
        // the kept session's token and the bind key: there, unreadable now — never "none", which forgets the trust
        assertFailsWith<DeviceAuthFailed> { keys.secret("veyq.refresh.1") }
        assertFailsWith<DeviceAuthFailed> { keys.bindHmac("veyq.device.1", ByteArray(8)) }
        Unit
    }
}
