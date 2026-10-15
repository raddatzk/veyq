package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import io.quarkus.runtime.LaunchMode
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
import java.util.concurrent.ConcurrentHashMap

/**
 * The blob-transport port (ADR 0013): the backend's control plane signs short-TTL presigned URLs and stats
 * objects; the **bytes never flow through it**. Operator-blind — the store only ever holds AEAD ciphertext
 * sealed client-side. One object key per chunk: `storageKey/<index>`.
 *
 * [canPresign] is the capability switch: an [S3BlobStore] presigns + stats real objects; the [InMemoryBlobStore]
 * (`%test`-only metadata stub) and the [UnconfiguredBlobStore] (no usable store set yet, ADR 0089) cannot presign and
 * have no transport path. Which one answers is [SwitchingBlobStore]'s, following the store setting at runtime; the
 * byte transfer itself is exercised by opt-in e2e against a real S3-compatible store.
 */
interface BlobStore {
    /** True when the store can produce presigned URLs (and therefore carry bytes); false for the dev store. */
    val canPresign: Boolean

    /**
     * The origin a browser fetches this store's objects from **when that is not the page's own origin**, or null
     * when it is (or when the store cannot presign at all). The app shell appends it to the policy's
     * `connect-src` (ADR 0069 Decision 2, cc.security.csp § `<store-origin>`), which is the only reader.
     *
     * It is a property of the store rather than of the shell so that it is derived from the **same** endpoint
     * resolution the presigned URLs come from: a second reading of `veyq.blob-store.*` could disagree with the
     * signer, and the disagreement would surface only as a browser-blocked fetch on one deployment target.
     * Abstract on purpose — a default `null` would let a delegating store silently hide its delegate's origin.
     */
    val storeOrigin: String?

    /**
     * A short-TTL presigned PUT URL for one chunk object, or null when the store cannot presign.
     *
     * [domain] is the **resolved tenant's own registered domain** — the one the request arrived on, looked up in
     * the `tenant` table, never a `Host` header echoed back (ADR 0063; the resolution itself lives in
     * `TenantHostResolver`). It is what makes the URL same-origin *for that tenant*, which is the whole of ADR
     * 0013 §4's no-CORS property on a server with more than one domain. `null` means the request resolved to no
     * tenant — the bootstrap domain, or a request that legitimately has none — and signs against the configured
     * default.
     *
     * The URL signs `content-type: application/octet-stream`, so the store refuses any other type (ADR 0013 §6),
     * and `content-length: `[contentLength] — the chunk's exact derived length — so it refuses a body of any other
     * size (ADR 0013 §3). Where a store ignores the signed length, `complete`'s exact check still holds.
     */
    fun presignPut(key: String, contentLength: Long, domain: String? = null): String?

    /** A short-TTL presigned GET URL for one chunk object, or null when the store cannot presign. [domain] as in
     *  [presignPut]: the resolved tenant's own domain, or null for the bootstrap default. The URL signs the
     *  response overrides `application/octet-stream` + `attachment`, so nothing it serves is renderable (ADR 0013
     *  §6). */
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

        /**
         * The object keys of chunks [indices] under [storageKey], in chunk order — the one enumeration of a blob's
         * chunk keys that every signer, verifier and reclaimer goes through, rather than each writing its own loop.
         */
        fun chunkKeys(storageKey: String, indices: IntRange): List<String> = indices.map { chunkKey(storageKey, it) }

        /** Every chunk key of a blob of [chunkCount] chunks under [storageKey], in chunk order. */
        fun chunkKeys(storageKey: String, chunkCount: Int): List<String> = chunkKeys(storageKey, 0 until chunkCount)
    }
}

/** A presigned URL for one chunk object, [index] in chunk order — what every presign site hands a client. */
data class PresignedRef(val index: Int, val url: String)

/**
 * Presigned PUTs for chunks [indices] of the blob under [storageKey], each signed for its exact [lengthOf] (ADR 0013
 * §3) — or null when the store cannot presign. A window is any such range; `start` signs the first one.
 */
fun BlobStore.presignPuts(storageKey: String, indices: IntRange, lengthOf: (Int) -> Long, domain: String?): List<PresignedRef>? =
    if (!canPresign) null
    else indices.zip(BlobStore.chunkKeys(storageKey, indices)) { i, key -> PresignedRef(i, presignPut(key, lengthOf(i), domain)!!) }

/** Presigned GETs for every chunk of a blob of [chunkCount] chunks under [storageKey] — or null when the store cannot presign. */
fun BlobStore.presignGets(storageKey: String, chunkCount: Int, domain: String?): List<PresignedRef>? =
    if (!canPresign) null
    else BlobStore.chunkKeys(storageKey, chunkCount).mapIndexed { i, key -> PresignedRef(i, presignGet(key, domain)!!) }

