package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * The store check (ADR 0089 decision 2) against a **local fake S3 endpoint that records every request it is sent** —
 * the check's whole contract is what it may do to a store, and only the requests prove that.
 *
 * The bites, each with the case that must make it red:
 *  - a bucket that does not exist fails **at BUCKET** and the check sends nothing that could create one
 *    ([aMissingBucketFailsAtItsStepAndCreatesNone]) — a check that tried `CreateBucket` on a 404 would show a PUT
 *    on `/<bucket>` here;
 *  - the check never deletes ([aPassingCheckWritesOneProbeAndDeletesNothing]) — object deletion keeps its one
 *    caller, `StoreReclaimSweep` (`validate_store_reclaim_arity.py`);
 *  - a CORS rule that does not name the instance's origin is a failure of the CORS step, not a pass.
 *
 * No fixed port and nothing outside localhost: the fake binds an ephemeral one.
 */
class StoreCheckTest {

    private lateinit var server: HttpServer
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var bucketExists = true
    @Volatile private var corsAllowsOrigin = true
    private val objects = Collections.synchronizedMap(HashMap<String, ByteArray>())

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        requests += "${ex.requestMethod} $path"
        var status = 400
        var body = ByteArray(0)
        when {
            ex.requestMethod == "HEAD" && path == "/$BUCKET" -> status = if (bucketExists) 200 else 404
            ex.requestMethod == "PUT" && path.startsWith("/$BUCKET/") -> {
                objects[path] = ex.requestBody.readBytes()
                status = 200
            }
            ex.requestMethod == "GET" && path.startsWith("/$BUCKET/") -> {
                val stored = objects[path]
                status = if (stored == null) 404 else 200
                if (stored != null) body = stored
            }
            ex.requestMethod == "OPTIONS" -> {
                if (corsAllowsOrigin) ex.responseHeaders.add("Access-Control-Allow-Origin", ex.requestHeaders.getFirst("Origin"))
                status = if (corsAllowsOrigin) 200 else 403
            }
        }
        ex.sendResponseHeaders(status, if (ex.requestMethod == "HEAD") -1 else body.size.toLong().takeIf { it > 0 } ?: -1)
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }

    private fun connection(type: StoreType = StoreType.S3, mode: SigningMode = SigningMode.INTERNAL_ENDPOINT) = StoreConnection(
        bundled = false, type = type, endpoint = "http://127.0.0.1:${server.address.port}", publicEndpoint = null,
        signingMode = mode, bucket = BUCKET, region = "us-east-1", accessKey = "access", secretKey = "secret",
        garageAdminEndpoint = null, garageAdminToken = null,
    )

    private fun check(provision: (StoreConnection) -> Unit = { error("an S3 store must never be provisioned") }) = StoreCheck(
        s3For = { BlobStoreProducer.s3Client(it) },
        signerFor = { BlobStoreProducer.connect(it, presignTtlSeconds = 60) },
        provision = provision,
    )

    private fun StoreCheckResult.outcomes() = steps.associate { it.step to it.outcome }

    @Test
    fun aMissingBucketFailsAtItsStepAndCreatesNone() {
        bucketExists = false

        val result = check().run(connection(), null, "https://veyq.example.com")

        assertFalse(result.passed)
        assertEquals(
            mapOf(
                StoreCheckStep.CREDENTIALS to StoreCheckOutcome.PASSED,
                StoreCheckStep.PROVISION to StoreCheckOutcome.SKIPPED,
                StoreCheckStep.BUCKET to StoreCheckOutcome.FAILED,
                StoreCheckStep.PROBE_PUT to StoreCheckOutcome.SKIPPED,
                StoreCheckStep.PROBE_GET to StoreCheckOutcome.SKIPPED,
                StoreCheckStep.CORS to StoreCheckOutcome.SKIPPED,
            ),
            result.outcomes(),
        )
        assertTrue(result.steps.single { it.step == StoreCheckStep.BUCKET }.detail!!.contains("does not exist"), result.toString())
        // The bite: asked about the bucket (a HEAD), and nothing else — no PUT on `/blobs`, which is how S3 creates one.
        assertEquals(listOf("HEAD /$BUCKET"), requests.toList(), "the check must send nothing that could create a bucket")
    }

    @Test
    fun aPassingCheckWritesOneProbeAndDeletesNothing() {
        val result = check().run(connection(), null, "https://veyq.example.com")

        assertTrue(result.passed, result.toString())
        assertEquals(setOf(StoreCheckOutcome.PASSED), result.steps.filter { it.step != StoreCheckStep.PROVISION }.map { it.outcome }.toSet())
        val probe = "/$BUCKET/${StoreCheck.PROBE_KEY}"
        assertEquals(listOf("HEAD /$BUCKET", "PUT $probe", "GET $probe", "OPTIONS $probe"), requests.toList())
        assertTrue(requests.none { it.startsWith("DELETE") }, "the check must never delete — object deletion has one caller")
    }

    @Test
    fun theProbeIsOverwrittenByTheNextCheckSoNothingAccumulates() {
        check().run(connection(), null, null)
        check().run(connection(), null, null)

        assertEquals(1, objects.size, "one fixed probe key, overwritten: $objects")
    }

    @Test
    fun aCorsRuleThatDoesNotNameTheOriginFailsTheCorsStep() {
        corsAllowsOrigin = false

        val result = check().run(connection(), null, "https://veyq.example.com")

        assertFalse(result.passed)
        assertEquals(StoreCheckOutcome.PASSED, result.outcomes()[StoreCheckStep.PROBE_GET], "everything before CORS passed")
        assertEquals(StoreCheckOutcome.FAILED, result.outcomes()[StoreCheckStep.CORS])
        assertTrue(result.steps.last().detail!!.contains("https://veyq.example.com"), result.toString())
    }

    @Test
    fun aStoreServedOnTheTenantsOwnDomainSkipsCorsAndSendsNoPreflight() {
        val result = check().run(connection(mode = SigningMode.TENANT_DOMAIN), null, "https://veyq.example.com")

        assertTrue(result.passed, result.toString())
        assertEquals(StoreCheckOutcome.SKIPPED, result.outcomes()[StoreCheckStep.CORS])
        assertTrue(requests.none { it.startsWith("OPTIONS") })
    }

    @Test
    fun withNoPublicOriginTheCorsStepIsSkippedWithItsReason() {
        val result = check().run(connection(), null, null)

        assertTrue(result.passed, result.toString())
        assertTrue(result.steps.last().detail!!.contains("public origin"), result.toString())
    }

    @Test
    fun aSettingThatCannotBeOpenedFailsAtCredentialsAndTouchesNoStore() {
        val result = check().run(null, "The stored secret key cannot be opened — enter it again.", "https://veyq.example.com")

        assertFalse(result.passed)
        assertEquals(StoreCheckOutcome.FAILED, result.outcomes()[StoreCheckStep.CREDENTIALS])
        assertEquals(setOf(StoreCheckOutcome.SKIPPED), result.steps.drop(1).map { it.outcome }.toSet())
        assertTrue(requests.isEmpty(), "no request without credentials: $requests")
    }

    @Test
    fun aGarageStoreIsProvisionedFirstAndAFailedBringUpStopsTheCheck() {
        val result = check { error("garage admin unreachable") }.run(connection(type = StoreType.GARAGE), null, null)

        assertFalse(result.passed)
        assertEquals(StoreCheckOutcome.FAILED, result.outcomes()[StoreCheckStep.PROVISION])
        assertEquals(StoreCheckOutcome.SKIPPED, result.outcomes()[StoreCheckStep.BUCKET])
        assertTrue(requests.isEmpty(), "a failed bring-up stops before the store is asked anything: $requests")
    }

    @Test
    fun theResultRoundTripsThroughItsStoredJson() {
        val result = check().run(connection(), null, null)

        assertEquals(result, StoreCheckResult.fromJson(result.passed, result.checkedAt, result.toJson()))
    }

    private companion object {
        const val BUCKET = "blobs"
    }
}
