package com.veyq.core.account.deviceregister

import com.veyq.app.generated.api.AccountDeviceRegisterApi
import com.veyq.app.generated.model.DeviceRegister
import com.veyq.app.generated.model.DeviceRegisterUpdate
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpError
import com.veyq.core.ratelimit.RateLimitExempt
import com.veyq.core.validation.validated
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the operator-blind device register (en.account.device-register, ADR 0004). Implements the
 * generated [AccountDeviceRegisterApi]; thin (ADR 0001) — delegates to [DeviceRegisterHandler] and maps the
 * stored ciphertext + version to the generated models. Keyed off the authenticated caller (the `SecurityIdentity`
 * principal), never an id in the request, so it is no cross-account read. Central auth (`@Authenticated`,
 * cr-0012): a missing/invalid token is a generic 401 before this runs. The server performs no content crypto —
 * the blob it serves and stores is opaque.
 *
 * A missing register is NOT an inconsistency: a born-empty account simply has no devices recorded yet, so GET
 * returns `{ blob: null, version: 0 }` (never a 500), and the sessions view degrades to unnamed rows that are
 * still fully revocable (uc.account.sessions §E4). PUT is version-guarded optimistic concurrency — a stale
 * version is a `409 stale_version` so the client re-reads, merges, and retries.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; reads and writes only the caller's own sealed register blob — one row, size-capped by " +
        "the contract. A session is attributable and revocable, so there is no anonymous budget to bound.",
)
class AccountDeviceRegisterResource(
    private val handler: DeviceRegisterHandler,
    private val identity: SecurityIdentity,
) : AccountDeviceRegisterApi {

    override fun getDeviceRegister(): Response {
        val view = handler.forAccount(identity.principal.name)
        return Response.ok(DeviceRegister(blob = view.blob, version = view.version)).build()
    }

    override fun putDeviceRegister(deviceRegisterUpdate: DeviceRegisterUpdate): Response =
        when (val r = handler.store(identity.principal.name, deviceRegisterUpdate.blob, deviceRegisterUpdate.version.validated())) {
            is PutResult.Written -> Response.ok(DeviceRegister(blob = deviceRegisterUpdate.blob, version = r.version)).build()
            is HttpFailure -> httpError(r)
        }
}
