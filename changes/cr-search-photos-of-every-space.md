---
id: cr-search-photos-of-every-space
title: The search finds every photo and video of every space by its capture year, place and name
status: done
satisfies: [uc.platform.search]
adrs: ["0125", "0033", "0079", "0105", "0051"]
invariants:
  - the server learns only that a member read every media listing of a scope (ids, times); nothing about a query (ADR 0125 decisions 1, 8a)
  - search holds no key past a space's walk; the listing is opened while the walk holds the node keys (ADR 0125 decisions 2, 8a)
  - core imports no application, and the backend's core module does not depend on :apps:memories
  - a medium of a trashed node, a trashed medium, or a medium a moderation hold hides from the member is no search document
---

## Goal

Build [ADR 0125](../docs/decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)
decision 8a (owner, 2026-11-09). Every photo and video of every space becomes a search document, with its capture
time, place and the original's name, as the open Memory's are today.

- "Rom" then finds a photo taken in Rome inside a Memory placed elsewhere.
- A year chip finds a photo by its capture year.
- A photo result outside the open Memory opens its Memory with that photo in the lightbox.

## Context (measured at refine with a read-only survey; substrate: branch `worktree-search-design` at `13814429a`)

- **The member listing today:** `listNodeMedia`, `contracts/api/memories/media.openapi.yaml` (`GET
  /api/scopes/{scopeId}/nodes/{nodeId}/media`, keyset by `position,id`, `ETag` = the node's items revision).
  - Its handler `MediaHandler.listMedia` checks scope READ only, refuses a trashed node (`MediaStore.findNode`), and
    pages through `MediaStore.keysetPage`.
  - The resource is `@RateLimitExempt` (`docs/crosscutting/cc.security.rate-limit.md` lists it as unbounded by
    decision).
  - A blocked row is marked through `blocks.contentView`, not dropped.
- **The closest precedent:** the share's subtree listing.
  - `ShareMediaHandler.list` reads `MediaStore.listShareMedia` (`scopeId=? and nodeId in (live ids) and deletedAt is
    null order by id`).
  - Its ETag is a digest cached under `items.revisionsOf(nodeIds)` plus the block sets (`ShareMediaTags.kt`). Only the
    first page is conditional.
- **The index:**
  - Every `veyq_media` row carries `scope_id`. `ix_media_scope(scope_id)` exists, and with InnoDB's implicit
    primary-key suffix it orders by `(scope_id, id)`. Verify with `EXPLAIN` against MariaDB in the slice test, or say
    why not.
  - A trashed node's media keep `deleted_at` null, so the query restricts to live node ids, as the share listing does.
- **Items revisions:** `veyq_node_items_revision` is core's (`NodeItemsRevisions`, `revisionsOf(nodeIds)` one batch
  read). It also moves on changes the search ignores (bucket move, re-key), which only costs an extra re-read.
- **The web side:**
  - Opening goes through `features/memories/openedListing.ts` and the `listingOpener` worker. They key by node key,
    so a scope page is grouped by `nodeId` and opened per node with that node's key, uploader keys and drop key.
  - The search's per-node hook `KindSearch.facets` is synchronous. There is no per-space async door yet
    (`core/search/searchIndex.ts` `readSpace`, `core/applications/registry.ts`).
  - `features/memories/search.ts` `mediaDocuments` builds the open Memory's documents. `MemoryDetail.vue` contributes
    them.
- **The tests to update:**
  - `OperatorBlindProbeTest` classifies every operation; the new one must be classified.
  - `RateLimitPostureSweepTest` requires the posture.
  - `TokenAnswersAreNotCompressedTest` names `listNodeMedia`; check whether the new operation belongs there.
  - Slice tests live beside `MediaListPagingSliceTest` and `MediaListQueryCountSliceTest`, with `MediaSeed.kt`.

- **Build on the slicing** (`cr-search-at-volume`, commit `71b23a327`): the index now builds each space in steps
  of at most 40 ms with yields between them, and the measured 4x-throttle reserve on the first keystroke is only
  14-35 ms under the 100 ms bound. So the per-space photo hook runs inside that step loop (opening a page per step, the
  listing opener in its worker). Add the photos stage to `scripts/measure_volume.py search`, re-run it once, and record
  the numbers in ADR 0125 § Consequences: both bounds must still hold with the photos indexed (the volume harness seeds
  photos: `… stage photos <N>`).

