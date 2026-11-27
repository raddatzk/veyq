package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.SetTenantMemberRoleRequest
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * Settings' *Members* of the open tenant (uc.spaces.remove-tenant-member; ADR 0101 decision 9): a port of the roster of the
 * web's `TenantMembersPanel.vue` over `members.ts`. Every member with their role, and for an owner the three acts the web
 * offers on a row: make a member a co-owner, take that back, remove a member who is no owner. A role change moves no key
 * (the member already holds the tenant's); a removal is the server's cascade, no key crosses either. Each real name opens
 * here, under the tenant key, bound to (tenant scope, member) as the directory's does ([People]).
 *
 * The owner is read from `listMyTenants`' role, as the web's panel reads it. Who sees which act is decided here
 * ([RosterMember.promote], [RosterMember.demote], [RosterMember.remove]); a platform draws what a row offers and nothing else.
 */
class TenantMembers internal constructor(private val tenant: TenantSession) {

    /** The roster as a page draws it: [rows] in the server's order, or [said] in their place (with *Try again* where [retry]). */
    @Serializable
    data class Roster(val rows: List<RosterMember>, val owner: Boolean, val said: Msg?, val retry: Boolean)

    /** A member's role in the tenant. */
    enum class MemberRole { OWNER, MEMBER }

    /** One member as a row shows them. */
    @Serializable
    data class RosterMember(
        val userId: String,
        val username: String,
        /** The real name as it opened here; null where none is set or none opens (the row then leads with `@username`). */
        val realName: String?,
        val email: String?,
        val role: MemberRole,
        /** Behind the tenant's epoch, declared pending by a rotation (ADR 0111 decision 9). */
        val pending: Boolean,
        /** *Make co-owner*: the caller owns the tenant and this is a member. */
        val promote: Boolean,
        /** *Remove co-owner*: the caller owns the tenant and this is an owner (the server's last-owner guard says the rest). */
        val demote: Boolean,
        /** *Remove member*: the caller owns the tenant and this is no owner (owners are managed by co-ownership, as on the web). */
        val remove: Boolean,
    ) {
        // the fields below are carried by the bridge as they are (its JSON encodes defaults), so a host decides none of them
        /** The row's title: the real name, else `@username` (the web's `fullName || '@' + username`). */
        val title: String = realName ?: "@$username"

        /** The `@username` under a real name; null where the title already is it. */
        val usernameLine: String? = realName?.let { "@$username" }

        /** The role's word. */
        val roleWord: Msg = if (role == MemberRole.OWNER) Msg.MEMBERS_ROLE_OWNER else Msg.MEMBERS_ROLE_MEMBER

        /** The status badge every listed member carries. */
        val statusWord: Msg = Msg.MEMBERS_STATUS_ACTIVE

        /** The pending badge's word; null where the member is current. */
        val pendingWord: Msg? = if (pending) Msg.MEMBERS_PENDING else null
    }

    /** How a role change or a removal ended: [done] where it went through, [notice] what is said either way. */
    class Outcome(val done: Boolean, val notice: Notice)

    /** The roster read anew: the members, their names opened here, and whether the caller owns the tenant. */
    @Throws(Exception::class)
    suspend fun read(): Roster {
        val tenantId = tenant.tenant.tenantId
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return NOT_AUTHORIZED
        val members = tenant.account.members(tenant.base, tenantId, tenantId) ?: return FAILED
        val owner = try {
            myRole() ?: return FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FAILED
        } == MyTenant.Role.OWNER
        return assemble(members, owner) { m -> People.realName(JoinRecords(tenant.account.suite), tenantId, key, epoch.toLong(), m) }
    }

    /** Makes [userId] an owner ([owner]) or a member again; the server's last-owner guard refuses the last owner's demotion. */
    @Throws(Exception::class)
    suspend fun setRole(userId: String, owner: Boolean): Outcome = attempt(Msg.MEMBERS_ROLE_CHANGE_FAILED) {
        val r = tenant.account.respond {
            TenantsApi(tenant.base, tenant.account.http).setTenantMemberRole(
                tenant.tenant.tenantId, userId,
                SetTenantMemberRoleRequest(if (owner) SetTenantMemberRoleRequest.Role.OWNER else SetTenantMemberRoleRequest.Role.MEMBER),
            )
        }
        when {
            r.success -> Outcome(true, Notice(if (owner) Msg.MEMBERS_PROMOTED else Msg.MEMBERS_DEMOTED))
            r.status == 409 && tenant.account.errorCode(r) == LAST_OWNER -> Outcome(false, Notice(Msg.MEMBERS_LAST_OWNER))
            else -> Outcome(false, Notice(Msg.MEMBERS_ROLE_CHANGE_FAILED))
        }
    }

    /**
     * Removes [userId] from the tenant (the server's whole cascade), with every link of the space turned off in the same act
     * where [revokeLinks] (ADR 0075 point 5; unticked, no parameter is sent, as on the web). The removal closes the member's
     * own links, so the session then reopens what moved and finishes the re-keys now due, in the background (ADR 0114
     * decision 9; the web's `rekeyClosedScopes`).
     */
    @Throws(Exception::class)
    suspend fun remove(userId: String, revokeLinks: Boolean): Outcome = attempt(Msg.MEMBERS_ACTION_FAILED) {
        val r = tenant.account.respond {
            TenantsApi(tenant.base, tenant.account.http).removeTenantMember(tenant.tenant.tenantId, userId, if (revokeLinks) true else null)
        }
        if (!r.success) return@attempt Outcome(false, Notice(Msg.MEMBERS_ACTION_FAILED))
        runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
        tenant.resumeRekeys()
        Outcome(true, Notice(Msg.MEMBERS_REMOVED))
    }

    private suspend fun myRole(): MyTenant.Role? {
        val r = tenant.account.respond { ScopesApi(tenant.base, tenant.account.http).listMyTenants() }
        if (!r.success) return null
        return r.body().firstOrNull { it.tenantId == tenant.tenant.tenantId }?.role
    }

    private inline fun attempt(failed: Msg, block: () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, Notice(failed))
    }

    internal companion object {
        const val LAST_OWNER = "last_owner"

        val NOT_AUTHORIZED = Roster(emptyList(), owner = false, said = Msg.MEMBERS_NOT_AUTHORIZED, retry = false)
        val FAILED = Roster(emptyList(), owner = false, said = Msg.MEMBERS_LOAD_FAILED, retry = true)

        /**
         * The roster from what [read] read: [members] in the server's order, [owner] whether the caller owns the tenant,
         * [name] each member's real name. **Load-bearing:** only an owner's roster offers an act, and *Remove member* never
         * on an owner's row.
         */
        fun assemble(members: List<ScopeMember>, owner: Boolean, name: (ScopeMember) -> String?): Roster {
            if (members.isEmpty()) return Roster(emptyList(), owner, Msg.MEMBERS_EMPTY, retry = false)
            val rows = members.map { m ->
                val role = if (m.role == ScopeMember.Role.OWNER) MemberRole.OWNER else MemberRole.MEMBER
                RosterMember(
                    userId = m.userId,
                    username = m.username,
                    realName = name(m),
                    email = m.email,
                    role = role,
                    pending = m.pending,
                    promote = owner && role == MemberRole.MEMBER,
                    demote = owner && role == MemberRole.OWNER,
                    remove = owner && role != MemberRole.OWNER,
                )
            }
            return Roster(rows, owner, null, retry = false)
        }
    }
}
