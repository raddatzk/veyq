package com.veyq.core.auth

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.credential.TokenCredential
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * The single front door for bearer-token authentication (cr-0012). Pulls the `Authorization: Bearer` token off
 * the request and hands it to the [SessionTokenIdentityProvider] via a [TokenAuthenticationRequest]; an absent or
 * blank token yields an anonymous (null) identity, so `@PermitAll` endpoints still pass and only annotation-gated
 * endpoints challenge. Resolution itself lives in the IdentityProvider — this only parses the header.
 *
 * Authentication runs lazily (`quarkus.http.auth.proactive=false`): it is invoked only when a security annotation
 * demands an identity, never on public endpoints. The 401/403 response body is produced centrally by the
 * security ExceptionMappers (see `AuthExceptionMappers`), not by [getChallenge].
 */
@ApplicationScoped
class BearerAuthenticationMechanism : HttpAuthenticationMechanism {

    override fun authenticate(context: RoutingContext, identityProviderManager: IdentityProviderManager): Uni<SecurityIdentity> {
        val token = context.request().getHeader("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Uni.createFrom().nullItem()
        return identityProviderManager.authenticate(TokenAuthenticationRequest(TokenCredential(token, "bearer")))
    }

    // Fallback only — when a security ExceptionMapper is present (it is), Quarkus uses that for the body instead.
    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(401, null, null))

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> =
        setOf(TokenAuthenticationRequest::class.java)
}
