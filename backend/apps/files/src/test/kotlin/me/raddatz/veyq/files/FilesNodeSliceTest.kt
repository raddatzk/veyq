package me.raddatz.veyq.files

import me.raddatz.veyq.core.node.FolderNodeRules
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `FILE` in core's tree, through the real node API as a member, in the application this module's tests boot: core
 * plus Files, no Memories (cr-files-application F0). Files registers one seam implementation — [FilesNodeKindRules] —
 * and nothing else: a `FILE` is created at the root and in a folder, takes no child, is the root of no share (Files
 * declares no `ShareRoots`, so `illegal_scope` as for any kind nobody declares), and is renamed and moved between
 * folders by core's placement write, which works for any placed kind. Each refusal sits beside a positive control
 * through the same route.
 */
@QuarkusTest
class FilesNodeSliceTest {

    @Inject
    @All
    lateinit var rules: MutableList<NodeKindRules>

    @Test
    fun theRegisteredRulesPartitionCoreAndFilesKinds() {
        for (kind in listOf(NodeEntity.FOLDER, FilesNodeKinds.FILE)) {
            val claiming = rules.filter { it.handles(kind) }.map { it.javaClass.simpleName }
            assertEquals(1, claiming.size, "node kind $kind must be handled by exactly one NodeKindRules: $claiming")
        }
        // `is`, not the class name: ArC hands out a client proxy, a subclass of the bean class
        assertTrue(rules.single { it.handles(FilesNodeKinds.FILE) } is FilesNodeKindRules, "FILE is Files' own")
        assertTrue(rules.single { it.handles(NodeEntity.FOLDER) } is FolderNodeRules, "FOLDER is core's own")
        assertTrue(rules.none { it.handles("MEMORY") }, "Memories is not part of this application")
    }

    @Test
    fun aFileIsCreatedAtTheRootAndInAFolderAndTakesNoChild() {
        val (userId, scopeId) = seedMemberScope()
        val folder = create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201).extractId()
        val atRoot = create(userId, scopeId, null, FilesNodeKinds.FILE).statusCode(201).extractId()
        val inFolder = create(userId, scopeId, folder, FilesNodeKinds.FILE).statusCode(201).extractId()
        assertEquals(listOf(FilesNodeKinds.FILE, scopeId), row(atRoot).take(2))
        assertEquals(listOf(FilesNodeKinds.FILE, folder), row(inFolder).take(2))

        // a file has no children: neither a file nor a folder under it
        for (child in listOf(FilesNodeKinds.FILE, NodeEntity.FOLDER)) {
            create(userId, scopeId, inFolder, child).statusCode(422).body("code", equalTo("illegal_structure"))
        }
        // control: the same children land in the folder beside it
        for (child in listOf(FilesNodeKinds.FILE, NodeEntity.FOLDER)) {
            create(userId, scopeId, folder, child).statusCode(201)
        }
    }

    @Test
    fun aFileIsTheRootOfNoShare() {
        val (userId, scopeId) = seedMemberScope()
        val file = create(userId, scopeId, null, FilesNodeKinds.FILE).statusCode(201).extractId()
        val folder = create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201).extractId()

        share(userId, file).statusCode(422).body("code", equalTo("illegal_scope"))
        // control: core's folder is shared through the same route
        share(userId, folder).statusCode(201)
    }

    @Test
    fun aFileIsRenamedAndMovedBetweenFolders() {
        val (userId, scopeId) = seedMemberScope()
        val a = create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201).extractId()
        val b = create(userId, scopeId, null, NodeEntity.FOLDER).statusCode(201).extractId()
        val file = create(userId, scopeId, a, FilesNodeKinds.FILE).statusCode(201).extractId()
        val other = create(userId, scopeId, a, FilesNodeKinds.FILE).statusCode(201).extractId()

        // a rename is a placement write to the same parent
        place(userId, scopeId, file, to = a, name = "cmVuYW1lZA==", version = 0).statusCode(204)
        assertEquals(listOf(FilesNodeKinds.FILE, a, "cmVuYW1lZA==", 1L), row(file))
        // a move into another folder
        place(userId, scopeId, file, to = b, name = "bW92ZWQ=", version = 1).statusCode(204)
        assertEquals(listOf(FilesNodeKinds.FILE, b, "bW92ZWQ=", 2L), row(file))
        // ...and to the root
        place(userId, scopeId, file, to = null, name = "cm9vdA==", version = 2).statusCode(204)
        assertEquals(listOf(FilesNodeKinds.FILE, scopeId, "cm9vdA==", 3L), row(file))

        // never into a file, and the refused write changes nothing
        place(userId, scopeId, file, to = other, name = "aW5zaWRl", version = 3).statusCode(422)
            .body("code", equalTo("illegal_structure"))
        assertEquals(listOf(FilesNodeKinds.FILE, scopeId, "cm9vdA==", 3L), row(file))
    }

    private fun create(userId: String, scopeId: String, parent: String?, kind: String): ValidatableResponse {
        val parentField = parent?.let { "\"parentId\":\"$it\"," } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{$parentField"nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"bmFtZQ==","slugToken":"${slug()}","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun place(userId: String, scopeId: String, node: String, to: String?, name: String, version: Long): ValidatableResponse {
        val parentField = to?.let { "\"parentId\":\"$it\"," } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{$parentField"encryptedName":"$name","slugToken":"${slug()}","wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementVersion":$version}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$node").then()
    }

    private fun share(userId: String, nodeId: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun ValidatableResponse.extractId(): String = extract().path("nodeId")

    /** The node's kind, parent, sealed name and placement version, read in a transaction of its own. */
    private fun row(node: String): List<Any> = QuarkusTransaction.requiringNew().call {
        NodeEntity.findById(node)!!.let { listOf(it.kind, it.parentId, it.encryptedName, it.placementVersion) }
    }
}