## Design

- **Contract** (`contracts/api/memories/media.openapi.yaml`, tag `media`, under the `MediaApi` root
  `/api/scopes/{scopeId}`): `listScopeMedia`, `GET /api/scopes/{scopeId}/media`.
  - **Parameters:** `after` (a mediaId), `limit` (1..1000), `If-None-Match`.
  - **Response:** a page of items. Each item is the `MediaSummary` fields a search needs (reuse the schema or a
    narrowed one; the implementer decides and says why) plus `nodeId`. The page also carries the per-node extras
    `listNodeMedia` carries (`sealedDropPrivateKey`, `uploaderKeys`), keyed by node id, for the nodes on the page.
  - **Order and freshness:** ordered by id. The `ETag` on the first page answers `304` when nothing moved.
  - **Scope:** a member's (scope READ), live nodes only, live media only. A blocked medium is treated as
    `listNodeMedia` treats it, and the client makes no document of it.
- **Backend** (`:apps:memories`):
  - A handler and a store query by `(scope_id, id)` over the live nodes' ids. Core serves the live node ids without
    naming the application.
  - The ETag is derived like the share listing's, from `revisionsOf(liveNodeIds)`, the tree revision and the
    moderation state the listing depends on.
  - `@RateLimitExempt` with the same reason as `listNodeMedia`; the rate-limit doc's list gains it.
  - A constant number of queries per page (a query-count slice test, as for `listNodeMedia`).
- **Web:**
  - **The registry door:** a per-application, per-space async hook (e.g. `ApplicationRegistrar.search({ scopeDocuments
    })`, the implementer names it). `readSpace` calls it while the walk still holds the scope's node keys, with
    `{ scopeId, keyOf(nodeId), signal }`, and zeroes the keys after it has returned.
  - **Memories:**
    - The hook fetches `listScopeMedia` by its ETag, groups the page by node, and opens it per node through the
      existing listing opener.
    - It returns photo and video documents with capture time, place and name, carrying the Memory's path.
    - A `304` keeps the previous documents.
  - **Deduplication:** a medium's document from the open Memory's contribution replaces the same `mediaId` from the
    scope listing.
  - **Groups:** a photo in the open Memory goes to the open node's group, as today; any other goes to *In this space*
    or *Other spaces* with its Memory's name in the second line.
  - **Opening:** a photo result outside the open Memory opens its Memory with that medium in the lightbox. Reuse an
    existing deep link to a medium if one exists, otherwise add the smallest one through core's `location.ts`, the
    one door for fragment paths. If neither fits ADR 0015's grammar, hand it back.
  - **The empty state** drops "photos only in the open Memory" (use case A2), as does Memories' `note`.
- **Docs:**
  - **Use case:** A2's sentence, step 3's Memories line, and a Quality note on what the server sees.
  - **Coverage:** `uc.platform.search.yaml` gains a criterion with refs on both web and backend, so the backend gap
    goes.
  - **Other docs:** the rate-limit doc, the Use page `spaces/search` in EN+DE, and ADR 0125's `Built:` (decision 8a
    built).

## Phases (contracts → backend → web)

### Phase 1 — contract + backend

- **Build:** the contract, then the handler, store query, ETag, posture and classifications.
- **Slice tests:**
  - paging by id across nodes;
  - a trashed node's media excluded;
  - trashed media excluded;
  - another scope's media never served;
  - a non-member refused;
  - `304` on an unchanged scope, and a new ETag after an upload, a trash and a node trash;
  - a blocked medium as `listNodeMedia` treats it;
  - the query count constant.
- **Bites:**
  - Drop the live-node restriction: the trashed-node test is red.
  - Drop the scope filter: the other-scope test is red.
- **Done when:** `./gradlew check` is green in `src/backend/` (the generated `MediaApi` gains the method in the same
  phase). The contract is in the same commit as the backend, because the generated interface does not compile without
  the resource (changes/README § A contracts phase that adds an operation to an EXISTING tag).

### Phase 2 — web

- **Build:** `pnpm run generate:api` first. Then the registry door, Memories' hook, deduplication, groups, opening and
  the empty state.
