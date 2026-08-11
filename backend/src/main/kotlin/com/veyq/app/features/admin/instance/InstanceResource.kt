package com.veyq.app.features.admin.instance

import com.veyq.app.generated.api.InstanceApi
import com.veyq.app.generated.model.InstanceRelease
import com.veyq.app.shared.identity.ServerRoles.SERVER_OWNER
import com.veyq.app.shared.ratelimit.RateLimitExempt
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the Server Owner's operational reads about the running instance. Implements the generated
 * [InstanceApi]; thin (ADR 0001) — it reads [ReleaseIdentity] and maps it onto the wire model.
 *
 * The gate is the class-level **@RolesAllowed**: a missing/invalid token → 401, a non-owner → 403, both
 * produced centrally by the security ExceptionMappers. That gate is the point of the endpoint's existence and
 * not a default: a precise release number is what maps an instance to a list of known vulnerabilities, and a
 * self-hosted instance is the kind that upgrades late — so this value never joins the pre-auth
 * `GET /api/setup`, whose response is unchanged (ADR 0057 §4).
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "A Server-Owner-only read of one instance-wide constant, computed once at startup. There is no " +
        "per-caller state to enumerate and no work to amplify, and the role gate already bounds the caller " +
        "set to one account.",
)
class InstanceResource(private val release: ReleaseIdentity) : InstanceApi {

    override fun getInstanceRelease(): Response =
        Response.ok(InstanceRelease(release = release.version)).build()
}
