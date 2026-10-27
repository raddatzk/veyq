---
id: cr-blob-frames-off-the-main-thread
title: A blob's chunk frames are sealed and opened in a worker of core's blob transport, on @noble as today
status: planned
satisfies: []
adrs: ["0017", "0013", "0105", "0026", "0047"]
invariants:
  - one implementation per primitive (owner 2026-10-26) - AES-256-GCM stays @noble/ciphers everywhere; the worker runs the conformance-pinned v1.sealFrame/openFrame unchanged, so not one byte moves and @veyq/crypto does not change
  - no key and no plaintext reaches anything but the page's own code in its own origin (ADR 0026); nothing new is sent to the server
  - core's blob transport imports no application (validate_web_core_imports_no_applications), so the worker is core's, not Memories' listing worker
  - a read the caller aborted is answered with an AbortError at once, and an open the AEAD refuses rejects as it does today
---

## Goal

Inbox #536. Every blob's content is sealed and opened as 256 KiB chunk frames under its `K_blob`, through
`@noble/ciphers`, on the thread that calls. For an upload or a whole-blob read, that thread is the page's main thread.
The profile measured that AEAD at about 60 MiB/s. In its 50-photo upload, GHASH alone took 1.9 s of main-thread self
time. A whole-blob open is one synchronous call: a 20 MB original in the lightbox is one long task of ≈ 330 ms, and a
64 MB download one of ≈ 1 s *(derived from the measured open rate)*.

The owner decided against two AES-GCM implementations (2026-10-26: one implementation per primitive). So this CR moves
the frames, not the primitive. The upload pump's seal, the whole-blob open and the chunk provider's open run in one
dedicated worker of core's blob transport, through the same `v1.sealFrame` / `v1.openFrame` they run today. Nothing on
the wire, in the suite or in `@veyq/crypto` changes. WebCrypto's AES-GCM is recorded as rejected for now, with its
numbers, in ADR 0017. A worker could switch to it later without a wire change.

**Ready to implement.** The owner took both recommendations of § Open questions (2026-10-27), so the decisions below
stand as written, and phase 0 has edited ADR 0017.

## Context & constraints

Measured at refine on branch `cr-536-frames-off-main-thread`, cut from `main` at `b6f1644cf`, nothing changed yet.
Every pointer was read in this refine. Claims held from memory are marked *(remembered)*, and figures computed from
measured rates are marked *(derived)*.

### What is bulk: the chunk frames, and nothing else

- **`aeadSeal`/`aeadOpen` have six callers, and two of them are bulk.**
  `git grep -n "aeadSeal\|aeadOpen" -- src/packages/crypto/src` finds:
  - `symWrap`/`symUnwrap`, `fieldWrap`/`fieldUnwrap` and `asymWrap`/`asymUnwrap` (`primitives.ts:69-132`), the
    typed seals of `seal.ts`. Each seals a key, a field or a small document;
  - `sealFrame`/`openFrame` (`v1.ts:189-195`). Only these take a blob's content, and their input has no bound but the
    blob's (ADR 0013 § 3).

  A second angle, keyed on the library: `git grep -n "@noble/ciphers\|gcm(" -- src tests ':!*.test.ts'
  ':!**/package*.json'` finds `primitives.ts` alone, outside the profile reading. The 107 typed-seal calls in 43 files
  stay where they are. Each one is synchronous and takes microseconds:
  `git grep -c -E "v1\.(seal|open|sealField|openField|sealTo|openAs|sealBlobMeta|openBlobMeta)\(" -- src/web/src src/admin-web/src tests/e2e ':!*.test.ts'`.
- **The frame** (`v1.ts:181-195`) is `nonce(12) ‖ AES-256-GCM(K_blob, nonce, plaintext, u32be(index) ‖ u32be(total))`.
  Every frame is independent of every other, and `total` sits in every frame's AAD, so there is no last-chunk flag. A
  frame can therefore be sealed or opened anywhere the key is, one at a time, in any order. Moving it into a worker
  needs nothing from the format.
