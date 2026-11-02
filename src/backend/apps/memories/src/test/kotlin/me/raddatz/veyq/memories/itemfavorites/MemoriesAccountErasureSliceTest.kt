package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import me.raddatz.veyq.memories.testing.MediaBlobRows

/**
 * What of the Memories application an account erasure takes (ADR 0101 decisions 4-5, [MemoriesAccountEraser]): the
 * person's item favorites everywhere, and nothing they put into a shared Memory — its media keep their uploader.
 */
@QuarkusTest
class MemoriesAccountErasureSliceTest {

    @Inject
    lateinit var purge: AccountPurge

    @Test
    fun aSharedMemoryKeepsTheErasedMembersMediaAndLosesTheirFavorites() {
        val owner = seedUser()
        val u = seedUser()
        val tenant = seedTenant(owner = owner)
        val shared = UUID.randomUUID().toString()
        val elsewhere = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(u, tenant)
            ScopeEntity().apply { id = shared; tenantId = tenant; kind = ScopeEntity.GROUP }.persist()
            ScopeEntity().apply { id = elsewhere; tenantId = tenant; kind = ScopeEntity.GROUP }.persist()
            listOf(owner to MembershipEntity.OWNER, u to MembershipEntity.MEMBER).forEach { (who, role) ->
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); userId = who; tenantId = tenant; scopeId = shared; this.role = role
                    wrappedGrant = TestFixtures.GRANT; createdAt = Instant.now()
                }.persist()
            }
        }
        val memory = seedNode(shared, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; scopeId = shared; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; scopeId = shared; nodeId = memory; createdBy = "user:$u"; updatedAt = Instant.now()
            }.also { it.persist(); MediaBlobRows.holdFor(it, blobId) }
            // A favorite in a scope the person is no member of: only the account erasure reaches it, not a removal.
            favorite(u, elsewhere, memory)
            favorite(owner, elsewhere, memory)
        }

        val serverOwner = TestFixtures.bearer(seedUser(serverRole = "SERVER_OWNER"))
        givenAdminChannel().header("Authorization", serverOwner).contentType(ContentType.JSON)
            .body("""{"address":"u-$u@example.com"}""").`when`().post("/api/instance/accounts/erasures")
            .then().statusCode(200)
        purge.purge(u)

        QuarkusTransaction.requiringNew().run {
            assertNull(VeyqUser.findById(u))
            assertEquals("user:$u", MediaEntity.findById(mediaId)!!.createdBy, "the Memory's media keep their uploader")
            assertEquals(0L, ItemFavoritesEntity.count("userId", u), "the person's item favorites are gone")
            assertEquals(1L, ItemFavoritesEntity.count("userId", owner), "control: another member's stay")
        }
    }

    private fun favorite(userId: String, scopeId: String, nodeId: String) {
        ItemFavoritesEntity().apply {
            id = "$userId|$scopeId|$nodeId"; this.userId = userId; this.scopeId = scopeId; this.nodeId = nodeId; ciphertext = "Yw=="
        }.persist()
    }
}
