package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Duration

/**
 * Fail-loud guard for the store values that are still configuration (ADR 0089 — the store itself is a Server-Owner
 * setting now, and an instance without one STARTS: it answers `/api/setup` and refuses uploads with a reason, so the
 * boot no longer requires a store at all).
 *
 * It refuses a bundled-store hint whose `veyq.bundled-store.bucket` breaks S3's bucket-naming rules, in **every**
 * mode — the value is not only a store's business: it becomes the path of every blobs router
 * `TraefikFileIngressSink` renders (ADR 0013 §4), so it is checked, not trusted. A setting's bucket is held to the
 * same rule when it is written.
 *
 * And it refuses, in every mode, a `veyq.blob.upload.retention` that does not outlast
 * `veyq.blob-store.presign-ttl-seconds`: the retention is both the upload-session purge's idle bound and the store
 * reclaim's not-before (ADR 0062 Decision 1), and both hold only while no PUT signed under the TTL can still land.
 */
@ApplicationScoped
class BlobStoreStartupCheck(
    config: VeyqConfig,
) {
    private val bundledBucket: String = config.bundledStore().bucket()
    private val uploadRetention: Duration = config.blob().upload().retention()
    private val presignTtlSeconds: Int = config.blobStore().presignTtlSeconds()

    fun onStart(@Observes ev: StartupEvent) {
        check(isValidBucketName(bundledBucket)) { bucketNameRefusal(bundledBucket) }
        retentionRefusal(uploadRetention, presignTtlSeconds)?.let { error(it) }
    }

    companion object {
        /** What an operator reads when the boot aborts on the name, so it names the key to fix. */
        fun bucketNameRefusal(name: String): String =
            "veyq.bundled-store.bucket=$name is not a valid S3 bucket name: 3-63 characters of a-z, 0-9, '.' and '-', " +
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
