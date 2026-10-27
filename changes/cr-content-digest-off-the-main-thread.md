---
id: cr-content-digest-off-the-main-thread
title: A file's SHA-256 is read in a worker of core's blob transport, handed the file, and beside the derivation
status: planned
satisfies: []
adrs: ["0017", "0013", "0026", "0082", "0102"]
invariants:
  - one implementation per primitive (ADR 0017 decision 1) - SHA-256 stays @noble/hashes through v1.sha256Stream on whichever thread runs it; no crypto.subtle.digest
  - the digest is byte for byte the SHA-256 of the plaintext, 43 base64url characters, as blob-meta's sha256 carries it and as other clients compare it later (ADR 0013 § 5)
  - no key and no plaintext reaches anything but the page's own code in its own origin (ADR 0026); nothing new is sent to the server
  - core's blob transport imports no application (validate_web_core_imports_no_applications), so the worker is core's
  - a digest the caller aborted rejects with an AbortError at once, and the upload queue keeps reading that name as cancelled
---

## Goal

Inbox #554. Before a file is uploaded or matched, `contentDigest` (`src/web/src/core/blob/sharedBlob.ts:27`) hashes it
with `@noble`'s SHA-256 on the page's main thread, 256 KiB at a time. In the profile's 50-photo upload after #536 that is
3.7–3.9 s of main-thread self time at throttle 1 (31 % of the main thread's work) and 15.7–16.0 s at throttle 4.

The owner's starting point was core's frame worker with a stateful `digest-begin` / `-update` / `-end` op. This refine
recommends something else, for two reasons the code shows:
- **The digest sits on the upload's critical path because of WHEN it runs, not WHERE.** It runs alone, before the
  derivation, while every worker waits. Moved to a worker and still run first, it costs a real device the same time.
  Run beside the derivation, which it does not depend on, it leaves the critical path on any thread.
- **A `Blob` can go to a worker whole.** The worker then reads its own slices. One request per digest, nothing
  transferred, no hash state that outlives a request, and the page can always start again from byte 0.

So: `contentDigest` keeps its signature and becomes a front door. A `Blob` is hashed in a digest worker of its own in
`core/blob`, by the same `v1.sha256Stream` loop. Any other source is hashed on the page as today. Memories' upload starts
the digest and the derivation together. Nothing on the wire changes, and the digest's bytes do not change.

**Not ready to implement.** § Open questions holds four questions for the owner. Q1 and Q2 decide the shape. The
decisions below are written as recommended.

## Context & constraints

Measured at refine on `worktree-agent-a986bf07fa021fc5a`, fast-forwarded to `main` at `86a2a7852` (the #536 merge),
nothing changed. Every pointer was read in this refine. Claims held from memory are marked *(remembered)*, and figures
computed from measured ones are marked *(derived)*. This worktree has no `node_modules`, so `npm run check` was not run.

### Where the digest is read: four sites, one of them not a `Blob`

Locator: `git grep -n "contentDigest\|findEqual(" -- src/web/src ':!*.test.ts'`. Second angle, keyed on the
library: `git grep -n "sha256Stream\|sha256Digest" -- src/web/src ':!*.test.ts'`.
- **Every Memories original before its upload**, `uploadMediaVia` (`features/memories/media.ts:613`). It serves a
  member's upload, a share contributor's upload, and the import of a download (`exportImport.ts:426`, through
  `addMedia`). Its source is `AddMediaSource.blob`, typed `Blob` (`shared/image/mediaDeriver.ts:37-38`).
- **The cover dialog's match** of a picked image against the gallery: `findEqual` (`sharedBlob.ts:76`), whose only
  caller is Memories' `findEqualMedium` (`storedMatch.ts:40`). Core's cover upload reaches it through the registry hook
  `cover.findStored`. The file is typed `Blob` (`storedMatch.ts:30`).
- **A banner picked in its dialog**, `useBannerEditor.ts:53`, on a `File`.
- **A video banner restored by an import or a copy**, `exportImport.ts:465`, on `ctx.payload(...)`, a `PayloadFile`
  (`core/export/hooks.ts:36`). For a download it is a `File`. **For a copy it is not a `Blob`:** every copy payload is
  `framedPayload` (`core/export/copySubtree.ts:122`, `copySource.ts:25`), a seekable object of closures whose slices
  open frames through the chunk provider. It cannot be structured-cloned.

The second angle finds two more streaming hashers, outside `contentDigest`:
- the export's (`core/export/exportSubtree.ts:207`), over the decrypted chunks it writes into the ZIP;
- the import's check (`core/export/importSubtree.ts:258`), over a ZIP entry's stream.

Both are one pass beside a CRC-32 that also runs on the page (`zip.ts:153-159`, `zip.ts:497-505`), and their sources
are async streams, not `Blob`s. The remaining `sha256Digest` calls hash tokens, keys, notes and sealed revision bodies of
bytes to kilobytes (`manageShares.ts`, `shareNote.ts`, `identityPins.ts`, `expenses/domain/codec.ts`).

**No Depot file is hashed.** The inbox row named "a Depot file's shared-blob check". `git grep -n -i "sha256\|digest" --
src/web/src/features/depot` is empty, and `findEqual`'s only caller is Memories'. Admin-web and the e2e harness call
neither function (`git grep -n "contentDigest\|sha256Stream" -- src/admin-web/src tests/e2e` is empty).

### What the digest is for, and what a wrong one does

- **What it must equal.** `blob-meta`'s `sha256`: the SHA-256 of the blob's plaintext as 43 base64url characters
  (`v1.json`'s seal note; `src/packages/crypto/src/blobMeta.ts:14-24`). It is sealed under the blob's own `K_blob`, so
  the server never reads it and compares nothing (ADR 0013 § 5). One upload writes it. A later upload compares it, in
  another session or another member's browser: a cover, a banner, or a gallery original against the video banner
  (`storedMatch.ts:1-12`). A video banner's `sha256` also sits in the node's sealed meta (ADR 0102).
- **A wrong digest is an integrity defect, not a confidentiality one.**
  - A false non-match stores the file a second time. Only the sharing of storage is lost.
  - A false match makes the new slot a row on another stored object of the same node. The banner or cover then
    silently shows a different file than the one picked. The member already holds that `K_blob`, and the server still
    sees ciphertext only.
  - The dangerous wrong digest is a constant one, for example the SHA-256 of a detached, empty buffer. It makes every
    file of the same sealed size match. The tests below carry a positive control for exactly that.
- **The function stays `@noble`.** ADR 0017 decision 1 already decides it: one implementation per primitive, and
  SHA-256 is one. `crypto.subtle.digest` is also unusable here regardless: it has no incremental form, so it would need
  the whole file in memory. That is against ADR 0013's "never holds the whole file". No ADR changes.

### When the digest runs in an upload

- **The queue runs one job at a time** (`core/blob/queue.ts:9-11`, `scheduleRunner` at `:183-195`).
- **One Memories file is strictly sequential** (`media.ts:609-631`):
  1. its type is sniffed;
  2. **its digest is read, alone**;
  3. the stored-original check uses the digest;
  4. `deriveTiers` runs in the media worker for an image, or on the page for a video poster;
  5. the metadata is sealed;
  6. the pump seals in the frame worker and `PUT`s;
  7. the medium is completed.
- **The derivation does not need the digest.** `deriveTiers` takes the source alone and never rejects: its own catch
  makes an upload original-only (`shared/image/mediaDeriver.ts:231-257`). The digest is first read in
  `originalBlobMetaOf`, when the original is planned, and by `storedOriginal`. Neither runs before the derivation's
  result is needed.
- **So today the frame worker is idle while a digest runs.** File N's seals are done before file N+1's digest
  starts. A digest in the frame worker would compete with seals only once #220 prepares the next file during an
  upload, or with a lightbox open or a video stream during an upload.

**Measured: one file's stages.** From the #536 after-reading's own files: `.run/profiles/20261027-072612/` in the #536
worktree, out of git, one run each, `s2-t1-r1` and `s2-t4-r1`. Each file's digest window is the CPU profile's samples
under the minified `contentDigest`. The derivation and the pump are the media and frame workers' `RunTask` events in the
trace, between two digest windows. Medians over the 50 files:

| | throttle 1 | throttle 4 |
|---|---|---|
| digest (its window, the reads included) | 113 ms (p10 103, p90 127) | 394 ms (p10 342, p90 432) |
| derivation (the media worker's first to last task) | 212 ms | 227 ms |
| pump (the frame worker's first to last task) | 255 ms | 1,019 ms |
| pump's end to the next file's digest | 58 ms | 100 ms |
| one file | 588 ms | 1,703 ms |

The frame worker's first seal starts a median 54 ms (throttle 1) before the media worker's last task ends. So the
derivation's critical part is about 160–210 ms *(derived)*. The digest window starts 0.2 ms before the derivation's
first task, which confirms the sequence above.

### Measured: how much of the 3.8 s is SHA-256, and how much of it may be the profiler

- **All of it is `contentDigest`.** In `s2-t1-r1`, noble's compression `process` has 2.38 s of self time, and its
  three helpers `Gf`/`Iie`/`Fie` (minified) have 1.38 s. Every sample sits under the minified `contentDigest` (`zX`).
  Its `slice` and `arrayBuffer` add 0.32 s.
- **The photos are 5.2 MB, not 3.6 MB.** `ls -la` and `du -sh` of that run's `fixtures/s2` show 50 files of 5.22 MB,
  250 MB together. #220's row and the site say "about 3.6 MB".
- **node hashes the same bytes about 4.4 times faster.** `@noble/hashes` 2.2.0, `sha256.create()` fed 256 KiB slices
  of a 3.6 MB random buffer, node v23.11.0, this Mac at load average ≈ 13, three runs of 20 files:
  - 11.6–12.1 ms per file, 298–311 MB/s, 0.83–0.86 ms per slice;
  - about 17 ms for a 5.2 MB photo *(derived)*;
  - against the profiled page's ≈ 76 ms of self time per photo (3.8 s / 50).
- **The CPU profiler is the likely cause** *(a hypothesis, not a measurement)*.
  - The same node, same day, sealed 4 MiB as `@noble/ciphers` 2.2.0 frames in 49.4–50.8 ms. The profiled Chromium crypto
    reading sealed them in 67–69 ms (2026-10-23). So node and the profiled page differ by about 1.35× for AES.
  - The frames' AEAD, though, took 12.4 s of main-thread self time before #536 (2026-10-24), and 5.1–5.4 s in the frame
    worker after it (2026-10-27). That is a ratio of about 2.3–2.4 *(derived)*.
  - The CDP profiler (100 µs) samples the main thread only, so a worker escapes its overhead.
- **What follows for this CR.**
  - The digest's real main-thread cost is likely between ≈ 0.9 s (node's rate) and ≈ 1.6 s (the frames' ratio) per 50
    photos at throttle 1 *(derived)*.
  - A profiled after-reading will credit the move with the profiler's own overhead. Q3 asks for the control that tells
    the two apart.

## Decisions this CR takes

1. **`contentDigest` keeps its signature and becomes the front door.**
   - A `Blob` source goes to core's digest worker. Any other `BlobSource` is hashed on the page thread, by the same loop
     as today.
   - So no caller changes, and a bypass needs a deliberate import of the page-thread loop.
   - The files are:
     - `core/blob/digest.ts`: the pure loop, `contentDigest`'s body today, plus the request and answer types. It imports
       `@veyq/crypto` alone, so both threads import it, and the worker never imports the front door, which would pull
       its `new Worker(…)` into the worker chunk (`frameOps.ts:1-5` names the trap).
     - `core/blob/digest.worker.ts`: the worker.
     - `core/blob/digestWorker.ts`: the front door, with the shape of the other front doors: a lazy module-level worker,
       a `workerUnavailable` latch, a `seq`-keyed job map, `warnOnce`. `sharedBlob.ts`'s `contentDigest` calls it.
   - Today, the one non-`Blob` caller is a copy's video banner (§ Where). Its slices already come from the frame worker's
     opens, and only the hash itself stays on the page.
2. **What crosses: the `Blob`, and nothing is transferred.**
   - A `Blob` or `File` crosses by structured clone as a handle to the same bytes, not as a copy *(remembered: the HTML
     serialisation of a `Blob` shares its data)*.
   - The answer is the 43-character string.
   - The worker reads `slice(at, at + 256 KiB).arrayBuffer()` itself, as the page does today.
   - The worker sees the plaintext bytes, as the frame worker and the media worker already do. It is the page's own
     origin and code, at the page's trust tier (ADR 0026). No key crosses.
3. **One request is one whole digest, and nothing outlives it.**
   - The worker's handler is async: it awaits each slice's read. So concurrent digests interleave at slice granularity
     (a banner picked during a queue upload), and a long video blocks nobody's request.
   - Its only state is a map from each request's id to the `AbortController` its `cancel` aborts, and an entry is
     deleted when its request is answered. The frame worker stays as stateless as #536 built it.
4. **Failure, abort and fall-back: #536's shape, minus the contradiction it had to solve.**
   - **No `ready` handshake.** #536 waits for `ready` because a post transfers its bytes, so a worker that fails to
     load would leave nothing to finish (`frameWorker.ts:18-23`). Here nothing is transferred: the page still holds the
     `Blob` after any post.
   - **Infrastructure:** a worker that does not load, `error`/`messageerror`, or a `postMessage` that throws. It
     latches the worker off for the session, finishes every outstanding digest on the page thread from byte 0 through
     the same loop, and warns once. A worker that dies mid-digest loses a hash state the page never held. The page
     starts again from the `Blob` it still has, so the only cost is the bytes already hashed, and the result is the same
     digest.
   - **Source:** a read the browser refuses, for example a `NotReadableError` for a file changed on disk. The worker
     answers the error's `name` and message, and the front door rejects with a `DOMException` of that name. It does not
     retry on the page, because the same file fails the same way there. Only `AbortError` is read by name anywhere
     (`queue.ts:158`).
   - **Abort:** the front door rejects with an `AbortError` at once, posts `cancel`, and drops the late answer by its
     id. The worker stops at its next slice.
5. **Memories' upload starts the digest and the derivation together** (Q2).
   - In `uploadMediaVia`, the digest and `deriveTiers` are started together, and both are awaited before
     `storedOriginal`, `sealMetadata` and the plans.
   - `deriveTiers` cannot reject, so only the digest's abort or read failure ends the upload. The derivation's result
     is then dropped, which wastes at most one derivation.
   - The wire does not change (the digest has no wire presence), and `uploadWire.characterisation.test.ts` stays the
     oracle.
   - The slice order `media.test.ts:1141` pins, digest first and then the pump, holds as well. The stub deriver reads no
     slice.
6. **The export's and the import's streaming hashers stay where they are.**
   - No reading names them, and each is one pass beside a CRC-32 that stays on the page.
   - Each would need per-chunk messages, and a copy of each chunk for the ZIP writer that still writes it.
   - The import's second digest does move: the one `addMedia` reads again (§ Not in this CR).
7. **A build guard, no lint door, as #536 decided.**
   - `verify:digest-worker` (`src/web/scripts/assert-digest-worker-in-dist.mjs`) is the twin of
     `assert-frame-worker-in-dist.mjs`. It checks that exactly one `assets/digest.worker-*.js` exists, carries the
     worker's own sentinel, and is referenced by the app. It is wired into `npm run check` and bitten by splitting the
     constructor.
   - That production code hashes a file only through the front door is decision 1's structure, re-checked once by the
     locator before the commit. Every remaining page-thread hasher is spared by name in § Where.

### Expected effect, for the after-reading to confirm or refute

All figures here are *derived* from the measured stages above, which are one run each, profiled.

- **Decision 5 alone shortens the upload.**
  - At throttle 1 the digest (113 ms) is shorter than the derivation (≈ 160–210 ms), so all of it leaves the critical
    path: up to ≈ 5.6 s of 30.2–31.1 s, ≈ 18 %.
  - At throttle 4 the page-thread digest (394 ms) outlasts the derivation, so ≈ 190–227 ms per file is hidden:
    ≈ 9.5–11 s of 90–96 s, ≈ 10–12 %.
  - This holds on a real device too, because the media worker derives in parallel either way.
- **Decision 1 alone barely shortens it on a real device.**
  - The same code on another core, still run first, takes the same time. In the profiled run it gains what the
    profiler cost the main thread (§ Measured).
  - At throttle 4 the emulation shows ≈ 394 → ≈ 113 ms per file (≈ 14 s). That is the throttle not reaching a worker
    (`how-veyq-is-tested.md:107-109`), not a faster device.
- **What decision 1 buys is the main thread.**
  - −3.7–3.9 s of profiled self time at throttle 1, and −15.7–16.0 s at throttle 4.
  - A page that keeps answering while a large video or a video banner is hashed: a 1 GB file is ≈ 3.5 s of hashing at
    node's rate and ≈ 15 s at the profile's.
  - The prerequisite for #220. At throttle 4 the pump is main-thread-bound at ≈ 1 s per file (`fetch` alone has 2.35 s
    of self time at throttle 1, under the pump's `PUT`). #220 would put the next file's digest beside it.
- **Unchanged:** the wire, the digest's bytes, `@veyq/crypto`, the backend, admin-web, the e2e harness, the export,
  and the import's integrity check.

### Not in this CR

- **An import hashes every original twice.** `archivePayload` verifies the entry's SHA-256 against the manifest
  (`importSubtree.ts:251-266`). Then `addMedia` reads the same bytes again for `blob-meta` (`media.ts:613`, via
  `exportImport.ts:426`). The verified hex digest is that digest. Handing it over would remove one pass per imported
  original. Q4.
- **A copy's video banner** is hashed again (`exportImport.ts:465`), although the source node's sealed meta already
  carries its `sha256` (ADR 0102). Nothing reads its cost.
- **The pump's `fetch`** has 2.35 s of main-thread self time at throttle 1. It is #220's to read, not this CR's.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Contracts | nothing: the digest's bytes are unchanged, `blob-meta` stays as it is | `grep -n "sha256" src/packages/crypto/src/blobMeta.ts` |
| Backend | nothing: it never sees the digest (sealed in `blob-meta`) | `git grep -n -i "sha256" -- src/backend/apps/memories/src/main` |
| `@veyq/crypto` | nothing: `v1.sha256Stream` as it is | `git grep -n "sha256Stream" -- src/packages/crypto/src ':!*.test.ts'` |
| Web: the worker | `digest.ts`, `digest.worker.ts`, `digestWorker.ts`; their dispatcher and front-door tests | the shape: `sed -n 1,60p src/web/src/core/blob/frameWorker.ts` |
| Web: the front door | `contentDigest` delegates (decision 1) | `git grep -n "contentDigest\|findEqual(" -- src/web/src ':!*.test.ts'` |
| Web: the overlap | `uploadMediaVia` (decision 5) | `grep -n "contentDigest\|deriveTiers" src/web/src/features/memories/media.ts` |
| Web: the tests | tests that compute a digest as the oracle, and the slice-order test | `git grep -ln "contentDigest\|findEqual\|deriveTiers\|uploadMediaVia" -- 'src/web/src/**/*.test.ts'` |
| Web: the build | `verify:digest-worker`, wired into `check` | `grep -n '"verify:\|"check"' src/web/package.json` |
| Profile | S2's comment names the digest worker's thread; the after-reading | `grep -n "frames.worker" tests/e2e/profile/s2-upload.profile.ts` |
| Docs | architecture §10's sentence on the largest main-thread cost | `grep -n "largest main-thread cost" docs/architecture/README.md` |
| Site | Security, `how-veyq-is-tested.md`: the hashing sentence becomes the after-reading; the photos' size. No Use page changes: nothing a member sees or does changes | `grep -n "Hashing each original\|3.6 MB" site/src/content/docs/security/how-veyq-is-tested.md` |
| Inbox | #554 deleted at the end; #220 given the after-reading | `grep -n "^| 220 \|^| 554 " docs/inbox.md` |

## Phases

0. **Refine close (the refining conversation, not the implementer).** Write the owner's answers into this CR's
   decisions. No ADR is edited: ADR 0017 decision 1 already decides the primitive, and placement is decided in no ADR,
   as for the frame worker. **Done when:** every `scripts/validate_*.py` exits `0`.

   **Contracts, backend and `@veyq/crypto`: empty, on purpose** (§ Inventory). Not one byte moves, and the backend never
   reads the digest.
1. **The before-reading without the CPU profiler** (only if Q3 is (a)). On `main`, before the first web edit:
   - add a switch that leaves `Profiler.start` out of `profiled` (`tests/e2e/profile/profiling.ts:111-127`), and pass it
     from `profile_hot_paths.py`;
   - read S2 at throttle 1 and 4, three runs each, with the trace on, and record the upload's total.

   The switch's own control: one profiled run beside it, whose total must be the larger. **Done when:** the numbers are
   in this CR, and every `scripts/validate_*.py` exits `0`.
2. **Web.** Before the first edit, record the exit code of `npm run check` in `src/web` on `main`. Then build decisions
   1–5 and 7.
   - **The dispatcher test** (`digest.worker.test.ts`) sends the worker module, under a stubbed `self`, `Blob`s of 0
     bytes, 1 byte, exactly 256 KiB, 256 KiB + 1 and ≈ 1 MiB random. Each answer must equal the page-thread loop's and
     `b64url(v1.sha256Digest(whole))`. **Positive control for a constant digest:** two different inputs of the same
     size answer different digests. A `cancel` mid-digest stops it, and an unknown op answers the sentinel.
   - **The front-door tests**, with a fake `Worker` (`frameWorker.test.ts`'s pattern). Each negative has a positive
     control on the same predicate:
     - a `Blob` is posted, with an empty transfer list, and its answer resolves. **Control:** a non-`Blob` source
       (`framedPayload`) posts nothing and resolves to the same digest on the page;
     - an abort rejects `AbortError` at once, posts `cancel`, and drops the late answer. **Control:** an unaborted
       digest resolves;
     - an `error` event mid-digest finishes every outstanding digest on the page from byte 0, to the right value, and
       warns once. **Control:** a healthy worker warns nothing;
     - a `source` answer rejects with that `DOMException` name and does not retry. **Control:** the same `Blob` on a
       healthy worker resolves.
   - **The overlap** (`media.test.ts`): a deriver whose `deriveImage` resolves only once the digest has started reading.
     Run sequentially, the upload never settles, so the test times out red. **Bite:** await the digest before
     `deriveTiers` again (snapshot and restore, never `git checkout`). Also, an abort during the digest rejects
     `AbortError` and reserves nothing. **Control:** unaborted, it reserves once.
   - The existing tests stay green and are the oracle: the slice-order test, `storedMatch.test.ts`,
     `useBannerEditor.test.ts`, `exportImport.test.ts` and the wire characterisation.
   - **`verify:digest-worker`** is bitten by splitting the constructor.
   - **The locator** of decision 7 is run again before the commit.

   **Done when:** `npm run check` in `src/web` is green, its `verify:*` steps included, and `npm run check` in
   `src/admin-web` is unchanged.

   **Browser pass, on the dev instance as the machine notes say:**
   - upload a photo and a video into a Memory;
   - pick the photo as a cover and the video as a banner: both must be shared rows, nothing uploaded, which proves the
     digest is byte-equal across the two threads;
   - pick another file of the same size: it must be uploaded, which is the constant-digest control;
   - cancel an upload mid-digest;
   - find `digest.worker` in the sources panel's worker list, and check that its warning is absent.

   **The after-reading:** `scripts/profile_hot_paths.py` re-run for S2 at throttle 1 and 4, three runs each. Record:
   - the upload's total;
   - SHA-256's main-thread self time (expected ≈ 0);
   - the digest worker's busy time. Expected ≈ 0.9–1.9 s at throttle 1 *(derived)*. A number near 3.8 s refutes the
     profiler hypothesis;
   - per file, as in § Measured, whether the digest's window lies inside the derivation's span;
   - with Q3 (a), the unprofiled totals beside phase 1's.

   Report a result that contradicts § Expected effect with its numbers. Never fix it in place.
3. **Docs and site.**
   - `how-veyq-is-tested.md` gets the after-reading and the photos' measured size.
   - Architecture §10's sentence is made true.
   - #220 gets the numbers.
   - #554 is deleted.

   **Done when:** `npm run check` in `site/` is green, and every `scripts/validate_*.py` exits `0`.

## Definition of done

- A `Blob`'s digest is read in core's digest worker by the same `@noble` loop. It runs on the page thread only for a
  non-`Blob` source, or while that worker cannot run.
- With Q2 (a), Memories' upload reads the digest beside the derivation.
- The digest's bytes, the wire, `@veyq/crypto` and the backend are unchanged.
- `verify:digest-worker` guards the build.
- The after-reading is published, and #220 carries it.
- No coverage file changes. `satisfies: []` holds because no use case's behaviour or criterion moves. The proof of an
  unchanged result is the dispatcher test against `sha256Digest` of the whole, the existing match tests, and the
  browser pass's shared rows.
- Inbox #554 is deleted.

## Open questions (owner)

1. **Where does the digest run?**
   - (a) **A digest worker of its own in `core/blob`, handed the `Blob`** (decisions 1–4). One request per digest,
     nothing transferred, no state between requests, and a restart from byte 0 is always possible. Its cost is a fifth
     module worker and a sixth `verify:*` twin.
   - (b) **The frame worker, with `digest-begin` / `-update` / `-end`** (the starting point). The page reads
     and transfers every slice.
     - The worker keeps a hash state per id, which the stateless frame worker does not have today, and it needs a
       `digest-abort` so an abandoned begin does not leak.
     - It runs the digest on the one thread that also seals and opens. Today that thread is idle while a digest runs,
       but under #220 the next file's digest would queue between this file's seals. ADR 0017's re-ask trigger is "the
       frame worker bounds an upload", and this placement would bring it closer by itself.
     - A worker dying mid-digest leaves the page neither the state nor the transferred slice, so it must re-read
       everything from byte 0.
   - (c) **The frame worker, handed the `Blob`** (an async op). This keeps (a)'s protocol and needs no new worker, but
     it keeps (b)'s single thread.
   - (d) **The media worker** (`shared/image/mediaDerive.worker.ts`). It is the image pixel worker. Only an image's
     derivation reaches it: never a video original, a banner, a cover's match or an import. It also runs the
     derivation that decision 5 wants the digest beside.
   - (e) **A second instance of the frame worker's chunk.** Two instances would share one chunk and couple two front
     doors' latches and state, to save one verify script. The digest instance would carry AEAD code it never runs.
   - (f) **No worker, decision 5 only.** It takes the digest off the upload's critical path at throttle 1, and it leaves
     the 3.8 s on the main thread and #220's question open.

   **Recommendation: (a).** It is the only option where the digest runs in parallel with the seals, and it is the
   simplest protocol: no transfer, no state, no ready handshake. (f) is the honest cheaper alternative if the
   main thread's share is judged not worth a worker before #220.
2. **Does the digest run beside the derivation?** It is a change to the Memories application's upload, which the inbox
   row did not name.
   - (a) yes, decision 5;
   - (b) no, the digest stays first.

   **Recommendation: (a).** It is the change that shortens the upload on a real device (≈ 18 % at throttle 1,
   *derived*), it is a few lines, and it changes no byte on the wire. Without it, (1a) frees the main thread and
   shortens nothing a phone would notice.
3. **Read the upload once without the CPU profiler, before and after?** The profiled self time of `@noble` on the main
   thread looks 2.3–4.4 times what the same code costs elsewhere (§ Measured). A worker escapes that overhead, so a
   profiled after-reading overstates any move off the main thread.
   - (a) yes: phase 1's switch, and the unprofiled totals before and after;
   - (b) no: keep the profiled reading and state the bias as its limit.

   **Recommendation: (a).** It is the measurement's own bite ("bite the MEASUREMENT before trusting its green",
   `changes/README.md`). It is also the first unprofiled number for an upload.
4. **The import's second pass over each original** (§ Not in this CR): file it as an inbox row, or leave it?

   **Recommendation: file it.** It halves an import's hashing with no new worker. It changes `ImportContentHook`'s
   payload, so it is a small CR of its own, not a rider here.

## Retrospective

_Filled by the implementer._
