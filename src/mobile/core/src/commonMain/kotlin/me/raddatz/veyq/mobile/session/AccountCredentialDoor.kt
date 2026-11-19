package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.apis.AccountEmailApi
import me.raddatz.veyq.mobile.api.apis.AccountEmailChangeApi
import me.raddatz.veyq.mobile.api.apis.AccountErasureApi
import me.raddatz.veyq.mobile.api.apis.AccountPasswordApi
import me.raddatz.veyq.mobile.api.apis.ModerationApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.AccountErasurePreview
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ChangePasswordRequest
import me.raddatz.veyq.mobile.api.models.EmailChangeRequest
import me.raddatz.veyq.mobile.api.models.EraseOwnAccountRequest
import me.raddatz.veyq.mobile.api.models.LoginChallenge

/** The password change of this account (ADR 0148 decision 6). */
fun VeyqAccount.passwordChange(): PasswordChange =
    PasswordChange(AccountCredentialDoor(this), primitives, me.raddatz.veyq.mobile.account.BreachCheck.of(this))

/**
 * The address change of this account and the kit it offers after (ADR 0148 decision 6). The request is sent through
 * [tenant]'s origin — the mail goes out through a space the account belongs to, as the web sends it from the page's
 * (`404 tenant_not_found` elsewhere): the tenant on screen, else the account's default.
 */
fun VeyqAccount.addressChange(tenant: me.raddatz.veyq.mobile.navigation.TenantRef? = defaultTenant): AddressChange =
    AddressChange(AccountCredentialDoor(this, tenant?.let(::originOf) ?: server), primitives)

/** The erasure of this account (ADR 0148 decision 8). */
fun VeyqAccount.erasure(): AccountErasure = AccountErasure(AccountCredentialDoor(this), primitives)

/** [CredentialDoor] over a signed-in [VeyqAccount]: the account's own origin for every call (they are the account's). */
internal class AccountCredentialDoor(
    private val account: VeyqAccount,
    /** Where a request that mails goes: a tenant's origin. */
    private val mailOrigin: String = account.server,
) : CredentialDoor {
    override val userId: String get() = account.userId
    override val email: String get() = account.email
    override val server: String get() = account.server

    override fun masterKey(): ByteArray? = account.masterKeyCopy()

    override suspend fun keyMaterial(): AccountKeyMaterial = account.keyMaterialNow()

    override suspend fun challenge(email: String): LoginChallenge = account.challenge(email)

    private suspend fun <T : Any> reply(f: suspend () -> HttpResponse<T>): FlowReply {
        val r = account.respond { f() }
        return FlowReply(r.status, if (r.success) null else account.errorCode(r))
    }

    override suspend fun changePassword(body: ChangePasswordRequest): FlowReply =
        reply { AccountPasswordApi(account.server, account.http).changePassword(body) }

    override suspend fun passwordChanged() = account.forgetSavedKeyKeepStore()

    override suspend fun requestEmailChange(body: EmailChangeRequest): FlowReply =
        reply { AccountEmailChangeApi(mailOrigin, account.http).requestEmailChange(body) }

    override fun addressChangeRequested() = account.addressChangeRequested()

    override val addressTaken: Boolean get() = account.readdressedFrom != null

    override suspend fun heldEmail(): String = account.call { AccountEmailApi(account.server, account.http).getAccountEmail() }.email

    override suspend fun erasurePreview(): Pair<FlowReply, AccountErasurePreview?> {
        val r = account.respond { AccountErasureApi(account.server, account.http).getOwnAccountErasurePreview() }
        return if (r.success) FlowReply(r.status) to r.body() else FlowReply(r.status, account.errorCode(r)) to null
    }

    override suspend fun memberNames(tenantId: String, userIds: List<String>): Map<String, String> {
        val tenant = account.tenants.firstOrNull { it.tenantId == tenantId } ?: return emptyMap()
        val members = account.members(account.originOf(tenant), tenantId, tenantId).orEmpty()
        return members.filter { it.userId in userIds }.associate { it.userId to it.username }
    }

    override suspend fun holdsModerationKey(): Boolean {
        val r = account.respond { ModerationApi(account.server, account.http).listMyModerationGrants() }
        if (r.status == 403) return false
        if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
        return r.body().epochs.any { it.sealedGrant != null }
    }

    override suspend fun erase(body: EraseOwnAccountRequest): FlowReply =
        reply { AccountErasureApi(account.server, account.http).eraseOwnAccount(body) }

    override suspend fun erased() = account.forgetErased()
}
