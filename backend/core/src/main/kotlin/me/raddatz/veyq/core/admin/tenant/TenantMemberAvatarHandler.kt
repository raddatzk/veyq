package me.raddatz.veyq.core.admin.tenant

import jakarta.enterprise.context.ApplicationScoped
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal

/**
 * The `ETag` of a member's picture (ADR 0084): the profile's `avatarRevision`, quoted. Lenient on the way in like the
 * node listing's ([me.raddatz.veyq.core.content.TreeETag]): a weak marker and the quotes are stripped, and anything
 * that is no number is "no match" (null => a plain `200`), never an error.
 */
object AvatarETag {
    fun of(revision: Int): String = "\"$revision\""

    fun parse(raw: String?): Int? =
        raw?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.toIntOrNull()?.takeIf { it >= 0 }
}

/** Result of reading a tenant member's sealed picture (ADR 0084). */
sealed interface MemberAvatarOutcome {
    data class Found(val sealedAvatar: String, val revision: Int) : MemberAvatarOutcome
    /** The caller's `If-None-Match` is the picture's current revision: a `304`, no body. */
    data class NotModified(val revision: Int) : MemberAvatarOutcome
    data object Suspended : MemberAvatarOutcome, HttpFailure by Failures.tenantSuspended
    /** No such tenant, or the caller is not an accepted member of it (indistinguishable, ADR 0042). */
    data object NotFound : MemberAvatarOutcome, HttpFailure by Failures.tenantNotFound
    /** The member has no picture (never set, cleared, or removed from the tenant), or is no member of it. */
    data object NoAvatar : MemberAvatarOutcome, HttpFailure by httpFailure("avatar_not_found", FailureStatus.NOT_FOUND)
}

/**
 * Reads a tenant member's profile picture for any other tenant member (uc.spaces.set-profile-picture, ADR 0084). The
 * picture is one opaque, constant-length ciphertext the server relays verbatim; it is served only behind the tenant
 * membership gate (the directory's, ADR 0042) and never on a share. A guest has no route here.
 */
@ApplicationScoped
class TenantMemberAvatarHandler(
    private val profiles: TenantProfileStore,
    private val access: ScopeAccess,
) {
    fun read(callerId: String, tenantId: String, memberId: String, ifNoneMatch: Int?): MemberAvatarOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(MemberAvatarOutcome.NotFound, MemberAvatarOutcome.Suspended)?.let { return it }
        val profile = profiles.find(memberId, tenantId) ?: return MemberAvatarOutcome.NoAvatar
        val sealed = profile.sealedAvatar ?: return MemberAvatarOutcome.NoAvatar
        return if (ifNoneMatch == profile.avatarRevision) MemberAvatarOutcome.NotModified(profile.avatarRevision)
        else MemberAvatarOutcome.Found(sealed, profile.avatarRevision)
    }
}
