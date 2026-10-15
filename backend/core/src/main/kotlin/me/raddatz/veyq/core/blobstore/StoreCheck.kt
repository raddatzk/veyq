package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** The check's steps, in the order they run — the wire's `InstanceStoreCheckStep.step`. */
enum class StoreCheckStep { CREDENTIALS, PROVISION, BUCKET, PROBE_PUT, PROBE_GET, CORS }

enum class StoreCheckOutcome { PASSED, FAILED, SKIPPED }

data class StoreCheckStepResult(val step: StoreCheckStep, val outcome: StoreCheckOutcome, val detail: String?)

/** One check of the store setting: whether it passed, when it ran, and every step's outcome in order. */
data class StoreCheckResult(val passed: Boolean, val checkedAt: Instant, val steps: List<StoreCheckStepResult>) {
    fun toJson(): String = MAPPER.writeValueAsString(steps.map { mapOf("step" to it.step.name, "outcome" to it.outcome.name, "detail" to it.detail) })

    companion object {
        private val MAPPER = ObjectMapper()

        fun fromJson(passed: Boolean, checkedAt: Instant, json: String): StoreCheckResult {
            val raw: List<Map<String, String?>> = MAPPER.readValue(json, object : TypeReference<List<Map<String, String?>>>() {})
            return StoreCheckResult(
                passed,
                checkedAt,
                raw.map { StoreCheckStepResult(StoreCheckStep.valueOf(it["step"]!!), StoreCheckOutcome.valueOf(it["outcome"]!!), it["detail"]) },
            )
        }
    }
}

/**
 * The check of ADR 0089 decision 2, against one resolved store. The steps run in order and stop at the first
 * failure; every later step is SKIPPED with no detail, so the operator reads exactly where it broke.
 *
 * **What it may do to the store is the point of this class.** For an S3-compatible store it changes no
 * configuration at all: the bucket is asked about (`HeadBucket`), never created; one fixed probe object under
 * [PROBE_KEY] is written through a presigned PUT and read back through a presigned GET — the store's ordinary data
 * plane, overwritten by every check and never deleted, so object deletion keeps its one caller
 * (`StoreReclaimSweep`, `validate_store_reclaim_arity.py`); and a CORS preflight is sent, never a CORS policy
 * written. Creating a bucket or writing a policy is provisioning, which ADR 0067 decision 1 keeps away from an
 * unknown store; only a Garage store is provisioned, by the [provision] step, before the same check runs.
 *
 * The probe goes through presigned URLs against the endpoint browsers use, signed exactly as an upload is, because
 * that is the path that fails when a store is misconfigured: credentials the backend can sign with but the store
 * rejects, a public endpoint nothing answers at, a CORS rule that does not name this instance's origin.
 *
 * Pure apart from the network it is pointed at: every collaborator is a parameter, so `StoreCheckTest` drives it
 * against a local fake S3 endpoint and observes every request the check sends.
 */
