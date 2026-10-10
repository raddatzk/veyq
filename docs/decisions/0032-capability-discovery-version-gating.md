# ADR 0032 — Capability discovery & version gating (lean core)

- Status: Accepted
- Date: 2026-07-08

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
`directTransport` (a runtime-detected capability the client degraded on — since removed, §5) — but there is **no version field and no generic
capability manifest**. Worker↔queue capability tags (`CapabilityTags`, ADR 0021) already realize the
operator-blind "worker advertises what it can do" direction.

This ADR decides the **lean core** of a client-facing capability-negotiation layer. The full lifecycle
(deprecation windows, per-user "new feature" notices, audience filtering) is **explicitly deferred**.

## Decision

Make the existing pre-auth **`GET /api/setup`** the **capability-discovery surface**. Add two things to its
response and a client-side gate that reads them.

### 1. `version: { major, build }` — the hard compatibility gate

- **`major`** is the **only** hard gate. It is incremented **exclusively when a capability is removed** (a
  breaking removal of an ability a client might rely on). Everything additive — a new capability, a new field, a
  new endpoint — bumps **`build`**, never `major`.
- Each client is **compiled with the `major` it was built against** (`expectedMajor`). On reaching `/api/setup`
  it compares:
  - `server.major == client.expectedMajor` → **compatible**, proceed.
  - `server.major > client.expectedMajor` → the client is **too old** → a **blocking** "update your app" state.
  - `server.major < client.expectedMajor` → the **server** is too old → a **blocking** "update this server" state.
  - Any mismatch is a hard stop **before** login — no silent partial operation.
- `build` is informational (diagnostics, "what's new"); it is **never** a gate.

### 2. `capabilities: [{ id }]` — the active capability manifest

- The instance advertises the **active** capabilities it serves, as stable lowercase-dotted ids
  (`sharing.public-links`, `sharing.uploader-links`, …).
- **Source of truth** is an **append-only** catalog `contracts/platform/capabilities.json`, codegen'd into a
  backend registry **and** each client's constants — so ids can never silently diverge across surfaces. Each
  catalog entry also carries a `serves: [uc.…]` back-ref to the use-case(s) it gates (catalog-only, validated —
  see §6); it is **not** put on the wire.
- **Lean core = active set only.** The `state` field (`active | deprecated`), `sunsetAt`/`supersededBy`, and the
  deprecation lifecycle are **deferred** — the wire shape is `[{ id }]` now, extensible to `[{ id, state, … }]`
  later without a `major` bump (additive → `build`).

### 3. Capability ≠ permission (the load-bearing distinction)

- A **capability** is **instance-wide** and **pre-auth**: *"this server knows how to do X at all."* It is read
  from `/api/setup` before login.
- A **permission** is **per-tenant / per-scope / per-role** and **post-auth**: *"you may do X here."* (E.g. the
  personal-scope gate on creating a public share.)
- A feature is offered only when **(capability present) AND (policy permits here)**. The capability manifest
  never carries per-user data — it stays operator-blind-safe (pre-auth, no identity, same for everyone).

### 4. Turning a capability off — advertised **and** enforced

An operator disables a capability **instance-wide** via config (a `veyq.capabilities.disabled` list, or a
per-feature flag). A disabled capability is then:

- **Omitted from the manifest** → every client **hides** the feature (soft, graceful — the ShareDialog simply
  doesn't offer the role).
- **Enforced at its endpoint** → the manifest is a UX hint, but the **authority is the server**. The endpoint
  that would exercise the capability rejects with `422 capability_disabled` — **every** such endpoint: a path that
  brings the capability back into use (re-activating a disabled share) exercises it as much as the one that
  first creates it. A client that ignores the manifest (or is stale) still cannot use a disabled capability.

Both are required: the manifest alone is only UX; the endpoint gate alone gives a broken-looking UI. Together
they are honest and safe.

### 5. Seed catalog & first consumers

The lean core seeds the catalog with the capabilities whose features already exist **and** that an operator has
a plausible reason to switch off. Two computation sources feed the same active manifest: an operator
**disabled-list** (policy) and, separately, an infra **preflight** (runtime).

| id | `serves` (use-cases) | source | disabled ⇒ |
|---|---|---|---|
| `sharing.public-links` | share-public-viewer, share-contributor | operator policy | Share affordance hidden; `createShare(READ/READ_WRITE)` and re-activating such a share → `422` |
| `sharing.uploader-links` | share-uploader | operator policy | Uploader option hidden; `createShare(WRITE_ONLY)` and re-activating such a share → `422` |
| `spaces.groups` | add-group-member, manage-group-members, rename-group, delete-group, consent-group-add | operator policy | group-space UI hidden; the group-create endpoint → `422` |
| `account.device-trust` | trust-device, remember-this-device, auto-unlock, set-device-security | operator policy | the "remember this device" toggle hidden; enrolling a device wrap → `422` |

**Sharing is the worked end-to-end proof** (manifest → client soft-gate → server `422`) since it is fully built
(web). `spaces.groups` and `account.device-trust` are seeded + advertised + client-soft-gated on the **same**
machinery; their endpoint-`422` enforcement lands at each feature's own create/enable gate (the identical
one-line pattern), advertised **active** only once that feature is built (§6).

