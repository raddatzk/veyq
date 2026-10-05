package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * Turns the declared store type into a [StoreProvisioner], **or into none at all**.
 *
 * "None at all" is the important half and is not a degraded case: `type: s3` — the default — means veyq
 * provisions nothing, so [StoreProvisioningRunner] gets `null` and reports
 * [StoreProvisioningState.NOT_ATTEMPTED] for the life of the instance. That is what makes ADR 0067 Decision 1's
 * *"an unknown store is never poked"* true structurally: there is no Garage code for a `type: s3` instance to
 * reach, because none was ever built (ADR 0067 § Consequences bounds the cost of the type this way — a shared
 * path that "mostly" branches is the version that stops being bounded).
 *
 * **A plain factory rather than a `@Produces` method**, deliberately: a producer for a normal-scoped bean may
 * not return `null` at all, and `Instance.isUnsatisfied` reports whether a *bean* exists rather than what it
 * produced — so the CDI spelling of "sometimes there is nothing" would have been a `@Dependent` producer plus a
 * nullable `get()`, which is more machinery than Kotlin's own `?` for the same answer.
 */
@ApplicationScoped
class StoreProvisionerFactory(
    private val config: VeyqConfig,
) {
    private val log = Logger.getLogger(StoreProvisionerFactory::class.java)

    /** The provisioner this instance's store type calls for, or `null` when veyq must provision nothing. */
    fun forConfiguredStore(): StoreProvisioner? {
        val store = config.blobStore()
        if (store.type() != StoreType.GARAGE) return null

        val adminEndpoint = store.garage().adminEndpoint().orElse("")
        val adminToken = store.garage().adminToken().orElse("")
        if (adminEndpoint.isBlank() || adminToken.isBlank()) {
            // A misconfiguration, not a failure of the boot: ADR 0013 §1 makes the *byte path* a hard startup
            // requirement and BlobStoreStartupCheck already enforces it; the admin plane is not that, and
            // aborting here would put a network-shaped dependency back into the startup path Decision 3
            // removed it from.
            log.warn(
                "veyq.blob-store.type=garage but veyq.blob-store.garage.admin-endpoint/admin-token are not both " +
                    "set — the store will not be provisioned. Set both, or declare veyq.blob-store.type=s3 if " +
                    "you bring the store up yourself.",
            )
            return null
        }

        return GarageStoreProvisioner(
            adminEndpoint = adminEndpoint,
            adminToken = adminToken,
            bucket = store.bucket(),
            accessKey = store.accessKey().orElse(""),
            secretKey = store.secretKey().orElse(""),
            nodeZone = store.garage().nodeZone(),
            nodeCapacity = store.garage().nodeCapacity(),
            cors = S3BucketCorsPolicy(provisioningS3Client()),
            corsOrigins = CORS_ORIGINS,
        )
    }

    /**
     * A short-lived S3 client for the provisioning seam's one genuinely-S3 step, built the same way
     * [BlobStoreProducer] builds the request-path one (path-style addressing, the internal endpoint, the store
     * credentials) — but a separate instance, so nothing about provisioning can touch the client the request
     * path presigns with, and so it never reaches [GarageStoreProvisioner] as anything wider than
     * [BucketCorsPolicy]'s two methods.
     */
    private fun provisioningS3Client(): S3Client {
        val store = config.blobStore()
        return S3Client.builder()
            .endpointOverride(URI.create(store.endpoint().orElse("")))
            .region(Region.of(store.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(store.accessKey().orElse(""), store.secretKey().orElse("")),
                ),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
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
