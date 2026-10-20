package me.raddatz.veyq.core.ratelimit

import java.lang.reflect.Method

/**
 * Resolves an endpoint's declared rate-limit posture (ADR 0051) from its annotations.
 *
 * One resolver, two callers with very different jobs: the boundary filters ask *"is this mine to enforce?"* per
 * request, and the posture sweep asks *"did anyone declare anything at all?"* per endpoint. Both must agree on
 * precedence, which is why neither re-implements it — a filter that disagreed with the sweep would enforce a
 * posture the guard did not check, or the reverse.
 *
 * Precedence, method before class (mirroring how `@PermitAll` overrides a class-level `@Authenticated`):
 * a method-level [RateLimitExempt] or [RateLimit] wins outright; otherwise the class-level one applies.
 */
sealed interface RateLimitPosture {

    /** The endpoint declares a bound on [key]. Enforced by a filter for `IP`/`USER`, by the handler otherwise. */
    data class Limited(val key: RateLimitKey) : RateLimitPosture

    /** The endpoint declares that it is deliberately unbounded, and says why. */
    data class Exempt(val reason: String) : RateLimitPosture

    /** The endpoint says nothing. This is what the sweep exists to reject. */
    data object Undeclared : RateLimitPosture

    companion object {
        /**
         * The posture of [method] on [resourceClass]. [method] may be null for a class-only question (the filters
         * always have one; a caller enumerating a resource may not).
         */
        fun of(resourceClass: Class<*>, method: Method?): RateLimitPosture {
            method?.getAnnotation(RateLimitExempt::class.java)?.let { return Exempt(it.reason) }
            method?.getAnnotation(RateLimit::class.java)?.let { return Limited(it.key) }
            resourceClass.getAnnotation(RateLimitExempt::class.java)?.let { return Exempt(it.reason) }
            resourceClass.getAnnotation(RateLimit::class.java)?.let { return Limited(it.key) }
            return Undeclared
        }

        /** The declared key when it is [key]'s to enforce, else null — the filters' one-line dispatch. */
        fun enforcedKey(resourceClass: Class<*>, method: Method?, key: RateLimitKey): RateLimitKey? =
            (of(resourceClass, method) as? Limited)?.key?.takeIf { it == key }
    }
}
