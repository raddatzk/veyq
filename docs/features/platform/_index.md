# Feature: Platform

The **cross-feature substrate** that keeps a self-hosted instance and its drifting clients honest with each
other. Not an end-user feature in itself — it is the negotiation + discovery layer every other feature rides on:
what this instance can do, what version it speaks, and which optional features the operator left on.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the manifest is pre-auth,
  identity-free, and the same for everyone — operator-blind-safe). Every client surface reads the same
  manifest; what each surface actually has is [`docs/coverage/`](../../coverage/README.md).
- **Decisions:** [ADR 0032](../../decisions/0032-capability-discovery-version-gating.md) (capability discovery +
  the `major` version gate — the lean core: `/api/setup` advertises `version` + a `capabilities` manifest, clients
  hard-gate on `major` and soft-gate features on capability presence, operators disable a capability instance-wide
  and the server enforces it).

## Use-cases

- [uc.platform.capability-discovery](use-cases/uc.platform.capability-discovery.md) — **built (web)** (cr-0103:
  a client discovers the instance's `version` + active `capabilities` from the pre-auth `/api/setup`,
  **hard-gates** on a `major` mismatch (forced "update app / update server"), **soft-gates** each feature on its
  capability's presence (graceful hide, never a crash), and an operator can **turn a capability off instance-wide**
  — omitted from the manifest **and** rejected at its endpoint. First consumer: the **sharing** roles
  (`sharing.public-links` / `sharing.uploader-links`).
  [ADR 0032](../../decisions/0032-capability-discovery-version-gating.md))

## Precedent already in the fresh state

The pre-auth `GET /api/setup` already advertises `suites: ["v1"]` (crypto-suite negotiation); the background-tasks
layer already matches worker **capability tags** (`CapabilityTags`,
[ADR 0021](../../decisions/0021-operator-blind-background-tasks.md)). This feature adds the **generic
client-facing** layer (a versioned capability manifest + the hard gate) above those. (An earlier `directTransport`
advisory on `/api/setup` was **removed** when a byte-path store became a hard requirement, cr-0105 / ADR 0013.)

## Deferred (later slices, each its own dependency)

- **Deprecation lifecycle** — `state: active | deprecated`, `sunsetAt` / `supersededBy`, the sunset window +
  non-dismissable warning, and the *remove-X-add-Y* replacement pattern (immutable ids).
- **"New feature" notices + per-user acks** — surfacing a freshly-introduced capability, dismissed per user.
- **Audience filtering** — `ALL_USERS` vs `SERVER_OWNER` capability visibility.
- **Unify `suites`** into the single `capabilities` manifest.
- **Client device self-capabilities** (NPU / WebCodecs / …) — a separate direction (client → self), not
  server-advertised.
