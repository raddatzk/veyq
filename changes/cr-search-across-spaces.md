---
id: cr-search-across-spaces
title: One search field across every space of the tenant, with chips, grouped by context
status: planned
satisfies: [uc.platform.search]
adrs: ["0125", "0079", "0081", "0105", "0047"]
invariants:
  - nothing about a query, a document or a result leaves the device or is written to any storage (ADR 0125 decisions 1, 2)
  - search holds no key; each tree's keys are zeroed once its documents are built (ADR 0125 decision 2)
  - core imports no application; Memories contributes through the registry (ADR 0079, `validate_web_core_imports_no_applications.py`)
  - the active space does not change while the search opens other spaces' trees (ADR 0125 decision 3)
  - a share view has no search field (ADR 0125 decision 7)
  - no backend and no contract change
---

## Goal

Build the first stage of [ADR 0125](../docs/decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md),
as [uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md) states it.

- **Scope:** one search field in the app shell, over every space of the tenant.
- **Searched:** node names, a Memory's date range, place and participants, and the photos and videos of the open
  Memory.
- **Chips:** space, year, place, person, kind.
- **Results:** grouped into *In this Memory*, *In this space* and *Other spaces*.
- **Surface:** web only. It reads what the backend already serves.

## Context & constraints (measured at refine, substrate: `main` at `cf59fa4e3`, this worktree's branch)

- **The registry:** `src/web/src/core/applications/registry.ts`.
  - Per-kind hooks are in `KindDescriptor`. The closest precedents for a pure hook that core calls with keys are
    `cover.findStored`, `exportContent` and `rekeyContent`.
  - A detail contributing data to core has its precedent in `core/node/nodeHeaderContribution.ts`
    (`contributeNodeHeader`).
  - The header forbids shell slots without a first user. The search field is core's, not a slot.
- **Opening a non-active space's tree:** Memories' `features/memories/participantIndex.ts` does it today.
  - The sequence is `fetchTree(scopeId, etag)` (304-aware), then `openScopeById` (`features/spaces/scopes.ts`), then
    `openTree` (`core/node/tree.ts`), then `zeroScopeKey` in a `finally`.
  - It is Memories code, so core cannot import it. Core needs the same sequence of its own.
  - Note that `openScopeById` calls `listMyScopes` each time. Call `listMyScopes` once per search session and reuse
    its result if the scopes module allows it; otherwise accept the extra round-trip and say so.
- **Scope resets:** `registry.scopeReset()` runs on every space enter, refresh and clear (`app/activeSpace.ts`).
  - The search documents are plaintext without keys (ADR 0125 decision 2), so they survive a space switch.
  - They go at sign-out.
  - They also go for a space whose tree no longer fetches or opens.
- **The tenant:** spaces are listed for the tenant resolved from the Host (`listMyScopes`). Another tenant is a
  different domain, which is why ADR 0125 decision 3 keeps search to this tenant.
- **Names and participants:**
  - Participants resolve through `core/people/directory.ts` (`personNow`, `loadTenantPeople`).
  - Memories' meta readers are `unsealNodeDate`, `unsealMemoryLocation` and `unsealMemoryParticipants`, under
    `features/memories/`.
  - `core/node/nodeMeta.ts` holds `unsealNodeMeta`.
- **The open Memory's listing:**
  - The listing lives in `features/memories/openedListing.ts`; `openedMeta` returns capturedAt, GPS and placeName.
  - Its items are component state in `useMediaGalleryLive.ts`, used by `MemoryDetail`.
  - The current node is the last crumb of `app/breadcrumb.ts`. `SpaceView.vue` computes `currentNode` but does not
    export it.
- **Shell:**
  - The topbar is `components/app/AppTopbar.vue`. Its header comment records the removed search centre; the grid is
    in `styles/layout.css`, with compact overrides.
  - `shared/ui/VeyqTagsInput.vue` (Reka `TagsInput` plus `Combobox`) already does chips with suggestions.
  - There is no global shortcut handler and no command palette.
  - The share route `/s/:shareId` renders `layout: 'plain'`, and `AppShell.vue` mounts the topbar only for
    `layout === 'app'`. A bookmark embed shows guest content inside the member shell; it is not indexed beyond the
    bookmark node's own name.
- **Text folding:** there is no shared helper. Two inline copies of NFD plus mark-stripping exist:
  - `features/expenses/people.ts`
  - `MemoryParticipantsDialog.vue`

  `shared/naming.ts`' slug folding is frozen into tokens, so it must not be reused.
- **i18n:** core's EN+DE catalog is `src/web/src/i18n/index.ts` (`shell:`). Memories has its own `features/memories/i18n.ts`.

