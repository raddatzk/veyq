package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.session.rotation.OpenedScope
import me.raddatz.veyq.mobile.session.rotation.ScopeRotation
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * The two whole-space acts an owner has on the web's Members panel and group page, for a tenant and a group alike: *Give it
 * a new lock* (ADR 0025 § 2, the web's `rekeySpace` → `commitRotation`, then `rekeyScope` on this device, ADR 0109) and
 * *Turn off every link* (ADR 0075 point 5, the web's `revokeScopeShares`, each a close whose re-key this device starts).
 */
internal object Locks {
    class Done(val done: Boolean, val notice: Notice)

    /** [scopeId]'s next epoch over its directory read now, [excluding] dropped from it by the owner's choice. */
    suspend fun newLock(tenant: TenantSession, scopeId: String, excluding: String?): Done {
        val tenantId = tenant.tenant.tenantId
        val members = tenant.account.members(tenant.base, tenantId, scopeId) ?: return Done(false, Notice(Msg.SPACE_LOCK_FAILED))
        val opener: suspend (String) -> OpenedScope? = { id ->
            tenant.scopeKey(id)?.let { (k, e) -> tenant.scopeKind(id)?.let { kind -> OpenedScope(id, tenantId, kind, e.toLong(), k.copyOf()) } }
        }
        ScopeRotation(tenant.account, AppKinds.scopeRekeyParticipants, AppKinds::tokenInput)
            .commitRotation(scopeId, members, opener, opener, excluding = excluding)
        runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
        // the nodes re-keyed here at once; where that does not finish, the space re-keys on (any member's device resumes it)
        val ran = runCatching { tenant.resumeRekeys()?.await() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        val done = ran?.get(scopeId).let { it == null || it == NodeRekey.Outcome.DONE }
        return Done(true, Notice(if (done) Msg.SPACE_LOCK_DONE else Msg.ROTATION_STATUS_REKEYING))
    }

    /** Every link of [scopeId] off; the count said (none, one, more). */
    suspend fun revokeLinks(tenant: TenantSession, scopeId: String): Done {
        val r = tenant.account.respond { ScopesApi(tenant.base, tenant.account.http).revokeScopeShares(scopeId) }
        if (!r.success) return Done(false, Notice(Msg.SPACE_LINKS_REVOKE_ALL_FAILED))
        val revoked = r.body().revoked
        if (revoked > 0) {
            runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
            tenant.resumeRekeys()
        }
        return Done(true, revokedNotice(revoked))
    }

    fun revokedNotice(revoked: Int): Notice =
        if (revoked == 0) Notice(Msg.SPACE_LINKS_REVOKED_NONE) else Notice(Msg.SPACE_LINKS_REVOKED, revoked)
}

/**
 * A member's identity-trust badge in the tenant's and a group's member lists (ADR 0111 decision 15), the web's
 * `MemberTrustBadge` over `tenantBadges`: *Verified* where someone's verification of their current head counts here, *New*
 * while this account first saw them under two weeks ago, an anomaly (a key this tenant does not anchor) with its note.
 * *Not verified* — every member's ordinary state — and an unchecked one show nothing; nor does this account's own row.
 * Display only.
 */
@Serializable
enum class MemberBadge(val word: Msg, val note: Msg?) {
    VERIFIED(Msg.SAFETY_STATE_VERIFIED, null),
    NEW(Msg.SAFETY_STATE_NEW, null),
    ANOMALY(Msg.TRUST_ANOMALY, Msg.TRUST_ANOMALY_NOTE);

    companion object {
        /** Each listed member's badge from *Who's who*'s states; **load-bearing:** verified wins over new, as the web's does. */
        fun of(w: WhosWho): Map<String, MemberBadge> = w.people.mapNotNull { o ->
            when (o.state) {
                WhosWho.State.CANT_CONFIRM -> o.userId to ANOMALY
                WhosWho.State.VERIFIED -> o.userId to VERIFIED
                WhosWho.State.NOT_VERIFIED -> if (o.isNew) o.userId to NEW else null
                WhosWho.State.UNCHECKED -> null
            }
        }.toMap()
    }
}