- **The page-thread seams are three, all in `src/web/src/core/blob/`.** Locator:
  `git grep -n "sealFrame\|openFrame\|openFrames(" -- src/web/src ':!*.test.ts'`.
  - `sealFrameAt` (`transport.ts:51-57`), inside the one upload pump (`pumpBlob`, `:79-128`; up to `PUMP_IN_FLIGHT =
    4` frames in flight). Every upload goes through it: Memories' originals and tiers (`media.ts:651-658`), covers
    (`coverWrite.ts:115`), banners (`bannerWrite.ts:124`), Depot files member and guest (`uploadFile.ts:172`,
    `shareUpload.ts:88`), and Expenses receipts (`receipts.ts:143`).
  - `openFrames` (`keys.ts:46-48`), the whole-blob open. Through `openBlobWithKey` (`transport.ts:249-253`) it serves a
    lightbox's original, a Memories download (`media.ts:1151-1154`), a cover (`coverStore.ts:52`) and a moderator's
    review (`MediumReview.vue:44`, `PotEntryReview.vue:85`).
  - The chunk provider's `getPlaintextChunk` (`transport.ts:329-374`, `makeChunkProvider`), which serves video range streaming (ADR 0047),
    Depot's streamed download (`download.ts:58`), receipts and attachments.
- **One frame open is already off the main thread.** Memories' listing worker opens a grid's thumbnail frames
  (`listingOpenerCore.ts:71-79`, ADR 0105 decision 3) and is handed in through `OpenOpts.openFrames`
  (`transport.ts:203-210`, `media.ts:1147-1148`). It is an application's worker, and core may not import an
  application. So it cannot carry core's seams, and it keeps its thumbnails (decision 6).
- **A re-key re-wraps `K_blob` and never re-seals a frame** (`rekeyScope.ts:248-258`). A shared blob re-wraps the same
  `K_blob` (`sharedBlob.ts:50-61`). Neither is a seam.
- **A pump's source is not always a `Blob`.** `BlobSource` is an interface (`types.ts:32-36`): an import's
  `PayloadFile` (`core/export/hooks.ts:36`) and a copy's source (cr-copy-node) read from elsewhere. So the worker cannot
  be handed "the file". The page reads each slice, which is async I/O and not main-thread work, and hands the bytes
  over.

### What the worker pattern already is here

Four workers exist, and three share one front-door shape:
- `argonWorker.ts` (in `@veyq/crypto`), `mediaDeriveWorker.ts` and `listingOpener.ts`: a lazily created module-level
  worker, a `workerUnavailable` latch, a `seq`-keyed pending map with a per-request fallback closure on the page thread,
  and a one-time `warnOnce`;
- each has a dispatcher test that loads the worker module under a stubbed `self` and sends every op
  (`argon.worker.test.ts`, `listingOpener.test.ts`);
- each has a build assertion that the chunk was emitted, `verify:*-worker` (`src/web/package.json:19-23`), because Vite
  emits a worker only for `new Worker(new URL('…', import.meta.url))` spelled as one expression;
- `worker-src 'self'` already admits a same-origin worker (`assert-csp-policy-in-dist.mjs:34-35`).

Under vitest there is no `Worker`, so every unit test takes the page-thread path. That is how the listing worker and
the derive worker are tested today.

### Measured: what moves, and what it costs

- **The rates.** The profile's crypto reading (Chromium, 2026-10-23; readings directory out of git,
  `crypto-t{1,4}-r{1,2,3}.crypto.json`, every conformance check `true`) measured `@noble` framed:
  - 4 MiB: 67–69 ms to seal, 65–73 ms to open;
  - 64 MiB: 1,038–1,049 ms to seal, 995–1,004 ms to open, at throttle 1;
  - ≈ 4.2 times slower at throttle 4.
