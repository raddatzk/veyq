package com.veyq.app.shared.blobstore

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration
import java.util.Optional

/**
 * The blob-transport port (ADR 0013): the backend's control plane signs short-TTL presigned URLs and stats
 * objects; the **bytes never flow through it**. Operator-blind — the store only ever holds AEAD ciphertext
 * sealed client-side. One object key per chunk: `storageKey/<index>`.
 *
 * [canPresign] is the capability switch: an [S3BlobStore] presigns + stats real objects, the
 * [InMemoryBlobStore] cannot presign and has no transport path (`%test`-only metadata stub — a configured store
 * is a hard requirement of `%dev`/`%prod`, enforced by [BlobStoreStartupCheck]; the byte transfer itself is
 * exercised by opt-in e2e against a real S3-compatible store).
 */
interface BlobStore {
    /** True when the store can produce presigned URLs (and therefore carry bytes); false for the dev store. */
    val canPresign: Boolean

    /** A short-TTL presigned PUT URL for one chunk object, or null when the store cannot presign. */
    fun presignPut(key: String): String?

    /** A short-TTL presigned GET URL for one chunk object, or null when the store cannot presign. */
    fun presignGet(key: String): String?

    /** Stored object size in bytes, or null if the object is absent (or the store has no transport). */
    fun stat(key: String): Long?

    /**
     * Delete chunk objects and **return the keys it could not delete** — an empty list means every key is gone
     * (ADR 0062 Decision 2). The failure channel is a return value, not an exception, because a batch is partly
     * deletable and *which* keys failed is what decides whether a reclaim row stays; and it is in the type at all
     * because this returned `Unit` while [S3BlobStore] swallowed every per-key failure, so no caller could report
     * one even if it wanted to. Absent keys count as deleted: a reclaim row enumerates a session's full declared
     * `chunk_count`, of which an abandoned upload staged an unknown subset.
     *
     * The one caller is [StoreReclaimSweep]; `scripts/validate_store_reclaim_arity.py` keeps it the only one.
     * Implementations do not throw — the sweep treats a throw as a wholly-refused batch, which is a deferral,
     * not a drop.
     */
    fun delete(keys: List<String>): List<String>

    companion object {
        /** The object key for chunk [index] under a blob's [storageKey] prefix. */
        fun chunkKey(storageKey: String, index: Int): String = "$storageKey/$index"
    }
}

/**
 * `%test`-only store (cr-0105): holds no bytes and **cannot presign** (ADR 0013 §1). It exists purely so the
 * hermetic slice suite can drive the metadata control plane (`start`/`complete`/`list` — blob/media rows, the
 * wrap, the budget *declaration*) without a real store; the byte transfer is covered by the opt-in store e2e.
 * `%dev`/`%prod` never reach it — [BlobStoreStartupCheck] fails loud there if no store is configured.
 */
class InMemoryBlobStore : BlobStore {
    override val canPresign = false
    override fun presignPut(key: String): String? = null
    override fun presignGet(key: String): String? = null
    override fun stat(key: String): Long? = null

    /**
     * A no-op that fails at nothing, so it refuses nothing: the empty list is the honest answer here, not a
     * convenient one — this store holds no bytes, so every key it is handed is already absent.
     *
     * It is still an **oracle hole**: the reclaim sweep is specified as "hand the row's keys to the store, then
     * settle the row", and against this store the first half is invisible — a sweep that silently dropped its
     * `delete(…)` call would pass the whole hermetic suite and clear every reclaim row. Do not assert the store
     * half by reading what a store method *returns*; that proves the keys were computed, never that they
     * arrived. Assert it through the test-scope `RecordingBlobStore` `@Alternative`, which records what this was
     * handed and delegates the rest.
     */
    override fun delete(keys: List<String>): List<String> = emptyList()
}

/**
 * Production store over **any S3-compatible endpoint** (Garage, AWS S3, Cloudflare R2, Backblaze B2, …, ADR
 * 0013 §1) via the **vendor-neutral AWS SDK for Java v2** — not tied to any single vendor's client. Two
 * objects: an [S3Client] connects to the internal endpoint for `stat`/`delete`; a **sign-only** [S3Presigner]
 * (built on the client-reachable public endpoint, §4) computes presigned URLs by local HMAC and never
 * connects. Path-style addressing is forced so custom endpoints resolve. Credentials come from operator env,
 * are never persisted, logged, or returned by any endpoint.
 */
class S3BlobStore(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
    private val presignTtlSeconds: Int,
) : BlobStore {
    override val canPresign = true

    override fun presignPut(key: String): String {
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        )
        return presigned.url().toString()
    }

    override fun presignGet(key: String): String {
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        )
        return presigned.url().toString()
    }

    override fun stat(key: String): Long? =
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength()
        } catch (e: NoSuchKeyException) {
            null // the object is not present
        }

    /**
     * Per-key, and a failure is **reported** rather than swallowed. This method used to be
     * `keys.forEach { runCatching { … } }` returning `Unit`: a 503, a network blip or an expired credential left
     * the object standing while the caller saw a normal return and dropped the only pointer to it. The
     * `runCatching` stays — one unreachable key must not cost the batch its other keys — but what it catches now
     * leaves the method.
     *
     * `NoSuchKeyException` is deliberately NOT special-cased: `deleteObject` is idempotent at every
     * S3-compatible store and answers 204 for an absent key, which is the outcome the caller wants anyway.
     */
    override fun delete(keys: List<String>): List<String> =
        keys.filter { key ->
            runCatching { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) }.isFailure
        }
}

/**
 * Picks the store from config: a non-blank `veyq.blob-store.endpoint` ⇒ [S3BlobStore], otherwise the
 * [InMemoryBlobStore] (the default for dev/test). The public endpoint (client-reachable) is used for signing;
 * empty ⇒ sign against the internal endpoint (external S3 / any publicly reachable store, ADR 0013 §4).
 */
@ApplicationScoped
class BlobStoreProducer(
    config: VeyqConfig,
) {
    private val endpoint: Optional<String> = config.blobStore().endpoint()
    private val publicEndpoint: Optional<String> = config.blobStore().publicEndpoint()
    private val accessKey: Optional<String> = config.blobStore().accessKey()
    private val secretKey: Optional<String> = config.blobStore().secretKey()
    private val bucket: String = config.blobStore().bucket()
    private val region: String = config.blobStore().region()
    private val presignTtlSeconds: Int = config.blobStore().presignTtlSeconds()

    @Produces
    @ApplicationScoped
    fun blobStore(): BlobStore {
        val endpointUrl = endpoint.orElse("")
        // A blank endpoint only reaches here in %test — [BlobStoreStartupCheck] already aborted %dev/%prod boot.
        if (endpointUrl.isBlank()) return InMemoryBlobStore()
        val creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey.orElse(""), secretKey.orElse("")),
        )
        // Path-style: custom endpoints (Garage/MinIO/…) can't serve virtual-host-style bucket subdomains.
        val pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build()
        val s3 = S3Client.builder()
            .endpointOverride(URI.create(endpointUrl))
            .region(Region.of(region))
            .credentialsProvider(creds)
            .serviceConfiguration(pathStyle)
            .build()
        val signEndpoint = publicEndpoint.orElse("").ifBlank { endpointUrl }
        val presigner = S3Presigner.builder()
            .endpointOverride(URI.create(signEndpoint))
            .region(Region.of(region))
            .credentialsProvider(creds)
            .serviceConfiguration(pathStyle)
            .build()
        return S3BlobStore(s3, presigner, bucket, presignTtlSeconds)
    }
}
