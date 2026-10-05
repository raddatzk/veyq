package me.raddatz.veyq.core.auth

import me.raddatz.veyq.core.account.login.AccessTokenEntity
import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * Phase-1 proof for cr-0012: a valid bearer token resolves to a [SecurityIdentity] carrying the userId principal
 * and the role drawn from `server_role`; a bad / expired token is rejected. This is the single token → caller
 * resolution the whole backend will route through once the slices are annotated (phases 2–3).
 */
@QuarkusTest
class SessionTokenIdentityProviderTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var identities: IdentityProviderManager

    @BeforeEach
    fun seed() {
        ensureUser(OWNER, "owner-idp@example.com", "SERVER_OWNER")
        ensureUser(MEMBER, "member-idp@example.com", "MEMBER")
    }

    @Test
    fun ownerTokenResolvesToOwnerIdentityWithRole() {
        val identity = authenticate(sessions.issue(OWNER).accessToken)
        assertEquals(OWNER, identity.principal.name)
        assertTrue(identity.roles.contains("SERVER_OWNER"))
    }

    @Test
    fun memberTokenResolvesWithoutOwnerRole() {
        val identity = authenticate(sessions.issue(MEMBER).accessToken)
        assertEquals(MEMBER, identity.principal.name)
        assertFalse(identity.roles.contains("SERVER_OWNER"))
    }

    @Test
    fun unknownTokenIsRejected() {
        assertFailsWith<AuthenticationFailedException> { authenticate("not-a-real-token") }
    }

    @Test
    fun expiredTokenIsRejected() {
        val token = sessions.issue(OWNER).accessToken
        QuarkusTransaction.requiringNew().run {
            AccessTokenEntity.find("tokenHash", Tokens.hash(token)).firstResult()!!
                .expiresAt = Instant.now().minusSeconds(60)
        }
        assertFailsWith<AuthenticationFailedException> { authenticate(token) }
    }

    @Test
    fun revokedFamilyTokenIsRejected() {
        val token = sessions.issue(OWNER).accessToken
        QuarkusTransaction.requiringNew().run {
            val at = AccessTokenEntity.find("tokenHash", Tokens.hash(token)).firstResult()!!
            SessionFamilyEntity.findById(at.familyId)!!.status = SessionFamilyEntity.REVOKED
        }
        assertFailsWith<AuthenticationFailedException> { authenticate(token) }
    }

    private fun authenticate(token: String): SecurityIdentity =
        identities.authenticate(TokenAuthenticationRequest(TokenCredential(token, "bearer")))
            .await().indefinitely()

    companion object {
        private const val OWNER = "idp-user-owner"
        private const val MEMBER = "idp-user-member"
    }
}
