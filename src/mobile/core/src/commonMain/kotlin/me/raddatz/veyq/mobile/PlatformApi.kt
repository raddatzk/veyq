package me.raddatz.veyq.mobile

/**
 * A member the platform opens to its applications and the composition only (ADR 0155 decision 7.1): `internal` before
 * the core split into a platform module and one module per application, and still none of the clients' business. Only
 * the platform module itself, `:apps:*`, `:composition` and the shared test support opt in (each build file's `optIn`);
 * `scripts/validate_mobile_module_graph.py` refuses the opt-in anywhere else. Each such member is hidden from
 * Objective-C too, so Swift sees what it saw while it was `internal`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "The platform's API for its applications and the composition only (PlatformApi); a client never opts in.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class PlatformApi
