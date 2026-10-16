package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import me.raddatz.veyq.memories.testing.nodeId
import me.raddatz.veyq.memories.testing.MemoriesWorldContent

/**
 * Re-activating and re-issuing a share publish its link again, and changing a link's role publishes one of the new
 * role, so each passes the check creating one does: the instance capability for the role it publishes
 * (uc.sharing.share-shared-scope A2, ADR 0032, ADR 0088). Each negative's
 * control is a link of another role in the same group, whose capability is still on — so the 422 is the role's
 * capability and not the call failing. The switch is a stored switch row ([CapabilitySwitchFixtures]), as the Server
 * Owner's Admin API leaves it.
 */
@QuarkusTest
class ReactivateShareCapabilitySliceTest {

    private val capabilities = CapabilitySwitchFixtures

    @Test
    fun reactivatingALinkWhoseCapabilityIsOffIs422() {
        val w = ScopeAccessWorld(listOf(MemoriesWorldContent())).build()
        val uploader = seededShare(w.live, ShareEntity.WRITE_ONLY)
        val viewer = seededShare(w.live, ShareEntity.READ)
        val auth = bearer(w.live.ownerId)

        capabilities.with(CapabilityIds.PUBLIC_SHARES_DROP_BOX) {
            given().header("Authorization", auth).`when`().post("/api/shares/$uploader/reactivate")
                .then().statusCode(422).body("code", equalTo("capability_disabled"))
            given().header("Authorization", auth).`when`().post("/api/shares/$viewer/reactivate").then().statusCode(204)
        }
    }

    @Test
    fun reissuingALinkWhoseCapabilityIsOffIs422() {
        val w = ScopeAccessWorld(listOf(MemoriesWorldContent())).build()
        val contributor = seededShare(w.live, ShareEntity.READ_WRITE)
        val viewer = seededShare(w.live, ShareEntity.READ)
        val auth = bearer(w.live.ownerId)
        val epoch = TestAdmission.epochOf(w.live.scopeId)

        capabilities.with(CapabilityIds.PUBLIC_SHARES_CONTRIBUTE) {
            reissue(auth, contributor, epoch).then().statusCode(422).body("code", equalTo("capability_disabled"))
            val newId = TestIds.shareId()
            reissue(auth, viewer, epoch, newId).then().statusCode(201).body("shareId", equalTo(newId))
        }
    }

    /**
     * A READ link changed into a READ_WRITE one in place (`updateShare`, cr-0119) would be a contribute link made past
     * the switch — and, since the guest gate refuses that role (ADR 0088 decision 5), a link that serves no guest. Only
     * an actual change into the switched-off role is refused: resending a link's current role, and narrowing to READ,
     * pass under the same switch; the refused change passes once the switch is back on, so the refusal stored nothing.
     */
    @Test
    fun changingALinkIntoAContributeLinkWhileContributeIsOffIs422() {
        val w = ScopeAccessWorld(listOf(MemoriesWorldContent())).build()
        val viewer = seededShare(w.live, ShareEntity.READ, enabled = true)
        val contributor = seededShare(w.live, ShareEntity.READ_WRITE, enabled = true)
        val narrowed = seededShare(w.live, ShareEntity.READ_WRITE, enabled = true)
        val auth = bearer(w.live.ownerId)

        capabilities.with(CapabilityIds.PUBLIC_SHARES_CONTRIBUTE) {
            updateRole(auth, viewer, ShareEntity.READ_WRITE).then().statusCode(422).body("code", equalTo("capability_disabled"))
            updateRole(auth, contributor, ShareEntity.READ_WRITE).then().statusCode(204) // control: no change of role
            updateRole(auth, narrowed, ShareEntity.READ).then().statusCode(204) // control: narrowing
        }
        assertEquals(ShareEntity.READ, roleOf(viewer), "the refused change stored nothing")
        assertEquals(ShareEntity.READ, roleOf(narrowed))
        updateRole(auth, viewer, ShareEntity.READ_WRITE).then().statusCode(204)
        assertEquals(ShareEntity.READ_WRITE, roleOf(viewer))
    }

    private fun updateRole(auth: String, shareId: String, role: String) =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"role":"$role","sealedNote":"$B64"}""")
            .`when`().patch("/api/shares/$shareId")

    private fun roleOf(shareId: String): String = QuarkusTransaction.requiringNew().call { ShareEntity.findById(shareId)!!.role }

    private fun reissue(auth: String, shareId: String, epoch: Int, newId: String = TestIds.shareId()) =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",${ShareWriteProof.field(newId)},"wrap":"$B64","sealedName":"$B64","sealedShareKey":"$B64","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares/$shareId/reissue")

    private fun seededShare(s: ScopeAccessWorld.Space, role: String, enabled: Boolean = false): String {
        val id = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x')
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                this.id = id; ownerAccount = s.ownerId; scopeId = s.scopeId; nodeId = s.nodeId; this.role = role
                // a WRITE_ONLY link carries the drop public key where every other role carries the wrap (ADR 0031 point 7)
                if (role == ShareEntity.WRITE_ONLY) sealedDropPublicKey = B64 else wrap = B64
                sealedName = B64; this.enabled = enabled; createdAt = Instant.now()
            }.persist()
        }
        return id
    }
}
