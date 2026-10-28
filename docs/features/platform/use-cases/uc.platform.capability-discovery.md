# UC: Discover what an instance can do (and refuse to break silently)

- **ID:** uc.platform.capability-discovery
- **Primary actor:** any client, **before and after login** — the discovery surface is pre-auth.
- **Goal:** A self-hosted instance and its version-drifting clients stay **honest** with each other: the client
  learns the instance's **version** and **active capability set**, hard-stops on an incompatible `major`, hides
  what the instance doesn't serve, and never breaks silently mid-flow.
- **Preconditions:** The instance is reachable. No identity, no session — this runs before login, and its answer
  is identical for every caller (so it leaks nothing about who is asking).
- **Postconditions (success):** The client holds the instance's `version` + active `capabilities` set for this
  session and gates its entry points on them. On an incompatible `major` the client is in a blocking state and
  has **not** attempted login.
- **Enabler:** [en.platform.capability-register](../enablers/en.platform.capability-register.md) (the register, its two levels and what "off" does).

## Main flow

1. On startup (before login), the client fetches `GET /api/setup` → `{ setupRequired, suites, instanceSalt,
   version: { major, build }, capabilities: [{ id }] }`.
2. The client compares `server.version.major` to the **`major` it was compiled against**. Equal → continue.
   Both are the platform's one major, the repository's `VERSION` when each was built
   ([ADR 0085](../../../decisions/0085-one-major-moved-on-a-yearly-train.md) decision 1), so the web client and the
   server it ships with always agree; the gate exists for a client built from another tree.
   (Mismatch → A1/A2. `build` never gates.)
3. The client keeps the `capabilities` set and exposes `hasCapability(id)`.
4. Each feature that is **optional per instance** gates its entry points on its capability: present → offered,
   absent → **hidden** (A3), never invoked.
5. After sign-in, on every entry into a space, the client reads the **tenant's effective set**
   (`GET /api/tenants/{tenantId}/capabilities`, every member): the instance level ANDed with what the tenant owner
   switched off ([uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md),
   [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 3).
   From then on `hasCapability` reads that set, a subset of the pre-auth one; when it cannot be read, the pre-auth set
   stands, and the server's gates refuse per tenant either way. Signing out forgets it.

## Alternate / exception flows

- **A1 — server `major` > client:** a **blocking** "update your app" screen, **before** login — never a partial,
  mysterious failure later.
- **A2 — server `major` < client:** a **blocking** "update this server" screen, same timing.
- **A3 — capability absent:** the feature is hidden and never invoked — graceful degradation, never a crash.
- **E1 — the client ignores A3 (stale or hostile):** the server **rejects** the endpoint with
  `422 capability_disabled`. The manifest is a UX hint; the server is the authority. An operator who disables a
  capability gets both halves: it is **omitted** from the manifest *and* enforced at the endpoint.

## Quality notes

- **A capability is not a permission.** It is instance-wide before sign-in and per tenant after it; it says what this
  *server* and this *tenant* offer, not
  what *you* may do. A feature is offered only when **(capability present) AND (policy permits here)** — e.g.
  `core.public-shares` present **and** the node is one the caller may share. Conflating the two
  is the modelling error this use-case exists to prevent ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) §6).
- **Operator-blind-safe.** The response is identical for every caller and carries no per-user state, so a
  pre-auth manifest leaks nothing — that is what lets it sit outside the auth boundary. The tenant's set is the same
  for every member of the tenant, and only members read it.
- **The catalog is a coarser projection over use-cases, not a 1:1 rename.** Each entry in
  `contracts/platform/capabilities.json` declares a `serves: [uc.…]` back-ref (catalog-only, never on the wire);
  `scripts/validate_capabilities.py` enforces that every `serves` resolves to a real use-case, so the catalog
  cannot drift from the graph. It is **append-only**: an id may be reserved before its first advertisement and is
  advertised active only once its use-case(s) are built.
- **Seeded with seven operator-policy capabilities**, all under `core` and arranged as a tree (ADR 0088), sharing being
  the worked end-to-end proof; each is advertised, soft-gated by `hasCapability` and refused with `422` at its
  create/enable path. The authoritative list is the catalog file, not this page. The Server Owner turns one off for
  the instance in admin-web or its CLI ([uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md)),
  and a tenant owner turns a `tenant`-level one off for their tenant in the web client's tenant settings
  ([uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md)).

## User impact

Whoever runs your server and whichever version you open it with, veyq tells you up front when they do not fit and
shows only what your server actually offers.

## Related

- Feature: [Platform](../_index.md)
- Decisions: [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the discovery +
  version-gating model, and why a capability is not a permission)
- Contract: `contracts/platform/capabilities.json` (the append-only catalog — the authoritative capability list)
- **Deferred** (see the feature index): the deprecation lifecycle (`state`, `sunsetAt`, `supersededBy`), per-user
  "new feature" notices/acks, audience filtering, folding `suites` into the unified manifest, and client device
  self-capabilities. (`directTransport` was **removed** rather than folded in — a byte-path store is now a hard
  requirement, cr-0105.)
- End-user view (docs site): [Sign in](../../../../site/src/content/docs/getting-started/sign-in.mdx)
- End-user view (docs site): [Help](../../../../site/src/content/docs/help/index.mdx)
