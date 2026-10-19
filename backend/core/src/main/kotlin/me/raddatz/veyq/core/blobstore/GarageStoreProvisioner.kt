package me.raddatz.veyq.core.blobstore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Brings the **bundled Garage** (ADR 0013 §1) from *running* to *usable*, over Garage's Admin API — the work
 * `infra/garage-init.sh` used to do from a container that existed only to own a shell.
 *
 * **Plain HTTP, not the `garage` binary and not its RPC** (ADR 0067 § Rejected). Measured against the pinned
 * v2.0.0: every bring-up step is a JSON call on `:3903` behind a bearer token, so the custom image bought
 * nothing but a shell — and a vendor-locked client in veyq is what CLAUDE.md's dependency rule forbids. The
 * JDK's own `HttpClient` and the Jackson already on the classpath cover it with no new dependency. Note when
 * reading a response: **a bogus path also answers `400`**, so a 400 is never evidence that an endpoint exists;
 * only a successful call is.
 *
 * **Six steps, each idempotent on its own** (ADR 0067 Decision 3 — see [StoreProvisioner] for why per-step and
 * not per-bootstrap). Three of the five admin calls are *not* natively idempotent — measured on v2.0.0,
 * `CreateBucket` and `ImportKey` answer `409` on a repeat while `AllowBucketKey` answers `200` — so each step
 * reads its own precondition first rather than relying on the write to be repeatable.
 *
 * **The layout step generalises past a fresh node.** `garage-init.sh` hardcoded `layout apply --version 1`, so
 * it only ever initialised a node that had never been laid out. That was a property of the script, not of the
 * act: here the step is satisfied by *any* node that already carries a role, at any layout version, and where
 * it does have work to do it applies `current + 1` — measured, a stale version number is refused.
 *
 * **The key alias is [KEY_ALIAS] and that is load-bearing, not cosmetic.** `scripts/run_web_e2e.py` grants
 * against that exact alias when it makes a fresh bucket for a suite, and after ADR 0067 this class is the only
 * thing that mints it. A rename breaks the e2e suite somewhere that looks nothing like provisioning.
 *
 * Nothing here reads, writes or deletes an object: five metadata calls plus [BucketCorsPolicy], which is a
 * two-method port for exactly that reason.
 */
