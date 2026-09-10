package com.veyq.app.shared.blobstore

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CORSConfiguration
import software.amazon.awssdk.services.s3.model.CORSRule
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * The one provisioning step that is **generic S3 rather than vendor mechanics** (ADR 0067): the bucket's CORS
 * policy, which ADR 0013 §4 requires and which — measured — nothing in this repository wrote before ADR 0067.
 * `garage-init.sh` never had it, so this is new work and not moved work, and it is the step with no working
 * example in the tree to copy.
 *
 * **Why it is a port of its own rather than an [S3Client] handed to the provisioner.** ADR 0062 Decision 3 says
 * exactly one component deletes store objects, and an `S3Client` is a capability over *every* object in the
 * bucket. Confining it here leaves the provisioner holding two methods that cannot reach an object at all, so
 * the arity invariant holds structurally and not only because `scripts/validate_store_reclaim_arity.py` greps
 * for `blobStore.delete` — which is a `BlobStore`-shaped predicate and would never have seen a raw SDK call.
 *
 * The AWS SDK is right here for the reason CLAUDE.md's dependency rule gives: this call is real S3, spoken by
 * the vendor-neutral client, so it is the one piece of the bring-up that would work unchanged against any
 * S3-compatible store.
 */
interface BucketCorsPolicy {

    /** The allowed origins currently configured on [bucket], or `null` when the bucket carries no policy. */
    fun allowedOrigins(bucket: String): List<String>?

    /** Replace [bucket]'s CORS policy with one allowing `PUT`+`GET` from [origins], exposing `ETag`. */
    fun put(bucket: String, origins: List<String>)
}

/**
 * [BucketCorsPolicy] over the vendor-neutral AWS SDK. The [s3] client is built by
 * [StoreProvisioningProducer] against the store's *internal* endpoint with the store's own credentials — the
 * same connection `S3BlobStore` uses, and a separate client instance, because this one is short-lived
 * provisioning work rather than the request-path client.
 */
class S3BucketCorsPolicy(private val s3: S3Client) : BucketCorsPolicy {

    override fun allowedOrigins(bucket: String): List<String>? = try {
        s3.getBucketCors(GetBucketCorsRequest.builder().bucket(bucket).build())
            .corsRules()
            .flatMap { it.allowedOrigins() }
    } catch (e: S3Exception) {
        // A bucket with no policy at all answers `NoSuchCORSConfiguration`, which is an ABSENCE and not a
        // failure — the state this step exists to repair. Any other S3Exception is a real fault and is
        // rethrown, because a provisioner that reads "no policy" out of an auth error would then write one,
        // report success, and leave the store unusable.
        if (e.awsErrorDetails()?.errorCode() == NO_SUCH_CORS_CONFIGURATION) null else throw e
    }

    override fun put(bucket: String, origins: List<String>) {
        s3.putBucketCors(
            PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(
                    CORSConfiguration.builder()
                        .corsRules(
                            CORSRule.builder()
                                .allowedOrigins(origins)
                                .allowedMethods("PUT", "GET")
                                .allowedHeaders("*")
                                .exposeHeaders("ETag")
                                .maxAgeSeconds(MAX_AGE_SECONDS)
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
    }

    companion object {
        private const val NO_SUCH_CORS_CONFIGURATION = "NoSuchCORSConfiguration"
        private const val MAX_AGE_SECONDS = 3600
    }
}
