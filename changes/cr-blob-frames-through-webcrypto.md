---
id: cr-blob-frames-through-webcrypto
title: A blob's chunk frames are sealed and opened through WebCrypto's AES-256-GCM; every other seal stays on @noble
status: planned
satisfies: []
adrs: ["0017", "0013", "0045", "0072", "0105", "0026"]
invariants:
  - the wire does not move - every frame is byte-identical to what @noble/ciphers seals for the same key, nonce, AAD and plaintext, and every stored frame keeps opening; suite v1 and contracts/crypto/vectors/v1.json stay as they are (one vector is added, none changes)
  - the typed seals (seal, sealTo, sealField, the key wraps, blob-meta) stay on @noble/ciphers and stay synchronous
  - no key and no plaintext reaches anything but the page's own code and the browser that already runs it (ADR 0026); nothing new is sent to the server
  - a missing or disagreeing WebCrypto degrades to @noble on the same thread, never to a refusal and never to a different byte
---

## Goal

Inbox #536. The profile's crypto reading measured AES-256-GCM through `@noble/ciphers` at about 60 MiB/s, and through
the browser's own WebCrypto at about 1,080 MiB/s over a whole buffer and about 2,200 MiB/s over veyq's 256 KiB chunk
frames, in Chromium. Both gave the v1 vectors' bytes and each other's. Every blob's content is sealed and opened as those
frames, today through `@noble` on the thread that calls. This CR moves exactly that: **a blob's chunk frames, sealed on
upload and opened on every read**, go through `crypto.subtle` AES-GCM. Every other seal stays on `@noble`. The bytes on
the wire do not change, so no stored blob is touched and suite v1 stays v1. ADR 0017 gains the rule that decides which
implementation a call uses, and how the second one is proven.

