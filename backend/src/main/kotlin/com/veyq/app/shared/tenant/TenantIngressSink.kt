package com.veyq.app.shared.tenant

/**
 * Where [TenantIngressProjector]'s computed set of routable tenant domains actually becomes **routing**
 * (ADR 0063). The projector owns the DB-facing half — *which* domains route; a sink owns the proxy-facing
 * half — *how* a proxy is told about them. The split exists because ADR 0064 adds a second deployment target
 * whose proxy is the Kubernetes Ingress API rather than a file on a shared volume, and the DB-facing half is
 * identical for both: one predicate, one bootstrap exclusion, one sorted list.
 *
 * **[apply] never throws.** That is the contract, not a courtesy: a failed projection must not fail the tenant
 * operation that triggered it (ADR 0063 — the row is committed and correct, and the next regenerate repairs
 * the routing), and it must not fail the startup regenerate either, which once restart-looped the production
 * stack. Every implementation swallows its own failure mode and logs loudly; nothing here declares a checked
 * exception or returns a result, because there is no caller that could act on one.
 *
 * **Every call is a FULL regenerate, never an incremental patch** (ADR 0063). [apply] receives the complete
 * routable set and must make the proxy's state equal to it — which for a sink that reconciles rather than
 * overwrites means **deleting** routing for a domain that is no longer in the list, not only adding routing
 * for one that is. A file sink gets this for free by overwriting the whole file; an API-backed sink does not,
 * and `KubernetesIngressSinkTest`'s deletion bite is where that is decided.
 */
interface TenantIngressSink {

    /**
     * Whether this instance has a proxy to project into at all. `false` short-circuits [TenantIngressProjector]
     * **before it reads the table** — with no proxy configured the projection is not degraded and not deferred,
     * it is *nothing*, which is what lets a self-hoster run the backend with no reverse proxy in front of it
     * (and what keeps `@Observes StartupEvent` from putting a database read on every `%test` boot).
     */
    val enabled: Boolean

    /**
     * Make the proxy route exactly [domains] and nothing else this sink owns. Sorted, lowercased, distinct, and
     * already stripped of the bootstrap domain by the projector; **not** yet re-validated as a hostname, which
     * is each sink's own job at the point where the string becomes proxy configuration (see
     * [TenantIngressSink.HOSTNAME]).
     */
    fun apply(domains: List<String>)

    companion object {
        /**
         * The same shape `CreateTenantRequest.domain` enforces at the boundary: a lowercase dotted hostname.
         *
         * **Declared once, applied by each sink separately, and that is deliberate.** The *shape* is one fact
         * and lives here; the *reason to re-check it* is per-sink, because what a bad string does differs:
         * a backtick closes a Traefik `Host(` matcher, while an illegal character in an Ingress rule makes the
         * API server reject the object. Neither sink may assume the column was validated on the way in — a row
         * that predates the boundary's validation, or arrives by a path that skips it, reaches both.
         */
        val HOSTNAME = Regex("^([a-z0-9](-?[a-z0-9])*\\.)+[a-z]{2,}$")
    }
}

/**
 * The sink for an instance with **no proxy to project into** — the `%dev`/`%test` default, and the shape a
 * self-hoster running the backend bare is in. Not a degraded mode and not an error: there is nothing to write
 * to, so nothing is attempted and nothing is logged. `TenantIngressNoConfigTest` is this object's subject.
 */
object DisabledIngressSink : TenantIngressSink {
    override val enabled = false

    /** Unreachable through [TenantIngressProjector], which stops at [enabled]; total anyway, so it cannot lie. */
    override fun apply(domains: List<String>) = Unit
}