The **infra-detected** category (a capability computed by a preflight, not a policy toggle, with no operator
off-switch or `422`) was originally illustrated by `directTransport` on `/api/setup`. That flag has been
**removed** — a byte-path store became a hard instance requirement ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)),
so there is nothing to soft-gate — leaving a future worker-gated `memory.derivatives` as the standing example of
an infra-detected capability that could fold into the manifest (see Consequences).

### 6. Capability grain — a coarser projection over use-cases, cross-referenced to them

A capability *looks* like a feature/use-case name and almost always **is** one (or a cluster) — but the two are
different **projections** and must not be collapsed 1:1:

- A **use-case** (`uc.<feature>.<name>`) is the **engineering-truth grain**: a spec + acceptance criteria +
  coverage, internal, kept forever as a record, **constant** across instances.
- A **capability** is the **toggle/negotiation grain**: the coarsest thing an operator would sensibly switch off
  and a client would sensibly gate a whole feature-surface on — a **per-instance, versioned, client-facing** wire
  token.

The mapping is therefore **many-to-many**, not identity — the seed shows it: `sharing.public-links` bundles the
viewer + contributor use-cases while `sharing.uploader-links` stands alone (2 capabilities over 3 sharing
use-cases), and `spaces.groups` / `account.device-trust` each fold **four to five** use-cases into one operator
switch. Always-on core use-cases (e.g. creating a Memory) get **no** capability; runtime/ops signals (e.g. a
future `server.auto-updates`) would be capabilities with **no** single use-case.

Two rules follow, so the catalog stays honest instead of being invented in parallel:

- **A capability entry declares the use-case(s) it gates** — a `serves: [uc.…]` back-ref in the catalog
  `contracts/platform/capabilities.json` (**catalog-only metadata**; the wire manifest stays `[{ id }]` — a
  client needs the ids, not the use-case links). This is the **same wiring model** `validate_feature_wiring`
  already enforces between use-cases/enablers and features. A validator (an extension of that one, or a sibling)
  checks every capability's `serves` resolves to a **real** use-case (the doc exists) — so a catalog entry can't
  be a typo or drift from the graph. Because the catalog is **append-only**, an id may be *reserved* ahead of its
  first advertisement; a capability is **advertised active** only once its use-case(s) are actually **built** and
  the operator hasn't disabled it. (So `spaces.groups` / `account.device-trust` are valid catalog entries now,
  advertised as their features finish building.)
- **Pick the grain coarser than a use-case.** One token *per* use-case would bloat the wire contract and the
  client gating, and most use-cases can't be disabled independently anyway. The **default grain is feature- or
  feature-cluster-level**; drop to a finer capability only when an operator has a real reason to toggle that slice
  alone (as `sharing.uploader-links` splits from `sharing.public-links`).

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
  the instance-wide discovery + version layer above it. Conflating the two is the main modeling risk, called out
  in §3.
- **The catalog is wired to the use-case index, not a parallel list.** Because every capability `serves` real,
  built use-cases (§6, validated), the catalog can't grow orphan entries or outlive the behavior it gates — the
  same guarantee `validate_feature_wiring` already gives the feature graph. The second modeling risk (treating
  capabilities *as* use-cases 1:1) is closed by the coarser-grain rule in §6.

## Alternatives considered

- **A min/max version range instead of a single `major`.** Rejected — ranges invite "works but degraded"
  ambiguity; a single removal-only `major` keeps the gate binary and cheap to reason about (a capability that
  still exists is `build`-additive; one that's gone is a `major` bump).
- **Semantic per-endpoint versioning (Accept-Version headers, /v2/).** Rejected for now — heavier, and it
  answers "which shape" not "does this instance do X at all"; the capability manifest answers the discovery
  question directly and the `major` gate covers breaking removals.
- **Client-only feature flags (build-time).** Rejected — can't reflect a *specific instance's* operator choices;
  discovery must be **server-advertised** to be per-instance.
