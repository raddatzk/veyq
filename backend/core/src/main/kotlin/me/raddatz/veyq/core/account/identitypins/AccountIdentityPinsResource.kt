package me.raddatz.veyq.core.account.identitypins

import me.raddatz.veyq.core.generated.api.AccountIdentityPinsApi
import me.raddatz.veyq.core.generated.model.IdentityPinEntry
import me.raddatz.veyq.core.generated.model.IdentityPinEntryPage
import me.raddatz.veyq.core.generated.model.IdentityPins
import me.raddatz.veyq.core.generated.model.IdentityPinsWrite
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the operator-blind identity-pin store (en.account.identity-pinning, ADR 0028, ADR 0104).
 * Implements the generated [AccountIdentityPinsApi]; thin (ADR 0001) — delegates to [IdentityPinsHandler] and maps
 * the stored ciphertext, ids and revision to the generated models. Keyed off the authenticated caller (the
 * `SecurityIdentity` principal), never an id in the request, so it is no cross-account read. Central auth
 * (`@Authenticated`, cr-0012): a missing/invalid token is a generic 401 before this runs. The server performs no
 * content crypto — the head and the entries it serves and stores are opaque.
 *
 * A missing store is NOT an inconsistency: a born-empty account has no pins yet, so the head reads
 * `{ blob: null, revision: 0 }` and the listing is empty. A write is one compare-and-set on the head's revision — a
 * stale one is a `409 stale_revision`, so the client re-reads and re-applies.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; reads and writes only the caller's own sealed pin store — a head and entries bounded by the " +
        "contract (25,000 per store). A session is attributable and revocable, so there is no anonymous budget to bound.",
)
class AccountIdentityPinsResource(
    private val handler: IdentityPinsHandler,
    private val identity: SecurityIdentity,
) : AccountIdentityPinsApi {

    override fun getIdentityPins(): Response {
        val view = handler.forAccount(identity.principal.name)
        return Response.ok(IdentityPins(blob = view.blob, revision = view.revision)).build()
    }

    override fun listIdentityPinEntries(after: String?, limit: Int?): Response {
        val from = after?.takeIf { it.isNotEmpty() }
        val size = limit ?: MAX_PAGE
        if ((from != null && !PIN_ID.matches(from)) || size !in 1..MAX_PAGE) return httpError(Failures.invalidRequest)
        val page = handler.entries(identity.principal.name, from, size)
        return Response.ok(
            // `propertyEntries` is the wire's `entries`: the Kotlin generator reserves the name.
            IdentityPinEntryPage(propertyEntries = page.entries.map { IdentityPinEntry(it.pinId, it.entry) }, more = page.more),
        ).build()
    }

    override fun writeIdentityPins(identityPinsWrite: IdentityPinsWrite): Response {
        val write = identityPinsWrite.toPinWrite()
        if (write.deletesBesideReplaceAll()) return httpError(Failures.invalidRequest)
        if (write.contradictory()) return httpError(PinWrite.contradiction)
        return when (val r = handler.write(identity.principal.name, write)) {
            is PutResult.Written -> Response.ok(IdentityPins(blob = write.head, revision = r.revision)).build()
            is HttpFailure -> httpError(r)
        }
    }

    companion object {
        private const val MAX_PAGE = 1000
        private val PIN_ID = Regex("^[0-9a-f]{64}$")
    }
}

/** The wire write as the store takes it — shared by the rotation, whose request carries the same body. */
fun IdentityPinsWrite.toPinWrite(): PinWrite = PinWrite(
    expectedRevision = revision.validated(),
    head = head,
    put = put.map { PinEntry(it.pinId, it.entry) },
    delete = remove,
    replaceAll = replaceAll.validated(),
)
