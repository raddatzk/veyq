package com.veyq.core.account.identitypins

import com.veyq.core.generated.api.AccountIdentityPinsApi
import com.veyq.core.generated.model.IdentityPins
import com.veyq.core.generated.model.IdentityPinsUpdate
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import com.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the operator-blind identity-pin store (en.account.identity-pinning, cr-0098, ADR 0028).
 * Implements the generated [AccountIdentityPinsApi]; thin (ADR 0001) — delegates to [IdentityPinsHandler] and
 * maps the stored ciphertext + version to the generated models. Keyed off the authenticated caller (the
 * `SecurityIdentity` principal), never an id in the request, so it is no cross-account read. Central auth
 * (`@Authenticated`, cr-0012): a missing/invalid token is a generic 401 before this runs. The server performs no
 * content crypto — the blob it serves and stores is opaque.
 *
 * Unlike key-material, a missing store is NOT an inconsistency: a born-empty account simply has no pins yet, so
 * GET returns `{ blob: null, version: 0 }` (never a 500). PUT is version-guarded optimistic concurrency — a stale
 * version is a `409 stale_version` so the client re-reads, union-merges, and retries.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; reads and writes only the caller's own sealed pin blob — one row, size-capped by " +
        "the contract. A session is attributable and revocable, so there is no anonymous budget to bound.",
)
class AccountIdentityPinsResource(
    private val handler: IdentityPinsHandler,
    private val identity: SecurityIdentity,
) : AccountIdentityPinsApi {

    override fun getIdentityPins(): Response {
        val view = handler.forAccount(identity.principal.name)
        return Response.ok(IdentityPins(blob = view.blob, version = view.version)).build()
    }

    override fun putIdentityPins(identityPinsUpdate: IdentityPinsUpdate): Response =
        when (val r = handler.store(identity.principal.name, identityPinsUpdate.blob, identityPinsUpdate.version.validated())) {
            is PutResult.Written -> Response.ok(IdentityPins(blob = identityPinsUpdate.blob, version = r.version)).build()
            is HttpFailure -> httpError(r)
        }
}
