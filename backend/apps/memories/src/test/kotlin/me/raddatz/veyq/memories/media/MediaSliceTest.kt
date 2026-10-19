package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Slice tests for media blobs (en.memory.media, cr-0028) against the **in-memory store** (dev/test metadata
 * only — no byte transfer; the byte path is the opt-in [BlobBatchE2ETest]). Covers: start→complete→attach→list
 * metadata round-trip; partial-batch per-item result; illegal_target (422-equivalent per-item) on a FOLDER;
 * budget_exceeded on an over-cap declaration; non-member 404; unauth 401; operator-blind (the K_blob wrap
 * round-trips verbatim). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `MediaCoreSliceTest` in `:core`.
 */
@QuarkusTest
class MediaSliceTest {

    /** cr-0107: drives the real share delete-own path (cr-0102) to exercise the delete_revision bump + id-set. */
    @Inject
    lateinit var sharing: MediaShareGuestWrites

    @Test
    fun startCompleteListRoundTrip() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofChunks(2)
        val uploadId = startOne(userId, scopeId, blob, wrap = "d3JhcHBlZA==")

        // complete + attach to the Memory; the merged listed-metadata tier is listed, EXIF is lazy (cr-0068)
        val mediaId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory","wrappedMeta":"bWV0YQ==","wrappedExif":"ZXhpZg=="}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].mediaId", notNullValue())
            .body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        // list the node's media — operator-blind: the wrap + sealed listed-metadata blob come back verbatim.
        // The heavier EXIF tier is deliberately NOT listed (kept constant-small); it is lazy via getMediaExif.
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].wrappedBlobKey", equalTo("d3JhcHBlZA=="))
            .body("media[0].chunkCount", equalTo(2))
            .body("media[0].byteSize", equalTo(blob.byteSize.toInt()))
            .body("media[0].wrappedMeta", equalTo("bWV0YQ==")) // the listed-metadata tier is listed (geometry + date + GPS)
            .body("media[0].wrappedExif", nullValue()) // the list never carries EXIF
            .body("media[0].updatedAt", notNullValue()) // cr-0107: the keyset cursor's sort key
            .body("mediaRevision", equalTo(1)) // cr-0107: one attach bumped the node's revision to 1

        // the lazy exif endpoint returns the sealed EXIF verbatim (opaque, never inspected)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/media/$mediaId/exif")
            .then().statusCode(200)
            .body("wrappedExif", equalTo("ZXhpZg=="))
    }

    @Test
    fun mediaExifWithoutExifIsNull() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "b25seQ==")

        // complete with no EXIF sealed (source carried no readable EXIF)
        val mediaId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/media/$mediaId/exif")
            .then().statusCode(200)
            .body("wrappedExif", nullValue())
    }

    @Test
    fun mediaExifForUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/media/${UUID.randomUUID()}/exif")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun updateMetaOverwritesSealedTierAndLists() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "b25seQ==")

        // complete with an initial listed-metadata tier (cr-0068)
        val mediaId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory","wrappedMeta":"bWV0YQ=="}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        // PUT a re-sealed listed-metadata blob (e.g. a corrected date) — opaque, echoed back verbatim (cr-0068)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"Y29ycmVjdGVk","metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta")
            .then().statusCode(200).body("wrappedMeta", equalTo("Y29ycmVjdGVk"))

        // the overwritten value comes back on list (the tier is always listed)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("media[0].wrappedMeta", equalTo("Y29ycmVjdGVk"))

        // null clears it
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":null,"metaRevision":1}""")
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta")
            .then().statusCode(200).body("wrappedMeta", nullValue())
    }

    /**
     * B3 (ADR 0077): the media tier's write is compare-and-set like the node's. A stale write is `409 stale_revision`
     * with the current tier, writes nothing, and — because nothing changed — does NOT move `media_revision`, so no
     * open gallery re-fetches a media that did not change. Before the compare-and-set it answered 200 and replaced
     * the tier. The control lands at the current revision and moves both counters.
     */
    @Test
    fun staleMediaMetaWriteIs409AndMovesNoRevision() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val media = completeOne(userId, scopeId, memory, wrap = "YQ==", meta = "djA=") // media_revision = 1
        listedMedia(userId, scopeId, memory).body("media[0].metaRevision", equalTo(0)).body("mediaRevision", equalTo(1))

        putMeta(userId, scopeId, media, """{"wrappedMeta":"YS12MQ==","metaRevision":0}""").then().statusCode(200)
        listedMedia(userId, scopeId, memory).body("media[0].metaRevision", equalTo(1)).body("mediaRevision", equalTo(2))

        putMeta(userId, scopeId, media, """{"wrappedMeta":"Yi12MQ==","metaRevision":0}""")
            .then().statusCode(409)
            .body("code", equalTo("stale_revision"))
            .body("wrappedMeta", equalTo("YS12MQ=="))
            .body("metaRevision", equalTo(1))
        listedMedia(userId, scopeId, memory)
            .body("media[0].wrappedMeta", equalTo("YS12MQ=="))
            .body("media[0].metaRevision", equalTo(1))
            .body("mediaRevision", equalTo(2))

        // control: stating the revision the 409 handed back lands, and moves both counters
        putMeta(userId, scopeId, media, """{"wrappedMeta":"Yi12Mg==","metaRevision":1}""")
            .then().statusCode(200).body("wrappedMeta", equalTo("Yi12Mg=="))
        listedMedia(userId, scopeId, memory)
            .body("media[0].wrappedMeta", equalTo("Yi12Mg=="))
            .body("media[0].metaRevision", equalTo(2))
            .body("mediaRevision", equalTo(3))
    }

    /** B3, the wire half: an update with no revision is malformed, and nothing is written. */
    @Test
    fun mediaMetaWithoutRevisionIs400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val media = completeOne(userId, scopeId, memory, wrap = "YQ==", meta = "djA=")
        putMeta(userId, scopeId, media, """{"wrappedMeta":"YS12MQ=="}""").then().statusCode(400)
        listedMedia(userId, scopeId, memory).body("media[0].wrappedMeta", equalTo("djA="))
        // control: the same body with its revision lands
        putMeta(userId, scopeId, media, """{"wrappedMeta":"YS12MQ==","metaRevision":0}""").then().statusCode(200)
    }

    @Test
    fun updateMetaForUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"Y29ycmVjdGVk","metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/media/${UUID.randomUUID()}/meta")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun derivativesRoundTripWithOwnWraps() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        // three independent uploads: original + thumbnail + preview, each its own K_blob wrap
        val original = startOne(userId, scopeId, TestBlob.ofChunks(3), wrap = "b3JpZw==", meta = "b3JpZy1tZXRh")
        val mediaId = TestBlob.ownerOf(original)
        val thumb = startOne(userId, scopeId, TestBlob.SMALL, wrap = "dGh1bWI=", ownerId = mediaId, role = "THUMBNAIL", meta = "dGh1bWItbWV0YQ==")
        val preview = startOne(userId, scopeId, TestBlob.ofChunks(2), wrap = "cHJldg==", ownerId = mediaId, role = "PREVIEW")

        // complete: one logical media = original + two role-tagged derivatives
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","originalUploadId":"$original","derivatives":[""" +
                    """{"kind":"THUMBNAIL","uploadId":"$thumb"},{"kind":"PREVIEW","uploadId":"$preview"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].mediaId", notNullValue())
            .body("results[0].error", nullValue())

        // list: the original tier + both derivative tiers, each with its OWN wrap (no shared K_blob)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].wrappedBlobKey", equalTo("b3JpZw=="))
            .body("media[0].chunkCount", equalTo(3))
            .body("media[0].derivatives.size()", equalTo(2))
            .body("media[0].derivatives.find { it.kind == 'THUMBNAIL' }.wrappedBlobKey", equalTo("dGh1bWI="))
            .body("media[0].derivatives.find { it.kind == 'THUMBNAIL' }.chunkCount", equalTo(1))
            .body("media[0].derivatives.find { it.kind == 'PREVIEW' }.wrappedBlobKey", equalTo("cHJldg=="))
            .body("media[0].derivatives.find { it.kind == 'PREVIEW' }.chunkCount", equalTo(2))
            // each tier's sealed blob meta, as its own upload declared it; the preview declared none
            .body("media[0].wrappedBlobMeta", equalTo("b3JpZy1tZXRh"))
            .body("media[0].derivatives.find { it.kind == 'THUMBNAIL' }.wrappedBlobMeta", equalTo("dGh1bWItbWV0YQ=="))
            .body("media[0].derivatives.find { it.kind == 'PREVIEW' }.wrappedBlobMeta", nullValue())
    }

    @Test
    fun mediaWithoutDerivativesListsEmptyTiers() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val original = startOne(userId, scopeId, TestBlob.SMALL, wrap = "b25seQ==")

        // a media with no derivable frame attaches the original only
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","originalUploadId":"$original"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].wrappedBlobKey", equalTo("b25seQ=="))
            .body("media[0].derivatives.size()", equalTo(0))
            .body("media[0].wrappedMeta", nullValue()) // none sealed → null (the grid uses a default aspect)
    }

    @Test
    fun derivativeUploadNotFoundFailsWholeItem() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val original = startOne(userId, scopeId, TestBlob.SMALL, wrap = "b3JpZw==")
        val bogus = UUID.randomUUID()

        // a missing derivative session fails the whole item atomically — nothing is attached
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original)}","originalUploadId":"$original",""" +
                    """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$bogus"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].mediaId", nullValue())

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(0))
    }

    @Test
    fun partialBatchPerItemResult() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "aw==")
        val bogus = UUID.randomUUID()

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory"},{"mediaId":"${TestBlob.ownerOf(bogus.toString())}","originalUploadId":"$bogus","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].mediaId", notNullValue())
            .body("results[1].error", equalTo("upload_not_found"))
            .body("results[1].mediaId", nullValue())
    }

    @Test
    fun mediaOnFolderIsIllegalTarget() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "aw==")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$folder"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("illegal_target"))
            .body("results[0].blobId", nullValue())
    }

    @Test
    fun mediaOnAnUnknownKindIsIllegalTarget() {
        // The allow-list bites on a kind that is none of FOLDER/MEMORY/CHAPTER (a deny-list of FOLDER would let it through).
        val (userId, scopeId) = seedMemberScope()
        val alien = seedNode(scopeId, "ALIEN", wrappedContentKey = CONTENT_KEY)
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = "aw==")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$alien"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("illegal_target"))
    }

    // --- live media updates (uc.memory.live-updates, cr-0107, ADR 0033) ---

    @Test
    fun unchangedPollIsNotModified() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==") // one attach → media_revision = 1

        // the initial full list caches the revision (the ETag)
        val rev = given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("mediaRevision", equalTo(1))
            .extract().path<Int>("mediaRevision").toString()

        // a re-poll echoing that revision as If-None-Match short-circuits to 304 — the O(1) idle fast path, no body
        given().header("Authorization", bearer(userId)).header("If-None-Match", rev)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(304)
    }

    @Test
    fun attachDeltaCarriesOnlyNewMediaAndNoIdSet() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        completeOne(userId, scopeId, memory, wrap = "YQ==") // media_revision = 1

        val cursor = newestCursor(userId, scopeId, memory) // cache the newest (updatedAt, mediaId)

        // another member attaches a second media → media_revision = 2
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        // the poll streams ONLY the new media past the cursor (a keyset delta, never a re-list), and — since no
        // delete happened (delete_revision unchanged) — carries NO O(N) id-set
        given().header("Authorization", bearer(userId)).header("If-None-Match", "1")
            .queryParam("since", cursor).queryParam("sinceDelete", 0)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("mediaRevision", equalTo(2))
            .body("deleteRevision", equalTo(0))
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(b))
            .body("mediaIds", nullValue()) // pure add → the id-set is skipped
    }

    @Test
    fun metaEditSurfacesViaCursorAndNoIdSet() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==", meta = "bWV0YQ==") // media_revision = 1

        val cursor = newestCursor(userId, scopeId, memory)

        // a meta edit re-seals the tier → media_revision bumps + updated_at advances past the cursor
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"Y29ycmVjdGVk","metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/media/$a/meta")
            .then().statusCode(200)

        // the edited media re-surfaces via the cursor as a delta upsert; still no id-set (nothing was deleted)
        given().header("Authorization", bearer(userId)).header("If-None-Match", "1")
            .queryParam("since", cursor).queryParam("sinceDelete", 0)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("mediaRevision", equalTo(2))
            .body("deleteRevision", equalTo(0))
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(a))
            .body("media[0].wrappedMeta", equalTo("Y29ycmVjdGVk"))
            .body("mediaIds", nullValue())
    }

    @Test
    fun deleteBumpsBothCountersAndIdSetOmitsRemoved() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==") // media_revision = 1
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==") // media_revision = 2

        // make b contributor-owned so the real cr-0102 delete-own path authorizes it, then delete it: that path
        // bumps BOTH counters (cr-0107) — media_revision 2→3, delete_revision 0→1
        QuarkusTransaction.requiringNew().run { MediaEntity.findById(b)!!.shareIdentityId = "d8a912ea-41ab-413a-97a2-2d4c91ccb02c" }
        sharing.deleteOwn(scopeId, memory, b, "d8a912ea-41ab-413a-97a2-2d4c91ccb02c")

        // a poll whose sinceDelete (0) lags delete_revision (1) gets the current id-set — only a remains, b dropped
        given().header("Authorization", bearer(userId)).header("If-None-Match", "2")
            .queryParam("sinceDelete", 0)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("mediaRevision", equalTo(3))
            .body("deleteRevision", equalTo(1))
            .body("mediaIds.size()", equalTo(1))
            .body("mediaIds", hasItem(a))
    }

    @Test
    fun netZeroDeleteAddStillCarriesIdSet() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==") // media_revision = 1
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==") // media_revision = 2

        val cursor = newestCursor(userId, scopeId, memory) // at b (the newest)

        // net-zero: delete b (media_revision 2→3, delete_revision 0→1) then add c (media_revision 3→4). The count
        // returns to 2 but the SET changed — a count-only signal would miss it; the id-set catches it.
        QuarkusTransaction.requiringNew().run { MediaEntity.findById(b)!!.shareIdentityId = "d8a912ea-41ab-413a-97a2-2d4c91ccb02c" }
        sharing.deleteOwn(scopeId, memory, b, "d8a912ea-41ab-413a-97a2-2d4c91ccb02c")
        val c = completeOne(userId, scopeId, memory, wrap = "Yw==")

        // the poll: c rides the delta (past the cursor) AND the id-set is present (a delete advanced delete_revision),
        // reconciling to {a, c} — b dropped despite the count staying 2
        given().header("Authorization", bearer(userId)).header("If-None-Match", "2")
            .queryParam("since", cursor).queryParam("sinceDelete", 0)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("mediaRevision", equalTo(4))
            .body("deleteRevision", equalTo(1))
            .body("media.mediaId", hasItem(c)) // c streams in via the keyset delta
            .body("mediaIds.size()", equalTo(2))
            .body("mediaIds", hasItem(a))
            .body("mediaIds", hasItem(c))
    }

    // --- helpers ---

    /** Start + complete one media on [nodeId] (in-memory store); returns its mediaId. Optionally seals a listed-metadata tier. */
    private fun putMeta(userId: String, scopeId: String, mediaId: String, body: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().put("/api/scopes/$scopeId/media/$mediaId/meta")

    private fun listedMedia(userId: String, scopeId: String, nodeId: String) =
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
            .then().statusCode(200).body("media.size()", equalTo(1))

    private fun completeOne(userId: String, scopeId: String, nodeId: String, wrap: String, meta: String? = null): String {
        val uploadId = startOne(userId, scopeId, TestBlob.SMALL, wrap = wrap)
        val metaField = if (meta != null) ""","wrappedMeta":"$meta"""" else ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$nodeId"$metaField}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].mediaId")
    }

    /** The node's keyset cursor as a full list (one page here) answers it: the position of its last row. */
    private fun newestCursor(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
            .then().statusCode(200).body("more", equalTo(false))
            .extract().path("cursor")

    /** Start one blob upload (in-memory store: presignedPuts come back null) and return its uploadId. */
    // The request-body bounds are enforced at the boundary, not in the handler: a violation answers core's
    // `ConstraintViolationExceptionMapper` (400 `invalid_request`, nothing of the failing field — inbox #355), so each
    // refusal is paired with a control that differs only in the bound. `validate_request_bounds.py` proves each
    // annotation is PRESENT; these prove the two mechanisms it relies on have an EFFECT — a top-level bound, and the
    // `@Valid` cascade.

    private fun startOne(
        userId: String, scopeId: String, blob: TestBlob, wrap: String,
        ownerId: String = UUID.randomUUID().toString(), role: String = "ORIGINAL", meta: String? = null,
    ): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role, meta = meta)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].presignedPuts", nullValue())
            .extract().path("results[0].uploadId")
}
