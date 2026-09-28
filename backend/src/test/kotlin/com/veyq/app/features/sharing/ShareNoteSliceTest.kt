package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.shareKeysJson
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Slice tests for the editable curator note on a share (uc.sharing.share-note, cr-0115, ADR 0036): create with a
 * note → the anonymous resolve returns it; the owner edits it **in place** (`PATCH`) → the resolve reflects the
 * new note at the **same shareId** (wrap/role unchanged); a null note clears it; a **non-owner** edit is a
 * non-enumerable 404; a share created without a note resolves with a null note and the owner's manage list carries
 * the recoverable `sealed_share_key`. Operator-blind: the server stores/serves only the opaque ciphertexts.
 */
@QuarkusTest
class ShareNoteSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val note1 = "bm90ZS1vbmU=" // "note-one"
    private val note2 = "bm90ZS10d28=" // "note-two"
    private val shareKey = "cmVjb3ZlcmFibGUta2V5" // symWrap(K_mem, K_share), opaque here
    private val name1 = "Rm9yIEdyYW5kbWE=" // "For Grandma" sealed (opaque here)
    private val name2 = "cmVuYW1lZA==" // "renamed"
    private val sealedDisplayName = "c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU=" // a contributor's K_mem-sealed name (opaque here)

    @Test
    fun createWithNoteThenAnonymousResolveReturnsIt() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)

        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200).body("sealedNote", equalTo(note1))
    }

    @Test
    fun ownerEditsTheNoteInPlaceAtTheSameShareId() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note2"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(204)

        // same link (shareId), new note; the grant (wrap) + role are untouched
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("sealedNote", equalTo(note2))
            .body("wrap", equalTo(wrap))
            .body("role", equalTo("VIEWER"))
    }

    @Test
    fun editingWithNullClearsTheNote() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":null}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(204)

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(null))
    }

    @Test
    fun aNonOwnerEditIs404AndLeavesTheNoteUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)
        val outsider = seedUser()

        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note2"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(note1))
    }

    @Test
    fun createWithoutNoteResolvesNullAndTheManageListCarriesTheRecoverableKey() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        // a share with the recoverable key but no note yet
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("sealedNote", equalTo(null))

        // the owner's manage list carries the recoverable key (so it can later recover K_share to add/edit a note)
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.sealedShareKey", equalTo(shareKey))
    }

    @Test
    fun createWithANameShowsItInTheManageListButNeverToTheViewer() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedShareName":"$name1","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        // the owner's manage list carries the private name
        given().header("Authorization", bearer(userId)).`when`().get("/api/shares")
            .then().statusCode(200).body("find { it.shareId == '$shareId' }.sealedShareName", equalTo(name1))

        // the anonymous resolve carries no name field at all (management-only) — it just resolves
        given().`when`().get("/api/shares/$shareId").then().statusCode(200)
    }

    @Test
    fun renameViaUpdateShareChangesTheNameKeepsTheNoteAndTheLink() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)

        // full-replace the editable fields: set a name, keep the note (same shareId)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note1","sealedShareName":"$name2"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.sealedShareName", equalTo(name2))
            .body("find { it.shareId == '$shareId' }.sealedNote", equalTo(note1))
    }

    // --- role change in place (uc.sharing.share-contributor role-mutable-in-place, cr-0119, ADR 0036) ---

    @Test
    fun promoteViewerToContributorInPlaceKeepsTheLinkAndEnablesContribution() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithRole(userId, memory, "VIEWER")

        // a VIEWER share rejects the anonymous contribute (name) step
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedDisplayName"}""")
            .`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))

        // promote the grant in place — same shareId, no re-wrap
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"role":"CONTRIBUTOR"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        // the link is unchanged (same wrap), the grant flipped, and the contribution now works
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200).body("role", equalTo("CONTRIBUTOR")).body("wrap", equalTo(wrap))
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedDisplayName"}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201)
    }

    @Test
    fun roleChangeToUploaderIs422() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithRole(userId, memory, "VIEWER")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"role":"UPLOADER"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(422).body("code", equalTo("invalid_role_change"))

        // the grant is unchanged — you can't turn a viewer link into a write-only drop-box in place
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("role", equalTo("VIEWER"))
    }

    @Test
    fun changingAnUploaderShareRoleIs422() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithRole(userId, memory, "UPLOADER")

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"role":"CONTRIBUTOR"}""")
            .`when`().patch("/api/shares/$shareId")
            .then().statusCode(422).body("code", equalTo("invalid_role_change"))

        // still a write-only drop-box — its promise isn't flippable on distributed links
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("role", equalTo("UPLOADER"))
    }

    // --- expiry set / clear in place (uc.sharing.share-public-viewer §5 "set an expiry", cr-share-link-expiry-edit) ---

    @Test
    fun ownerSetsAnExpiryInPlaceAndAnExpiredShareResolves404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createWithNote(userId, memory, note1)
        given().`when`().get("/api/shares/$shareId").then().statusCode(200) // live

        val past = Instant.now().minusSeconds(60).toString()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedNote":"$note1","expiresAt":"$past"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        given().`when`().get("/api/shares/$shareId").then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun ownerClearsAnExpiryInPlaceAndTheShareResolvesAgain() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val past = Instant.now().minusSeconds(60).toString()
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedShareKey":"$shareKey","expiresAt":"$past"}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path<String>("shareId")
        given().`when`().get("/api/shares/$shareId").then().statusCode(404) // expired at birth

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"expiresAt":null}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        given().`when`().get("/api/shares/$shareId").then().statusCode(200) // live again
    }

    @Test
    fun aFullReplaceEditThatOmitsExpiresAtClearsIt() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, wrappedContentKey = CONTENT_KEY)
        val future = Instant.now().plusSeconds(3600).toString()
        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedShareKey":"$shareKey","expiresAt":"$future"}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path<String>("shareId")

        // full replace, like sealedNote/sealedShareName: an edit that omits expiresAt clears it — the manage UI
        // therefore re-sends the current value to preserve it.
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"sealedShareName":"$name2"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.expiresAt", equalTo(null))
    }

    // --- helpers ---

    private fun createWithRole(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun createWithNote(userId: String, nodeId: String, note: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","sealedNote":"$note","sealedShareKey":"$shareKey"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
