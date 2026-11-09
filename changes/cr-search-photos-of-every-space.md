---
id: cr-search-photos-of-every-space
title: The search finds every photo and video of every space by its capture year, place and name
status: planned
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

(filled by the implementer)
