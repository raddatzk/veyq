---
id: cr-prepare-the-next-file-ahead
title: Prepare the next file while one is on the wire
status: in-progress
satisfies: ["uc.memory.upload"]
adrs: ["0013", "0017", "0026", "0105"]
invariants:
  - operator-blind - the next file's on-device stage reads the picked file and nothing else; it touches no key, seals nothing and sends nothing
  - the wire carries one file at a time - every server write of a file (reserve, windows, complete) happens after the previous file's complete, in queue order, as today
  - every seal of a medium is made after the device floor (`atFloor`) ran for that file's upload, under the key read then
  - the frame worker, the digest worker and the media worker keep their contracts unchanged - no new op, no change to `ready`, the latch or `cancel`
  - an abort still settles `cancelled`, never `failed`, and a cancelled file is settled at once, wherever it is in the pipeline
---

## Goal

Inbox #220. Memories' upload runs one file at a time: each file runs its **on-device stage** (type, digest, tiers, EXIF
read) and then its **upload stage** (seal its metadata, reserve, seal and `PUT` its frames, complete). This CR runs the
next file's on-device stage while the current file is in its upload stage, one file ahead, and changes nothing on the
wire. The refine was done with the owner on 2026-10-27 as the deleted `cr-next-file-prepared-while-one-uploads`
(`git show 5a6c23679:changes/cr-next-file-prepared-while-one-uploads.md`): its rule chose relieving the main thread first
(route B, then #640); this CR is that rule's branch A, read again on the relieved tree.

## The reading that chose it (2026-10-29)

**Substrate.** `main` at `66af6b1d8`, built on this Mac (the web client inside the backend's fast-jar, and the admin
client), laid over the local candidate images `veyq:candidate-arm64` / `veyq-admin:candidate-arm64` by a scratch
Dockerfile, nothing pulled. Maps: `vite build --sourcemap` of the same tree (same names and bytes but for the
`sourceMappingURL` line; `pdf.worker.min` and the rolldown runtime carry none and were never sampled).
`scripts/profile_hot_paths.py --scenario s2`, alone on the machine: three runs without the CPU profiler and one with it
at throttle 1 and 4, read by `scripts/profile_attribute.py` (every control PASS). Each batch started at a 1-minute load
≤ 4 (3.93, 3.67); during the runs it was 5.9–6.9, the instance itself included.

| median of 3 unprofiled runs | throttle 1 | throttle 4 |
|---|---|---|
| W, the upload | 18.2 s (17.9–19.2) | 20.0 s (19.9–20.3) |
| M, the main thread's tasks | 3.0 s | 8.5 s |
| **R**, the largest named bucket | 2.7 % (on-device page side) | **5.9 %** (reactivity) |
| **G**, what an overlap could move | **8.0 s, 44.2 %** | 8.4 s, 41.8 % |
| per file P / U / mP / mU | 161 / 178 / 19 / 19 ms | 171 / 180 / 61 / 54 ms |
| workers: frame / digest / derivation | 6.1 / 4.1 / 4.4 s | 6.0 / 4.1 / 4.6 s |

**The rule** (owner 2026-10-27): A if G at throttle 1 ≥ 10 % of W and R at throttle 4 < 10 % of W. Both hold, so A.
After #640 the main thread is no longer what bounds the upload at either throttle (M is 3.0 s of 18.2 s and 8.5 s of
20.0 s); a file's two stages are each mostly worker and wire time, about as long as each other, and run one after the
other.

## Context & constraints

- **The queue** (`src/web/src/core/blob/queue.ts`) runs one opaque `run` at a time: `nextQueued` picks the first
  `queued` item in batch order, `runOne` sets `preparing` and flips to `uploading` on the first progress, a transient
  failure re-queues for one retry ahead of later items, `cancelFile` settles a `queued` item at once and aborts an
  in-flight one. Depot, Expenses and Memories enqueue into it.
- **Memories' job** (`features/memories/uploadQueue.ts` `enqueueUploads`): `run` calls the batch's `UploadFn` — the
  member's `addMedia` behind `atFloor` and the re-key retry, or a guest's `addSharePhoto` — and both forward `opts` whole
  into `uploadMediaVia` (`media.ts`).
- **One file** (`media.ts` `uploadMediaVia`): `resolveMediaType`; the digest (digest worker) beside `deriveTiers` (media
  worker); the stored-original check (unseals the banner under the node key); the media id minted; `sealMetadata`
  (`readExif` with `exifr` on the page, then both tiers sealed under the node key); `uploadSealedMedia` (plan, reserve,
  pump, complete). The type, the digest, the tiers and the EXIF read are key-free and send nothing.
- **Where the transport reports 0:** `reserveAndPump` (`core/blob/transport.ts`) reports `onProgress(0)` after the
  reserve answered, right before the first chunk `PUT`; so the running file's on-device stage is over then.
