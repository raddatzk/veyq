---
id: cr-meta-writes-carry-the-version-they-read
title: A sealed-meta write carries the version it was built from, and a stale one answers 409 stale_version
status: done
satisfies: [uc.memory.concurrent-curation, uc.memory.date, uc.memory.place, uc.memory.cover, en.memory.node-meta, en.memory.media]
adrs: ["0077", "0072", "0033", "0068", "0036"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Answered by the owner, 2026-10-03: every recommendation below is taken (Q1–Q7), and implementation has the go.**

1. **Which entities get a version check?** The inbox row asked about every editable entity. Inventory rows 1–16
   sort them by two questions: can more than one account write the row, and does the write re-send something
   the editor did not edit?
   - **Recommendation: the two sealed meta blobs only. That is the node `wrappedMeta` (a Memory's date, place
     and cover) and the media `wrappedMeta` (date correction, place name, media type)** (D1).
   - Everything else is recorded as last-writer-wins, with its reason (D4). Those writes either have one
     writer, or they carry only the field the user typed.
   - The row's own example does not hold. A share is edited only by its `owner_account`
     ([uc.sharing.share-note](../docs/features/sharing/use-cases/uc.sharing.share-note.md) line 54:
     *"only the share's creator edits"*; `SharingStore.kt:197`). Two members can never collide on a share.
2. **The refined state says the opposite of Q1, so this supersedes it.**
   [uc.memory.date](../docs/features/memory/use-cases/uc.memory.date.md) § Quality notes says:
   *"Eventual consistency over locking (deliberate) … no data loss"*. It names *"the `media_rev` CAS"* as the
   upgrade if strict consistency is ever needed.
   - That note reasoned about the derived `auto` range only. The blob also holds `location` and `cover`, which
     never self-heal, and a manual date. Inventory row 1 shows a member who only *opens* a Memory wiping all
     three.
   - **Recommendation: supersede the note (D1). Use a dedicated `meta_version`, not `media_revision` (D2).**
     `media_revision` moves on every upload, so a CAS on it would refuse a date edit whenever anyone adds a
     photo.
3. **Wire form: an `If-Match` header answering `412`, or a body field answering `409 stale_version`?**
   **Recommendation: the body field and `409 stale_version`** (D3).
   - It is the vocabulary that already exists: `putIdentityPins` and `putDeviceRegister`
     (`grep -n stale_version contracts/api/account.openapi.yaml`).
   - It is typed in the generated clients.
   - The repo's one ETag (`mediaRevision`) validates a read cache for a whole list. It is not a per-item write
     precondition, so `If-Match` would be a second vocabulary for the same rule.
4. **How does the loser re-read?** Neither entity has a single-item GET. The node only has `listNodes`, and the
   media only has the per-node keyset delta.
   - **Recommendation: the `409` carries the current `wrappedMeta` and its `metaVersion`** (D3).
   - This differs from the pins, which re-GET their one blob, because there is no such GET here.
   - It is the ciphertext the caller can already list, so operator-blindness is unchanged.
   - The alternative is reloading the whole tree, or coupling the lightbox to the gallery's delta poll, for one
     item.
5. **Same-field conflict in an editor.** Example: A sets the date while B's editor still shows the old one.
   - **Recommendation: surface it.** B's save is refused, and the editor re-opens with the current value and a
     one-line notice.
   - A conflict on a *different* field merges silently.
   - The self-heal writers never surface anything. They recompute from the fresh blob (D5).
6. **Where does the acceptance criterion live?** *"A meta write never overwrites what its writer did not read"*
   is one property with five triggers (date editor, place editor, cover editor, two self-heals) plus three media
   writers. `changes/README.md` § coverage calls that the tell for a use-case of its own.
   - **Recommendation: a new `uc.memory.concurrent-curation` ("two members curate one Memory at once").** It
     owns the property and its failure modes (stale tree, self-heal from a stale tree, same-field conflict), and
     date / place / cover point at it.
   - Phase 1 writes it, then adds it to `satisfies:`.
7. **The guard's subject** (D6). **Recommendation: every PUT/PATCH operation and every POST whose `operationId`
   starts with an edit verb** declares `x-veyq-concurrency`. Keyed on the method alone, the guard would miss
   six POST edits (Inventory 17).

**Folded 2026-10-03 (inbox #273, #274), decided under the owner's standing "all recommendations" — listed here
so they can be reviewed:**

8. **A meta writer whose blob does not open** (#273, D7). **Decided: refuse, never rebuild from empty.** The
   self-heals and the media backfills stop without a write; an editor save makes no write and says so in one
   line. The read paths stay best-effort (uc.memory.date E3 is about display and still holds).
9. **The media writers rebuild from a projection** (measured during this fold, D7). `unsealMeta` returns a
   normalized copy without `placeName`/`placeLevel`, and all three media writers spread that copy. **Decided:
   writers open the raw tier and keep every field verbatim; `unsealMeta` also returns the place name.**
10. **A whole-set toggle before its set loaded** (#274, D8). **Decided: the item-mark toggle — and the Memory-pin
    toggle, which has the same shape — refuses unless the store holds a successful load for that node/scope.**
    Not changed: an own entry that does not verify, and a pin blob that does not open. Both are documented
    degradations whose recovery *is* the next write (ADR 0068 §9; `resealFavoritesUnderNewMasterKey`).
11. **The cover editor after a blob operation** (D9). An `upload` (or a switch away from an uploaded cover) has
    already moved the node's one cover-blob slot before the meta write. **Decided: once a blob operation ran, a
    same-field conflict does not surface — the editor's own cover wins on the retry**, because re-opening on the
    peer's cover would show a cover whose blob this save just replaced.

## Goal

Two members curate the same Memory. A sets its place to "Lisbon". B opened the space earlier, then opens that
Memory. B's client sees that the `auto` date range moved, re-seals the whole meta blob from the tree it loaded
at space entry, and PATCHes it. A's place is gone. Nobody edited anything on B's side, and no one is told.

After this CR, every write of a sealed meta blob states the version it was built from. The server
compare-and-sets that version and answers `409 stale_version` with the current blob. The client re-applies only
its own change on top and retries. This is the identity-pin loop applied to the two blobs that several members
write.

The server cannot merge sealed fields. So the merge is the client's job, and the version check is the server's
only tool. Everything that does not need it is recorded as a deliberate last-writer-wins, and a guard keeps a
new editable operation from skipping that decision.

## Inventory

Substrate: worktree of `main` @ `0c9e348`, 2026-10-02.
- **Read** means established by reading the code.
- **Measured** means a command whose output is quoted.
- No throwaway test was run: every hazard below is a straight-line read with no branch that could hide it.
- Rows marked *(sub-read)* were read by a delegated Explore pass and not re-read here. An implementer
  spot-checks one of them.

Paths are shortened: `B/` is `backend/src/main/kotlin/com/veyq/app/` and `W/` is `web/src/`.

| # | Entity / op | Writers | Write shape | Guard today | Stale-overwrite risk | Locator |
|---|---|---|---|---|---|---|
| 1 | **Node `wrappedMeta`** (`updateNode`): Memory date, place, cover | **Any accepted scope member.** `ScopeAccess.check(…, WRITE)` checks membership, suspension and deletion, and **no role** | Whole sealed blob replaced (`wrappedMeta?.let { node.wrappedMeta = it }`). The client unseals the cached blob, sets one field, and re-seals all of it | **None.** No lock, no version. `updateNode` bumps no counter | **High, and measured by reading.** See the paths listed below the table | `sed -n 136,146p B/features/content/NodesStore.kt`; `sed -n 43,52p B/shared/membership/ScopeAccess.kt`; `grep -n "updateNode(" W/features/memory/memoryDate.ts W/features/memory/memoryLocation.ts W/features/memory/memoryCover.ts`; `grep -rn "loadTree()" W \| grep -v test` |
| 2 | **Media `wrappedMeta`** (`updateMediaMeta`): date correction, `placeName`, `mediaType` | Any accepted scope member | Whole sealed tier replaced. `setMediaMeta`, `backfillPlaceName` and `backfillMediaType` all spread `{...meta, field}` from the lightbox's snapshot | None. `setMeta` bumps `media.updatedAt` and the node's `media_revision`, but nothing compares them | **Medium.** See the path listed below the table | `grep -n "persistMeta(scopeId" W/features/memory/media.ts`; `grep -n "captured.value = " W/features/memory/components/MediaLightbox.vue` |
| 3 | Node rename (`encryptedName` + `slugToken`) | Any member | Sends only the two fields the user typed | `slug_taken` only | Same-field only: B's typed name replaces A's. Nothing B did not see is re-sent | `sed -n 208,222p W/features/memory/tree.ts` |
| 4 | Node move / reorder (`parentId`, `orderIndex`) | Any member | Single fields | None | **No editor exists**: `grep -rn orderIndex W` finds no sender | — |
| 5 | `commitCoverBlob` | Any member | Replaces the cover blob pointer | None | Same-field. *(sub-read, [inferred])* The cover crop lives in row 1's blob, so it is covered there | `grep -n "fun commitCover" B/features/media/MediaStore.kt` |
| 6 | `renameBucket`, `moveBucketMedia` | Any member | The one typed name / the listed ids | None | Same-field / intent-typed | `grep -n "fun renameBucket\|fun moveMedia" B/features/media/MediaStore.kt` |
| 7 | `renameGroup`, `setScopeIcon` | Group owners (co-owners possible) | One sealed value, sealed fresh from what was typed | `nameEpoch` / `iconEpoch` = current epoch. That is a **rotation** guard, not an edit guard | Same-field | `grep -n "currentEpoch(scopeId) != nameEpoch\|currentEpoch(scopeId) != iconEpoch" B/features/spaces/scope/ScopesHandler.kt` |
| 8 | `setMemberRole` | Group owners | One `role` | Last-owner count, then write, unlocked | The value is typed; not a stale-view loss. The count-then-write race is a different defect (proposal P1) | `grep -n "isLastOwnerDemote" B/features/spaces/scope/ScopesHandler.kt` |
| 9 | **Share edit** (`updateShare`): note, name, role, expiry, password | **One account**: `owner_account`. Ownership moves on re-activate | Whole form. Note, name and expiry are fully replaced; role is always sent; the password is tri-state | Row lock (against delete), no version | One person, two devices. The form shows what it saves, so nothing unseen is lost from another person | `sed -n 193,210p B/features/sharing/SharingStore.kt`; `grep -n "ownerAccount = callerAccount" B/features/sharing/SharingStore.kt` |
| 10 | Profile (`updateMyProfile`) | Self | Whole form (username, sealed name, consent). The server patches by null, but the client sends all three | None | One person, two devices | `sed -n 50,60p W/features/account/profile/ProfilePanel.vue` |
| 11 | `putFavorites` / `putItemFavorites` | Self (row key `userId\|scopeId[\|nodeId]`) | Whole set re-sealed from the in-memory store | None; documented LWW | One person, two devices: one toggle can be lost. Already accepted ([en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md) § Last-write-wins, ADR 0068) | `grep -n "Last-write-wins" W/features/memory/favorites.ts` |
| 12 | Tenant terms, mail settings, share-link limit | The tenant owner. Single *[inferred]*: the root membership is born with one OWNER, and `setMemberRole` is GROUP-only | Whole document / whole form / one field | None. `terms_version` exists but only `acceptTenantTerms` compares it | One editor | `grep -n "isOwner(callerId, tenantId)" B/features/admin/tenant/TenantTermsHandler.kt B/features/admin/tenant/TenantMailSettingsHandler.kt` |
| 13 | `setTenantStorageQuota`, `changeTenantDomain` | The Server Owner (singleton) | One field | Domain: conditional UPDATE on status | One editor | `grep -n "RolesAllowed" B/features/admin/tenant/TenantsResource.kt` |
| 14 | **Exemplar:** `putIdentityPins`, `putDeviceRegister` | Self, multi-device | Whole sealed blob | **`version` CAS → `409 stale_version`**, with the client re-read, re-apply, retry loop (≤ 5) | Solved | `sed -n 45,63p B/features/account/identitypins/IdentityPinsStore.kt`; `grep -n "isStaleVersion\|MAX_WRITE_ATTEMPTS" W/features/account/identityPins.ts` |
| 15 | **The ETag read pattern** | — | `listNodeMedia` takes `If-None-Match` = `mediaRevision`. It answers `304`, or a keyset delta with `ETag` | Read cache only; no write sends it | — | `grep -n "ifNoneMatch\|ETag" B/features/media/MediaResource.kt`; `grep -rn "bumpMediaRevision(" B` |
| 16 | `unsealNodeMeta` on a blob that does not open | — | Returns `{ v: 1 }`. A read-modify-write after that re-seals **only its own field** | — | Wipes every sibling. A version does not help here, because the write is not stale. Folded as D7 (#273); measured in row 18 | `sed -n 26,40p W/features/memory/nodeMeta.ts` |
| 18 | **#273, node:** a meta write over a present blob that does not open | — | `unsealNodeMeta` → `{ v: 1 }`, then the writer's one field | — | **Measured** (throwaway vitest, 2026-10-03): `setMemoryDate` over a blob sealed under another key that held `location: Lisbon` sent `updateNode` once, with `{"v":1,"date":{…}}` — the place is gone | `sed -n 26,40p W/features/memory/nodeMeta.ts` |
| 19 | **#273, media:** a backfill over a tier that does not open | — | `unsealMeta` → `null`, then `{ ...(meta ?? { v: 1 }), field }` | — | **Measured:** `backfillMediaType` over an unopenable tier PUT `{"v":1,"mediaType":"video/mp4"}` — capture time and GPS gone | `grep -n "meta ?? { v: 1 }" W/features/memory/media.ts` |
| 20 | **Media writers spread a projection** (found by this fold) | — | `unsealMeta` returns a normalized copy without `placeName`/`placeLevel`; all three media writers spread it | — | **Measured:** a tier holding `placeName: Lisboa` → `unsealMeta(…).placeName` is `undefined`, and `setMediaMeta` wrote a tier without it. So every date correction drops the resolved name, and the lightbox re-resolves a name it already sealed on every open | `grep -n "placeName" W/features/memory/media.ts` |
| 21 | **#274:** a mark / pin toggle before the own set loaded | Self | Whole set from `itemFavoritesStore.marks` / `favoritesStore.ids` | No `loaded` check | **Measured:** with `loaded = false`, `toggleItemFavorite('m1')` saved `['m1']` and `toggleFavorite('n1')` saved `['n1']`. A failed load also leaves `loaded = false` (`loadMarks` swallows) | `grep -n "loaded" W/app/itemFavoritesStore.ts W/app/favoritesStore.ts` |
| 17 | **The guard's subject, from two angles** | — | **Measured:** `grep -nE "^    (put\|patch):" contracts/api/*.yaml \| wc -l` gives **14**. `grep -nE "operationId: (rename\|set\|update\|change\|commit\|move\|put)[A-Z]" contracts/api/*.yaml` gives **20**: the same 14 plus `renameGroup setScopeIcon moveBucketMedia commitCoverBlob changeTenantDomain changePassword` | — | Keyed on the method, the guard misses six POST edits. The difference is the finding | as stated |

**Row 1 hazard paths (high risk):**
- **(a) A self-heal that wipes a peer's place.** The tree is loaded at space entry (`W/app/activeSpace.ts:185`).
  It is reloaded only after create, rename or delete (`W/views/MemoryHomeView.vue:182-191`). `settle()` runs
  `reconcileAuto` and then the location self-heal against `ctx.node().wrappedMeta`, which is that cached blob,
  and both PATCH it whole. `settle()` runs on open and after every media-list refresh, including a peer's
  upload arriving through the 20 s poll (`grep -n "async function settle" -A3
  W/features/memory/components/MemoryDetail.vue`). A peer's newer place or cover is wiped by a member who
  edited nothing.
- **(b) A self-heal that overwrites a peer's manual date.** The guard *"a manual date is the user's — never
  overwritten"* (`W/features/memory/useMemoryDate.ts:65`) is evaluated against the stale blob. A peer's newer
  manual date is overwritten with an `auto` range.
- **(c) An editor that wipes a sibling.** Any editor save (date, place, cover) re-seals stale siblings.

**Row 2 hazard path (medium risk):** the lightbox snapshots the tier when an item is selected, and the
place-name and media-type backfills fire later from that snapshot. A peer's date correction made in between is
silently undone.

## Decisions

- **D1: two blobs get a version check: node `wrappedMeta` and media `wrappedMeta`.** These are the only
  writes that several accounts make *and* that re-send fields the writer did not edit (Inventory 1–2).
  - The uc.memory.date note *"eventual consistency over locking … no data loss"* is superseded (Q2): it holds
    for the derived range only.
- **D2: a dedicated counter per blob.** Add `veyq_node.meta_version` and `veyq_media.meta_version`
  (`BIGINT NOT NULL DEFAULT 0`) in the next free `V<n>__`.
  - Run `ls backend/src/main/resources/db/migration | sort -V | tail -1` at implement time. At refine the last
    one was `V64__share_write_verifier.sql`.
  - The counter is bumped by exactly the write that replaces that blob, and by nothing else:
    `NodesStore.applyUpdate` when `wrappedMeta` is present, and `MediaStore.setMeta`.
  - It is **not** `media_revision`. That counter moves on attach, trash, restore, move and meta changes, so a CAS
    on it would turn every concurrent upload into a date-edit conflict (Q2).
  - The creates (`NodesStore.kt:119`, `MediaStore.kt:164`) are born at 0.
  - Locator for every blob write: `grep -rn "\.wrappedMeta = \|this.wrappedMeta = " B`. Today it finds those
    two creates, `applyUpdate` and `setMeta`.
- **D3: the wire contract.**
  - `NodeSummary` and `MediaSummary` gain `metaVersion` (integer, `x-veyq-visibility: structure`, as the pins'
    `version` is).
  - `UpdateNodeRequest` gains `metaVersion`. It is **required whenever `wrappedMeta` is present** (else
    `400 invalid_request`), so an old client cannot slip past. Rename, move and reorder do not send it.
  - `MediaMetaUpdate` gains a required `metaVersion`.
  - The server writes the blob with a conditional UPDATE: `… where id = ? and meta_version = ?`. That is the
    pins' compare-and-set (`IdentityPinsStore.kt:58-62`), with no lock and no read-then-compare. It then
    branches on the row count.
  - On a miss, the answer is `409 stale_version`. The body is a new `StaleSealedMeta` schema
    `{ code, wrappedMeta, metaVersion }` in `_common`, carrying the current value (Q4). Nothing is written. A
    request that also renamed the node is refused whole.
  - A successful write stores `metaVersion + 1`, and that number is fixed by the compare-and-set itself. So the
    client derives it, and no success response changes shape: `updateNode` stays `204`, which its rename callers
    rely on.
  - The seal stays bound to the node or media only. Binding it to the version (ADR 0072's pin form) would
    detect an operator rollback, which is a different threat and out of scope here.
- **D4: last-writer-wins is a recorded decision, not a default.**
  - **Rows 3–8: same-field writes.** The write carries only what was typed, so nothing the editor never saw is
    re-sent.
  - **Rows 9–13: one writer.**
  - **Row 11** was already accepted.
  - The decision and its reason are written into each operation's contract (D6), so it is visible where the
    next editor is added.
- **D5: the client loop is the pins' loop** (`W/features/account/identityPins.ts`, `mutateAndSave`).
  - Each writer is expressed as `apply(freshMeta) → meta | null`.
  - The tree and the media list carry `metaVersion`. `patchNodeMeta` stores the blob and its version.
  - On `409 stale_version`, the client takes the body's blob and version, re-runs `apply`, and retries. Retries
    are bounded by the same `MAX_WRITE_ATTEMPTS = 5`.
  - **Editors** re-apply only their own field. If the fresh value of *that* field differs from the value the
    editor opened with, the editor re-opens with the current value and a notice instead of retrying (Q5).
  - **Self-heals** (`reconcileAuto` and the location self-heal) re-run their whole decision on the fresh blob,
    including the "manual is never overwritten" check. They return `null` (no write) when that check says so.
  - **Media backfills** re-apply their one field.
- **D6: a guard, `scripts/validate_write_concurrency.py`.**
  - **Subject, derived on every run:** every operation whose method is PUT or PATCH, ∪ every operation whose
    `operationId` starts with an edit verb (`rename|set|update|change|commit|move|put`). Each must declare an
    operation-level `x-veyq-concurrency` (precedent: the operation-level `x-veyq-channel`), with one of these
    values:
    - `version`: the request schema has a required-or-conditional integer `*ersion` property, and the
      operation's `409` description names `stale_version`.
    - `last-writer-wins`, with a non-empty `reason`.
  - **Reverse angle:** any request schema reachable from a non-GET operation that carries a `version` /
    `*Version` property must belong to an operation declared `version`.
  - **Its limit:** the guard proves a declaration and its wire form. It never proves the server compares the
    version; the red-first slice tests do. A POST edit named outside the verb list slips through, and the
    docstring says so.
- **D7: a writer never seals a blob it could not open, nor one rebuilt from a projection** (#273, Q8–Q9).
  - Node: a write-path open (`nodeMeta.ts`) answers `{ v: 1 }` only for an **absent** blob; a present blob that
    does not open or does not parse is refused (`MetaUnreadable`). Every node writer goes through it, including
    each retry's re-open of the `409` body.
  - Media: the same rule in `persistMeta`, and the writers start from the **raw** opened tier — every field,
    known or not, kept verbatim — never from `unsealMeta`'s normalized copy. `unsealMeta` gains
    `placeName`/`placeLevel`, so the lightbox stops re-resolving a name it already sealed.
  - Who hears about it: a self-heal or backfill stops silently; an editor shows one line (the conflict notice's
    sibling, `memory.meta.unreadable`).
  - A version does not catch this: the write is not stale, it is blind. That is why it is its own rule.
- **D8: a whole-set write never starts from a set it has not loaded** (#274, Q10). `toggleItemFavorite` refuses
  (no optimistic flip, no write) unless `itemFavoritesStore` holds a successful load for **that** node;
  `toggleFavorite` refuses unless `favoritesStore.loaded`. A failed load leaves `loaded` false, so it refuses too.
- **D9: the cover editor's conflict exception** (Q11). `onCoverSave` surfaces a same-field conflict only when no
  blob operation (`uploadCoverBlob`, `deleteCoverBlob`) ran in that save; after one, its own cover is re-applied
  on the fresh blob.

## Phases

- [x] 1 docs (refined state first). Write an ADR at the next free number (run `ls docs/decisions | tail -1`; it
  was 0076 at refine) covering D1–D5: *"a write that re-sends what its writer did not edit, and that more than
  one account makes, carries the version it read"*.
  - Write `uc.memory.concurrent-curation` (Q6) and add it to `satisfies:`.
  - **Edit** uc.memory.date's E5 and its *"Eventual consistency over locking"* note: both point at the new
    use-case, and the `media_rev` sentence goes.
  - **Narrow** en.memory.favorites § Last-write-wins to cite the ADR's recorded-LWW rule.
  - Before writing the site list, sweep the whole tree for the superseded claim:
    `grep -rn "no data loss\|Eventual consistency over locking\|media_rev" docs wiki`.
  - Write a `planned` coverage file for the new use-case.
  - · Done when: `python3 scripts/validate_changes.py` and `python3 scripts/validate_coverage.py` are green.
- [x] 2 contracts. D3 on `content`, `media` and `_common`, and `x-veyq-concurrency` on every subject operation
  (D6, Inventory 17), with the guard written **red first**: run it before the annotations land and record its
  list.
  - This phase reshapes `UpdateNodeRequest` and `MediaMetaUpdate` on existing tags.
  - **Expected red, web only:** `W/features/memory/media.ts:1086`. Its `mediaMetaUpdate: { wrappedMeta }` lacks
    the now-required `metaVersion`. `UpdateNodeRequest.metaVersion` is conditional, so it is optional in the
    schema and its four callers stay green.
  - **Expected green, backend:** `grep -rn "MediaMetaUpdate(\|UpdateNodeRequest(" backend/src` finds no
    construction, and a handler that only reads the body still compiles. Run `npm run check` once to see the red
    named above.
  - · Done when: `npm run generate:api` succeeds, the new guard is green after the annotations, and
    `validate_request_bounds.py`, `validate_field_visibility.py` and `validate_contracts_common.py` are green.
- [x] 3 backend. D2 migration and the CAS in `NodesStore.applyUpdate` and `MediaStore.setMeta`. The handlers map
  the miss to `409` with `StaleSealedMeta`. `metaVersion` goes into both summaries.
  - Classify `OperatorBlindProbeTest`'s view of the new response field.
  - · Done when: DoD B1–B4 are green, red first, and `./gradlew check` is green in `backend/`.
- [x] 4 web. D5 in `memoryDate.ts`, `memoryLocation.ts`, `memoryCover.ts`, `useMemory{Date,Location,Cover}.ts`,
  `treeStore.ts` (`patchNodeMeta` carries the version), `media.ts` `persistMeta` and its three callers, and
  `MediaLightbox.vue` (the notice on a same-field conflict).
  - D7 in `nodeMeta.ts` (the write-path open) and `media.ts` (raw-tier writers; `unsealMeta` returns the place
    name). D8 in `app/itemFavoritesStore.ts` and `app/favoritesStore.ts`. D9 in `useMemoryCover.ts`.
  - The gallery's live poll (`useMediaGalleryLive.ts`) patches `metaVersion` beside `wrappedMeta`.
  - · Done when: DoD W1–W9 are green, red first, and `npm run check` is green in `web/`.
- [x] 5 docs close. Coverage refs for the new use-case's criteria. The `developer-styleguide.md` bullet points
  at the ADR. Inbox #271's status cell says done.
  - · Done when: the trust-chain glob is green.

## Definition of done

- **Baseline before the first edit:** run `for f in scripts/validate_*.py; do python3 "$f"; done`, and
  `./gradlew check` / `npm run check` once, and record the exit codes. A red after this is then this CR's own.
- **Red first, per covered entity.** Each test is written before its fix and records its failing assertion.
  - **B1** (`NodesSliceTest`): write meta at `metaVersion 0`, which succeeds; the listed `metaVersion` is then 1.
    A second write stating 0 must be `409 stale_version` with the first blob and 1 in the body, and the stored
    blob unchanged.
    **Today the second write is `204` and replaces the blob.** Control: a write stating 1 succeeds, and the
    listed version is then 2.
  - **B2:** `wrappedMeta` without `metaVersion` → `400`. A rename alone needs no version and leaves
    `metaVersion` unmoved.
  - **B3** (`MediaSliceTest`): the same as B1 for `updateMediaMeta`. `media_revision` moves on the success and
    **not** on the `409`.
  - **B4:** the stale write that also carries a new `encryptedName` leaves the name unchanged.
  - **W1** (`memoryDate`/`useMemoryDate` tests): a date save from a stale blob meets a `409` whose blob holds
    `location: X`. The retry's sealed blob carries the new date **and** `X`. Today no retry exists and `X` is
    absent.
  - **W2:** `reconcileAuto` from a stale `auto` blob meets a `409` whose blob holds a manual date. No second
    write is made.
  - **W3:** a same-field conflict in the date editor makes no retry and surfaces the current value.
  - **W4:** `backfillPlaceName` from a snapshot without `capturedAtOverride` meets a `409` whose tier has it.
    The retry keeps the override.
  - **W5:** the location and cover editors each get one W1-shaped case.
  - **W6 (#273, node, live defect):** a date save and the `auto` self-heal over a present blob that does not open
    make no `updateNode`; the save tells the user. Today: one PATCH carrying only the date (Inventory 18).
  - **W7 (#273, media, live defect):** `backfillMediaType` / `backfillPlaceName` over an unopenable tier make no
    PUT. Today: a PUT carrying only the backfilled field (Inventory 19).
  - **W8 (projection, live defect):** a date correction on a tier holding `placeName` keeps it, and `unsealMeta`
    returns it. Today: dropped (Inventory 20).
  - **W9 (#274, live defect):** a mark toggle and a pin toggle before their set loaded make no save and no
    optimistic flip. Control: after a load, the toggle saves the loaded set plus the new id.
  - **W10 (D9):** a cover upload whose meta write meets a same-field `409` retries with its own cover; a crop-only
    (`keep`) save on the same `409` re-opens instead.
- **Bite, per lever** (snapshot and restore, `changes/README.md` § bite-proof):
  - Drop `and meta_version = ?` from either CAS: B1/B3 go red with `204`/`200`.
  - Stop the bump: B1's control goes red, because the write at 1 gets `409`.
  - Re-run `apply` on the stale blob instead of the conflict body's: W1 and W4 go red.
  - Skip the manual-date re-check after a `409`: W2 goes red.
  - D7, node: make the write-path open return `{ v: 1 }` for a present blob that does not open: W6 goes red.
  - D7, media: start `persistMeta`'s writers from `unsealMeta`'s copy instead of the raw tier: W8 goes red.
  - D8: drop the `loaded` check from `toggleItemFavorite` (then from `toggleFavorite`): W9 goes red, per store.
  - D9: surface the conflict regardless of a blob operation: W10's upload case goes red.
  - **Guard:**
    - Delete `x-veyq-concurrency` from `updateNode` → red, naming it.
    - Keep `version` but remove `metaVersion` from `UpdateNodeRequest` → red.
    - Drop `stale_version` from its `409` → red.
    - Add a forged `PUT` with no declaration → red.
    - Each case is run once on the untouched tree first, and green there (§ untouched tree).
- **Controls:** every 409 test ends with a write at the current version that succeeds. A server that refuses
  everything cannot pass.
- `./gradlew check` and `npm run check` are green, the trust-chain glob is green, and inbox #271's status
  cell points here.

**The refine's proposals, as they stand:** P1 became inbox #272, P4 became #275 (both left alone here). P2 and
P3 became #273 and #274, which this CR now folds (D7, D8). P3's own wording ("the caller's own entry was not
recognised at load") is the half deliberately **not** changed — an own entry that does not verify is someone
else's by ADR 0068 §9, and the next toggle replacing it is that ADR's recovery; the half that is a defect is the
toggle before any load (Inventory 21).

## Retrospective

- **Two predictions in the refine were wrong, and both were one command away.** Phase 2 said "expected green,
  backend"; an added *required* response field reddens the server that constructs the summary
  (`ContentResource.kt:122`). And B2/B3 assumed a missing required `metaVersion` is a boundary 400; a required
  integer generates a primitive Long that Jackson fills with 0 (`mediaMetaWithoutVersionIs400` answered 200).
  Root cause: the refine ran the subject locators but not the generator against a sketched schema. *Next time:*
  when a CR adds a field to a request or response schema, the refine sketches it and runs `./gradlew
  compileKotlin` once. (Moved: `changes/README.md` § phases, `backend/CLAUDE.md` § validation.)
- **The refine never asked which gates the touched files sit in.** `nodeMeta.ts` is in the web Stryker scope,
  which admits only pure files; the first web draft put the PATCH there, and the gate (break 95) stayed green at 98.7 %
  while the file’s own score dropped from 100 % to 96 % — a green that hid the drift. Found only because I opened the
  manifest before committing. *Next time:* the inventory greps `web/stryker.mutate.json`,
  `packages/crypto/stryker.mutate.json` and `backend/pitest.scope.json` for every file it plans to edit.
- **The guard's own wording needed one honest exception the CR did not foresee:** its reverse angle found
  `acceptTenantTerms` (a POST outside the verb list carrying `version`, conflict code `terms_version_changed`).
  The first run on the untouched tree named it, so `conflictCode` became part of the declaration rather than a
  hard-coded `stale_version`. The red-first run did its job; nothing to change in how we work, only noted.
