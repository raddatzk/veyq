package me.raddatz.veyq.core.account.profile

import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.persistence.createOrConflict
import jakarta.enterprise.context.ApplicationScoped

/** Result of a profile read/update (cr-0083). `requireGroupAddConsent` (the consent preference, cr-0094) and
 *  `donationBannerDismissed` (ADR 0124) are account-global flags surfaced here — the caller's own settings surface. */
sealed interface ProfileOutcome {
    data class Found(
        val username: String, val name: SealedValue?, val avatarRevision: Int, val requireGroupAddConsent: Boolean,
        val donationBannerDismissed: Boolean,
    ) : ProfileOutcome
    // no tenant for the Host, or no profile there → 404
    data object NoTenant : ProfileOutcome, HttpFailure by httpFailure("profile_not_found", NOT_FOUND)
    // the new handle is taken by someone else in this tenant → 409
    data object UsernameTaken : ProfileOutcome, HttpFailure by Failures.usernameTaken
    // a sealed name under a tenant epoch that is not the current one (ADR 0115 decision 3) → 409
    data object StaleEpoch : ProfileOutcome, HttpFailure by Failures.staleEpoch
    // a sealed name without its value key's wrap or the epoch of that wrap, or a wrap without a name (the contract's
    // `dependentRequired`) → 400
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

/** A stored name with its value key's wrap, as the profile read answers it. */
private fun storedName(sealedName: String?, wrap: String?) = SealedValue.of(sealedName, wrap)

/**
 * The caller's per-tenant identity profile (username + sealed real name, ADR 0023) in the tenant resolved from
 * the Host (`RequestTenant`), which arrives here as its id. Operator-blind — the sealed name is ciphertext the server stores verbatim; only the plaintext
 * `username` is read (to enforce per-tenant uniqueness). Auth is central (`@Authenticated` on the resource).
 *
 * **A sealed field comes with its value key's wrap and states its tenant epoch** (ADR 0115): the name and the picture
 * are each sealed under a value key the member mints at this write, and only that key is wrapped under the tenant
 * scope's key, which a rotation replaces and re-wraps every value key under — never rewriting a value — so each is
 * written only while the epoch it states is the tenant scope's current one, decided under that scope's row lock, which
 * the rotation's commit holds while it swaps the re-wrapped keys in. One landing after it under the old key is `409
 * stale_epoch`.
 */
@ApplicationScoped
class AccountProfileHandler(private val profiles: TenantProfileStore) {

    fun get(userId: String, tenantId: String?): ProfileOutcome {
        tenantId ?: return ProfileOutcome.NoTenant
        val profile = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        val user = VeyqUser.findById(userId)
        return ProfileOutcome.Found(
            profile.username, storedName(profile.sealedName, profile.wrappedNameValueKey), profile.wireAvatarRevision(),
            user?.requireGroupAddConsent ?: false, user?.donationBannerDismissed ?: false,
        )
    }

    fun update(
        userId: String, tenantId: String?, username: String?, sealedName: String?, wrappedNameValueKey: String?, epoch: Int?,
        requireGroupAddConsent: Boolean?, donationBannerDismissed: Boolean? = null,
    ): ProfileOutcome {
        // The request's own shape first (the contract's `dependentRequired`): a sealed name brings its wrap and its epoch.
        if (!SealedValue.pairs(sealedName, wrappedNameValueKey) || (sealedName != null && epoch == null)) return ProfileOutcome.InvalidRequest
        val name = SealedValue.of(sealedName, wrappedNameValueKey)
        tenantId ?: return ProfileOutcome.NoTenant
        // `ux_tp_tenant_username` lets one of two concurrent claims to a handle through; the loser answers as its
        // replay does — the handle is someone else's now.
        return createOrConflict(conflict = { if (takenByAnother(userId, tenantId, username)) ProfileOutcome.UsernameTaken else null }) {
            applyUpdate(userId, tenantId, username, name, epoch, requireGroupAddConsent, donationBannerDismissed)
        }
    }

    private fun takenByAnother(userId: String, tenantId: String, username: String?): Boolean {
        val norm = username?.trim()?.lowercase() ?: return false
        return profiles.find(userId, tenantId)?.username != norm && profiles.usernameTaken(tenantId, norm)
    }

    private fun applyUpdate(
        userId: String, tenantId: String, username: String?, name: SealedValue?, epoch: Int?, requireGroupAddConsent: Boolean?,
        donationBannerDismissed: Boolean?,
    ): ProfileOutcome {
        if (name != null && !profiles.atCurrentEpoch(tenantId, epoch!!)) return ProfileOutcome.StaleEpoch
        val existing = profiles.find(userId, tenantId) ?: return ProfileOutcome.NoTenant
        // A username change to a handle another member already holds is rejected; keeping (or re-setting) your
        // own is fine.
        if (username != null) {
            val norm = username.trim().lowercase()
            if (norm != existing.username && profiles.usernameTaken(tenantId, norm)) return ProfileOutcome.UsernameTaken
        }
        profiles.update(userId, tenantId, username, name)
        // The consent preference (cr-0094) and the banner's dismissal (ADR 0124) are account-global — they live on the
        // user, not the per-tenant profile. An omitted one is left as it is.
        val user = VeyqUser.findById(userId)
        if (user != null) {
            if (requireGroupAddConsent != null) user.requireGroupAddConsent = requireGroupAddConsent
            if (donationBannerDismissed != null) user.donationBannerDismissed = donationBannerDismissed
        }
        val updated = profiles.find(userId, tenantId)!!
        return ProfileOutcome.Found(
            updated.username, storedName(updated.sealedName, updated.wrappedNameValueKey), updated.wireAvatarRevision(),
            user?.requireGroupAddConsent ?: false, user?.donationBannerDismissed ?: false,
        )
    }

    /** Set (overwrite) the caller's picture; the revision bump is the store's one guarded UPDATE. */
    fun setAvatar(userId: String, tenantId: String?, avatar: SealedValue, epoch: Int): AvatarWriteOutcome {
        tenantId ?: return AvatarWriteOutcome.NoTenant
        return when (profiles.setAvatar(userId, tenantId, avatar, epoch)) {
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
