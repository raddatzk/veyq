# ADR 0015 — Operator-blind readable URLs: the content path lives in the #fragment

- Status: Accepted
- Date: 2026-06-28

## Context

The web shell wants **readable, bookmarkable URLs** for navigation — the pre-E2EE design source used
human-readable paths (`/~me/reisen-2026/rom`, `/@familie/…`) that mirror the breadcrumb. But that era was **not
operator-blind**: its server *saw* the path and *computed* the navigation from it. Ours must not. The tension:

- Node + scope names are **sealed** ([ADR 0012](0012-content-key-hierarchy.md)); the server must never learn a
  name. A URL slug is **derived from a name** — so a plaintext slug anywhere the server can read it is a leak.
- With HTML5 history routing, the browser sends the **full path** to the server on any **hard load / reload /
  deep-link / bookmark** (the SPA fallback must return `index.html`). That request line lands in the operator's
  access / reverse-proxy logs. So plaintext slugs in the **path** leak names to the operator — even though the
  SPA "resolves" them client-side; the issue is the raw URL *bytes*, not who interprets them.

Considered and rejected during refinement:

- **Plaintext slugs in the history path** (`/~me/reisen-2026/rom`). Rejected — leaks names to access logs on
  every hard load (above). A "cosmetic" slug is still a leak.
- **Opaque ids only** (`/n/<uuid>`, `/m/<uuid>`). Operator-blind and simple, but **not readable** — no
  bookmark tells you where it goes, and it doesn't match the design source's URLs.
- **Full hash-mode for the whole app** (`/#/login`, `/#/~me/…`). Operator-blind, but uglifies the auth URLs
  too and conflates auth routing with content routing.

## Decision

**The in-shell content path lives in the URL `#fragment`, resolved entirely client-side; top-level (auth)
routes stay history-mode.**

- The current Memory-tree position is encoded in `location.hash`: `#~kevin/reisen-2026/rom` (a **`~username`**
  personal/profile scope), `#@familie/sommer` (a group scope). The browser **never transmits the fragment** in
  an HTTP request — so the readable, name-derived slugs (and the username) reach the operator **never**, while
  the path stays bookmarkable. `~<username>` + `@<group>` + the folder/Memory slugs live only in the fragment.
  **The token-less fragment** (a bare `#urlaube`, and `/` at the root) selects the shared **`TENANT`** space —
  the default home, pinned by [ADR 0019](0019-token-less-tenant-home.md) (this ADR left the token-less default
  unspecified).
- **`~<username>` is a person route, not a constant.** The old self-token `~me` is **removed**; the personal
  scope is addressed by the caller's own per-tenant `username` ([ADR 0023](0023-per-tenant-member-identity.md)) —
  the sidebar builds `#~<own-username>`. Opening your **own** `~username` is your personal scope (as today);
  opening **another** member's `~username` is the (later) **profile view** — the Memories *you can see* that tag
  that person. That view is derived **entirely client-side** from the scopes you already decrypt (the server
  never computes "which Memories is X in" — that social graph would break operator-blindness); it depends on a
  future per-Memory **participants** tag (sealed `userId`s), so today only your own `~username` resolves to
  something openable. A `username` change re-slugs your personal path (old bookmarks may miss) — acceptable for a
  client-side convenience path.
- **Resolution is local.** The client walks its **decrypted** tree, re-slugifying each child's name to match a
  segment — exactly the navigation the operator-blind server can't do. The server is never consulted to resolve
  a path; it only serves the per-scope opaque node rows ([ADR 0012](0012-content-key-hierarchy.md)).
- **One slug function, two uses.** `slugify(name)` is URL-safe (`[a-z0-9-]`, German folding + diacritic strip,
  deterministic non-empty fallback). It **already feeds the blinded `slug_token`** the server enforces
  uniqueness on (`UNIQUE(parentId, slug_token)`, [ADR 0012](0012-content-key-hierarchy.md)) — so the **same**
  function resolves URL→node. Sibling slugs are no longer unique by construction since
  [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md): a file folds by its own
  kind's function (a file `a.b` and a folder `a-b` coexist with one URL segment), and two drop-box guests' identity
  folders sit under different guest keys, so two guests named "Anna" both slugify to `anna`. So the client
  disambiguates, by one rule for building and resolving a path (`web/src/core/node/location.ts` `segmentsOf`):
  among siblings of one plain segment the first in **creation order** keeps it, the others get `-2`, `-3`, …
  skipping a number whose segment a sibling holds as its own name. Creation order is the server-observed
  `NodeSummary.createdAt` (to the millisecond, `veyq_node.created_at TIMESTAMP(3)`), then the node id for two born
  in one millisecond (`compareByCreation`) — an order no client can steer: the instant is the server's, set once and
  kept by every rename, move, trash and restore, and node ids are client-minted random UUIDv4 that only break a tie.
  (Node ids alone, the first rule, let a later sibling whose id happened to sort first take an earlier one's path;
  time-ordered client ids — UUIDv7 — were rejected because a client's clock is forgeable and would leak, inbox #387.)
  The rule is client-side only (nothing stored or sealed); a later-created sibling never moves an earlier path; an
  older sibling that comes back (restored, or moved or renamed in) takes its birth place and shifts the younger
  ones' segments — deterministic, by design; and a future folder export names its directories by the same rule. Group slugs are **not** server-enforced (no group slug token) — same-named groups resolve
  first-match, client-side ([ADR 0014](0014-group-scopes-and-governance.md)).
- **History-mode for the top level.** `/`, `/login`, `/setup`, … stay real paths; only the content position
  rides the fragment. The shell renders **browse or detail by the resolved node's `kind`** — no per-node route.
- **Deep links survive a cold start.** Because a fresh load drops the in-memory vault, a bookmarked
  `#~me/…/rom` bounces to login; the route guard **remembers the intended fragment** and the login view
  **restores it after unlock** — so the bookmark lands on the exact node, still without the slug ever reaching
  the server.

## Consequences

- **Readable URLs *and* operator-blind.** The design source's URL feel (`~me`, `@group`, slugs) is reproduced
  with **zero** name leakage — the fragment is the seam that makes both true at once.
- **No server navigation, no server slug for the path.** The server keeps serving opaque per-scope node rows;
  all path semantics are client-side. (The blinded `slug_token` it *does* store is for per-parent uniqueness,
  not navigation.)
- **`@group` is a routing seam for groups** ([ADR 0014](0014-group-scopes-and-governance.md)) and **`~username`
  for person/profile views** ([ADR 0023](0023-per-tenant-member-identity.md)) — the parser accepts both ahead of
  their features; today only the caller's **own** `~username` (their personal scope) resolves, foreign profiles
  arrive with the participants tag.
- **Limitation:** slugs are lossy + name-derived; the displayed label always comes from the decrypted name, not
  the slug. A name change changes the slug (old bookmarks to a renamed node may miss) — acceptable for a
  client-side convenience path.

> Shipped under cr-0031 (web). The slug hardening, the fragment parser/resolver, the single content host, and
> the deep-link-through-login restore all live in the web client; backend unchanged.
