package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.ScopeAccessWorld
import com.veyq.app.shared.membership.ScopeAccessWorld.Companion.B64
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Re-activating a share publishes its link again, so it passes the check creating one does: the instance
 * capability for the share's role (uc.sharing.share-shared-scope A2, ADR 0032). With `sharing.public-links` off, a
 * disabled VIEWER link stays off (`422 capability_disabled`); the control is an UPLOADER link in the same group,
 * whose `sharing.uploader-links` is still on — so the 422 is the role's capability and not the call failing.
 */
@QuarkusTest
@TestProfile(ReactivateShareCapabilitySliceTest.PublicLinksDisabled::class)
class ReactivateShareCapabilitySliceTest {

    class PublicLinksDisabled : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("veyq.capabilities.disabled" to "sharing.public-links")
    }

    @Inject lateinit var sessions: SessionService

    @Test
    fun reactivatingALinkWhoseCapabilityIsOffIs422() {
        val w = ScopeAccessWorld(sessions, shareRole = ShareEntity.UPLOADER).build()
        val viewer = disabledShare(w.live, ShareEntity.VIEWER)
        val uploader = disabledShare(w.live, ShareEntity.UPLOADER)
        val auth = w.token(w.live.ownerId)

        given().header("Authorization", auth).`when`().post("/api/shares/$viewer/reactivate")
            .then().statusCode(422).body("code", equalTo("capability_disabled"))
        given().header("Authorization", auth).`when`().post("/api/shares/$uploader/reactivate").then().statusCode(204)
    }

    private fun disabledShare(s: ScopeAccessWorld.Space, role: String): String {
        val id = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x')
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                this.id = id; ownerAccount = s.ownerId; scopeId = s.scopeId; nodeId = s.nodeId; this.role = role
                wrap = B64; sealedName = B64; enabled = false; createdAt = Instant.now()
            }.persist()
        }
        return id
    }
}
