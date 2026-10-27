---
id: cr-next-file-prepared-while-one-uploads
title: Read what holds the upload's main thread first, then prepare the next file while one is on the wire, or relieve the main thread instead
status: planned
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

Inbox #220. Memories' upload runs one file at a time, and each file runs its on-device stage (type, digest, tiers,
EXIF read) and then its upload stage (seal its metadata, reserve, seal and `PUT` its frames, complete). The row asks
whether the next file's on-device stage should run while the current file is on the wire.

The owner's concern decides the shape of this CR. At throttle 4, which stands in for a slow device, #554's
after-reading has the main thread busy 64–71 s of a 71–78 s upload. Overlapping two stages cannot shorten an upload
below the main thread's own work. So **phase 0 reads what occupies the main thread during S2**, at both throttles, and
computes what an overlap could actually move. Its result chooses one of three branches, by a rule written below:
- **A**: build the overlap (phases 1–4);
- **B**: relieve the main thread first, as a CR of its own, and re-read phase 0 after it;
- **C**: close #220, because neither pays.

**Not ready to implement.** § Open questions holds the owner's questions. The decisions below are written as
recommended.

## Context & constraints

Measured at refine on `worktree-agent-ab611705648383265`, fast-forwarded to `main` at `6afdd95b2` (the #554 merge),
nothing changed. Every file:line below was read in this refine. Figures computed from measured ones are marked
*(derived)*, and readings of an earlier tree are marked *(pre-#554, from git)*. Nothing was run: the owner works on this
Mac, so no profile, image build or dev server ran. This worktree has no `node_modules`, so `npm run check` did not run
either.

### Where an upload's stages run today

- **The queue runs one opaque job at a time.** A job is a single `run` (`src/web/src/core/blob/queue.ts:32-36`). The
  runner takes the first `queued` item across batches in batch order (`queue.ts:121-127`) and awaits it before the next
  (`queue.ts:183-195`). An item is `preparing` from its start and flips to `uploading` on its first progress
  (`queue.ts:141`, `:146-149`). A transient failure re-queues it for its one retry, ahead of every later item
  (`queue.ts:162-163`). The queue cannot start anything of a later job today, because it only knows `run`.
- **Memories' job is `addMedia` behind an `UploadFn`.** The adapter is `features/memories/uploadQueue.ts:186-200`, and
  its default runs the device floor `atFloor` first (`:188`). A share guest's `UploadFn` is `addSharePhoto`
  (`MemoryShareBody.vue:127-131`, `source.ts:468-480`), and both forward `opts` whole into `uploadMediaVia`.
- **One file, in order** (`features/memories/media.ts:602-634`):
  1. `resolveMediaType` (`:611`) reads the first bytes only when the type is empty (`:529-538`);
  2. the digest in core's digest worker beside `deriveTiers` in the media worker (`:618-621`);
  3. the stored-original check (`:622`), which unseals the node's banner under the node key
     (`storedMatch.ts:73-83`);
  4. the media id is minted (`:624`);
  5. `sealMetadata` (`:625`, body `:447-477`) reads the EXIF with `exifr` on the page's main thread (`readExif`,
     `:399-438`), then seals both tiers under the node key;
  6. `uploadSealedMedia` plans each blob, then `reserveAndPump` (`:663`, `core/blob/transport.ts:135-159`) reserves,
     pumps and completes.

  Steps 1, 2 and the EXIF read of step 5 are key-free and send nothing. Steps 3, 4, the seals of 5, and 6 use the key
  or the wire.
- **The pump** keeps `PUMP_IN_FLIGHT = 4` workers (`transport.ts:68-69`). Each reads a 262,144-byte slice
  (`transport.ts:53-55`, `@veyq/crypto` `v1.ts:203`), seals it in the frame worker (`transport.ts:59-61`), `PUT`s it and
  reports progress once per chunk (`transport.ts:107-131`, `:152-156`). So seal and wire overlap inside one file's
  pump.
- **Separate threads.** The digest worker (`core/blob/digestWorker.ts:70`), the frame worker (`frameWorker.ts:100`) and
  the media worker (`shared/image/mediaDeriveWorker.ts:70`) are three Workers. A next file's digest and derivation
  would not queue behind this file's seals.
- **Per file, before #554** *(pre-#554, from git: `git show 81b28369e:changes/cr-content-digest-off-the-main-thread.md`,
  § When the digest runs; one run each, medians over 50 files)*:

  | | throttle 1 | throttle 4 |
  |---|---|---|
  | digest (then on the main thread) | 113 ms | 394 ms |
  | derivation (media worker, first to last task) | 212 ms | 227 ms |
  | pump (frame worker, first to last task) | 255 ms | 1,019 ms |
  | pump's end to the next file's digest | 58 ms | 100 ms |
  | one file | 588 ms | 1,703 ms |

  The throttle reaches the main thread only, never a worker (`scripts/profile_hot_paths.py:68-72`). Yet the pump's span
  grows ×4.0 from throttle 1 to 4 while its seals run in the unthrottled frame worker (5.2–5.5 s at both throttles,
  #220's row). So at throttle 4 the pump stage is main-thread time, not seal or wire *(derived)*.

### What the row gets wrong

- **The bound has the wrong shape.** The row bounds what an overlap hides "between the larger of seal and wire and
  their sum". An overlap hides, per pair of files, at most the **shorter** of file N+1's on-device stage and file N's
  upload stage, and never more than the main thread's idle time in the window. Seal and wire are two off-main parts of
  the upload stage that already overlap each other (above). Neither is that stage's length. At throttle 1 the
  on-device stage looks like the shorter one (≈ 212 ms against ≈ 255 ms of pump plus round trips, pre-#554). So the
  bound there is the on-device stage, and the row does not consider that stage at all. Phase 0 computes the bound from
  what each run already records (§ Phase 0's numbers).
- **"The pump's requests and the page's own work bound the upload" is unmeasured.** It is an attribution nobody made.
  The code shows at least four other candidates for the main thread (next section).
- **"The wire" includes more than the upload's PUTs.** The store requests are every presigned request of the window,
  told apart only by their `X-Amz-` query, and Resource Timing carries no method (`tests/e2e/profile/profiling.ts:65-74`,
  `:148`). So each file's thumbnail `GET`s after its tile reconciles are counted as the upload's wire too. The PUT wire
  is at most the published 18 % and 8 %.
- **Right:** the next file's digest would run in the digest worker, beside this file's seals in the frame worker.

### What can occupy the main thread during S2: candidates the code shows, none measured

Phase 0 sizes each of these. They are listed so that its buckets are keyed on the code, not on a guess.

1. **The pump's requests**, per chunk: the slice read, the transfer to the frame worker, the `fetch` `PUT` and reading
   its empty answer (`transport.ts:53-61`, `:119-127`, `:393-400`). ≈ 23.5 chunks per photo: 1,176 PUTs for 50 photos
   (`transport.ts:396-397`). Plus each window's presign (`transport.ts:182-189`) and the reserve and complete.
2. **Vue reactivity, once per progress tick.** The queue's state is deep-reactive (`queue.ts:115`), and each chunk
   writes `item.progress` (`queue.ts:148`). The gallery's `optimisticTiles` reads every file's `progress`
   (`useOptimisticTiles.ts:52-69`, `:64`) and returns new objects. `layoutItems` re-sorts every tile by capture time
   (`useMediaGalleryLive.ts:120-139`). `MediaGrid` recomputes the whole justified layout, its rows, its places, the
   mounted rows and the nearest-first window order (`components/MediaGrid.vue:72-79`, `:84-161`), whose watch then
   joins and compares the ids (`:163-172`). So each of ≈ 1,176 ticks re-lays out a grid of up to 100 tiles *(read from
   the code; its cost is unmeasured)*. Trap for the attribution: a re-render's stack starts at Vue's scheduler flush,
   never at the progress write that caused it.
3. **EXIF, twice per photo, on the main thread.** At pick, all 50 at once: `probePreview` runs `readCaptureTime`
   (`useOptimisticTiles.ts:96`, `:106`; `exifr` at `media.ts:320-328`) and a dimension probe (`:109`;
   `createImageBitmap` on the page, `shared/image/mediaDeriver.ts:147-155`). In the upload, `readExif` parses again with
   more segments (`media.ts:399-438`).
4. **Each finished file's reconcile.** A live poll (`MemoryDetail.vue:479`, `useMediaGalleryLive.ts:281-319`) opens the
   delta in the listing worker, upserts and re-lays out the grid. Then it settles the auto date and location
   (`MemoryDetail.vue:403-421`, `useMemoryDate.ts:72-83`), which reads the node's meta from the server and may write it.
   A new tile near the viewport then has its thumbnail read.
5. **The derivation's page side**: posting the `Blob`, receiving the bytes, and copying the preview into a `Blob` for
   the thumbnail pass (`mediaDeriveWorker.ts:99-120`, `mediaDeriver.ts:241-243`). It is small while the worker runs.
6. **GC**: ≈ 1,176 × 2 buffers of 256 KiB churned through the window.

### What the profile's output can answer today, and what it lacks

Read in `tests/e2e/profile/profiling.ts`, `s2-upload.profile.ts` and `scripts/profile_hot_paths.py`.

- **The samples are there.** The trace's `disabled-by-default-v8.cpu_profiler` category samples every thread every
  0.2 ms, profiled or not (`profiling.ts:24-27`, `profile_hot_paths.py:29-36`). So an unprofiled run still carries the
  main thread's JS samples, and the trace's `devtools.timeline` events carry its style, layout, paint and GC
  (`profiling.ts:44-55`).
- **The per-file stage edges are there.** `<stem>.window.json` lists every API request with start and duration
  (`profiling.ts:57-63`, `:144-152`). Each file makes one reserve (`/api/scopes/{scopeId}/blob-uploads-batch`,
  `contracts/api/blob.openapi.yaml:28`) and one complete (`/api/scopes/{scopeId}/media-batch`,
  `contracts/api/memories/media.openapi.yaml:39`). So file N's on-device stage is the gap from N−1's complete to N's
  reserve, and its upload stage runs from its reserve to its complete.
- **Nothing in the tree reads any of it.** `git grep -ln cpuprofile` finds only the driver and `profiling.ts`. Each
  attribution so far was made by hand: #536's first reading, #536's after-reading, #554's refine and its after-reading.
  `s2-upload.profile.ts:7-15` names the method (source maps of the same build, never minified names) and its trap.
- **Three gaps:**
  - no mark ties the trace's clock to the window (`t0`/`t1` are `performance.now()` values, `profiling.ts:133`, `:135`);
  - `Performance.getMetrics` is taken only after the window, so its cumulative `TaskDuration`, `ScriptDuration`,
    `LayoutDuration` and `RecalcStyleDuration` include the sign-in before it (`profiling.ts:143`);
  - store requests carry no method (above).

So the existing output can answer phase 0's question once a reader exists and the three gaps are closed. No product
code has to change for it.

## Phase 0's numbers, and the rule that picks the branch

Per run, from the unprofiled runs (the CPU profiler's overhead lands on the main thread only, `profile_hot_paths.py:29-33`):

- **W**: the upload's total (`operationMs`).
- **M**: the main thread's busy time in the window (its top-level tasks).
- **M by kind**: script, style and layout, paint and composite, GC, other (trace events).
- **M's script by bucket**: the six candidates above, by source path through the build's source map, plus "other". The
  reactivity bucket is everything under Vue's scheduler flush.
- **Per file N**: Pₙ (on-device stage) and Uₙ (upload stage) from the API edges, and the main thread's busy time inside
  each, mPₙ and mUₙ.
- **G**, what an overlap could move: Σₙ min(Pₙ₊₁, Uₙ, Pₙ₊₁ + Uₙ − mPₙ₊₁ − mUₙ), capped by W − M. Two stages run
  together take at least as long as the longer one and as their main-thread work together, and the upload never ends
  before the main thread's work in it. File 1 is reported apart, since its gap holds the pick-time burst.
- **R**: the largest single bucket of M, "other" excluded, as a share of W.

**The rule**, on the medians of three runs at each throttle:

- **A, overlap first:** G at throttle 1 is at least 10 % of W, and R at throttle 4 is under 10 % of W.
- **B, relieve the main thread first:** R at throttle 4 is at least 10 % of W, whatever G is. Relief shortens the
  throttle-4 upload, which an overlap cannot move. It also raises the overlap's own ceiling W − M. This CR then waits,
  its phase 0 re-read after the relief lands. The relief is genuinely independent work, so it is a CR of its own, filed
  as an inbox row with phase 0's bucket table. The candidates the code already names are listed above (2, 3 and 4 look
  cheapest). Phase 0 decides which.
- **C, close:** neither threshold is met. #220 is deleted, its numbers recorded in architecture § 10.

The rule gates on what the overlap would move (G) and what a relief would move (R), never on "is the main thread
busy" (`changes/README.md` § A phase's entry condition is phrased against what the phase would CHANGE).

**Expected, for phase 0 to confirm or refute** *(derived, not measured)*:
- At throttle 1, P ≈ the derivation's 212 ms and U ≈ the pump's 255 ms plus round trips (pre-#554), so G could reach
  ≈ 40 % of W if the main thread has room.
- At throttle 4, W − M is 2–9 s of 71–78 s by the row's ranges, so G ≤ 3–12 %. If one bucket holds 10 % of W, that
  outcome is B.

## Decisions this CR takes (branch A; the owner may veto each)

1. **Depth 1.** At most one file runs its on-device stage ahead of the file on the wire.
   - S2's files are alike, so a second slot buys nothing.
   - Each slot holds a tier set while it waits.
   - Two derivations in flight decode two originals at once: the media worker's handler is async
     (`mediaDerive.worker.ts:41-43`).
   - At throttle 4 a deeper pipeline only queues more main-thread work behind a saturated thread.
2. **It lives in core's queue as an optional hook, and the job holds the result.** `UploadJob` gains
   `prepare?(ctx: { signal }): Promise<void>`. The job memoizes it in its own closure, and its `run` awaits the same
   promise, so the queue never holds a prepared value.
   - **When:** the runner calls the next queued item's `prepare` when the running item reports its first progress
     (`queue.ts:146-149`). At that moment its own on-device stage is done (`media.ts:618-625` run before the reserve,
     and `transport.ts:151` reports 0 after it).
   - **With what:** that item's own `controller.signal` (`queue.ts:150`, `:212`), the one its `run` gets later.
   - **Which item:** "next" is what `nextQueued` would pick (`queue.ts:121-127`), skipping an item whose job has no
     `prepare`. Depot's and Expenses' jobs have none, so they are untouched.
   - **Rejected alternative:** a prefetch inside Memories' adapter. The adapter cannot see the queue's order across
     batches, the retry re-queue (`queue.ts:162-163`) or a cancel.
   - **The hook's rule, in its doc comment:** `prepare` sends nothing and writes nothing, so `abandon` never has
     anything of it to undo.
3. **What is "on-device" in Memories.** A `prepareMedia(source, deriver, signal)` runs steps 1 and 2 and the EXIF read of
   step 5. `uploadMediaVia` takes its result through `AddMediaOptions`, else runs it itself (the import's `addOriginal`,
   `exportImport.ts:422-427`, and every test). Everything that reads a key stays in the upload stage, after `atFloor`
   (`uploadQueue.ts:188`), under the key read then: the stored-original check, the mint, the seals, the plan.
   `sealMetadata` splits into its read and its seal. `UploadFn` keeps its shape (`uploadQueue.ts:39`), since the prepared
   result rides in `opts`, which both transports forward.
4. **Order and server writes are unchanged.** The prepare stage makes no request. So file N+1's reserve still follows
   file N's complete, the wire still carries one file at a time, and each file's own request order stays what
   `uploadWire.characterisation.test.ts` pins. Completion order is queue order, as today.
5. **Failure, abort and cancel.**
   - **File N fails or is cancelled while N+1 is prepared:** N+1 is independent and goes next. N's automatic retry is
     taken first (it is re-queued ahead, `queue.ts:162-163`), and N+1 waits with its result.
   - **A failed attempt drops its own prepared result,** and the retry prepares again. One rule, and nothing stale is
     held for a retry the user may never ask for.
   - **Cancelling a file prepared ahead settles it `cancelled` at once,** as a queued one is (`queue.ts:238-242`), and
     aborts its signal. Today an in-flight item waits for "the runner's abort checkpoints" (`queue.ts:243-244`). An item
     prepared ahead is not the runner's yet, so it would wait for file N to finish.
   - **The digest** of a cancelled prepare is stopped by the digest worker's `cancel` (`digestWorker.ts:112-116`,
     `digest.worker.ts:34-37`).
   - **The derivation** runs to its end and is dropped. The media worker has no cancel and `deriveTiers` takes no signal
     (`mediaDeriveWorker.ts:99-120`, `mediaDeriver.ts:231-234`). That is the "at most one derivation" `media.ts:616-617`
     already accepts, and no new op is added.
   - **The frame worker is not touched:** the prepare stage seals nothing, so its `ready` handshake
     (`frameWorker.ts:18-23`, `:102-108`, `:160`) and its latch with `reread` (`:153-156`, `:179-183`) serve the one
     upload stage as today.
6. **What the tile says.** A file prepared ahead shows `preparing` from the start of its prepare until its first
   progress. Then it shows `uploading`. A flip back to `queued` once prepared would make a tile go queued → preparing →
   queued → uploading. The site's states (Queued, Preparing, Uploading, `site/src/content/docs/memories/add-photos-and-videos.mdx:12-15`,
   and its DE twin) stay true, so no page changes. Uc.memory.upload step 3's "names the stage its file is actually in"
   holds: a prepared file waiting is still being prepared for the wire.
7. **Page-thread fall-backs: no special case.** A latched media or digest worker finishes its work on the page
   (`mediaDeriveWorker.ts:49-57`, `digestWorker.ts:50-58`). Overlapping then puts two main-thread stages on one thread:
   the same work in another order, so it cannot lose more than scheduling noise. A predicate to skip the overlap would be
   a second path to test, for a degraded mode the console already names. The residual, named: a hidden tab whose
   page-thread `toBlob` waits for idle periods (`mediaDeriver.ts:78-82`) may wait longer beside a busy pump.
8. **Scope.** Memories' member and guest uploads through the queue. The import (`exportImport.ts:422-427`, its own
   sequential walk outside the queue) and Depot are not in it.

### Memory bound at depth 1

Per file, measured where the tree has a number:
- **Original:** 5.2 MB (`profile_hot_paths.py:115-117`). It is held as a disk-backed `File` and never read whole
  (`mediaDeriver.ts:32-36`, `uploadQueue.ts:198`), so preparing ahead holds no copy of it.
- **Its decode, while derived:** 4032 × 3024 × 4 = 48,771,072 bytes in the media worker (`mediaDerive.worker.ts:53`),
  plus at least the 2048 × 1536 preview canvas, 12,582,912 bytes *(derived from `PHOTOS` and `TIER_SPECS`,
  `mediaDeriver.ts:24-27`)*. Depth 1 keeps one decode at a time: file N's derivation is done before N+1's starts.
- **Tiers held while waiting:** a ≤ 2048 px JPEG at quality 0.85 and a ≤ 512 px one at 0.8. Their bytes are
  **unmeasured** in the tree, and phase 0 records them from the run's `veyq_blob.byte_size` values below an original's.
- **Frames held by the one pump:** ≤ 4 sealed frames of 262,172 bytes plus ≤ 4 plaintext slices being sealed, about
  2 MiB (`transport.ts:68-69`). This is unchanged, since there is still one pump.

So depth 1 adds, over today's peak, the pump's ≈ 2 MiB beside the next decode, and one tier set waiting.

**Seen in passing, not this CR's:** the pick-time probe decodes every picked photo on the page at once, 50 at a time in
S2 (`useOptimisticTiles.ts:83-97`, `mediaDeriver.ts:147-155`). Up to 50 × 48.8 MB could be held at once if the browser
runs them together, which is more than any pipeline depth adds. This is read from the code and unmeasured.

### Not in this CR

- The main-thread relief itself: branch B files it as its own CR.
- A cancel op for the media worker (decision 5).
- The import's overlap and its double hash (#555).
- A real phone (#260): Chromium's throttle is a slower main thread, never a slower device
  (`site/src/content/docs/security/how-veyq-is-tested.md:114-117`).

## Inventory

Run each again at implement time; the hits are the sites.

- **The queue and its readers:** `git grep -n "UploadJob\|nextQueued\|scheduleRunner\|cancelFile" -- src/web/src`.
  Second angle, by the importers: `git grep -ln "core/blob/queue'" -- src/web/src` (Depot, Expenses and Memories import
  it; only Memories gains a `prepare`).
- **The test that pins "one at a time":** `core/blob/queue.test.ts:94-113`. It becomes "one job in its upload stage at a
  time".
- **The upload's stage split:** `git grep -n "uploadMediaVia\|sealMetadata\|readExif\|deriveTiers(" -- src/web/src`,
  tests included. `media.test.ts` and the wire snapshots under `features/memories/__snapshots__/uploadWire.*.json` are
  the oracle that each file's requests do not move.
- **The device floor:** `git grep -n "atFloor" -- src/web/src`. Every seal must follow it.
- **The docs that state "one file at a time":** `git grep -n -i "one file at a time\|sequential, not parallel" -- docs
  site/src/content`. Two hits: `uc.memory.upload.md:26` and `:81`.
- **The profile harness:** `git grep -ln "profile_hot_paths\|e2e/profile" -- scripts tests docs site`. No guard anchors
  on it (`scripts/validate_*.py` absent from the hits).
- **Contracts, backend, admin-web, `@veyq/crypto`: empty.** Nothing on the wire changes.

## Phases

0. **What holds the main thread** (the measurement; the owner's go for the image builds and runs).
   - **Harness** (`tests/e2e/profile/profiling.ts`):
     - a `performance.mark` at `t0` and at `t1`, with fixed names that carry nothing of the content, so the trace and
       the window share a clock;
     - `Performance.getMetrics` before the window as well, kept as `metricsBefore`;
     - nothing else, and no product code.
   - **A reader**, `scripts/profile_attribute.py <run-dir> --sourcemaps <dir>` (Python 3.9: `from __future__ import
     annotations`, stdlib only, its own VLQ decoder). It writes per stem, and as medians per throttle, every number of
     § Phase 0's numbers. It also writes:
     - each worker's busy time, by the chunk its samples run (`frames.worker-*`, `digest.worker-*`,
       `mediaDerive.worker-*`);
     - the store requests split by method, read from the trace's `ResourceSendRequest`;
     - the tier sizes;
     - per file, whether its digest worker interval lies inside the previous file's upload stage (phase 4's existence
       measure, read here as its control).

     The source maps come from `vite build --sourcemap` of the same commit (`s2-upload.profile.ts:9-12`). The reader
     refuses a sampled chunk that has no map.
   - **The reader's controls**, each one that must be able to go red:
     - a `--selftest` on a synthetic trace with known samples, bitten by mis-bucketing one source path (snapshot and
       restore);
     - M from the trace and the `TaskDuration` delta from the metrics agree within 10 %: two angles, keyed on different
       things;
     - "other" stays under 15 % of M's script, or the reading decides nothing and the buckets are extended;
     - the frame worker's busy time falls in the published 5.1–5.5 s;
     - on these before-runs, the existence measure counts **0** digests inside an upload stage.
   - **The reading:** S2 on the candidate images of `main`, three runs unprofiled plus one profiled at throttle 1 and at
     4 (`--no-cpu-profile`), with the load beside each. Record the table in this CR and in #220's row.
   - **Exit:** the rule above names A, B or C. The owner confirms the branch before phase 1. On B or C, this CR stops
     here.

   **Done when:** the reader's selftest is green and bitten, the table is recorded, the branch is named, and every
   `scripts/validate_*.py` exits `0` (guard gaps only if a validator changed).
1. **Docs (A only).**
   - `uc.memory.upload` step 3 and its quality note "Sequential, not parallel" become "one file on the wire at a time;
     the next file's on-device stage runs while the current one is on the wire, one ahead". The byte budget the note
     argues from still holds, since it is the wire's.
   - Its coverage YAML gains a criterion, `next-file-prepared-while-one-is-on-the-wire`, as a `gap` until phase 3 names
     its tests.

   **Done when:** every `scripts/validate_*.py` exits `0`.
2. **Web, core's queue (A only).** First record `npm run check`'s exit code in `src/web` on `main`. Then build
   decisions 2, 5 and 6 in `queue.ts`. The tests (`queue.test.ts`), each negative with its positive control:
   - **Overlap, gated on both sides** (`changes/README.md` § An OVERLAP assertion gates both sides): job A's `run`
     reports progress 0 and then resolves only once B's `prepare` has started, and B's `prepare` resolves only once A
     reported progress. If the queue prepares B only after A settles, the test never settles and goes red.
   - **Not too early:** B's `prepare` is not called before A's first progress. **Bite:** call it at A's start.
   - **One ahead:** with A, B and C, C's `prepare` waits for B's first progress. **Bite:** prepare every queued item.
   - **One upload stage at a time:** the revised `:94` test.
   - **Cancel:** an item prepared ahead settles `cancelled` at once, with its signal aborted. **Control:** left alone,
     it runs, and its `prepare` ran once in total.
   - **Failure:** A fails hard, and B runs next on its prepared result. A's retry calls `prepare` again.
   - **No hook:** a job without `prepare` is never prepared ahead.

   **Done when:** `npm run check` in `src/web` is green.
3. **Web, Memories (A only).** Build decisions 3 and 8: `prepareMedia`, the split of `sealMetadata`, the memo in
   `enqueueUploads`.
   - **`media.test.ts`:**
     - with a prepared result, `uploadMediaVia` calls neither the digest nor the deriver again. **Control:**
       without one, it calls each once;
     - `prepareMedia` makes no transport call and reads no key;
     - in the queue adapter, `atFloor` runs after the prepare and before the first seal.
   - **Wire, two files through `enqueueUploads`** (in `uploadWire.characterisation.test.ts`'s style):
     - file 2's deriver resolves only once file 1's first `PUT` was made, and file 1's complete waits for file 2's
       derivation to start, so the overlap is gated on both sides;
     - the request sequence keeps every request of file 2 after file 1's complete. **Bite:** start file 2's upload
       stage at file 1's first progress;
     - the existing snapshots stay byte-equal.
   - Coverage: the criterion names these tests.

   **Done when:** `npm run check` in `src/web` is green.

   **Browser pass**, on the dev instance as the machine notes say:
   - pick 20 photos into a Memory, and watch one tile say Uploading while the next says Preparing;
   - cancel the one that is Preparing: it goes at once, and the rest upload;
   - cancel all mid-batch;
   - find no worker warning in the console.
4. **The after-reading and the docs (A only).**
   - **The reading:** `profile_hot_paths.py` S2, three runs before (phase 0's images) and three after, interleaved,
     unprofiled, at throttle 1 and 4, with the load beside each, read by `profile_attribute.py`.
   - **The gate:**
     - **The overlap exists:** in every after-run, at least 45 of the 49 pairs have N+1's digest interval inside N's
       upload stage. The before-runs count 0, which is the measurement's control;
     - **No throttle-4 regression:** the after median is not above the before median, and no after-run is above the
       before-runs' maximum;
     - **The throttle-1 gain is real:** the after median is below the before-runs' minimum. Record it beside phase 0's G.
     - A result that contradicts § Expected is reported with its numbers, never fixed in place.
   - **Docs:**
     - `docs/architecture/README.md:316-317`'s sentence is made true;
     - `how-veyq-is-tested.md` gets the after-reading;
     - #220 is deleted.

   **Done when:** `npm run check` in `site/` is green, and every `scripts/validate_*.py` exits `0`.

## Definition of done

**Branch A:**
- phases 0–4 done;
- `npm run check` green in `src/web`, and unchanged in `src/admin-web`;
- `uc.memory.upload`'s new criterion covered, with `python3 scripts/validate_coverage.py` green;
- the reader kept in `scripts/` with its selftest (Q2);
- the published Security page updated. The Use page "Add photos and videos" is unchanged, and decision 6 says why.

**Branch B or C:** phase 0 done, and the reader and the harness additions committed. On B, the relief's row is filed
with the bucket table, and this CR's status stays `planned`. On C, #220 is deleted and this CR with it.

## Open questions (owner)

- **Q1. The exit rule:** the 10 % bars and the rule's order, B before A when both hold. **Recommended: as written.**
  Relief moves the throttle-4 case, which an overlap cannot, and it raises the overlap's own ceiling.
- **Q2. The reader as a permanent script**, `scripts/profile_attribute.py` with a selftest, against a one-off in
  scratch. **Recommended: permanent.** Main-thread attribution has been done by hand four times (#536 twice, #554
  twice). That is past the rule of three (`docs/ai/README.md` § When to add a tool), and the hand method has already
  missed `@noble`'s input copy once (`s2-upload.profile.ts:10-12`).
- **Q3. On B, a CR of its own** (a new row), with this CR waiting, against a relief phase inside this CR.
  **Recommended: its own CR.** It is independent work with its own gate, and the overlap is re-judged on the relieved
  tree.
- **Q4. The tile of a file prepared ahead says `preparing` until its first progress** (decision 6), against a flip back
  to `queued`. **Recommended: `preparing`.** No flicker, and the site's three states stay true.
- **Decisions 1, 2, 5, 7 and 8** are taken above with their reasons: depth 1, the core hook, dropping a failed
  attempt's prepared result, no fall-back special case, and Memories' queue uploads only. Each is open to a veto.

## Retrospective

To be written at the end of implementation.
