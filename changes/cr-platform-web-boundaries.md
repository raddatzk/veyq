---
id: cr-platform-web-boundaries
title: Platform phase 2 — the web core stops importing the Memories application
status: planned
satisfies: [en.memory.tree, en.memory.favorites, uc.sharing.share-public-viewer]
adrs: ["0079", "0068", "0072", "0047", "0026"]
invariants: [operator-blind E2EE everywhere, self-hostable by default, API-first]
---

## Open for the owner

1. **How far does this CR draw the seam around the folder level and the node dialogs?** Three pieces of the tree
   host are Memories-shaped as written and have one user: the level view (`MemoryBrowse`: date sort, covers, the
   space map, `MemoryCard`), the create/rename dialog (`NodeMutationDialog`: `create-memory` pre-arms a place) and
   the share dialogs. **Recommendation: single-claim slots now, per-kind composition with the second
   application.** The registry holds them as slots that exactly one application may claim (a second claim throws),
   and says so in its header. The alternative — splitting `MemoryBrowse` into a folder level plus per-kind sections
   today — would freeze an abstraction from one usage, which `docs/ai/README.md` § Refinement checklist ("Spike
   when the shape is unclear") tells us not to. The cost of the recommendation: the next application's CR re-cuts
   three slots.
2. **A root kind for an UPLOADER share.** `ShareView` must dispatch on the share's root kind. Viewer and contributor
   responses carry it (`ShareNode.kind` on the node with `parentId: null`); an unlocked UPLOADER response serves no
   nodes (`PublicShare.nodeId` only), so its root kind is unknown to the client. This CR may not touch `contracts/`.
   **Recommendation:** an uploader share dispatches to the one application that claimed the kind-less drop share
   (registration refuses a second claim), and a small follow-up CR adds `rootKind` to `PublicShare` (contracts →
   backend → web) before a second shareable kind exists. Accepting that follow-up, or doing it first, is the
   owner's call.
3. **Rename `features/memory` to an application directory in this CR?** **Recommendation: no.** This CR draws the
   boundary by import direction and keeps the directory name. The rename is ~120 files plus every coverage ref that
   names them, and it changes no import direction; the guard's application list is one constant, so renaming later
   is mechanical. The backend's phase 1 made the same call (`features.media` stayed where it was).
4. **A web guard in Python or in ESLint?** **Recommendation: Python** (step 1, reasons under Decisions). Say so if
   editor feedback in the IDE matters more to you than the guard sitting in the buildless trust-chain.

## Goal

[ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) makes Memories the
first application of a platform and says an application depends on core, never the reverse. The backend half is
drawn ([`cr-platform-core-boundaries`](cr-platform-core-boundaries.md)); the web half is not: `app/`,
`components/app/`, `sw/`, `views/` and three core features import `features/memory` directly, and the shell
branches on `NodeKind.Memory`. This CR is that restructuring's **web phase: make the import direction true, with
no behaviour change.**

After it, nothing outside the application imports the application except one named composition root; the shell
asks a build-time registry what to render for a node kind, which slot an application fills, and what runs when a
scope is entered or the master key rotates. A guard keeps it that way, and its bite-test proves it can go red.
The proof is the existing suite on the one application that exists, repointed — no new behaviour to confound a
failure. A new node kind, a contract change and the Gradle split are other CRs.

## State (measured 2026-10-04, branch `worktree-agent-aafee91c6cfb69a72` @ `9c972497`, tree clean)

All numbers come from the commands in § Inventory unless a command is named here. `web/node_modules` is absent in
the refine worktree, so **no `npm` command was run**: the import edges were read with `grep` and a throwaway
resolver that follows relative specifiers (all of `web/src` uses relative imports; no alias exists — `grep -rn "from '@/"
web/src` is empty).

- **`features/memory` is 120 files, 56 of them tests** (`find web/src/features/memory -type f | wc -l`; `-name
  '*.test.ts'`). It holds two things under one name: the Memories application (media, derivatives, upload queue,
  gallery, covers, date/place curation, map, item favorites) and the platform's node layer (`tree.ts`,
  `treeStore.ts`, `location.ts`, `browse.ts`) plus the sharing manage/resolve code (`share.ts`,
  922 lines).
