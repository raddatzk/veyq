---
id: cr-next-file-prepared-while-one-uploads
title: Read what holds the upload's main thread first, then prepare the next file while one is on the wire, or relieve the main thread instead
status: in-progress
satisfies: ["uc.memory.upload"]
adrs: ["0013", "0017", "0026", "0105"]
invariants:
  - operator-blind - the frame worker is handed what it holds today (the blob's K_blob by structured clone, zeroed once answered) plus the presigned URL and the picked file's handle; it sends the sealed frame to that URL and nothing else, nothing in the clear
  - the wire is unchanged - the same PUTs to the same presigned URLs with the same Content-Type, PUMP_IN_FLIGHT at most, each read to its end; every server write of a file (reserve, windows, complete) stays on the page, in today's order
  - the Worker stays an optimisation - no Worker, a Worker that never loads, or one that dies mid-chunk finishes the chunk on the page thread through the same code (read, `keys.ts` seal, `putChunk`)
  - an abort still settles `cancelled`, never `failed`, and stops the chunk PUTs in flight, the worker's included
---

## Goal

Inbox #220 asked whether the next file's on-device stage should run while the current file is on the wire. The owner
asked first what holds the upload's main thread, since at throttle 4 #554's after-reading had it busy 64–71 s of a
71–78 s upload, and an overlap cannot shorten an upload below the main thread's own work. Phase 0 read it and chose
between three branches (A overlap, B relieve the main thread, C close). **It chose B** (§ Phase 0's reading): the pump's
per-chunk work on the page holds more than half of the throttle-4 upload. This CR builds that relief: each chunk is
read, sealed and `PUT` in core's frame worker, so the page no longer receives every sealed frame and starts every
`fetch`. The overlap (A) is judged again on the relieved tree, from this CR's after-reading (#220's row carries it).

## Phase 0's reading (2026-10-28)

**Substrate.** `main` at `2e0952cd5`, built on this Mac: the web client (`npm run build`) inside the backend's fast-jar,
and the admin client, laid over the last local candidate images (`veyq:candidate-arm64`, `veyq-admin:candidate-arm64`
of 2026-10-26) by a scratch Dockerfile — no registry contacted, so the runtime layers are that candidate's and the code is
`main`'s. The maps: `vite build --sourcemap` of the same tree, whose 17 chunks carry the same names and, but for the
`sourceMappingURL` line, the same bytes as the image's (compared file by file). S2 through
`scripts/profile_hot_paths.py --scenario s2 --ignore-load`: three runs without the CPU profiler and one with it, at
throttle 1 and 4, read by `scripts/profile_attribute.py`. **Load:** the 1-minute load stayed above 8 for the five
minutes waited (19.9 at the end); it was 17.5 and 19.8 at the two invocations' starts and 19–27 during them, other
agents' work. Every number below is a reading on that busy machine.

| median of 3 unprofiled runs | throttle 1 | throttle 4 |
|---|---|---|
| W, the upload (26.5–31.9 s · 74.1–74.7 s) | 29.6 s | 74.5 s |
| M, the main thread's tasks | 16.2 s | 63.8 s |
| W − M | 13.4 s | 10.7 s |
| M by kind: script / style+layout / paint / GC / task time no event names | 6.4 / 0.3 / 0.3 / 0.4 / 8.7 s | 23.2 / 1.8 / 2.0 / 1.6 / 34.8 s |
| sampled script by bucket: pump / reactivity / on-device page side / EXIF / reconcile / other | 3.6 / 1.5 / 0.5 / 0.1 / 0.1 / 0.2 s | 13.8 / 7.0 / 0.4 / 0.2 / 0.3 / 0.8 s |
| **R**, the largest bucket (the pump's script) as a share of W | 11.7 % | **18.6 %** |
| M by its tasks' bucket (a task's native time too): pump / reactivity / no script | 10.3 / 2.1 / 2.3 s | **41.5 / 9.4 / 9.5 s** |
| **G**, what an overlap could move | **9.3 s, 31.5 %** | 9.5 s, 12.8 % |
| per file: P / U / mP / mU (median over files 2–50) | 185 / 354 / 28 / 258 ms | 215 / 1,180 / 82 / 1,103 ms |
| P₁, the first file's on-device stage (pick-time burst) | 1.5 s | 2.9 s |
| workers: frame / digest / derivation | 5.5 / 5.2 / 4.5 s | 5.5 / 4.9 / 4.9 s |
| store requests | 1,152 PUT, 24 GET | 1,152 PUT, 24 GET |

The profiled runs agree (throttle 1: 32.1 s, R 12.4 %, G 31.3 %; throttle 4: 78.5 s, R 17.5 %, the pump's tasks 40.0 s),
and each profiled total is above its unprofiled median, the switch's own control.

**What it says.**
- **The pump holds the main thread.** Its script alone is R = 18.6 % at throttle 4 (≥ 10 %: branch B, whatever G is).
  Counted by task, the tasks whose script is the pump's hold 41.5 s of the 63.8 s, 56 % of W: a sealed frame arrives
  from the frame worker (`HandlePostMessage`, 1,378 tasks, 40 s), and its continuation starts the next chunk's `PUT`
  (`fetch` is 70 % of the pump's samples) and reads its answer. The CR's candidates 1 and 2 named the pump and the
  reactivity; the bulk is the native half of the pump's per-chunk round trip, which no event of the trace names.
- **Reactivity is second**: 7.0 s of script under Vue's scheduler at throttle 4 (9.4 % of W), 9.4 s counted by task (12.6 %).
  The rule asks for the largest; the second is filed (#640).
- **EXIF, the reconcile and the pick-time probe are small** (each ≤ 0.3 % of W), so #558's memory question stays a
  memory question.
- **G at throttle 1 is 31.5 %**, so the overlap would pay there. The rule puts B first because relief moves throttle 4,
  which an overlap cannot, and raises the overlap's ceiling W − M; the overlap is judged again after it.
- **Every control holds** (`profile_attribute.py`, exit 0): M against the main thread's CPU time ×1.026–1.035 at throttle
  1; `other` 2.6–4.6 % of the sampled script; the frame worker at 5.1–5.8 s; 0 of 49 digests inside an upload stage.
  Three controls were corrected against this reading, each from a measured cause, never to fit: `TaskDuration` counted
  7.9 s where the trace's tasks held 14.5 s and `ThreadTime` 14.1 s, so the second angle is `ThreadTime`; under throttle
  4 `ThreadTime` exceeded M by 11–12 % in every run (the throttle's own CPU time outside the page's tasks), so the
  agreement is gated at throttle 1 and printed at 4; the digest worker's own housekeeping (up to 10.7 ms inside one
  upload stage, never a digest, which takes 86–110 ms) counted as a digest at the first threshold of 5 ms, now 40 ms; and
  the frame worker's range became a floor, since its time grows with the load (5.62 s at load 25 against the published
  5.1–5.5 s) and will grow with what this CR gives it.
- **Tier sizes** were not recorded: they bound branch A's memory, which this CR does not build.

## Decisions (branch B)

1. **The frame worker reads, seals and `PUT`s each chunk.** A new op `seal-put` carries `K_blob`, the chunk's index and
   the blob's frame count, the chunk's source (the picked file's `Blob`, which crosses as a handle and is read in the
   worker; a source that is no `Blob` — a copy's frame reader — is read on the page and its slice transferred, as today),
   the presigned URL, and `PUT_CONTENT_TYPE`. The worker seals with `keys.ts` `sealFrameOf` (no second implementation),
   `PUT`s the frame **as a `Blob`**, reads the empty answer to its end, and answers the status alone. The sealed frame
   never returns to the page. Rejected: a worker that owns the whole pump (windows, retries, progress): the control plane
   would move into the worker and every application's transport with it; the per-chunk round trip is what costs.
   - **Why a `Blob`** (added after the first after-reading): with a byte-array body the frame worker was busy 16–17 s
     instead of 5.5 s and bounded the upload at throttle 1 (34.3–42.2 s against 31.8–32.6 s adjacent before). Measured in
     a worker in Chrome, 400 `PUT`s of 256 KiB, four in flight: 6.2–6.7 ms per `PUT` as bytes, 0.85–0.89 ms as a `Blob`,
     the `Blob`'s making included. The page's fall-back keeps the byte body: it runs only without a Worker, and 38 test
     files read a `PUT` body as bytes.
2. **The page keeps the control plane**: the window presigns, the `403` re-sign and its one retry, the progress per
   chunk, `PUMP_IN_FLIGHT`. A `403` retry submits `seal-put` again with the re-signed URL, so the retried chunk is
   sealed again under a fresh nonce; the refused `PUT` stored nothing, so no two frames of one index are ever stored.
3. **Abort**: an aborted chunk rejects with an `AbortError` at once, as today, and the page posts the worker a `cancel`
   for it, which aborts that `PUT`'s own `AbortController`. A `cancel` is no job: it has no answer.
4. **Fall-back** as the front door's other jobs: no Worker, or a latch before the Worker took the job, runs the chunk on
   the page (read, seal, `putChunk`); a Worker that dies after taking it runs it on the page again — re-reading from the
   source, so nothing was lost by the transfer; the `PUT` is idempotent. A `PUT` that fails in the network is an
   answer, not a Worker failure: it rejects with the worker's error text and never runs on the page a second time.
5. **CSP**: the worker's script is served with the shell's policy (`AppShellRoutes`, every response but `/api`), whose
   `connect-src` carries the store's origin; a `PUT` from the worker is held to the same policy as the page's.
6. **The profile harness reads the worker's store requests.** Resource Timing is per realm, so the page no longer sees
   its PUTs; `profiling.ts` adds each worker's resource entries, moved onto the page's clock by the two time origins,
   so the wire (#261) is read as before. Playwright sees and routes a dedicated worker's requests like the page's
   (measured: `page.route`, `context.route` and `page.on('request')` each saw a worker's `PUT` with its body, Playwright
   1.64.1, Chrome), so L1's store holds (`depot-clicks.spec.ts`), its egress route and the driver's failed requests are
   unchanged.

## Inventory

- **The pump and the front door:** `git grep -n "sealFrameOff\|sealFrameAt\|putChunk\|FRAME_OPS\|readSlice" -- src/web/src`
  (`core/blob/transport.ts`, `frameWorker.ts`, `frameOps.ts`, `frames.worker.ts` and their tests). Every upload goes
  through `reserveAndPump`: `git grep -ln "reserveAndPump" -- src/web/src` (Memories' media and banner, Depot's files and
  drop box, Expenses' receipts, core's cover).
- **The worker contract's tests:** `frames.worker.test.ts` sends every op of `FRAME_OPS`; `frameWorker.test.ts` drives
  the front door with a fake Worker. The wire suites (`*.wire.test.ts`, `uploadWire.characterisation.test.ts`) run with
  no `Worker` (vitest), so on the page path: their snapshots must stay byte-equal.
- **The guard on the build:** `scripts/assert-frame-worker-in-dist.mjs` (`verify:frame-worker`).
- **The profile harness:** `tests/e2e/profile/profiling.ts` (`storeRequests`).
- **Docs:** `docs/architecture/README.md` § 10 (the reading), ADR 0017's frame-worker paragraph, ADR 0013 § 2 if it
  names where the pump runs, `site/src/content/docs/security/how-veyq-is-tested.md` (the readings),
  `uc.memory.upload` (what it says about sealing). No contract, backend or `@veyq/crypto` change.

## Phases

0. **What holds the main thread** — done: `profiling.ts`'s window marks and `metricsBefore`, `scripts/profile_attribute.py`
   with its selftest (bitten: `transport.ts` moved to another bucket turns two of its checks red), the reading above.
1. **Web, core's blob transport.** The tests first, then decisions 1–4.
   - `frames.worker.test.ts`: `seal-put` PUTs the frame `keys.ts` would seal, with the one Content-Type, and answers
     the status without the bytes; a `cancel` aborts that PUT; a network failure is an answer. Every op of `FRAME_OPS`
     stays answered.
   - `frameWorker.test.ts`: the request carries the `Blob` and no plaintext (nothing transferred); a non-`Blob` source
     transfers its slice; an abort rejects at once and posts `cancel`; a latch before `ready` and a death after it each
     finish the chunk on the page.
   - `transport.test.ts`: a `PUT` the store refuses with `403` is re-signed and sent once more, sealed anew, and any
     other refusal fails without a re-sign (core's own case beside `media.test.ts`'s re-sign through `addMedia`).
   - The page path keeps today's steps and their order: the chunk is asked for only when a Worker takes it, so
     `media.test.ts`'s one slice per frame and the copy suites' one read per frame stay as they are.
   - The wire snapshots stay byte-equal.

   **Done when:** `npm run check` in `src/web` is green. — Done: red only on timeouts at load 18–24 (as on `main`), the
   timed-out files green alone with a longer timeout, build and every `verify:*` green.
2. **The browser pass** — done on the branch's production build (a kept profile instance: real Traefik, the shell's CSP,
   the bundled store), headless system Chrome, German UI, the instance's throwaway owner through the sign-in form: 12
   photos, 277 PUTs, every one in the frame worker's Resource Timing and none in the page's; then 10 more, one cancelled
   while uploading and one while queued, both `cancelled`, the other 8 done; no worker warning; the one aborted request
   was the cancelled file's `PUT` in flight.
3. **The after-reading** — done (2026-10-28, load 25–40 at the starts, up to 75 between): three unprofiled runs per
   throttle, the branch beside two passes of `main` taken just before and just after it.

   | median (range) | `main`, before | `main`, after | branch (frame as bytes) | **branch (frame as `Blob`)** |
   |---|---|---|---|---|
   | W at throttle 1 | 32.2 s (31.8–32.6) | 29.8 s (26.1–29.9) | 35.4 s (34.3–42.2) | **25.9 s (24.6–28.6)** |
   | W at throttle 4 | 75.4 s (70.8–90.1) | 73.4 s (68.2–81.0) | 42.8 s (41.5–45.6) | **37.5 s (34.5–38.0)** |
   | M at 1 / 4 | 17.3 / 66.9 s | 15.2 / 62.7 s | 6.4 / 23.7 s | **6.6 / 24.1 s** |
   | the pump's tasks at 1 / 4 | 10.9 / 43.6 s | 9.7 / 41.2 s | 0.7 / 1.7 s | **0.5 / 0.9 s** |
   | frame worker | 5.3–6.3 s | 5.2–6.0 s | 16.1–17.2 s | **7.7–8.9 s** |
   | R at 4 (bucket) | 17.9 % (pump) | 17.8 % (pump) | 17.1 % (reactivity) | **23.6 % (reactivity)** |
   | G at 1 / 4 | 31.5 / 11.1 % | 32.1 / 13.6 % | 28.5 / 27.5 % | **36.5 / 27.5 %** |

   Every control holds in every pass but the bytes branch's one refused run (the access token expired in a 99 s window
   at load 75, four API requests answered 401 — environmental, and its M against the CPU time failed, as that control
   is meant to on a starved machine). Two passes ended "REFUSED" only by the driver's host check, because another
   agent's race database container went away during them; their runs are complete. The gate the refine set holds: the
   throttle-4 median fell below every before-run, and the throttle-1 median (25.9 s) is below every before-run (26.1 s
   the lowest).
4. **Docs and site** — done: architecture § 10, ADR 0017's paragraph, how-veyq-is-tested, #220's row (G and R on the
   relieved tree, waiting on #640), #640 (reactivity).

## Retrospective

- **The fork was refined for one branch only.** The CR designed branch A in detail and left B as "a CR of its own"; the
  brief then asked for whichever route phase 0 chose, so B's design (where the PUT runs, what crosses, cancel, the
  fall-back) was decided by the implementer without an owner review. A CR whose phase 0 chooses between builds should
  sketch each branch's shape, or say that the chosen branch goes back to refinement.
- **Sampled script undercounts a main thread.** The CR's buckets were keyed on the script the samples see; the pump's
  script was 18.6 % of the throttle-4 upload while its tasks — the native half of each chunk's round trip, which no
  trace event names — held 56 %. The reader counts both now ([task_buckets], its docstring says why).
- **The first build of the relief moved the cost instead of removing it.** The worker took the `fetch`es and became the
  bottleneck at throttle 1; only the after-reading showed it, and a ten-minute benchmark found the cause (a byte-array
  body costs about seven times a `Blob`'s). A relief that moves work between threads is read on both throttles before
  it is called done, and the cost of what moves is measured where it lands.
- **Controls designed without data needed four corrections on the first real trace** (TaskDuration as the second angle,
  the throttle's own CPU time, the digest worker's housekeeping, the frame worker's range). Each was corrected from a
  measured cause and is recorded in the reader's docstring; a control written into a CR before any trace was read is a
  guess until one has.
- **The machine was never quiet.** The five-minute wait for a load ≤ 8 ended at 19.9; every reading here ran at load
  13–40 (up to 75), beside other agents' builds and races, Microsoft Defender and Word. The adjacent before/after passes
  carried the comparison; absolute numbers from this day are not comparable with #554's. A measurement CR is best run
  alone, not in a wave of five.
- **Tooling:** the worktree's command guard refuses heredocs and compound `git`/`docker` lines, so file edits went
  through the editor and scripts through files; the shared scratchpad holds other sessions' files, one of which shadowed
  Python's `email` module for a script run from its root — run helper scripts from a directory of their own.
