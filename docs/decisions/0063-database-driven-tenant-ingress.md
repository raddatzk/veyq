# ADR 0063 — Tenant↔domain routing is generated from the database, not hand-edited

- Status: Accepted
- Date: 2026-09-07

## Context

A **tenant** carries a domain, and host-based routing resolves every request's tenant context from it
([scopes-and-tenancy.md](../crosscutting/scopes-and-tenancy.md) — "One server hosts many tenants, each with
its own domain"). Tenant creation is a **runtime** Server-Owner action (`POST /api/tenants`,
[tenants.openapi.yaml](../../contracts/api/tenants.openapi.yaml)) — but the reverse proxy that actually
routes a domain to the `app`/`garage` containers does not know about it. Today
`infra/docker-compose.prod.yml` carries exactly one static Traefik `Host(...)` label per service, driven by
the single `${VEYQ_DOMAIN}` value in `infra/.env`. A second tenant with its own domain is therefore not
actually usable end-to-end: creating it via the API succeeds, but no router forwards its traffic until an
operator hand-edits the compose file and restarts the stack — a deployment-time action gating what is
documented as a runtime one.

Forces:

- **The DB is already the single source of truth for tenant↔domain.** A second, hand-maintained place (the
  compose file) holding the same mapping can only drift from it — exactly the duplication
  [`docs/ai/README.md`](../ai/README.md) rule 2 warns against ("point at one canonical source; never restate
  it").
- **`VEYQ_DOMAIN` cannot become purely runtime.** Before any tenant row exists, an operator still needs a
  reachable domain to open the browser and create the *first* tenant at all — the config-secret KEK /
  `/api/setup` bootstrap ([ADR 0050](0050-operator-config-secret-encryption.md),
  [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)) already depends on the stack being
  reachable before the DB holds product data. `VEYQ_DOMAIN` stays the deployment-time **bootstrap** domain;
  what changes is that it is no longer the *only* domain a server can ever answer to.
- **Garage/S3 presigning is domain-coupled.** [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)
  chose same-origin, path-routed blob access specifically to avoid CORS, and signs presigned URLs against a
  single instance-wide `veyq.blob-store.public-endpoint`. A second tenant domain that is not `VEYQ_DOMAIN`
  breaks that silently: a browser on `tenant-b.example.com` would receive URLs signed for
  `https://veyq.example.org` — a different origin, so the same-origin trick stops applying to every tenant
  but the first.
- **This has to generalize to Kubernetes.** A Helm-based deployment
  ([ADR 0064](0064-kubernetes-helm-deployment-target.md)) needs the identical tenant↔domain mapping,
  projected as Ingress objects instead of a Traefik file — the same principle, a different sink.

## Decision

**The backend owns an ingress projector**: on tenant create / domain change / deactivate, it regenerates a
**complete** router description from the `tenant` table — never an incremental patch, so the projection is
always fully re-derivable and self-healing (a wiped file, a fresh Traefik container, a corrupted volume all
repair themselves from the next regenerate, which also runs once at backend startup before serving traffic
normally).

- **docker-compose sink.** The backend writes a Traefik **file-provider** dynamic config to a volume shared
  with `traefik` (`--providers.file.directory=... --providers.file.watch=true`); Traefik hot-reloads it, no
  restart. For every tenant domain **other than** `VEYQ_DOMAIN`, it emits the same router pair the static
  labels already define for the bootstrap domain — an `app` router and a same-origin, prefix-preserved
  `garage` blobs router ([ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)) — both referencing
  the existing `letsencrypt` cert resolver, so ACME issues a per-domain certificate automatically as each
  router appears.
- **`VEYQ_DOMAIN` is unchanged.** It stays the static, always-present bootstrap router in
  `infra/docker-compose.prod.yml`, which is what lets an operator reach `/api/setup` and create the first
  tenant before any DB-driven router exists. The projector is strictly additive — it only ever adds routers
  for domains the static config doesn't already cover.
- **Presigning becomes per-request, not per-instance.** `S3BlobStore` signs against the **resolved tenant's
  own domain** (the request's `Host`), not a single `veyq.blob-store.public-endpoint` value — the config
  value remains the bootstrap-domain default, used only until a tenant row supplies its own.
- **Kubernetes sink** (detailed in [ADR 0064](0064-kubernetes-helm-deployment-target.md)) implements the
  same projector interface against the K8s Ingress API instead of a file.

## Consequences

- **New coupling, deliberately narrow.** `app` and `traefik` now share a volume for routing config only —
  never secrets, never content.
  - **The shared volume's mount point must exist in the app image, owned by the runtime user** — added
    2026-09-08 after this cost a stack that could not start. Docker seeds a fresh named volume from the image
    directory it covers, ownership included; with no such directory the volume came up `root:root`, the app
    (uid 10001) could not write it, and `TenantIngressProjector.onStartup` failed the Quarkus startup event —
    so a **first-ever** `docker compose up` of the production stack restart-looped with the projector's
    `AccessDeniedException` and never served a request. It went unnoticed because this repository has no
    remote and that stack had never actually been deployed. Held now by
    `scripts/validate_app_volume_ownership.py`, which derives the paths from the compose file rather than
    from this sentence.
  - **The projection is not a startup requirement, and that is now true of its failures too** — 2026-09-09.
    Fixing the ownership above removed the *cause*; this removes the *coupling*, because the guard that holds
    it reads compose files and can say nothing about a Kubernetes sink, whose volume semantics are the chart's
    (ADR 0064). `writeAtomically` had a catch that already promised this, but it opened one line too late:
    `createDirectories` and `createTempFile` sat above it, and an unwritable directory throws on exactly those
    two — so the one failure that had ever been observed was the one the catch did not cover. Every filesystem
    call is inside it now, and an instance that cannot project its routing logs loudly and still serves
    `/api/setup`. `TenantIngressUnwritablePathTest` holds both callers: the startup one by configuring the
    unwritable path *before* the application boots, so a regression cannot start the test class at all.
  - **An empty projection carries no `http:` section** — 2026-09-09, found by the live run that proved the two
    fixes above. Traefik rejects a section that is present but empty, so the `routers: {}` this ADR's first
    implementation emitted was the one spelling it cannot parse: `"routers cannot be a standalone element"`,
    logged by the file provider on every reload. Measured against the pinned v3.6 by writing each shape into a
    running stack's volume — `http:` + `routers: {}`, `http: {}`, and `http:` with empty
    routers/services/middlewares are all rejected; a comments-only file and an empty file are accepted, and the
    provider loads a real router again immediately afterwards. It costs no routing (there are no dynamic
    routers to lose while the set is empty), but **every freshly deployed instance has zero tenants**, so every
    new operator met a proxy insisting their routing was broken while debugging their first deploy — the same
    first-deploy path as the defect above. The empty branch now writes only its self-describing header, which
    is what still separates "no tenants" from "the projection never ran". `TenantIngressEmptyProjectionTest`
    holds the shape; Traefik's half is measured in that test's own table, not re-derivable from code.
- **ACME rate limits become an operator-visible concern** once domains can be added faster than by hand;
  `infra/.env.example`'s existing `VEYQ_ACME_CASERVER` staging-server advice is the same mitigation, now
  worth surfacing at tenant-creation time too.
- **Multi-domain-per-tenant and tenant self-service stay out of scope.** This ADR removes the *manual
  compose edit* for an **additional single-domain tenant**; it does not touch
  [uc.admin.create-tenant.md](../features/admin/use-cases/uc.admin.create-tenant.md)'s "one tenant = one
  domain" cut or the SaaS self-service concern (inbox #41). Tenant creation is still Server-Owner-only.
- **Rejected — wildcard-subdomain routing.** One static `HostRegexp` Traefik rule + a DNS-01 wildcard
  certificate needs zero dynamic config and zero new backend responsibility. Rejected because it forces
  every additional tenant onto a subdomain of the operator's own domain, permanently ruling out per-tenant
  custom domains — and because the DB-projector principle is the one that also serves Kubernetes; the
  wildcard shortcut would have to be thrown away, not extended, once Helm ships.
- **[ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §4 is updated in place** to state per-request
  signing.

## Related

- [uc.admin.tenant-reachability](../features/admin/use-cases/uc.admin.tenant-reachability.md) (the use-case
  this decision serves — it owns the flow, the failure modes and the acceptance criteria; the triggers live
  with the operations that cause them, in
  [create-tenant](../features/admin/use-cases/uc.admin.create-tenant.md) and
  [suspend-tenant](../features/admin/use-cases/uc.admin.suspend-tenant.md)).
- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) (blob transport, same-origin routing — amended
  by this decision).
- [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the Kubernetes sink).
- [ADR 0066](0066-tenant-suspension.md) (the "deactivate" trigger this projector reacts to — a request-level
  lockout, of which routing withdrawal is only a secondary effect).
- [scopes-and-tenancy.md](../crosscutting/scopes-and-tenancy.md) (tenant carries a domain).