- **Reverse imports into `features/memory` from outside it: 14 non-test files, 3 directories of the shell plus 3
  features** (row 1). By directory: `app/` 3 files (5 imports), `components/app/` 2 (7), `views/` 2 (21),
  `features/spaces` 4 (4), `features/account` 2 (3), `sw/` 1 (1); plus `src/test/models.ts` (types, exempt).
  **19 test files outside the application import it too**, 10 of them through `vi.mock` or `importOriginal`
  (row 2; the 10 is `grep -rlE "vi\.(mock|doMock)\('[./]*(features/)?memory/|importOriginal<typeof import\('[./]*(features/)?memory/" web/src --include='*.test.ts'` minus the application's own) — the `web/CLAUDE.md` rule that a moved module silently disarms every mock of it applies to every step
  that moves a module.
- **The application's own outbound edges are fine and numerous** (it may use core): `api/` 20, `app/` 14, `shared/`
  42, `features/spaces` 13, `sw/` 2 imports (non-test). Two cycles exist today because of the reverse edges:
  `app/activeSpace` ↔ `features/memory/treeStore`, and `features/spaces` ↔ `features/memory` (`share`,
  `mediaDeriver`). Both disappear when the reverse edges do.
- **The shell names Memories kinds in four places**: `AppSidebar.vue` (favorites filter on `NodeKind.Memory`),
  `MemoryHomeView.vue` (openable = `Memory || Chapter`, shareable = `Memory`, folder = `Folder`) (row 3). Inside the
  application the kinds are named freely, which is the point of an application.
- **`childrenOf` returns the fixed triple `{ folders, memories, chapters }`** (`features/memory/browse.ts`), which is
  why `MemoryHomeView` cannot be kind-blind.
- **`ShareView.vue` is 805 lines and is one Memory viewer**: it imports 10 modules from `features/memory`
  (`share`, `media`, `uploadQueue`, `useOptimisticTiles`, `optimisticUpload`, `useThumbnailPipeline`,
  `mediaGridLayout`, `MediaGrid`, `OptimisticTile`, `MediaLightbox`). Its shell half (loading / unavailable /
  password / terms gates) is kind-independent; its name pre-screen, grid, upload picker and lightbox are not.
  `resolveShare` reads `share.nodes` for keys but never a node's `kind` (`grep -n "\.kind" web/src/features/memory/share.ts`
  finds only access, derivative and tile kinds).
- **The backend guard states the web counterpart cannot be born green** (`scripts/validate_shared_imports_no_features.py`
  docstring, last paragraph: `shared/ui/SecretKeyField.vue` → `features/account/`). That is a *different* edge
  (shared → a core feature, not core → application); this CR's guard does not look at it, and says so.

## Context & constraints

- **Hard invariants, unchanged.** Operator-blind E2EE: nothing here changes what is sealed, what is sent or what
  the server sees. **No contract, crypto or migration change** — `git diff main -- contracts/ packages/crypto/` is
  empty. No seal purpose or AAD string is touched; moved crypto modules keep their bytes (the existing tests are
  the proof, no new vectors).
