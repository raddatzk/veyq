package com.veyq.app.shared.tenant

import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * Projects the `tenant` table into the reverse proxy's routing description (ADR 0063), so that **"the tenant
 * exists"** and **"the tenant's domain resolves"** cannot drift apart. Without it a second tenant is created
 * successfully and is then unreachable until an operator hand-edits `infra/docker-compose.prod.yml` — a
 * deployment-time action gating what the API documents as a runtime one (uc.admin.tenant-reachability,
 * whose triggers are uc.admin.create-tenant's and uc.admin.suspend-tenant's operations).
 *
 * **This class is the DB-facing half only.** *Which* domains route is decided here and is identical on every
 * deployment target; *how* a proxy is told about them is a [TenantIngressSink] — a Traefik file on
 * docker-compose, Kubernetes `Ingress` objects on ADR 0064's chart. This file knows neither.
 *
 * **Full regenerate, never an incremental patch.** [regenerate] recomputes the whole routable set from the
 * table every time and hands the sink the complete answer, which is what makes the projection self-healing: a
 * wiped volume, a fresh Traefik container, a file some operator edited, an `Ingress` somebody deleted by hand
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

    private val bootstrapDomain: String? = config.tenant().ingress().bootstrapDomain().orElse(null)?.lowercase()

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
            TenantEntity.list("status != ?1", TenantEntity.SUSPENDED).map { it.domain }
        }
        val routable = domains
            .map { it.lowercase() }
            .filter { it != bootstrapDomain } // already a static router in the compose file — never emitted twice
            .distinct()
            .sorted()
        sink.apply(routable)
    }
}
