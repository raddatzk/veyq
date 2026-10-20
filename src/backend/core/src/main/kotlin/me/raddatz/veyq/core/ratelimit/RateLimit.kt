package me.raddatz.veyq.core.ratelimit

import jakarta.ws.rs.NameBinding

/**
 * The axis a rate limit counts on — the vocabulary of the declarative posture (ADR 0051).
 *
 * The split is not cosmetic: [IP] and [USER] are the only keys a **boundary filter** can know, because they come
 * from the request itself (the client address) or from the resolved `SecurityIdentity`. Every other key is a domain
 * value that only exists **behind** the handler's fine-grained authorization guard (the second layer in
 * `cc.security.authorization`), so those are *declaration markers* — the handler enforces them.
 *
 * An entry here is server-visible operational metadata by construction. **No key may ever be derived from
 * content** ([invariant] operator-blind) — that is a hard boundary on anything added to this enum.
 */
enum class RateLimitKey {
    /** The client's IP as `ClientAddress` resolves it (ADR 0094), counted before authentication. Only valid on a
     *  `@PermitAll` endpoint. */
    IP,

    /** The resolved principal (`userId`), counted after authorization. Only valid on an `@Authenticated` endpoint. */
    USER,

    /** `shareId` — a share's own budget, so one share's guests cannot exhaust another's. Handler-enforced. */
    SHARE,

    /** The message recipient (e.g. an invited address), so a single target cannot be bombed. Handler-enforced. */
    RECIPIENT,

    /** The acting resource owner, where the owner — not the caller's IP — is the abusable axis. Handler-enforced. */
    OWNER,

    /** The tenant as a whole, bounding aggregate spend across all its members. Handler-enforced. */
    TENANT,
    ;

    /** Whether a boundary filter enforces this key, or it is a marker the handler enforces (ADR 0051). */
    val boundaryEnforced: Boolean get() = this == IP || this == USER
}

/**
 * Declares that an endpoint (or every endpoint of a resource) is rate-limited on [key].
 *
 * The annotation carries the **key axis only, never the numbers** — limit values live in `VeyqConfig`
 * (`veyq.ratelimit.*`, `veyq.share.*.rate.*`) so a self-hosted instance can tune them ([invariant]
 * self-hostable), and so the limiter core can change without touching a single annotation.
 *
 * `@NameBinding` binds [IpRateLimitFilter] and [UserRateLimitFilter] to the annotated endpoints. **Name-binding
 * matches on the annotation TYPE, not its value** — so both filters are bound wherever *any* key is declared, and
 * each one reads the declared key at runtime ([RateLimitPosture]) and no-ops when it is not its own. A
 * handler-enforced key therefore passes through both filters untouched.
 *
 * A method-level annotation wins over a class-level one; a method-level [RateLimitExempt] overrides a class-level
 * `@RateLimit`.
 */
@NameBinding
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(val key: RateLimitKey)
