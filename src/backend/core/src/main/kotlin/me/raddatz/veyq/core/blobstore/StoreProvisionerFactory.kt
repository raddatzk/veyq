package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/** What [StoreProvisionerFactory.forBoot] brings up, and from which source. */
data class BootProvisioning(val provisioner: StoreProvisioner, val source: Source) {
    enum class Source {
        /** The store setting in use. */
        SETTING,

        /** The bundled-store hint, because no store is in use — whether or not one is pending. */
        BUNDLED_HINT,
    }
}

/**
 * Turns a store into a [StoreProvisioner], **or into none at all**.
 *
 * "None at all" is the important half and is not a degraded case: an S3-compatible store — the default type —
 * means veyq provisions nothing, so [StoreProvisioningRunner] reports [StoreProvisioningState.NOT_ATTEMPTED] for it.
 * That is what makes ADR 0067 Decision 1's *"an unknown store is never poked"* true structurally: there is no Garage
 * code for an `S3` store to reach, because none was ever built for it.
 *
 * **Which store is provisioned at boot** follows ADR 0089: the store setting when one is set (a Garage one is
 * provisioned, an S3-compatible one is not), and otherwise the deployment's bundled-store hint — so a fresh install,
 * the target of a restore above all, has its bundled bucket before any store is in use — and the server then adopts the
 * bundled store where nothing is saved (ADR 0089 decision 3, `BundledStoreAdoption`). The check provisions a Garage
 * setting again, synchronously, before it probes ([forConnection]).
 *
 * **A plain factory rather than a `@Produces` method**, deliberately: a producer for a normal-scoped bean may
 * not return `null` at all, and Kotlin's own `?` says "sometimes there is nothing" with less machinery.
 */
@ApplicationScoped
class StoreProvisionerFactory(
    private val config: VeyqConfig,
    private val settings: StoreSettings,
    private val hint: BundledStoreHint,
) {
    private val log = Logger.getLogger(StoreProvisionerFactory::class.java)

    /**
     * What to provision at boot: the setting's Garage, or with none in use the bundled hint's, or nothing — and which of
     * the two it is, so the worker knows it brought up the hint's Garage for an instance with no store in use, the one
     * case after which the server adopts the bundled store ([BootProvisioning.Source.BUNDLED_HINT]).
     */
    fun forBoot(): BootProvisioning? = when (val current = settings.current()) {
        is StoreResolution.Ready -> forConnection(current.connection)?.let { BootProvisioning(it, BootProvisioning.Source.SETTING) }
        StoreResolution.NotSet -> hint.connection?.let { forConnection(it) }?.let { BootProvisioning(it, BootProvisioning.Source.BUNDLED_HINT) }
        is StoreResolution.Unavailable -> {
            if (current.hintMissing) {
                log.warn("The bundled store is chosen, but this deployment passes no bundled-store hint — nothing is provisioned.")
            } else {
                log.warnf("The store's %s cannot be opened under the current config-secret key — nothing is provisioned until it is entered again.", current.unreadable)
            }
            null
        }
    }

    /** The provisioner [connection]'s type calls for, or `null` when veyq must provision nothing (`S3`). */
    fun forConnection(connection: StoreConnection): StoreProvisioner? {
        if (connection.type != StoreType.GARAGE) return null
        val adminEndpoint = connection.garageAdminEndpoint.orEmpty()
        val adminToken = connection.garageAdminToken.orEmpty()
        if (adminEndpoint.isBlank() || adminToken.isBlank()) {
            // The setting is validated on write and the hint is complete or absent, so this is unreachable short of
            // a hand-edited row — reported, never thrown: provisioning is no startup requirement (ADR 0067 Decision 3).
            log.warn("A Garage store without its admin endpoint and token cannot be provisioned.")
            return null
        }
        val garage = config.blobStore().garage()
        return GarageStoreProvisioner(
            adminEndpoint = adminEndpoint,
            adminToken = adminToken,
            bucket = connection.bucket,
            accessKey = connection.accessKey,
            secretKey = connection.secretKey,
            nodeZone = garage.nodeZone(),
            nodeCapacity = garage.nodeCapacity(),
            // A separate client from the request path's, so nothing about provisioning can touch the client the
            // request path presigns with, and it reaches the provisioner as nothing wider than BucketCorsPolicy.
            cors = S3BucketCorsPolicy(BlobStoreProducer.s3Client(connection)),
            corsOrigins = CORS_ORIGINS,
        )
    }

    private companion object {
        /**
         * `*`, and it stays `*` — ADR 0013 §4 measured that a multi-tenant instance on the bundled store has
         * exactly one working policy, because Garage v2.0.0 echoes a two-origin list back as a comma-joined
         * `Access-Control-Allow-Origin` that the browser then rejects. The reasoning lives in full at
         * [GarageStoreProvisioner.ensureCors]; this is the value, in one place, so there is one thing to
         * change if that table ever changes.
         */
        val CORS_ORIGINS = listOf("*")
    }
}
