---
id: cr-upload-windowed-presign
title: Uploads are presigned per window, bound to their opener, and streamed — the TTL stops capping a file
status: planned
satisfies: [uc.memory.upload, uc.sharing.share-contributor]
adrs: ["0013", "0051", "0062"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

Every `PUT` URL of an upload is signed once, at `start`, under a 600 s TTL, and nothing signs another. So the
largest file that can finish is TTL × uplink: about 1.5 GB at 20 Mbit/s, whatever `veyq.blob.max-bytes` says.
The same shape makes `start` sign every chunk of up to 256 blobs in one request. And the web client reads the
whole file into memory and seals every frame before sending the first, so a large video dies in the tab before
the clock is even the problem.

After this CR:
- `start` returns a first **window** of URLs, and a new operation signs each next window for an open
  session. Each request is authorized again, so a revoked link or a removed member stops an in-flight upload at
  its next window.
- A session is **bound to its opener**, and only they can extend or complete it.
- The stale-session purge keys on the **last issued window**.
- `max-bytes` becomes a 20 GiB sanity ceiling.
- The web client **streams**: it reads, seals and sends one frame at a time.
- Folded in (review B9): the five copies of the chunk-key enumeration and the twin `PresignRef`/`PresignedRef`
  collapse into one `BlobStore` helper and one class.

## Context & constraints

- **The rule:** [ADR 0013 §2, §3 and Consequences](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md),
  [en.memory.media](../docs/features/memory/enablers/en.memory.media.md) (main flow, E2/E3/E3a),
  [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) (quality notes),
  [uc.sharing.share-contributor](../docs/features/sharing/use-cases/uc.sharing.share-contributor.md) A2 and
  [uc.sharing.share-uploader](../docs/features/sharing/use-cases/uc.sharing.share-uploader.md) A2 — all written
  in the refine.
- **Builds on `cr-upload-frame-size-contract`:** a window signs each chunk's exact length, which that CR's
  frame-arithmetic unit supplies. This CR implements after it, after `cr-request-bounds-enforced` (done — the new
  schemas land under its guard, `scripts/validate_request_bounds.py`), and after `cr-inert-store-responses` (the same `BlobStore` presign sites and
  the client `putChunk`). Both this CR and `cr-cold-credential-proves-the-secret-key` add a Flyway migration, and at planning time each would take `V50`
  (the highest was `V49__item_favorites.sql`); whichever merges second renumbers its file.
- **What the sessions are today** (measured at refine; re-check the row this CR leans on hardest, the missing
  opener):
  - `veyq_upload_session` has no status and no opener column. A row existing means open.
  - `findSession` matches on id + scope only
    (`grep -n 'fun findSession' -A3 backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`).
  - Both callers already know who is asking: `completeForNode` receives `shareIdentityId`/`uploaderUserId`
    (`grep -n 'fun completeForNode' backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`).
- **The gates to reuse** (fourth tell: locate the gate, not only the insertion point):
  - Member: `store.isMember(userId, scopeId)` in `MediaHandler.start`.
  - Contributor: `SharingHandler.startUploads`'s chain — active share → password → `canUpload` → identity →
    `allowUpload` (`grep -n 'fun startUploads' -A12 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`).
  - `MediaResource` is `@RateLimitExempt`, and its reason names presign minting as the amplification axis
    (`grep -n 'RateLimitExempt' -A5 backend/src/main/kotlin/com/veyq/app/features/media/MediaResource.kt`;
    `docs/crosscutting/cc.security.rate-limit.md` § the `MediaResource` bullet).
- **Where the memory goes today** (the web half):
  - `uploadQueue.ts` reads `file.arrayBuffer()`.
  - `sealBlob` seals all frames up front.
  - `uploadMediaVia` runs one unbounded `Promise.all` over every chunk.
  - The cover has its own second `PUT` loop.

  Locators: `grep -n 'arrayBuffer()' web/src/features/memory/*.ts`,
  `grep -n 'function sealBlob\|function uploadMediaVia\|function uploadCoverBlob\|function putChunk\|function byIndex' web/src/features/memory/media.ts`,
  `grep -n 'startShareBlobUploads' -B3 -A20 web/src/features/memory/share.ts`.
- **The re-sign precedent** is the GET side's re-presign on a stale-URL `403` (`grep -n 'freshUrls' web/src/features/memory/media.ts`).
  The GET side itself is out of scope.
- **Out of scope:**
  - resuming across a page reload (ADR 0013, deferred);
  - the storage quota — per-blob `max-bytes` bounds a file, never a total; the total and the open-session bound
    are [ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)'s, built by
    `cr-share-link-storage-limit` and `cr-tenant-storage-quota` after this CR;
  - the GET presign path.

## Decisions

- **Two new operations, batch-shaped like their siblings** (ADR 0013 §2):
  - Member: `presignBlobUploadWindows`, `POST /api/scopes/{scopeId}/blob-upload-windows`.
  - Contributor: `presignShareBlobUploadWindows`, `POST /api/shares/{shareId}/blob-upload-windows`. It carries
    the same password header and body `contributorToken` as `startShareBlobUploads`.
  - A request item is `{uploadId, fromChunk}` and gets `presignedPuts` for chunks
    `[fromChunk, min(fromChunk + W, chunkCount))`, each URL signed for its chunk's exact length.
  - `W` is `veyq.blob.presign-window`, default 64.
  - Per-item errors:
    - `upload_not_found` for an unknown session, one that is not open, **or one the caller did not open**. One
      code for all three, so a probe learns nothing.
    - `range_invalid` for a `fromChunk` outside the blob.
  - Top-level access failures are those of the matching `start`.
  - `start`'s `presignedPuts` carries only the first window. Its description says so.
  - The member operation lands on the existing `media` tag, so the contracts phase cannot end with
    `./gradlew check` green (`changes/README.md` § phases). Its done-when lists only what can run.
- **The session records its opener**: the account, or the share identity — exactly one.
  - The window and every complete (member, contributor, cover) resolve a session only for its opener. That
    closes today's hole where any scope member or any contributor identity could complete any session given its
    id.
  - Sessions from before the migration carry no opener. They match nobody and age out through the purge; there
    is no data migration.
  - It is stored as **two nullable references** (the account, the share identity), not as one opaque key. The
    storage quota sums a link's open sessions by share identity
    ([ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) §4,
    `cr-share-link-storage-limit`), and an opaque key would leave that SUM nothing to join on. One constraint
    remains: the purge, `ScopePurge` and the reclaim paths that delete sessions must not need to read it
    (`grep -rln 'UploadSession\|upload_session' backend/src/main`).
- **Every window is authorized again** with the gate of its `start` — membership, or the share chain.
  - The contributor window does **not** draw from `allowUpload`. That bucket counts the writes that create
    sessions and rows (start and complete, per the comment in `completeUploads`), and a window creates neither.
  - Per call, a start or window signs at most 256 × `W` URLs, where a start used to be able to sign 256 × every
    chunk. `MediaResource`'s exemption reason and `cc.security.rate-limit` say that bound instead of "a real
    amplification axis" unqualified.
- **Liveness is the last issued window.** The session carries a timestamp set at `start` and touched by every
  window, and `UploadSessionPurge` keys on it instead of on the start.
  - `veyq.media.upload.retention` stays `PT1H`. Its argument is restated per window: a `PUT` can only begin
    within the TTL of the window that signed it.
  - `retentionDefaultsToTheMeasuredPresignBound` keeps asserting retention > TTL. Its comment changes.
- **`veyq.blob.max-bytes` defaults to 20 GiB.** `infra/` and `charts/` carry no override
  (`grep -rn 'max-bytes\|MAX_BYTES' infra charts` is empty at refine).
- **One chunk-key enumeration.** `BlobStore` gains the helper that enumerates a blob's chunk keys over a range,
  and the five `(0 until n).map { chunkKey(…) }` sites use it:
  `grep -rn 'chunkKey' backend/src/main/kotlin` (and the test copy in `MediaBudgetBranchSliceTest`).
  `PresignRef` and `PresignedRef` become one class beside `BlobStore`
  (`grep -rn 'PresignRef\b\|PresignedRef\b' backend/src`).
  This stays a one-shot proof, not a new guard. Every copy calls the same `chunkKey`, so a sixth copy cannot
  derive different keys, and the scheme itself is pinned by `ObjectKeyCarriesNoStructureSliceTest`. The
  duplication was cost, not risk.
- **The web client streams through one pump.**
  - The original is read as `file.slice` per frame, sealed with its index and the total (both known from the
    file size), and `PUT` with a small fixed number in flight.
  - The pump asks for the next window before the URLs in hand run out.
  - A `PUT` that answers `403` re-signs from that chunk and retries it once, mirroring the GET side's
    re-presign.
  - Derivatives are small and stay sealed in memory. EXIF and derivation read from the `Blob`, never from a
    full copy.
  - Member uploads, contributor uploads (`share.ts`) and the cover all use the pump through their transport,
    which gains a `window` call. The cover's separate `PUT` loop goes.

## Phases

- [ ] 1 contracts — the two operations, their schemas, `start`'s changed description, and the per-item error
      descriptions, in `media` and `sharing`. Locator for the sites the descriptions supersede:
      `grep -n 'One presigned PUT URL per chunk\|presignedPuts' contracts/api/media.openapi.yaml contracts/api/sharing.openapi.yaml`.
      · Done when: `npm run generate:api` + `npm run check` (web) green, and every `scripts/validate_*.py`
        green (including `validate_request_bounds.py` on the new schemas). `./gradlew check` is red until
        phase 2, because the existing `MediaApi` gains a method. The contributor op lands on its existing tag
        too. Check which, don't assume.
- [ ] 2 backend. In this order:
      1. **Live-defect bites first**, on the tree phase 1 left:
         - a second member completing another member's session → `upload_not_found` (today it attaches);
         - a second contributor identity on the same share completing the first one's session → the same;
         - `start` of a blob larger than `W` chunks returns exactly `W` URLs (today every chunk).

         Each has a control: the opener completes; a blob ≤ `W` chunks gets all its URLs.
      2. The migration (opener + liveness), the opener stored at every session creation, and the opener
         predicate on every session read (`grep -rn 'findSession' backend/src/main`).
      3. The window operations with their gates. The B9 helper and the merged ref class land here, since the
         window is the new enumeration site.
      4. The purge on liveness, and the comments that stated "nothing re-signs". Locator — this is the fragment
         sweep, run again at this phase:
         `grep -rniE "signed once|nothing re-signs|no re-presign|once at .?start" backend/src docs contracts`.
         The coverage criterion it finds is rewritten in phase 4.
      5. `max-bytes` 20 GiB. The rate-limit exemption reason and doc bullet.

      **Injection bites** (new behaviour, green from its first run; name the lever, snapshot-restore):
      - **Revocation at the next window:** a window after the share is disabled → refused, with a control
        window before it → `200`. Lever: drop the active-share check from the window path.
      - **Removed member:** the same shape; lever: the membership check.
      - **Liveness:** a session started 2 h ago whose last window was 5 min ago survives the purge, and one
        whose last window was 2 h ago does not. Lever: the purge predicate back on the start timestamp.
      - **Range:** `fromChunk = chunkCount` → `range_invalid`, with a control at the last chunk → one URL.

      `BlobBatchE2ETest` (opt-in, real Garage) uploads a blob of more than `W` chunks through start + window.

      · Done when: `./gradlew check` green, and
      `VEYQ_S3_E2E=1 ./gradlew test --tests '*BlobBatchE2ETest*'` green against the dev store.
- [ ] 3 web — the streaming pump and its three transports. Tests go in `media.test.ts` / `share` tests /
      `uploadQueue.test.ts`. Bites:
      - **Live defect: the whole file is never read.** A spy on the source `Blob` shows `slice` per frame and no
        `arrayBuffer()` of the whole. Red on the untouched tree through `uploadQueue.ts`'s read.
      - **Live defect: in-flight `PUT`s never exceed the pump's bound** for a many-chunk blob. Today it is every
        chunk at once.
      - **Injection:** a `PUT` answering `403` triggers one window request from that chunk and one retry.
        Lever: make the fake store's first `PUT` of chunk *k* answer `403`.
      - The next window is requested before the current one runs out. Assert on the order of calls, not on
        timing.

      Check the uploads tray's *preparing → uploading* split against the new shape. Sealing now happens during
      the upload, so *preparing* is derivation only. The use-case's step 3 wording holds, but read it.
      · Done when: `npm run check` (web) green.
- [ ] 4 docs + coverage
      - `uc.memory.upload.yaml` `abandoned-session-reclaimed`: its text is **edited**. The bound argument moves
        from "signed once at `start`" to per window; the refs stay, plus the liveness test.
      - New criteria in `uc.memory.upload.yaml`:
        - "an upload outlives the presign TTL: URLs are issued per window, and an expired window is re-signed";
        - "a session is extended and completed only by its opener, and only while the opener still holds the
          upload right";
        - "the client streams a file and never holds all of it".
      - A new criterion in `uc.sharing.share-contributor.yaml`: "revoking or expiring the link stops an in-flight
        upload at its next window".
      - Read the owning docs **end to end**, not only the grep hits: ADR 0013, `en.memory.media`,
        `uc.memory.upload`. The refine wrote them; the phase checks nothing in them now disagrees with what
        was built.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The three phase-2 live defects and the two web live defects were seen red on their assertion, not on a setup
  line.
- Each injection bite was seen red with its named lever.
- The opt-in e2e moved a blob of more than one window through the real store.
- `grep -rn '0 until' backend/src/main/kotlin | grep chunkKey` returns nothing outside `BlobStore`, and
  `grep -rn 'class PresignRef\b\|class PresignedRef\b' backend/src` returns one class.
- The phase-2 fragment sweep returns no statement that URLs are signed only at `start`.

**Not proven:** that a real browser tab stays within memory on a multi-GiB file. The unit tests prove the pump
never reads the whole file and bounds what is in flight. A live upload of a file larger than the tab could hold
is a manual check, not a claim.

## Retrospective
