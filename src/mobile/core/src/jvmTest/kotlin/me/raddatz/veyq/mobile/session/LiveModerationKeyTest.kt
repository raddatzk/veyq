package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.session.rotation.keyRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * A moderator's *Secure your account* (uc.account.rotate-keys, ADR 0099 § 4, inbox #965/#1426) against a running dev
 * instance, as the OWNER of a throwaway made a moderator (`throwaway:account -- --out <file> --moderator`: appointed,
 * accepted and handed a minted epoch by the Server Owner's acts; a file naming a seeded account FAILS, [Throwaway]).
 * Before: the grants it holds open with its identity and match each epoch's published bundle. Then the keys are replaced
 * through the core (`AccountRotation`, whose staging puts every held epoch re-sealed to the new identity,
 * `ModerationKeyStage`). After: the same epochs are served, every grant a new ciphertext, each opening with the NEW
 * identity to an `M_n` its bundle owns, none with the old one — the old identity's grants are gone.
 *
 * The staging's wiring is load-bearing here: without `moderationKey.put` in `stageRooted` the server refuses the publish
 * (a holder may not lose the key silently), so this case is red (bitten 2026-11-28). Destructive: run alone, on a
 * throwaway of its own (its Secret Key in the file is stale afterwards; `--erase` signs in by password only).
 */
class LiveModerationKeyTest {
    init { me.raddatz.veyq.mobile.TestApplications.installInert() }

    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private fun device() = ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "live moderation device", p)

    @Test
    fun aModeratorsKeyReplacementCarriesEveryEpochToTheNewIdentity() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertEquals("1", t.env["VEYQ_MODERATOR"], "the throwaway is no moderator: create it with --moderator")
        val minted = t.env.getValue("VEYQ_MODERATION_EPOCH").toInt()
        val email = t.email()
        val password = t.env.getValue("VEYQ_PASSWORD")
        val oldKey = t.env.getValue("VEYQ_SECRET_KEY")
        val dir = kotlin.io.path.createTempDirectory("live-moderation").toFile()
        runTest(timeout = 8.minutes) {
            val owner = VeyqAccount.signIn(p, origin, email, password, oldKey, device(), client)
            val suite = owner.suite
            val before = ModerationGrants.read(owner)
            assertTrue(before.any { it.epoch == minted }, "the throwaway holds no grant of epoch $minted: ${before.map { it.epoch }}")
            val oldIdentity = owner.identityCopy()
            val oldKem = suite.xwingKey(oldIdentity.kemSeed)
            ModerationGrants.open(suite, oldKem, before).values.forEach { it.fill(0) }

            val rotation = owner.keyRotation(open = null)
            rotation.open()
            rotation.setNewPassword(false)
            rotation.prove(password, assertNotNull(SecretKeyText.read(p, oldKey)))
            assertEquals(KeyRotation.Step.KIT, rotation.current.step, "the gate refused: ${rotation.current.refusal}")
            val kit = assertNotNull(rotation.kit)
            assertTrue(kit.save(java.io.File(dir, kit.current.fileName).path, "en", 20_000))
            kit.confirm(true)
            val newKey = SecretKeyText.format(p, kit.keyCopy())
            rotation.run()
            assertTrue(rotation.current.done, "the key replacement was not done: ${rotation.current.refusal}")

            val again = VeyqAccount.signIn(p, origin, email, password, newKey, device(), client)
            val after = ModerationGrants.read(again)
            assertEquals(before.map { it.epoch }.toSet(), after.map { it.epoch }.toSet(), "the epochs held moved across the key replacement")
            val oldSeals = before.mapNotNull { it.sealedGrant }.toSet()
            assertTrue(after.none { it.sealedGrant in oldSeals }, "a grant sealed to the old identity is still served")
            val newIdentity = again.identityCopy()
            val opened = ModerationGrants.open(suite, suite.xwingKey(newIdentity.kemSeed), after)
            assertEquals(after.map { it.epoch }.toSet(), opened.keys, "the new identity does not open every epoch")
            opened.values.forEach { it.fill(0) }
            for (g in after) {
                val sealed = assertNotNull(g.sealedGrant)
                val stillOpens = runCatching { suite.openAs(ModerationGrants.context(g.epoch), oldKem, unb64(sealed)).fill(0) }.isSuccess
                if (stillOpens) fail("the old identity still opens epoch ${g.epoch}")
            }
            println("[live] the moderator's epochs ${after.map { it.epoch }} moved to the new identity; the old one opens none")
            oldKem.mlKemDk.fill(0); oldKem.x25519Sk.fill(0); oldIdentity.kemSeed.fill(0); oldIdentity.signSeed.fill(0)
            newIdentity.kemSeed.fill(0); newIdentity.signSeed.fill(0)
            again.removeThisDevice()
            runCatching { owner.removeThisDevice() }
        }
        dir.deleteRecursively()
    }
}
