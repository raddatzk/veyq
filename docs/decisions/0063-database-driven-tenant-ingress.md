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

**Which domains route and how a proxy is told are two halves, split at `TenantIngressSink`.** The first is
`TenantIngressProjector` and is identical on every deployment target; the second is one implementation per
proxy, chosen by `veyq.tenant.ingress.sink` — named, never inferred from which settings happen to be
populated ([ADR 0067](0067-store-provisioning-from-a-declared-type.md) Decision 1 settled that shape once).
A sink **never throws**: a failed projection must not fail the tenant operation that triggered it, nor the
startup regenerate.

- **docker-compose sink — and it is the whole of compose's routing.** The backend writes a Traefik
  **file-provider** dynamic config to a volume shared with `traefik` (`--providers.file.directory=...
  --providers.file.watch=true`); Traefik hot-reloads it, no restart. **Traefik runs no other provider.** The
  Docker provider would need the Docker socket, and a `:ro` mount of it restricts nothing about the API, so
  the internet-facing proxy would hold root on the host. The file therefore carries everything compose
  routes, in one atomic write by one author:
  - the **services**: the app's public listener, the store and the admin surface, at upstreams the recipe
    passes as configuration. The recipe still owns the topology; the sink only renders it. No service ever
    points at the backend's admin listener ([ADR 0065](0065-admin-ui-cli-separate-service.md) § admin channel).
  - the **router pair** for every routable domain: an `app` router and a same-origin, prefix-preserved blobs
    router ([ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)). Both reference the `letsencrypt`
    cert resolver, so ACME issues each domain's certificate as its router appears.
  - the **admin router**, when the recipe configures an admin domain
    ([ADR 0065](0065-admin-ui-cli-separate-service.md)).
  - the **middlewares** those routers reference. They sit in the same file, so a router can never name a
    middleware Traefik has not loaded yet.
- **`VEYQ_DOMAIN` is routed from configuration, not from the table.** The sink renders its pair from
  `veyq.tenant.ingress.bootstrap-domain` on every regenerate, the startup one included, so it is present
  before any tenant row exists. A tenant row that carries the same domain is **not rendered a second time**,
  because two routers claiming one `Host` make Traefik drop both. On Kubernetes the chart's own `Ingress`
  routes the bootstrap domain, so that sink keeps leaving it out.
- **Presigning becomes per-request, not per-instance.** `S3BlobStore` signs against the **resolved tenant's
  own domain** (the request's `Host`), not a single `veyq.blob-store.public-endpoint` value — the config
  value remains the bootstrap-domain default, used only until a tenant row supplies its own.
- **Kubernetes sink** (detailed in [ADR 0064](0064-kubernetes-helm-deployment-target.md)) implements the same
  `TenantIngressSink` against the K8s Ingress API instead of a file, and full-regenerate costs it two things a
  file sink gets for free. **It must delete.** Overwriting a file makes *"the proxy matches the table"* true in
  one call; a reconciling sink has to diff and remove, or the argument above ("permanently wrong, with nothing
  that ever notices") lands as a route to a suspended tenant that outlives the tenant. **And it must know what
  is its.** A file sink cannot destroy a stranger's file by being wrong about which lines are its content; a
  reconcile-by-list sink can destroy a stranger's `Ingress` by being wrong about which objects are its — so
  every projected object carries `app.kubernetes.io/managed-by: veyq-tenant-ingress`, and that label is the
  **selector the diff is taken over**, not a check applied afterwards. An unlabelled object is never a
  candidate.

## Consequences

- **New coupling, deliberately narrow.** `app` and `traefik` now share a volume for routing config only —
  never secrets, never content.
  - **The shared volume's mount point must exist in the app image, owned by the runtime user.** This cost a
    stack that could not start, which is why it is stated here at all. Docker seeds a fresh named volume from the image
    directory it covers, ownership included; with no such directory the volume came up `root:root`, the app
    (uid 10001) could not write it, and `TenantIngressProjector.onStartup` failed the Quarkus startup event —
    so a **first-ever** `docker compose up` of the production stack restart-looped with the projector's
    `AccessDeniedException` and never served a request. It went unnoticed because this repository has no
    remote and that stack had never actually been deployed. Held now by
    `scripts/validate_app_volume_ownership.py`, which derives the paths from the compose file rather than
    from this sentence.
  - **The projection is not a startup requirement, and that holds for its failures too.** Owning the mount
    point above removes the *cause*; putting **every** filesystem call inside `writeAtomically`'s catch
    removes the *coupling* — which matters independently, because the guard that holds the mount point reads
    compose files and can say nothing about a Kubernetes sink, whose volume semantics are the chart's
    (ADR 0064). The catch is easy to get one line wrong: with `createDirectories` and `createTempFile` above
    it, an unwritable directory throws on exactly those two, so the only failure ever observed in practice was
    the one the catch did not cover. An instance that cannot project its routing logs loudly and still serves
    `/api/setup`. `TenantIngressUnwritablePathTest` holds both callers: the startup one by configuring the
    unwritable path *before* the application boots, so a regression cannot start the test class at all.
  - **An empty projection carries no `http:` section** — found by the live run that proved the two properties
    above. Traefik rejects a section that is present but empty, so `http:` with `routers: {}` is the one
    spelling it cannot parse: `"routers cannot be a standalone element"`, logged by the file provider on every
    reload. Measured against the pinned v3.6 by writing each shape into a
    running stack's volume — `http:` + `routers: {}`, `http: {}`, and `http:` with empty
    routers/services/middlewares are all rejected; a comments-only file and an empty file are accepted, and the
    provider loads a real router again immediately afterwards. It costs no routing (there are no dynamic
    routers to lose while the set is empty), but **every freshly deployed instance has zero tenants**, so every
    new operator met a proxy insisting their routing was broken while debugging their first deploy — the same
    first-deploy path as the defect above. The empty branch now writes only its self-describing header, which
    is what still separates "no tenants" from "the projection never ran". `TenantIngressEmptyProjectionTest`
    holds the shape; Traefik's half is measured in that test's own table, not re-derivable from code.
- **One author means one failure domain.** A projection that cannot be written now leaves the bootstrap
  domain unrouted as well, not only the tenants. Two things bound that. The one cause ever observed, an
  unowned mount point, is held by the guard above. And the admin surface's default mode is a localhost port
  that bypasses Traefik, so the operator keeps a way in to a stack whose public routing is down.
- **Rejected — a static routes file shipped with the recipe beside the projection.** Every layout was
  measured on the pinned v3.6, and none of them works:
  - a file bind-mounted into the `:ro` projection volume stops the container from starting, because runc
    cannot create the mount point: `read-only file system`;
  - the projection mounted as a **subdirectory** of a recipe directory loads at startup but never reloads,
    because the file provider's watch is not recursive. A tenant created afterwards would stay unrouted until
    a restart, which is the defect this ADR exists to remove. The same rename in the watched directory
    itself reloads at once, and that is the control;
  - the same bind into a **writable** volume runs, but it makes Traefik a second writer of the routing and
    leaves a root-owned empty stub in the app's volume.
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
- [ADR 0070](0070-runtime-hardening-on-both-targets.md) (what the proxy and the other containers may do,
  now that no container needs the Docker socket).
- [ADR 0066](0066-tenant-suspension.md) (the "deactivate" trigger this projector reacts to — a request-level
  lockout, of which routing withdrawal is only a secondary effect).
- [scopes-and-tenancy.md](../crosscutting/scopes-and-tenancy.md) (tenant carries a domain).
