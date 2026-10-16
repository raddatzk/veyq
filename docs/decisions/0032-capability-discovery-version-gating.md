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

- Ids are **paths in a tree**, `core.<x>` or `<application>.<x>`. The register, its two switching levels
  (operator, tenant owner), inheritance along the path and what makes an entry switchable are
  [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)'s. **Built today:** seven
  `core.*` entries (§5), the first cut of the tree (ADR 0088 decision 10).
- **Source of truth** is the catalog `contracts/platform/capabilities.json`, codegen'd into a backend registry
  **and** each client's constants, so ids can never silently diverge across surfaces. Each catalog entry also
  carries a `serves: [uc.…]` back-ref to the use-case(s) it gates (catalog-only, validated — see §6); it is
  **not** put on the wire. From V1 on the catalog is **append-only**: an id is never removed or repurposed, except
  that a presence id may retire at a train (ADR 0088 decision 9).
- **Two deliveries.** The pre-auth `/api/setup` advertises the **instance** level, for what matters before login.
  After login the server delivers the **effective** set for the tenant. The client only reads it and never
  computes it (ADR 0088 decision 3).
- **Wire shape** `[{ id, …values }]`: each entry carries the requirement values in effect on it (ADR 0088 decision
  8), one optional property per requirement of the catalog, which was additive and cost no major. The deprecation lifecycle (`state`, `sunsetAt`, `supersededBy`) stays deferred: a
  removal under [ADR 0085](0085-one-major-moved-on-a-yearly-train.md) is marked `deprecated: true` in its contract
  and needs no manifest state.

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

An operator disables a capability **instance-wide**: in `admin-web` or its CLI, through the Admin API, without a
restart ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 12).
This is built: admin-web's Server → Capabilities panel and `veyq-admin capabilities off <id>` call
`PUT /api/instance/capabilities/{capabilityId}`, the state is a row in the database (`veyq_capability_switch`), and the
next request reads it. There is no configuration property for it (the `veyq.capabilities.disabled` list is gone). A
disabled capability is then:

- **Omitted from the manifest** → every client **hides** the feature (soft, graceful — the ShareDialog simply
  doesn't offer the role).
- **Enforced at its endpoint** → the manifest is a UX hint, but the **authority is the server**. The endpoint
  that would exercise the capability rejects with `422 capability_disabled` — **every** such endpoint: a path that
  brings the capability back into use (re-activating a disabled share) exercises it as much as the one that
  first creates it. A client that ignores the manifest (or is stale) still cannot use a disabled capability.

Both are required: the manifest alone is only UX; the endpoint gate alone gives a broken-looking UI. Together
they are honest and safe. A device switch also acts on what exists: the key material withholds the switched-off wraps
without deleting them (ADR 0088 decision 5). A tenant owner switches a `+` entry off for their tenant the same way,
from the tenant settings in `web/`, with the same gate answering `422` for that tenant's content (ADR 0088 decisions 2,
5 and 12): the entry leaves the tenant's set every member reads after sign-in, and the pre-auth manifest stays the
instance level.

### 5. The built entries

The catalog holds the capabilities whose features exist **and** that an operator has a plausible reason to switch off.
Two computation sources feed the same active manifest: an operator **disabled set** (policy, stored in the database, §4) and,
separately, an infra **preflight** (runtime). These seven are what is built (`contracts/platform/capabilities.json`, whose
`description` per entry is the reference for what off does); a child is switched off by its own id or by its parent's,
the AND along the path being the server's (ADR 0088 decision 2):

| id | `serves` (use-cases) | disabled ⇒ |
|---|---|---|
| `core.public-shares` | share-public-viewer | Share affordance hidden; `createShare`, re-activating and re-issuing a READ link → `422`, and by the AND those of the two children below; every existing link answers guests `404` like an unknown one |
| `core.public-shares.contribute` | share-contributor | the READ_WRITE role is not offered; the same three operations on a READ_WRITE link, and changing a READ link into one, → `422`; an existing READ_WRITE link answers guests `404` |
| `core.public-shares.drop-box` | share-uploader | the uploader option and the drop-box node are not offered; WRITE_ONLY links and creating a drop-box node → `422`; an existing WRITE_ONLY link answers guests `404` |
| `core.group-space` | add-group-member, manage-group-members, rename-group, delete-group, consent-group-add | group creation hidden; `createGroup` → `422` (an existing group stays usable, inbox #440 resolved as a doc fault) |
| `core.remember-device` | trust-device, remember-this-device | enrolling a DEVICE wrap → `422`, and by the AND a passwordless one |
| `core.remember-device.passwordless` | remember-this-device | the "remember this device" toggle hidden; enrolling a DEVICE_KEY wrap → `422` |
| `core.device-linking` | onboard-device | "sign in with code" and the Settings entry hidden; every `/api/pairing/*` endpoint → `422` |

**Sharing is the worked end-to-end proof** (manifest → client soft-gate → server `422`). A share switch also reaches
existing links at once (ADR 0088 decision 5): core's guest gate answers a link of a switched-off role exactly as an
unknown one, on every guest endpoint, and serves it again once the switch is back on.

The **infra-detected** category (a capability computed by a preflight, not a policy toggle, with no operator
off-switch or `422`) was originally illustrated by `directTransport` on `/api/setup`. That flag has been
**removed** — a byte-path store became a hard instance requirement ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)),
so there is nothing to soft-gate. No infra-detected capability exists now: derivatives, once named as the example,
are part of the base function and never switchable (ADR 0088 § Not in the register). The source stays available
for a future one.

### 6. Capability grain — a coarser projection over use-cases, cross-referenced to them

A capability *looks* like a feature/use-case name and almost always **is** one (or a cluster) — but the two are
different **projections** and must not be collapsed 1:1:

- A **use-case** (`uc.<feature>.<name>`) is the **engineering-truth grain**: a spec + acceptance criteria +
  coverage, internal, kept forever as a record, **constant** across instances.
- A **capability** is the **toggle/negotiation grain**: the coarsest thing an operator would sensibly switch off
  and a client would sensibly gate a whole feature-surface on — a **per-instance, versioned, client-facing** wire
  token.

The mapping is therefore **many-to-many**, not identity — the entries show it: three share ids over three sharing
use-cases, while `core.group-space` and `core.remember-device` each fold **two to five** use-cases into one switch. Always-on core use-cases (e.g. creating a Memory) get **no** capability; runtime/ops signals (e.g. a
future `server.auto-updates`) would be capabilities with **no** single use-case.

Two rules follow, so the catalog stays honest instead of being invented in parallel:

- **A capability entry declares the use-case(s) it gates** — a `serves: [uc.…]` back-ref in the catalog
  `contracts/platform/capabilities.json` (**catalog-only metadata**; the wire manifest stays `[{ id }]` — a
  client needs the ids, not the use-case links). This is the **same wiring model** `validate_feature_wiring`
  already enforces between use-cases/enablers and features. A validator (an extension of that one, or a sibling)
  checks every capability's `serves` resolves to a **real** use-case (the doc exists) — so a catalog entry can't
  be a typo or drift from the graph. Because the catalog is **append-only**, an id may be *reserved* ahead of its
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
  the instance-wide discovery + version layer above it. Conflating the two is the main modeling risk, called out
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
