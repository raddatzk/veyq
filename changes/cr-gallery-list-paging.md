---
id: cr-gallery-list-paging
title: The media list in bounded pages, a constant number of queries per page, and compressed answers
status: planned
satisfies: ["uc.memory.gallery", "uc.memory.live-updates"]
adrs: ["0033", "0103", "0105", "0081", "0069", "0013", "0064", "0078"]
invariants:
  - operator-blind - paging adds a page size and a "more" flag, never a sort key the server could not already see
  - the revisions a client keeps are the first page's, so no change committed while it pages is lost
  - a response that carries a token is never compressed (ADR 0103 decision 3), and the set is derived from the contracts
  - share view = member view - the guest's listing is paged by the same rule
---

## Goal

Inbox #490, measured by `cr-volume-measurement-local` (2026-10-18, the production recipe pinned to two cores): the media
list of a Chapter is **one unbounded answer** built with **2 + one per derivative SELECTs per photo** — 10,000 photos cost
40,017 SELECTs, 3.0 s warm and 23.4 MB — and **no answer veyq sends is compressed**. Three fixes, each on its own phase so
any of them can land alone:

1. **A constant number of queries per page** instead of per photo (the cheap half, no contract change).
2. **The list in pages** of a bounded size, on the keyset ADR 0033 already has ([ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)
   decision 6, proposed with this CR), for the member's and the guest's listing alike.
3. **Compressed answers** from the application, never for an answer that carries a token
   ([ADR 0103](../docs/decisions/0103-the-application-compresses-its-answers-except-the-ones-that-carry-a-token.md), proposed).

The client keeps rendering as it does: it loads every page, then hands the same full list to the same reducer — the grid
appears when its order is final, which needs every item's sealed capture time ([ADR 0105](../docs/decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)
decision 4, proposed with `cr-gallery-virtualization`).

## Context & constraints

- State: [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) (the order is the sealed capture
  time, read only on the device — so **a page is never the first screen**, ADR 0033 decision 6),
  [uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md) (the conditional read and the
  delta this CR builds the pages from), [ADR 0081](../docs/decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)
  decision 3 (revision read before the rows), [ADR 0069](../docs/decisions/0069-backend-serves-the-web-app-shell.md) (the
  backend serves the bundle, so ADR 0103 compresses it too).
- **Sibling CRs.** `cr-gallery-virtualization` (#489) renders and decrypts what this CR delivers; neither renders before the
  last page. The presign work of inbox #488 is **being implemented now** in
  `useThumbnailPipeline.ts` and `core/blob/transport.ts` — this CR touches neither file. `cr-volume-measurement-cax`
  phase 5 deletes the harness this CR measures with; this CR's acceptance runs before that phase (that CR names it).
- **Not this CR:** the trash listing (`state=trashed`, ordered by `deletedAt`) keeps one answer — it is bounded by the
  trash retention and shares only the batched mapper of phase 2; live updates for a **guest's** gallery (the share
  listing has no ETag and no poll today, `source.ts`'s "no live poll") are Q2; the 2.3 KB per item (three wrapped keys,
  two manifests, padded meta) is the item's shape, not its delivery.
- **A growing number meets a contract cap** (changes/README § A CR that grows a count): the response gains its first
  `maxItems` (the page cap). Caps found elsewhere in the contracts on stores that grow with members or items are listed
  in `cr-identity-pins-per-entry` § Other contract caps — none is on this CR's path.

### Measured substrate (worktree at `6fb7c8dd`, clean)