- **Tests:**
  - the door is called with the keys open and they are zeroed after (spy);
  - a `304` keeps the documents;
  - deduplication;
  - a photo from another space found by a place chip and by a year chip;
  - opening it lands in its Memory's lightbox;
  - no blocked medium becomes a document.
- **Browser pass on the `veyq-search` stack:** "Rom" finds `strand.jpg` from outside *Roma 2024*; a year chip finds a
  photo in another space; opening it shows the lightbox. Desktop, final screenshots only.
- **Done when:** `pnpm run check` is green in `src/web/` and the core-import guard is green.

### Phase 3 — docs

- **Docs:** the Docs list above.
- **Done when:** the trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, three phases: `886babb0b` (contract + backend), `6577fe1b7` (web),
then the typing fix the volume re-measurement asked for (`c6f54d4e5`) and the docs phase with the measurement.

### What was built, and the choices made (owner away; technical choices are mine)

- **Contract.** `listScopeMedia` on the `media` tag, `GET /api/scopes/{scopeId}/media?after=&limit=`, `If-None-Match`. A
  row is a **narrowed** `ScopeMedium` (node, ids, the original's blob ref with its sealed blob meta, `wrappedMeta`,
  `metaRevision`, `ownerKey`, `blockedByOperator`), not `MediaSummary`: the search opens none of the derivatives (two more
  blob refs with manifests per row), buckets or times, so the page is smaller and says only what the search reads. The
  per-node extras are `nodes: [{ nodeId, sealedDropPrivateKey, uploaderKeys }]`, one entry per node of the page that has
  a drop key.
- **Live nodes in the statement, not as a list.** The query is `scopeId = ? and deletedAt is null and exists (select n
  from NodeEntity n where n.id = m.nodeId and n.deletedAt is null) order by id` (`MediaStore.listScopeMedia`). The CR said
  "core serves the live node ids"; a 5,000-node space would have made that a 5,000-placeholder `IN` per page (and the
  share's own path has the same shape, with MariaDB's 65,535-placeholder ceiling behind it). Memories already asks a
  node's liveness on core's row (`findNode`), so the predicate is the same one. Core serves what the tag needs instead:
  `NodeItemsRevisions.revisionsOfLiveNodes(scopeId)`, one join.
- **The tag is derived, not a digest of the rows.** The share listing digests its rows because a guest must learn no
  revision; a member is told the item revisions by `listNodeMedia` already. So `ScopeMediaTag` = SHA-256 over the tree
  revision, every live node's item revisions and the scope's blocked node and item ids, read before the rows. An idle
  read costs the access check, the blocks, the tree revision and the join - no media query and no cache. I kept the tree
  revision in the key as the ADR says, although the live-node set it stands for is in the key anyway: it also makes a
  rename a new answer, which is what keeps a photo's path (its Memory's name) fresh without a second rule.
