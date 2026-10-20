package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * Projects the `tenant` table into the reverse proxy's routing description (ADR 0063), so that **"the tenant
 * exists"** and **"the tenant's domain resolves"** cannot drift apart. Without it a second tenant is created
 * successfully and is then unreachable until an operator hand-edits `infra/docker/docker-compose.prod.yml` — a
 * deployment-time action gating what the API documents as a runtime one (uc.admin.tenant-reachability,
 * whose triggers are uc.admin.create-tenant's and uc.admin.suspend-tenant's operations).
 *
 * **This class is the DB-facing half only.** *Which* domains route is decided here and is identical on every
 * deployment target; *how* a proxy is told about them is a [TenantIngressSink] — a Traefik file on
 * docker-compose, Kubernetes Gateway API objects on ADR 0064's chart. This file knows neither.
 *
 * **Full regenerate, never an incremental patch.** [regenerate] recomputes the whole routable set from the
 * table every time and hands the sink the complete answer, which is what makes the projection self-healing: a
 * wiped volume, a fresh Traefik container, a file some operator edited, an `HTTPRoute` somebody deleted by hand
 * all repair themselves on the next call — and one runs at [onStartup], before this instance serves traffic.
 * An incremental "add one router" would leave every one of those states permanently wrong, with nothing that
 * ever notices.
 *
 * Two things this is deliberately **not**. It is not a confidentiality boundary: a domain is operational
 * metadata (host routing needs it in cleartext anyway), so nothing here holds or derives key material and
 * operator-blindness is untouched. And it is not a startup requirement: with no sink configured it is a clean
 * no-op, which is the `%dev`/`%test` default and what keeps a self-hoster running the backend without a proxy
 * at all (self-hostable by default) — and when a sink *is* configured, its failures degrade rather than
 * propagate ([TenantIngressSink.apply] never throws).
 *
 * **The routing predicate is `status != SUSPENDED`, and that is load-bearing.** PENDING routes because a
 * pending tenant's owner must be able to *reach* its accept link at all; SUSPENDED does not, because ADR 0066
 * takes it offline. That predicate is also why acceptance (PENDING → ACTIVE, `TenantAcceptHandler`) is **not**
 * a trigger — the tenant was already routable. Narrow the predicate to `status == ACTIVE` and acceptance
 * becomes a sixth trigger site that nothing in this file would tell you about:
 * `scripts/validate_tenant_ingress_triggers.py` only reaches `TenantHandler`.
 */
@ApplicationScoped
class TenantIngressProjector(config: VeyqConfig, private val sink: TenantIngressSink) {

    private val bootstrapDomain: String? = config.tenant().ingress().bootstrapDomain().orElse(null)?.trim()?.lowercase()

    /** A fresh or wiped projection repairs itself here, before this instance answers its first request. */
    fun onStartup(@Observes event: StartupEvent) {
        regenerate()
    }

    /**
     * Recompute the complete routable domain set from the `tenant` table and hand it to the sink. Safe to call
     * from anywhere and at any rate: it reads in its own transaction, so a caller must invoke it **after** its
     * own has committed or the read cannot see what the caller just wrote.
     */
    fun regenerate() {
        // Before the read, not after: with no proxy to project into this is *nothing* — not a degraded write,
        // not a query whose result is discarded. Every `@QuarkusTest` in the suite boots through [onStartup]
        // under exactly that configuration.
        if (!sink.enabled) return
        val domains = QuarkusTransaction.requiringNew().call {
            TenantEntity.list("status not in ?1", TenantEntity.OFFLINE).map { it.domain }
        }
        val routable = domains
            .map { it.lowercase() }
            // Routed from configuration on both targets — by the Traefik sink's own bootstrap pair, by the chart's
            // HTTPRoute on Kubernetes. A row carrying it again would be a second router on one Host.
            .filter { it != bootstrapDomain }
            .filter { isRoutableHostname(it) }
            .distinct()
            .sorted()
        sink.apply(routable)
    }

    companion object {
        private val log: Logger = Logger.getLogger(TenantIngressProjector::class.java)

        /** The same shape `CreateTenantRequest.domain` enforces at the boundary: a lowercase dotted hostname. */
        private val HOSTNAME = Regex("^([a-z0-9](-?[a-z0-9])*\\.)+[a-z]{2,}$")

        /**
         * Re-validated here even though `CreateTenantRequest`/`ChangeDomainRequest` already reject anything else
         * at the boundary. This is the point where a stored string becomes **proxy configuration**: a row that
         * predates that validation, or arrives by any path that skips it, is otherwise handed to a sink that
         * will interpolate it — into a Traefik `Host(` matcher, where a backtick closes the rule, or into an
         * `HTTPRoute`/listener hostname and object name, where an illegal character makes the API server reject the
         * object and, on a reconcile whose failure boundary is the whole call, strands every *other* tenant's
         * routing too. Cheaper to drop the row than to trust every past and future writer of the column.
         *
         * **Here and not in each sink, even though the damage is per-sink.** The predicate is not a property of
         * any proxy — it is `tenant.domain`'s own shape, mirroring the API boundary, and both sinks matched it
         * identically. Put in the sinks it is one copy per implementation and, worse, an obligation on every
         * future one that nothing but a KDoc sentence enforces — a sink that forgot it would emit the backtick
         * silently. This class is the single funnel from the table to any proxy, so it is the one place where
         * applying it cannot be skipped. `internal` so `TenantIngressHostnameTest` can pin the character
         * classes without a container; `TenantIngressProjectorTest.aRowThatSkippedTheBoundaryIsNeverProjected`
         * drives the real threat through the table.
         */
        internal fun isRoutableHostname(domain: String): Boolean =
            HOSTNAME.matches(domain).also {
                if (!it) log.warnf("Skipping tenant domain %s in the ingress projection: not a routable hostname", domain)
            }
    }
}