- **The throttle never reaches a worker.** The Argon2id worker ran in 5,354 ms at throttle 1 and 5,413 ms at 4
  (`site/src/content/docs/security/how-veyq-is-tested.md:101-103`). So under the profile's throttle a worker's seal runs
  at desktop speed. That is the emulation's artefact, not a phone's truth, and the after-reading says so.
- **The upload.** 50 photos of ≈ 3.6 MB took about 41 s at throttle 1, the wire 9.5 % of it (#220).
  ≈ 200 MB of frames *(derived: originals plus tiers)* at 60 MiB/s are ≈ 3.3 s of main-thread AEAD, and ≈ 14 s at
  throttle 4 *(derived)*.
- **WebCrypto, for the record** (decision 8): same browser and inputs, ≈ 1,080 MiB/s whole and ≈ 2,200 MiB/s framed.
  Both matched the v1 vectors and `@noble`'s bytes. It slowed ≈ 4–4.7× under throttle 4, as the main thread does, so
  "async" did not mean "off the main thread" in Chromium.

### Not in this CR: the other bulk primitive on the main thread

`contentDigest` (`sharedBlob.ts:27-34`) hashes with `@noble`'s SHA-256 at these sites:
- every Memories original before its upload (`media.ts:613`);
- a Depot file's shared-blob check (`sharedBlob.ts:76`);
- a banner (`useBannerEditor.ts:53`);
- an export and an import (`exportSubtree.ts:207`, `importSubtree.ts:258`).

It reads 256 KiB at a time, so no single call is a long task, but its total is main-thread work. Its cost was never
measured. Q2 filed it as inbox #554, numbered by phase 1's re-read.

## Decisions this CR takes

1. **One frame worker, core's: `src/web/src/core/blob/frames.worker.ts`, with its front door `frameWorker.ts`.** It
   takes the existing front-door shape (§ What the worker pattern already is here): one module-level worker, created on
   the first frame, a latch, a `seq`-keyed pending map and `warnOnce`. Its ops, named once in a shared ops list as
   `argonOps.ts` names Argon's:
   - `seal-frame`: `{ blobKey, index, total, plaintext }` → the frame. The nonce is drawn in the worker
     (`v1.randomBytes(12)`, `crypto.getRandomValues` exists in a worker).
   - `open-frames`: `{ blobKey, total, from, frames }` → the joined plaintext of `frames[from …]`, each opened at its
     own index against `total`. This is `keys.ts`'s `openFrames`, generalised to a run of a blob, because the AAD
     binds the whole blob's `total` and never a run's length (the trap `transport.ts:369-370` states).

   The worker imports `@veyq/crypto/v1` and `keys.ts`, which `keys.ts:1-4` already keeps free of the API client and
   page state for exactly this kind of reader. It holds no state between requests.
2. **What crosses, and what is copied.**
   - **`K_blob`, 32 raw bytes, by structured clone in each request.** The worker zeroises its copy when the request is
     answered. It keeps no key registry, so there is nothing to forget on a close and nothing a stale request can find.
   - **The plaintext and the ciphertext cross in the transfer list, never copied.** For a seal, the page reads a slice
     with `slice.arrayBuffer()`, a fresh buffer it owns, and transfers it. The sealed frame comes back transferred.
     For an open, each fetched frame (`readScheduler.ts:255`, `new Uint8Array(await res.arrayBuffer())`, a whole
     buffer) goes in transferred, and the plaintext comes back transferred. A view that does not span its whole buffer
     is copied first, because transferring it would detach bytes the caller still holds.
   - **The trust argument is ADR 0105 decision 3's.** The worker is the page's own origin and code, so it sits at the
     page's trust tier (ADR 0026). Priced against the attacker it could matter to (`docs/ai/README.md` § Price a
     confidentiality layer): one who reads the page's memory already reads the raw `K_blob` in the plan
     (`transport.ts:41`) and the `K_node` it is wrapped under. A second short-lived copy in the worker adds nothing for
     that attacker. The CR makes no new zeroisation promise and removes none. The plan's own copy stays as it is today.
