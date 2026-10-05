package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.All
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The registered `ShareGuestWrites` partition the node kinds: at most one claims each, so `SharingHandler.writesFor`
 * never has to choose — and if two ever did, it refuses (`share_not_contributable`) rather than take whichever CDI
 * listed first. This pins the contract over the real bean set, so a second application claiming MEMORY is red here
 * before any guest write meets it.
 */
@QuarkusTest
class ShareGuestWritesPartitionSliceTest {
    @Inject
    @All
    lateinit var guestWrites: MutableList<ShareGuestWrites>

    @Test
    fun atMostOneImplementationSupportsEachNodeKind() {
        assertTrue(guestWrites.isNotEmpty(), "the media application registers guest writes")
        for (kind in listOf(NodeEntity.FOLDER, MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)) {
            val claiming = guestWrites.filter { it.supports(kind) }.map { it.javaClass.simpleName }
            assertTrue(claiming.size <= 1, "node kind $kind is claimed by more than one ShareGuestWrites: $claiming")
        }
        assertEquals(1, guestWrites.count { it.supports(MemoriesNodeKinds.MEMORY) }, "a Memory has exactly one guest writer")
    }
}
