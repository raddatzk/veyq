# ADR 0015 — Operator-blind readable URLs: the content path lives in the #fragment

- Status: Accepted
- Built: yes
- Date: 2026-06-28
- Model: [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md)
- Replaced in part: a foreign `~username` as the profile view by ADR 0083 (built)

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

- The current tree position is encoded in `location.hash`: `#~kevin/reisen-2026/memories/rom` (a **`~username`**
  personal scope), `#@familie/sommer` (a group scope). The browser **never transmits the fragment** in
  an HTTP request — so the readable, name-derived slugs, and which scope and node a load opens, never reach the
  operator's request line or access log, while the path stays bookmarkable. (The username itself is not hidden: it
  is plaintext on the server, [ADR 0023](0023-per-tenant-member-identity.md).) On operator-served web this holds
  against a passive operator only: an active one serves the code that reads the fragment
  ([ADR 0026](0026-client-trust-tiers.md)). `~<username>` + `@<group>` + the folder/Memory slugs live only in the
  fragment. **The token-less fragment** (a bare `#urlaube`, and `/` at the root) selects the shared **`TENANT`**
  space — the default home, [ADR 0019](0019-token-less-tenant-home.md).
- **`~<username>` is the personal scope's token, never a profile.** The personal scope is addressed by the
  caller's own per-tenant `username` ([ADR 0023](0023-per-tenant-member-identity.md)) — the sidebar builds
  `#~<own-username>`; `~me` stays a valid fallback where the handle is unknown. Another member's `~username`
  resolves to nothing openable: a person's profile is its own fragment
  ([ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 5). A `username`
  change re-slugs your personal path (old bookmarks may miss) — acceptable for a client-side convenience path.
- **Resolution is local.** The client walks its **decrypted** tree, re-slugifying each child's name to match a
  segment — exactly the navigation the operator-blind server can't do. The server is never consulted to resolve
  a path; it only serves the per-scope opaque node rows ([ADR 0012](0012-content-key-hierarchy.md)).
- **One slug function, two uses.** `slugify(name)` is URL-safe (`[a-z0-9-]`, German folding + diacritic strip,
  deterministic non-empty fallback). It **already feeds the blinded `slug_token`** the server enforces
  uniqueness on (`UNIQUE(parentId, slug_token)`, [ADR 0012](0012-content-key-hierarchy.md)) — so the **same**
  function resolves URL→node. Sibling slugs are not unique by construction: a name folds by its node's kind
  ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 16 — a file `a.b` and a
  folder `a-b` share one URL segment), a name is unique **within its route group** only (the
  token of an application's node HMACs `<routeSlug>/<slug>`, so a Memory and an Expenses Pot `Rom` coexist — the
  route slug below already gives them two addresses), and two drop-box guests' identity folders sit under different
  guest keys, so two guests named "Anna" both slugify to `anna`. So the client disambiguates, by one rule for building and resolving
  a path: among siblings of one plain segment **and one class** (the numbering spaces below) the first in **creation
  order** keeps it, the others get `-2`, `-3`, … skipping a number whose segment a sibling holds as its own name.
  Creation order is the server-observed creation instant (to the millisecond), then the node id for two born in one
  millisecond — an order no client can steer: the instant is the server's, set once and kept by every rename, move,
  trash and restore, and node ids are client-minted random UUIDv4 that only break a tie. **Rejected:** node ids alone,
  which let a later sibling whose id happened to sort first take an earlier one's path; and time-ordered client ids
  (UUIDv7), because a client's clock is forgeable and would leak. The rule is client-side only (nothing stored or
  sealed); a later-created sibling never moves an earlier path; an older sibling that comes back (restored, or moved
  or renamed in) takes its birth place and shifts the younger ones' segments — deterministic, by design. A download's
  ZIP names its directories by its own rule ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
  decision 3). Group slugs are **not** server-enforced ([ADR 0014](0014-group-scopes-and-governance.md) keeps no
  group slug token), so a group's token is its name's slug only while no other of the member's groups shares it, else
  its scope id; a slug that two of them share resolves to **no** group, never to whichever comes first — guessing
  could open the wrong group's content. Where each piece lives in the client:
  [en.platform.node-tree § Addressing](../features/platform/enablers/en.platform.node-tree.md#addressing-urls).
- **The grammar of a node's path: a folder ends in a slash, an application puts its slug before its nodes,
  Depot has none.** The path stays in the fragment, readable and resolved client-side exactly as above, after the
  scope token (token-less tenant, `~username`, `@group`). What follows the token is:
  - **A folder** is its slug path with a **trailing slash**: folder `memory` in `foo` is `#foo/memory/`.
  - **A Depot node** (a file, a drop box) is its plain slug, no segment and no slash: `#foo/memory`. Depot is an
    explicit, documented exception — it **declares** that it has no segment, so that absence is a choice the
    registry knows and only one application may make.
  - **Every other application's node** carries the application's **route slug** before its name, claimed once in
    the registry (a second claim throws): Memory `memory` in `foo` is `#foo/memories/memory`.
    The segment stands where the path enters the application; its children extend the path without repeating it
    (`#foo/memories/memory/<chapter>`), and sub-pages are the application's own.
  - **Resolution.** A segment equal to a registered route slug, where a node child is expected and the parent is
    not already that application's, is the application segment and the next segment names one of its nodes. The
    slug is therefore **reserved** among the plain names: a folder (or file) whose name folds to it is numbered by
    the existing rule — a folder `Memories` is `memories-2`, the next `memories-3`. Platform words (the
    history-mode routes) cannot be claimed as a slug.
  - **Numbering spaces are separate.** Equal names are numbered (`anna`, `anna-2`, in creation order) within a
    **class** only — the folders; one application's nodes; the nodes with no segment (Depot and any kind nobody
    claimed). File `a.b` and folder `a-b` are both `a-b` and need no `-2` between them; the trailing slash tells
    them apart. A name's uniqueness follows the application split: each application
    with a route slug is its own group, while the folders and the nodes with no segment stay one group between
    them (a folder `Rom` and a file `rom` still meet by name, though their URLs are told apart by the slash).
  - **The trailing slash is part of the address, and fragile by design.** A path without it means the **file** if
    one has that slug, else the folder; with it, **only** the folder (never a file, never a Memory). `…/memory/bar`
    always reads `memory` as a folder, since a file has no children (a drop box has, so the reading backtracks:
    folder first for a segment with more behind it, then the node that has children). Parsing therefore keeps the
    slash and building emits it for a folder at the end of the path; a client that drops it still opens the right
    node unless a file shares the slug.
  - **Rejected: history-mode node URLs** (`/<app-slug>/:nodeId`, with no name, title or place in a URL). They
    would put the node id into the operator's access logs on every hard load, need a scope lookup (an endpoint, a
    probe or a scope in the path) and a replacement for `~username`, and contradict this ADR's reason for the
    fragment. A segment-less Memory bookmark (`#foo/rom`) does not resolve and falls back to the scope root.
