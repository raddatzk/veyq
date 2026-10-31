package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.generated.api.InstanceAccountsApi
import me.raddatz.veyq.core.generated.model.AccountAddressRequest
import me.raddatz.veyq.core.generated.model.AccountErasureList
import me.raddatz.veyq.core.generated.model.AccountErasureRequester
import me.raddatz.veyq.core.generated.model.PendingAccountErasure
import me.raddatz.veyq.core.generated.model.ServerOwnerAccountErasureRequest
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.identity.AccountErasureEntity
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset

/**
 * The Server Owner's door to the account erasure on the admin channel (uc.account.erase-account, ADR 0101 decisions 1
 * and 3): erase an account by its address, list the pending erasures, stop one. Thin over [AccountErasureHandler].
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only erasure requests and stops on the admin channel: one row per account, one mail per " +
        "request to the account's own address (never to an address the request names beyond the account it finds); " +
        "the role gate already bounds the caller set to one account.",
)
class InstanceAccountsResource(
    private val handler: AccountErasureHandler,
    private val identity: SecurityIdentity,
) : InstanceAccountsApi {

    override fun listAccountErasures(): Response = Response.ok(
        AccountErasureList(
            handler.pending().map {
                PendingAccountErasure(
                    address = it.address,
                    requestedAt = it.requestedAt.atOffset(ZoneOffset.UTC),
                    requestedBy = if (it.requestedBy == AccountErasureEntity.SERVER_OWNER) AccountErasureRequester.SERVER_OWNER
                    else AccountErasureRequester.SELF,
                    dueAt = it.dueAt.atOffset(ZoneOffset.UTC),
                )
            },
        ),
    ).build()

    override fun eraseAccount(serverOwnerAccountErasureRequest: ServerOwnerAccountErasureRequest): Response =
        when (val o = handler.eraseByServerOwner(
            identity.principal.name, serverOwnerAccountErasureRequest.address,
            keyLossConfirmed = serverOwnerAccountErasureRequest.confirmModerationKeyLoss == true,
        )) {
            is ErasureOutcome.Pending -> Response.ok(
                PendingAccountErasure(
                    address = o.address,
                    requestedAt = o.requestedAt.atOffset(ZoneOffset.UTC),
                    requestedBy = AccountErasureRequester.SERVER_OWNER,
                    dueAt = o.dueAt.atOffset(ZoneOffset.UTC),
                ),
            ).build()
            is HttpFailure -> httpError(o)
        }

    override fun stopAccountErasure(accountAddressRequest: AccountAddressRequest): Response =
        when (val o = handler.stop(accountAddressRequest.address)) {
            StopErasureOutcome.Stopped -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }
}