/**
 * `%test`-only store (cr-0105): holds no bytes and **cannot presign** (ADR 0013 §1). It exists purely so the
 * hermetic slice suite can drive the metadata control plane (`start`/`complete`/`list` — blob and application rows, the
 * wrap, the budget *declaration*) without a real store; the byte transfer is covered by the opt-in store e2e.
 * `%dev`/`%prod` never reach it — without a usable store setting they get [UnconfiguredBlobStore].
 */
class InMemoryBlobStore : BlobStore {
    override val canPresign = false
    override val storeOrigin: String? = null
    // The domain is ignored, and can be: this store has no endpoint to sign against in the first place.
    override fun presignPut(key: String, contentLength: Long, domain: String?): String? = null
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
 * Credentials come from the store setting (sealed at rest, ADR 0089) or the bundled hint, and are never logged or
 * returned by any endpoint.
 *
 * **Signing is per-request, not per-instance** (ADR 0063, amending ADR 0013 §4). A presigned URL is only
 * same-origin — the whole reason there is no CORS policy to configure — if it is signed for the origin the
 * browser is actually on, so a server with a second tenant domain must sign for *that* domain. Hence one cached
 * presigner per resolved sign endpoint rather than one for the instance. Building a presigner is pure local
 * HMAC setup with no network I/O, so the cache is a convenience, not a pool; it is bounded by the number of
 * tenant domains and never invalidated, because a domain that stops being a tenant's simply stops being asked
 * for.
 *
 * **Which origin a URL is signed for is a named mode, not a shape read off the other settings**
 * ([SigningMode], ADR 0067 Decision 5) — and there are three of them, because ADR 0013 §4 distinguishes three
 * deployment shapes. [SigningMode.TENANT_DOMAIN] is the per-domain swap above, and the only one where a
 * tenant's domain reaches the signed URL at all: it is same-origin *because* our own proxy serves the store on
 * that domain. [SigningMode.STORE_ORIGIN] signs `public-endpoint` verbatim, for a store on an origin of its own
 * that the backend does **not** reach at that address (ADR 0064's Kubernetes Ingress host; a tenant's own
 * bucket) — every tenant then shares one signed host, and that branch needs a bucket CORS policy which signing
 * cannot see the absence of. [SigningMode.INTERNAL_ENDPOINT] signs the endpoint the backend itself uses, for a
 * store already publicly reachable there (external S3); a blank `public-endpoint` lands here whatever the mode
 * says, since nothing else is left to sign against.
 *
 * In the latter two the domain is ignored **entirely** rather than substituted, which would invent a bucket host
 * that does not exist. That is [endpointFor]'s doing and nobody else's — `RequestTenant`, which only hands over
 * the resolved tenant's registered domain, is a different class in a different package that ignores nothing.
 */
class S3BlobStore(
    private val s3: S3Client,
    private val bucket: String,
    private val presignTtlSeconds: Int,
    private val internalEndpoint: String,
    private val publicEndpoint: String,
    // Mirrors `@WithDefault("tenant-domain")` on the config member, and is a second copy of it on purpose: it
    // keeps every pre-existing construction site byte-identical, so "no existing deployment moves" is shown by
    // untouched tests rather than by edited ones. Drift is caught from both sides — change the config default
    // and `S3BlobStoreSigningModeConfigTest` reddens, change this one and the swap cases below do.
    signingMode: SigningMode = SigningMode.TENANT_DOMAIN,
    private val credentials: StaticCredentialsProvider,
    private val region: String,
) : BlobStore {
    override val canPresign = true

    /**
     * The configured mode, with the one combination that cannot mean what it says folded in **once, here**,
     * rather than as a second predicate inside [endpointFor]: a blank `public-endpoint` leaves nothing to swap
     * a host into and nothing to sign verbatim, so it is [SigningMode.INTERNAL_ENDPOINT] and no other mode is
     * expressible. Folding it in at construction is what keeps [endpointFor] a total `when` over three
     * constants — and it reads the connection, never the `tenant` table, so it is fixed for the life of this
     * store object; a changed setting builds a new one ([SwitchingBlobStore]).
     */
    private val mode: SigningMode =
        if (publicEndpoint.isBlank()) SigningMode.INTERNAL_ENDPOINT else signingMode

    private val presigners = ConcurrentHashMap<String, S3Presigner>()

    /**
     * Read off [endpointFor], never off the config, so it cannot name another origin than the URLs do. Only
     * [SigningMode.TENANT_DOMAIN] answers null, and not because its endpoint is unknown: it signs for the
     * tenant's own domain, which is the page's origin, so `'self'` already covers it. The other two ignore the
     * domain, so `endpointFor(null)` is the one origin every URL they sign names. Signing is path-style, so the
     * endpoint's origin is the fetched origin — a virtual-hosted bucket would put the bucket in the host.
     */
    override val storeOrigin: String? = when (mode) {
        SigningMode.TENANT_DOMAIN -> null
        SigningMode.STORE_ORIGIN, SigningMode.INTERNAL_ENDPOINT -> originOf(endpointFor(null))
    }

    /*
     * ADR 0013 §6 — nothing the store serves is renderable, and THE SIGNER is what guarantees it: not the store,
     * which on `type: s3` veyq never configures (ADR 0067), and not the proxy, which the Kubernetes target does not
     * have in front of its store. Any member or share contributor can PUT plaintext through their own URL, and
     * where the store shares the app's origin a `text/html` object is a page on it. So the PUT signs the one
     * type the store may keep, and the GET signs the response overrides — which also neutralise an object stored
     * before this rule. Both are inside the signature: measured against Garage v2.0.0, a PUT sent with another
     * type and a GET with an override stripped are each a 403, so a URL holder can change neither.
     */

    override fun presignPut(key: String, contentLength: Long, domain: String?): String {
        val presigned = presignerFor(domain).presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .putObjectRequest(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(INERT_TYPE).contentLength(contentLength).build(),
                )
                .build(),
        )
        return presigned.url().toString()
    }

