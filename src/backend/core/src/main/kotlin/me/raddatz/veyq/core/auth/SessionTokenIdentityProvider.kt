package me.raddatz.veyq.core.auth

import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.IdentityProvider
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

/**
 * Resolves an opaque bearer access token into a [SecurityIdentity] — the one place token → caller resolution
 * happens (cr-0012). Wraps the existing authenticated-read guard [AccessTokenResolver.resolveUserId] (ADR 0004,
 * which also enforces the session family is ACTIVE) and reads the global role from `veyq_user.server_role`.
 *
 * Operator-blind: the identity carries only the userId principal, the management role, and the opaque token as a
 * credential — never a content key (those are the client-side scope-key grants). A token that is unknown,
 * expired, or whose user is gone is a single generic [AuthenticationFailedException] → 401.
 *
 * The lookup is blocking JPA, so it is offloaded off the IO thread via [AuthenticationRequestContext.runBlocking]
 * and run in its own transaction (the provider is outside any request-scoped JTA context).
 */
@ApplicationScoped
class SessionTokenIdentityProvider(private val sessions: AccessTokenResolver) :
    IdentityProvider<TokenAuthenticationRequest> {

    override fun getRequestType(): Class<TokenAuthenticationRequest> = TokenAuthenticationRequest::class.java

    override fun authenticate(
        request: TokenAuthenticationRequest,
        context: AuthenticationRequestContext,
    ): Uni<SecurityIdentity> =
        context.runBlocking { QuarkusTransaction.requiringNew().call { resolve(request.token.token) } }

    private fun resolve(token: String): SecurityIdentity {
        val userId = sessions.resolveUserId(token) ?: throw AuthenticationFailedException()
        val user = VeyqUser.findById(userId) ?: throw AuthenticationFailedException()
        val builder = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal(userId))
            .addCredential(TokenCredential(token, "bearer"))
        if (user.serverRole == SERVER_OWNER) builder.addRole(SERVER_OWNER)
        // The second role, read from its own table (ADR 0099 § 1): an accepted, unremoved appointment. A removal ends
        // it on the session's next request, since every request resolves its identity again.
        if (ModeratorEntity.isModerator(userId)) builder.addRole(ModeratorEntity.MODERATOR)
        return builder.build()
    }
}
