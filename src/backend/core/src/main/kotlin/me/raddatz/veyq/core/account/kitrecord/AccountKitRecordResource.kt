package me.raddatz.veyq.core.account.kitrecord

import me.raddatz.veyq.core.generated.api.AccountKitRecordApi
import me.raddatz.veyq.core.generated.model.KitRecord
import me.raddatz.veyq.core.generated.model.KitRecordUpdate
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the account's kit record (ADR 0157 decision 3, uc.account.renew-emergency-kit). Implements the
 * generated [AccountKitRecordApi]; thin (ADR 0001) — delegates to [KitRecordHandler]. Keyed off the authenticated caller
 * (the `SecurityIdentity` principal), never an id in the request, so it reads and writes only the caller's own record.
 * Central auth (`@Authenticated`, cr-0012): a missing or invalid token is a generic 401 before this runs. The server
 * performs no content crypto — the blob it serves and stores is opaque.
 *
 * No record is no inconsistency: an account whose kit predates the record has none, and `GET` answers `{}` (the blob
 * absent — the client reads layout 1). `PUT` replaces the record whole (last writer wins) and answers `204`.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; reads and writes only the caller's own sealed kit record — one row, size-capped by the " +
            "contract, replaced in place. A session is attributable and revocable, so there is no anonymous budget to bound.",
)
class AccountKitRecordResource(
    private val handler: KitRecordHandler,
    private val identity: SecurityIdentity,
) : AccountKitRecordApi {

    override fun getKitRecord(): Response =
        Response.ok(KitRecord(blob = handler.forAccount(identity.principal.name))).build()

    override fun putKitRecord(kitRecordUpdate: KitRecordUpdate): Response =
        if (handler.store(identity.principal.name, kitRecordUpdate.blob)) {
            Response.noContent().build()
        } else {
            httpError(Failures.authenticationFailed)
        }
}
