package me.raddatz.veyq.core.node

import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.node.rekey.NodeEpochGate

import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
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
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.moderation.block.ModerationBlocks

/**
 * Core's placement seam ([NodeKindRules], ADR 0079): the one rule that handles a node's kind decides where it may
 * live; **none or several → `illegal_structure`**, never CDI bean order and never a default. Core answers for `FOLDER`
 * only ([FolderNodeRules]); the kinds an application owns are placed by that application's rules. The bean set is
 * pinned over the real registration; the fail-closed answers are pinned through a `NodesHandler` built over a chosen
 * rule set — core alone, none at all, two claiming one kind — each negative beside a positive control on the same tree.
 * The Memories kinds appear here as the wire spells them, as data a forged or absent application is asked about.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that need Memories in the application at runtime are `NodeKindRulesPartitionMemoriesSliceTest` in `:apps:memories`.
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
    lateinit var capabilities: CapabilityRegistry

    @Inject
    lateinit var blocks: ModerationBlocks

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
    fun anEmptyRuleSetPlacesNothingNotEvenAFolder() {
        val (userId, scopeId) = seedMemberScope()
        val none = NodesHandler(store, access, mutableListOf(), capabilities, ApplicationSwitch(capabilities, mutableListOf()), blocks, NodeEpochGate())
        assertEquals(CreateNodeOutcome.IllegalStructure, create(none, userId, scopeId, null, NodeEntity.FOLDER))

        // control: the registered set places the same folder at the same root
        assertTrue(create(handler, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
    }

    @Test
    fun twoRulesClaimingOneKindRefuseItAndNeitherIsAsked() {
        val (userId, scopeId) = seedMemberScope()
        val a = Forged("MEMORY")
        val b = Forged("MEMORY")
        val doubled = NodesHandler(store, access, mutableListOf<NodeKindRules>(FolderNodeRules(), a, b), capabilities, ApplicationSwitch(capabilities, mutableListOf()), blocks, NodeEpochGate())
        assertEquals(CreateNodeOutcome.IllegalStructure, create(doubled, userId, scopeId, null, "MEMORY"))
        assertTrue(a.asked.isEmpty() && b.asked.isEmpty(), "an ambiguous kind is asked of nobody")
        assertTrue(create(doubled, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created,
            "the unambiguous kind beside it is unaffected")

        // control: either of them alone places the Memory
        val single = NodesHandler(store, access, mutableListOf<NodeKindRules>(FolderNodeRules(), a), capabilities, ApplicationSwitch(capabilities, mutableListOf()), blocks, NodeEpochGate())
        assertTrue(create(single, userId, scopeId, null, "MEMORY") is CreateNodeOutcome.Created)
        assertEquals(listOf("MEMORY"), a.asked)
    }

    @Test
    fun aForgedKindIsRefusedByTheRegisteredSet() {
        // `create` takes the kind as a string, and the contract's open token lets any well-formed one through: the
        // registered set is the only gate (the HTTP door is `NodeKindTokenCoreSliceTest`).
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
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
        post(userId, scopeId, """{"nodeId":"$outer","kind":"FOLDER","encryptedName":"b3V0ZXI=","slugToken":"b3V0ZXI=","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .then().statusCode(201).body("nodeId", equalTo(outer))
        val inner = UUID.randomUUID().toString()
        post(userId, scopeId, """{"parentId":"$outer","parentEpoch":0,"nodeId":"$inner","kind":"FOLDER","encryptedName":"aW5uZXI=","slugToken":"aW5uZXI=","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .then().statusCode(201).body("nodeId", equalTo(inner))

        post(userId, scopeId, """{"parentId":"${UUID.randomUUID()}","parentEpoch":0,"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bG9zdA==","slugToken":"bG9zdA==","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    private fun create(h: NodesHandler, userId: String, scopeId: String, parent: String?, kind: String): CreateNodeOutcome =
        h.create(userId, scopeId, UUID.randomUUID().toString(), parent, kind, "bg==", "c2x1Zy0${UUID.randomUUID()}".take(60), 0, 0, CONTENT_KEY, null, 0)

    private fun post(userId: String, scopeId: String, body: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
}
