package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.generated.api.InstanceApi
import me.raddatz.veyq.core.generated.model.InstanceConfigSecret
import me.raddatz.veyq.core.generated.model.InstanceRelease
import me.raddatz.veyq.core.generated.model.InstanceStore
import me.raddatz.veyq.core.generated.model.InstanceStoreCheck
import me.raddatz.veyq.core.generated.model.InstanceStoreCheckStep
import me.raddatz.veyq.core.generated.model.InstanceStoreSecret
import me.raddatz.veyq.core.generated.model.InstanceStoreSetting
import me.raddatz.veyq.core.generated.model.InstanceStoreType
import me.raddatz.veyq.core.generated.model.InstanceStoreUpdate
import me.raddatz.veyq.core.blobstore.StoreCheckResult
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import java.time.ZoneOffset
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
 * So does the config-secret state (ADR 0050), read through [ConfigSecretKeyGuard.readState] because a restore's
 * verification read must change nothing.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only reads of instance-wide values — a release stamp fixed at startup, the " +
        "store-provisioning state a background runner last reached, the config-secret state, and the store " +
        "setting — and the owner's own store write and check (the check reaches only the store the owner set, " +
        "each step bounded by a ten-second timeout). There is no per-caller state to enumerate and no work an " +
        "anonymous caller could amplify, and the role gate already bounds the caller set to one account.",
)
class InstanceResource(
    private val release: ReleaseIdentity,
    private val storeProvisioning: StoreProvisioningReport,
    private val configSecret: ConfigSecretKeyGuard,
    private val store: StoreHandler,
) : InstanceApi {

    override fun getInstanceRelease(): Response =
        Response.ok(InstanceRelease(release = release.version)).build()

    override fun getInstanceStoreProvisioning(): Response =
        Response.ok(InstanceStoreProvisioning(state = storeProvisioning.state())).build()

    override fun getInstanceConfigSecret(): Response =
        Response.ok(
            InstanceConfigSecret(
                state = when (configSecret.readState()) {
                    ConfigSecretKeyState.OK -> InstanceConfigSecret.State.OK
                    ConfigSecretKeyState.ABSENT -> InstanceConfigSecret.State.ABSENT
                    ConfigSecretKeyState.MISMATCH -> InstanceConfigSecret.State.MISMATCH
                },
            ),
        ).build()

    override fun getInstanceStore(): Response = Response.ok(store.read().toWire()).build()

    override fun setInstanceStore(instanceStoreUpdate: InstanceStoreUpdate): Response =
        when (val outcome = store.set(instanceStoreUpdate.toUpdate())) {
            is StoreSetOutcome.Stored -> Response.ok(outcome.view.toWire()).build()
            is HttpFailure -> httpError(outcome)
        }

    override fun checkInstanceStore(): Response =
        when (val outcome = store.check()) {
            is StoreCheckRun.Done -> Response.ok(outcome.result.toWire()).build()
            is HttpFailure -> httpError(outcome)
        }

    private fun InstanceStoreUpdate.toUpdate() = StoreUpdate(
        bundled = bundled == true,
        type = type?.let { StoreType.valueOf(it.name) },
        endpoint = endpoint, publicEndpoint = publicEndpoint, bucket = bucket, region = region, accessKey = accessKey,
        secretKey = secretKey, garageAdminEndpoint = garageAdminEndpoint, garageAdminToken = garageAdminToken,
    )

    private fun StoreView.toWire() = InstanceStore(
        bundledAvailable = bundledAvailable,
        blobsStored = blobsStored,
        setting = setting?.let {
            InstanceStoreSetting(
                bundled = it.bundled,
                type = InstanceStoreType.valueOf(it.type.name),
                endpoint = it.endpoint, publicEndpoint = it.publicEndpoint, bucket = it.bucket, region = it.region,
                accessKey = it.accessKey, garageAdminEndpoint = it.garageAdminEndpoint,
                secretKeySet = it.secretKeySet, garageAdminTokenSet = it.garageAdminTokenSet,
                unreadable = it.unreadable.map { secret -> InstanceStoreSecret.valueOf(secret.name) },
            )
        },
        lastCheck = lastCheck?.toWire(),
    )

    private fun StoreCheckResult.toWire() = InstanceStoreCheck(
        passed = passed,
        checkedAt = checkedAt.atOffset(ZoneOffset.UTC),
        steps = steps.map {
            InstanceStoreCheckStep(
                step = InstanceStoreCheckStep.Step.valueOf(it.step.name),
                outcome = InstanceStoreCheckStep.Outcome.valueOf(it.outcome.name),
                detail = it.detail,
            )
        },
    )
}
