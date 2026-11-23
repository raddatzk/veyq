package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * uc.account.erase-account (ADR 0148 decision 8) against a running dev instance: the throwaway's MEMBER (`VEYQ_USER2_*`,
 * `throwaway:account -- --with-member`) erased through the core — the preview, the password proof, the erasure —, then
 * refused at sign-in, and this device's records of it gone. Destructive, so it runs as a throwaway only (a file naming
 * a seeded account FAILS, [Throwaway]) and alone, after every case that signs the member in; the owner and the tenant go
 * with the throwaway's `--erase`, which reports the member as already erased.
 */
class LiveErasureTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }

    @Test
    fun theMemberIsErasedRefusedAtSignInAndForgottenByTheDevice() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val email = t.email("VEYQ_USER2")
        val password = t.env.getValue("VEYQ_USER2_PASSWORD")
        val run = LiveRun("VEYQ_USER2", t.file)
        runTest(timeout = 3.minutes) {
            val device = run.device()
            val account = VeyqAccount.signIn(p, origin, email, password, t.env.getValue("VEYQ_USER2_SECRET_KEY"), device, client)
            account.trustThisDevice(password, null)
            assertNotNull(device.trusted(account.key), "the device was not trusted")
            val erasure = account.erasure()
            erasure.load()
            assertTrue(erasure.current.loaded && erasure.current.refusal == null, "the preview: ${erasure.current}")
            assertTrue(!erasure.current.serverOwner && !erasure.current.onTheWeb)
            erasure.erase("not the password")
            assertEquals(me.raddatz.veyq.mobile.words.Msg.ERASURE_ERROR_PASSWORD, erasure.current.refusal)
            erasure.erase(password)
            assertTrue(erasure.current.erased, "not erased: ${erasure.current.refusal}")
            assertNull(device.trusted(account.key), "the device kept the erased account's record")
            assertTrue(device.trusted().isEmpty())
            val refused = runCatching { VeyqAccount.signIn(p, origin, email, password, t.env.getValue("VEYQ_USER2_SECRET_KEY"), run.device(), client) }.exceptionOrNull()
            assertTrue(refused is SignInFailure, "the erased member still signs in")
            println("[live] the throwaway's member was erased: ${(refused as SignInFailure).reason}")
        }
    }
}
