---
id: cr-import-hands-its-digest-over
title: An import's payload check hands the SHA-256 it computed to the upload, so an original is hashed once
status: planned
satisfies: []
adrs: ["0082", "0013", "0079", "0017"]
invariants:
  - the digest an upload seals into blob-meta is the SHA-256 core computed over the very bytes it hands out, never a value read from the manifest
  - a payload whose bytes fail the manifest's sha256 or the ZIP entry's CRC still stops the import before any hook receives it (ADR 0082 decision 10)
  - the digest's bytes do not change - 43 base64url characters of the plaintext's SHA-256, as blob-meta carries it (ADR 0013 § 5); nothing on the wire changes
  - core reaches no application and no application's code changes for this (ADR 0079); SHA-256 stays @noble's through v1.sha256Stream (ADR 0017 decision 1)
  - a contentDigest whose signal has already aborted rejects with an AbortError, remembered digest or not
---

## Goal

Inbox #555. An import of a download reads every payload twice for its SHA-256:
1. core's payload check (`archivePayload`, `src/web/src/core/export/importSubtree.ts:251-270`) hashes it while it streams,
   to compare with the manifest;
2. Memories' upload hashes the same bytes again for the original's `blob-meta` (`uploadMediaVia`,
   `src/web/src/features/memories/media.ts:618-621`, reached through `addOriginal`, `exportImport.ts:425-426`).

A video banner is hashed a second time too (`restoreBanner`, `exportImport.ts:465`), which the row does not name.

The digest core computed is the digest the upload needs. This CR has core remember it for the `File` it hands out, so
`contentDigest` answers that `File` without reading it. One pass per imported original and per imported video banner
goes away. No hook type changes, and no application code changes.

## Context & constraints

