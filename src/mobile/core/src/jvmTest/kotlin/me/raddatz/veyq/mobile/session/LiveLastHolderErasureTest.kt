package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDelivery
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The last holder's *Erase account* (uc.account.erase-account A8, ADR 0101 decision 9, ADR 0099 § 5; inbox #1426) against a
 * running dev instance, as the moderator of a throwaway made the LAST holder of the moderation key
 * (`throwaway:account -- --out <file> --last-holder`: the Server Owner's own client delivered it every older epoch, then
 * the Server Owner was removed from the roster; a file naming a seeded account FAILS, [Throwaway]).
 *
 * Through the core's [AccountErasure] over the account's real door: the preview says the key goes to the Server Owner
 * first ([AccountErasure.State.handsOver]) and asks for every live epoch. Two hand-overs the server must refuse are sent
 * first, each through the real door with one thing changed after the core sealed it — an epoch left out
 * (`moderation_delivery_required`) and the grants named for another identity (`identity_changed`) —; each is said
 * ([Msg.ERASURE_ERROR_KEY_DELIVERY], [Msg.ERASURE_ERROR_KEY_IDENTITY_CHANGED]) and erases nothing: the account still
 * signs in and is still the last holder. Then the erasure as the app runs it: erased, the account no longer signs in.
 *
 * That the Server Owner holds every epoch after — reappointed by the hand-over, each grant opening to its epoch's bundle —
 * is read by the Server Owner's own client, which this case never signs in as: the file's `throwaway:account -- --erase`
 * is red unless it does (its `--status` reads the same). Destructive: run alone, on a `--last-holder` throwaway of its own.
 */
class LiveLastHolderErasureTest {
    init { me.raddatz.veyq.mobile.TestApplications.installInert() }

    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private fun device() = ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "live last holder device", p)

    /** The account's real door with the hand-over [tamper]ed after the core sealed it. */
    private fun tampered(account: VeyqAccount, tamper: (me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDeliveryUpload) -> me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDeliveryUpload): CredentialDoor {
        val real = AccountCredentialDoor(account)
        return object : CredentialDoor by real {
            override suspend fun sealForServerOwner(delivery: ServerOwnerKeyDelivery) = tamper(real.sealForServerOwner(delivery))
        }
    }

    @Test
    fun theLastHoldersErasureHandsEveryEpochToTheServerOwner() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertEquals("1", t.env["VEYQ_LAST_HOLDER"], "the throwaway is no last holder: create it with --last-holder")
        val who = if (t.env["VEYQ_MODERATOR_ACCOUNT"] == "member") "VEYQ_USER2" else "VEYQ"
        val email = t.email(who)
        val password = t.env.getValue("${who}_PASSWORD")
        val key = t.env.getValue("${who}_SECRET_KEY")
        runTest(timeout = 8.minutes) {
            val account = VeyqAccount.signIn(p, origin, email, password, key, device(), client)
            val live = ModerationGrants.read(account).map { it.epoch }.sorted()
            assertTrue(live.isNotEmpty(), "the throwaway holds no epoch")

            val leftOut = AccountErasure(tampered(account) { it.copy(grants = it.grants.drop(1)) }, p).also { it.load() }
            assertTrue(leftOut.current.handsOver, "the last holder's preview asks for no hand-over")
            leftOut.erase(password)
            assertEquals(Msg.ERASURE_ERROR_KEY_DELIVERY, leftOut.current.refusal, "a hand-over missing an epoch was not refused as such")
            assertFalse(leftOut.current.erased)

            // a well-formed identity that is not the Server Owner's
            val stranger = b64(me.raddatz.veyq.mobile.crypto.IdentityPublic(account.suite.xwingKey(p.randomBytes(32)).publicKey, p.randomBytes(32)).serialize())
            val elsewhere = AccountErasure(tampered(account) { it.copy(identityPublicKey = stranger) }, p).also { it.load() }
            elsewhere.erase(password)
            assertEquals(Msg.ERASURE_ERROR_KEY_IDENTITY_CHANGED, elsewhere.current.refusal, "a hand-over named for another identity was not refused as such")
            assertFalse(elsewhere.current.erased)

            // Nothing of either refusal stuck: still the account, still the last holder of the same epochs.
            val still = VeyqAccount.signIn(p, origin, email, password, key, device(), client)
            assertEquals(live, ModerationGrants.read(still).map { it.epoch }.sorted(), "a refused erasure moved the epochs held")
            still.removeThisDevice()

            val erasure = account.erasure().also { it.load() }
            assertTrue(erasure.current.handsOver, "a refused erasure left the throwaway no last holder")
            erasure.erase(password)
            assertTrue(erasure.current.erased, "the last holder's erasure was refused: ${erasure.current.refusal}")
            val gone = runCatching { VeyqAccount.signIn(p, origin, email, password, key, device(), client) }
            assertTrue(gone.isFailure, "the erased account still signs in")
            println("[live] the last holder of epochs $live erased itself, the hand-over inside; two tampered hand-overs refused first")
        }
    }
}
