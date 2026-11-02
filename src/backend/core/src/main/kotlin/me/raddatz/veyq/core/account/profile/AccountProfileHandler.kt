package me.raddatz.veyq.core.account.profile

import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.persistence.createOrConflict
import jakarta.enterprise.context.ApplicationScoped

/** Result of a profile read/update (cr-0083). `requireGroupAddConsent` is the account-global consent preference
 *  (cr-0094) surfaced here — the caller's own settings surface. */
sealed interface ProfileOutcome {
    data class Found(
        val username: String, val sealedName: String?, val avatarRevision: Int, val requireGroupAddConsent: Boolean,
    ) : ProfileOutcome
    // no tenant for the Host, or no profile there → 404
    data object NoTenant : ProfileOutcome, HttpFailure by httpFailure("profile_not_found", NOT_FOUND)
    // the new handle is taken by someone else in this tenant → 409
    data object UsernameTaken : ProfileOutcome, HttpFailure by Failures.usernameTaken
    // a sealed name under a tenant epoch that is not the current one (ADR 0115 decision 3) → 409
    data object StaleEpoch : ProfileOutcome, HttpFailure by Failures.staleEpoch
    // a sealed name without the epoch it is sealed under (the contract's `dependentRequired`) → 400
    data object InvalidRequest : ProfileOutcome, HttpFailure by Failures.invalidRequest
}

/** Result of setting/clearing the caller's picture (ADR 0084). */
sealed interface AvatarWriteOutcome {
    data object Done : AvatarWriteOutcome
    // no tenant for the Host, or no profile there → 404
    data object NoTenant : AvatarWriteOutcome, HttpFailure by httpFailure("profile_not_found", NOT_FOUND)
    // a picture under a tenant epoch that is not the current one (ADR 0115 decision 3) → 409
    data object StaleEpoch : AvatarWriteOutcome, HttpFailure by Failures.staleEpoch
}

/**
 * The caller's per-tenant identity profile (username + sealed real name, ADR 0023) in the tenant resolved from
 * the Host (`RequestTenant`), which arrives here as its id. Operator-blind — the sealed name is ciphertext the server stores verbatim; only the plaintext
 * `username` is read (to enforce per-tenant uniqueness). Auth is central (`@Authenticated` on the resource).
 *
 * **A sealed field states its tenant epoch** (ADR 0115 decision 3): the name and the picture are sealed under the
 * tenant scope's key, which a rotation replaces and re-seals every profile under, so each is written only while the
 * epoch it states is the tenant scope's current one — decided under that scope's row lock, which the rotation's commit
 * holds while it swaps the re-sealed profiles in. One landing after it under the old key is `409 stale_epoch`.
 */
@ApplicationScoped
class AccountProfileHandler(private val profiles: TenantProfileStore) {

    fun get(userId: String, tenantId: String?): ProfileOutcome {
        tenantId ?: return ProfileOutcome.NoTenant
        val profile = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        val consent = VeyqUser.findById(userId)?.requireGroupAddConsent ?: false
        return ProfileOutcome.Found(profile.username, profile.sealedName, profile.wireAvatarRevision(), consent)
    }

    fun update(
        userId: String, tenantId: String?, username: String?, sealedName: String?, epoch: Int?, requireGroupAddConsent: Boolean?,
    ): ProfileOutcome {
        // The request's own shape first (the contract's `dependentRequired`): a sealed name brings its epoch.
        if (sealedName != null && epoch == null) return ProfileOutcome.InvalidRequest
        tenantId ?: return ProfileOutcome.NoTenant
        // `ux_tp_tenant_username` lets one of two concurrent claims to a handle through; the loser answers as its
        // replay does — the handle is someone else's now.
        return createOrConflict(conflict = { if (takenByAnother(userId, tenantId, username)) ProfileOutcome.UsernameTaken else null }) {
            applyUpdate(userId, tenantId, username, sealedName, epoch, requireGroupAddConsent)
        }
    }

    private fun takenByAnother(userId: String, tenantId: String, username: String?): Boolean {
        val norm = username?.trim()?.lowercase() ?: return false
        return profiles.find(userId, tenantId)?.username != norm && profiles.usernameTaken(tenantId, norm)
    }

    private fun applyUpdate(
        userId: String, tenantId: String, username: String?, sealedName: String?, epoch: Int?, requireGroupAddConsent: Boolean?,
    ): ProfileOutcome {
        if (sealedName != null && !profiles.atCurrentEpoch(tenantId, epoch!!)) return ProfileOutcome.StaleEpoch
        val existing = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        // A username change to a handle another member already holds is rejected; keeping (or re-setting) your
        // own is fine.
        if (username != null) {
            val norm = username.trim().lowercase()
            if (norm != existing.username && profiles.usernameTaken(tenantId, norm)) return ProfileOutcome.UsernameTaken
        }
        profiles.update(userId, tenantId, username, sealedName)
        // The consent preference is account-global (cr-0094) — it lives on the user, not the per-tenant profile.
        if (requireGroupAddConsent != null) VeyqUser.findById(userId)?.let { it.requireGroupAddConsent = requireGroupAddConsent }
        val updated = profiles.find(userId, tenantId)!!
        val consent = VeyqUser.findById(userId)?.requireGroupAddConsent ?: false
        return ProfileOutcome.Found(updated.username, updated.sealedName, updated.wireAvatarRevision(), consent)
    }

    /** Set (overwrite) the caller's picture; the revision bump is the store's one guarded UPDATE. */
    fun setAvatar(userId: String, tenantId: String?, sealedAvatar: String, epoch: Int): AvatarWriteOutcome {
        tenantId ?: return AvatarWriteOutcome.NoTenant
        return when (profiles.setAvatar(userId, tenantId, sealedAvatar, epoch)) {
            TenantProfileStore.AvatarSet.SET -> AvatarWriteOutcome.Done
            TenantProfileStore.AvatarSet.NO_PROFILE -> AvatarWriteOutcome.NoTenant
            TenantProfileStore.AvatarSet.STALE_EPOCH -> AvatarWriteOutcome.StaleEpoch
        }
    }

    /** Clear the caller's picture (idempotent) and bump the revision. */
    fun clearAvatar(userId: String, tenantId: String?): AvatarWriteOutcome {
        tenantId ?: return AvatarWriteOutcome.NoTenant
        return if (profiles.clearAvatar(userId, tenantId)) AvatarWriteOutcome.Done else AvatarWriteOutcome.NoTenant
    }
}
