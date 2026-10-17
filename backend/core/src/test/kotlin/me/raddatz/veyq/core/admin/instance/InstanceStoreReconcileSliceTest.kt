package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.ScriptedBucket
import me.raddatz.veyq.core.blobstore.StoreOrphanEntity
import me.raddatz.veyq.core.blobstore.StoreReconcileEntity
import me.raddatz.veyq.core.blobstore.StoreReconcileSweep
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The bucket reconcile's read and its two switches over the full HTTP stack on the admin channel (decision 9 of
 * cr-bucket-reconcile): who may call them, what the verdict says after each switch, and the one refusal the contract
 * names. What a pass finds is [me.raddatz.veyq.core.blobstore.StoreReconcileSliceTest]'s; here a pass runs only to show
 * its counts reach the wire.
 */
@QuarkusTest
class InstanceStoreReconcileSliceTest {

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var sweep: StoreReconcileSweep

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, "reconcile-owner@example.com", "SERVER_OWNER")
        ensureUser(MEMBER_ID, "reconcile-member@example.com", "MEMBER")
        QuarkusTransaction.requiringNew().run {
            StoreOrphanEntity.deleteAll()
            StoreReconcileEntity.deleteAll()
        }
    }

    @AfterEach
    fun forget() {
        recorder.bucket = null
    }

    @Test
    fun withNoStoreThereIsNothingToConfirm() {
        read().statusCode(200).body("verdict", equalTo("NO_STORE")).body("lastPass", nullValue())
        owner().`when`().put(EXCLUSIVE).then().statusCode(409).body("code", equalTo("no_store"))
    }

    @Test
    fun theSwitchesMoveTheVerdictAndThePassReachesTheWire() {
        val bucket = ScriptedBucket(digest = "bucket-${UUID.randomUUID()}", storeTime = Instant.parse("2026-10-17T12:00:00Z"))
        bucket.put("${UUID.randomUUID()}/0", 42, Instant.parse("2026-10-17T10:00:00Z"))
        recorder.bucket = bucket
        sweep.tick()

        read().statusCode(200)
            .body("verdict", equalTo("NOT_CONFIRMED"))
            .body("exclusiveBucketConfirmedAt", nullValue())
            .body("lastPass.orphanObjects", equalTo(1))
            .body("lastPass.orphanBytes", equalTo(42))
            .body("candidates.storageKeys", equalTo(1))
            .body("candidates.firstDueAt", equalTo("2026-10-17T14:00:00Z"))
            .body("graceSeconds", equalTo(7200))

        owner().`when`().put(EXCLUSIVE).then().statusCode(200)
            .body("verdict", equalTo("ARMED")).body("exclusiveBucketConfirmedAt", notNullValue())
        owner().`when`().put(PAUSE).then().statusCode(200).body("verdict", equalTo("PAUSED")).body("pausedAt", notNullValue())
        owner().`when`().delete(PAUSE).then().statusCode(200).body("verdict", equalTo("ARMED")).body("pausedAt", nullValue())
        owner().`when`().delete(EXCLUSIVE).then().statusCode(200).body("verdict", equalTo("NOT_CONFIRMED"))

        // Confirmed for this bucket, then another one is in use: the confirmation does not carry over.
        owner().`when`().put(EXCLUSIVE).then().statusCode(200).body("verdict", equalTo("ARMED"))
        bucket.digest = "another-bucket-${UUID.randomUUID()}"
        read().statusCode(200).body("verdict", equalTo("NOT_CONFIRMED")).body("exclusiveBucketConfirmedAt", nullValue())
    }

    @Test
    fun aMemberIsNotTheServerOwnerAndIsRefusedOnEveryOperation() {
        member().`when`().get(RECONCILE).then().statusCode(403).body("code", equalTo("not_server_owner"))
        member().`when`().put(EXCLUSIVE).then().statusCode(403)
        member().`when`().delete(EXCLUSIVE).then().statusCode(403)
        member().`when`().put(PAUSE).then().statusCode(403)
        member().`when`().delete(PAUSE).then().statusCode(403)
        // The control: the same calls answer the Server Owner.
        read().statusCode(200)
    }

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun member() = givenAdminChannel().header("Authorization", bearer(MEMBER_ID))

    private fun read(): ValidatableResponse = owner().`when`().get(RECONCILE).then()

    private companion object {
        const val OWNER_ID = "3e0c5a9d-6b21-4f8e-9c47-a1d2b3c4e5f6"
        const val MEMBER_ID = "8f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b"
        const val RECONCILE = "/api/instance/store/reconcile"
        const val EXCLUSIVE = "$RECONCILE/exclusive-bucket"
        const val PAUSE = "$RECONCILE/pause"
    }
}