3. **The three seams.**
   - `sealFrameAt` reads its slice on the page and awaits the worker's `seal-frame`. The pump's shape is unchanged:
     4 in flight, windows, the 403 retry.
   - `openFrames` becomes `openFramesOff` (async), the default `FrameOpener` of `openBlobWithKey` when the caller
     passes none.
   - `getPlaintextChunk` opens its one frame through `open-frames` with `from = index`.

   `keys.ts`'s synchronous `openFrames` stays. It is what the worker runs, and the page-thread path under decision 5.
4. **Jobs are bounded, so a cancel is prompt and the queue stays fair.** The seal and the open share one worker, and a
   worker runs one job at a time. So a whole-blob open is sent as runs of at most 16 frames (4 MiB, ≈ 70 ms at the
   measured rate). The front door checks the caller's `AbortSignal` between runs. An abort rejects with an
   `AbortError` at once, and the answer of a run already in the worker is dropped by its `seq`. At most one run is ever
   wasted, so an upload's seals wait at most one run behind a lightbox. The page joins the runs, a 64 MB `set` of a
   few milliseconds *(remembered: memcpy speed)*. The pump sends one frame per job, so its cancel granularity is one
   frame, as today.
5. **Errors keep their meaning.**
   - **Source:** a frame the AEAD refuses. The worker answers `source`, and the front door rejects with an `Error`, as
     `openFrame`'s throw does today. It is not retried on the page, because the same bytes fail the same way. No
     caller reads the error's text (`git grep -n -i "ghash\|invalid tag" -- src/web/src` is empty).
   - **Infrastructure:** a worker that does not load, `error`/`messageerror`, or a `postMessage` that throws. This
     latches the worker off for the session, finishes every outstanding request through the same `@noble` call on the
     page thread, and warns once (Q1). It is the same code on another thread, so it is no second implementation.
   - **Abort:** decision 4.
6. **The listing worker keeps its thumbnails.** Its `open-frames` is part of a pipeline that already opens a grid's
   metas and keeps the Memory's keys (ADR 0105 decision 3). Routing thumbnails through core's worker would serialise
   them behind an upload's seals for no gain. `OpenOpts.openFrames` stays the seam by which an application brings its
   own opener.
7. **The conformance proof does not change: one implementation, the vectors.** `v1.conformance.test.ts` keeps pinning
   `sealFrame`/`openFrame` to `chunk_frame` and `chunk_frame_size`, and the worker runs exactly those functions. The
   proof that the placement changes nothing is the argon worker's:
   - a dispatcher test sends every op to `frames.worker.ts` under a stubbed `self` and compares each answer with the
     page-thread result;
   - the web's wire tests keep opening captured PUT bodies with `v1.openFrame` (`depot/uploadFile.wire.test.ts:175`,
     `depot/copy.wire.test.ts:183`, `memories/copy.wire.test.ts:282`).

   **No `chunk_frame_empty` vector.** It was worth adding only while a second implementation met the empty frame
   for the first time. With one implementation, it would re-pin a function whose AAD and layout the `chunk_frame` KAT
   already pins byte-exact (ADR 0045 Consequences), and whose empty-frame length `v1.conformance.test.ts:1363-1373`
   already asserts. A future WebCrypto change brings it with it.
8. **WebCrypto is rejected for now, and recorded so** (the ADR 0017 draft below). The worker is the seam where it could
   come in later without a wire change: `frames.worker.ts`'s internals could switch and nothing outside would notice.
   The record names the trigger that re-opens it: an after-reading in which the frame worker, not the wire, bounds an
   upload's throughput.
