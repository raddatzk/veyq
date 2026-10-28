# ADR 0019 — The token-less fragment is the Tenant space (shared root is home), Personal on `~me`

- Status: Accepted
- Built: yes
- Date: 2026-07-03
- Model: [en.spaces.tenant-scope](../features/spaces/enablers/en.spaces.tenant-scope.md)

## Context

[ADR 0015](0015-operator-blind-readable-urls.md) put the in-shell content path in the URL `#fragment` and
established two leading tokens — `~me` (personal) and `@<group>` (group) — resolved client-side. It did **not**
pin what a **token-less** fragment means: the first web slice simply defaulted an empty/bare fragment to
**Personal** (`~me`), because Personal was the only scope the shell
could open at the time — the shared `TENANT` scope, though born at activation
([ADR 0006](0006-cold-tenant-scope-birth.md)), had no client entry.

We are now making the `TENANT` scope enterable ([en.spaces.tenant-scope](../features/spaces/enablers/en.spaces.tenant-scope.md)).
That forces the question ADR 0015 left open: with
**three** openable kinds, which one owns the **token-less** default — the clean `family.example.com/` with no
bare `#`? veyq's product center is **memory-sharing**; a tenant *is* a domain, and its shared root scope is the
commons that domain exists to serve. Two candidates:

- **Personal as the token-less home** (today's implicit default). Keeps the first-slice behaviour; but it makes
  the *private* space the face of a *shared* domain, and pushes the shared space onto a token, inverting the
  product's emphasis.
- **Tenant as the token-less home.** `family.example.com/` lands in the family's shared space; Personal becomes
  the explicit `~me`. Matches "the domain is the tenant is the shared space", and reserves the cleanest URL for
  the collaborative center.

## Decision

**The token-less fragment resolves to the `TENANT` scope; Personal and groups keep explicit leading tokens.**

The full grammar (extends, and revises the default of, [ADR 0015](0015-operator-blind-readable-urls.md)):

| Space | Root | A child |
|---|---|---|
| **Tenant** (shared, default home) | `/` — token-less, **no bare `#`** | `/#urlaube` |
| **Personal** | `/#~me` | `/#~me/urlaube` |
| **Group** | `/#@<id>` | `/#@<id>/urlaube` |

- **Token-less = tenant, and it stays clean.** A tenant child rides a bare fragment (`#urlaube`) with **no**
  `~me`/`@` prefix and **no** empty `#` at the root — `/` alone is the tenant root. The leading token is what
  selects the scope; its absence *is* the tenant selector.
- **Personal ↔ Tenant is a leading-token flip**, resolved entirely client-side like every other fragment move
  ([ADR 0015](0015-operator-blind-readable-urls.md)) — the blind server resolves no path and sees no fragment.
- **Fallback to Personal when there is no tenant grant.** The token-less home is openable only if the member
  **holds a `TENANT` grant** it can open; for a member who does not, the shell **falls back to Personal** (`~me`) as
  the effective home and marks the Tenant entry unavailable ([en.spaces.tenant-scope](../features/spaces/enablers/en.spaces.tenant-scope.md), E1). The default
  is thus *conditional*: token-less means "tenant if you can open it, else personal".
- **Slugs stay in the fragment.** Folder/Memory slugs are unchanged from [ADR 0015](0015-operator-blind-readable-urls.md);
  only the leading token (or its absence) changed meaning. Operator-blindness is untouched — the fragment still
  never reaches the server.

## Consequences

- **The shared space is the front door.** Opening `family.example.com/` drops a granted member in the commons —
  the on-brand default for a memory-*sharing* app — with Personal one `~me` click away.
- **Cleanest URL for the collaborative center.** The token-less form (`/`, `#urlaube`) belongs to the space
  meant to be shared and linked; the private space carries the visible `~me` marker, which also reads as an
  honest "you are in your private space" signal.
- **The default is conditional, not absolute** (cost). "Token-less = tenant" holds only for grant-holders; a member
  without an openable tenant grant lands in Personal. This is a deliberate, documented fallback, not a bug — but it
  means a bare `/` does not universally identify one scope across members.
- **The flip changes what a bare bookmark means** (cost). A pre-existing bookmark to `family.example.com/`
  (bare) that used to open Personal now opens the Tenant space (or falls back to Personal). Explicit `#~me/…`
  and `#@…/…` bookmarks are **unaffected**; only the token-less default moved. Acceptable — a bare root is a
  home link, not a deep content bookmark.
- **Deep-link-through-login is unchanged.** The route guard still remembers the intended fragment and restores
  it after unlock ([ADR 0015](0015-operator-blind-readable-urls.md)); a cold hit on `/` restores to the tenant
  home (or the Personal fallback).
- **Rejected — keep Personal as the token-less home.** Reserves the cleanest URL for the private space on a
  shared domain and tokenises the commons, inverting the product's emphasis. Rejected; it only made sense while
  the tenant scope had no client entry.
- **Rejected — a distinct token for the tenant** (e.g. `#^` or `#tenant`). Adds a third sigil and leaves the
  clean token-less `/` either meaningless or arbitrarily assigned; worse ergonomics for no gain. The absence of
  a token is the most natural "the whole shared space" selector.
- **Supersedes only the default, not ADR 0015.** ADR 0015's fragment-routing decision, client-side resolution,
  and the `~me`/`@` tokens all stand; this ADR pins the previously-unspecified token-less default and is
  cross-referenced from 0015.
