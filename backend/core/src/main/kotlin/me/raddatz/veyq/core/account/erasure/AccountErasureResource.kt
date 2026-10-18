package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.generated.api.AccountErasureApi
import me.raddatz.veyq.core.generated.model.AccountErasurePreview
import me.raddatz.veyq.core.generated.model.EraseOwnAccountRequest
import me.raddatz.veyq.core.generated.model.ErasureTenant
import me.raddatz.veyq.core.generated.model.OwnAccountErasure
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for the member's door to the account erasure (uc.account.erase-account, ADR 0101 decision 1).
 * Implements the generated [AccountErasureApi]; thin (ADR 0001). `@Authenticated`: the caller's own session names the
 * account, and the erase proves the password besides. The `USER` bucket bounds runaway automation.
 */
@Authenticated
@RateLimit(key = RateLimitKey.USER)
class AccountErasureResource(
    private val handler: AccountErasureHandler,
    private val identity: SecurityIdentity,
) : AccountErasureApi {

    override fun getOwnAccountErasurePreview(): Response {
        val tenants = handler.preview(identity.principal.name) ?: return httpError(ErasureOutcome.ServerOwnerAccount)
        return Response.ok(
            AccountErasurePreview(
                tenants.map { t ->
                    ErasureTenant(UUID.fromString(t.tenantId), t.name, t.otherMemberIds.map(UUID::fromString))
                },
            ),
        ).build()
    }

    override fun eraseOwnAccount(eraseOwnAccountRequest: EraseOwnAccountRequest): Response {
        val choices = eraseOwnAccountRequest.lastOwnerChoices.map { OwnerChoice(it.tenantId.toString(), it.newOwnerId?.toString()) }
        return when (val o = handler.eraseOwn(
            identity.principal.name, eraseOwnAccountRequest.nonce, eraseOwnAccountRequest.attestationSignature, choices,
        )) {
            is ErasureOutcome.Pending -> Response.ok(OwnAccountErasure(o.dueAt.atOffset(ZoneOffset.UTC))).build()
            is HttpFailure -> httpError(o)
        }
    }
}