- **EXPLAIN** could not run in the slice test (they run on H2); run by hand against MariaDB - see the measurement below.
- **The registry door** is `ApplicationRegistrar.search({ scopeDocuments })`, per application, write-once. The hook is
  handed `{ scopeId, tag, previous, keys(), slices(), signal }`:
  - `keys()` is lazy: on a walk whose tree moved, the node keys come from a resolver over the opened tree; on a tree
    `304`, the scope key is opened only if the hook asks (a listing `304` asks nothing). Every node key the resolver
    opened is zeroed when the hook returns; the scope key with the walk's (never the active space's).
  - `previous` (the last answer's documents) lets the hook keep a medium at an unchanged revision without opening it.
  - `slices` runs the hook's per-medium steps in core's 40 ms slices; `signal` aborts on a reset.
  - It is asked for **every** space on every walk, the active one included: an item bump moves no tree revision.
- **Memories' hook** reads the listing by its tag, filters blocked rows first (one place), opens each page per node in the
  listing opener Worker under the node's key and closes every listing it opened in a `finally`.
- **Groups and second line.** An object goes where its node goes (the open node's group when its node is the open one or
  below it); its second line is its space (other spaces), its Memory's path and its day.
- **Opening (handed back, see below).** No deep link to a medium exists, and ADR 0015's grammar has no place for one
  (`…/memories/rom/<x>` reads as a chapter slug). The conservative default built: the search navigates to the Memory's
  fragment and leaves an in-memory request (`core/search/openRequest.ts`); `MemoryDetail` takes it once its gallery holds
  the medium, or drops it once the gallery has read and lacks it. A reload or a copied link carries no medium.
- **The note door went.** `KindSearch.note` had one user (Memories' "photos only in the open Memory"); with the note gone
  it had none, so it was deleted rather than kept empty.
- **Found in the browser pass, fixed:** `indexedTree`/`indexedScope` were not reactive. A field mounted before the index
  was built (any reload on a Memory's page) cached an empty tree, so the open node's group never appeared - "Nichts
  gefunden" for a photo of the open Memory. Pre-existing since cr-search-across-spaces (its pass entered the Memory after
  the build); both now read `searchState.revision`, with a test that is red without it.

### Bites (each red at the asserted line, restored by snapshot)

- Backend (`bite.py`, `:apps:memories:test --tests '*ScopeMediaListSliceTest'`): the live-node `exists` dropped → the
  trashed-node test red (line 74); the scope filter neutralised (`scopeId = ?1 or scopeId <> ?1`) → the other-scope test
  red (line 72, plus three others); the blocked items left out of the tag → the tag test red.
- Web (`bite_web.py`): the resolver's `zero()` dropped → the two key tests red; the blocked filter dropped in the hook →
  its blocked test red, and in `mediaDocuments` → `MemoryDetail.search` red; the dedup dropped → the dedup test red;
  `indexedTree`'s reactivity dropped → the reload test red.
- A first bite of the blocked rule stayed green: the hook skipped a blocked row in two places (the open and the
  document). I moved the rule to one filter instead of testing two layers that each sufficed.

### Checks

- `./gradlew check` in `src/backend`: BUILD SUCCESSFUL (8 m 46 s).
- `pnpm run check` in `src/web` (after `generate:api`): green, 540 test files, 6,535 tests - at the web phase and
  again with the typing fix.
- The trust-chain guards: 69 `scripts/validate_*.py`, all green (`validate_guard_gaps.py` skipped: no validator changed).
- The browser pass (`search-pass/p12-pass.mjs`, desktop, `veyq-search` stack, worktree backend 8180 + Vite 8189): 15 of
  15 checks pass. Screenshots: `p12-desktop-rom.png`, `p12-desktop-year-chip.png`, `p12-desktop-lightbox.png`. Its
  fixture: strand.jpg was given the place "Roma" and the capture day 2019-07-14 through the app's own meta writes in the
  page (`p12-seed.mjs`), since the UI sets a photo's place only through the maps opt-in (a third-party service).

### The volume re-measurement

- **Instance.** The harness's own, booted from this branch (`veyq-vol-search:p12` at `6577fe1b7`, project
  `veyq-vol-11091132`), seeded `birth`, `tree --target 5000`, `spread --target 1000`, `members --target 445` and the new
  stage `photos --target 10000` (the tenant's 'All photos' Chapter, 3.2 min). Torn down afterwards (`teardown`), the
  images removed.
- **The harness.** `search_spec` refuses without the photos stage and counts each space's live media; the leg
  (`tests/e2e/measure/search.ts`) waits for every space's media listing besides the trees; `search_findings` requires the
  second focus to read every space's listing (the active one's included) as one `304`. `measure_volume_selftest.py`
  green; `tests/e2e` typechecks.
- **EXPLAIN on MariaDB** (`p12/explain_vol.py`, the 10,000-photo scope): the listing scans `ix_media_scope` in id order
  (range, "Using index condition; Using where", no filesort) with the node as a `PRIMARY` `eq_ref`; its first page took
  0.68 ms. The revisions join was planned as a scan of `veyq_node_items_revision` (one row on this instance) with the
  node an `eq_ref`; on an instance with many tenants the optimizer may keep scanning that table - not measured, left as a
  residual.
- **Readings** (`search --runs 2 --throttles 1,4 --active tenant|group`; ×4 typing = the longest task while typing,
  always the first keystroke "K"):

| Invocation, load (1-min, start→end) | landing | build wall ×1 / ×4 | longest build task ×1 / ×4 | longest typing task ×4 | heap |
|---|---|---|---|---|---|
| A 12:04, 7.9→9.4 | tenant | 2.4, 2.4 / 5.8, 5.4 s | 65, 55 / 97, 101 ms | 97, 83 ms | +8.3 MB |
| A 12:05, 9.0→8.7 | group | 2.5, 2.3 / 4.0, 4.0 s | 76, 60 / 126, 115 ms | 83, 76 ms | +7.9 MB |
| B 12:07, 7.5→7.7 | tenant | 2.3, 2.3 / 6.3, 5.0 s | 55, 51 / 104, 87 ms | **108**, 76 ms | +8.3 MB |
| B 12:08, 7.7→8.1 | group | 2.1, 1.9 / 3.6, 5.1 s | 63, 54 / 102, 128 ms | 70, 91 ms | +7.9 MB |
| C 12:10, 8.2→8.2 (re-run) | tenant | 2.4, 2.6 / 4.9, 5.9 s | 72, 79 / 92, 102 ms | 74, 71 ms | +8.3 MB |
| C 12:11, 8.0→12.9 (re-run) | group | 2.4, 2.2 / 5.6, 4.2 s | 65, 58 / 147, 107 ms | **113**, 88 ms | +7.9 MB |
| D 12:17, 8.3→5.9 (fix) | tenant | 2.6, 2.3 / 4.3, 4.3 s | 53, 79 / 86, 84 ms | 57, 88 ms | +8.3 MB |
| D 12:19, 6.4→6.9 (fix) | group | 1.8, 1.9 / 3.3, 3.8 s | 53, 53 / 100, 105 ms | 99, 62 ms | +7.9 MB |
| E 12:21, 7.9→7.2 (fix) | tenant | 2.3, 2.4 / 5.0, 4.9 s | 73, 60 / 106, 90 ms | 80, 79 ms | +8.3 MB |
| E 12:23, 6.9→8.4 (fix) | group | 2.1, 1.9 / 3.9, 4.3 s | 55, 56 / 99, 119 ms | 66, 81 ms | +7.9 MB |

- **Judgment.** Bound 2 (the build, 200 ms at ×4) held in every reading: 84–147 ms. Bound 1 (typing, 100 ms at ×4) failed
  once in B (tenant, 108 ms); its re-run C held there but the group landing failed in C (113 ms) after holding twice - a
  coin flip around the bound. A CPU profile (`search --profile`, a diagnosis run) put ~20 ms of the "K" task in the
  matching: every one of ~17,000 documents ran four string searches and a regular-expression split of the text. **The
  fix** (`query.ts`): a name that does not hold the text is answered by one `includes`, and the words are split once per
  query. Re-measured twice (D, E) on an image with the fix (`p12fix`), both landings: typing 57–99 ms, build 84–119 ms at
  ×4 - both bounds hold in both runs, at loads 5.9–8.4. The margin on typing stays thin (one reading at 99 ms); the rest of
  that task is Vue's render of the list, which the photos did not change. Quantity 3 held: every second focus read each
  other space's tree and every space's media listing as one `304`; its 2 decoded strings are cr-search-at-volume's
  finding (the settings sections' `load()` on every focus), not the index. The photos roughly doubled the build's wall time
  and the index's heap (+3.7–4.2 MB → +7.8–8.4 MB).

### Left open

- **The typing bound's margin** is ~1 ms in the worst reading after the fix, and the remainder is the list's render.
  A household with more photos (or a slower device) meets it first; the next lever is rendering fewer rows on the first
  keystroke, or debouncing a single character - a UX choice, so not taken here.
- **The revisions join's plan on a big instance** (see EXPLAIN above).

### Owner questions

1. **A medium's own address.** Opening a photo found elsewhere works through an in-memory request; nothing in the URL
   names the medium. Recommendation: keep it so - a search result is opened, not shared - unless a "link to this photo"
   is wanted; then it needs an ADR 0015 amendment (a fragment suffix the slug grammar cannot produce, e.g. `…/rom!<id>`).

### Process observations

- I overwrote an existing test file (`features/memories/search.test.ts`) with `Write`, believing it new; the tool's
  "updated" wording was the only sign. Restored from `HEAD` at once and moved my tests to `search.scope.test.ts`. A
  `git status` (or `ls`) before creating a file named like a module's own test would have caught it.
- The pass's last step found a defect no unit test could: a computed cached over a non-reactive read. A unit test of
  the field mocks the index; only a reload in a real browser mounts the field before the index exists.