**This CR is not ready to implement until the owner has answered § Open questions and phase 0 has edited ADR 0017**
(`docs/ai/README.md` § Refined state comes before the CR). The decisions below are written on the recommended answers;
an answer that differs is edited into every decision and phase it changes before phase 1 starts (`changes/README.md`
§ An owner's answer that reverses a decision).

## Context & constraints

Measured at refine in worktree `agent-a2c1c03ff3b1f7d92` at `e7000dafd` (= `main`), nothing changed yet. Every pointer
below was read in this refine; claims I hold from memory and did not measure are marked *(remembered)*.

### What is bulk: the chunk frames, and nothing else

- **The AEAD has six callers, and two of them are bulk.** `git grep -n "aeadSeal\|aeadOpen" -- src/packages/crypto/src`:
  `symWrap`/`symUnwrap`, `fieldWrap`/`fieldUnwrap`, `asymWrap`/`asymUnwrap` (`primitives.ts:69-132`) and
  `sealFrame`/`openFrame` (`v1.ts:189-195`). The first six are the typed seals of `seal.ts`, and each seals a key, a
  field or a small document. Only the frame takes a blob's content, and only the frame's input has no upper bound but
  the blob's (ADR 0013 § 3). Second angle, keyed on the library rather than on the wrapper:
  `git grep -n "@noble/ciphers\|gcm(" -- src tests ':!*.test.ts' ':!**/package*.json'` finds `primitives.ts` alone,
  outside the profile reading. The two agree.
- **So the rule is structural, not a size threshold:** a chunk frame goes through WebCrypto, a typed seal through
  `@noble`. A threshold would have to be policed at 107 typed-seal calls in 43 files
  (`git grep -c -E "v1\.(seal|open|sealField|openField|sealTo|openAs|sealBlobMeta|openBlobMeta)\(" -- src/web/src src/admin-web/src tests/e2e ':!*.test.ts'`).
  Each of those calls is synchronous, and WebCrypto is promise-only. Moving them would make the whole typed seal API
  async for inputs of bytes to a few KiB, where 60 MiB/s already takes microseconds.
- **The production seams are three, all in `src/web/src/core/blob/`:**
  - the upload pump's `sealFrameAt` (`transport.ts:51-57`). Every upload goes through it: Memories' originals and
    tiers (`media.ts:651-658`), covers (`coverWrite.ts:115`), banners (`bannerWrite.ts:124`), Depot files member and
    guest (`uploadFile.ts:172`, `shareUpload.ts:88`), Expenses receipts (`receipts.ts:143`);
  - `openFrames` (`keys.ts:46-48`), the whole-blob open. It is called by `openBlobWithKey` (`transport.ts:250-253`):
    a lightbox's original, a download, a cover, a moderator's review. It also runs inside Memories' listing worker
    for thumbnails (`listingOpenerCore.ts:74`, ADR 0105 decision 3) and as that worker's page-thread fallback
    (`listingOpener.ts:157`);
  - the chunk provider's `getPlaintextChunk` (`transport.ts:368-372`), which serves range streaming (ADR 0047), Depot's
    streamed download, receipts and attachments.

  Locator: `git grep -n "sealFrame\|openFrame\|openFrames(" -- src/web/src ':!*.test.ts'`. A re-key re-wraps
  `K_blob` and never re-seals a frame (`rekeyScope.ts:248-258`), and a shared blob re-wraps the same `K_blob`
  (`sharedBlob.ts:50-61`), so neither is a seam.
- **admin-web and the e2e harness seal no frame.** `git grep -n "sealFrame\|openFrame\|CHUNK_FRAME" -- src/admin-web/src tests/e2e/src`
  is empty. They keep importing `@veyq/crypto/v1` unchanged, and nothing here reaches them.

### Why the bytes cannot move, and do not have to

- **The frame is one AEAD per 256 KiB, independent of every other** (`v1.ts:181-195`): `frame = nonce(12) ‖
  AES-256-GCM(K_blob, nonce, plaintext, aad)` with `aad = u32be(index) ‖ u32be(total)`. The nonce is 12 fresh random
  bytes per frame, drawn by the caller (`transport.ts:56`, `v1.randomBytes(12)`). WebCrypto's
  `encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, key, plaintext)` returns
  `ciphertext ‖ tag(16)`, which is what `gcm(key, nonce, aad).encrypt(plaintext)` returns. So the frame is the same
  bytes, and the measured reading proved it on the vectors and over 4 MiB and 64 MiB in both layouts. Its
  `conformance` block is all `true` in all six runs (the readings directory of 2026-10-23, out of git, files
  `crypto-t{1,4}-r{1,2,3}.crypto.json`).
- **There is no last-chunk flag to map: `total` is in every frame's AAD.** A dropped, reordered or appended frame
  fails to open, because the opener passes the whole blob's `chunkCount` (`transport.ts:369-371` carries that trap as
  a comment). Every frame but the last carries exactly `plaintextBytes`. The last carries `1..plaintextBytes`, and an
  empty blob is one frame of nothing. The backend derives `chunkCount` from `byteSize` on exactly this (`ChunkFrames.kt`,
  pinned by `ChunkFramesTest`). Byte-identical frames leave all of that as it is.
- **The framing is what makes WebCrypto usable at all.** WebCrypto has no incremental AEAD: a blob sealed as one GCM
  stream could only be sealed by it whole, in memory. Each frame is one call.
- **The vectors pin the frame twice today:** `chunk_frame` (8 bytes of plaintext, index 2 of 4) and
  `primitives.aes256gcm`. No vector holds the empty frame, the one input where an AEAD binding has its own edge (no
  plaintext, tag only). `v1.conformance.test.ts:1363-1373` seals one only for its length.

### Key handling

- **`K_blob` exists as raw bytes and keeps existing as raw bytes.** `planBlob` draws it (`transport.ts:41`), and the
  typed `blob-key` seal wraps it under the owner's key (`:43`). `blob-meta` seals under it (`:46`). A report hands it
  over raw (`keys.ts:21-25`, uc.moderation.report), and a re-key unwraps it raw. A `CryptoKey` is therefore a
  **handle for speed, not a boundary.** It is imported from those bytes with `importKey('raw', K_blob, 'AES-GCM',
  false, ['encrypt', 'decrypt'])`, once per blob per seam. It is made non-extractable only so that no later code can
  export it by mistake. This is ADR 0005's "non-extractable ≈ HSM interface, not grade", minus even the interface,
  because the raw bytes sit in the same heap.
