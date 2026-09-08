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
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * A short-TTL presigned PUT URL for one chunk object, or null when the store cannot presign.
     *
     * [domain] is the **resolved tenant's own registered domain** — the one the request arrived on, looked up in
     * the `tenant` table, never a `Host` header echoed back (ADR 0063; the resolution itself lives in
     * `TenantHostResolver`). It is what makes the URL same-origin *for that tenant*, which is the whole of ADR
     * 0013 §4's no-CORS property on a server with more than one domain. `null` means the request resolved to no
     * tenant — the bootstrap domain, or a request that legitimately has none — and signs against the configured
     * default.
     */
    fun presignPut(key: String, domain: String? = null): String?

    /** A short-TTL presigned GET URL for one chunk object, or null when the store cannot presign. [domain] as in
     *  [presignPut]: the resolved tenant's own domain, or null for the bootstrap default. */
    fun presignGet(key: String, domain: String? = null): String?

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
    // The domain is ignored, and can be: this store has no endpoint to sign against in the first place.
    override fun presignPut(key: String, domain: String?): String? = null
    override fun presignGet(key: String, domain: String?): String? = null
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
 * 0013 §1) via the **vendor-neutral AWS SDK for Java v2** — not tied to any single vendor's client. An
 * [S3Client] connects to the internal endpoint for `stat`/`delete`; the **sign-only** [S3Presigner]s compute
 * presigned URLs by local HMAC and never connect. Path-style addressing is forced so custom endpoints resolve.
 * Credentials come from operator env, are never persisted, logged, or returned by any endpoint.
 *
 * **Signing is per-request, not per-instance** (ADR 0063, amending ADR 0013 §4). A presigned URL is only
 * same-origin — the whole reason there is no CORS policy to configure — if it is signed for the origin the
 * browser is actually on, so a server with a second tenant domain must sign for *that* domain. Hence one cached
 * presigner per resolved sign endpoint rather than one for the instance. Building a presigner is pure local
 * HMAC setup with no network I/O, so the cache is a convenience, not a pool; it is bounded by the number of
 * tenant domains and never invalidated, because a domain that stops being a tenant's simply stops being asked
 * for.
 *
 * **A per-domain endpoint only exists where the store is same-origin behind our own proxy** — i.e. where
 * `public-endpoint` is configured. With it blank (ADR 0013 §4's other branch: external S3, or any store already
 * publicly reachable at its own hostname) the endpoint has nothing to do with a tenant's domain, so [domainFor]
 * ignores the domain entirely rather than inventing a bucket host that does not exist.
 */
class S3BlobStore(
    private val s3: S3Client,
    private val bucket: String,
    private val presignTtlSeconds: Int,
    private val internalEndpoint: String,
    private val publicEndpoint: String,
    private val credentials: StaticCredentialsProvider,
    private val region: String,
) : BlobStore {
    override val canPresign = true

    private val presigners = ConcurrentHashMap<String, S3Presigner>()

    override fun presignPut(key: String, domain: String?): String {
        val presigned = presignerFor(domain).presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        )
        return presigned.url().toString()
    }

    override fun presignGet(key: String, domain: String?): String {
        val presigned = presignerFor(domain).presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build(),
        )
        return presigned.url().toString()
    }

    /** Keyed by the resolved endpoint, not by the domain: every domain that signs against the bootstrap default
     *  — the common case — then shares one presigner instead of one per caller. */
    private fun presignerFor(domain: String?): S3Presigner =
        presigners.computeIfAbsent(endpointFor(domain)) { endpoint ->
            S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()
        }

    /**
     * The bootstrap default, or that same URL with its **host** swapped for the tenant's domain — scheme, port
     * and path are kept, because they are properties of how this deployment exposes the store (`:443`, a path
     * prefix behind Traefik) and not of which tenant is asking.
     *
     * A domain that cannot be substituted (a malformed configured endpoint) falls back to the default rather
     * than throwing: signing for the wrong origin costs the client a CORS-blocked fetch, while throwing costs
     * them the upload. The domain reaching here is a *registered* one from the `tenant` table, never a header
     * value, so this is a robustness fallback and not a validation boundary — the validation already happened
     * when the row was written, and `TenantHostResolver` is what makes a spoofed `Host` resolve to nothing.
     */
    private fun endpointFor(domain: String?): String {
        val default = publicEndpoint.ifBlank { internalEndpoint }
        if (domain == null || publicEndpoint.isBlank()) return default
        return runCatching {
            val base = URI.create(publicEndpoint)
            URI(base.scheme, base.userInfo, domain, base.port, base.path, base.query, base.fragment).toString()
        }.getOrDefault(default)
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
        // The presigners are built lazily, one per resolved sign endpoint, because *which* endpoint a URL is
        // signed for is now a per-request answer (ADR 0063) — so this hands over the ingredients rather than a
        // finished presigner.
        return S3BlobStore(
            s3 = s3,
            bucket = bucket,
            presignTtlSeconds = presignTtlSeconds,
            internalEndpoint = endpointUrl,
            publicEndpoint = publicEndpoint.orElse(""),
            credentials = creds,
            region = region,
        )
    }
}