- Docs: [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) step 3 and its quality note
  "Sequential, not parallel"; [architecture § 10](../docs/architecture/README.md);
  `site/src/content/docs/security/how-veyq-is-tested.md`. The Use page "Add photos and videos" names the states Queued,
  Preparing, Uploading; they stay true (decision 6), so it does not change.
- Contracts, backend, admin-web, `@veyq/crypto`: untouched — nothing on the wire changes.

## Decisions (owner 2026-10-27, as refined; re-read against `66af6b1d8`)

1. **Depth 1.** At most one file runs its on-device stage ahead of the file on the wire: S2's files are alike, each slot
   holds a tier set while it waits, and two derivations in flight would decode two originals at once.
2. **An optional hook on core's queue; the job holds the result.** `UploadJob.prepare?(ctx: { signal })`. The runner calls
   the next item's `prepare` when the running item reports its first progress, with that item's own signal (the one its
   `run` gets later). "Next" is what `nextQueued` would pick, skipping an item whose job has no `prepare` (Depot's and
   Expenses' jobs have none). The job memoizes the promise in its own closure and its `run` awaits the same promise, so
   the queue never holds a prepared value. `prepare` sends nothing and writes nothing, so `abandon` never has anything of
   it to undo. Rejected: a prefetch inside Memories' adapter, which cannot see the queue's order, its retry or a cancel.
3. **Memories' on-device stage is `prepareMedia(source, deriver, signal)`**: the type, the digest beside the tiers, and
   the metadata read (the EXIF read, and the probe of an underivable source's geometry). `sealMetadata` splits into that
   read and its seal. `uploadMediaVia` takes the result as `AddMediaOptions.prepared`, else runs `prepareMedia` itself
   (the import's `addOriginal` and every caller outside the queue). Everything that reads a key stays in the upload stage,
   after `atFloor`: the stored-original check, the mint, the seals, the plan. `UploadFn` keeps its shape.
4. **Order and server writes are unchanged**: the prepare makes no request, so file N+1's reserve still follows file N's
   complete; completion order is queue order.
5. **Failure, abort, cancel.** File N failing or cancelled while N+1 is prepared: N's automatic retry is taken first (it
   is re-queued ahead), N+1 waits with its result. A failed attempt drops its own prepared result and the retry prepares
   again. Cancelling a file prepared ahead settles it `cancelled` at once and aborts its signal (the digest worker's
   `cancel` stops its digest); its derivation runs to its end and is dropped (the media worker has no cancel, the one
   derivation `media.ts` already accepts).
6. **The tile** of a file prepared ahead says `preparing` from its prepare's start until its first progress — never back
   to `queued`.
7. **Page-thread fall-backs: no special case.** A latched worker finishes its work on the page; overlapped, that is the
   same work in another order.
8. **Scope:** Memories' member and guest uploads through the queue. The import and Depot are not in it.

**Memory at depth 1** *(derived)*: the next original stays a disk-backed `File`; its decode (4032 × 3024 × 4 =
48.8 MB, plus the 2048 px canvas) runs while the current file pumps, whose frames hold about 2 MiB; then one tier set
(a ≤ 2048 px and a ≤ 512 px JPEG) waits. Depth 1 keeps one decode at a time: N+1's derivation starts after N's own is
done.

## Phases

1. **Docs.** uc.memory.upload step 3 and the quality note: one file on the wire at a time, the next file's on-device
   stage one ahead. Its coverage criterion `next-file-prepared-while-one-is-on-the-wire` lands with phase 3's tests:
   `validate_coverage.py` takes a criterion gap only on a surface the criterion already covers.
   **Done when:** every `scripts/validate_*.py` exits 0.
2. **Web, core's queue** (decisions 2, 5, 6), tests first in `queue.test.ts`:
   - **Overlap, gated on both sides:** A's `run` reports progress 0, then resolves only once B's `prepare` started; B's
     `prepare` resolves only once A reported progress. A queue that prepares B only after A settles never settles.
   - **Not too early:** B's `prepare` is not called before A's first progress. **Bite:** prepare at A's start.
   - **One ahead:** with A, B, C, C's `prepare` waits for B's first progress. **Bite:** prepare every queued item.
   - **One run at a time** stays, with prepares in the mix.
   - **Cancel:** an item prepared ahead settles `cancelled` at once, its signal aborted, abandoned once; left alone it
     runs, and its `prepare` ran once in total.
   - **Failure:** A fails hard, B runs next on its prepared result; A's automatic retry runs before B.
   - **No hook:** a job without `prepare` is skipped for the next one that has it.
   **Done when:** `npm run check` in `src/web` is green.
3. **Web, Memories** (decisions 3, 4, 8), tests first:
   - `media.test.ts`: with `prepared`, `uploadMediaVia` calls neither the digest nor the deriver; without, once each;
     `prepareMedia` makes no transport call and never reads a key.
   - `uploadQueue.test.ts`: the job's `prepare` is memoized and `run` hands its result to the `UploadFn`; a failed run
     drops it and the retry prepares again; `atFloor` runs after the prepare and before the first seal.
   - `uploadQueue.test.ts`, two files: file 2's prepare starts only once file 1 reported its first progress, and file
     1's upload finishes only once file 2's prepare ran (both sides); each upload gets its own file's result.
   - "Every request of file 2 follows file 1's complete" is the composition of two pinned facts, not a third test:
     `prepareMedia` sends nothing (`media.test.ts`, no reserve, completion or `fetch` while it runs) and one job's `run`
     at a time (`queue.test.ts`). A two-file wire recording was dropped at implement time: `enqueueUploads` takes no
     deriver, so in vitest its real deriver finds no `createImageBitmap` and the recording would hold originals only.
     The wire snapshots stay byte-equal.
   **Done when:** `npm run check` in `src/web` is green; the coverage criterion names these tests.
4. **Browser pass** on a production build (a profile instance): 20 photos, one tile uploading while the next is
   preparing; cancel the preparing one (it goes at once, the rest upload); cancel all mid-batch; no worker warning.
5. **The after-reading**, alone on the machine: S2 on `main`'s images and on this branch's, three unprofiled runs each at
   throttle 1 and 4, read with `profile_attribute.py` (`--expect-overlap` on the branch).
   - **The overlap exists:** in every branch run at least 45 of 49 pairs have N+1's digest inside N's upload stage
     (`main`'s runs count 0, the measurement's control).
   - **No throttle-4 regression:** the branch's median is not above `main`'s, and no branch run above `main`'s maximum.
   - **The throttle-1 gain is real:** the branch's median below `main`'s minimum. A result against § The reading is
     reported with its numbers, never fixed in place.
6. **Docs and site:** architecture § 10, how-veyq-is-tested, `profile_attribute.py`'s docstring if a control moved; #220
   deleted; this CR deleted after its lessons moved.

## Definition of done

Phases 1–6 done; `npm run check` green in `src/web`; uc.memory.upload's new criterion covered and
`validate_coverage.py` green; the Security page updated; the Use page unchanged (decision 6).

## Done (2026-10-29)

- Phases 1–3: `91318cf8d`, `921bbe6a0`, `1b34095b8`; `npm run check` green (478 files, 5,498 tests); seven bites, each red.
- Phase 4, the browser pass on the branch's production build (a kept profile instance, headless system Chrome): a tile
  `Uploading…` beside the next `Preparing…` in 5 of 10 samples at the batch's start, never two of either; the file
  preparing ahead cancelled from the tray read `Cancelled` 50 ms later while the other kept uploading; cancel-all
  after 8 done left 8 done and 12 cancelled; no worker warning; the only error answer was the signed-out boot's refresh
  (401 by design), the only failed request the cancelled file's chunk `PUT` in flight.
- Phase 5, alone on the machine, each batch at a 1-minute load ≤ 4 (3.82, 3.93; 4.7–7.8 during the runs):

  | median (range) | `main` before | branch | `main` after |
  |---|---|---|---|
  | W at throttle 1 | 18.2 s (17.9–19.2) | **10.8 s (10.6–11.5)** | 18.0 s (17.6–18.4) |
  | W at throttle 4 | 20.0 s (19.9–20.3) | **13.4 s (13.3–13.5)** | 20.1 s (20.0–20.2) |
  | per file P / U at throttle 1 | 161 / 178 ms | 2 / 187 ms | 159 / 171 ms |
  | digests inside the previous upload stage | 0 of 49 | **49 of 49** | 0 of 49 |
  | G at 1 / R at 4 | 44.2 % / 5.9 % | 1.3 % / 8.8 % | 44.3 % / 5.7 % |

  Every gate holds. Two branch runs failed the CPU-time control (×1.122, ×1.207): explained, not excluded — the control
  depends on the window's length (the reader's docstring, inbox #770).

## Retrospective

- **The day-old readings were mostly load.** #640's after-reading was taken on trust as "37.5 → 20.0 s"; a baseline of
  the tree before #640 run under the same quiet conditions put it at 21.4 → 20.0 s. The route-B readings had already
  said absolute numbers from a loaded day do not compare; a reading that attributes a change still needs its
  baseline on the same machine state. Next time: when a measurement CR reads a change another CR made, run that
  change's parent beside it in the same session before writing the attribution.
- **A control written for one window length failed on the next.** The CPU-time control agreed only because two errors
  cancelled near W ≈ 18 s; it was explained here with the trace's own `tdur`, not loosened. Filed as #770.
- **Process:** running alone made the reading cheap — every batch settled to load ≤ 4 within three minutes, against a
  five-minute wait that never got below 8 on 2026-10-28. The worktree's command guard again refused heredocs, compound
  `git`/`docker` lines and `$VAR` arguments to an interpreter; helper scripts in the scratch directory carried them.