9. **A build guard, no lint door.**
   - `verify:frame-worker` (`src/web/scripts/assert-frame-worker-in-dist.mjs`) is the twin of
     `assert-listing-worker-in-dist.mjs`. It checks that exactly one `assets/frames.worker-*.js` exists, that it
     carries a sentinel of the worker's own, and that the app references it. It is wired into `npm run check` and
     bitten by splitting the constructor.
   - That production code reaches frames only through the front door is proved once, by phase 2's locator, which
     lists no page-thread `v1.sealFrame`/`v1.openFrame`/`openFrames(` outside `keys.ts`, the worker and tests.
   - A permanent ESLint door like `veyq/argon-through-the-worker` is not built. The Argon door earned its keep with four
     bypasses found by reading, each freezing the page for 0.8 s. Here three seams sit in one directory, and the
     profile reads the long task a bypass would bring back. Worth re-asking the day a bypass is found.

### Expected effect, for the after-reading to confirm or refute

- **Read:** opening a 20 MB original or downloading a 64 MB one puts no long task on the main thread. The ≈ 330 ms
  and ≈ 1 s move to the worker. **The picture does not arrive sooner.** The latency stays the open's plus a transfer.
  What changes is that the page keeps answering meanwhile. Video streaming loses its ≈ 4 ms per frame on the main
  thread.
- **Upload:** the seal's ceiling stays ≈ 60 MiB/s, with one worker and the same code, so an upload is not faster
  because of the seal. Its ≈ 3.3 s of main-thread AEAD (≈ 14 s at throttle 4) leave the main thread *(derived)*.
  They now run in parallel with the page's own work: the next file's SHA-256 and the queue's UI. They also run in
  parallel with the `PUT`s. The total may shrink by up to that overlap, which phase 2's S2 re-read measures.
- **Unchanged:** the wire, the suite, the vectors, `@veyq/crypto`, the backend, admin-web and the e2e harness.
  `git grep -n "sealFrame\|openFrame\|CHUNK_FRAME" -- src/admin-web/src tests/e2e/src` is empty: neither seals a
  frame, and both keep importing `@veyq/crypto/v1` untouched.

### The edit to ADR 0017 (applied in place in phase 0, 2026-10-27, no addendum — `docs/decisions/README.md`)

The suite does not change, and where a frame runs is placement, which ADR 0017 does not decide (the Argon worker isn't
in it either). Two things in it do bind a future decision, and only those go in:

**Decision 1**, after "AES-256-GCM-class AEAD (already quantum-resistant at 256-bit).", insert:

> Each primitive of the suite has one implementation, `@noble` (owner 2026-10-26), so the conformance vectors pin the
> code that runs, and no second implementation's agreement has to be proven in every browser. A key the browser keeps
> only for itself is the one exception, because only WebCrypto makes it non-extractable: the device-local seals of
> ADR 0005 never reach the wire, and the same browser unwraps what it wrapped.

The exception was added when phase 0 applied the draft: `deviceKeyWrap.ts` and `sessionVault.ts` already seal with
WebCrypto's AES-GCM under a non-extractable key, so the sentence without it was false.

**Consequences**, after "Rejected for now — HPKE with a post-quantum KEM", a new bullet:

