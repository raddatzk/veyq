package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A link's usage is measured by **exactly one** [UploadAdmission], or the link is not admitted at all (fail closed,
 * ADR 0073 §4): with none, an application that lost its implementation would have its links count nothing and admit
 * everything; with two, CDI bean order would pick the measure. The bean set is pinned over the real registration;
 * the dispatch through [StorageQuota] with forged lists — each refusal beside the positive control that the one real
 * admission admits the same start into the same link under its limit.
 */
@QuarkusTest
class UploadAdmissionPartitionSliceTest {
    @Inject
    @All
    lateinit var admissions: MutableList<UploadAdmission>

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var usage: StorageUsage

    /** Measures [kind] and holds nothing — what a second or a careless application would answer. */
    private class Forged(private val kind: String) : UploadAdmission {
        override fun supports(nodeKind: String): Boolean = nodeKind == kind
        override fun linkBlobUsage(shareId: String): Long = 0
    }

    @Test
    fun theRegisteredAdmissionsPartitionTheNodeKinds() {
        for (kind in listOf(NodeEntity.FOLDER, MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)) {
            val claiming = admissions.filter { it.supports(kind) }.map { it.javaClass.simpleName }
            assertTrue(claiming.size <= 1, "node kind $kind is measured by more than one UploadAdmission: $claiming")
        }
        assertEquals(1, admissions.count { it.supports(MemoriesNodeKinds.MEMORY) }, "a Memory link is measured")
    }

    @Test
    fun aLinkIsAdmittedOnlyThroughExactlyOneAdmission() {
        val (tenantId, shareId) = link()
        assertTrue(fits(admissions, tenantId, shareId), "control: the one registered admission admits a link under its limit")
        assertFalse(fits((admissions + Forged(MemoriesNodeKinds.MEMORY)).toMutableList(), tenantId, shareId), "two measures: refused")
        assertFalse(fits(mutableListOf(), tenantId, shareId), "no measure: refused, never admitted")

        assertEquals(0L, linkUsage(admissions, shareId), "control: the registered set measures the empty link")
        assertNull(linkUsage((admissions + Forged(MemoriesNodeKinds.MEMORY)).toMutableList(), shareId))
        assertNull(linkUsage(mutableListOf(), shareId))
    }

    private fun quota(list: MutableList<UploadAdmission>) = StorageQuota(em, usage, list)

    private fun fits(list: MutableList<UploadAdmission>, tenantId: String, shareId: String): Boolean =
        QuarkusTransaction.requiringNew().call {
            quota(list).linkFits(TenantEntity.findById(tenantId)!!, shareId, listOf(TestBlob.SMALL.upload("d3JhcA==")))
        }

    private fun linkUsage(list: MutableList<UploadAdmission>, shareId: String): Long? =
        QuarkusTransaction.requiringNew().call { quota(list).linkUsage(shareId) }

    /** A READ_WRITE link on a fresh Memory, its tenant's default per-link limit far above one small blob. */
    private fun link(): Pair<String, String> {
        val (ownerId, scopeId, tenantId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x')
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = ownerId; this.scopeId = scopeId; nodeId = memory; role = "READ_WRITE"
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
            }.persist()
        }
        return tenantId to shareId
    }
}
