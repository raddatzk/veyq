package me.raddatz.veyq.core.node

import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.arc.All
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY

/**
 * Core's placement seam ([NodeKindRules], ADR 0079): the one rule that handles a node's kind decides where it may
 * live; **none or several → `illegal_structure`**, never CDI bean order and never a default. Core answers for `FOLDER`
 * only ([FolderNodeRules]); the kinds an application owns are placed by that application's rules. The bean set is
 * pinned over the real registration; the fail-closed answers are pinned through a `NodesHandler` built over a chosen
 * rule set — core alone, none at all, two claiming one kind — each negative beside a positive control on the same tree.
 * The Memories kinds appear here as the wire spells them, as data a forged or absent application is asked about.
 *
 * **Split from `NodeKindRulesPartitionSliceTest`** (cr-platform-gradle-modules step 8a): these methods need Memories in the application at runtime, so they live in
 * `:apps:memories`; the rest of the class is `NodeKindRulesPartitionSliceTest` in `:core`. The bodies moved verbatim.
 */
@QuarkusTest
class NodeKindRulesPartitionMemoriesSliceTest {

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
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, "MEMORY", wrappedContentKey = "aw==")

        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, null, "MEMORY"), "a Memory at the root")
        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, folder, "MEMORY"), "a Memory in a folder")
        assertEquals(CreateNodeOutcome.IllegalStructure, create(coreAlone, userId, scopeId, memory, "CHAPTER"), "a Chapter in a Memory")

        // control: the same handler places core's own kind, at the root and in a folder; the registered set the Memory
        assertTrue(create(coreAlone, userId, scopeId, null, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
        assertTrue(create(coreAlone, userId, scopeId, folder, NodeEntity.FOLDER) is CreateNodeOutcome.Created)
        assertTrue(create(handler, userId, scopeId, folder, "MEMORY") is CreateNodeOutcome.Created)
    }

    private fun create(h: NodesHandler, userId: String, scopeId: String, parent: String?, kind: String): CreateNodeOutcome =
        h.create(userId, scopeId, UUID.randomUUID().toString(), parent, kind, "bg==", "c2x1Zy0${UUID.randomUUID()}".take(60), 0, 0, CONTENT_KEY, null)
}