Measured at refine on `worktree-agent-a9b9cb8e9c002dc73`, fast-forwarded to `main` at `6afdd95b2` (the #554 merge),
nothing changed. Every pointer was read in this refine. Claims held from memory are marked *(remembered)*, and figures
computed from measured ones are marked *(derived)*. Nothing was profiled, and `npm run check` was not run.

### The same bytes? Yes, for every source and every media kind

- **Core hashes the bytes it hands out.** `archivePayload` streams `source.stream(ref.path)` into `v1.sha256Stream`
  (`importSubtree.ts:258-263`). It then returns `new File([source.blob(ref.path, ref.mediaType)], …)` (`:268`).
  - **ZIP:** `stream` reads `file.slice(dataAt…dataAt + size)` in 1 MiB parts and checks the CRC-32 after the last one
    (`zip.ts:497-507`, `readAt` at `:317-320`). `blob` is `file.slice(dataAt, dataAt + size)` (`zip.ts:513-517`). Same
    range, same `File`. Only stored entries are read (`zip.ts:387`, `:422`, `:438`), so nothing is inflated.
  - **Directory** (an unpacked download): `stream` reads `f.slice(at, at + 1 MiB)` (`importSource.ts:65-68`), and `blob`
    is `f.slice(0, undefined, type)` (`:69`). Same `File`.
- **The upload uploads that object as it is.** `addOriginal` passes `wholePayload(file)`, which is the same object cast
  (`exportImport.ts:346-349`), as `AddMediaSource.blob`. `uploadMediaVia` spreads the source (`typed = { ...source, … }`,
  `media.ts:611`), so `typed.blob` is still that object. It hashes `typed.blob` (`:619`) and uploads `typed.blob` as the
  original (`:629`). Nothing re-encodes or strips the original, for an image, a video or any other type. Only the
  derived tiers are new bytes, and they carry no `sha256` (`DERIVED_META`, `:551`).
- **The video banner** gets the payload `File` straight from `ctx.payload` and hashes that object (`exportImport.ts:460`,
  `:465`). An image banner's file is the derived JPEG, which is hashed nowhere on import.
- **A cover** (`restoreCover`, `:452`) is re-encoded by `uploadCoverImage` and hashed nowhere
  (`git grep -n "sha256" -- src/web/src/core/node/cover ':!*.test.ts'` is empty).
- **A non-`Blob` source never reaches these sites.** A copy's payloads are `framedPayload` (`copySubtree.ts:119-123`).
  - A copy's originals go through `addCopiedMedia` (`exportImport.ts:381`), which hashes nothing: it carries the source
    blob's `blob-meta` `sha256` verbatim (`:233`).
  - A copy's video banner is hashed on the page from `framedPayload` (`sharedBlob.ts:36`). That is out of this CR's
    scope (§ Not in this CR).

So the hand-over is valid wherever `archivePayload` produced the `File`: every download payload, ZIP or directory.

### Encoding, and what `archivePayload` keeps

- **The manifest:** lowercase hex, 64 characters. The pattern is `^[0-9a-f]{64}$` in the contract
  (`contracts/export/veyq-subtree.schema.json`, `$defs/payload`) and `SHA` in `manifest.ts:112`. The export writes it
  with the same `hex` (`exportSubtree.ts:207-218`).
- **`blob-meta`:** base64url without padding, 43 characters (`digest.ts:25`, `b64url(hasher.digest())`).
- **The conversion:** none is needed if core hands over `b64url` of the 32 bytes its own hasher produced. The manifest's
  hex is never decoded.
- **What it keeps today: nothing.** `hex(hasher.digest())` is compared and dropped (`importSubtree.ts:267`).
  - Because of the `ref.sha256 &&` short-circuit, `digest()` is not even called when the manifest has no `sha256`, though
    the hasher has read every byte.
  - `sha256` is **optional** in the contract (required are `path`, `size`, `mediaType`) and in the parser
    (`manifest.ts:151`). This build's export always writes it.

### Trust

- **A manifest field must never reach `blob-meta`.** Once the check passes, the claimed and the computed value are equal,
  so handing over the claim would look harmless. But it would make `blob-meta` only as true as the comparison. A wrong
  `sha256` in `blob-meta` is an integrity defect: a later cover or banner silently becomes a row on another stored object
  (`storedMatch.ts:1-12`; the #554 CR's analysis).
- **The structure that rules it out (decision 1):**
  - The value core remembers is `b64url` of its own hasher's 32 bytes. No code decodes the manifest's hex.
  - It is keyed by the `File` object core just built. A slice or a re-wrapped copy misses and is hashed, which fails safe.
  - It is registered only after the stream has ended (the ZIP's CRC is checked there) and after the comparison.
  - **A manifest without `sha256`** still gets the computed digest remembered (Q2). That is the case that tells
    "computed" apart from "claimed", so it is the discriminating test (§ Proof).

### The hook boundary

- `ImportContentHook` is core → application through the registry (`hooks.ts:1-4`, `:91`). An application may use core,
  never the reverse (ADR 0079). Three applications implement it: Memories, Depot (`depot/exportImport.ts:93`) and Expenses
  (`expenses/exportImport.ts:326`). Only Memories hashes a payload.
- **`contentDigest` is already core's one front door for a file's digest** (`sharedBlob.ts:28-37`). If core remembers
  the digest beside that door, the application does not have to be handed anything. Both Memories sites call
  `contentDigest` on the very object `ctx.payload` returned.
- **The row's assumption** that the hook's payload must change holds only for the explicit variants (Q1 (b), (c)).

### Cost: where the two passes run since #554

- **The check: page thread, alone, before the hook gets the file.**
  - `v1.sha256Stream` runs in `archivePayload` on the main thread, beside the ZIP's CRC-32 (`zip.ts:503`).
  - The walk is strictly sequential: `replaySubtrees` creates node after node (`importSubtree.ts:355-368`), and the
    Memories hook adds medium after medium (`exportImport.ts:376-386`). Each payload's check waits for the last upload.
  - So this pass is on the import's critical path and on the main thread. #554 measured the same `@noble` loop on the
    main thread at 3.1–3.2 s per 50 photos of 5.2 MB at throttle 1, unprofiled (`how-veyq-is-tested.md:102-106`). The
    check reads 1 MiB parts and adds a CRC, so its cost is of that order *(derived, not measured for an import)*.
- **The second pass: the digest worker, beside the derivation** (`media.ts:618-621`, #554).
  - **For a photo, it is hidden.** #554's stage medians are 113 ms for the digest window and ≈ 160–210 ms for the
    derivation's critical part at throttle 1 (the #554 CR). Removing the pass saves worker CPU (4.4–4.5 s per 50 photos,
    `how-veyq-is-tested.md:106`) and one full read of each original, not wall time *(derived)*. On a device where hashing
    outlasts the derivation, part of it shows.
  - **For a video original, it is on the critical path.** The derivation is one poster frame on the page
    (`mediaDeriver.ts:133-140`), and the digest reads the whole file, ≈ 3.5 s per GB at node's rate (the #554 CR,
    *derived*).
  - **For a video banner, it runs alone.** `restoreBanner` awaits `contentDigest` before `uploadBanner` with nothing beside
    it (`exportImport.ts:463-467`). The whole pass is on the critical path.
- **Judgment.** Worth doing, as a small CR. It is about 20 lines of production code, it touches no hook type and no
  application, and it takes a full pass off the critical path for every video original and video banner. For photos, the
  row's "removes one pass" holds for CPU and reads, not for the import's duration. The import's larger hashing cost is the
  check's own pass on the main thread, which this CR leaves alone (Q3).
- **No measurement is needed to decide** (Q4). The gain is one fewer read per payload, which a test counts. No doc or site
  page gets a number from this CR.

### Not in this CR

- **The check's own pass off the main thread** (Q3). It would hash the `File` in the digest worker. Then the ZIP's CRC
  would either become redundant where `sha256` is present or need a page-side pass where it is absent. That changes how
  ADR 0082 decision 10's check runs, so it is a refine of its own.
- **A copy's video banner** is hashed again on the page from `framedPayload` (`exportImport.ts:465`), although the source
  node's sealed meta carries its `sha256` (ADR 0102). That value is a stored claim, not a digest computed now, so its
  trust is a different question. It was already named by the #554 CR.
- **The verification overlapping the derivation** (handing the file out before the check ends, with a gate before the
  reserve). It would take the check off the critical path too. It moves ADR 0082's "verified before a hook uploads it"
  from core into every hook, so it is not proposed.

## Decisions this CR takes

1. **Core remembers the digest it computed, for the `File` it hands out** (Q1 (a)).
   - `core/blob/sharedBlob.ts` gets a module-private `WeakMap<Blob, string>` and an exported
     `rememberDigest(file: Blob, sha256: Uint8Array): void`. It takes the hasher's raw 32 bytes, refuses any other length
     (throws), and stores their `b64url`.
   - `contentDigest(source, signal)` answers a remembered source from the map, without a read. If `signal` has already
     aborted, it rejects with an `AbortError` first, so the caller's abort behaviour does not change. Otherwise it works as
     today.
   - `archivePayload` calls `hasher.digest()` once and compares its hex where the manifest has a `sha256`. Only then does
     it build the `File`, call `rememberDigest(file, digest)` and return it.
   - **Who may set it:** core's import resolver alone. `rememberDigest`'s doc says why: the caller vouches that the bytes
     are that object's own, hashed by core. The locator `git grep -n "rememberDigest" -- src/web/src ':!*.test.ts'` must
     list only its definition and `importSubtree.ts`. It is re-run before the commit. There is no lint door, as #554
     decided for its front door.
   - **No hook type changes and no application code changes.** `addOriginal` and `restoreBanner` already pass the
     object through to `contentDigest`. The comments that become false are corrected: `importSubtree.ts:12-13` and
     `:249-250`, `hooks.ts:76-78` (`payload`), `sharedBlob.ts:28-34` (`contentDigest`), and `exportImport.ts:456-458`
     ("its digest is read again here").
2. **The digest is remembered whether or not the manifest had a `sha256`** (Q2). It is the digest of those bytes, which
   is what `blob-meta` states. Whether the manifest agreed is the check's concern, not the upload's.
3. **The check itself does not change.** Same pass, same thread, same refusals, same order. The CRC still ends the stream
   before anything is remembered.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Contracts, backend, `@veyq/crypto` | nothing: the digest's bytes and the wire are unchanged | `grep -n "sha256" contracts/export/veyq-subtree.schema.json` |
| Web: the memo | `rememberDigest` and `contentDigest`'s lookup | `grep -n "export function contentDigest" src/web/src/core/blob/sharedBlob.ts` |
| Web: the check | `archivePayload` keeps its digest and registers it | `grep -n "sha256Stream\|hasher.digest" src/web/src/core/export/importSubtree.ts` |
| Web: who benefits | every `contentDigest` call that can receive an import's `File` | `git grep -n "contentDigest\|findEqual(" -- src/web/src ':!*.test.ts'` |
| Web: second angle | every hook that reads a payload (only Memories hashes one) | `git grep -n "ctx.payload(" -- src/web/src/features ':!*.test.ts'` |
| Web: the tests | the check's, the memo's, the upload's read order | `git grep -ln "contentDigest\|checksum\|ctx.payload(" -- 'src/web/src/**/*.test.ts'` |
| Docs | none: ADR 0082 decision 10 and `uc.platform.export-import` stay true as written | `git grep -n -i "sha256" -- docs/features/platform/use-cases/uc.platform.export-import.md` |
| Site | none: nothing a member sees or does changes, and no reading is published | `git grep -n -i "import" -- site/src/content/docs/security/how-veyq-is-tested.md` |
| Inbox | #555 deleted at the end | `grep -n "^| 555 " docs/inbox.md` |

## Phases

0. **Refine close (the refining conversation, not the implementer).** Write the owner's answers into § Decisions.
   **Done when:** every `scripts/validate_*.py` exits `0`.
1. **Web.** Before the first edit, record the exit code of `npm run check` in `src/web` on `main`. Then build decisions
   1–3.

   **The proof.** Every negative has a positive control on the same predicate, and each bite is a snapshot and restore,
   never `git checkout`.
   - **The check hands over what it computed:** `core/export/importHardening.test.ts`, new describe, with its ZIP and
     directory builders and the fake hook of `:46`.
     - For a ZIP and a directory, the hook takes the `File`, spies on its `slice`, and calls `contentDigest(file)`. The
       answer equals `digestHere(file)` (imported from `core/blob/digest.ts` on purpose, the page-thread loop over the
       object's own bytes, which proves they are the same bytes) and `b64url(v1.sha256Digest(body))`. `slice` was not
       called by `contentDigest`.
     - **Control:** `contentDigest(new File([file]))`, a different object over the same bytes, reads (`slice` called) and
       answers the same digest.
   - **Computed, not claimed:** the same test with the payload's `sha256` removed from the manifest.
     - The import proceeds, `contentDigest(file)` makes no read, and it answers the bytes' digest.
     - **Bite:** register the manifest's value instead (hex-decode `ref.sha256` where present, skip otherwise). This test
       goes red: nothing is remembered, so a read happens.
   - **A tampered manifest still fails, and no hook receives the payload:**
     - a `sha256` of other bytes refuses with `checksum`;
     - a ZIP whose entry fails its CRC refuses with `checksum`;
     - in both, the hook's `payload` call rejects and the hook builds nothing. `importHardening.test.ts:321` and
       `subtree.test.ts:273` are the existing anchors.
     - **Bite:** remove the comparison. The `sha256` case goes red.
     - **Control:** the untampered archive imports (the first case).
   - **The memo's own edges:** `core/blob/sharedBlob.test.ts`.
     - A remembered `Blob` answers with no read. With an already-aborted signal it rejects `AbortError`. **Control:**
       unaborted, it resolves.
     - `rememberDigest` with 31 or 33 bytes throws.
   - **The upload reads the original once:** `features/memories/media.test.ts`, the pattern of the read-order test at
     `:1229`.
     - A source whose digest was remembered: `source.slice.mock.calls` equal the pump's slices alone, and the original's
       `blob-meta` `sha256` (the `readBlobMeta` pattern of `:419`) equals the remembered value.
     - **Control:** the existing `:1229` test, unremembered, stays digest + pump.
     - **Bite:** drop the lookup in `contentDigest`. The new test goes red.
   - **The object survives to the door:** `features/memories/exportImport.test.ts`, the `importMemoryContent` describe
     (`:197`) and the banner round trip (`:411`). The `blob` of `addMedia`'s source is `toBe` the object `ctx.payload`
     returned, and so is the video banner's `contentDigest` argument.
     - **Bite:** re-wrap the file in `addOriginal` (`new File([file], …)`). The test goes red.
   - The existing tests stay green and are the oracle: #554's overlap tests (`media.test.ts:484-560`),
     `uploadWire.characterisation.test.ts`, `copySubtree.test.ts`, `copy.wire.test.ts`, and Depot's and Expenses'
     `exportImport.test.ts`.
   - Decision 1's locator is re-run before the commit.

   **Done when:** `npm run check` in `src/web` is green, and `npm run check` in `src/admin-web` is unchanged.

   **No browser pass is required:** no screen changes. If one is run on the dev instance, it imports a download holding
   a photo, a video and a video banner. Then picking the imported video as the banner must make a shared row, nothing
   uploaded, which shows the remembered digest equals the one a fresh hash gives.
2. **Close.** Delete #555 from `docs/inbox.md`. **Done when:** every `scripts/validate_*.py` exits `0`.

## Definition of done

- An import hashes each download payload once: core's check computes the digest, and `contentDigest` answers the `File`
  core handed out from it. A member's upload, a cover's or banner's match, and a copy hash as before.
- The digest sealed into `blob-meta` is core's computed one, never the manifest's field. That holds by structure
  (decision 1) and is proven by the case without `sha256` and its bite.
- A tampered or damaged payload still stops the import before any hook receives it.
- The digest's bytes, the wire, the hook types, `@veyq/crypto` and the backend are unchanged.
- No coverage file changes. `satisfies: []` holds because no use case's behaviour or criterion moves.
- Inbox #555 is deleted.

## Open questions (owner)

**Decided** (owner 2026-10-27): every recommendation below is taken — (a) core remembers the digest (Q1), also
without a manifest `sha256` (Q2), the check's own main-thread pass is inbox #557 (Q3), and no reading first (Q4).
**Ready to implement.**


1. **How does the digest reach the upload?**
   - (a) **Core remembers it for the `File` it hands out, and `contentDigest` answers from that** (decision 1). No hook
     type and no application changes, and the video banner is covered by the same line. A copy or a re-wrap misses
     safely. Its cost is that the free digest is implicit: a reader of `uploadMediaVia` sees it only in
     `contentDigest`'s doc.
   - (b) **An optional `sha256` on `PayloadFile`**, set by `archivePayload` on its `File`, read by `addOriginal` into a
     new `AddMediaOptions.sha256` and by `restoreBanner`. It is explicit in the hook type. It needs a branded type or a
     subclass of `File` so that a manifest string cannot be passed by accident. It changes Memories at three sites, and
     the field means nothing for a copy's payloads.
   - (c) **`payload()` returns `{ file, sha256? }`.** It is the most explicit. It changes every hook's payload calls in
     three applications and their test fakes (`git grep -n "ctx.payload(" -- src/web/src`), for one reader.

   **Recommendation: (a).** The trust is structural: the value is core's own hasher's bytes, keyed by core's own object.
   The manifest has no path to it, and no application has to be trusted with it. It is also the smallest change that
   covers both Memories sites.
2. **Remember the digest when the manifest has no `sha256`?**
   - (a) yes, decision 2;
   - (b) only where it was compared.

   **Recommendation: (a).** `blob-meta` states the digest of the bytes, and that holds either way. (b) would re-hash
   exactly the unverified payloads, for no gain in truth. It would also remove the one test that tells "computed" apart
   from "claimed".
3. **The check's own pass on the main thread** (§ Not in this CR):
   - (a) leave it;
   - (b) file it as an inbox row, read with an import scenario first;
   - (c) take it into this CR.

   **Recommendation: (b).** It is likely the import's largest hashing cost now (≈ 3 s per 50 photos *derived*, on the
   critical path), but it changes how the archive check runs (the CRC), and an import has never been read. A profile
   scenario for an import would give both CRs their number.
4. **A reading before deciding?**

   **Recommendation: no.** The CR claims one fewer read per payload, which a test counts, and publishes no number. Since
   #554 the removed pass is hidden for a photo, so a profiled import would mostly show Q3's pass, which this CR does not
   touch.

## Retrospective

Implemented on `worktree-agent-a649f4328f29e673f`, fast-forwarded to `main` at `5a6c23679`.

- **Built as decided, no deviation.** `sharedBlob.ts`: a module-private `WeakMap<Blob, string>`, `rememberDigest` (32 bytes
  or `digest_not_sha256`), and `contentDigest` answering a remembered `Blob` from it (an already-aborted signal rejects
  `AbortError` first). `archivePayload` calls `hasher.digest()` once, compares its hex where the manifest names a
  `sha256`, then builds the `File` and remembers the digest for it. Decision 1's locator lists only the definition and
  `importSubtree.ts`. Comments corrected: `importSubtree.ts` (header, `archivePayload`), `hooks.ts` (`payload`),
  `sharedBlob.ts` (`contentDigest`), Memories' `restoreBanner` and `addOriginal` (each names that a re-wrapped `File` is
  hashed again - the one way to lose the hand-over, which fails safe), and the comment above `uploadMediaVia`'s digest.
  No application code changed beyond comments.
- **Baseline (`npm run check` on `main`, before the first edit): exit 1**, lint and typecheck green, one test red by
  timeout only: `FileViewer.pdf.test.ts` "loads pdf.js only through a dynamic import" ran 9.7 s against vitest's 5 s
  default, load 7.20 at the start and 17.35 at the end. **The baseline's substrate was not clean:** it ran in the
  background and the first two edits (`sharedBlob.ts`, `importSubtree.ts`) landed while its test stage ran, so its
  test stage may have read them. The red test reads neither. Harvested as a sentence under `docs/ai/README.md`
  § The pipeline, "A measurement is only as true as the tree it ran against".
- **After: `npm run check` green** (exit 0; 457 files, 5085 tests), load 6.99 at the start, 17.41 at the end. The
  timeout above did not recur. `src/admin-web` was not run: the diff touches nothing under it or `src/packages/`.
- **Bites** (each a snapshot and an Edit back, the restored files `diff`-equal to the snapshot):
  1. Remember the manifest's hex-decoded `sha256` instead, skipping where absent: the two "manifest without a sha256"
     cases (ZIP, directory) went red, `slice` called once. The four with a `sha256` stayed green, which is the point:
     only the absent claim tells computed from claimed.
  2. Remove the comparison: the new `checksum` case, `importHardening`'s existing one and `subtree.test.ts:273` went red.
  3. Disable the ZIP CRC check (`zip.ts`): the new CRC case went red (`cause` undefined). The manifest has no `sha256`
     there, so the CRC is the only detector.
  4. Drop the lookup in `contentDigest`: 8 red - the memo's own two, the four hand-over cases, the media read-order
     test and the banner test.
  5. Skip the abort check for a remembered digest: the `AbortError` case went red (resolved).
  6. Re-wrap the original in `addOriginal` and the banner's file at its `contentDigest` (`new File([file])`): both new
     `exportImport.test.ts` cases went red.
- **What the tests learned.** A test fake whose payload is a fresh `File` per call hides object identity; the two
  Memories tests remember a marker digest no hash of the bytes gives, so the sealed `sha256` can only have come
  from the memo. The banner test's export puts the media's payloads first, so the banner's file is found by name, not
  by index (the first run compared against `a.jpg`).
- **No code trap left to move:** the one trap (a re-wrapped `File` loses the remembered digest) is at the two
  Memories call sites and in `hooks.ts`'s `payload` doc.
- **For an inbox row (reported, not filed):** the `FileViewer.pdf.test.ts` source walk reads every `src/` file
  synchronously and is close to vitest's 5 s default under load; it is the one timeout the baseline showed.
