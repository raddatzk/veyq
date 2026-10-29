package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A drop-box guest's identity folder takes its slug token like any create (inbox #792, `IdentityFolders`): a token another
 * child of the drop box already holds is `409 slug_taken` with nothing stored — before the fix the insert died on
 * `ux_node_parent_slug` at the flush and its replay, asking only for this identity's folder, re-threw it: a `500`. Only a
 * crafted request meets it (each identity keys its tokens under its own `K_up`), e.g. one guest holding two identities.
 */
@QuarkusTest
class DropFolderSlugSliceTest {

    @Test
    fun aSecondIdentitysFolderWithATakenSlugTokenIsSlugTaken() {
        val (userId, scopeId, _) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val first = GuestTreeShare.seed(userId, scopeId, dropBox, ShareEntity.WRITE_ONLY)
        val second = GuestTreeShare.seed(userId, scopeId, dropBox, ShareEntity.WRITE_ONLY)
        val slug = GuestTreeShare.slug()

        val made = ensure(first, slug)
        assertEquals(200, made.statusCode())
        assertEquals(true, made.path<Boolean>("created"))

        val refused = ensure(second, slug)
        assertEquals(409, refused.statusCode(), refused.body().asString())
        assertEquals("slug_taken", refused.path<String>("code"))
        assertEquals(1L, tx { NodeEntity.count("parentId", dropBox) }, "nothing stored for the second identity")

        // control: the identity that holds the token still gets its own folder back, and a fresh token lands
        val again = ensure(first, slug)
        assertEquals(200, again.statusCode())
        assertEquals(made.path<String>("nodeId"), again.path<String>("nodeId"))
        assertEquals(200, ensure(second, GuestTreeShare.slug()).statusCode())
        assertEquals(2L, tx { NodeEntity.count("parentId", dropBox) })
    }

    private fun ensure(link: GuestTreeShare.Link, slug: String): ExtractableResponse<Response> =
        given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"$slug","epoch":0,"wrappedContentKey":"d3JhcA=="}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then().extract()

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
