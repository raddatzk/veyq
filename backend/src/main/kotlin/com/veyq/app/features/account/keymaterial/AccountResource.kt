package com.veyq.app.features.account.keymaterial

import com.veyq.app.generated.api.AccountApi
import com.veyq.app.generated.model.AccountKeyMaterial
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.shared.http.FailureStatus.SERVER_ERROR
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.http.httpFailure
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter serving the authenticated caller's wrapped key material for client-side unlock
 * (uc.account.unlock). Implements the generated [AccountApi]; thin (ADR 0001) — extracts the bearer token,
 * delegates the resolve + load to [KeyMaterialHandler], and maps the stored ciphertext to the generated
 * models. The server performs no content crypto: every field served here is opaque ciphertext or the public
 * suite id.
 *
 * The material is keyed off the authenticated caller (the `SecurityIdentity` principal), never an id in the
 * request, so it is no cross-account read. Authentication is central (`@Authenticated`, cr-0012): a missing,
 * malformed, expired, or revoked token is a single generic 401 before this runs (auth ≠ unlock — a stolen token
 * yields only ciphertext, useless without the password and Secret Key). That last property holds because the
 * handler serves only the **interactive** wraps: the password-less RECOVERY wrap is withheld here and released
 * only by the (future) email-gated recovery flow (cr-0052), so a session alone never opens the vault.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated; a single read of the caller's own key material. No amplification and no oracle — " +
        "the caller already holds the session those wraps belong to.",
)
class AccountResource(
    private val handler: KeyMaterialHandler,
    private val identity: SecurityIdentity,
) : AccountApi {

    override fun getAccountKeyMaterial(): Response {
        // The caller is authenticated, so missing material is a server inconsistency (every account is created
        // with its wraps), not an auth failure → a controlled 500, never a misleading 401.
        val material = handler.forUser(identity.principal.name)
            ?: return httpError(httpFailure("internal_error", SERVER_ERROR))

        // Map stored strings to the generated enums by their wire VALUE, not Enum.valueOf (which matches by
        // constant NAME and throws on any drift). Unknown wrap methods are skipped (forward-compatible);
        // an unknown suite the server can't serve is a controlled 500, never an uncaught exception.
        val suiteId = AccountKeyMaterial.SuiteId.entries.firstOrNull { it.value == material.suiteId }
            ?: return httpError(httpFailure("internal_error", SERVER_ERROR))
        val wraps = material.wraps.mapNotNull { wrap ->
            val method = MasterKeyWrap.Method.entries.firstOrNull { it.value == wrap.method }
            if (method == null) {
                // Forward-compatible skip, but never silent: a method the current enum doesn't know is
                // either a newer-client wrap (fine) or data corruption (e.g. a dropped PASSWORD wrap would
                // lock the account out) — surface it so an operator can tell the two apart. The method
                // string is low-sensitivity (a small known set), no ciphertext or secret is logged.
                log.warnf("Skipping master-key wrap with unrecognised method '%s'", wrap.method)
                null
            } else {
                MasterKeyWrap(id = wrap.id, method = method, wrappedKey = wrap.wrappedKey, kdfParams = wrap.kdfParams)
            }
        }

        return Response.ok(
            AccountKeyMaterial(
                suiteId = suiteId,
                wraps = wraps,
                wrappedIdentityPrivateKey = material.wrappedIdentityPrivateKey,
            ),
        ).build()
    }

    private companion object {
        private val log: Logger = Logger.getLogger(AccountResource::class.java)
    }
}
