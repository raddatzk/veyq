package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeSuccession
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.XWingKey
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * One tenant's standings as its member list serves them (`tenantTrust.ts` § `loadTenantView`, ADR 0111 decisions 12
 * and 15), read once per opening of the tenant. [members] is null when this device could not open the tenant or list its
 * members: every standing is then UNAVAILABLE, and a group's granter is judged by the pin alone, as on the web.
 */
internal class TenantView(private val members: Map<String, ScopeMember>?) {
    fun standing(userId: String): Standing {
        val m = (members ?: return Standing.UNAVAILABLE)[userId] ?: return Standing.UNANCHORED
        if (m.pendingConsent == true) return Standing.UNANCHORED
        if (m.sealedJoinRecord == null) return if (m.role == ScopeMember.Role.OWNER) Standing.ROOT else Standing.UNANCHORED
        // A join record: the web opens it and walks the chain from the step it names (the JOINED standing). Not built
        // here, so it is refused rather than trusted (see [Standing]).
        return Standing.UNANCHORED
    }
}

/** What opening a grant came to: its key, or why it stays closed. A closed scope waits for a current grant. */
sealed interface GrantOutcome {
    class Opened(val key: ByteArray) : GrantOutcome
    class Refused(val reason: Reason) : GrantOutcome

    enum class Reason {
        /** Below the scope's current epoch, or below this device's floor for it (ADR 0024 decision 6). */
        AWAITING_CURRENT_KEY,
        UNSIGNED,
        NOT_SELF_SIGNED,
        BAD_SIGNATURE,
        GRANTER_KEY_UNANCHORED,
        GRANTER_KEY_RETIRED,
        /** No chain of succession statements leads from the key this device held to the one granted (ADR 0115 d. 5). */
        EPOCH_UNLINKED,
        DOES_NOT_OPEN,
        /** This device refused its own pin store (rolled back or unreadable): no key from another account is judged. */
        TRUST_STATE,
    }
}

/**
 * The recipient's half of ADR 0028 decision 6, a port of `openOwnGrant` (`src/web/src/features/spaces/scopes.ts`) and
 * `verifyOwnGrant` (`grantTrust.ts`): a grant is opened only once its granter's signature verifies, and the key inside
 * is used only once the epoch it carries succeeds the key this device held.
 *
 *  - **Self-granted:** the signature verifies under this account's own signing key, from the vault, never a directory.
 *  - **PERSONAL:** only self-granted opens.
 *  - **Shared:** the signature verifies under the granter's key the row carries, and the granter's key chain reaches
 *    that key from the head this account pinned ([Anchoring]): a first-seen chain is pinned, an advanced one moves the
 *    pin (only after the signature verified, so a forged grant pins nothing); anything else is GRANTER_KEY_UNANCHORED.
 *    A GROUP's granter is judged by their standing in the group's tenant ([TenantView]); the TENANT scope's own grant
 *    by the pin alone.
 *  - **A retired key** of the granter's chain opens a grant only at an epoch at or below this device's floor.
 *  - **The floor** refuses a grant below an epoch this device held; **the succession** refuses a key at or above the
 *    held epoch that the served statements do not lead to. A grant that opened is held: floor and held key rise to it.
 */
