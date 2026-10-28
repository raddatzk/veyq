# ADR 0032 — Capability discovery & version gating (lean core)

- Status: Accepted
- Built: yes
- Date: 2026-07-08
- Model: [Applications](../apps/README.md)
- Replaced in part: § 2, § 4, § 5 by ADR 0088 (built)

## Context

veyq is **self-hostable by default**: an operator runs their own backend, and users reach it with clients
(the web client served by that backend) whose **versions drift** from the server's. Two problems follow
that a REST API alone doesn't solve:

1. **Compatibility.** A client built against a newer/older server can call an endpoint that no longer exists, or
   miss one it relies on — a silent break. We want an **honest, explicit** gate: *"this app and this server are
   incompatible — update one"* — not a mystery 404 mid-flow.
2. **Feature discovery + operator control.** Not every instance runs every feature. An operator may **turn a
   feature off instance-wide** (policy, footprint, legal). A client must **discover** what the instance actually
   supports and **hide** what it doesn't — gracefully, never crashing on a missing feature.

The previous implementation had a **thin** server-advertised `CapabilityRegistry` (a static list at
`/api/client-capabilities`, used mostly for a settings display + "new feature" notices) and a **much richer,
never-built** target-picture design (a `major.build` hard gate + an append-only capability manifest + a
deprecation lifecycle). The fresh state already has the **seams**: the pre-auth `GET /api/setup` response
advertises `suites: ["v1"]` (crypto-suite negotiation the owner-registration picks from) and, at the time,
`directTransport` (a runtime-detected capability the client degraded on, since removed: a byte-path store is a hard
instance requirement, [ADR 0013](0013-blob-storage-presigned-direct-to-store.md)) — but there was **no version field
and no generic capability manifest**. Worker↔queue capability tags (`CapabilityTags`, ADR 0021) already realize the
operator-blind "worker advertises what it can do" direction.

This ADR decides the **lean core** of a client-facing capability-negotiation layer. The full lifecycle
(deprecation windows, per-user "new feature" notices, audience filtering) is **explicitly deferred**.

## Decision

Make the existing pre-auth **`GET /api/setup`** the **capability-discovery surface**. Add two things to its
response and a client-side gate that reads them.

### 1. `version: { major, build }` — the hard compatibility gate

- **`major`** is the **only** hard gate, and it is the platform's one major: the same number as the release major
  in `VERSION`, moved only by a removal, on the yearly train or in an unscheduled security
  major ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md) owns when and why). Everything additive (a new
  capability, field or endpoint) is a minor and never moves `major`.
- Each client is **compiled with the majors it speaks**. On reaching `/api/setup` it checks the server's `major`:
  - a major it speaks → **compatible**, proceed;
  - a server major **newer** than any it speaks → the client is **too old** → a **blocking** "update your app"
    state;
  - a server major **older** than any it speaks → the **server** is too old → a **blocking** "update this server"
    state;
  - any mismatch is a hard stop **before** login, never a silent partial operation.
- The web client and `admin-web` ship in the server's own image and speak exactly its major. A store-shipped
  client speaks every major released since the train before last (ADR 0085 decision 5).
- `build` is informational (diagnostics, "what's new"); it is **never** a gate.

### 2. `capabilities` — the register of what this server serves

Replaced by [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) (the ids as a tree,
the two levels, the two deliveries, the wire shape `[{ id, …values }]`); the catalog `contracts/platform/capabilities.json`
is the one list, and [en.platform.capability-register](../features/platform/enablers/en.platform.capability-register.md)
the mechanism.

### 3. Capability ≠ permission (the load-bearing distinction)

- A **capability** says what this server serves and what its operator and the tenant allow: *"X exists here at
  all."* It is instance-wide before login, narrowed per tenant after it (ADR 0088), and **never** per user, per
  scope or per role.
- A **permission** is **per-scope / per-role** and **post-auth**: *"you may do X here."* (E.g. the personal-scope
  gate on creating a public share.) A view's client-side projection of it is its **rights**
  ([ADR 0086](0086-one-word-per-meaning.md)).
- A feature is offered only when **(capability active) AND (permission granted here)**. The pre-auth manifest
  carries no identity. It stays operator-blind-safe because it is the same for everyone. The tenant set is the same
  for every member of that tenant.

### 4. Turning a capability off — advertised **and** enforced

Who switches where is [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)'s
(decision 12). What stays this ADR's is that a capability switched off is both **omitted from the manifest**, so every
client hides the feature, and **enforced at its endpoint**: every endpoint that would exercise it, a path that brings it
back into use included, answers `422 capability_disabled`. The manifest alone is only UX, and a stale or hostile client
ignores it; the endpoint gate alone gives a broken-looking UI. Together they are honest and safe.

### 5. The built entries

