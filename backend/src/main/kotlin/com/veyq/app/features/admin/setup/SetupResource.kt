package com.veyq.app.features.admin.setup

import com.veyq.app.generated.api.SetupApi
import com.veyq.app.generated.model.Capability
import com.veyq.app.generated.model.CreateServerOwnerRequest
import com.veyq.app.generated.model.SetupResult
import com.veyq.app.generated.model.SetupStatus
import com.veyq.app.generated.model.Version
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.httpError
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.identity.normalizeEmail
import com.veyq.app.shared.ratelimit.RateLimit
import com.veyq.app.shared.ratelimit.RateLimitKey
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

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
    recoveryMasterKeyTag = recoveryMasterKeyTag,
    wraps = masterKeyWraps.map { WrapInput(method = it.method.value, wrappedKey = it.wrappedKey, kdfParams = it.kdfParams) },
)
