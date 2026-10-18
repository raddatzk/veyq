---
id: cr-memory-banner
title: A Memory's banner — an image or a small video above the menu bar, the Memory's own attachment, framed by a sealed focal point
status: planned
satisfies: ["uc.memory.banner"]
adrs: ["0102", "0047", "0072", "0077", "0079", "0082", "0086", "0093"]
invariants:
  - operator-blind - the server learns that a banner exists and whether it is a video, never which medium, the focal point, the frame or the description
  - a banner's bytes are never a medium - not in the media list, the item count, the live revision or the media trash
  - the attachment slots and the meta that names them are written in one transaction
  - a banner video is never the original and never plays on its own
  - share view = member view - a guest sees the banner; rights only remove the action
---

## Goal

Build [uc.memory.banner](../docs/features/memory/use-cases/uc.memory.banner.md) on
[ADR 0102](../docs/decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md): a Memory (and a
Chapter) can carry one image or one video above its menu bar — a **banner**, set independently of the cover, by a member who
may write the scope, seen identically by link guests. Its bytes are the Memory's own attachments in core's
`veyq_node_attachment` (slots `BANNER`, `BANNER_POSTER`), whether uploaded for it or made from a gallery medium's original on
the editor's device; its frame (fill around a focal point, or fit with blurred sides) and description live sealed in the
node's `wrappedMeta`. A video banner plays a small H.264 copy made in the browser through WebCodecs
([en.memory.playback-derivative](../docs/features/memory/enablers/en.memory.playback-derivative.md)) — a poster and a play
button, never autoplay, never the original. The owner decided the product shape on 2026-10-18; this CR's owner questions
are the open choices below it.

## Context & constraints

