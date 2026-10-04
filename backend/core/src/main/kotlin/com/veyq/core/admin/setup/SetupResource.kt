package com.veyq.core.admin.setup

import com.veyq.core.generated.api.SetupApi
import com.veyq.core.generated.model.Capability
import com.veyq.core.generated.model.CreateServerOwnerRequest
import com.veyq.core.generated.model.SetupResult
import com.veyq.core.generated.model.SetupStatus
import com.veyq.core.generated.model.Version
import com.veyq.core.http.FailureStatus.CONFLICT
import com.veyq.core.http.httpError
import com.veyq.core.http.httpFailure
import com.veyq.core.identity.normalizeEmail
import com.veyq.core.ratelimit.RateLimit
import com.veyq.core.ratelimit.RateLimitKey
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID
import com.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for first-run setup. Implements the generated [SetupApi]; thin — it maps to/from the
 * generated models and delegates to [SetupHandler] (ADR 0001). Field validation is enforced by @Valid
 * on the generated request model before this runs. Public (`@PermitAll`, cr-0012): setup runs pre-auth,
 * before any account exists.
 */
@PermitAll
@RateLimitExempt(
    reason = "First-run setup status is the client bootstrap read (instance salt, suites, capabilities, " +
        "version) — every client calls it before it can do anything, so throttling it throttles startup. " +
        "It is a constant, non-enumerable snapshot with no per-caller state. The one endpoint here that " +
        "writes, createServerOwner, overrides this with key = IP.",
)
class SetupResource(private val handler: SetupHandler) : SetupApi {

    override fun getSetupStatus(): Response {
        val state = handler.state()
        return Response.ok(
            SetupStatus(
                setupRequired = state.setupRequired,
                suites = state.suites,
                instanceSalt = state.instanceSalt,
                version = Version(major = state.versionMajor, build = state.versionBuild),
                capabilities = state.capabilities.map { Capability(id = it) },
            ),
        ).build()
    }

    // Per-IP bound on the pre-auth server-owner creation (defense in depth; not on the status read).
    @RateLimit(key = RateLimitKey.IP)
    override fun createServerOwner(createServerOwnerRequest: CreateServerOwnerRequest): Response =
        try {
            val userId = handler.createOwner(createServerOwnerRequest.toRegistration())
            Response.status(Response.Status.CREATED)
                .entity(SetupResult(userId = UUID.fromString(userId)))
                .build()
        } catch (e: SetupAlreadyComplete) {
            httpError(httpFailure("setup_already_complete", CONFLICT))
        }
}

private fun CreateServerOwnerRequest.toRegistration() = OwnerRegistration(
    email = normalizeEmail(email),
    suiteId = suiteId.value,
    identityPublicKey = identityPublicKey,
    wrappedIdentityPrivateKey = wrappedIdentityPrivateKey,
    authPublicKey = authPublicKey,
    secretKeyProofPublicKey = secretKeyProofPublicKey,
    wraps = masterKeyWraps.map { WrapInput(method = it.method.value, wrappedKey = it.wrappedKey, kdfParams = it.kdfParams) },
)