> - **Rejected for now — the platform's WebCrypto AES-GCM for a blob's chunk frames.** In Chromium (2026-10-23),
>   `@noble` sealed and opened frames at about 60 MiB/s. WebCrypto ran at about 1,080 MiB/s over a whole buffer and
>   about 2,200 MiB/s over 256 KiB frames, gave the v1 vectors' bytes and `@noble`'s, and slowed with the main thread
>   under CPU throttling. Rejected because it is a second implementation of a primitive of the suite. Its agreement
>   would need a proof in every engine, and a disagreement writes frames only that engine opens. The frames' cost is answered by
>   placement instead: they are sealed and opened in a worker of core's blob transport
>   (`src/web/src/core/blob/frames.worker.ts`), off the page's main thread. The worker is also where WebCrypto could
>   come in without a wire change, because the bytes are the same. Re-ask when a reading shows that the frame worker,
>   and not the wire, bounds an upload.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Contracts | nothing (decision 7) | `python3 -c "import json;print(json.load(open('contracts/crypto/vectors/v1.json'))['chunk_frame'])"` |
| Backend | nothing: it reads `chunk_frame_size` only, which does not change | `git grep -n "chunk_frame" -- src/backend` |
| `@veyq/crypto` | nothing | `git grep -n "sealFrame\|openFrame" -- src/packages/crypto/src ':!*.test.ts'` |
| Web: the worker | `frames.worker.ts`, `frameWorker.ts`, its ops list, the dispatcher and front-door tests | `ls src/web/src/core/blob/`; the shape: `sed -n 1,60p src/web/src/features/memories/listingOpener.ts` |
| Web: the seams | decision 3 | `git grep -n "sealFrame\|openFrame\|openFrames(" -- src/web/src ':!*.test.ts'` |
| Web: the tests | tests that call `openFrames` synchronously or stub the opener | `git grep -n "openFrames\|FrameOpener" -- 'src/web/src/**/*.test.ts'` |
| Web: the build | `verify:frame-worker`, wired into `check` | `grep -n '"verify:\|"check"' src/web/package.json` |
| Profile | S2 re-read; a whole-blob open timed with a long-task observer | `grep -n "Re-run it" -A12 site/src/content/docs/security/how-veyq-is-tested.md` |
| Docs | ADR 0017 (the draft); architecture §10's "largest avoidable cost" sentence | `grep -n "largest avoidable cost" docs/architecture/README.md` |
| Site | Security, `how-veyq-is-tested.md`: the upload bullet says moving bulk encryption to the browser's AES "is tracked". It becomes the after-reading and the worker. No Use page: nothing a member sees or does changes. `cryptography.md` stays true (`@noble` throughout) | `grep -n "authentication step\|is tracked" site/src/content/docs/security/how-veyq-is-tested.md` |
| Inbox | #536 deleted at the end; #220 given the after-reading | `grep -n "^| 220 \|^| 536 " docs/inbox.md` |

## Phases

0. **Refine close (the refining conversation, not the implementer).** *Done 2026-10-27.* Once the owner has
   answered: edit ADR 0017 in place with the draft above, and write the answers into this CR's decisions. **Done
   when:** every `scripts/validate_*.py` exits `0`.

   **Contracts, backend and `@veyq/crypto`: empty, on purpose** (decision 7, the Inventory). No byte moves, and the
   backend performs no content crypto (`src/backend/CLAUDE.md`).
