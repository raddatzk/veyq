package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Duration
import java.util.Optional

/**
 * Fail-loud guard (ADR 0013 §1, cr-0105): a byte-path object store is a **hard requirement** of any real instance.
 * Outside the hermetic `%test` profile a blank `veyq.blob-store.endpoint` **aborts startup** — media is not an
 * optional feature, so an instance without a store is misconfigured, not gracefully degraded (there is no
 * client-facing `directTransport` flag to soft-gate on any more). `%test` keeps the metadata-only in-memory stub.
 *
 * It also refuses a `veyq.blob-store.bucket` that breaks S3's bucket-naming rules, in **every** mode, `%test`
 * included — unlike the endpoint, the name is never absent. The value is not only a store's business: it becomes the
 * path of every blobs router `TraefikFileIngressSink` renders (ADR 0013 §4), so it is checked, not trusted.
 *
 * And it refuses, in every mode, a `veyq.blob.upload.retention` that does not outlast
 * `veyq.blob-store.presign-ttl-seconds`: the retention is both the upload-session purge's idle bound and the store
 * reclaim's not-before (ADR 0062 Decision 1), and both hold only while no PUT signed under the TTL can still land.
 */
@ApplicationScoped
class BlobStoreStartupCheck(
    config: VeyqConfig,
) {
    private val endpoint: Optional<String> = config.blobStore().endpoint()
    private val bucket: String = config.blobStore().bucket()
    private val uploadRetention: Duration = config.blob().upload().retention()
    private val presignTtlSeconds: Int = config.blobStore().presignTtlSeconds()

    fun onStart(@Observes ev: StartupEvent) {
        check(hasStore(endpoint.orElse(""), LaunchMode.current())) { REQUIRED_MSG }
        check(isValidBucketName(bucket)) { bucketNameRefusal(bucket) }
        retentionRefusal(uploadRetention, presignTtlSeconds)?.let { error(it) }
    }

    companion object {
        const val REQUIRED_MSG =
            "veyq.blob-store.endpoint is required outside the %test profile: media needs a byte path (ADR 0013). " +
                "Run the bundled store (docker compose --profile s3 up -d) and set veyq.blob-store.endpoint + creds."

        /** A configured store is mandatory unless we are in the hermetic `%test` profile (in-memory stub). */
        fun hasStore(endpoint: String, mode: LaunchMode): Boolean =
            mode == LaunchMode.TEST || endpoint.isNotBlank()

        /** What an operator reads when the boot aborts on the name, so it names the key to fix. */
        fun bucketNameRefusal(name: String): String =
            "veyq.blob-store.bucket=$name is not a valid S3 bucket name: 3-63 characters of a-z, 0-9, '.' and '-', " +
                "starting and ending with a letter or digit, no '..', and not shaped like an IPv4 address."

        /**
         * Null when the upload retention outlasts the presign TTL, else what the operator reads. Equal is refused: a
         * PUT signed in the TTL's last second may still be streaming when the window closes.
         */
        fun retentionRefusal(retention: Duration, presignTtlSeconds: Int): String? =
            if (retention > Duration.ofSeconds(presignTtlSeconds.toLong())) null
            else "veyq.blob.upload.retention=$retention must exceed veyq.blob-store.presign-ttl-seconds=" +
                "$presignTtlSeconds: an upload session idle past it is purged and its staged keys reclaimed while a " +
                "PUT signed under that TTL can still land (ADR 0062 Decision 1)."

        private val BUCKET_NAME = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        private val IPV4_SHAPED = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

        /** S3's general-purpose bucket-naming rules — the subset every S3-compatible store accepts. */
        fun isValidBucketName(name: String): Boolean =
            BUCKET_NAME.matches(name) && ".." !in name && !IPV4_SHAPED.matches(name)
    }
}
