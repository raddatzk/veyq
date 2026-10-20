package me.raddatz.veyq.core.blobstore

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * An in-process stand-in for Garage's Admin API, built to the shapes **measured against the pinned v2.0.0**
 * rather than to the shapes this codebase would find convenient. It exists so per-step idempotence
 * ([GarageStoreProvisionerTest]) can be asserted on the exact call sequence, which no run against a real node
 * can show.
 *
 * **Why the measured status codes matter here more than usual.** ADR 0067's own note: a *bogus* Admin API path
 * answers `400` on the real v2.0.0, so a fake that answers `400` for "I do not know this endpoint" is
 * indistinguishable from a real endpoint that rejected a well-formed request — and a test against it would
 * prove nothing about whether the endpoint exists. This fake therefore **fails loudly on an unknown path**
 * (recorded and answered `418`, which the real API never returns), so a provisioner that starts calling an
 * endpoint nobody measured reddens rather than passing.
 *
 * The three write calls carry the measured non-idempotence that shapes the provisioner: `CreateBucket` and
 * `ImportKey` answer `409` on a repeat, `AllowBucketKey` answers `200`. A provisioner that assumed "just write
 * it again" would go red here for the same reason it would fail against a real store.
 */
class FakeGarageAdminApi(
    var layoutVersion: Int = 0,
    var hasRole: Boolean = false,
) {
    /** Every path this fake was asked for, in order — the oracle for "which steps actually ran". */
    val calls = mutableListOf<String>()

    /** Global aliases of the buckets that exist, keyed to the id the fake hands back. */
    val buckets = mutableMapOf<String, String>()

    /** Access key ids the fake holds. */
    val keys = mutableSetOf<String>()

    /** Grants recorded as `bucketId/accessKeyId`. */
    val grants = mutableListOf<String>()

    /** When set, every call answers this status and body — how a failing store's error log is driven. */
    var failWith: Pair<Int, String>? = null

    /** Paths the provisioner asked for that this fake does not implement — always a test failure. */
    val unknownPaths = mutableListOf<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.createContext("/") { handle(it) }
        server.executor = null
        server.start()
    }

    fun stop() = server.stop(0)

    /** Forget the call log without forgetting the store's state — how a "run it a second time" case is set up. */
    fun forgetCalls() = calls.clear()

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val query = ex.requestURI.query
        calls += path
        val body = ex.requestBody.readBytes().decodeToString()
        val (status, response) = failWith ?: route(path, query, body)
        val bytes = response.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun route(path: String, query: String?, body: String): Pair<Int, String> = when (path) {
        "/v2/GetClusterLayout" -> {
            val roles = if (hasRole) """[{"id":"$NODE_ID","zone":"local","capacity":1000000000}]""" else "[]"
            200 to """{"version":$layoutVersion,"roles":$roles,"stagedRoleChanges":[]}"""
        }

        "/v2/GetClusterStatus" -> 200 to """{"layoutVersion":$layoutVersion,"nodes":[{"id":"$NODE_ID","isUp":true}]}"""

        "/v2/UpdateClusterLayout" -> {
            check(NODE_ID in body) { "UpdateClusterLayout did not name the node the status call reported" }
            layoutVersion += 0 // staging does not apply
            200 to "{}"
        }

        "/v2/ApplyClusterLayout" -> {
            // Measured: a stale version is refused (`Invalid new layout version`), which is what forces the
            // provisioner to read the CURRENT version rather than hardcode `1` the way garage-init.sh did.
            val wanted = Regex("\"version\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
            if (wanted != layoutVersion + 1) {
                500 to """{"code":"InternalError","message":"Internal error: Invalid new layout version"}"""
            } else {
                layoutVersion = wanted
                hasRole = true
                200 to "{}"
            }
        }

        "/v2/GetBucketInfo" -> {
            val alias = query?.substringAfter("globalAlias=")
            val id = buckets[alias]
            if (id == null) 404 to """{"code":"NoSuchBucket","message":"Bucket not found"}"""
            else 200 to """{"id":"$id","globalAliases":["$alias"]}"""
        }

        "/v2/CreateBucket" -> {
            val alias = Regex("\"globalAlias\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            // Measured: 409, not a silent success. A provisioner that skips the existence check breaks here.
            if (alias in buckets) 409 to """{"code":"BucketAlreadyExists","message":"Bucket already exists"}"""
            else {
                buckets[alias] = "bucket-id-$alias"
                200 to """{"id":"bucket-id-$alias"}"""
            }
        }

        "/v2/GetKeyInfo" -> {
            val id = query?.substringAfter("id=")
            if (id in keys) 200 to """{"accessKeyId":"$id"}"""
            else 404 to """{"code":"NoSuchAccessKey","message":"Access key not found"}"""
        }

        "/v2/ImportKey" -> {
            val id = Regex("\"accessKeyId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            // Measured: 409, and the real message says the id can never be reused even after a delete.
            if (id in keys) 409 to """{"code":"KeyAlreadyExists","message":"Key already exists"}"""
            else {
                keys += id
                200 to """{"accessKeyId":"$id"}"""
            }
        }

        // Measured: 200 on a repeat. The one write that is natively idempotent.
        "/v2/AllowBucketKey" -> {
            val bucketId = Regex("\"bucketId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            val keyId = Regex("\"accessKeyId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            grants += "$bucketId/$keyId"
            200 to "{}"
        }

        else -> {
            unknownPaths += path
            // NOT 400: on the real v2.0.0 an unknown path answers 400, so answering 400 here would make "this
            // endpoint does not exist" indistinguishable from "this endpoint rejected your body".
            418 to """{"code":"FakeDoesNotImplementThis","message":"$path"}"""
        }
    }

    companion object {
        const val NODE_ID = "37c581c77970b44193e464ad0db3a08aa1b785d9ec308732733a6d3670d56e65"
    }
}

/** A [BucketCorsPolicy] that records what it was asked, so the CORS step is observable without a real store. */
class FakeBucketCorsPolicy(private val policies: MutableMap<String, List<String>> = mutableMapOf()) : BucketCorsPolicy {
    val writes = mutableListOf<Pair<String, List<String>>>()

    override fun allowedOrigins(bucket: String): List<String>? = policies[bucket]

    override fun put(bucket: String, origins: List<String>) {
        policies[bucket] = origins
        writes += bucket to origins
    }

    /** Remove ONLY the CORS policy, leaving every other provisioned thing in place. */
    fun forget(bucket: String) = policies.remove(bucket)
}