1. **Web.** Before the first edit, record the exit code of `npm run check` in `src/web` on `main`, and the guard
   baseline. Neither was run at refine, because this worktree has no `node_modules`. Then build decisions 1–6 and 9.
   - **The dispatcher test.** Every op through `frames.worker.ts` under a stubbed `self`, against the page-thread
     result. A refused frame answers `source`.
   - **The front-door tests**, with a fake `Worker` (`listingOpener.test.ts`'s pattern). Each negative has a positive
     control on the same predicate:
     - an abort between runs rejects `AbortError`, and a later run's answer is dropped. **Control:** an unaborted open
       of the same blob resolves to `openFrames`'s bytes;
     - an `error` event finishes outstanding requests on the page thread and warns once. **Control:** a healthy worker
       warns nothing;
     - a transferred buffer is detached on the page. **Control:** a sub-view is copied, and its parent stays usable.
   - **The pump** seals through the worker and still retries a 403 and cancels on abort. The existing transport and
     wire tests stay green: they are the oracle.
   - **`verify:frame-worker`** is bitten by splitting the constructor: snapshot and restore, never `git checkout`.
   - **The locator** of decision 9 is run again before the commit, and every remaining hit is spared by name.

   **Done when:** `npm run check` in `src/web` is green, its `verify:*` steps included, and `npm run check` in
   `src/admin-web` is unchanged.

   **Browser pass, on the dev instance as the machine notes say:**
   - upload a photo and a Depot file of more than 512 KiB (three frames, a short last one), and an empty Depot file;
     then open each one: the lightbox, a download, a video's range stream;
   - open a 20 MB original and download a 64 MB file under a `PerformanceObserver({ type: 'longtask' })` in the
     console: no task above 50 ms is attributed to the open;
   - cancel an upload and close a lightbox mid-open, and read the console;
   - read `frames.worker` in the sources panel's worker list, and check that its warning is absent.

   **The after-reading:** `scripts/profile_hot_paths.py` re-run for S2 at throttle 1 and 4, three runs each. Record
   the upload's total, the main thread's self time and its frame AEAD share, the worker's busy time, and SHA-256's
   main-thread self time (Q2: the number #554 is decided on). Report a result that contradicts § Expected effect with the numbers. Never fix it in
   place.
2. **Docs and site.**
   - `how-veyq-is-tested.md`'s upload bullet gets the after-reading and the worker.
   - Architecture §10's sentence is made true.
   - #220 gets its new number and verdict (§ What it changes for #220).
   - #536 is deleted.

   **Done when:** `npm run check` in `site/` is green, and every `scripts/validate_*.py` exits `0`.

## What it changes for #220

#220 asks whether to prepare the next file's on-device stage during the current file's upload. Its ceiling was the
wire's share, 9.5 % at throttle 1, because the seal ran on the main thread beside the next file's SHA-256 and
competed with it. After this CR:
- the current file's seal runs in the frame worker;
- its `PUT`s are on the network;
- the next file's derivation runs in the media worker, and only its SHA-256 and EXIF stay on the main thread.

So an overlap could hide the whole upload stage, seal plus wire, not the wire alone: ≈ (3.3 s + 3.9 s) / 41 s
≈ 17 % at throttle 1 *(derived)*. **#220 is re-judged on phase 1's S2 re-read.** It may now clear the bar it missed
at under 10 %, and it should be decided on the measured share, not on this estimate.

## Definition of done

- Every chunk frame a page seals or opens through core's blob transport runs in the frame worker, through the same
  `@noble` code, or on the page thread only while that worker cannot run (Q1).
- The wire, the suite, the vectors and `@veyq/crypto` are unchanged.
- No long task opens a 20 MB original. The after-reading is published, and #220 carries it.
- `verify:frame-worker` guards the build.
- ADR 0017 records "one implementation per primitive" and WebCrypto rejected for now.
- No coverage file changes. `satisfies: []` holds because no use case's behaviour or criterion moves. The proof of an
  unchanged result is the dispatcher test and the wire tests, as for the Argon worker.
- Inbox #536 is deleted.

## Open questions (owner)

**Decided.** Owner 2026-10-27 ("Passt"): both recommendations are taken. Q1 is (a), Q2 is (a), filed as inbox #554.

1. **When the frame worker cannot run, what happens?** Cases: a mis-bundled chunk, a CSP refusal, a worker that
   crashes. Vitest, which has no `Worker`, is the same case.
   - (a) finish on the page thread through the same `@noble` call, latch the worker off for the session, and warn once.
     This is the shape all three front doors have;
   - (b) refuse: the upload or the open fails with an error the page shows.

   **Recommendation: (a).** It is the same code on another thread, not a second implementation, so the principle "one
   implementation per primitive" is untouched. A member gets today's behaviour, slower, instead of a broken upload.
   `verify:frame-worker` makes the mis-bundled case loud in the build, where it is cheapest. Under (b), every unit test
   of the transport would need a `Worker` shim, because vitest has none.
2. **SHA-256 over every original also runs `@noble` on the main thread. File it?** `contentDigest` hashes every
   Memories original before its upload, and every export and import, 256 KiB at a time. Its cost has never been
   measured.
   - (a) file an inbox row now. It gets its number from phase 1's S2 re-read and is decided then; the frame worker would
     be its natural home, with a stateful `digest` op;
   - (b) fold it into this CR now: a `digest-begin` / `digest-update` / `digest-end` op on the frame worker, with
     `contentDigest` and the export's hasher sending slices to it;
   - (c) leave it unfiled.

   **Recommendation: (a).** No reading shows its cost, and #536 itself began with a number. Once the frame worker
   exists, (b) is a small CR, decided on the measured share.

## Retrospective

_Filled by the implementer._