- **Zeroisation does not change.** The suite zeroises where it can (`vault.ts:49-51`, `rekeyScope.ts:93-256`,
  `listingOpenerCore.ts:65`). A `CryptoKey` cannot be zeroised: the engine's expanded key lives until the key object is
  collected. Priced against the attacker it would stop (`docs/ai/README.md` § Price a confidentiality layer): one who
  reads the page's memory reads the raw `K_blob` in the plan, and the `K_node` it is wrapped under. The CR makes no new
  promise and removes none.

### Where the time goes, measured, and what that says about a worker

- **The wall-clock reading bounds the main thread's share.** Framed WebCrypto took 1.8 ms for 4 MiB and 29–32 ms for
  64 MiB on the page thread at throttle 1. At throttle 4 it took 8 ms and 133–137 ms. Even if every microsecond of that
  were the main thread's, a 50-photo upload's ≈ 200 MB of frames *(derived: 50 × 3.6 MB of originals plus tiers)*
  costs at most ≈ 90 ms at throttle 1 and ≈ 400 ms at throttle 4. Today's `@noble` costs ≈ 3.3 s and ≈ 14 s. Those
  costs are spread over 256 KiB frames with an `await` between each, so no frame is a long task.
- **"WebCrypto is async, so it is off the main thread" does not hold in Chromium.** Chromium's throttle slows the main
  thread and never a worker: the Argon2id worker ran in 5,354 ms at throttle 1 and 5,413 ms at 4
  (`site/src/content/docs/security/how-veyq-is-tested.md:101-103`). WebCrypto slowed about 4–4.7× under the same
  throttle (`@noble` 4.2×), so in Chromium its work runs on the main thread, or on one throttled with it. That is no argument for a
  worker, because the bound above already holds on the main thread. Firefox and WebKit are unmeasured.
