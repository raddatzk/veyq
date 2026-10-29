package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.identity.keychain.KeyChainStepEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.persistence.CoreStatementCount
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.GRANT
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A scope's member list is read with the same number of SQL statements whatever the number of members (inbox #537):
 * every member's account is read once for the list, never once per member. Measured before the fix at 1,017 statements
 * for 1,000 members (one `findById` per member; 1,012 on the profile's MariaDB). A group of one against a group of
 * sixty-one; half of the sixty carry a stored key chain (the members the old shape read one by one: a chain-less
 * member's account was already in the session from the key-chain read) and half do not, so both of the key-chain read's
 * paths are in the count. The count includes the request's own reads (the session, the access gate), which do not grow
 * with the group either; what the assertion holds is that nothing does.
 */
@QuarkusTest
class MemberListQueryCountSliceTest {

    @Test
    fun aGroupOfOneAndOfSixtyOneIsListedWithTheSameStatements() {
        val one = seedMemberScope(ScopeEntity.GROUP)
        val many = seedMemberScope(ScopeEntity.GROUP)
        addMembers(many.scopeId, 60)
        val list = { userId: String, scopeId: String, size: Int ->
            given().header("Authorization", bearer(userId))
                .`when`().get("/api/scopes/$scopeId/members")
                .then().statusCode(200).body("size()", equalTo(size))
        }
        list(one.userId, one.scopeId, 1) // warm: the session and the gate's first reads are not the list's
        assertEquals(
            CoreStatementCount.during { list(one.userId, one.scopeId, 1) },
            CoreStatementCount.during { list(many.userId, many.scopeId, 61) },
            "member list",
        )
    }

    /** [n] plain members of [scopeId], every second one with a stored key-chain step (as a member who secured the account). */
    private fun addMembers(scopeId: String, n: Int) {
        QuarkusTransaction.requiringNew().run {
            val tenantId = ScopeEntity.findById(scopeId)!!.tenantId
            repeat(n) { i ->
                val user = newUser().also { it.persist() }
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); userId = user.id; this.tenantId = tenantId; this.scopeId = scopeId
                    role = MembershipEntity.MEMBER; wrappedGrant = GRANT; grantedEpoch = 0; createdAt = Instant.now()
                }.persist()
                if (i % 2 == 0) {
                    KeyChainStepEntity().apply {
                        id = UUID.randomUUID().toString(); userId = user.id; idx = 0; identityPublicKey = user.identityPublicKey
                        secretKeyProofPublicKey = "cHJvb2Y="; head = "aGVhZA=="; createdAt = Instant.now()
                    }.persist()
                }
            }
        }
    }
}
