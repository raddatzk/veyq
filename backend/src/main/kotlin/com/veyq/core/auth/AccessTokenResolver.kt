package com.veyq.core.auth

/**
 * The one thing [SessionTokenIdentityProvider] needs from the session feature: who, if anyone, a bearer access
 * token belongs to. A kernel package must not depend on a feature slice (developer-styleguide § 1), so the provider
 * depends on this interface and `core.account.login.SessionService` implements it — the only bean of this type.
 */
interface AccessTokenResolver {
    /** The user behind [accessToken], or null if it is unknown, expired, or its session family is not active. */
    fun resolveUserId(accessToken: String): String?
}