- **The win is a fraction of an upload, not 18×.** The upload took about 41 s at throttle 1, the wire 9.5 % of it
  (inbox #220), and GHASH alone 1.9 s of main-thread self time. The AEAD as a whole is about 8 % at throttle 1 and
  about 12 % at throttle 4 *(derived from the measured rates; the S2 profile's after-reading measures it)*.
- **The read side gains the most visible time.** `openFrames` opens a whole blob in one synchronous call, so a 20 MB
  original in the lightbox is one long task of ≈ 330 ms, and a 64 MB video download ≈ 1 s *(derived from the measured
  open rate, 995–1,055 ms per 64 MiB)*. Through WebCrypto with an `await` per frame, neither is a long task. The row
  names sealing; the reading measured the open beside it at the same rate.
- **Another bulk primitive is on the main thread, and WebCrypto cannot take it.** `contentDigest`
  (`sharedBlob.ts:27-34`) runs `@noble`'s SHA-256 over every Memories original before its upload (`media.ts:613`),
  over a Depot file's shared-blob check (`sharedBlob.ts:76`), over a banner (`useBannerEditor.ts:53`), and over an
  export and an import (`exportSubtree.ts:207`, `importSubtree.ts:258`). `crypto.subtle.digest` has no incremental
  form. This is Q4, outside this CR's scope.

### Fallbacks, and what already requires WebCrypto

- **Every context that uploads already requires a secure context.** `planBlob` calls `crypto.randomUUID()`
  (`transport.ts:42`), which is `[SecureContext]` like `crypto.subtle` *(remembered)*. The media Service Worker needs
  one too. So no browser that can upload today loses the upload to WebCrypto's availability.
- **The web already seals with WebCrypto AES-GCM:** the remember-me device-key wrap and the session vault
  (`deviceKeyWrap.ts:61-63`, `sessionVault.ts:54-57`), each behind an availability check (`deviceKeyWrap.ts:28`).
- **Node has WebCrypto.** The package and web suites run under happy-dom on Node 22 (`package.json` `engines`). The
  package's `vitest.config.ts` says happy-dom supplies `crypto`, and `boot.test.ts:66` generates an AES-GCM key there.
  So the unit suites exercise the WebCrypto path against Node's implementation. A browser engine's implementation is
  exercised only in a browser.
- **No WebAssembly.** The app ships none and its `script-src 'self'` stays strictly same-origin (`cc.security.csp.md:62-64`),
  so a WASM AES is not an alternative. WebCrypto needs no CSP source at all.

### The precedents this reuses

- The three worker front doors share one shape: a lazily created worker, a `workerUnavailable` latch, a per-request
  fallback closure and a `warnOnce`. They are `argonWorker.ts` (in this package), `mediaDeriveWorker.ts` and
  `listingOpener.ts`. A degrade-once latch for "WebCrypto disagrees" takes the same shape without a worker.
- **The web's wire tests already open what production sealed with an independent implementation.**
  `depot/uploadFile.wire.test.ts:175`, `depot/copy.wire.test.ts:183` and `memories/copy.wire.test.ts:282` open
  captured PUT bodies with `v1.openFrame`. Once production seals through WebCrypto, each of those is a cross-implementation
  check for free. That is why `@noble`'s `sealFrame`/`openFrame` stay exported (decision 2).

## Decisions this CR takes

1. **The chunk frames, and only they, go through WebCrypto** (Q2: the open side too). A new module
   `src/packages/crypto/src/frames.ts` (export `./frames`) holds:
   - `frameKey(blobKey): Promise<FrameKey>`, which imports `K_blob` once, non-extractable, for `encrypt` and `decrypt`;
   - `sealFrameWith(key, index, total, plaintext, nonce): Promise<Uint8Array>` and
     `openFrameWith(key, index, total, frame): Promise<Uint8Array>`. They build the same `frameAad` as `v1.ts:188` and
     emit or read `nonce ‖ ciphertext ‖ tag`, which is the same layout and the same bytes.

   A `FrameKey` carries the raw bytes beside the `CryptoKey`, so the fallback (decision 3) seals with the same key.
2. **`@noble`'s `sealFrame`/`openFrame` stay in `v1.ts` and stay exported.** They are the reference, the fallback and
   the tests' oracle. Production code reaches frames only through `./frames`. That is proved once by the phase 3
   locator, not by a permanent lint door (Q5): a bypass costs time and no confidentiality, since the bytes are
   identical, and the profile reads time.
3. **A differential check before first use; a disagreement or a missing `crypto.subtle` falls back to `@noble`**
   (Q3). On the first frame operation in a realm (the page, the listing worker), `frames.ts` seals two fixed inputs,
   an empty one and a 33-byte one, at a fixed key, nonce and `(index, total)`, through WebCrypto and through `@noble`.
   It compares the bytes and opens each through the other. Equal means WebCrypto for that realm. Different, absent, or
   any rejection means `@noble` for that realm, and one console line with no secret, the shape `argonWorker.ts:27-31`
   uses. The check is memoised as one promise per realm. Its reference is the conformance-pinned `@noble`, so no
   vector is copied into the bundle. It guards the realistic failure, which is a binding's wrapper (the AAD, the tag
   length, empty input), not AES itself. That failure would otherwise write frames only that engine opens.
4. **An open that WebCrypto refuses is final.** A tag mismatch rejects. It is not retried on `@noble`, which would give
   the same verdict at 35 times the cost. Callers keep seeing a rejection where they saw a throw: no caller reads the
   error's text (`git grep -n -i "ghash\|invalid tag\|OperationError" -- src/web/src` is empty), and the listing
   worker maps any failure to `source` (`listingOpenerCore.ts:73-77`).
5. **No new worker** (Q1). The seal stays where the pump runs, and the open where its caller runs, because decision
   1's cost is bounded by § Where the time goes. The listing worker, which already holds the keys (ADR 0105 decision
   3), gets the same `frames.ts` inside itself, so no key crosses anew. A dedicated worker does become worth a CR of
   its own if phase 3's after-reading shows more than ≈ 1 s of main-thread time for the 50-photo upload at throttle 4
   in any engine. The transport is fixed here so that CR need not rediscover it:
   - the `File`/`Blob` crosses by structured clone, which copies no bytes;
   - the `CryptoKey` crosses by structured clone, and non-extractable survives it;
   - each sealed frame's `ArrayBuffer` comes back in the transfer list.

   The plaintext and the raw `K_blob` never cross.