- **Build-time only** ([ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  Decision 5, [ADR 0026](../docs/decisions/0026-client-trust-tiers.md)). The registry is filled by a static import
  in one composition root. No `import.meta.glob`, no dynamic `import()` by computed specifier, no plugin loading:
  what runs is what was reviewed. The guard forbids `import.meta.glob` in core so discovery can never hide an edge.
- **The registry is not a convenience, it is a trust surface.** Registered code runs with every unlocked key. Hence
  the registry is written once (a second registration of the same kind, slot or hook id throws), is not exported
  as mutable state, and is filled before `boot()` resolves its first route.
- **Fail closed.** A node kind with no registration renders an explicit unsupported state, never an empty region. A
  lifecycle hook that carries key-dependent work (master-key rotation reseal) is *required*: see step 7.
- **Behaviour-neutral.** Same bundle shape (the host mounts the application's components synchronously, as it
  imports them today — no new async chunk), same DOM, same URLs, same i18n keys. The i18n catalog stays one file
  and `styles/memory.css` stays global: both are out of scope and named as limits.
- **Moving a module disarms its mocks silently** (`web/CLAUDE.md`). Every move step proves the new mocks bite (see
  each step's bite). `vi.mock` strings are paths: a mock left on the old path stubs nothing.
- **Python 3.9 on the dev Mac**: the new guard starts with `from __future__ import annotations`.
- **Out of scope.** Renaming `features/memory` (Open 3). `admin-web/` (its own package, no application). Any new
  node kind; opening `NodeKind` to values core does not interpret (contract + generated client). The Gradle split.
  Splitting `MemoryBrowse` / `NodeMutationDialog` / the share dialogs per kind (Open 1). `shared/ui/SecretKeyField`
  → `features/account` (a different edge).

## Decisions

1. **Where core and the application live.** Application = `web/src/features/memory/**` (one constant in the guard).
   Core = everything else under `web/src` — `app/`, `shared/`, `sw/`, `i18n/`, `views/`, `components/app/`, every
   other `features/*`, the root files — plus a new **`web/src/core/`** that receives what is platform but filed
   under `memory` today (`core/node/`, `core/applications/`, `core/sharing/`). Not moving `app/` and `shared/` into
   `core/` keeps this a behaviour-neutral import-direction change; the guard defines core by exclusion, so a new
   directory is core by default and cannot forget to be guarded. `web/CLAUDE.md` gets the map (step 9).
2. **One composition root.** `web/src/applications.ts` is the only non-test file outside the application allowed to
   import it. `main.ts` calls its `registerApplications()` before `boot()`; `src/test/setup.ts` calls it for every
   suite. A root file the guard exempts *by exact path* is the one hole this design opens; the bite-test names it
   (a sibling file importing the application is still red).
3. **The registry (`core/applications/registry.ts`) carries only what a current core call site needs**, found by
   § Inventory rows 3–8, and nothing speculative:
   - a **kind descriptor** per node kind: `{ kind, detail, icon, shareable }` where `detail` is the component the host
     renders for an openable node and absent means "not openable" (replaces `isOpenable`, `shareable`, the trash
     icon in `SpaceTrashDialog`, and the kind filter in `AppSidebar`);
   - **single-claim slots**: `levelView`, `nodeMutationDialog`, `shareDialog`, `shareBody` (per root kind),
     `topbarTray`, `sidebarPins` — claimed once, a second claim throws (Open 1 and 2 say why);
   - **lifecycle hooks**: `onScopeEntered(scopeId)`, `onScopeReset()`, `onMasterKeyRotated(old, new)`.
   The detail/level components receive a **generic context** (`node`, `scopeId`, `epochs`, `contentKey`, the child
   buckets, `readonly`) and the application registers a thin adapter that maps it to the props `MemoryDetail` /
   `MemoryBrowse` already take. Those two (1021 and 212 lines) are not edited, which is what makes the step
   reviewable.
4. **`childrenOf` returns buckets keyed by kind**: `{ folders: TreeNode[]; byKind: ReadonlyMap<NodeKind, TreeNode[]> }`,
   each bucket ordered exactly as today (`orderIndex`, then name). `folders` stays a named field because `FOLDER` is
   the platform's; core code reads no other key by name. The Memories adapter reads `byKind.get(NodeKind.Memory)`.
   The type stays the generated closed `NodeKind` (opening it is a contract CR); the *convention* "core never names
   `NodeKind.Memory|Chapter`" is what the guard enforces.
5. **The guard is Python, a ratchet, and also reads kind names.** `scripts/validate_web_core_imports_no_applications.py`:
   (a) no file under `web/src` outside the application and outside the exempt set (`src/applications.ts`, `src/test/**`,
   `*.test.ts`) names the application by a relative specifier in `from '…'`, `export … from '…'`, `import('…')`;
   (b) no such file contains `import.meta.glob`; (c) no core file outside tests names `NodeKind.Memory`,
   `NodeKind.Chapter`, `'MEMORY'` or `'CHAPTER'`. Today's violations go in an **explicit allowlist** (per file and
   per rule), so the guard is green the day it lands (the backend guard was; a plain web guard would be red — State,
   last bullet) and every step deletes the entries it removes. A stale entry (listed, no longer violating) is red,
   so the list can only shrink; step 9 deletes it and asserts it is empty. Why not ESLint `no-restricted-imports`:
   the config already uses `no-restricted-imports` (`veyq/one-sign-out`, `veyq/tests-use-the-real-catalog`) and
   `@typescript-eslint/no-restricted-imports` (`veyq/trust-reset-one-door`) on overlapping file sets, and flat
   config resolves a rule by name with the last block winning — a third block under either name silently replaces
   one of them (the config's own comment at the trust-reset block says so); a path-regex pattern cannot tell which
   directory a *relative* specifier reaches from the importing file (`eslint/featureMocks.js` documents the same
   limit and wrote a local rule for it, inbox #286); and a ratchet needs a stale-entry check ESLint has no form
   of. A local ESLint rule remains possible later; the Python guard runs in the buildless trust-chain (`scripts/validate_*.py`
   glob) where docs-only and script-only changes are validated.
   Tests stay out of the guard for the backend guard's reason (a test is a consumer of both sides).
6. **Favorites live with Memories.** The backend already counts both favorites packages as the Memories application
   (`features.content.favorites|itemfavorites` are in the core-import guard's application list), so the web stores
   (`app/favoritesStore.ts`, `app/itemFavoritesStore.ts`, `features/memory/favorites.ts`, `itemFavorites.ts`) are
   the application's. The shell reaches them only through `sidebarPins`, `onScopeEntered/Reset` and
   `onMasterKeyRotated`.

## Inventory (measured at refine; each row is a command — re-run it, do not trust the prose)

All paths relative to the repo root. **Per `changes/README.md`, the first implement action is to spot-check the row
this CR leans on hardest: row 1, because every step's allowlist deletion and step 9's "empty" rest on it.**

| # | Fact | Locator (run it) |
|---|---|---|
| 1 | The non-test reverse imports into the application (the sites this CR removes). Includes `src/test/models.ts`, which is exempt | `grep -rnE "from '[./]*(features/)?memory/\|import\('[./]*(features/)?memory/" web/src --include='*.ts' --include='*.vue' \| grep -v "^web/src/features/memory/" \| grep -v "\.test\.ts:"` |
| 2 | The test files outside the application that import it (incl. `vi.mock`/`importOriginal`) — every one needs its mock path checked after a move | `grep -rlE "from '[./]*(features/)?memory/\|import\('[./]*(features/)?memory/\|vi\.(mock\|doMock)\('[./]*(features/)?memory/" web/src --include='*.test.ts' \| grep -v "^web/src/features/memory/"` |
| 3 | Core code that names a Memories kind | `grep -rnE "NodeKind\.(Memory\|Chapter)\|'MEMORY'\|'CHAPTER'" web/src --include='*.ts' --include='*.vue' \| grep -v "generated\|\.test\.ts\|^web/src/features/memory/\|^web/src/test/"` |
| 4 | `childrenOf`'s consumers (the fixed triple is read here) | `grep -rn "childrenOf\|level\.\(folders\|memories\|chapters\)" web/src --include='*.ts' --include='*.vue'` |
| 5 | What the tree layer imports inside the application — the node layer's move closure; `tree.ts` reaches `nodeMeta` and `memoryLocation` only through `createMemory` | `grep -nE "from '\./" web/src/features/memory/{tree,treeStore,location,browse,coverStore}.ts` |
| 6 | The generic-vs-Memories split inside `tree.ts` (`createContentNode` is generic; `createMemory`/`createChapter` are kind wrappers) | `grep -nE "^export\|createContentNode" web/src/features/memory/tree.ts` |
| 7 | The shell slots: who mounts the upload tray and the pinned rail | `grep -n "UploadStatusMenu" web/src/components/app/AppTopbar.vue` ; `grep -n "favoritesStore\|coverStore\|MemoryCoverImage" web/src/components/app/AppSidebar.vue` |
| 8 | Lifecycle edges into the application: scope entry/reset (`resetCovers`, `loadTree`, `resetTree`, favorites load) and master-key rotation | `grep -n "resetCovers\|resetTree\|loadTree\|loadFavoritesFor" web/src/app/activeSpace.ts web/src/views/MemoryHomeView.vue` ; `grep -n "resealFavorites\|retagItemMarks" web/src/features/account/rotation/rotateKeys.ts` |
| 9 | Small pure relocations: `saveBytes` (account → `memory/download`), `revokeScopeShares` (spaces → `memory/share`; it calls only `scopesApi`), the image deriver (spaces icon → `memory/mediaDeriver`), `ChunkProvider` (a type `sw/register` takes from `memory/media`) | `grep -rn "memory/download\|revokeScopeShares\|memory/mediaDeriver\|ChunkProvider" web/src --include='*.ts' --include='*.vue' \| grep -v "^web/src/features/memory/"` |
| 10 | The worker path other tooling names: `new URL('./mediaDerive.worker.ts', import.meta.url)` and the dist assertion that keys on the chunk name | `grep -rn "mediaDerive" web/scripts web/src/features/memory/mediaDeriveWorker.ts` |
| 11 | Share resolution mixes kind-independent work (fetch, password token, `K_share`, name/note, errors, terms) with Memories work (`K_mem`, media rows → `MediaItem`); the root kind is on `ShareNode` and absent for an uploader | `grep -nE "^export" web/src/features/memory/share.ts` ; `grep -n "nodes\b" web/src/features/memory/share.ts` ; `grep -n "ShareNode:" -A22 contracts/api/sharing.openapi.yaml` |
| 12 | `ShareView.vue`'s state machine: the order password → terms → name → content, and the four test files that pin it | `grep -n "v-else-if\|v-if=\"status" web/src/views/ShareView.vue` ; `ls web/src/views/ShareView*.test.ts` |
| 13 | Coverage refs that cite a test this CR moves | `grep -rn "features/memory/\(tree\|location\|browse\|favorites\|itemFavorites\|download\|share\|mediaDeriver\)\|app/\(favoritesStore\|itemFavoritesStore\)\|views/ShareView" docs/coverage` |
| 14 | Other tooling that names a moved path | `grep -ln "features/memory" scripts/*.py web/stryker.mutate.json web/vite.config.ts web/scripts/*` ; `grep -n "nodeMeta\|features/memory" web/stryker.mutate.json \| cut -c1-80` |
| 15 | The existing ESLint rule names on overlapping file sets (why Decision 5 is not an ESLint block) | `grep -n "'no-restricted-imports'\|'@typescript-eslint/no-restricted-imports'" web/eslint.config.js` |

## Phases

One commit per step, each ending with `npm run generate:api && npm run check` in `web/` (the client is untracked and
per-worktree — `web/CLAUDE.md`) plus the trust-chain guards its Done-when names. **Confirm the commit path works
before step 1** (one throwaway commit). The sequence is the allowlist's order: each step deletes the entries it
removes, and the guard is the completeness proof for that step.

**Ordering.** Step 1 first. Steps 2 and 3 touch disjoint files and may run in parallel. Step 4 needs 3. Steps 5, 6, 7
and 8 need 4 and are independent of each other, but all four edit `core/applications/registry.ts` and the guard's
allowlist: run them serially, or accept a mechanical conflict on those two files. Step 9 last.

- [ ] 1 the guard, as a ratchet — write `scripts/validate_web_core_imports_no_applications.py` (Decision 5). Its
      allowlist is generated from rows 1 and 3 on the untouched tree, per file and rule. The docstring keeps its
      three fields (asserts / the defect that bought it / its limit); the limit names: tests and `src/test/` are not
      read; `shared/ui/SecretKeyField` → `features/account` is a different edge; regex over source, not an AST, so
      a commented-out import is read as an import (conservative) and a specifier built at runtime is not seen. Update
      the backend guard's closing paragraph (it says the web side has no counterpart). Register forged-file cases in
      `scripts/validate_guard_gaps.py` `CASES` (closed hole, last column `False`).
      · **Bite-test (per bite, lever named):** (a) a forged `web/src/shared/ui/Probe.vue` importing
      `'../../features/memory/tree'` turns it red, the same file importing `'../../features/account/trustReset/trustReset'` stays
      green (not this guard's subject); (b) a forged `web/src/applications2.ts` importing the application is red —
      the composition-root exemption is by exact path, not by directory (create the root in step 4; until then forge
      against the path the guard will exempt); (c) `export * from` and `import('…')` are each red; (d) delete one
      allowlist entry whose file still violates → red; keep an entry whose file no longer violates → red; (e) a
      core file containing `NodeKind.Memory` is red, `NodeKind.Folder` green; (f) a core file with
      `import.meta.glob(` is red. Run the predicate on the untouched tree first and read it green (`changes/README.md`).
      · Done when: `python3 scripts/validate_web_core_imports_no_applications.py` and `python3 scripts/validate_guard_gaps.py`
      green; the allowlist equals row 1 ∪ row 3 minus `src/test/`.
- [ ] 2 pure relocations out of the application, no new abstraction (row 9, 10) — `saveBytes` to `shared/`;
      `revokeScopeShares` to `features/spaces/scopes.ts`; the deriver family (`mediaDeriver`, `mediaPixels`,
      `mediaDeriveWorker`, `mediaDerive.worker.ts` with their tests) to `shared/image/`, **keeping the worker's file
      basename** so the dist chunk name and `web/scripts/assert-media-worker-in-dist.mjs` still match (only its
      comment path changes); `ChunkProvider` moves to `sw/protocol.ts` (the SW defines the port it is served
      through, media implements it — the dependency inverts). The application imports the new homes. Deletes the
      allowlist entries of `emergencyKit.ts`, `GroupMembersPanel.vue`, `TenantMembersPanel.vue`, `SpaceIconDialog.vue`,
      `spaceIconImage.ts`, `sw/register.ts`.
      · **Bite-test (mocks, `web/CLAUDE.md`):** make each moved export throw; every suite that mocks it stays green
      and the module's own test goes red (`GroupMembersPanel.test`, `TenantMembersPanel.test` mock `revokeScopeShares`).
      · Done when: `npm run check` green (this includes `verify:media-worker`); the guard green with six fewer entries;
      row 13/14 refs repointed (`python3 scripts/validate_coverage.py` green).
- [ ] 3 the node layer to `core/node/` — `tree.ts` (without `createMemory`/`createChapter`), `treeStore.ts`,
      `location.ts`, `browse.ts`, the `TreeNode` type, with their tests. `createContentNode` is exported from
      `core/node`; the kind wrappers `createMemory`/`createChapter` and their `nodeMeta`/`MemoryLocation` use stay in
      the application (`features/memory/memoryNodes.ts`). `coverStore.ts` stays (a Memories concept). Repoint imports,
      including `vi.mock` strings (row 2).
      · **Bite-test:** make `loadTree` throw in `core/node/treeStore.ts`; `activeSpace.test.ts` (which mocks it) stays
      green and the store's own test goes red; same for `location`.
      · Done when: `npm run check` green; the guard green, entries for `activeSpace.ts` reduced to the `resetCovers`
      edge and `AppSidebar.vue`/`MemoryHomeView.vue` reduced to what steps 5–6 own; `docs/coverage` refs for `tree`,
      `location`, `browse` repointed; `web/stryker.mutate.json` still lists a file that exists
      (`python3 scripts/mutation_meta_check.py` needs a built report — run `validate_coverage` and read the manifest
      by hand instead, and say so in the commit).
- [ ] 4 the registry and the composition root — `core/applications/registry.ts` (types + register/lookup, write-once,
      unsupported-kind fallback), `web/src/applications.ts` (`registerApplications()`), `main.ts` and
      `src/test/setup.ts` call it; Memories registers its kinds with **no slot filled yet**. The registry's header
      states the build-time rule (Decision 3) and the single-claim rule.
      · **Bite-test:** register the same kind twice → throws; look up an unregistered kind → the explicit unsupported
      result, never `undefined` reaching a template; the composition root is the only exempt file (step 1 bite b).
      · Done when: `npm run check` green with the suite unchanged; registry unit tests green; the guard green.
- [ ] 5 kind→detail resolver and generic buckets (Decision 3, 4) — `childrenOf` returns `{ folders, byKind }`;
      `MemoryHomeView` (a core view, name kept) renders `registry.detailFor(node.kind)` for an openable node, the
      `levelView` slot otherwise, and opens the `nodeMutationDialog` / `shareDialog` slots; `shareable` comes from the
      descriptor plus the existing capability check. `DeleteNodeDialog` and `SpaceTrashDialog` move to
      `core/node/components/` (they name `Folder` only; the trash icon comes from the descriptor). The Memories
      adapters (`MemoryDetailHost`, `MemoryLevelHost`) map the generic context to `MemoryDetail`/`MemoryBrowse`
      props. Deletes the `MemoryHomeView.vue` entries (both rules).
      · **Bite-test:** register no detail for `CHAPTER` → opening a chapter shows the unsupported state, and
      `MemoryHomeView`'s test goes red (it opens one); leave `byKind` empty → the gallery test goes red. Order within a
      bucket is pinned by `browse.test.ts` (carried, not weakened).
      · Done when: `npm run check` green; `MemoryHomeView` and `MemoryBrowse`/`MemoryDetail` tests unchanged apart from
      mount wiring; the guard green with the `MemoryHomeView` entries gone.
- [ ] 6 shell slots — `AppTopbar` renders `registry.topbarTray` (the upload tray), `AppSidebar` renders
      `registry.sidebarPins` (the pinned Memories with covers: the favorites filter, `coverStore`, `MemoryCoverImage`
      move into a Memories `PinnedMemories` component). Deletes `AppTopbar.vue` and `AppSidebar.vue` entries.
      · **Bite-test:** an application that fills no slot renders the shell without the tray and the rail (no throw,
      no blank gap that shifts layout) — a test for the empty case; leave `sidebarPins` unclaimed in the real
      composition root → the `AppSidebar` test that asserts the pinned rail goes red.
      · Done when: `npm run check` green; `AppSidebar.test`/`AppTopbar.test` repointed, not weakened; guard green.
- [ ] 7 lifecycle hooks and the favorites stores — `app/activeSpace.enterScope` calls the registry's
      `onScopeReset`/`onScopeEntered` (Memories: `resetCovers`, load favorites); `rotateKeys` calls `onMasterKeyRotated`
      (Memories: `resealFavoritesUnderNewMasterKey`, `retagItemMarksUnderNewMasterKey`). `app/favoritesStore*` and
      their tests move to the application. **`onMasterKeyRotated` is required, not optional:** `rotateKeys` refuses
      to start when the composition root has not registered it, because favorites are sealed under the master key and
      a rotation that skips them leaves them undecryptable. Deletes `activeSpace.ts`, `favoritesStore.ts`,
      `itemFavoritesStore.ts`, `rotateKeys.ts` entries.
      · **Bite-test:** drop Memories' rotation registration → `rotateKeys` refuses and its test goes red (the reseal is
      asserted to be *called* with the real registry, not through a mock of it); drop the reset hook → the covers
      test that asserts a scope switch clears them goes red (a negative needs its live control: the same switch with the
      hook registered clears).
      · Done when: `npm run check` green; `docs/coverage` refs for the two store tests repointed.
- [ ] 8 `ShareView` shell and dispatch (Open 2, row 11, 12) — `core/sharing/resolvePublicShare.ts` takes the
      kind-independent half of `resolveShare` (fetch, password token, `K_share`, sealed name/note, the three error
      classes, terms, `pickLocaleText`) and returns the root kind from the `parentId: null` node (null when none);
      `views/ShareView.vue` keeps the route and the loading / unavailable / password / terms gates and renders
      `registry.shareBodyFor(rootKind)`; the Memories body takes the name pre-screen, the grid, the picker and the
      lightbox and calls `openSharedMemory` (the Memories half of `resolveShare`). The gate order password → terms →
      name → content is unchanged. Deletes the `ShareView.vue` entries.
      · **Bite-test:** a share whose root kind has no registered body → the unavailable state, not a blank page (a
      test with a live control: the same share with the body registered renders); swap terms and name in the shell
      → `ShareView.order.test.ts` goes red (it pins the order). Mocks in the four `ShareView*.test.ts` files repoint.
      · Done when: `npm run check` green; the web e2e suites that open a share (`shareContributor.e2e`,
      `shareUploader.e2e`) green in the `web-e2e` job — they need the live stack, so they are not part of
      `npm run check`; `docs/coverage` refs repointed.
- [ ] 9 close — the allowlist is deleted from the guard and the guard asserts it is empty; row 1 returns only
      `src/test/models.ts`; row 3 returns nothing.
      · Done when (from the repo root): (a) row 1's command, minus `src/test/`, is **empty**; (b) row 3's command is
      **empty**; (c) the guard has no allowlist and is green; (d) `git diff main -- contracts/ packages/crypto/` is
      empty.

## Definition of done

- **The enforcement exists and is bitten.** The web core-import guard (step 1) is green on the final tree with no
  allowlist, and every bite named in the steps was run once red and recorded in its commit message.
- **Behaviour is unchanged.** `npm run generate:api && npm run check` green in `web/` with **no test deleted or
  weakened** — account the suite per assertion where a test moved, not by count (`changes/README.md`). The built
  bundle has the same chunk set apart from the moved files' names (`ls web/dist/assets` before/after, read as a list),
  `verify:media-worker` included. The `web-e2e` job is green on the merged tree.
- **No contract, crypto or migration change.** `git diff main -- contracts/ packages/crypto/ backend/` is empty.
- **The trust chain.** `for f in scripts/validate_*.py; do python3 "$f"; done` green on the merged tree
  (the coordinating session runs it; each step runs the guards its Done-when names).
- **Coverage.** No acceptance criterion changes; every coverage ref that cited a moved test is repointed
  (`python3 scripts/validate_coverage.py`).
- **Docs.** `web/CLAUDE.md` gets the core / application map and the registry rule (what a new application registers);
  `docs/crosscutting/ui-web-styleguide.md` § "Where a component lives" names `core/` and the application;
  [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences and
  [`docs/apps/README.md`](../docs/apps/README.md) / [`memories.md`](../docs/apps/memories.md) say the web side is
  separated by import direction and what is not (directory name, the three single-claim slots, the root kind of an
  uploader share); inbox #324's status text names this CR until the last CR of the series lands.
- **Retrospective** written, and the CR deleted after what it taught moved to where it binds (`changes/README.md`).

## Retrospective

_(filled in at close)_