internal class GrantOpener(
    private val suite: V1,
    private val trust: Trust,
    private val primitives: Primitives,
    private val userId: String,
    private val myPublic: ByteArray,
    private val mySignPublic: ByteArray,
    private val kem: XWingKey,
    private val marks: TrustMarks,
    private val anchoring: Anchoring,
    /** A scope's succession statements, or null when the server serves none to this account (`404` / `403`). */
    private val successions: suspend (scopeId: String) -> List<ScopeSuccession>?,
) {
    suspend fun open(s: ScopeSummary, view: TenantView?): GrantOutcome {
        val epoch = s.grantedEpoch.toLong()
        if (s.grantedEpoch < s.currentEpoch || epoch < marks.floor(s.id)) return refused(GrantOutcome.Reason.AWAITING_CURRENT_KEY)
        val verdict = try {
            verify(s, epoch, view)
        } catch (e: TrustStateRefused) {
            GrantOutcome.Reason.TRUST_STATE
        }
        if (verdict != null) return refused(verdict)
        val key = try {
            suite.openAs(Context("scope-grant", "scopeId" to s.id, "epoch" to epoch), kem, unb64(s.wrappedGrant))
        } catch (e: Exception) {
            return refused(GrantOutcome.Reason.DOES_NOT_OPEN)
        }
        val (commitment, seed) = trust.successionKeyPair(key).let { (pk, sk) -> b64(pk) to sk }
        seed.fill(0)
        if (!succeedsHeld(s.id, epoch, commitment)) {
            key.fill(0)
            return refused(GrantOutcome.Reason.EPOCH_UNLINKED)
        }
        marks.hold(s.id, epoch, commitment)
        return GrantOutcome.Opened(key)
    }

    private fun refused(r: GrantOutcome.Reason) = GrantOutcome.Refused(r)

    /** Null when the grant may be opened, else why not. */
    private suspend fun verify(s: ScopeSummary, epoch: Long, view: TenantView?): GrantOutcome.Reason? {
        val granter = s.granterUserId ?: return GrantOutcome.Reason.UNSIGNED
        val signature = s.grantSignature?.let { runCatching { unb64(it) }.getOrNull() } ?: return GrantOutcome.Reason.UNSIGNED
        val statement = runCatching { suite.grantStatement(s.id, s.kind.value, epoch, userId, myPublic, unb64(s.wrappedGrant)) }.getOrNull()
            ?: return GrantOutcome.Reason.BAD_SIGNATURE
        if (granter == userId) return if (verifies(mySignPublic, statement, signature)) null else GrantOutcome.Reason.BAD_SIGNATURE
        if (s.kind.value == "PERSONAL") return GrantOutcome.Reason.NOT_SELF_SIGNED
        val granterKey = s.granterIdentityPublicKey ?: return GrantOutcome.Reason.UNSIGNED
        val granterSign = runCatching { suite.deserializeIdentityPublic(unb64(granterKey)).sign }.getOrNull() ?: return GrantOutcome.Reason.BAD_SIGNATURE
        if (!verifies(granterSign, statement, signature)) return GrantOutcome.Reason.BAD_SIGNATURE
        val standing = if (s.kind.value == "GROUP") (view ?: TenantView(null)).standing(granter) else null
        val judged = anchoring.anchorChainKey(granter, granterKey, standing)
        if (judged.anchor == Anchor.ANOMALY) return GrantOutcome.Reason.GRANTER_KEY_UNANCHORED
        if (judged.retired && epoch > marks.floor(s.id)) return GrantOutcome.Reason.GRANTER_KEY_RETIRED
        return null
    }

    private fun verifies(publicKey: ByteArray, message: ByteArray, signature: ByteArray) =
        runCatching { primitives.ed25519Verify(publicKey, message, signature) }.getOrDefault(false)

    /**
     * `requireSuccessionFromHeld`: passes with nothing held (a device's first grant of the scope) and below the held
     * epoch (the floor is that check); at the held epoch the key must be the held one; above it the served statements
     * must lead, one per epoch, from the held key to this one.
     */
    private suspend fun succeedsHeld(scopeId: String, epoch: Long, commitment: String): Boolean {
        val held = marks.held(scopeId) ?: return true
        if (epoch < held.epoch) return true
        if (epoch == held.epoch) return commitment == held.commitment
        val served = successions(scopeId) ?: return false
        val links = runCatching { served.map { Trust.SuccessionLink(it.epoch.toLong(), unb64(it.successionPublicKey), unb64(it.successionSignature)) } }.getOrNull()
            ?: return false
        return trust.verifySuccessionPath(scopeId, held.epoch, unb64(held.commitment), links, epoch, unb64(commitment))
    }
}
