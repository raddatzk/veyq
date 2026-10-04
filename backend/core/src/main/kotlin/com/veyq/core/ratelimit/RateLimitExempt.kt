package com.veyq.core.ratelimit

/**
 * Declares that an endpoint (or every endpoint of a resource) is deliberately **not** rate-limited, and why.
 *
 * This is the other half of the mandatory posture (ADR 0051): before it existed, an endpoint with no limiter and an endpoint nobody had thought about looked identical in the
 * tree. [reason] is what makes them distinguishable, so the sweep rejects a blank one — an empty reason would
 * restore exactly the silence this annotation exists to remove.
 *
 * Not a `@NameBinding`: nothing binds to an exemption. It is read reflectively by [RateLimitPosture] (so a
 * method-level exemption can override a class-level `@RateLimit`) and by the posture sweep.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimitExempt(val reason: String)
