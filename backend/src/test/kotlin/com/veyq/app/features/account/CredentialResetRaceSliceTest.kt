package com.veyq.app.features.account

import com.veyq.app.features.account.keymaterial.KeyMaterialHandler
import com.veyq.app.features.account.login.LoginHandler
import com.veyq.app.features.account.login.SessionFamilyEntity
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.account.pairing.DevicePairingEntity
import com.veyq.app.features.account.pairing.PairingHandler
import com.veyq.app.features.account.pairing.PairingStore
import com.veyq.app.features.account.password.PasswordChangeStore
import com.veyq.app.features.account.recovery.RecoveryStore
import com.veyq.app.features.account.rotation.RewrittenWrap
import com.veyq.app.features.account.rotation.RotationStore
import com.veyq.app.shared.crypto.SecretKeyProof
import com.veyq.app.shared.crypto.TestProofKey
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.AccountStore
import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.identity.WrapMethod
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * A sign-in, a new device wrap and a pairing delegation each race the three operations that end an account's other
 * devices: a password reset, a password change and a key rotation (inbox #297, developer-styleguide §12) — and a
 * delegation races the fourth, a sign-out everywhere else (inbox #316).
 *
 * Each of those cleanups enumerates what it ends — the account's ACTIVE session families, its device wraps, its live
 * pairing channels — and none sees a row another transaction has not committed yet. A writer that read the account
 * before the cleanup committed and inserted after it therefore left behind exactly what the cleanup exists to end: a
 * session signed in with the replaced password, a password-less `DEVICE_KEY` wrap under the account whose password
 * was just reset, or a delegated session (and, against a rotation, a channel still carrying the old Master Key).
 * Read before ADR 0078's READ COMMITTED or after it, the cleanup's selection could not see the row.
 *
 * The rule: **the account row is the serializing parent.** Every cleanup and every one of these writers locks it
 * first, and a writer re-reads under that lock what the cleanup changes — the auth key a sign-in verifies against,
 * the session a wrap or a delegation is made under. Some serial order then explains every outcome: the writer
 * either committed before the cleanup enumerated (and is ended by it), or decides after it (and is refused).
 *
 * Shape: [whileUncommitted] holds the cleanup's writes — the store calls its handler makes, in its order — while the
 * writer runs; then the reverse, the writer held while the cleanup runs. Fresh account per round.
 */
@QuarkusTest
class CredentialResetRaceSliceTest {

    @Inject lateinit var sessions: SessionService
    @Inject lateinit var recoveries: RecoveryStore
    @Inject lateinit var changes: PasswordChangeStore
    @Inject lateinit var rotations: RotationStore
    @Inject lateinit var pairingStore: PairingStore
    @Inject lateinit var pairings: PairingHandler
    @Inject lateinit var logins: LoginHandler
    @Inject lateinit var keyMaterial: KeyMaterialHandler
    @Inject lateinit var accounts: AccountStore

    private enum class Cleanup { RESET, CHANGE, ROTATION }

    /** What a cleanup leaves alive by design: the family of the device performing a change or a rotation. A reset
     *  has no session to spare. */
    private class Account(val id: String, val email: String, val ownToken: String, val ownFamily: String, val otherToken: String)

    @Test
    fun aSignInWithTheReplacedPasswordDoesNotSurviveTheResetOrChangeItRaced() {
        // A rotation keeps the password, so a sign-in after it is legitimate and races nothing it should not.
        for (cleanup in listOf(Cleanup.RESET, Cleanup.CHANGE)) {
            repeat(RACE_ROUNDS) { round ->
                val account = seedAccount()
                val nonce = challenge(account.email)
                val status = whileUncommitted(
                    change = cleanupOf(cleanup, account),
                    overlapping = { verify(account.email, nonce).statusCode() },
                )
                val label = "$cleanup round $round (sign-in answered $status)"
                assertEquals(NEW_AUTH_PUB, authKeyOf(account.id), "$label: the cleanup applied")
                assertEquals(0L, liveFamiliesBeyond(account, cleanup), "$label: a session signed in with the old password survived")
                assertEquals(401, status, "$label: the sign-in verifies against the key the cleanup installed")
            }
        }
        // Control: the same sign-in, raced against nothing, is let in — so the 401 above is the race, not the fixture.
        val account = seedAccount()
        assertEquals(200, verify(account.email, challenge(account.email)).statusCode())
    }

    @Test
    fun aDeviceWrapDoesNotSurviveTheResetChangeOrRotationItRaced() {
        for (cleanup in Cleanup.entries) {
            repeat(RACE_ROUNDS) { round ->
                val account = seedAccount()
                val status = whileUncommitted(
                    change = cleanupOf(cleanup, account),
                    overlapping = { addWrap(account.otherToken) },
                )
                val label = "$cleanup round $round (wrap answered $status)"
                assertEquals(0L, nonSurvivorWraps(account.id), "$label: a device wrap outlived the cleanup")
                assertEquals(401, status, "$label: the wrap's session was ended by the cleanup it waited for")
            }
        }
        // Control: the same wrap, raced against nothing, is stored.
        assertEquals(201, addWrap(seedAccount().otherToken))
    }

    @Test
    fun aPairingDelegationDoesNotSurviveTheResetChangeOrRotationItRaced() {
        for (cleanup in Cleanup.entries) {
            repeat(RACE_ROUNDS) { round ->
                val account = seedAccount()
                val handle = answeredChannel()
                val status = whileUncommitted(
                    change = cleanupOf(cleanup, account),
                    overlapping = { delegate(account.otherToken, handle) },
                )
                val label = "$cleanup round $round (delegation answered $status)"
                assertEquals(0L, liveFamiliesBeyond(account, cleanup), "$label: a delegated session outlived the cleanup")
                if (cleanup == Cleanup.ROTATION) {
                    assertEquals(0L, liveChannelsOf(account.id), "$label: a channel bound under the old Master Key outlived the rotation")
                }
                assertEquals(401, status, "$label: the delegation's session was ended by the cleanup it waited for")
            }
        }
        // Control: the same delegation, raced against nothing, mints.
        assertEquals(200, delegate(seedAccount().otherToken, answeredChannel()))
    }

    @Test
    fun aPairingDelegationDoesNotSurviveASignOutEverywhereElseItRaced() {
        // "Sign out everywhere else" (uc.account.sessions) is the fourth cleanup that ends an account's other devices,
        // and it ends their families by the same enumeration (inbox #316). A device it signs out could delegate while
        // it ran: serially that delegation is either revoked with the rest or refused as a dead session. Unlike a
        // reset it replaces no credential a sign-in could beat it to — the password still signs in a moment later —
        // so the race is the delegation: a session minted by a device that is being signed out.
        repeat(RACE_ROUNDS) { round ->
            val signedOut = seedAccount()
            val handle = answeredChannel()
            val status = whileUncommitted(
                change = { sessions.revokeOtherFamilies(signedOut.id, signedOut.ownToken) },
                overlapping = { delegate(signedOut.otherToken, handle) },
            )
            val label = "round $round (delegation answered $status)"
            assertEquals(0L, liveFamiliesBeyond(signedOut, Cleanup.CHANGE), "$label: a delegated session outlived the sign-out everywhere else")
            assertEquals(401, status, "$label: the delegation's session was ended by the sign-out it waited for")
        }
        // Control: the sign-out alone spares the device that asked for it, so the zeros above are the race's and not a
        // revoke that took everything.
        val alone = seedAccount()
        sessions.revokeOtherFamilies(alone.id, alone.ownToken)
        assertEquals(1L, liveFamiliesBeyond(alone, Cleanup.RESET), "the sign-out everywhere else spares its own session")
    }

    @Test
    fun aSignOutEverywhereElseThatStartsWhileADelegationIsUncommittedStillEndsIt() {
        // The other order: the delegation holds its transaction open, and the sign-out must wait for it and end what it
        // minted rather than enumerate past the uncommitted family. Held as its store calls, in its order:
        // `PairingHandler.delegate` opens a transaction of its own and would commit inside the hold.
        repeat(RACE_ROUNDS) { round ->
            val delegating = seedAccount()
            val held = answeredChannel()
            whileUncommitted(
                change = {
                    accounts.lock(delegating.id)
                    val row = checkNotNull(pairingStore.findByHandleHash(Tokens.hash(held)))
                    check(pairingStore.claimDelegation(row.id, delegating.id)) { "round $round: the held claim" }
                    pairingStore.bindDelegation(row.id, sessions.issueDelegated(delegating.id).familyId)
                },
                overlapping = { sessions.revokeOtherFamilies(delegating.id, delegating.ownToken) },
            )
            assertEquals(0L, liveFamiliesBeyond(delegating, Cleanup.CHANGE), "round $round: the sign-out left the held delegation's session alive")
        }
    }

    @Test
    fun aChangeOrRotationDecidesAgainstWhatTheCleanupItWaitedForWrote() {
        // The cleanups race each other too. Each checks its proof against the account as it reads it — the change its
        // attestation against the auth key, both of them the caller's session — so that read is taken under the
        // account lock: a check read before it passed against the key or the session the held cleanup was replacing.
        repeat(RACE_ROUNDS) { round ->
            // A change attested by the replaced password, after a change installed a new one: serially a 403.
            val changed = seedAccount()
            val afterChange = whileUncommitted(
                change = cleanupOf(Cleanup.CHANGE, changed),
                overlapping = { changePassword(changed.ownToken) },
            )
            assertEquals(NEW_AUTH_PUB, authKeyOf(changed.id), "round $round: a change attested by the replaced password landed ($afterChange)")
            assertEquals(403, afterChange, "round $round: the attestation is checked against the key the change installed")

            // A change by a session the reset revoked: serially a 401.
            val reset = seedAccount()
            val afterReset = whileUncommitted(
                change = cleanupOf(Cleanup.RESET, reset),
                overlapping = { changePassword(reset.ownToken) },
            )
            assertEquals(NEW_AUTH_PUB, authKeyOf(reset.id), "round $round: a change by a revoked session landed ($afterReset)")
            assertEquals(401, afterReset, "round $round: the change's session was ended by the reset it waited for")

            // A rotation by a session the change revoked: serially a 401.
            val rotated = seedAccount()
            val afterRotation = whileUncommitted(
                change = cleanupOf(Cleanup.CHANGE, rotated),
                overlapping = { rotate(rotated.otherToken) },
            )
            assertEquals(TestFixtures.IDENTITY_PUBLIC_KEY, identityOf(rotated.id), "round $round: a rotation by a revoked session landed ($afterRotation)")
            assertEquals(401, afterRotation, "round $round: the rotation's session was ended by the change it waited for")
        }
        // Control: the same change and the same rotation, raced against nothing, apply.
        assertEquals(204, changePassword(seedAccount().ownToken))
        assertEquals(200, rotate(seedAccount().otherToken))
    }

    @Test
    fun aCleanupThatStartsWhileASignInOrAWrapIsUncommittedStillEndsIt() {
        // The other order: the writer holds its transaction open, and the cleanup must wait for it and then end what
        // it wrote — not enumerate past the uncommitted row.
        repeat(RACE_ROUNDS) { round ->
            val account = seedAccount()
            val nonce = challenge(account.email)
            val reset = cleanupOf(Cleanup.RESET, account)
            whileUncommitted(
                change = { checkNotNull(logins.verify(account.email, nonce, sign(nonce))) { "round $round: the held sign-in" } },
                overlapping = { QuarkusTransaction.requiringNew().run { reset() } },
            )
            assertEquals(0L, liveFamiliesBeyond(account, Cleanup.RESET), "round $round: the reset left the held sign-in's session alive")

            val other = seedAccount()
            val change = cleanupOf(Cleanup.CHANGE, other)
            whileUncommitted(
                change = { keyMaterial.addWrap(other.id, other.otherToken, WrapMethod.DEVICE_KEY.name, DEVICE_WRAP, KDF) },
                overlapping = { QuarkusTransaction.requiringNew().run { change() } },
            )
            assertEquals(0L, nonSurvivorWraps(other.id), "round $round: the change left the held wrap behind")
        }
    }

    // --- the cleanups, as the store calls their handlers make inside their one transaction -------------------------

    private fun cleanupOf(cleanup: Cleanup, account: Account): () -> Unit = when (cleanup) {
        Cleanup.RESET -> {
            val tokenId = mintRecoveryToken(account)
            ({
                check(recoveries.applyReset(account.id, account.email, NEW_PW_WRAP, KDF, NEW_AUTH_PUB, tokenId)) { "reset" }
                sessions.revokeAllForUser(account.id)
            })
        }
        Cleanup.CHANGE -> ({
            check(changes.applyChange(account.id, NEW_PW_WRAP, KDF, NEW_AUTH_PUB)) { "change" }
            sessions.revokeOtherFamilies(account.id, account.ownToken)
        })
        Cleanup.ROTATION -> ({
            check(
                rotations.applyRotation(
                    account.id, NEW_IDENTITY_PUB, NEW_WRAPPED_IDENTITY,
                    RewrittenWrap(NEW_PW_WRAP, KDF), RewrittenWrap(NEW_REC_WRAP, KDF),
                ),
            ) { "rotation" }
            pairingStore.supersede(pairingStore.liveChannels(account.id).map { it.id })
            sessions.revokeOtherFamilies(account.id, account.ownToken)
        })
    }

    // --- writers ---------------------------------------------------------------------------------------------------

    private fun challenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge").then().statusCode(200).extract().path("nonce")

    private fun verify(email: String, nonce: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"${sign(nonce)}"}""")
            .`when`().post("/api/login/verify").then().extract()

    private fun addWrap(accessToken: String): Int =
        given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
            .body("""{"method":"DEVICE_KEY","wrappedKey":"$DEVICE_WRAP","kdfParams":"$KDF"}""")
            .`when`().post("/api/account/wraps").then().extract().statusCode()

    /** A password change from the replaced password to [CHANGED_AUTH_PUB], with both halves of its proof valid
     *  against the account as seeded. */
    private fun changePassword(accessToken: String): Int {
        val wrap = SecretKeyProof.Wrap(CHANGED_PW_WRAP, KDF)
        val proof = PROOF_KEY.sign(SecretKeyProof.passwordChangeStatement(OLD_AUTH_PUB, CHANGED_AUTH_PUB, wrap))
        return given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
            .body(
                """{"passwordWrap":{"wrappedKey":"$CHANGED_PW_WRAP","kdfParams":"$KDF"},"authPublicKey":"$CHANGED_AUTH_PUB",
                   "attestationSignature":"${sign(CHANGED_AUTH_PUB)}","secretKeyProofSignature":"$proof"}""",
            )
            .`when`().post("/api/account/password").then().extract().statusCode()
    }

    /** A key rotation from the seeded identity, its proof valid against the account as seeded. */
    private fun rotate(accessToken: String): Int {
        val statement = SecretKeyProof.rotationStatement(
            currentIdentityPublicKey = TestFixtures.IDENTITY_PUBLIC_KEY,
            identityPublicKey = ROTATED_IDENTITY_PUB,
            wrappedIdentityPrivateKey = NEW_WRAPPED_IDENTITY,
            passwordWrap = SecretKeyProof.Wrap(NEW_PW_WRAP, KDF),
            recoveryWrap = SecretKeyProof.Wrap(NEW_REC_WRAP, KDF),
        )
        return given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
            .body(
                """{"identityPublicKey":"$ROTATED_IDENTITY_PUB","wrappedIdentityPrivateKey":"$NEW_WRAPPED_IDENTITY",
                   "passwordWrap":{"wrappedKey":"$NEW_PW_WRAP","kdfParams":"$KDF"},
                   "recoveryWrap":{"wrappedKey":"$NEW_REC_WRAP","kdfParams":"$KDF"},
                   "secretKeyProofSignature":"${PROOF_KEY.sign(statement)}",
                   "identityPins":{"blob":"$DOC","version":0},"deviceRegister":{"blob":"$DOC","version":0}}""",
            )
            .`when`().post("/api/account/rotation").then().extract().statusCode()
    }

    private fun delegate(accessToken: String, handle: String): Int =
        given().header("Authorization", "Bearer $accessToken").contentType(ContentType.JSON)
            .body("""{"handle":"$handle"}""")
            .`when`().post("/api/pairing/delegate").then().extract().statusCode()

    /** A channel a new device opened and answered: the state a trusted device delegates from. */
    private fun answeredChannel(): String {
        val handle = Base64.getEncoder().encodeToString(ByteArray(32).also { RNG.nextBytes(it) })
        pairings.open(handle)
        check(pairings.answer(handle, "YW5zd2Vy")) { "answer" }
        return handle
    }

    // --- fixtures and reads ----------------------------------------------------------------------------------------

    private fun seedAccount(): Account {
        val id = seedUser(authPublicKey = OLD_AUTH_PUB, secretKeyProofPublicKey = PROOF_KEY.publicKey)
        val email = "u-$id@example.com"
        QuarkusTransaction.requiringNew().run {
            listOf(WrapMethod.PASSWORD.name to OLD_PW_WRAP, WrapMethod.RECOVERY.name to OLD_REC_WRAP).forEach { (method, wrap) ->
                MasterKeyWrapEntity().apply {
                    this.id = UUID.randomUUID().toString(); userId = id; this.method = method; wrappedKey = wrap; kdfParams = KDF
                }.persist()
            }
        }
        val own = sessions.issue(id)
        return Account(id, email, own.accessToken, own.familyId, sessions.issue(id).accessToken)
    }

    private fun mintRecoveryToken(account: Account): String {
        val hash = Tokens.hash(Tokens.random())
        QuarkusTransaction.requiringNew().run {
            recoveries.mint(account.id, account.email, hash, Instant.now().plus(1, ChronoUnit.HOURS))
        }
        return QuarkusTransaction.requiringNew().call<String> { recoveries.findByTokenHash(hash)!!.id }
    }

    /** ACTIVE families of the account beyond the one the cleanup spares by design. */
    private fun liveFamiliesBeyond(account: Account, cleanup: Cleanup): Long = QuarkusTransaction.requiringNew().call<Long> {
        val spared = if (cleanup == Cleanup.RESET) "" else account.ownFamily
        SessionFamilyEntity.count("userId = ?1 and status = ?2 and id <> ?3", account.id, SessionFamilyEntity.ACTIVE, spared)
    }

    private fun nonSurvivorWraps(userId: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        MasterKeyWrapEntity.count("userId = ?1 and method not in ?2", userId, WrapMethod.SURVIVORS)
    }

    private fun liveChannelsOf(userId: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        DevicePairingEntity.count("userId = ?1 and status not in ?2", userId, DevicePairingEntity.TERMINAL)
    }

    private fun identityOf(userId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(userId)?.identityPublicKey }

    private fun authKeyOf(userId: String): String? =
        QuarkusTransaction.requiringNew().call<String?> { VeyqUser.findById(userId)?.authPublicKey }

    private companion object {
        const val KDF = "a2RmLXBhcmFtcw=="
        const val OLD_PW_WRAP = "b2xkLXBhc3N3b3JkLXdyYXA="
        const val NEW_PW_WRAP = "bmV3LXBhc3N3b3JkLXdyYXA="
        const val OLD_REC_WRAP = "b2xkLXJlY292ZXJ5LXdyYXA="
        const val NEW_REC_WRAP = "bmV3LXJlY292ZXJ5LXdyYXA="
        const val NEW_AUTH_PUB = "bmV3LWF1dGgtcHVibGljLWtleQ=="
        const val NEW_IDENTITY_PUB = "bmV3LWlkZW50aXR5LWtleQ=="
        const val NEW_WRAPPED_IDENTITY = "bmV3LXdyYXBwZWQtaWRlbnRpdHk="
        const val DEVICE_WRAP = "ZGV2aWNlLWtleS13cmFw"
        const val CHANGED_AUTH_PUB = "Y2hhbmdlZC1hdXRoLXB1YmxpYy1rZXk="
        const val CHANGED_PW_WRAP = "Y2hhbmdlZC1wYXNzd29yZC13cmFw"
        const val ROTATED_IDENTITY_PUB = "cm90YXRlZC1pZGVudGl0eS1rZXk="
        const val DOC = "ZG9jdW1lbnQ="

        /** The account's Secret-Key proof key: every seeded account registers it. */
        val PROOF_KEY = TestProofKey()

        val RNG = SecureRandom()

        /** The password every seeded account signs in with — the one each reset and change replaces. */
        val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val OLD_AUTH_PUB: String = Base64.getEncoder().encodeToString(KEYPAIR.public.encoded.let { it.copyOfRange(it.size - 32, it.size) })

        fun sign(nonceB64: String): String = Signature.getInstance("Ed25519").run {
            initSign(KEYPAIR.private)
            update(Base64.getDecoder().decode(nonceB64))
            Base64.getEncoder().encodeToString(sign())
        }
    }
}