## Design

### Core: `src/web/src/core/search/`

- **The document:**
  - **Fields:** `{ scopeId, kind, id, revision, name, path, facets }`.
  - **Facets:** `{ years?: number[] | {from, to}, places?: string[], people?: string[] (user ids), capturedAt?, ... }`.
    The implementer settles the exact shape.
  - **Kinds:** a node document comes from core for every node. An application contributes facets for its own kinds,
    and documents of its own (photos) for the open node.
- **The index:**
  - It lives in memory, one entry per scope: `{ etag, documents }`. It is built lazily on the first focus.
  - A refresh on later focus walks every scope with its ETag. A `304` keeps the entry, a moved tree rebuilds it, and
    a failure drops it and marks the scope "not searched".
  - The active scope and the open node are built first.
  - A generation counter abandons a superseded build, as `participantIndex` does.
  - Sign-out clears it.
  - It never writes any storage.
- **The registry hooks** (names are the implementer's):
  - **A per-kind `search` hook.** Core calls it while the scope's keys are open, with the node, its key and the
    parent key; it returns the kind's facets.
  - **A contribution door for the open node's own documents,** in the shape of `contributeNodeHeader`. A detail
    pushes documents while it is mounted, and they are withdrawn when it unmounts.
  - Memories implements both. The search hook gives a Memory's date range, place name and participant ids. The
    contribution gives `MemoryDetail`'s opened listing as photo and video documents with capturedAt and placeName.
- **The query:**
  - A query is `{ chips: Chip[], text }`. Chip kinds: `space`, `year`, `place`, `person`, `kind`.
  - A suggestion is derived from the typed token against the index:
    - four digits make a year;
    - a person is a prefix match on a directory name;
    - a place is a prefix match on any document's place;
    - a space is a prefix match on a scope name;
    - a kind matches a registered kind's label.
- **Matching and ranking:**
  - A document must satisfy every chip, and the folded remaining text must match its name or its place.
  - Within a group: an exact name, then a prefix, then a word-start, then a substring, then a place match. Ties are
    broken by the name's collation.
- **Groups:**
  - *In this Memory* holds the documents of the open node's subtree and its contributed documents.
  - *In this space* holds the rest of the active scope.
  - *Other spaces* holds everything else; each result carries its scope's name and its path.
  - An empty group is not rendered. The groups are capped by count, with a "show more" per group.
- **Folding:** one `shared/text/fold.ts` (NFD, strip marks, lower-case, ß→ss). The two inline copies move onto it.

### UI

- **The field:**
  - The topbar's centre column comes back with the field. On compact widths it collapses to an icon button that opens
    the field in a sheet.
  - A shortcut focuses it: `/` and `Ctrl/⌘+K`, ignored while an input is focused.
  - It is built on Reka `Combobox` plus `TagsInput`, extending or reusing `VeyqTagsInput` where it fits.
- **The results:** a popover (a sheet on compact widths) with the three groups.
  - Each row shows the kind's icon, the name, a secondary line (path, plus the space for *Other spaces*; the capture
    date for a photo) and the matched chip values.
  - Keyboard navigation follows the combobox.
- **The states:**
  - "Searching N spaces…" while trees load.
  - Spaces that could not be searched are named.
  - The empty state says what is searched, and that photos are searched only in the open Memory (uc E1, A2).
- **Opening a result:**
  - A node opens through the existing navigation. It switches the active space when needed, as the sidebar does.
  - A photo opens the open Memory's lightbox.

## Inventory (locators — run them, the counts are not frozen)

| Where | Locator |
|---|---|
| Hooks, door, registry tests | `rg -n "contributeNodeHeader\|cover\?:\|rekeyContent" src/web/src/core` |
| Memories' meta readers | `rg -ln "unsealMemoryLocation\|unsealMemoryParticipants\|unsealNodeDate" src/web/src` |
| Tree opening | `rg -n "export (async )?function (fetchTree\|openTree\|openScopeById\|zeroScopeKey)" src/web/src` |
| Folding copies | `rg -n "normalize\('NFD'\)" src/web/src` |
| Topbar grid | `rg -n "grid-template-columns" src/web/src/styles/layout.css` and `AppTopbar.vue` |
| Shell i18n | `rg -n "shell:" src/web/src/i18n/index.ts` |
| Guard on core → application | `scripts/validate_web_core_imports_no_applications.py` |
| Site pages | `git grep -ln "uc.platform" -- site/src/content/docs` (a new page joins them) |

## Phases

### Phase 1 — the search model in core

