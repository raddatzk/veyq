package com.veyq.app.shared.tenant

import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Projects the `tenant` table into the reverse proxy's routing description (ADR 0063), so that **"the tenant
 * exists"** and **"the tenant's domain resolves"** cannot drift apart. Without it a second tenant is created
 * successfully and is then unreachable until an operator hand-edits `infra/docker-compose.prod.yml` — a
 * deployment-time action gating what the API documents as a runtime one (uc.admin.tenant-reachability,
 * whose triggers are uc.admin.create-tenant's and uc.admin.suspend-tenant's operations).
 *
 * **Full regenerate, never an incremental patch.** [regenerate] rewrites the whole file from the table every
 * time, which is what makes the projection self-healing: a wiped volume, a fresh Traefik container or a file
 * some operator edited all repair themselves on the next call — and one runs at [onStartup], before this
 * instance serves traffic. An incremental "add one router" would leave every one of those states permanently
 * wrong, with nothing that ever notices.
 *
 * Two things this is deliberately **not**. It is not a confidentiality boundary: a domain is operational
 * metadata (host routing needs it in cleartext anyway), so nothing here holds or derives key material and
 * operator-blindness is untouched. And it is not a startup requirement: with no `dynamic-config-path`
 * configured it is a clean no-op, which is the `%dev`/`%test` default and what keeps a self-hoster running the
 * backend without Traefik at all (self-hostable by default).
 *
 * **The routing predicate is `status != SUSPENDED`, and that is load-bearing.** PENDING routes because a
 * pending tenant's owner must be able to *reach* its accept link at all; SUSPENDED does not, because ADR 0066
 * takes it offline. That predicate is also why acceptance (PENDING → ACTIVE, `TenantAcceptHandler`) is **not**
 * a trigger — the tenant was already routable. Narrow the predicate to `status == ACTIVE` and acceptance
 * becomes a sixth trigger site that nothing in this file would tell you about:
 * `scripts/validate_tenant_ingress_triggers.py` only reaches `TenantHandler`.
 *
 * ADR 0064's Kubernetes sink is a *second* implementation of the same projection against the Ingress API —
 * it reuses the DB-facing half below, not this file writer.
 */
@ApplicationScoped
class TenantIngressProjector(config: VeyqConfig) {

    private val dynamicConfigPath: String? = config.tenant().ingress().dynamicConfigPath().orElse(null)
    private val bootstrapDomain: String? = config.tenant().ingress().bootstrapDomain().orElse(null)?.lowercase()

    /** A fresh or wiped Traefik volume repairs itself here, before this instance answers its first request. */
    fun onStartup(@Observes event: StartupEvent) {
        regenerate()
    }

    /**
     * Rewrite the complete router set from the `tenant` table. Safe to call from anywhere and at any rate: it
     * reads in its own transaction, so a caller must invoke it **after** its own has committed or the read
     * cannot see what the caller just wrote.
     *
     * The write is temp-file-plus-atomic-rename because Traefik watches this directory and reloads on change:
     * a plain truncate-and-write is observable as a **half-written file**, which Traefik parses and rejects,
     * dropping every dynamic router until the next regenerate.
     */
    fun regenerate() {
        val path = dynamicConfigPath ?: return
        val domains = QuarkusTransaction.requiringNew().call {
            TenantEntity.list("status != ?1", TenantEntity.SUSPENDED).map { it.domain }
        }
        val routable = domains
            .map { it.lowercase() }
            .filter { it != bootstrapDomain } // already a static router in the compose file — never emitted twice
            .filter { isRoutableHostname(it) }
            .distinct()
            .sorted()
        writeAtomically(Path.of(path), render(routable))
    }

