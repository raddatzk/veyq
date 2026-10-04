package com.veyq.core.identity

/**
 * Global server-management roles, stored in `veyq_user.server_role` and surfaced as `SecurityIdentity` roles by
 * the [com.veyq.core.auth.SessionTokenIdentityProvider] (cr-0012). A management role gates *management*
 * actions only; it never confers content readability — that stays the client-side scope-key grant.
 */
object ServerRoles {
    const val SERVER_OWNER = "SERVER_OWNER"

    /** An ordinary global user (no server-management powers). The default for invite-born accounts. */
    const val MEMBER = "MEMBER"
}