6. **One new vector, `chunk_frame_empty`:** an empty blob's only frame, index 0 of total 1, under `chunk_frame`'s key
   and nonce. It is generated by an independent implementation, Python's `cryptography` `AESGCM`, as `file_blob` was.
   That makes it the third implementation, not one of the two under test. Nothing else in `v1.json` changes. This
   follows ADR 0017 rule 4, "a vector first": the vector pins the one edge the second implementation newly meets. It is
   not a tamper vector, which ADR 0045 rules out.
7. **What the conformance proves after this CR.** For the frame, `v1.conformance.test.ts` holds `@noble` and
   `frames.ts` (Node's WebCrypto under vitest) to `chunk_frame` and `chunk_frame_empty`. A differential test seals the
   same inputs through both and opens each through the other. Its lengths are 0, 1, 15, 16, 17, 4,095, 262,143 and
   262,144, at several `(index, total)`. In the browser, decision 3's check runs on every engine at first use. The
   profile's crypto reading runs in Chromium, Firefox and WebKit (phase 3), and the owner's real-device pass covers
   Safari on iOS. So for the frame, the vectors become a cross-implementation anchor, the role X-Wing's already has.

### Draft: the edit to ADR 0017 (applied in place in phase 0, no addendum — `docs/decisions/README.md`)

**Decision 1**, after "AES-256-GCM-class AEAD (already quantum-resistant at 256-bit).", insert:

> The suite has two implementations of AES-256-GCM, and the shape of the call decides between them, never the bytes. A
> blob's chunk frames are the one AEAD whose input is a blob's content, unbounded but for the blob. They go through the
> platform's WebCrypto (`crypto.subtle`, 96-bit nonce, 128-bit tag; `src/packages/crypto/src/frames.ts`). Every other
> AEAD goes through `@noble/ciphers` and stays synchronous: the typed seals, the field seals, the key wraps and a
> blob's sealed metadata. Both give the same bytes for the same key, nonce, AAD and plaintext, so the choice is not
> part of the suite. A frame sealed by either opens with either, and no stored frame was re-sealed. Before its first
> frame, a client compares the two implementations on fixed inputs. Where WebCrypto is absent or disagrees, it seals
> and opens frames through `@noble` (`frames.ts`).

**Decision 4**, after "the hybrid KEM is held to X-Wing's published vectors, which other implementations produced.",
insert:

> The chunk frame is a second such anchor. `@noble/ciphers` and WebCrypto are each held to `chunk_frame` and
> `chunk_frame_empty`, the latter generated by an independent Python implementation, and to each other's output: in
> the package's test against Node's WebCrypto, and in every browser at first use.

**Consequences**, in the bullet "The vectors are what makes rule 3 enforceable", its last clause becomes "so for
everything else that cross-stack-parity role is dormant until a second independent implementation exists — except for
the chunk frame, which has one in the same stack (decision 4)". Then a new bullet after it:

> - **Frames through WebCrypto, everything else through `@noble`, for measured cost.** In Chromium (2026-10-23)
>   `@noble` sealed and opened at about 60 MiB/s, and WebCrypto at about 2,200 MiB/s over 256 KiB frames. The frames
>   are the only AEAD a blob's size reaches, so they are where the time goes. The typed seals are small and called
>   synchronously throughout the client, and WebCrypto is promise-only. Rejected:
>   - *every AEAD through WebCrypto*: it makes the whole seal API async for inputs that take microseconds;
>   - *a worker for the seal*: the measured wall-clock bounds its main-thread cost below the noise of an upload, even
>     where the engine runs it on the main thread;
>   - *a WebAssembly AES*: the CSP keeps WebAssembly out;
>   - *a suite v2*: no byte changes, so there is nothing to version.
>
>   The GCM tag check that refuses a tampered frame is now the engine's rather than the library's. ADR 0045's reason
>   for no tamper vectors holds unchanged: it is a dependency's check either way. The browser is not a new party. It
>   already runs every key the client holds (ADR 0026).

**Related**: add ADR 0013 (`0013-blob-storage-presigned-direct-to-store.md`, the frame's shape).

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Vectors | `chunk_frame_empty` added, beside `chunk_frame`, with a note naming its generator | `python3 -c "import json;print(json.load(open('contracts/crypto/vectors/v1.json'))['chunk_frame'])"` |
| Crypto package | `src/frames.ts`, the `./frames` export, the conformance and differential tests, the Stryker scope | `grep -n '"exports"' -A10 src/packages/crypto/package.json`; `grep -n '"mutate"' -A10 src/packages/crypto/stryker.mutate.json`; `grep -n "chunk_frame" src/packages/crypto/src/v1.conformance.test.ts` |
| Web seams | the three seams; `openFrames` becomes async, with its callers | `git grep -n "sealFrame\|openFrame\|openFrames(\|openFramesFor(" -- src/web/src ':!*.test.ts'` |
| Web tests | a test that calls `openFrames` synchronously, or awaits nothing from it | `git grep -n "openFrames(" -- 'src/web/src/**/*.test.ts'` |
| Listing worker | the `open-frames` branch awaits; a `close-memory` landing mid-open zeroises only the worker's map copies, never the request's `blobKey` (`listingOpenerCore.ts:63-67`) | `grep -n "open-frames\|close-memory" src/web/src/features/memories/listingOpenerCore.ts` |
| Bench | `media.bench.ts` measures both implementations | `grep -n "sealFrame\|openFrame" src/web/src/features/memories/media.bench.ts` |
| Profile | the crypto reading in three engines; S2 re-read | `grep -n "devices\[" tests/e2e/profile/playwright.config.ts`; `grep -n "Re-run it" -A12 site/src/content/docs/security/how-veyq-is-tested.md` |
| Backend | nothing (below) | `git grep -n "chunk_frame" -- src/backend` |
| Docs | ADR 0017 (the draft), ADR 0045's "the rejection itself is `@noble`'s", security model § the suite, architecture §10's "largest avoidable cost" sentence | `grep -n "rejection itself" docs/decisions/0045-*.md`; `grep -n "Content itself is" docs/crosscutting/security-model.md`; `grep -n "largest avoidable cost" docs/architecture/README.md` |
| Site | Security: `cryptography.md` (the stance's `@noble` line, the AES-256-GCM row), `verify-it-yourself.md` (the conformance table and "What this proves"), `how-veyq-is-tested.md` (the profiling reading). No Use page: nothing a member sees or does changes | `git grep -n -i "noble\|single-implementation\|authentication step" -- site/src/content/docs/security` |
| Inbox | #536 deleted at the end; #220 given the after-reading | `grep -n "^| 220 \|^| 536 " docs/inbox.md` |

## Phases

0. **Refine close (the refining conversation, not the implementer).** Once the owner has answered:
   - Edit ADR 0017 in place with the draft above, adjusted to the answers.
   - Edit the security model's suite paragraph with one sentence on the two implementations.
   - Write the answers into this CR's decisions.

   **Done when:** every `scripts/validate_*.py` exits `0`.
1. **Contracts.** Before the first edit, record on `main`: the exit codes of `npm run check` in `src/packages/crypto`
   and `src/web`, `npm run mutation` in `src/packages/crypto`, and the guard baseline. None of them was run at refine,
   because this worktree has no `node_modules`.
   - Generate `chunk_frame_empty` with Python's `cryptography` `AESGCM`, using a script in the scratchpad, not the repo.
     First reproduce `chunk_frame` byte for byte with it, then write the new group and its note.
   - Add its case to `v1.conformance.test.ts` against `@noble`'s `sealFrame`/`openFrame`. It must be green at once. A
     red means the empty frame was wrong all along: stop, and hand it back.

   **Done when:** the conformance test is green, and every `scripts/validate_*.py` exits `0`.

   **Backend: empty, on purpose.** The backend performs no content crypto (`src/backend/CLAUDE.md`). It reads
   `chunk_frame_size` only (`ChunkFrames.kt`, `ChunkFramesTest`), which does not change, and identical bytes leave
   `chunkCount`, `byteSize` and the range arithmetic as they are.
2. **`@veyq/crypto`.** Decisions 1–3 and 7: `frames.ts`, its export, the conformance cases of both implementations,
   the differential test, the realm check and its fallback, and `src/frames.ts` in `stryker.mutate.json`. It is
   crypto, not placement: a mutated AAD or layout in it is a wrong byte.
   - Each fallback trigger has a test with a positive control on the same predicate: `crypto.subtle` absent, a stubbed
     `encrypt` that returns other bytes, and one that rejects. Each must end on `@noble` with one warning. **Control:**
     the real `subtle` ends on WebCrypto with none.
   - A frame sealed through the fallback opens through WebCrypto, and the reverse.

   **Done when:** `npm run check` in `src/packages/crypto` is green, and `npm run mutation` leaves no survivor in
   `frames.ts` against the baseline from phase 1.
3. **Web.** Decisions 4 and 5. The pump imports one `FrameKey` per plan, before its workers start, and `sealFrameAt`
   seals through it. `openFrames` becomes async through `openFrameWith`, frame by frame. The chunk provider imports one
   `FrameKey` with its `K_blob` unwrap. The listing worker uses the same module. Before the commit, the seam locator
   run again lists no production call of `v1.sealFrame`/`v1.openFrame` outside `src/packages/crypto` (decision 2's
   one-shot proof). Every hit left is a test or the bench, spared by name.
   - **Browser pass on the dev instance**, against the main checkout's dev stack, as the machine notes say:
     - upload a photo (three tiers), a Depot file larger than 512 KiB (three frames, a short last one) and an empty
       Depot file;
     - open each one: the lightbox, the download, a video's range stream;
     - open a photo and a file **uploaded before this phase**: frames `@noble` sealed and WebCrypto opens. This is the
       check no unit test can make, on stored bytes;
     - read the console for decision 3's warning. Its absence is the expected state; force it once by stubbing
       `crypto.subtle` in the console, and see an upload still work.
   - **The after-reading:**
     - `scripts/profile_hot_paths.py` re-run for S2 and the crypto reading, throttle 1 and 4, three runs each;
     - the crypto reading also run in Firefox and WebKit, for conformance and throughput. That needs projects in
       `tests/e2e/profile/playwright.config.ts`, and its `profiled` CPU window is Chromium's alone. *(Machine hint, the
       planned form: the pinned Playwright image is taken to carry all three engines — remembered; check it first.)*
     - From S2's CPU profile, record the frame seal's main-thread self time and SHA-256's (Q4).

   **Done when:** `npm run check` in `src/web` is green (its `verify:*` steps included), `npm run check` in
   `src/admin-web` is unchanged, the browser pass is done with the console read, and the after-reading is recorded. A
   main-thread seal above decision 5's threshold is reported back with the numbers, never fixed in place.
4. **Docs and site.** The Inventory's docs and site rows, in English. These are Security pages, which have no German
   copy.
   - `how-veyq-is-tested.md` gets the after-reading, and says the three engines it ran in.
   - #220 gets the after-reading and its re-judged ceiling (§ What it changes for #220).
   - #536 is deleted.

   **Done when:** `npm run check` in `site/` is green, and every `scripts/validate_*.py` exits `0`.

## What it changes for #220

#220 asks whether to prepare the next file during an upload, and its ceiling is the wire's share (9.5 % at throttle 1,
3.5 % at 4). Today the frame seal runs inside the pump. This CR takes ≈ 3.2 s (throttle 1) and ≈ 13.5 s (throttle 4)
of `@noble` out of the upload. The derived totals fall to about 38 s and 98 s, with the wire the same, so the wire's
share rises to about 10 % and 4 % *(derived; phase 3 measures it)*. **The ceiling hardly moves, so this CR does not make
#220 worth doing.** #220 stays open until phase 3's S2 reading replaces these estimates. The larger on-device share the
overlap would hide is the derivation and the SHA-256 pass (Q4), and phase 3's profile names them.

## Definition of done

- Every chunk frame a client seals or opens goes through WebCrypto where it exists and agrees with `@noble`, and
  through `@noble` otherwise. Every other seal is unchanged.
- The wire is byte-identical. A blob uploaded before the CR opens after it, in the browser.
- Both implementations are held to `chunk_frame`, `chunk_frame_empty` and each other. `frames.ts` is in the mutation
  scope with no survivor.
- ADR 0017, the security model, ADR 0045 and the Security pages say so. The after-reading is published, and #220
  carries it.
- No coverage file changes. `satisfies: []` holds because no use case's behaviour or criterion moves. The frame's
  proof is the package's conformance test, which is no use-case criterion (ADR 0040). The structural invariant
  ("production frames only through `./frames`") is proved once in phase 3, without a guard (decision 2, Q5).
- Inbox #536 is deleted.

## Open questions (owner)

1. **A worker for the seal, as the row proposes?**
   - (a) no worker: WebCrypto on the thread that calls today. The listing worker uses it too. A worker becomes a CR of
     its own if the after-reading crosses decision 5's threshold;
   - (b) a seal worker: the pump hands it the `File` and the `CryptoKey`, and gets the sealed frames back, transferred;
   - (c) the whole pump in a worker: read, seal and `PUT`, with presigning and the session refresh moved with it.

   **Recommendation: (a).** The reading bounds (a)'s main-thread cost at ≈ 90 ms of a 41 s upload, ≈ 400 ms at
   throttle 4, in frames that are no long tasks, and that bound holds even though Chromium runs WebCrypto on (or
   throttled with) the main thread. (b) adds a fifth worker front door with its latch, its fallback and its
   `verify:*` build assertion to save that bound at most. (c) moves the auth refresh and the progress plumbing for a
   wire that is 9.5 % of the upload. Firefox and WebKit are unmeasured. Phase 3's three-engine reading is where (a)
   is checked.
2. **The open side too, or sealing only as the row says?**
   - (a) both: the pump's seal, the whole-blob open, the chunk provider and the listing worker;
   - (b) the seal only.

   **Recommendation: (a).** The reading measured the open at the same 60 MiB/s. Today's whole-blob open is one
   synchronous call: ≈ 330 ms for a 20 MB original in the lightbox, ≈ 1 s for a 64 MB download, a long task each. The
   seal never was one, since it runs a frame at a time. Doing one side only would leave two implementations live on
   the same seams for no reason.
3. **How is WebCrypto proven in the browser, and what happens where it fails?**
   - (a) decision 3: compare it with `@noble` on fixed inputs at first use per realm. A disagreement, an absence or a
     rejection means `@noble` for that realm, with one console line;
   - (b) require WebCrypto, with no fallback: refuse to seal where it is absent or disagrees;
   - (c) no check in the browser: trust the engine, and fall back only where `crypto.subtle` is absent.

   **Recommendation: (a).** A wrong frame is permanent: a frame only one engine opens is lost on every other device.
   The check costs two tiny seals once per page. (b) turns a browser bug into a broken upload, which is worse for a
   member than a slow one. (c) leaves Safari and Firefox proven by nothing in CI, since lane L1 is Chromium only
   (`tests/e2e/playwright.config.ts:61`).
4. **SHA-256 over every original stays `@noble` on the main thread. File it?** `contentDigest` hashes every Memories
   original before upload, and every export and import. WebCrypto's `digest` has no streaming form, so this CR cannot
   take it.
   - (a) file an inbox row now, its number from phase 3's S2 profile, decided then: a whole-buffer `digest` below a size
     bound, or the stream in a worker;
   - (b) fold it into this CR;
   - (c) leave it unfiled.

   **Recommendation: (a).** It is a different primitive with a different fix, and its cost is unmeasured. Phase 3 is
   where it gets a number, as #536 got its own.
5. **A permanent guard that production frames go through `./frames`?**
   - (a) no: a one-shot locator in phase 3. A bypass is slower, never weaker, because the bytes are identical, and the
     profile reads time;
   - (b) an ESLint door like `veyq/argon-through-the-worker`, refusing `v1.sealFrame`/`v1.openFrame` in production
     files.

   **Recommendation: (a)**, by `docs/ai/README.md` rule 3. The Argon door earned its keep with four bypasses found by
   reading, each one freezing the page for 0.8 s. A frame bypass today is three call sites in one directory, and its
   cost is one the profile already measures. Worth re-asking if a bypass is ever found.

## Retrospective

_Filled by the implementer._
