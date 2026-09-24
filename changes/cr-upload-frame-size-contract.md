---
id: cr-upload-frame-size-contract
title: The frame size is a contract constant — chunkCount is derived, every PUT signs its exact length
status: planned
satisfies: [uc.memory.upload]
adrs: ["0013", "0047"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

Today only the web client knows how big a chunk frame is (256 KiB of plaintext, 262 172 bytes sealed). The
server takes a declared `chunkCount` and `byteSize` on trust. It checks only that no chunk is missing and that
the total does not exceed `byteSize`, and every presigned `PUT` URL accepts an object of any size for its TTL.
After this CR:
- the frame size is one constant in the crypto vectors, read by the backend and every client;
- `start` derives `chunkCount` from `byteSize` and refuses any other value, and any `byteSize` no frame
  sequence can have;
- each presigned `PUT` signs its chunk's exact `Content-Length`;
- `complete` requires every chunk at exactly its derived length.

This is the half of inbox #154 that windowed presigning (`cr-upload-windowed-presign`) builds on: a window can
only sign per-chunk lengths once every chunk's length is known.

## Context & constraints

- **The rule:** [ADR 0013 §3](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md), written in the
  refine, with the Garage v2.0.0 measurement of signed `Content-Length`. That measurement was **inherited** from
  the review, not re-run here. This CR's e2e reproduces it.
- **Frame layout** (`contracts/crypto/vectors/v1.json` `chunk_frame`, `packages/crypto/src/v1.ts` `sealFrame`):
  nonce(12) ‖ GCM ciphertext ‖ tag(16), aad = u32be(index) ‖ u32be(total). The web client chunks at 256 KiB
  and seals an empty blob as one 28-byte frame
  (`grep -n 'function chunkify' -A6 web/src/features/memory/media.ts`).
- **Range streaming already depends on it:** [ADR 0047](../docs/decisions/0047-inline-video-playback-range-streaming.md);
  `web/src/sw/range.ts` assumes every frame but the last is full.
- **The SW must not import the data layer** (`range.test.ts`'s own pin says so). Whether it may import
  `@veyq/crypto` is a bundle question this CR does not open. See Decisions.
- **Order:** after `cr-inert-store-responses` (it changes the same `S3BlobStore.presignPut` builder to sign
  `content-type`; this CR adds `content-length` to the same builder) and after `cr-request-bounds-enforced` (done —
  the changed `byteSize`/`chunkCount` bounds land under its guard, `scripts/validate_request_bounds.py`). Before `cr-upload-windowed-presign`.
- **Out of scope:** windows, the session's opener, the purge, streaming, `max-bytes`'s new default — all in
  `cr-upload-windowed-presign`.

## Decisions

- **One constant, in the vectors.** `v1.json` gains a sibling of `chunk_frame` carrying the frame's plaintext
  size (262 144), nonce (12) and tag (16) bytes, with a `note` stating the rule: every frame but the last is
  full, the last carries 1…262 144 bytes, and an empty blob is one frame with 0.
  - `@veyq/crypto` exports it, pinned by `v1.conformance.test.ts`.
  - `media.ts` imports it: its `CHUNK_SIZE`, and the literal `28` in `plaintextSize`
    (`grep -n '28 \* ref.chunkCount' web/src/features/memory/media.ts`).
  - `web/src/sw/range.ts` keeps its local constants so the SW bundle stays free of the crypto package. Its
    test pins them to the `@veyq/crypto` export instead of to `media.ts`.
  - The backend holds one constant, pinned by a test that reads `../contracts/crypto/vectors/v1.json` — the
    pattern `CapabilityTagRegistryTest` already uses for `contracts/tasks/tags/v1.json`.
- **Frame arithmetic lives in one backend unit** in the media feature, not in `BlobStore`: storage knows keys,
  not frames. It answers three questions, and `start`, `complete`, the test fixtures and (next CR) the window
  all ask it:
  - the chunk count for a `byteSize`;
  - the exact length of chunk *i*;
  - whether a `byteSize` is possible at all.

  A `byteSize` is impossible when it is under 28, or when its last frame would be shorter than 29 bytes while
  there is more than one frame.
- **`start` refuses a mismatch per item with a new code `shape_invalid`**, distinct from `budget_exceeded`
  (over the ceiling). One is a client bug, the other a file too large, and a client can say different things
  for them. `veyq.blob.max-chunks` is **deleted**: a derived count needs no cap of its own.
- **Contract:**
  - `chunkCount` keeps `minimum: 1` and loses its `maximum`: it is derived, and the ceiling is `max-bytes`.
  - `byteSize` gets `minimum: 28`.
  - Both descriptions state the derivation and cite the vector.
  - `presignedPuts`'s description says each URL is signed for its chunk's exact length.
  - The per-item `error` descriptions gain `shape_invalid`.
  - Both specs change (`media`, `sharing`): they are separate schema copies that must agree.
- **`presignPut` takes the chunk's exact length and signs it.** The SDK must actually put `content-length`
  into `X-Amz-SignedHeaders`. That is a property of the AWS SDK's presigner, not of this repo, so it is the
  **first thing measured** (phase 2, step 1), before anything is built on it.
- **`complete` requires every chunk at exactly its derived length** (not "total ≤ `byteSize`"). A mismatch is
  still a purge + `budget_exceeded` item, as today. The code stays; its description is corrected to say the
  objects did not match the declaration.

## Phases

- [ ] 1 contracts (crypto) — the vector entry and the constant's export from `@veyq/crypto`, pinned by
      `v1.conformance.test.ts`. Nothing reads it yet, so this phase ends green everywhere.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [ ] 2 backend. In this order:
      1. **Measure the lever:** extend `S3BlobStorePresigningTest` so a `presignPut` with a length asserts
         `content-length` in `X-Amz-SignedHeaders`. Run it before building anything on it. If the SDK does not
         sign it, stop and bring it back to refinement.
      2. The frame-arithmetic unit and its unit tests, pinned to the vector file.
      3. **The test fixtures first.** Every slice test that declares an arbitrary `(chunkCount, byteSize)`
         pair gets one fixture that derives a valid declaration and stages objects of exact lengths:
         `grep -rl 'chunkCount' backend/src/test`. Replace the pairs with the fixture — do not hand-compute
         new ones. A derived pair is also valid under today's checks, so this step ends green on its own and
         the steps after it change behaviour against a suite that already speaks the new shape.
      4. **Write the two bite-tests below.** They are live defects on the tree step 3 leaves; read which
         assertion reddens.
      5. **The contract, together with the code that obeys it.** Make the schema bounds and descriptions
         above in both specs. `scripts/validate_request_bounds.py` holds the annotations to them. Locator:
         `grep -n 'chunkCount\|byteSize\|budget_exceeded\|presignedPuts' contracts/api/media.openapi.yaml contracts/api/sharing.openapi.yaml`.
         In the same step, `start` derives and refuses (`shape_invalid`), and `max-chunks` goes. The bounds and
         the derivation land together: a `byteSize` of 16 is refused by both, so one without the other is a red
         whose cause the next step removes. Locators:
         `grep -rn 'maxChunks\|max-chunks' backend/src contracts docs infra charts` and
         `grep -n 'fun startOne' -A16 backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`.
      6. `presignPut` signs the length at every call site:
         `grep -rn 'presignPut' backend/src/main/kotlin backend/src/test/kotlin`. The test doubles
         (`FakePresigningBlobStore`, `RecordingBlobStore`) change signature with it.
      7. `complete` checks exact lengths:
         `grep -n 'fun verifyBlob' -A8 backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`.

      **Bite-tests:**
      - `start` with a `chunkCount` one off the derived value → `shape_invalid` (live defect: `200` with a
        session today).
      - `complete` with one chunk staged one byte short and the total still ≤ `byteSize` →
        `budget_exceeded` + purged (live defect: attaches today).
      - Each has a control with exact lengths → success.
      - `BlobBatchE2ETest` (opt-in, real Garage) uploads a multi-frame blob at derived sizes and reproduces
        ADR 0013 §3's table: a wrong-length `PUT` → `403`, a same-length re-`PUT` → `200`.

      · Done when: `./gradlew check` green, `npm run generate:api` + `npm run check` (web) green, and
      `VEYQ_S3_E2E=1 ./gradlew test --tests '*BlobBatchE2ETest*'` green against the dev store
      (`docker compose -f infra/docker-compose.yml --profile s3 up -d`).
- [ ] 3 web — the three copies of the frame size become the one export. Locators:
      `grep -rnE 'CHUNK_SIZE|FRAME_OVERHEAD|PLAINTEXT_CHUNK_SIZE|256 \* 1024' web/src` and
      `grep -rn '28' web/src/features/memory/media.ts web/src/sw/range.ts`.
      Tests that pinned `media.ts` against `range.ts` now pin both against `@veyq/crypto`.
      No client behaviour changes. The frames were already this size, and `fetch` sends a `Uint8Array` body's
      exact length.
      · Done when: `npm run check` (web) green.
- [ ] 4 docs + coverage
      - ADR 0047's `CHUNK_SIZE` mention points at the vector
        (`grep -n 'CHUNK_SIZE' docs/decisions/0047-inline-video-playback-range-streaming.md`).
      - A new criterion in `uc.memory.upload.yaml`: "the declared shape of a blob follows from its size — the
        server refuses any other chunk count, signs each chunk's exact length, and attaches a blob only when
        every chunk is present at exactly that length". Covered by the phase-2 slice tests and the opt-in e2e.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The signed-header lever was measured before anything was built on it.
- Both backend bite-tests were seen red on the untouched tree.
- The opt-in e2e reproduced ADR 0013 §3's table against the dev Garage.
- The frame size appears as a literal in exactly one place per runtime: the vector, `range.ts` (pinned),
  and the backend constant (pinned). The phase-3 locator returns only those, plus tests.

**Not proven:** that every S3-compatible store refuses a wrong length. That is a store property. `complete`'s
exact check is what holds where one does not.

## Retrospective
