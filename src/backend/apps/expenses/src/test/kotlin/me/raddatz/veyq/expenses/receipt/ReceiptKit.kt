package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.nullValue
import java.time.Instant
import java.util.UUID

/** The receipts' wire as a client speaks it — core's transport, then core's blob service's completion and reads, a receipt
 *  being an application blob of the Pot held by its entry (ADR 0112) — for this module's tests. */
object ReceiptKit {
    const val WRAP = "d3JhcC1rLWJsb2I="
    const val META = "c2VhbGVkLWJsb2ItbWV0YQ=="

    /** One upload of a receipt for the Pot [pot] (its owner the Pot, its role the receipt's, unless named). */
    fun start(
        userId: String, scopeId: String, pot: String, blob: TestBlob = TestBlob.SMALL, role: String = ExpensesBlobRoles.RECEIPT,
        owner: String = pot,
    ): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(WRAP, ownerId = owner, role = role, meta = META)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch").then()

    /** [start], answered, and its chunks staged at their exact lengths, as an honest client leaves them. */
    fun startStaged(
        store: FakePresigningBlobStore, userId: String, scopeId: String, pot: String, blob: TestBlob = TestBlob.SMALL,
        role: String = ExpensesBlobRoles.RECEIPT, owner: String = pot,
    ): String {
        val uploadId = start(userId, scopeId, pot, blob, role, owner).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(store, sessionKey(uploadId))
        return uploadId
    }

    fun complete(
        userId: String, scopeId: String, pot: String, entryId: String, receiptId: String, uploadId: String, nodeEpoch: Int = 0,
    ): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":$nodeEpoch,"ownerId":"$entryId","id":"$receiptId","role":"${ExpensesBlobRoles.RECEIPT}","uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$pot/application-blobs").then()

    /** A member's receipt of [entryId], uploaded and completed: its receipt id. */
    fun upload(
        store: FakePresigningBlobStore, userId: String, scopeId: String, pot: String, entryId: String,
        blob: TestBlob = TestBlob.SMALL, receiptId: String = UUID.randomUUID().toString(),
    ): String {
        complete(userId, scopeId, pot, entryId, receiptId, startStaged(store, userId, scopeId, pot, blob)).statusCode(200)
        return receiptId
    }

    fun guestStart(link: GuestTreeShare.Link, pot: String, blob: TestBlob = TestBlob.SMALL, role: String = ExpensesBlobRoles.RECEIPT): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeId":"$pot","items":[${blob.declare(WRAP, ownerId = pot, role = role, meta = META)}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()

    fun guestStartStaged(store: FakePresigningBlobStore, link: GuestTreeShare.Link, pot: String, blob: TestBlob = TestBlob.SMALL): String {
        val uploadId = guestStart(link, pot, blob).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(store, sessionKey(uploadId))
        return uploadId
    }

    fun guestComplete(
        link: GuestTreeShare.Link, pot: String, entryId: String, receiptId: String, uploadId: String, xff: String = "203.0.113.80:40000",
    ): ValidatableResponse =
        given().contentType(ContentType.JSON).header("X-Forwarded-For", xff)
            .body("""{"nodeEpoch":0,"contributorToken":"${link.token}","ownerId":"$entryId","id":"$receiptId","role":"${ExpensesBlobRoles.RECEIPT}","uploadId":"$uploadId"}""")
            .`when`().post("/api/shares/${link.shareId}/nodes/$pot/application-blobs").then()

    fun list(userId: String, scopeId: String, pot: String, query: String = "", ifNoneMatch: String? = null): ValidatableResponse {
        var spec = given().header("Authorization", bearer(userId))
        if (ifNoneMatch != null) spec = spec.header("If-None-Match", ifNoneMatch)
        return spec.`when`().get("/api/scopes/$scopeId/nodes/$pot/application-blobs$query").then()
    }

    fun guestList(shareId: String, pot: String, query: String = "", ifNoneMatch: String? = null): ValidatableResponse {
        var spec = given()
        if (ifNoneMatch != null) spec = spec.header("If-None-Match", ifNoneMatch)
        return spec.`when`().get("/api/shares/$shareId/nodes/$pot/application-blobs$query").then()
    }

    /** A member's download of [blobId]: core's presigned GETs, or the item's error. */
    fun download(userId: String, scopeId: String, blobId: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch").then()

    /** A guest's download of [blobId]: core's share blob GET. */
    fun guestDownload(shareId: String, blobId: String): ValidatableResponse =
        given().`when`().get("/api/shares/$shareId/blobs/$blobId").then()

    /** A moderator's content block on the item [itemId] (an entry or a receipt), as `takeMeasure` leaves its row. */
    fun block(scopeId: String, itemId: String) = QuarkusTransaction.requiringNew().run {
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString(); kind = BlockKind.CONTENT_ITEM.name; targetId = itemId; this.scopeId = scopeId
            takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
            activeKey = ModerationBlockEntity.activeKeyOf(BlockKind.CONTENT_ITEM, itemId)
        }.persist()
    }

    fun sessionKey(uploadId: String): String = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }

    fun receipt(id: String): ApplicationBlobEntity? = QuarkusTransaction.requiringNew().call { ApplicationBlobEntity.findById(id) }

    /** A receipt of [entryId] in the Pot [pot], seeded as its completion leaves it (born kept, as for a live entry):
     *  a blob row and the service's row naming it — its id. */
    fun seed(scopeId: String, pot: String, entryId: String, writer: String, blobWrap: String = WRAP, meta: String? = null): Pair<String, String> =
        QuarkusTransaction.requiringNew().call {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString(); chunkCount = 1; byteSize = 44
                wrappedBlobKey = blobWrap; wrappedBlobMeta = meta
            }.persist()
            val now = Instant.now()
            val row = ApplicationBlobEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; nodeId = pot; ownerId = entryId; role = ExpensesBlobRoles.RECEIPT
                this.blobId = blobId; writerUserId = writer; createdAt = now; keptAt = now
            }
            row.persist()
            row.id to blobId
        }

    fun blobOf(receiptId: String): String = checkNotNull(receipt(receiptId)) { "no receipt $receiptId" }.blobId

    fun blob(id: String): BlobEntity? = QuarkusTransaction.requiringNew().call { BlobEntity.findById(id) }
}
