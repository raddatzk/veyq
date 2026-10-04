package com.veyq.core.account.login

import com.veyq.core.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.NewCookie

/**
 * The web refresh-token cookie (ADR 0004 transport; cr-0052, cr-0055). `httpOnly` (never readable by JS) +
 * `SameSite=Strict`, scoped to the refresh path so it rides only the rotation call. `Secure` is **config-driven**
 * (`veyq.session.cookie.secure`, on in prod; off in local dev over http, where a Secure cookie would never be
 * sent). The value is the same opaque refresh token the body carries for native clients — no key material — so
 * the cookie keeps the server operator-blind.
 *
 * Two lifetimes (ADR 0004 addendum), distinguished by **name** because a cookie can't report its own `Max-Age`
 * back to the server — so refresh re-sets the same-named cookie and never silently changes the lifetime:
 *   - [persistent] `veyq_refresh` with `Max-Age` = refresh TTL — survives a browser restart ("remember me").
 *   - [session] `veyq_refresh_session` with **no** `Max-Age` (a session cookie) — survives reloads, dropped on
 *     browser close (the web default).
 */
@ApplicationScoped
class SessionCookie(
    config: VeyqConfig,
) {
    private val secure: Boolean = config.session().cookie().secure()

    /** The refresh token read off a request + which lifetime cookie carried it (so rotation re-sets the same). */
    data class Read(val token: String, val persistent: Boolean)

    /** A persistent refresh cookie (survives a browser restart) carrying [token] — web "remember me". */
    fun persistent(token: String): NewCookie = build(NAME_PERSISTENT, token, REFRESH_TTL_SECONDS)

    /** A session refresh cookie (no Max-Age → dropped on browser close) carrying [token] — the web default. */
    fun session(token: String): NewCookie = build(NAME_SESSION, token, SESSION_MAX_AGE)

    /** The cookies to write for a chosen lifetime: the refresh cookie carrying [token] **and** a clearing
     *  cookie for the OTHER lifetime — so exactly one refresh cookie ever exists and a stale other-lifetime
     *  cookie can't shadow it on the next [read] (which prefers persistent). Every write site uses this, so it
     *  is impossible to set one cookie without clearing the other. */
    fun issue(token: String, persistent: Boolean): Array<NewCookie> =
        if (persistent) arrayOf(persistent(token), clearedSession()) else arrayOf(session(token), clearedPersistent())

    /** Read the refresh token from whichever cookie is present (persistent wins), or null when neither is. */
    fun read(headers: HttpHeaders): Read? {
        headers.cookies[NAME_PERSISTENT]?.value?.ifBlank { null }?.let { return Read(it, persistent = true) }
        headers.cookies[NAME_SESSION]?.value?.ifBlank { null }?.let { return Read(it, persistent = false) }
        return null
    }

    /** A clearing cookie (Max-Age 0) for the persistent name — set when issuing a SESSION cookie so a prior
     *  persistent cookie can't shadow it (read() prefers persistent). */
    fun clearedPersistent(): NewCookie = build(NAME_PERSISTENT, "", 0)

    /** A clearing cookie (Max-Age 0) for the session name — set when issuing a PERSISTENT cookie. */
    fun clearedSession(): NewCookie = build(NAME_SESSION, "", 0)

    /** Clearing cookies (Max-Age 0) for BOTH names — logout drops whichever lifetime the browser holds. */
    fun cleared(): Array<NewCookie> = arrayOf(clearedPersistent(), clearedSession())

    private fun build(name: String, value: String, maxAge: Int): NewCookie =
        NewCookie.Builder(name)
            .value(value)
            .path(PATH)
            .httpOnly(true)
            .secure(secure)
            .sameSite(NewCookie.SameSite.STRICT)
            .maxAge(maxAge)
            .build()

    private companion object {
        const val NAME_PERSISTENT = "veyq_refresh"
        const val NAME_SESSION = "veyq_refresh_session"
        const val PATH = "/api/token/refresh"
        // NewCookie's "unset" Max-Age (-1) emits no Max-Age attribute → a session cookie.
        const val SESSION_MAX_AGE = -1
        val REFRESH_TTL_SECONDS = (SessionService.REFRESH_TTL_DAYS * 24 * 60 * 60).toInt()
    }
}