    override fun presignGet(key: String, domain: String?): String {
        val presigned = presignerFor(domain).presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignTtlSeconds.toLong()))
                .getObjectRequest(
                    GetObjectRequest.builder().bucket(bucket).key(key)
                        .responseContentType(INERT_TYPE)
                        .responseContentDisposition(INERT_DISPOSITION)
                        .build(),
                )
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
     * The three signing behaviours, written out in one place and total over [SigningMode] — the `when` is
     * exhaustive with no `else`, so a fourth mode cannot be added without deciding this.
     *
     * Only [SigningMode.TENANT_DOMAIN] lets [domain] reach the answer at all: `public-endpoint` with its
     * **host** swapped, keeping scheme, port and path, because those are properties of how this deployment
     * exposes the store (`:443`, a path prefix behind Traefik) and not of which tenant is asking. The other two
     * ignore [domain] by construction — in [SigningMode.STORE_ORIGIN] the store's origin has nothing to do with
     * which tenant is asking, so this is where "every tenant signs against the same host" is *implemented*
     * rather than merely configured, and it is what makes a `Host` nobody claims unable to move the origin even
     * one step further than [SigningMode.TENANT_DOMAIN] does.
     *
     * A domain that cannot be substituted (a malformed configured endpoint) falls back to `public-endpoint`
     * rather than throwing: signing for the wrong origin costs the client a CORS-blocked fetch, while throwing
     * costs them the upload. The domain reaching here is a *registered* one from the `tenant` table, never a
     * header value, so this is a robustness fallback and not a validation boundary — the validation already
     * happened when the row was written, and `TenantHostResolver` is what makes a spoofed `Host` resolve to
     * nothing.
     */
    private fun endpointFor(domain: String?): String = when (mode) {
        SigningMode.INTERNAL_ENDPOINT -> internalEndpoint
        SigningMode.STORE_ORIGIN -> publicEndpoint
        SigningMode.TENANT_DOMAIN -> if (domain == null) publicEndpoint else runCatching {
            val base = URI.create(publicEndpoint)
            URI(base.scheme, base.userInfo, domain, base.port, base.path, base.query, base.fragment).toString()
        }.getOrDefault(publicEndpoint)
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

    private companion object {
        /** The only type a presigned PUT lets the store keep and the type every presigned GET is served as. */
        const val INERT_TYPE = "application/octet-stream"
        const val INERT_DISPOSITION = "attachment"

        /** `scheme://host[:port]` — the form a CSP source expression and a browser's `origin` share. An explicit
         *  port stays: it is part of the origin unless it is the scheme's default, which URI leaves as -1. */
        fun originOf(endpoint: String): String {
            val uri = URI.create(endpoint)
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
        }
    }
}

/**
 * The store outside `%test` while no usable store is set (ADR 0089 decision 5): the instance runs and answers
 * `/api/setup`, and every upload is refused with a reason (`canPresign` false is `store_unavailable`) — never the
 * silent [InMemoryBlobStore], whose `delete` reports every key gone. This one reports every key it was asked to
 * delete as NOT deleted, so a reclaim row waits for a store instead of being dropped.
 */
