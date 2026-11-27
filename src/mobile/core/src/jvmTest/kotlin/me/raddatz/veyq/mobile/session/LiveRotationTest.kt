package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.session.rotation.keyRotation
import me.raddatz.veyq.mobile.session.rotation.finishRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * uc.account.rotate-keys (ADR 0148 decision 7) against a running dev instance, as the OWNER of a throwaway with a member
 * ([Throwaway]; a file naming a seeded account FAILS): the owner verifies the member, replaces the keys through the core
 * — the kit written where "the person chose", "I saved it", the publish, the finish —, every scope reaches `secured`
 * (or `rekeying`), the old Secret Key is refused, the new one signs in to the same spaces, and the owner's verification
 * still counts (re-signed by the new identity). The password is kept, so the throwaway's `--erase` (password only)
 * still signs in. Destructive: run alone, on a throwaway of its own (its Secret Key in the file is stale afterwards).
 */
class LiveRotationTest {
    // the rotation reads the registry (its scope-level re-key, the Master-Key hooks): a throwaway's spaces hold no
    // application's content, so every application inert (in `:core` without it: "AppKinds read before …", inbox #1191)
    init { me.raddatz.veyq.mobile.TestApplications.installInert() }

    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private fun device() = ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "live rotation device", p)

    @Test
    fun theOwnerReplacesTheKeysAndSignsInWithTheNewKit() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val email = t.email()
        val password = t.env.getValue("VEYQ_PASSWORD")
        val oldKey = t.env.getValue("VEYQ_SECRET_KEY")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val dir = kotlin.io.path.createTempDirectory("live-rotation").toFile()
        runTest(timeout = 8.minutes) {
            val owner = VeyqAccount.signIn(p, origin, email, password, oldKey, device(), client)
            val member = VeyqAccount.signIn(p, origin, t.email("VEYQ_USER2"), t.env.getValue("VEYQ_USER2_PASSWORD"), t.env.getValue("VEYQ_USER2_SECRET_KEY"), device(), client)
            val tenant = owner.tenants.first { it.domain == domain }
            val session = owner.open(tenant)
            val spaces = session.spaces().map { it.scopeId }.toSet()
            assertNotNull(session.personTrust.person(member.userId), "the member is not anchored")
            assertEquals(PersonTrust.Outcome.VERIFIED, session.personTrust.compareDigits(member.userId, true))

            val rotation = owner.keyRotation(open = null)
            rotation.open()
            assertEquals(KeyRotation.Step.GATE, rotation.current.step)
            rotation.setNewPassword(false)
            rotation.prove(password, assertNotNull(SecretKeyText.read(p, oldKey)))
            assertEquals(KeyRotation.Step.KIT, rotation.current.step, "the gate refused: ${rotation.current.refusal}")
            val kit = assertNotNull(rotation.kit)
            val file = java.io.File(dir, kit.current.fileName)
            assertTrue(kit.save(file.path, "en", 20_000))
            kit.confirm(true)
            val newKey = SecretKeyText.format(p, kit.keyCopy())
            rotation.run()
            assertTrue(rotation.current.done, "not done: ${rotation.current.refusal}")
            println("[live] scopes after the finish: ${rotation.current.scopes.map { "${it.scopeId.take(8)} ${it.status}" }}")
            val states = owner.finishRotation()
            assertTrue(states.isNotEmpty())
            assertTrue(
                states.all { it.state == RotationScopeState.State.secured || it.state == RotationScopeState.State.rekeying },
                "a scope did not get its new lock: ${states.map { "${it.scopeId}=${it.state}" }}",
            )

            val refused = runCatching { VeyqAccount.signIn(p, origin, email, password, oldKey, device(), client) }.exceptionOrNull()
            assertEquals(SignInFailure.Reason.SECRET_KEY, (refused as? SignInFailure)?.reason, "the old Secret Key still opens the account")
            val again = VeyqAccount.signIn(p, origin, email, password, newKey, device(), client)
            val reopened = again.open(again.tenants.first { it.domain == domain })
            assertEquals(spaces, reopened.spaces().map { it.scopeId }.toSet(), "the new keys do not open every space")
            assertTrue(assertNotNull(reopened.personTrust.person(member.userId)).verifiedByMe, "the owner's verification did not survive the rotation")

            again.removeThisDevice()
            runCatching { owner.removeThisDevice() }
            member.removeThisDevice()
        }
        dir.deleteRecursively()
    }
}
