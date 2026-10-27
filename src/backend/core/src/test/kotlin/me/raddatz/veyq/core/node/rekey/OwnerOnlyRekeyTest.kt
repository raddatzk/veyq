package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [OwnerOnlyRekey]'s rule, boot-free (ADR 0111 decision 11, last bullet; ADR 0080 Decision 11): owner-only where members
 * place nothing — a drop box's kind, a guest's identity folder and below one — and never because of a drop key alone.
 * Each negative has its positive on the same predicate. The wired rule (which kinds are drop boxes) is
 * `RekeyCoreSliceTest`'s, `DropBoxRekeySliceTest`'s and `MediaNodeRekeySliceTest`'s.
 */
class OwnerOnlyRekeyTest {

    private val rows = mutableMapOf<String, NodeEntity>()

    private fun node(kind: String, parent: NodeEntity? = null, dropKey: Boolean = false, identity: Boolean = false): NodeEntity =
        NodeEntity().apply {
            id = UUID.randomUUID().toString(); scopeId = "scope"; parentId = parent?.id ?: "root"; this.kind = kind
            sealedDropPrivateKey = if (dropKey) "ZHJvcA==" else null
            shareIdentityId = if (identity) UUID.randomUUID().toString() else null
        }.also { rows[it.id] = it }

    private fun ownerOnly(n: NodeEntity) = OwnerOnlyRekey.of(n, { it == BOX }, rows::get)

    @Test
    fun aDropKeyedNodeOfNoDropBoxKindIsAnyMembers() {
        val memory = node(MEMORY, dropKey = true)
        assertFalse(ownerOnly(memory), "a Memory carrying a drop key for its uploader link")
        assertFalse(ownerOnly(node(CHAPTER, memory, dropKey = true)), "a chapter with its own drop key")
        assertTrue(ownerOnly(node(BOX, dropKey = true)), "control: a drop box's kind with the same drop key")
    }

    @Test
    fun aDropBoxIsAnOwnersWithOrWithoutItsKey() {
        assertTrue(ownerOnly(node(BOX)))
        assertFalse(ownerOnly(node(MEMORY)), "control: the same row of another kind")
    }

    @Test
    fun anIdentityFolderAndWhatLiesBelowAreAnOwners() {
        val box = node(BOX, dropKey = true)
        val folder = node(FOLDER, box, identity = true)
        val inner = node(FOLDER, folder)
        assertTrue(ownerOnly(folder))
        assertTrue(ownerOnly(node(FILE, inner)), "two levels below")
        assertFalse(ownerOnly(node(FOLDER, node(FOLDER))), "control: a folder below a plain folder")
    }

    private companion object {
        const val BOX = "BOX"
        const val MEMORY = "MEMORY"
        const val CHAPTER = "CHAPTER"
        const val FOLDER = "FOLDER"
        const val FILE = "FILE"
    }
}