object UnconfiguredBlobStore : BlobStore {
    override val canPresign = false
    override val storeOrigin: String? = null
    override fun presignPut(key: String, contentLength: Long, domain: String?): String? = null
    override fun presignGet(key: String, domain: String?): String? = null
    override fun stat(key: String): Long? = null
    override fun delete(keys: List<String>): List<String> = keys
}

/**
 * The store every caller injects: it follows the current store setting (ADR 0089 decision 5), with no restart. Each
 * call reads [StoreSettings.current] — a cached resolution, a field read until a change invalidates it — and builds
 * the delegate again only when that resolution is a different object, so the signing client, the presigner cache and
 * [storeOrigin] (the app shell's CSP) all move together when the setting moves; nothing outlives the setting it was
 * built from. A replaced delegate is left to the garbage collector rather than closed, because a request on another
 * thread may still be signing with it; a store setting changes a handful of times in an instance's life.
 *
 * [built] needs no generation of its own (inbox #445): it is a memo of [StoreSettings.current], checked against it on
 * every call. A racing call can store a delegate for an older resolution over a newer one, but the next call finds the
 * current resolution a different object and builds again — a wasted build, never a stale store that sticks.
 *
 * With no usable setting it is [InMemoryBlobStore] under `%test` (the hermetic suite's metadata stub) and
 * [UnconfiguredBlobStore] everywhere else.
 */
class SwitchingBlobStore(
    private val settings: StoreSettings,
    private val presignTtlSeconds: Int,
    private val hermetic: Boolean,
) : BlobStore {
    private class Built(val resolution: StoreResolution, val store: BlobStore)

    @Volatile
    private var built: Built? = null

    /** The store for the current setting. */
    fun delegate(): BlobStore {
        val resolution = settings.current()
        built?.let { if (it.resolution === resolution) return it.store }
        val store = when (resolution) {
            is StoreResolution.Ready -> BlobStoreProducer.connect(resolution.connection, presignTtlSeconds)
            else -> if (hermetic) InMemoryBlobStore() else UnconfiguredBlobStore
        }
        built = Built(resolution, store)
        return store
    }

    override val canPresign: Boolean get() = delegate().canPresign
    override val storeOrigin: String? get() = delegate().storeOrigin
    override fun presignPut(key: String, contentLength: Long, domain: String?): String? = delegate().presignPut(key, contentLength, domain)
    override fun presignGet(key: String, domain: String?): String? = delegate().presignGet(key, domain)
    override fun stat(key: String): Long? = delegate().stat(key)
    override fun delete(keys: List<String>): List<String> = delegate().delete(keys)
}

/**
 * Produces the [SwitchingBlobStore] every caller injects, and is the one place an S3 client is built from a
 * [StoreConnection] — the request path, the check and provisioning all connect the same way.
 */
@ApplicationScoped
class BlobStoreProducer(
    private val settings: StoreSettings,
    config: VeyqConfig,
) {
    private val presignTtlSeconds: Int = config.blobStore().presignTtlSeconds()

    @Produces
    @ApplicationScoped
    fun blobStore(): BlobStore = SwitchingBlobStore(settings, presignTtlSeconds, LaunchMode.current() == LaunchMode.TEST)

    companion object {
        /** A path-style S3 client for [connection]'s internal endpoint: custom endpoints (Garage/MinIO/…) cannot
         *  serve virtual-host-style bucket subdomains. */
        fun s3Client(connection: StoreConnection): S3Client =
            S3Client.builder()
                .endpointOverride(URI.create(connection.endpoint))
                .region(Region.of(connection.region))
                .credentialsProvider(credentials(connection))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()

        /**
         * The request-path store for [connection]. The presigners are built lazily, one per resolved sign endpoint,
         * because *which* endpoint a URL is signed for is a per-request answer (ADR 0063) — so this hands over the
         * ingredients rather than a finished presigner.
         */
        fun connect(connection: StoreConnection, presignTtlSeconds: Int): S3BlobStore =
            S3BlobStore(
                s3 = s3Client(connection),
                bucket = connection.bucket,
                presignTtlSeconds = presignTtlSeconds,
                internalEndpoint = connection.endpoint,
                publicEndpoint = connection.publicEndpoint.orEmpty(),
                signingMode = connection.signingMode,
                credentials = credentials(connection),
                region = connection.region,
            )

        private fun credentials(connection: StoreConnection): StaticCredentialsProvider =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(connection.accessKey, connection.secretKey))
    }
}
