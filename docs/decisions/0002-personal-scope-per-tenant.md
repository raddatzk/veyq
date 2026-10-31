# ADR 0002 — Personal scopes are tenant-bound (one per member, per tenant)

- Status: Accepted
- Built: yes
- Date: 2026-06-25
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

An instance hosts multiple tenants, each on its own domain (e.g. `family.example.com`,
`friends.example.com`), and the backend resolves the active tenant from the request `Host`. A naive
model gives each user a single **global** personal space. But sharing a private memory out of a global
personal space has no natural tenant host — a share link would land on an arbitrary or wrong domain,
leaking a private memory's link across tenant boundaries (e.g. a family memory surfacing under
`friends.example.com`).

## Decision

Personal scopes are **tenant-bound**: a user has a **separate personal scope per tenant** they belong
to, born by the member's own client the first time it enters that tenant after unlock. Group scopes likewise
live within a tenant. There is **no host-less / global content scope** — every scope sits inside exactly one
tenant.

## Consequences

- **Host-correct sharing.** Sharing from a personal scope produces a link under that tenant's domain (a
  family memory → `family.example.com`, never `friends.example.com`). No cross-tenant host leakage.
- **Host-based routing covers all scope kinds** — personal and group need no special-case routing: the shell
  enters them, and a share link points at them, under their tenant's host like everything else. The access gate
  itself is not host-bound — an operation reaches a scope by id from any of its member's domains
  ([ADR 0066](0066-tenant-suspension.md)), and only membership decides it.
- **"My private space" is per-tenant, not global.** A user in N tenants has N personal scopes.
- **First-run has no personal scope.** At `setup-server` no tenant exists yet, so the Server Owner gets
  a personal scope only once they create or join a tenant.
- **Cost / rejected alternative:** there is no single "all my private content across tenants" view; a
  global personal space was rejected precisely because it cannot keep share links on the correct domain.
  Cross-tenant aggregation, if ever wanted, would be an explicit separate feature.
