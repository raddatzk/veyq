package me.raddatz.veyq.memories.node

import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY

/**
 * The whole placement matrix of the node tree — every child kind under every parent slot, for a create and for a
 * move — against the rule as core's `NodesHandler` held it before `NodeKindRules` moved the `MEMORY`/`CHAPTER` arms
 * into Memories (cr-platform-gradle-modules step 4). The oracle is that function itself ([before]), copied, so the
 * expectation is derived from the old code rather than restated from memory; the kinds are spelled as the wire spells
 * them, string literals, so this file compiles and was green on the tree before the move and is green after it. With
 * Memories' rules present the runtime must answer exactly what the single core function answered.
 *
 * Handler level, not HTTP: `NodesHandler.create` takes the kind as a string, and the fail-closed answer for a kind no
 * rule handles is the handler's own. (The contract's `NodeKind` is an open token now, so a well-formed unknown kind
 * reaches the handler over HTTP too — `NodeKindTokenCoreSliceTest` pins that door.)
 */
@QuarkusTest
class NodeKindMatrixSliceTest {

    @Inject
    lateinit var handler: NodesHandler

    private val kinds = listOf("FOLDER", "MEMORY", "CHAPTER", "NOT_A_KIND")

    /** The parent slots: the scope root, an existing node of each kind, and a parent that does not exist. */
    private val parents = listOf("ROOT", "FOLDER", "MEMORY", "CHAPTER", "MISSING")

    /**
     * `NodesHandler.parentAllows` as of `5145d695`, verbatim but for three spellings: the `NodeEntity` constants as
     * their values, `ParentSlot.Root` as `"ROOT"`, and `ParentSlot.Node(k)` as `k`:
     * ```
     * NodeEntity.FOLDER, NodeEntity.MEMORY ->
     *     parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
     * NodeEntity.CHAPTER -> parent is ParentSlot.Node && parent.kind == NodeEntity.MEMORY
     * else -> false
     * ```
     * `ParentSlot.Missing` never reached it: `create` and the move branch of `update` answered `illegal_structure`
     * for a missing parent first, and so does [before].
     */
    private fun before(kind: String, parent: String): Boolean = parent != "MISSING" && when (kind) {
        "FOLDER", "MEMORY" ->
            parent == "ROOT" || (parent != "ROOT" && parent == "FOLDER")
        "CHAPTER" -> parent != "ROOT" && parent == "MEMORY"
        else -> false
    }

    private data class Tree(val userId: String, val scopeId: String, val slots: Map<String, String>)

    /** A scope with one node of each kind at its root — seeded, not created, so no rule decides where they sit. */
    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val slots = mapOf(
            "ROOT" to scopeId,
            "FOLDER" to seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY),
            "MEMORY" to seedNode(scopeId, "MEMORY", wrappedContentKey = "aw=="),
            "CHAPTER" to seedNode(scopeId, "CHAPTER", wrappedContentKey = "aw=="),
            "MISSING" to UUID.randomUUID().toString(),
        )
        return Tree(userId, scopeId, slots)
    }

    @Test
    fun createFollowsTheMatrix() {
        val t = tree()
        val seen = mutableListOf<String>()
        for (kind in kinds) for (parent in parents) {
            val outcome = handler.create(
                t.userId, t.scopeId, UUID.randomUUID().toString(), t.slots.getValue(parent), kind,
                "bg==", "c2x1Zy0${UUID.randomUUID()}".take(60), 0, 0, CONTENT_KEY, null,
            )
            val landed = when (outcome) {
                is CreateNodeOutcome.Created -> true
                CreateNodeOutcome.IllegalStructure -> false
                else -> error("create $kind under $parent: unexpected $outcome")
            }
            seen += "$kind under $parent: ${if (landed) "created" else "illegal_structure"}"
        }
        assertEquals(expected("created"), seen)
    }

    @Test
    fun moveFollowsTheMatrix() {
        val seen = mutableListOf<String>()
        for (kind in kinds) for (parent in parents) {
            val t = tree() // a fresh tree per move, so no earlier move changes a later cell's parent or slug
            val node = seedNode(t.scopeId, kind, wrappedContentKey = CONTENT_KEY)
            val outcome = handler.update(t.userId, t.scopeId, node, null, null, t.slots.getValue(parent), null, null, null)
            val moved = when (outcome) {
                UpdateNodeOutcome.Updated -> true
                UpdateNodeOutcome.IllegalStructure -> false
                else -> error("move $kind under $parent: unexpected $outcome")
            }
            seen += "$kind under $parent: ${if (moved) "moved" else "illegal_structure"}"
        }
        assertEquals(expected("moved"), seen)
    }

    /** The oracle's answer for every cell; at least one cell of each answer, or the matrix proves nothing. */
    private fun expected(ok: String): List<String> {
        val cells = kinds.flatMap { kind -> parents.map { parent -> "$kind under $parent: ${if (before(kind, parent)) ok else "illegal_structure"}" } }
        check(cells.count { it.endsWith(ok) } == 5) { "the oracle allows five placements (FOLDER, MEMORY × ROOT, FOLDER; CHAPTER × MEMORY): $cells" }
        return cells
    }
}
