package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.membership.TestAdmission
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchesProfile
import me.raddatz.veyq.core.platform.capabilities.SwitchableCapabilities
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import me.raddatz.veyq.memories.testing.nodeId
import me.raddatz.veyq.memories.testing.MemoriesWorldContent

/**
 * Re-activating and re-issuing a share publish its link again, so each passes the check creating one does: the
 * instance capability for the share's role (uc.sharing.share-shared-scope A2, ADR 0032, ADR 0088). Each negative's
 * control is a link of another role in the same group, whose capability is still on — so the 422 is the role's
 * capability and not the call failing. The switch is the test seam [SwitchableCapabilities], never config.
 */
@QuarkusTest
@TestProfile(CapabilitySwitchesProfile::class)
class ReactivateShareCapabilitySliceTest {

    @Inject
    lateinit var capabilities: SwitchableCapabilities

    @Test
    fun reactivatingALinkWhoseCapabilityIsOffIs422() {
        val w = ScopeAccessWorld(listOf(MemoriesWorldContent())).build()
        val uploader = disabledShare(w.live, ShareEntity.WRITE_ONLY)
        val viewer = disabledShare(w.live, ShareEntity.READ)
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
        val contributor = disabledShare(w.live, ShareEntity.READ_WRITE)
        val viewer = disabledShare(w.live, ShareEntity.READ)
        val auth = bearer(w.live.ownerId)
        val epoch = TestAdmission.epochOf(w.live.scopeId)

        capabilities.with(CapabilityIds.PUBLIC_SHARES_CONTRIBUTE) {
            reissue(auth, contributor, epoch).then().statusCode(422).body("code", equalTo("capability_disabled"))
            val newId = TestIds.shareId()
            reissue(auth, viewer, epoch, newId).then().statusCode(201).body("shareId", equalTo(newId))
        }
    }

    private fun reissue(auth: String, shareId: String, epoch: Int, newId: String = TestIds.shareId()) =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"shareId":"$newId",${ShareWriteProof.field(newId)},"wrap":"$B64","sealedName":"$B64","sealedShareKey":"$B64","sealedShareKeyEpoch":$epoch}""")
            .`when`().post("/api/shares/$shareId/reissue")

    private fun disabledShare(s: ScopeAccessWorld.Space, role: String): String {
        val id = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x')
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                this.id = id; ownerAccount = s.ownerId; scopeId = s.scopeId; nodeId = s.nodeId; this.role = role
                // a WRITE_ONLY link carries the drop public key where every other role carries the wrap (ADR 0031 point 7)
                if (role == ShareEntity.WRITE_ONLY) sealedDropPublicKey = B64 else wrap = B64
                sealedName = B64; enabled = false; createdAt = Instant.now()
            }.persist()
        }
        return id
    }
}
