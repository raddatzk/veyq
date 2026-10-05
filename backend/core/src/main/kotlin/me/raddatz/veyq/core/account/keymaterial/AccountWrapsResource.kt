package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.generated.api.AccountWrapsApi
import me.raddatz.veyq.core.generated.model.AddWrapRequest
import me.raddatz.veyq.core.generated.model.AddWrapResult
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for adding/removing the caller's Master Key wraps (uc.account.trust-device). Implements the
 * generated [AccountWrapsApi]; thin (ADR 0001) — delegates to [KeyMaterialHandler]. Authentication is central
 * (`@Authenticated`, cr-0012): a missing/invalid token is a generic 401 before this runs. Operator-blind: the
 * server stores only the ciphertext wrap + opaque KDF params, never a key or password. Both endpoints act only
 * on the **caller's own** wraps (resolved from the `SecurityIdentity` principal, never an id in the body); the
 * PASSWORD floor can't be removed and delete is idempotent (always 204).
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; adds or removes a wrap on the caller's own account. Bounded by the DEVICE " +
        "whitelist and the caller's own session, not by request volume.",
)
class AccountWrapsResource(
    private val handler: KeyMaterialHandler,
    private val identity: SecurityIdentity,
) : AccountWrapsApi {

    override fun addAccountWrap(addWrapRequest: AddWrapRequest): Response =
        when (val outcome = handler.addWrap(
            identity.principal.name,
            // Re-read under the account lock: the session this request was let in on may end while it waits.
            identity.getCredential(TokenCredential::class.java)?.token,
            addWrapRequest.method.value,
            addWrapRequest.wrappedKey,
            addWrapRequest.kdfParams,
        )) {
            is AddWrapOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(AddWrapResult(id = outcome.id)).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun deleteAccountWrap(id: String): Response {
        handler.deleteWrap(identity.principal.name, id)
        return Response.noContent().build()
    }
}