- **History-mode for the top level.** `/`, `/login`, `/setup`, … stay real paths; only the content position
  rides the fragment. The shell renders **browse or detail by the resolved node's `kind`** — no per-node route.
- **Deep links survive a cold start.** Because a fresh load drops the in-memory vault, a bookmarked
  `#~me/…/rom` bounces to login; the route guard **remembers the intended fragment** and the login view
  **restores it after unlock** — so the bookmark lands on the exact node, still without the slug ever reaching
  the server.

## Consequences

- **Readable URLs *and* operator-blind.** The design source's URL feel (`~me`, `@group`, slugs) is reproduced
  with no name in any request line — the fragment is the seam that makes both true at once (against an active
  operator only on a client it does not serve, ADR 0026).
- **No server navigation, no server slug for the path.** The server keeps serving opaque per-scope node rows;
  all path semantics are client-side. (The blinded `slug_token` it *does* store is for per-parent uniqueness,
  not navigation.)
- **`@group` is the routing seam for groups** ([ADR 0014](0014-group-scopes-and-governance.md)), **`~username`**
  for the caller's personal scope ([ADR 0023](0023-per-tenant-member-identity.md)) and **`#people/<userId>`** for
  a person's profile (ADR 0083).
- **Limitation:** slugs are lossy + name-derived; the displayed label always comes from the decrypted name, not
  the slug. A name change changes the slug (old bookmarks to a renamed node may miss) — acceptable for a
  client-side convenience path.