class GarageStoreProvisioner(
    private val adminEndpoint: String,
    private val adminToken: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val nodeZone: String,
    private val nodeCapacity: String,
    private val cors: BucketCorsPolicy,
    private val corsOrigins: List<String>,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : StoreProvisioner {

    private val log = Logger.getLogger(GarageStoreProvisioner::class.java)

    override fun provision() {
        ensureLayout()
        val bucketId = ensureBucket()
        ensureKey()
        ensureGrant(bucketId)
        ensureCors()
    }

    /**
     * Step 1 — the node carries a layout role. Satisfied by any non-empty role set: a node that was laid out by
     * an older veyq, by hand, or by a `garage` CLI is already past this step, whatever version its layout is at.
     */
    private fun ensureLayout() {
        val layout = get("/v2/GetClusterLayout")
        if (layout["roles"]?.isEmpty == false) return

        val nodeId = get("/v2/GetClusterStatus")["nodes"]?.firstOrNull()?.get("id")?.asText()
            ?: error("Garage reports no nodes, so there is nothing to assign a layout role to")
        log.infof("provisioning store: assigning a layout role to node %s", nodeId)
        post(
            "/v2/UpdateClusterLayout",
            mapOf(
                "roles" to listOf(
                    mapOf("id" to nodeId, "zone" to nodeZone, "capacity" to capacityBytes(), "tags" to emptyList<String>()),
                ),
            ),
        )
        // Re-read rather than reuse the version from above: staging is itself a write, and applying a stale
        // version number is refused (measured — `Invalid new layout version`).
        val staged = get("/v2/GetClusterLayout")["version"]?.asInt() ?: error("Garage layout carries no version")
        post("/v2/ApplyClusterLayout", mapOf("version" to staged + 1))
    }

    /** Step 2 — the bucket exists. Returns its id, which the grant step needs. */
    private fun ensureBucket(): String {
        existingBucketId()?.let { return it }
        log.infof("provisioning store: creating bucket %s", bucket)
        post("/v2/CreateBucket", mapOf("globalAlias" to bucket))
        return existingBucketId() ?: error("Garage accepted CreateBucket for '$bucket' but does not report it")
    }

    /** Step 3 — the access key veyq signs with exists in the store. */
    private fun ensureKey() {
        if (send("GET", "/v2/GetKeyInfo?id=$accessKey", null).statusCode() != NOT_FOUND) return
        log.infof("provisioning store: importing access key %s as '%s'", accessKey, KEY_ALIAS)
        post(
            "/v2/ImportKey",
            mapOf("accessKeyId" to accessKey, "secretAccessKey" to secretKey, "name" to KEY_ALIAS),
        )
    }

    /**
     * Step 4 — that key may read, write and own the bucket. `AllowBucketKey` is the one admin call that is
     * natively idempotent (measured: `200` on a repeat), so this one may simply be re-sent.
     */
    private fun ensureGrant(bucketId: String) {
        post(
            "/v2/AllowBucketKey",
            mapOf(
                "bucketId" to bucketId,
                "accessKeyId" to accessKey,
                "permissions" to mapOf("read" to true, "write" to true, "owner" to true),
            ),
        )
    }

    /**
     * Step 5 — the bucket's CORS policy. The **only** step here that is generic S3 rather than Garage
     * mechanics, and the only one that is new work rather than moved work: `garage-init.sh` never wrote one
     * (measured — `PutBucketCors` appears in no code and no `infra/` file before ADR 0067).
     *
     * The policy is `*` and must stay `*` on this store. ADR 0013 §4 measured what a bucket CORS policy can
     * express on the pinned Garage v2.0.0: a two-origin list is *accepted* and then produces a comma-joined
     * `Access-Control-Allow-Origin`, which is not a legal header value — so the preflight succeeds and the
     * browser blocks the fetch anyway, a failure that reads as a working configuration from the server side.
     * On a multi-tenant instance `*` is therefore the one policy that works. It is tolerable rather than
     * merely tolerated: the presigned URL **is** the capability, it expires in minutes, and the fetch carries
     * no cookie — so `*` widens who may use a URL they already hold, never who may obtain one. Do not
     * "improve" this into an enumerated origin list; §4's table exists to stop exactly that.
     */
    private fun ensureCors() {
        if (cors.allowedOrigins(bucket)?.toSet() == corsOrigins.toSet()) return
        log.infof("provisioning store: writing CORS policy %s on bucket %s", corsOrigins, bucket)
        cors.put(bucket, corsOrigins)
    }

    private fun existingBucketId(): String? {
        val res = send("GET", "/v2/GetBucketInfo?globalAlias=$bucket", null)
        if (res.statusCode() == NOT_FOUND) return null
        return parse(res, "/v2/GetBucketInfo")["id"]?.asText()
    }

    /** `1G` / `500M` / a bare byte count — the same spelling `garage layout assign -c` took. */
    private fun capacityBytes(): Long {
        val raw = nodeCapacity.trim()
        val unit = raw.lastOrNull()?.uppercaseChar()
        val multiplier = when (unit) {
            'K' -> 1_000L
            'M' -> 1_000_000L
            'G' -> 1_000_000_000L
            'T' -> 1_000_000_000_000L
            else -> null
        }
        val digits = if (multiplier == null) raw else raw.dropLast(1)
        val value = digits.toLongOrNull() ?: error("veyq.blob-store.garage.node-capacity is not a size: '$nodeCapacity'")
        return value * (multiplier ?: 1L)
    }

    private fun get(path: String): JsonNode = parse(send("GET", path, null), path)

    private fun post(path: String, body: Map<String, Any>): JsonNode =
        parse(send("POST", path, mapper.writeValueAsString(body)), path)

    private fun send(method: String, path: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(adminEndpoint.trimEnd('/') + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $adminToken")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun parse(res: HttpResponse<String>, path: String): JsonNode {
        // The token is never in the message: a failure here is read by whoever runs the instance, and the
        // admin token is an operator secret like the store credentials beside it. Nor is the body: it is a remote
        // API's text, and a log carries a remote body by its status and its error code only (ADR 0092 § 3) — the
        // retry line writes this message (`StoreProvisioningRunner`). `GarageStoreProvisionerTest` holds it.
        check(res.statusCode() in 200..299) {
            "Garage admin API $path answered ${res.statusCode()}" + (errorCode(res.body())?.let { " ($it)" } ?: "")
        }
        return if (res.body().isBlank()) mapper.createObjectNode() else mapper.readTree(res.body())
    }

    /** Garage's error `code` (`NoSuchBucket`, `InternalError`), when the body carries one of [ERROR_CODE]'s shape. */
    private fun errorCode(body: String): String? =
        runCatching { mapper.readTree(body)?.get("code")?.takeIf { it.isTextual }?.asText() }.getOrNull()
            ?.takeIf { ERROR_CODE.matches(it) }

    companion object {
        /** The only shape of a Garage error code a log may carry: a word, never a sentence that could hold a value. */
        private val ERROR_CODE = Regex("^[A-Za-z]{1,64}$")

        /**
         * The name the access key is imported under. `scripts/run_web_e2e.py` grants against this exact alias
         * (an undocumented contract that used to belong to `garage-init.sh:47`), so it is part of this class's
         * interface with the test harness and not an implementation detail.
         */
        const val KEY_ALIAS = "veyq-app"

        private const val NOT_FOUND = 404
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