- State: [uc.memory.banner](../docs/features/memory/use-cases/uc.memory.banner.md),
  [en.memory.playback-derivative](../docs/features/memory/enablers/en.memory.playback-derivative.md),
  [ADR 0102](../docs/decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md) (Proposed; its
  decisions 1–4 carry Q1, Q2, Q4, Q5 below as recommendations). The pattern reused end to end is the cover's:
  [uc.platform.node-cover](../docs/features/platform/use-cases/uc.platform.node-cover.md) (one write for the attachment and
  the meta), [uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md) (the lightbox's *Set as cover*).
- ADRs: [0047](../docs/decisions/0047-inline-video-playback-range-streaming.md) (playback through the SW range shim; the
  gallery keeps the original), [0072](../docs/decisions/0072-a-seal-binds-its-object.md) (the blob seal binds blob, node,
  role), [0077](../docs/decisions/0077-a-write-carries-the-version-it-read.md) (`metaRevision`),
  [0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments; contracts by
  owner; core names no application concept), [0082](../docs/decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
  (export, import, copy), [0086](../docs/decisions/0086-one-word-per-meaning.md) (why *banner*),
  [0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 4, 8, 9
  (hold, last editor, `K_field`).
- Rules: [Memories' role matrix](../docs/apps/memories.md#what-a-guest-may-do--the-role-matrix) (no guest meta or cover
  operation; the banner joins that row); `CLAUDE.md` (no deprecated dependency — the muxer); `docs/ai/README.md` § a UI
  slice is clicked in a real browser.
- **Neighbours, not this CR's:** a small tier for **gallery** videos (ADR 0047's question; this CR's transcoder would be its
  enabler — inbox #493, filed with this CR); the gallery's load time on large Chapters (inbox #488–#490) — the banner draws
  without the media list precisely so it does not wait on them.

### Measured substrate (worktree at `4e55f576`, clean)

Every row is a command; run it, don't trust the summary. The summary is what it printed at refine.

| Fact the plan leans on | Locator | At refine |
|---|---|---|
| The cover's one-write pair is core's, in the `content` tag | `grep -n 'operationId: setNodeCover\|operationId: removeNodeCover' contracts/api/content.openapi.yaml` | 2 ops |
| The cover handler: WRITE access, kind opt-in, session bound to node + slot | `grep -n 'fun set\|fun remove\|takesCover\|boundTo\|access.check' backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodeCoverHandler.kt` | the order the banner handler mirrors |
| The cover store: attach/detach + meta CAS in one transaction, rollback on a stale meta | `grep -n 'attachments.attach\|attachments.detach\|applyUpdate\|setRollbackOnly' backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodeCoverStore.kt` | the template |
| `AttachmentStore`: one blob per slot, compare on the replaced blob, the replaced one freed with a reclaim record | `grep -n 'fun attach\|fun detach\|deleteBlobRow' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentStore.kt` | `attach`, `detach` |
| The slot is an open token, ≤16 chars (`BANNER_POSTER` is 13) | `grep -n 'A-Z0-9_' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentStore.kt contracts/api/_common.openapi.yaml` | `^[A-Z][A-Z0-9_]{0,15}$` |
| Memories declares its member roles and refuses guest roles here — and today **`MEMBER = GUEST`** | `grep -rn 'MemberBlobRoles\|admitsGuestRole\|val MEMBER\|val GUEST' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/` | `MemoriesMemberBlobRoles`, `MemoryBlobRoles` (`val MEMBER: Set<String> = GUEST`), `MediaShareGuestWrites` |
| Trash purge and scope erasure walk every attachment row (no slot filter) | `grep -n 'phase\|NodeAttachmentEntity.delete\|deleteBlobRow' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentNodeSubtreeEraser.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentScopeEraser.kt` | phase 200 each |
| Account erasure / tenant deletion purge scopes through `ScopePurge`; an attachment's uploader is kept as attribution | `grep -n 'scopePurge.purge' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/erasure/AccountPurge.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantPurge.kt`; `grep -n 'veyq_node_attachment' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/erasure/AccountPurge.kt` | covered without a slot list |
| The hold copies every attachment row of a reported subtree and pins its blob | `grep -n 'veyq_node_attachment' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/hold/CoreHoldSnapshot.kt`; `grep -rln 'HoldSnapshotContributor' backend --include='*.kt'` | phase 200, `Subtree` only |
| An ITEM report is media only — the banner is reported with its Memory | `grep -rn 'class .*ReportableItems' backend --include='*.kt'` | `MediaReportableItems` only |
| Quota: every `veyq_blob` counts for the tenant; only a guest's attachment counts for a link | `grep -n 'SUM(b.byte_size)' backend/core/src/main/kotlin/me/raddatz/veyq/core/quota/StorageUsage.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/quota/StorageQuota.kt` | tenant: all blobs; link: guest attachments |
| No new storage-key table, so no claim list, backup index or e2e decoy changes | `grep -n 'CLAIM_TABLES =' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReconcileStore.kt`; `grep -n 'FROM veyq_blob\|FROM veyq_hold_pin' scripts/instance_backup.py`; `grep -n 'def seed_project_decoy' -A20 scripts/run_web_e2e.py \| grep CREATE` | `veyq_blob` + `veyq_hold_pin` carry every attachment's key |
| No new blob-reference arm (a slot reuses `veyq_node_attachment.blob_id`) | `grep -n -A4 'EXPECTED_ENTITY_ARMS = \[' scripts/validate_blob_reference_arity.py` | 3 arms, unchanged |
| A guest reads attachments through the share's gated route | `grep -n 'veyq_node_attachment\|NodeAttachmentEntity' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentSharedSubtreeContributor.kt`; `grep -n 'shareChunkUrls' web/src/core/node/cover/coverStore.ts` | member `openBlob`, guest `shareChunkUrls` |
| The meta is one `node-meta` seal; a new top-level key is preserved by old clients; its size is capped | `grep -n "'node-meta'" packages/crypto/src/seal.ts`; `grep -n 'type RawNodeMeta\|export function editField' web/src/core/node/nodeMeta.ts`; `grep -n 'maxLength: 8192' contracts/api/content.openapi.yaml`; `grep -n 'MAX_PARTICIPANTS =' web/src/features/memory/memoryParticipants.ts` | 8192 base64 chars, shared with 100 participants, cover, place, date |
| A `BlobRef` is what a sealed meta names an attachment by | `sed -n 15,25p web/src/core/blob/types.ts`; `sed -n 1,12p web/src/core/node/cover/coverModel.ts` | `{blobId, chunkCount, byteSize, wrappedBlobKey, ownerId, role, wrappedBlobMeta?}` |
| The cover's derive and size bound, reused for an image banner | `grep -n 'COVER_SPEC =\|MAX_COVER_SOURCE_BYTES =' web/src/core/node/cover/coverWrite.ts` | 2048 / 0.85; 40 MiB |
| No small video exists; a video's poster is a 2048 JPEG; nothing transcodes | `grep -n 'deriveVideoPoster\|TIER_SPECS' web/src/shared/image/mediaDeriver.ts`; `grep -rni 'webcodecs\|VideoEncoder\|ffmpeg\|MediaRecorder' web/src web/package.json` | poster only; no transcoder |
| Playback of an attachment through the SW shim exists (Depot) | `grep -rln 'registerPlayback' web/src --include='*.ts' --include='*.vue'` | `useFileViewer.ts`, `MediaLightbox.vue` |
| The Memory page: core's `NodeHeader`, then the tab row; nothing sticky in Memories; the shell's top bar is | `grep -n '<NodeHeader\|<Tabs\|memory-cover-preview' web/src/features/memory/components/MemoryDetail.vue`; `grep -n 'sticky' web/src/styles/layout.css`; `grep -n 'app-topbar-height:' web/src/styles/base.css` | banner goes above `<NodeHeader>`; offset 72px / compact / 0 |
| A guest renders the same `detail` component, without the shell's top bar | `grep -n ':is="detail"' web/src/core/sharing/ShareTreePage.vue`; `grep -n "path: '/s/:shareId'" web/src/router.ts` | `layout: 'plain'` |
| Who may set it: the cover's right | `grep -n 'setCover\|editMeta' web/src/features/memory/rights.ts` | member `write`; every guest `false` |
| The kind registration (one `detail` for MEMORY and CHAPTER) and the cover's application seam | `grep -n 'cover:\|registerKind\|detail:' web/src/features/memory/register.ts` | one registration each |
| The lightbox's *Set as cover* — the entry point *Use as banner* mirrors | `grep -n 'setCover\|coverMediaId\|suspendKeys' web/src/features/memory/components/MediaLightbox.vue` | props + event |
| Export/copy carry the cover inside Memories' content; the schema's def | `grep -n 'content.cover = \|async function coverOf' web/src/features/memory/exportImport.ts`; `grep -n 'memoriesCover' contracts/export/veyq-subtree.schema.json` | the banner joins beside it |
| Where a new Memories spec file must be registered | `git grep -ln 'contracts/api/memories'` | `build.gradle.kts` (by directory), `web/package.json`, `scripts/contract_specs.py`, `web/src/api/client.ts`, `web/src/features/memory/api.ts` |
| Every new operation must be classified | `grep -rln 'class OperatorBlindProbeTest' backend` | `backend/src/test/.../OperatorBlindProbeTest.kt` |
| The published page the banner joins (EN + DE) and the sidebar | `ls site/src/content/docs/memories/set-a-cover.mdx site/src/content/docs/de/memories/set-a-cover.mdx`; `grep -n "'memories/" site/astro.config.mjs` | a sibling page `memories/add-a-banner` |

**Spot-check before relying on the table** (`changes/README.md`): the row the design leans on hardest is "every erasure
and the hold walk attachments without a slot filter". Re-run its two commands and read the two eraser bodies before phase 2.

## Decisions this CR takes

1. **The word is *banner*** (ADR 0102 decision 1; Q1). Field `banner`, slots/roles `BANNER`, `BANNER_POSTER`, operations
   `setMemoryBanner` / `removeMemoryBanner`, i18n `memory.banner.*`, component `MemoryBanner`.
2. **The sealed shape** is ADR 0102 decision 2's, with the `BlobRef`s **without** `wrappedBlobMeta` (the types are fixed:
   `image/jpeg`, `video/mp4`). A reader normalizes best-effort (a malformed focal point → the centre; an unknown `frame` →
   `fill`; an unknown shape → no banner, preserved untouched), never throws.
3. **The meta budget is measured, not assumed.** Phase 3 adds a test that seals the worst case — 100 participants, a cover
   upload ref, a place with every field, a range date, a video banner with a 200-character description of 4-byte characters
   — and asserts it under `maxLength: 8192`. If it is over, the implementer hands back (the options: raise `maxLength` in
   `content.openapi.yaml`, a core contract, or shorten the description bound); it is not decided in place.
4. **Contracts:** a new file `contracts/api/memories/memory-banner.openapi.yaml`, its own tag `memory-banner` (a new tag
   compiles, `changes/README.md`), paths `PUT /api/scopes/{scopeId}/nodes/{nodeId}/banner` and
   `POST /api/scopes/{scopeId}/nodes/{nodeId}/banner/removal` (the cover's shape). `SetMemoryBannerRequest`:
   `uploadId`, `posterUploadId` (required for a video, absent for an image — the server cannot know which, so: present ⇒
   the poster slot is filled, absent ⇒ it is emptied), `expectedBlobId`, `expectedPosterBlobId` (each uuid or null),
   `wrappedMeta`, `metaRevision`. `RemoveMemoryBannerRequest`: `blobId`, `posterBlobId` (nullable), `wrappedMeta`,
   `metaRevision`. Conflicts: `banner_stale` (with the current blob ids), `stale_revision` (with the current meta),
   `scope_deleting`; `banner_unreadable` is the client's, not the wire's. `x-veyq-concurrency: revision (metaRevision)`,
   a rate-limit key as the cover's. `_common`'s `BlobRole` description names Memories' two new roles. The export schema gains
   `memoriesBanner` (`file`, `poster?`, `medium`, `frame`, `focal`, `alt?`, `mediaIndex?`) beside `memoriesCover`, in both
   Memory and Chapter content.
5. **Backend (Memories):** `BANNER`, `BANNER_POSTER` join `MemoryBlobRoles.MEMBER`, never `GUEST` — which splits the two
   sets for the first time (`MEMBER` is `GUEST` today, and its KDoc says so: it becomes `GUEST + BANNER_ROLES`, the KDoc
   rewritten, and `MediaUploadCompletion` must still refuse a banner role for a medium — a test pins it). A
   `MemoryBannerHandler` (access WRITE, kind `MEMORY`/`CHAPTER` via Memories' own kind rules, each session bound to the node
   and its slot, `verifyBlob`) and a `MemoryBannerStore` (both slots + `NodesStore.applyUpdate` in one transaction, rollback
   on a stale meta — `NodeCoverStore` is the template, its code is not shared by copy: if the two stores' bodies end up
   alike, the implementer lifts the common part into core's attachment package, a core change that names no slot). No
   migration, no new table, no eraser, no hold contributor, no claim — phase 2 proves each of these by a test that names the
   slot, not by the absence of code.
6. **Web — data:** `features/memory/banner/` holds the model (`normalizeBanner`, `bannerStyle(focal)` →
   `object-position`), the store (keyed on the sealed meta as `coverStore`; member `openBlob`, guest `shareChunkUrls`; object
   URLs revoked on change), the writer (`setMemoryBanner` / `removeMemoryBanner` / a framing-only `writeNodeMeta` via
   `editField('banner', …)`, re-applied on `stale_revision`), and the transcoder (`playbackDerivative.ts`, the enabler, with
   `isConfigSupported` gating and cancel). A gallery source reads the medium's **original** through the existing download
   path, never the preview.
7. **Web — display:** `MemoryBanner.vue` rendered by `MemoryDetail.vue` above `<NodeHeader>` (Memories' own view: no
   registry slot and no core change). Frame by container query on `.memory-detail` (it is already an `inline-size`
   container): 3:1 above a breakpoint, 16:9 below, `max-height: 35vh`, space reserved before the bytes arrive. `fill`:
   `object-fit: cover; object-position` from the focal point. `fit`: a backdrop `<img>` of the same object URL (`filter:
   blur(…)`, scaled, `aria-hidden`) behind the contained medium. Video: the poster plus the lightbox's play-button pattern;
   on click `registerPlayback` with a chunk provider over the `BANNER` ref (Depot's `useFileViewer` is the model), then
   `<video controls playsinline>`; the fallback without a SW decrypts the small copy whole (it is small). The tab row
   becomes `position: sticky` with a `top` from a variable the shell sets (`--app-topbar-height`, the compact header's
   height, 0 on the plain share layout).
8. **Web — editing:** a *Banner* action in `headerActions` (`shown: can.setBanner`, a new right equal to `setCover` in every
   row of `rights.ts`); *Use as banner* in the lightbox (`canSetBanner`, `bannerMediaId` marks *Current banner*; the cover's
   `suspendKeys` pattern); `BannerDialog.vue` (file input `image/*,video/*`, the focal-point handle on the medium, the two
   frame previews, the Fill/Fit switch for a portrait medium, the description, Remove, Save, transcode progress with cancel).
   The delete confirmation of the banner's origin medium carries the notice and the *Remove the banner too* option (ADR 0102
   decision 7). i18n EN + DE.
9. **Export / import / copy:** Memories' content writes `banner` (files `banner.jpg` or `banner.mp4` + `banner-poster.jpg`,
   and `mediaIndex` when the origin medium is in the export); import re-uploads the files into the slots through
   `setMemoryBanner` (no re-transcode: the archive holds the small copy); a copy moves the blobs by frame as it moves an
   uploaded cover (`uploadCoverBlob` is the model). A banner whose file is missing is a stated loss (`copyLosses`), never a
   failed import.

## Phases

- [ ] **0 spike (web, no product code)** — measure, in the e2e browsers (Chromium, WebKit, Firefox under Playwright),
      `VideoEncoder.isConfigSupported` for H.264 at 1280 and `AudioEncoder.isConfigSupported` for AAC (and the fallback audio
      codec the engine that lacks AAC would need, played back in MP4 by all three); choose the muxer (maintained, AGPL-compatible
      licence, `npm view <pkg> deprecated` empty, its bundle size). Record the matrix and the choice in
      `en.memory.playback-derivative.md` § Open (turning it into a Decision). · Done when: the matrix is written there with
      the commands that produced it. **Hand back** if no maintained muxer exists, or if Chromium itself cannot encode H.264 —
      the design then needs the owner. One engine lacking it is ADR 0102's E3 (refused on that device), not a hand-back.
- [ ] **1 contracts** — decision 4. Classify both operations in `OperatorBlindProbeTest`. · Done when: `npm run generate:api`
      (web) and the backend's generation succeed; `scripts/validate_*.py` green except `validate_guard_gaps.py`; `./gradlew
      check` red **only** in `ModuleCompositionTest` (a generated API no resource implements), stated, not stubbed
      (`changes/README.md` § a new tag compiles). One use of each new schema written in Kotlin and in TypeScript before the
      commit (the generator keeps property names).
- [ ] **2 backend** — decision 5, with slice tests in `backend/apps/memories/src/test/.../banner/`: set an image; set a video
      (two slots); replace video → image frees the poster; replace image → image frees the old; remove frees both; a stale
      meta attaches nothing (`stale_revision`, both slots unchanged); a stale expected blob (`banner_stale`); a guest of every
      link role cannot start an upload in either role nor call either operation; a member without write cannot; a `FOLDER`
      or a Depot kind is `illegal_target`; trash keeps both rows, restore shows them, the purge frees both blobs with reclaim
      records; a scope purge frees both; a `NODE` report's hold has both rows and both pins; the tenant quota counts both;
      **the media list, the item revision and the media trash are unchanged** by any banner write. · Done when: `./gradlew
      check` green (`ModuleCompositionTest` included).
- [ ] **3 web — data and the transcoder** — decisions 2, 3, 6, 9 (model, store, writer, transcoder, export/import/copy).
      · Done when: `npm run check` (web) green; the meta-budget test of decision 3 green or handed back; the transcoder's
      un-mocked browser test (below, bite 8) green in Chromium.
- [ ] **4 web — display and editing** — decisions 7, 8. · Done when: `npm run check` green, and a **browser pass** on the
      dev stack (`docs/ai/README.md` § a UI slice is clicked): a landscape image (fill, focal point moved, checked at a wide and
      a phone width), a portrait image (fit with blurred sides, then switched to fill), a portrait video from the gallery
      (poster, no playback before the click, plays after, the network list shows the `BANNER` blob's chunks and no original's),
      remove; the tab row sticks under the top bar while the banner scrolls away; the same Memory through a `READ` link
      (banner shown, no action, the tab row sticks at the top); the console free of errors. The instance state that pass
      needs: a member with write, a Memory with a landscape image, a portrait image and a portrait video under 2 minutes, a
      `READ` link to it.
- [ ] **5 docs** — `uc.memory.banner` and `en.memory.playback-derivative` to built (status, what holds); the coverage YAML
      filled (drop `status: planned` and the gaps it closes); ADR 0102 to Accepted with the owner's answers;
      [`docs/apps/memories.md`](../docs/apps/memories.md) (domain data, the role matrix's cover row gains the banner);
      `docs/crosscutting/security-model.md` and the site's `security/what-the-server-sees.md` (the banner's line: existence,
      video-ness, the one-time timing on a gallery source); the Use pages `memories/add-a-banner` EN + DE with their
      `sources:` and the sidebar entry in `site/astro.config.mjs`, walked in the client before they count
      (`changes/README.md`); `uc.memory.cover` and `uc.platform.node-cover` gain one line pointing at the banner as the
      independent sibling. · Done when: `npm run check` in `site/` green and the guard glob green.

## Bite tests (each says how it is bitten)

1. **One transaction** (backend) — injection: make the store attach outside the meta's transaction (commit the attach before
   `applyUpdate`); the stale-meta test must go red on "both slots unchanged".
2. **The poster follows the medium** — injection: drop the detach of `BANNER_POSTER` on an image replace; the
   video → image test goes red (the poster row survives).
3. **Guests cannot write** — injection: add `BANNER` to `MemoryBlobRoles.GUEST`; the guest upload-start test goes red. A
   positive control on the same predicate: a member's start in `BANNER` succeeds.
4. **Not a medium** — injection: bump `NodeItemsRevisions` in the banner store; the "unchanged media revision" assertion
   goes red. Control: a media upload in the same test moves it.
5. **Erasure and hold reach the slot** — injection (on a detached worktree only, per `changes/README.md` § bite-proof): a
   `slot != 'BANNER'` filter in `AttachmentNodeSubtreeEraser` and in `CoreHoldSnapshot`; the purge and the hold tests go red.
   This is the bite that makes "core walks every attachment" a measured claim.
6. **Focal point** (web unit) — injection: swap `x` and `y` in `bannerStyle`; an asymmetric focal point's
   `object-position` assertion goes red.
7. **No autoplay** — injection: render the `<video>` with `autoplay` before the click; the component test asserting no
   `<video>` element (and no `registerPlayback` call) before the click goes red.
8. **The copy is small, and is a video** — **un-mocked**, before the mapping depends on it (`changes/README.md` § a library's
   output): a Playwright test transcodes a fixture video (portrait, 1080×1920, with sound) and decodes the result in the
   browser — long edge ≤ 1280, codec H.264, duration within a frame of the source, sound present. Lever: set the target
   edge to 1920; the test goes red.
9. **Never the original** — the browser pass's network check, and a component test asserting the chunk provider is built
   from the `BANNER` ref; injection: hand it the origin medium's original ref; red.
10. **Operator-blind on the wire** — the wire assertion derives its canaries from every unsealed leaf
    ([cc.security.wire-assertion](../docs/crosscutting/cc.security.wire-assertion.md)): an e2e that writes a banner with a
    description makes that description a canary, swept over the database, the store, the logs and the HTTP bodies. Lever:
    send `alt` in a clear request field in a scratch build; the sweep goes red.
11. **Export round trip** — injection: drop `content.banner` from the export; the round-trip test (banner present after
    import, with its frame and focal point) goes red.

## Owner questions (each with a recommendation)

- **Q1 — the word.** The owner said "Header"; *header* already names the Memory's title row (core's `NodeHeader`) and the
  place the cover shows ("detail header", `uc.memory.cover`). **Recommendation: *Banner*** (EN and DE), as written
  throughout. Alternative: *Header* with the title row renamed — a sweep through code and docs for no gain.
- **Q2 — a gallery medium: copy or reference.** **Recommendation: copy** (ADR 0102 decision 3): derived from the original
  into the banner's own slots — one storage form and lifecycle, draws before the gallery, and the server never learns which
  medium. Its costs: bytes stored twice; deleting the medium keeps the banner (the delete dialog says so and offers to remove
  it); a report of the medium does not reach the banner (it is reported with its Memory). Alternative: a reference as the
  cover's `mediaId` — follows the medium's trash and reports, but names the medium to the operator by the fetch on every
  open, draws only after the media list, and a video would still need a small tier the server would see added to exactly
  that medium.
- **Q3 — which row sticks.** Today the page is `NodeHeader` (title, cover tile, date, place, people, actions — tall) and
  then the tab row. **Recommendation: the tab row sticks**; the banner and the header row scroll away together.
  Alternative: the header row sticks — about twice the height on a phone, and it carries the cover tile.
- **Q4 — how long a video may be.** **Recommendation: refuse longer than 2 minutes** (about 30 MB at the target bitrate,
  a transcode a phone finishes in a tab), no trimming in this CR. Alternatives: no bound (a long transcode in a tab, a large
  banner), or take the first N seconds (a trim UI, more to build).
- **Q5 — a browser that cannot encode.** **Recommendation: refuse the video there and offer its first frame as an image
  banner** (the poster derive exists). Alternative: refuse without an offer. Using the original is excluded by the owner's
  decision.

## Definition of done

Phases 0–5 checked; `./gradlew check` (backend) and `npm run check` (web, site) green; every bite above bitten as it says;
`docs/coverage/features/memory/use-cases/uc.memory.banner.yaml` covers every acceptance criterion with a test or an
explicit gap, and `python3 scripts/validate_coverage.py` is green. **Baseline before the first edit:** on a detached
worktree of `HEAD` (`git worktree add --detach <dir> HEAD`), `for f in scripts/validate_*.py; do [ "$f" = scripts/validate_guard_gaps.py ] || python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`
— its output is the baseline; a red after this CR that is not in it is this CR's. No guard changes, so
`validate_guard_gaps.py` is not owed (it is run if one does). The published pages (`memories/add-a-banner` EN + DE, the
security page's line) are updated and walked in the client.

## Retrospective

(filled at implement-session close)