    /**
     * A Traefik file-provider dynamic configuration. Routers only: the **services** are the ones the compose
     * file's labels already define, referenced across providers as `<name>@docker`, so a tenant domain gets
     * byte-for-byte the same backends (and the blobs router's `passHostHeader`) as the bootstrap domain — which
     * is what keeps ADR 0013 §4's same-origin, prefix-preserved blob route true for every tenant, not just the
     * first. The blobs router carries the higher priority for the same reason its label does: its rule is the
     * longer one and must win over the catch-all app router.
     *
     * `internal` rather than private so `TenantIngressEmptyProjectionTest` can call it with an explicit domain
     * list. The alternative — driving the empty case through [regenerate] — is not available: the `%test` H2
     * instance is shared across test profiles and already carries a tenant row with a `veyq_membership`
     * foreign key, so "no tenants" is a state no test can reach by deleting. This is a pure function of its
     * argument, and the DB half it would otherwise exercise is `TenantIngressProjectorTest`'s subject.
     */
    internal fun render(domains: List<String>): String = buildString {
        append("# GENERATED by veyq's TenantIngressProjector from the `tenant` table (ADR 0063).\n")
        append("# Do not edit: every write is a full regenerate and will discard your changes.\n")
        if (domains.isEmpty()) {
            // Comments only — **no `http:` key at all**, which is the opposite of what this branch used to do
            // and the opposite of what reading the file format suggests. Measured against the pinned Traefik
            // v3.6, writing each shape into a running stack's volume and reading the provider's reaction:
            //
            //   http: + routers: {}                     REJECTED  "routers cannot be a standalone element"
            //   http: {}                                REJECTED  "http cannot be a standalone element"
            //   http: + empty routers/services/middlewares  REJECTED
            //   comments only, no http: key             accepted
            //   an empty file                           accepted
            //
            // Traefik rejects a section that is present but empty, so the old `routers: {}` — chosen to read as
            // "no dynamic routers" rather than as a truncated file — was the one spelling it cannot parse. It
            // cost nothing routing-wise (there are no dynamic routers to lose, and the provider picks the file
            // up again as soon as one appears), but **every freshly deployed instance has zero tenants**, so
            // every new operator met a routing error in the proxy log while debugging their first deploy.
            // The header stays: a present, self-describing file is what distinguishes "no tenants" from "the
            // projection never ran", and both this branch and the one below are full rewrites of it.
            append("# No tenant routes: this instance has no routable tenant beyond the bootstrap domain,\n")
            append("# whose routers are static labels in the compose file.\n")
            return@buildString
        }
        append("http:\n")
        append("  routers:\n")
        domains.forEach { domain ->
            // Named after the domain, not its position: a router name that shifted when some *other* tenant was
            // added would churn every router in Traefik's dashboard and logs on each regenerate. The hostname
            // shape validated above is exactly the character set a Traefik router name allows.
            val slug = "veyq-tenant-${domain.replace('.', '-')}"
            append("    $slug-app:\n")
            append("      rule: \"Host(`$domain`)\"\n")
            append("      priority: 1\n")
            append("      entryPoints: [websecure]\n")
            append("      service: veyq-app@docker\n")
            append("      tls:\n")
            append("        certResolver: letsencrypt\n")
            append("    $slug-blobs:\n")
            append("      rule: \"Host(`$domain`) && PathPrefix(`/veyq`)\"\n")
            append("      priority: 100\n")
            append("      entryPoints: [websecure]\n")
            append("      service: veyq-blobs@docker\n")
            append("      tls:\n")
            append("        certResolver: letsencrypt\n")
        }
    }

    /**
     * Re-validated here even though `CreateTenantRequest`/`ChangeDomainRequest` already reject anything else at
     * the boundary. This is the point where a stored string becomes **proxy configuration**: a row that predates
     * that validation, or arrives by any path that skips it, would otherwise be interpolated into a Traefik rule
     * — and a backtick in a domain closes the `Host(` matcher. Cheaper to drop the row than to trust every past
     * and future writer of the column.
     */
    private fun isRoutableHostname(domain: String): Boolean =
        HOSTNAME.matches(domain).also {
            if (!it) log.warnf("Skipping tenant domain %s in the ingress projection: not a routable hostname", domain)
        }

    /**
     * **Every filesystem call is inside the `try`, and that boundary is the point of this function.** It used
     * to start one line lower, with `createDirectories` and `createTempFile` above it — and an unwritable
     * directory throws on exactly those two, so the `AccessDeniedException` that a root-owned volume produced
     * went straight past the catch below. From [onStartup] that failed the Quarkus startup event and the
     * container restart-looped: an instance that could not project its routing would not serve `/api/setup`
     * either. The directory ownership that caused it is fixed in the `Dockerfile` and held by
     * `scripts/validate_app_volume_ownership.py`, but only for the compose deployment that guard can read —
     * ADR 0064's chart brings its own volume semantics, so degrading here is the half that travels.
     * `TenantIngressUnwritablePathTest` bites both callers.
     */
    private fun writeAtomically(target: Path, content: String) {
        var tmp: Path? = null
        try {
            val dir = target.toAbsolutePath().parent
            Files.createDirectories(dir)
            // Same directory as the target on purpose: an ATOMIC_MOVE is only guaranteed within one filesystem.
            tmp = Files.createTempFile(dir, ".${target.fileName}", ".tmp")
            Files.writeString(tmp, content)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Best-effort, and swallowed on purpose: the cleanup runs in the very state that got us here, so on
            // an unwritable directory `deleteIfExists` throws too — out of the catch block, past every caller,
            // and the degrade this function exists for would be undone by its own tidying.
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
            // A failed projection must not fail the tenant operation that triggered it: the row is committed and
            // correct, and the next regenerate (any later mutation, or the next startup) repairs the file. Loud,
            // because until then the new domain does not resolve.
            log.errorf(e, "Could not write the tenant ingress projection to %s — routing is stale until the next regenerate", target)
        }
    }

    private companion object {
        val log: Logger = Logger.getLogger(TenantIngressProjector::class.java)

        /** The same shape `CreateTenantRequest.domain` enforces at the boundary: a lowercase dotted hostname. */
        val HOSTNAME = Regex("^([a-z0-9](-?[a-z0-9])*\\.)+[a-z]{2,}$")
    }
}