Replaced by [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) § The register; what
is built is the catalog, and what each entry's off does is its `whenOff` there.

### 6. Capability grain — a coarser projection over use-cases, cross-referenced to them

A capability *looks* like a feature/use-case name and almost always **is** one (or a cluster) — but the two are
different **projections** and must not be collapsed 1:1:

- A **use-case** (`uc.<feature>.<name>`) is the **engineering-truth grain**: a spec + acceptance criteria +
  coverage, internal, kept forever as a record, **constant** across instances.
- A **capability** is the **toggle/negotiation grain**: the coarsest thing an operator would sensibly switch off
  and a client would sensibly gate a whole feature-surface on — a **per-instance, versioned, client-facing** wire
  token.

The mapping is therefore **many-to-many**, not identity: a share id per link role, while `core.group-space` folds
several group use-cases into one switch. Always-on core use-cases (e.g. creating a Memory) get **no** capability; runtime/ops signals (e.g. a
future `server.auto-updates`) would be capabilities with **no** single use-case.

Two rules follow, so the catalog stays honest instead of being invented in parallel:

- **A capability entry declares the use-case(s) it gates** — a `serves: [uc.…]` back-ref in the catalog
  `contracts/platform/capabilities.json` (**catalog-only metadata**, never on the wire — a client needs the ids, not
  the use-case links). This is the **same wiring model** `validate_feature_wiring` enforces between use-cases/enablers
  and features: `validate_capabilities.py` checks every capability's `serves` resolves to a **real**, built use-case —
  so a catalog entry can't be a typo or drift from the graph. Because the catalog is **append-only**, an id may be *reserved* ahead of its
  first advertisement; a capability is **advertised active** only once its use-case(s) are actually **built** and
  the operator hasn't disabled it. (So an entry for a feature still being built is valid in the catalog,
  advertised as that feature finishes.)
- **Pick the grain coarser than a use-case.** One token *per* use-case would bloat the wire contract and the
  client gating, and most use-cases can't be disabled independently anyway. The **default grain is feature- or
  feature-cluster-level**; drop to a finer capability only when an operator has a real reason to toggle that slice
  alone (as `core.public-shares.drop-box` splits from `core.public-shares`).

## Consequences

- **Self-hosted version drift becomes explicit, not a silent break.** The `major` gate turns "why is this
  button doing nothing" into "your app and this server are incompatible — update one."
- **Operators get a real off-switch** for optional features, enforced server-side, hidden client-side — without
  per-feature bespoke plumbing (one registry, one gate).
- **`/api/setup` grows into the pre-auth manifest** it was already becoming (`suites` + `directTransport` were
  the precedent). `suites` **stays as-is** for now; `directTransport` was **removed** (S3 is a hard
  requirement, [ADR 0013](0013-blob-storage-presigned-direct-to-store.md)) rather than folded in.
- **Deferred (each a later slice, none blocking this):** the **deprecation lifecycle** (`state`, `sunsetAt`,
  `supersededBy`, the ~4-month sunset + non-dismissable warning); **per-user "new feature" notices/acks**;
  **audience filtering** (`ALL_USERS` vs `SERVER_OWNER`); **client device self-capabilities** (NPU/WebCodecs,
  a separate direction); migrating `suites` into the unified manifest.
- **Not a permission system.** This ADR deliberately does not touch per-scope/role authorization — it only adds
  the discovery + version layer above it. Conflating the two is the main modeling risk, called out
  in §3.
- **The catalog is wired to the use-case index, not a parallel list.** Because every capability `serves` real,
  built use-cases (§6, validated), the catalog can't grow orphan entries or outlive the behavior it gates — the
  same guarantee `validate_feature_wiring` already gives the feature graph. The second modeling risk (treating
  capabilities *as* use-cases 1:1) is closed by the coarser-grain rule in §6.

## Alternatives considered

- **A min/max version range advertised by the server.** Rejected: ranges invite "works but degraded" ambiguity.
  The server advertises one removal-only `major`, which keeps the gate binary. The set of majors a client speaks
  is the client's own, fixed when it is built (ADR 0085), not a range the server negotiates.
- **Semantic per-endpoint versioning (Accept-Version headers, /v2/).** Rejected for now — heavier, and it
  answers "which shape" not "does this instance do X at all"; the capability manifest answers the discovery
  question directly and the `major` gate covers breaking removals.
- **Client-only feature flags (build-time).** Rejected — can't reflect a *specific instance's* operator choices;
  discovery must be **server-advertised** to be per-instance.

## Related

- On the docs site: [Capabilities](../../site/src/content/docs/run/capabilities.mdx) (the register for the Server Owner),
  [Moderation](../../site/src/content/docs/run/moderation.md) (switching as one of the operator's levers) and the
  [Security roadmap](../../site/src/content/docs/security/roadmap.md) (the designed switches).