class StoreCheck(
    private val s3For: (StoreConnection) -> S3Client,
    private val signerFor: (StoreConnection) -> BlobStore,
    private val provision: (StoreConnection) -> Unit,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
    private val now: () -> Instant = Instant::now,
) {

    /**
     * Check [connection]; null means the setting resolved to nothing usable, and [unreadableDetail] says why (the
     * CREDENTIALS step's failure). [publicOrigin] is the origin browsers open this instance at — the CORS preflight
     * is sent with it — or null when the deployment names none.
     */
    fun run(connection: StoreConnection?, unreadableDetail: String?, publicOrigin: String?): StoreCheckResult {
        val steps = mutableListOf<StoreCheckStepResult>()
        val ok = runSteps(connection, unreadableDetail, publicOrigin, steps)
        StoreCheckStep.entries.drop(steps.size).forEach { steps += StoreCheckStepResult(it, StoreCheckOutcome.SKIPPED, null) }
        return StoreCheckResult(ok, now(), steps)
    }

    private fun runSteps(
        connection: StoreConnection?,
        unreadableDetail: String?,
        publicOrigin: String?,
        steps: MutableList<StoreCheckStepResult>,
    ): Boolean {
        fun pass(step: StoreCheckStep, detail: String? = null) = steps.add(StoreCheckStepResult(step, StoreCheckOutcome.PASSED, detail))
        fun skip(step: StoreCheckStep, detail: String) = steps.add(StoreCheckStepResult(step, StoreCheckOutcome.SKIPPED, detail))
        fun fail(step: StoreCheckStep, detail: String): Boolean {
            steps.add(StoreCheckStepResult(step, StoreCheckOutcome.FAILED, detail))
            return false
        }

        if (connection == null) return fail(StoreCheckStep.CREDENTIALS, unreadableDetail ?: "No store is set.")
        pass(StoreCheckStep.CREDENTIALS)

        if (connection.type == StoreType.GARAGE) {
            try {
                provision(connection)
                pass(StoreCheckStep.PROVISION)
            } catch (e: Exception) {
                return fail(StoreCheckStep.PROVISION, "Bringing the Garage store up failed: ${describe(e)}")
            }
        } else {
            skip(StoreCheckStep.PROVISION, "An S3-compatible store is never provisioned by veyq; its bucket, key and CORS rule are the operator's.")
        }

        // Asked, never created: a missing bucket is the operator's to create on a store veyq does not own.
        try {
            s3For(connection).use { it.headBucket(HeadBucketRequest.builder().bucket(connection.bucket).build()) }
            pass(StoreCheckStep.BUCKET)
        } catch (e: S3Exception) {
            return fail(
                StoreCheckStep.BUCKET,
                when (e.statusCode()) {
                    404 -> "The bucket ${connection.bucket} does not exist (HTTP 404)."
                    403 -> "The access key may not use the bucket ${connection.bucket} (HTTP 403)."
                    else -> "The store answered HTTP ${e.statusCode()} for the bucket ${connection.bucket}."
                },
            )
        } catch (e: SdkClientException) {
            return fail(StoreCheckStep.BUCKET, "The store did not answer at ${connection.endpoint}: ${describe(e)}")
        }

        val signer = signerFor(connection)
        val putUrl = signer.presignPut(PROBE_KEY, PROBE_BODY.size.toLong(), null)
            ?: return fail(StoreCheckStep.PROBE_PUT, "The store cannot sign an upload.")
        val put = send(HttpRequest.newBuilder(URI.create(putUrl)).timeout(TIMEOUT)
            .header("Content-Type", PROBE_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(PROBE_BODY)).build())
        when {
            put.error != null -> return fail(StoreCheckStep.PROBE_PUT, "The presigned upload could not reach ${originOf(putUrl)}: ${put.error}")
            put.status !in 200..299 -> return fail(StoreCheckStep.PROBE_PUT, "The presigned upload to ${originOf(putUrl)} answered HTTP ${put.status}.")
            else -> pass(StoreCheckStep.PROBE_PUT)
        }

        val getUrl = signer.presignGet(PROBE_KEY, null)
            ?: return fail(StoreCheckStep.PROBE_GET, "The store cannot sign a download.")
        val get = send(HttpRequest.newBuilder(URI.create(getUrl)).timeout(TIMEOUT).GET().build())
        when {
            get.error != null -> return fail(StoreCheckStep.PROBE_GET, "The presigned download could not reach ${originOf(getUrl)}: ${get.error}")
            get.status != 200 -> return fail(StoreCheckStep.PROBE_GET, "The presigned download from ${originOf(getUrl)} answered HTTP ${get.status}.")
            !get.body.contentEquals(PROBE_BODY) -> return fail(StoreCheckStep.PROBE_GET, "The presigned download returned other bytes than were uploaded.")
            else -> pass(StoreCheckStep.PROBE_GET)
        }

        if (connection.signingMode == SigningMode.TENANT_DOMAIN) {
            skip(StoreCheckStep.CORS, "The store is served on each tenant's own domain, so no browser request to it is cross-origin.")
            return true
        }
        if (publicOrigin == null) {
            skip(StoreCheckStep.CORS, "No public origin is configured for this instance (veyq.tenant.ingress.bootstrap-domain), so there is no origin to send a preflight with.")
            return true
        }
        val preflight = send(HttpRequest.newBuilder(URI.create(putUrl)).timeout(TIMEOUT)
            .header("Origin", publicOrigin)
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "content-type")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build())
        val allowed = preflight.allowOrigin
        return when {
            preflight.error != null -> fail(StoreCheckStep.CORS, "The preflight could not reach ${originOf(putUrl)}: ${preflight.error}")
            allowed == publicOrigin || allowed == "*" -> { pass(StoreCheckStep.CORS); true }
            else -> fail(
                StoreCheckStep.CORS,
                "The store's preflight answered HTTP ${preflight.status} without allowing $publicOrigin" +
                    (allowed?.let { " (it allows $it)" } ?: "") + " — its bucket needs a CORS rule for that origin.",
            )
        }
    }

    private class Answer(val status: Int, val body: ByteArray, val allowOrigin: String?, val error: String?)

    private fun send(request: HttpRequest): Answer = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        Answer(response.statusCode(), response.body() ?: ByteArray(0), response.headers().firstValue("Access-Control-Allow-Origin").orElse(null), null)
    } catch (e: IOException) {
        Answer(0, ByteArray(0), null, describe(e))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Answer(0, ByteArray(0), null, "interrupted")
    }

    companion object {
        /** The one object the check writes: fixed, so every check overwrites the last and nothing accumulates. */
        const val PROBE_KEY = "veyq-check/probe"
        private const val PROBE_TYPE = "application/octet-stream"
        private val PROBE_BODY = "veyq store check\n".toByteArray(Charsets.UTF_8)
        private val TIMEOUT: Duration = Duration.ofSeconds(10)

        /** The exception's own message, or its type — never more: a store's answer about itself, not a stack. */
        private fun describe(e: Throwable): String = (e.message?.lineSequence()?.firstOrNull()?.take(300)) ?: e.javaClass.simpleName

        private fun originOf(url: String): String = URI.create(url).let { "${it.scheme}://${it.authority}" }
    }
}