| Fact the plan leans on | Locator | At refine |
|---|---|---|
| The per-item queries: `BlobEntity.findById` for the original, `MediaDerivativeEntity.find("mediaId = ?1")`, `BlobEntity.findById` per derivative; no relation mapping, no batch fetch | `grep -n 'findById\|MediaDerivativeEntity.find' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt`; `grep -n 'ManyToOne\|OneToMany\|batch' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/Entities.kt backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/Entities.kt` | 4 per photo with two derivatives |
| `toRow`'s three callers: the active list, the trash, the share list | `grep -n 'toRow' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt` | 3 |
| The keyset already exists: `since` = `updatedAt,id`, `order by updatedAt, id`; the share list has no order, no `since`, no ETag | `grep -n 'order by\|nodeId in' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt`; `grep -n 'ItemsSince\|ItemsIfNoneMatch' contracts/api/_common.openapi.yaml contracts/api/memories/*.yaml` | one cursor in all contracts |
| No index serves the keyset: `ix_media_node (node_id)` only | `grep -n 'INDEX\|CREATE TABLE veyq_media' backend/apps/memories/src/main/resources/db/migration/V202610041201__memories_baseline.sql` | sort on top of `ix_media_node` |
| No list in the contracts pages; no `limit` anywhere | `grep -rn -E 'name: (limit|cursor|pageToken|after|offset)\b' contracts/api` | 0 |
| `MediaListPage.media` / `ShareMediaListing.media` carry no `maxItems` | `grep -n 'maxItems' contracts/api/memories/media.openapi.yaml contracts/api/memories/share-media.openapi.yaml` | request schemas only |
| The web's full-list readers (first load, cover, export) and the share reader that filters the whole subtree by node on the client | `grep -rn -E 'listMedia\b|listMediaPage|listShareMedia' web/src --include='*.ts' \| grep -v -E 'generated|\.test\.ts'` | `source.ts`, `media.ts`, `coverLoad.ts`, `exportImport.ts`, `share.ts`, `core/sharing/*` |
| The live tick keeps `mediaRevision` / `deleteRevision` and the newest cursor | `grep -n 'mediaRevision\|deleteRevision\|newestCursor\|reset' web/src/features/memory/useMediaGalleryLive.ts` | first load at the `load({reset:true})` site |
| Compression is off everywhere: no property, no Traefik middleware, no route filter | `grep -rn -i 'compress\|gzip' backend/src/main/resources backend/core/src/main/resources infra charts`; `grep -n 'middleware' backend/core/src/main/kotlin/me/raddatz/veyq/core/tenant/TraefikFileIngressSink.kt` | none |
| Quarkus 3.36's knobs: `enable-compression` (build-time), `compress-media-types` (default includes `application/json`, `text/html`, `text/css`, `application/javascript`), `compression-level`, `compressors` (`br` needs JNI), `@Compressed` / `@Uncompressed`, and static resources follow the same media types | `grep -n 'version.ref = "quarkus"\|^quarkus' backend/gradle/libs.versions.toml`; in `quarkus-vertx-http-3.36.0-sources.jar`: `VertxHttpBuildTimeConfig.java` (`enableCompression`, `compressMediaTypes`), `StaticResourcesRecorder.java:45` | as listed |
| Answers that carry a token (ADR 0103 decision 3's set) | `grep -rn -B3 'x-veyq-visibility: token' contracts/api` → each hit's enclosing schema → the operations returning it | `verifyLogin`, `refreshSession`, `adoptSession`, `delegatePairingSession`, `createShareIdentity`, `createTenant`, `resendTenantInvite`, `reassignTenantInvite`, `inviteTenantOwner` |
| Auth is a bearer header; the refresh cookie is `SameSite=Strict`; no CSRF token exists | `grep -n 'SameSite' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/SessionCookie.kt`; `grep -rni 'csrf\|xsrf' backend/core/src/main contracts web/src \| wc -l` | `STRICT`; 0 |
| The harness reads SELECTs (global `Com_select`), wire and body bytes and `Content-Encoding` | `grep -n 'com_select\|contentEncoding\|gzipBytes' scripts/measure_volume.py web/scripts/measure-volume.mts` | the server leg |
| No backend test counts queries | `grep -rl 'generate_statistics\|QueryCount\|PrepareStatementCount' backend` | 0 |

Measured at refine (`cr-volume-measurement-local`, the readings outside git): 1,000 photos 4,011 SELECTs / 180 ms;
3,000 12,013 / 814 ms / 7.0 MB; 10,000 40,017 / 3.0 s warm, 1.9–3.1 s cold / 23.4 MB (13.3 MB gzipped by the harness);
the 304 path 9–11 SELECTs, under 10 ms.

**Read, not measured — phase 2 measures it first.** The keyset's sort key is `updated_at`, stamped `Instant.now()` inside
the writing transaction (`MediaStore.kt`, `cr-0107` comments). A transaction that stamps *t1* and commits after another
that stamped *t2 > t1* has already been passed by a client whose cursor reached *t2*: its row is never delivered as a
delta. The window is the time between the stamp and the commit (milliseconds), so two members uploading at once could hit
it. Paging does not widen it; ADR 0033 has it today. See Q1.

## Decisions this CR takes

1. **One page is mapped with a constant number of queries.** The page's original blobs, its derivatives and their blobs
   are read with one `IN` query each (or one join), whatever the page size, for all three callers of `toRow`. The order
   and the rows of today's answer are unchanged; a missing blob still drops the row as `toRow` does.
2. **Pages on the existing keyset.** `listNodeMedia` takes `limit` (1–1000; absent = 1000, the cap) and answers `more`;
   `media` gets `maxItems: 1000`. The client asks for 500 (the phase measures 250 / 500 / 1000 at 10,000 photos and
   keeps the best, writing the reading into the comment at the constant). The first page is a read without `since`;
   every later page sends the last row's cursor. The revisions the client stores are the **first page's** (ADR 0033
   decision 6 says why that loses nothing). The `304` path is untouched.
3. **A keyset index**: `(node_id, updated_at, id)` on `veyq_media`, in a new Memories migration. The share listing's
   keyset uses it per node.
4. **The share twin pages by the same rule.** `listShareMedia` gains `nodeId` (optional: a listing of one node inside the
   shared subtree, refused with the share's ordinary 404 outside it), `since`, `limit` and `more`, ordered by
   `(updated_at, id)`. The guest's gallery asks for its open node only, as the member's does — today it downloads the
   whole subtree and filters on the client (`source.ts`). Readers that need the subtree (`core/sharing/review.ts`,
   `resolvePublicShare.ts`) page without `nodeId`.
5. **Every full-list reader goes through one page loop** (`listAllMedia` or the existing `listMedia`, reshaped): the
   first load, the cover picker, the export, the guest's gallery and the subtree readers. No caller hand-rolls a loop.
6. **Compression per ADR 0103**: `quarkus.http.enable-compression=true` in the base `application.properties` (so dev,
   e2e and prod alike), the default media types plus `image/svg+xml` and `application/manifest+json`, gzip and deflate
   only, the level phase 3 measures. `@Uncompressed` on every resource method of the token set, held by a test that
   derives the set from the contracts (decision 7). No change to Traefik, the chart or admin-web's nginx: admin-web is
   localhost-only and small; a proxy that sees `Content-Encoding` passes the body on (R — Compose's Traefik has no
   `compress` middleware to test it against; phase 3 states it only as remembered).
7. **The guard is a test, not a list.** A backend slice test reads every response schema in `contracts/api` for a
   `x-veyq-visibility: token` field (through the generated models or the YAML, as `OperatorBlindProbeTest` derives its
   operation set), calls each such operation with `Accept-Encoding: gzip` and asserts **no** `Content-Encoding`, and — its
   positive control on the same predicate — calls `listNodeMedia` and a static asset with the same header and asserts
   `gzip`.

## Open questions (owner)

- **Q1: If phase 2's race test is red, what fixes it?** (a) a **per-node sequence**: each media write stamps the row with
  the node's next item revision, taken under the revision row's lock that `NodeItemsRevisions.bump` already holds to the
  commit, so rows become visible in sequence order and the keyset becomes `(seq, id)`; or (b) an **overlap**: the server
  answers rows from `cursor − δ` and the client's upsert absorbs the repeats, which shrinks the window without closing it.
  **Recommendation:** (a) — it is exact, costs one column and the index of decision 3 on it instead of `updated_at`, and
  `updated_at` keeps its other readers (purge, served time). If the test is green, neither.
- **Q2: A guest's gallery has no live poll.** Share view = member view says it should; it needs an ETag on the share
  listing that tells the guest only what its node shows (ADR 0081 decision 4's rule for guests). **Recommendation:** its
  own inbox row, not this CR — it is a feature, and this CR is the delivery of what exists.

## Phases

- [ ] **0 docs** — ADR 0033 decision 6 and ADR 0103 written at refine (proposed); here: uc.memory.live-updates main flow
  step 1 ("lists its media once (full list)") says pages and the first page's revisions; uc.memory.gallery names the
  guest's per-node listing; coverage criteria added to `uc.memory.live-updates.yaml` (`first-load-in-pages`,
  `paging-loses-no-concurrent-change`) and `uc.memory.gallery.yaml` (`guest-lists-its-node-in-pages`) with their refs
  gapped until the phase that brings them. · Done when: `validate_coverage.py`, `validate_doc_links.py`,
  `validate_feature_format.py` green.
- [ ] **1 contracts** — `ItemsLimit` (query, 1–1000) and the `more` field in `_common.openapi.yaml`; `listNodeMedia`
  takes `limit`, `MediaListPage` gains `more` (required) and `media.maxItems: 1000`; `listShareMedia` takes `nodeId`,
  `ItemsSince`, `ItemsLimit`, `ShareMediaListing` gains `more` and `maxItems: 1000`. Predicted reds: the backend's
  `MediaListPage(` and `ShareMediaListing(` constructions (a new required response field); `npm run generate:api` then
  `npm run check` in `web/` green (only optional request params were added). `OperatorBlindProbeTest` needs no new row
  (no new operation). · Done when: `generate:api`; `npm run check` (web) green; the contracts guards
  (`validate_contracts_common.py`, `validate_request_bounds.py`, `validate_field_visibility.py`) green; the two predicted
  backend reds and no other, read from `./gradlew compileKotlin`.
- [ ] **2 backend: queries and pages** — first the **query-count test** (Hibernate statistics in the test profile or a
  counting datasource): a list of 1 and of 60 photos with two derivatives each issue the same number of statements; red
  on today's tree. Then decision 1 (green), the keyset `limit`/`more` (fetch `limit + 1`), the index migration, the share
  twin (decision 4). Then the **race test** of § Measured substrate: two transactions stamping out of commit order, a
  client paging between them; it says red or green, and Q1's answer follows only a red. · Done when: `./gradlew check`
  green; the query-count test bitten (per-item `toRow` back → red); a paging test: 1,201 rows in pages of 500 arrive
  complete, a row edited and one deleted between page 1 and 3 are delivered or reconciled by the next poll from the first
  page's revisions — bitten by keeping the last page's revisions instead (→ the delete is never reconciled); the guest
  test: a `nodeId` outside the share is the same 404 as an unknown share, with a positive control inside it.
- [ ] **3 backend: compression** — decision 6 and the test of decision 7. Measure the level: the 10,000-photo list
  through the harness's server leg at levels 1, 4 and 6 (CPU time on the pinned two cores, wire bytes), keep the best
  trade, write the readings beside the property. · Done when: `./gradlew check` green; the token test bitten twice
  (`@Uncompressed` off one token operation → red naming it; a `token` field added to a response in a scratch copy of a
  spec → red until that operation is excluded); its positive control bitten (compression off → red); the harness reads
  `Content-Encoding: gzip` on the list and the bundle.
- [ ] **4 web** — decision 5's one page loop; the first load keeps the first page's revisions and the last page's cursor;
  the guest's gallery lists its node by `nodeId`; the cover picker, the export and the subtree readers page.
  `useThumbnailPipeline.ts` and `core/blob/transport.ts` are not touched (#488). · Done when: `npm run check` green;
  `useMediaGalleryLive.test.ts` pins that the stored revisions are the first page's (bitten: the last page's → red);
  **browser pass** (dev stack with S3, a Memory of 1,200 photos seeded by `web/scripts/seed-volume.mts`, a member and a
  guest link): the grid shows all 1,200 in capture order, the network list shows three pages each carrying
  `Content-Encoding: gzip`, an upload from a second member appears by the next poll, the guest's listing names the node.
- [ ] **5 measure and record** — the harness's photos stage at 1,000 / 3,000 / 10,000 on the local recipe, server leg
  only (the browser leg is `cr-gallery-virtualization`'s): SELECTs per page constant, warm list time and wire bytes per
  stage. Inbox #490 closed with the readings' summary; the Run docs say nothing new (compression is not an operator knob)
  — `git grep -n 'Content-Encoding\|compress' site/src/content/docs` confirms no page claims otherwise; the Security
  area's *What the server sees* gains nothing (sizes are still sizes, ADR 0103 § Consequences). · Done when: the 10,000
  list completes in under 2 s warm on the local recipe (Q1's rule of the CAX CR, applied to the list); every
  `scripts/validate_*.py` except `validate_guard_gaps.py` green.

## Definition of done

Every phase's Done-when; the query-count, paging and token tests bitten as written; the harness's reading at 10,000
recorded in the retrospective; inbox #490 done.

## Retrospective

_Filled at implementation._