- **Build:** `core/search/` (documents, index, query, ranking, groups), `shared/text/fold.ts` (the two copies moved
  onto it), and the registry's search hook and contribution door, with no application implementing them yet.
- **Tests:** unit tests beside each module:
  - chip derivation;
  - every chip's predicate, including year overlap with a Memory's range;
  - ranking order;
  - grouping;
  - ETag revalidation (304 keeps, moved rebuilds, failure drops and names the space);
  - the generation abort;
  - keys zeroed after a build (spy on `zeroScopeKey`);
  - sign-out clears.
- **A negative with its control:** no `localStorage`/`sessionStorage`/`indexedDB`/`caches` write during a build and a
  query (spies), with a positive control proving the spies see a write.
- **Done when:** `pnpm run check` is green in `src/web/` and the core-import guard is green.

### Phase 2 — Memories contributes

- **Build:**
  - Memories implements the per-kind hook for `MEMORY` (date range, place, participants) and, where it makes sense,
    for `CHAPTER` (its date).
  - `MemoryDetail` contributes the opened listing's photos and videos.
  - Person chips resolve through core's directory.
- **Tests:** unit tests for both contributions. A photo document's capturedAt and place come from `openedMeta`. The
  contribution is withdrawn on unmount.
- **Done when:** `pnpm run check` is green and the guard is green.

### Phase 3 — the field

- **Build:**
  - the topbar field and the compact sheet;
  - the shortcut;
  - the popover with its groups, states and opening;
  - EN+DE strings in the shell catalog, and Memories' kind words in its own;
  - `ui-web-styleguide.md` followed (read it).
- **Tests:** component tests for the field (chip from suggestion, removal, keyboard), the groups and the empty state.
  A guest/plain layout renders no field.
- **The browser pass on the separate stack** (below), at a desktop width and at a phone width:
  - a Memory found by name, by a year chip and by a person chip in another space;
  - opening it switches the space;
  - a photo found in the open Memory, opening in the lightbox;
  - a rename picked up by the tree poll;
  - nothing in the console.

  Screenshots of the final state only.
- **Done when:** `pnpm run check` is green, the browser pass is done, and the screenshots are saved under the
  scratchpad.

### Phase 4 — the docs move with it

- **Coverage:**
  - `uc.platform.search.yaml`'s web gap becomes refs, criterion by criterion; the backend gap stays.
  - Drop `status: planned` once every criterion has a ref.
- **Statuses:**
  - `uc.platform.search`'s Status becomes "built (web)" with the browser pass's date.
  - The `_index.md` entry follows.
  - ADR 0125's `Built:` becomes `partly (not built: decision 8)`.
  - Inbox #21 names what is left (8a, 8b, 8c).
- **The site:**
  - A new Use page (EN+DE, at the depth of its neighbours, e.g. `spaces/search.mdx` and its `de/` twin) names
    `uc.platform.search` in `sources:` and uses the UI's own words.
  - The use case links it under `## Related`.
- **Retrospective:** filled, and this CR is deleted once its lessons are moved where they bind.
- **Done when:** the trust-chain guards are green (`for f in scripts/validate_*.py`, except `validate_guard_gaps.py`,
  which is skipped because no guard changed), using `/usr/bin/python3`.

## The separate stack for the browser pass

The owner, 2026-11-08: a new stack with its own database and Garage; the owner's running stack is not touched.

- **The project:** a compose project of its own, from a copy of `infra/docker/docker-compose.yml` placed outside the
  repository (scratch), with:
  - its own `name:`;
  - its own `container_name`s;
  - its own volumes;
  - every host port moved;
  - `--profile s3` (MariaDB plus Garage; Traefik only if the store path needs it).
- **Never** run `infra:*`, `scripts/run_web_e2e.py` or the main checkout's compose file: those recreate the owner's
  containers (`infra/README.md` § The launcher).
- **The backend and web:**
  - Started from this worktree (its own `build/`), with ports moved through the worktree's untracked
    `local.properties` (`infra/README.md` § A second instance from a worktree).
  - The database and bundled-store env point at the new stack's ports.
  - `VEYQ_BLOB_STORE_PUBLIC_ENDPOINT`, or the launcher's derivation, is the origin actually browsed.
- **Seeding:**
  - `backend:seed` / `pnpm run bootstrap:user` against it, with a principal of the worktree's own (`.run/.env` in the
    worktree, never the main checkout's).
  - A second space (a group) with a Memory and a photo, so the cross-space pass has something to find.
- **Afterwards:** stop the backend and Vite by PID. Leave the stack up for the owner to look at, and write how to
  reach it and how to remove it (`docker compose -p <name> down -v`) into the retrospective.

## Retrospective

(filled by the implementer)
