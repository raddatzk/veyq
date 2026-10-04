package com.veyq.core.node

import com.veyq.core.content.CreateNodeOutcome
import com.veyq.core.content.NodesHandler
import com.veyq.core.content.NodesStore
import com.veyq.core.membership.ScopeAccess
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.arc.All
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Core's placement seam ([NodeKindRules], ADR 0079): the one rule that handles a node's kind decides where it may
 * live; **none or several → `illegal_structure`**, never CDI bean order and never a default. Core answers for `FOLDER`
 * only ([FolderNodeRules]); the kinds an application owns are placed by that application's rules. The bean set is
 * pinned over the real registration; the fail-closed answers are pinned through a `NodesHandler` built over a chosen
 * rule set — core alone, none at all, two claiming one kind — each negative beside a positive control on the same tree.
 * The Memories kinds appear here as the wire spells them, as data a forged or absent application is asked about.
 */
@QuarkusTest
class NodeKindRulesPartitionSliceTest {
    @Inject
    @All
    lateinit var rules: MutableList<NodeKindRules>

    @Inject
    lateinit var store: NodesStore

    @Inject
    lateinit var access: ScopeAccess

    @Inject
    lateinit var handler: NodesHandler

    /** A rule for [kind] that allows it anywhere and records each placement it is asked about. */
    private class Forged(private val kind: String) : NodeKindRules {
        val asked = mutableListOf<String>()
        override fun handles(kind: String): Boolean = kind == this.kind
        override fun parentAllows(kind: String, parent: ParentSlot): Boolean {
            asked += kind
            return true
        }
    }

    @Test
    fun theRegisteredRulesPartitionTheKinds() {
        for (kind in listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER")) {
            val claiming = rules.filter { it.handles(kind) }.map { it.javaClass.simpleName }
            assertEquals(1, claiming.size, "node kind $kind must be handled by exactly one NodeKindRules: $claiming")
        }
        // `is`, not the class name: ArC hands out a client proxy, a subclass of the bean class
        assertTrue(rules.single { it.handles(NodeEntity.FOLDER) } is FolderNodeRules, "FOLDER is core's own")
        assertTrue(rules.none { it.handles("NOT_A_KIND") }, "no rule handles a kind nobody owns")
    }

    @Test
    fun withCoreAloneOnlyAFolderIsPlaced() {
        val (userId, scopeId) = seedMemberScope()
        val coreAlone = NodesHandler(store, access, mutableListOf(FolderNodeRules()))
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
        val memory = seedNode(scopeId, "MEMORY", wrappedContentKey = "aw==")

        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, null, "MEMORY"), "a Memory at the root")
        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, folder, "MEMORY"), "a Memory in a folder")
        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, memory, "CHAPTER"), "a Chapter in a Memory")

        // control: the same handler places core's own kind, at the root and in a folder; the registered set the Memory
        assertTrue(create(coreAlone, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
        assertTrue(create(coreAlone, userId, scopeId, folder, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
        assertTrue(create(handler, userId, scopeId, folder, "MEMORY") is CreateNodeOutcome.Created)
    }

    @Test
    fun anEmptyRuleSetPlacesNothingNotEvenAFolder() {
        val (userId, scopeId) = seedMemberScope()
        val none = NodesHandler(store, access, mutableListOf())
        assertEquals(CreateNodeOutcome.IllegalStructure, create(none, userId, scopeId, null, NodeEntity.FOLDER))

        // control: the registered set places the same folder at the same root
        assertTrue(create(handler, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
    }

    @Test
    fun twoRulesClaimingOneKindRefuseItAndNeitherIsAsked() {
        val (userId, scopeId) = seedMemberScope()
        val a = Forged("MEMORY")
        val b = Forged("MEMORY")
        val doubled = NodesHandler(store, access, mutableListOf<NodeKindRules>(FolderNodeRules(), a, b))
        assertEquals(CreateNodeOutcome.IllegalStructure, create(doubled, userId, scopeId, null, "MEMORY"))
        assertTrue(a.asked.isEmpty() && b.asked.isEmpty(), "an ambiguous kind is asked of nobody")
        assertTrue(create(doubled, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created,
            "the unambiguous kind beside it is unaffected")

        // control: either of them alone places the Memory
        val single = NodesHandler(store, access, mutableListOf<NodeKindRules>(FolderNodeRules(), a))
        assertTrue(create(single, userId, scopeId, null, "MEMORY") is CreateNodeOutcome.Created)
        assertEquals(listOf("MEMORY"), a.asked)
    }

    @Test
    fun aForgedKindIsRefusedByTheRegisteredSet() {
        // `create` takes the kind as a string; the contract's closed enum does not shield the handler.
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
        assertEquals(CreateNodeOutcome.IllegalStructure, create(handler, userId, scopeId, null, "NOT_A_KIND"))
        assertEquals(CreateNodeOutcome.IllegalStructure, create(handler, userId, scopeId, folder, "NOT_A_KIND"))

        // control: the same slots take a folder
        assertTrue(create(handler, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
        assertTrue(create(handler, userId, scopeId, folder, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
    }

    @Test
    fun overHttpAFolderLandsInAFolderAndNotUnderAMissingParent() {
        val (userId, scopeId) = seedMemberScope()
        val outer = UUID.randomUUID().toString()
        post(userId, scopeId, """{"nodeId":"$outer","kind":"FOLDER","encryptedName":"b3V0ZXI=","slugToken":"b3V0ZXI=","orderIndex":0}""")
            .then().statusCode(201).body("nodeId", equalTo(outer))
        val inner = UUID.randomUUID().toString()
        post(userId, scopeId, """{"parentId":"$outer","nodeId":"$inner","kind":"FOLDER","encryptedName":"aW5uZXI=","slugToken":"aW5uZXI=","orderIndex":0}""")
            .then().statusCode(201).body("nodeId", equalTo(inner))

        post(userId, scopeId, """{"parentId":"${UUID.randomUUID()}","nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bG9zdA==","slugToken":"bG9zdA==","orderIndex":0}""")
            .then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    private fun create(h: NodesHandler, userId: String, scopeId: String, parent: String?, kind: String): CreateNodeOutcome =
        h.create(userId, scopeId, UUID.randomUUID().toString(), parent, kind, "bg==", "c2x1Zy0${UUID.randomUUID()}".take(60), 0, 0, null, null)

    private fun post(userId: String, scopeId: String, body: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
}
