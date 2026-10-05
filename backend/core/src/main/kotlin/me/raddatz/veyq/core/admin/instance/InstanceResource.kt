package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.generated.api.InstanceApi
import me.raddatz.veyq.core.generated.model.InstanceRelease
import me.raddatz.veyq.core.generated.model.InstanceStoreProvisioning
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the Server Owner's operational reads about the running instance. Implements the generated
 * [InstanceApi]; thin (ADR 0001) — it reads [ReleaseIdentity] and [StoreProvisioningReport] and maps them onto
 * the wire models.
 *
 * The gate is the class-level **@RolesAllowed**: a missing/invalid token → 401, a non-owner → 403, both
 * produced centrally by the security ExceptionMappers. That gate is the point of the endpoint's existence and
 * not a default: a precise release number is what maps an instance to a list of known vulnerabilities, and a
 * self-hosted instance is the kind that upgrades late — so this value never joins the pre-auth
 * `GET /api/setup`, whose response is unchanged (ADR 0057 §4). The store-provisioning read sits behind the
 * same gate for the same reason: it describes the operator's own infrastructure and what is wrong with it.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only reads of instance-wide values already held in memory — a release stamp fixed " +
        "at startup, and the store-provisioning state a background runner last reached. There is no " +
        "per-caller state to enumerate and no work to amplify (neither read touches the store or the " +
        "database), and the role gate already bounds the caller set to one account.",
)
class InstanceResource(
    private val release: ReleaseIdentity,
    private val storeProvisioning: StoreProvisioningReport,
) : InstanceApi {

    override fun getInstanceRelease(): Response =
        Response.ok(InstanceRelease(release = release.version)).build()

    override fun getInstanceStoreProvisioning(): Response =
        Response.ok(InstanceStoreProvisioning(state = storeProvisioning.state())).build()
}
