---
id: cr-upload-prepare-visibly-and-decode-once
title: the pre-upload phase says what it is doing, decodes each photo once, and stops idling the CPU while the network works
status: planned
satisfies: ["uc.memory.upload"]
adrs: ["0013", "0034"]
invariants:
  - operator-blind — untouched. This CR changes only WHEN and HOW OFTEN the client decodes, labels and schedules; every byte that crosses the wire is sealed exactly as today, and no phase edits `sealBlob`, `sealMetadata` or the wire shape.
  - "the wire stays strictly serial — `reserve` → PUT → `complete` for one file finishes before the next file's `reserve` begins. Phase 3 overlaps only the ON-DEVICE stage. This is what keeps [ADR 0034](../docs/decisions/0034-media-uploader-buckets.md)'s unenforced primary-bucket uniqueness out of reach (architecture §11 names the sequential queue as the reason its race is unreachable) and keeps the open-session/byte-budget accounting one-at-a-time."
  - "reuse the existing UI ([ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first) — phase 1 adds no control and no new i18n key: `media.uploadStatus.{queued,preparing}` already exist and are already rendered by the tray; only the tile fails to ask for them."
  - "derivative quality is a product default, not an implementation detail ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md) § The three tiers) — phase 2 may not soften the progressive-halving downscale to save a decode. Halving 4032→2048→512 passes through the same ≤2× steps the two independent runs do today; anything that does not, is out of scope."
  - "no contract change — `contracts/` and `backend/` are untouched (a `git status` proves it)."
---

## Goal

Picking a large batch of photos into a Memory looks like the app has hung: the grid fills with tiles, and
nothing moves for a long time before the first byte leaves the device. Three separate causes sit in that gap,
and the first one is not a performance problem at all — **the UI does not say what it is doing**. Every
optimistic tile renders `aria-label="Uploading {name}"` and an indeterminate ring
(`OptimisticTile.vue:30` + `:59`), whether the file is `queued`, `preparing` or genuinely `uploading`. The one
honest label — *"Preparing…"* — exists, is translated in both languages (`i18n/index.ts:742` + `:1606`), and is
rendered only by the TopBar tray, which is not where the user is looking.

Behind that, the pre-network stage does more work than it needs to and does it at the wrong time. Each picked
image is decoded from its original bytes **four** times — once for the tile's aspect ratio
(`useOptimisticTiles.ts:90`), once per derivative tier (`mediaDeriver.ts:215` + `:216`, two independent
`createImageBitmap` runs over the full-resolution source), and once more for the sealed geometry
(`media.ts:234`) — plus a fifth full decode the browser performs to render the tile, whose `<img>` points at
the full-resolution original (`useOptimisticTiles.ts:78`). Two of those four produce the same two numbers.
And while a file's ciphertext is being PUT, the main thread — the only thread that decodes, downscales and
seals — sits idle instead of preparing the next file.

This CR makes the stage honest first, then stops the waste, then overlaps the two stages. It does **not**
move work to a Worker: that is a real option, a larger one, and it should be decided against a measurement
this CR is what produces.

## Context & constraints

- **[uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md)** owns this flow. Main-flow
  step 2 (the instant local preview) and step 3 (seal → start → PUT → attach) stand; the quality note
  *"Sequential, not parallel. One file at a time keeps the byte-budget + open-session accounting simple"* is
  **narrowed, not dropped** — after phase 3 it is one file at a time *on the wire*, which is the half the
  reason was ever about.
- **[en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)** owns the tier specs
  and the progressive-halving rule. Phase 2 changes the decode count, never a tier's size, quality or filter.
- **[ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)** is why `reserve` / PUT /
  `complete` is the wire boundary that phase 3 must not cross: all of a session's PUT URLs are signed once at
  `start`, and the presign TTL is what bounds an abandoned session.
- **Explicitly not in scope — OffscreenCanvas in a Worker.** It is the structurally right answer to "the main
  thread is the bottleneck", and it is also the largest change of the four. Phases 1–3 are each independently
  correct without it; if preparation is still the felt cost after them, the Worker is its own CR **with the
  phase-3 measurement behind it** rather than on suspicion.
- **Explicitly not in scope — the tile's `<img>` source.** Pointing 100 tiles at 100 full-resolution originals
  is the fifth decode and a real cost, but fixing it means deciding what the tile shows *before* a derivative
  exists — a product question about the instant preview that step 2 of the use-case settles today. Named here
  so the next reader knows it was seen and left.

### Substrate

Measured **2026-09-20 against `main` @ `116c9ce`**, working tree **dirty and shared with a parallel session**:
four modified docs (`uc.memory.{gallery,live-updates,upload}.md`, `docs/inbox.md`) and five untracked CRs,
none of them this one's. Every line this CR cites was read on that tree. Line numbers drift — **re-read the
surrounding code, do not trust the number** — and the neighbour's tip may be ahead of yours, so **stage by
explicit path, never `git add -A`, and address a commit by hash, never by `HEAD~n`**.

**A live collision, not a hazard in the abstract.** `cr-gallery-one-order-for-every-tile` is open and
untracked in the same tree, and it edits three of the same artifacts: `useOptimisticTiles.ts` (it adds a
capture-time probe to *the same pass* phase 2 rewrites), `OptimisticTileVM` (it adds a field, phase 1 adds
another), and `uc.memory.upload.yaml#optimistic-in-grid-tiles` (it deletes the *"pinned ahead of the listed
media"* clause phase 4 would otherwise have to reason about). **That CR goes first.** This one is written
against the tree it leaves behind; if it has not landed when this one starts, re-read those three artifacts
before the first edit rather than resolving a merge afterwards.

### Inventory — the locators, not their output

| Question | Command |
|---|---|
| Every site that decodes an original | `grep -rn "createImageBitmap\|probeDimensions\|deriveImage(" web/src --include="*.ts" --include="*.vue"` |
| Where the "one file at a time" claim is written, in code and prose | `grep -rni "one file at a time\|sequential\|not parallel" web/src docs changes --include="*.ts" --include="*.vue" --include="*.md" --include="*.yaml"` |
| Which surfaces render a tile's status | `grep -rn "OptimisticTile\|uploadStatus\|uploadTilePreview" web/src` |
| Which tests pin the decode count or the queue's ordering | `grep -rn "deriver.images\|deriver.posters\|sequentially\|FIFO" web/src/features/memory` |

The two angles disagree, which is the finding. The **symbol** angle (`createImageBitmap` etc.) locates the
four decodes and stops there. The **claim** angle finds one more site no symbol grep reaches:
`docs/architecture/README.md:299` uses *"the web upload queue is sequential"* as the **reachability argument**
for why ADR 0034's unenforced primary-bucket uniqueness costs nothing today. That sentence is the reason
phase 3's boundary is where it is — and a broader parallelisation, which is the obvious reading of "can we
parallelise this", would have falsified a live security-adjacent claim silently.

**Spot-check the first row before the first edit.** This CR's whole second phase rests on the decode count
being four; if the command returns fewer sites than phase 2 names, the site list is wrong, not the grep.

### Traps

- **`media.test.ts:192` asserts the waste.** `expect(deriver.images).toBe(2)`, with `:194-195` pinning the
  call order THUMBNAIL-then-PREVIEW. Phase 2 makes it fail. It is **rewritten, not deleted** — the property
  worth keeping is *"both tiers are produced at their pinned specs"*, and the new assertion states it over one
  decode. Changing a test a CR cites as evidence is exactly the move that needs saying out loud, so: this is a
  restatement at the same strength, not a relaxation.
- **The same stub is blind to the fourth decode.** `stubDeriver()` (`media.test.ts:138`) implements no
  `probeDimensions`, and `probeDims` returns `null` early when it is absent (`media.ts:231`) — so the geometry
  decode runs in **no** test today. Phase 2's bite must give the stub a counting `probeDimensions` first, or it
  counts a path that never ran and passes for the wrong reason.
- **`ShareView.vue` has no quiet window at all.** Its picker enqueues synchronously (`ShareView.vue:211-225`)
  — no merge, no timer. So the contributor surface is the live control for phase 1: immediate enqueue already
  ships there. It also means the Safari E3 workaround protects only the curator path, which is a real gap and
  **not this CR's** — it belongs in the inbox.
- **`OptimisticTile.vue` is shared by both surfaces** (`MemoryDetail` and `ShareView` both import it), so
  phase 1's label fix lands on both at once. Assert it where the component is mounted, not per surface.
- **Two labels are wrong, not one.** The container's `media.uploadTilePreview` ("Uploading {name}",
  `OptimisticTile.vue:30`) is as untrue for a queued file as the ring's `media.uploadStatus.uploading`
  (`:59`). A phase that fixes only the inner one leaves a screen reader saying "Uploading" over a file that has
  not started.

## Phases

- [ ] 1 web — **the tile says which stage it is in, and the picker stops making everyone wait for Safari.**
      Two independent corrections, one phase because they share the one measurable outcome (time until the
      grid tells the truth). (a) `OptimisticTileVM` already carries `status`; the tile renders the matching
      `media.uploadStatus.*` string in the existing `.upload-tile-state` zone — the same slot the failed state
      already uses for `upload-tile-failed-label`, so no new control and no new key — and both aria-labels
      follow the status instead of asserting "Uploading". (b) `MemoryDetail`'s picker enqueues **what it
      already has, immediately**, and the `RETRY_DELAYS` re-reads enqueue late arrivals as an *additional*
      batch instead of gating the first one: `QUIET_MS` (`MemoryDetail.vue:220`) stops being a delay every
      user pays and becomes what it was for. E3 is preserved in full — the merge still de-dupes by signature,
      so a file read twice is still enqueued once. · **Bite (live defect, B1):** a tile whose file is `queued`
      exposes neither aria-label containing "Uploading". Reddens on the untouched tree — read *which* of the
      two assertions reddens, since the hard-wired `:59` label and the `:30` container label fail
      independently. · **Bite (live defect, B2):** with fake timers, a pick whose `input.files` is complete
      synchronously reaches `enqueueUploads` **before** `QUIET_MS` elapses; the control on the same predicate
      is a late-arriving second read still producing an enqueue, so B2 cannot be satisfied by a picker that
      simply stopped merging. · Done when: `npm run check` green in `web/`, B1 + B2 red before and green after.

- [ ] 2 web — **decode each original once.** One decode feeds the geometry, the tile's aspect ratio and both
      tiers: `deriveTiers` produces THUMBNAIL from the already-downscaled PREVIEW (4032→2048→512 traverses the
      same ≤2× halving steps, so the pinned filter rule is honoured, not traded away), and the dimensions the
      decode already knows are returned rather than re-probed. `sealMetadata` consumes that result instead of
      calling `probeDims`, and `useOptimisticTiles` consumes it instead of its own probe — which is also what
      removes the duplicate work between the tile pass and the upload pass. · **Bite (injection, B3):** a
      counting deriver — `stubDeriver` extended with a `probeDimensions` that increments, per the trap above —
      records exactly **one** decode of the original for one image through the full `addMedia` path, while both
      tiers still arrive at `TIER_SPECS.THUMBNAIL` and `TIER_SPECS.PREVIEW`. Lever: restore the second
      `deriveImage` call over the source blob; the count goes to two and B3 reddens. The lever is a call this
      CR deletes and no later phase reintroduces, so it cannot be disarmed by this CR's own completion. ·
      **Control:** the video path still decodes exactly one poster and zero images (`media.test.ts:219-220`
      unchanged) — a change that collapsed the image count by skipping derivation entirely would fail it. ·
      Done when: `npm run check` green, B3 green and demonstrably red under its lever, `media.test.ts:192`
      rewritten as described in Traps.

- [ ] 3 web — **prepare the next file while this one is on the wire.** *Entry condition: measure first.* Take
      the time from enqueue to the first PUT for a fixed batch after phase 2 and write the number in the
      retrospective. **If preparation is no longer the felt cost, stop here and close the CR at two phases** —
      building an overlap nobody waits for is the tool without the pain. If it is, the queue runs the on-device
      stage of file *n+1* (read → derive → seal) concurrently with file *n*'s PUTs, while `reserve` / PUT /
      `complete` stay strictly serial across files — one in-flight wire transaction, always. The seam is
      `uploadMediaVia`: the stage before `transport.reserve` (`media.ts:360-364`) is what may overlap; from
      `reserve` on, nothing does. · **Bite (live defect, B4):** with two files and a transport that blocks
      inside file 1's PUT, file 2 has completed its sealing before file 1's `complete` is called — reddens on
      today's runner, which has not touched file 2 at all. · **Bite (injection, B5, the invariant):** across a
      whole batch, no two wire transactions overlap — `reserve`/`complete` call windows are disjoint, asserted
      on recorded enter/exit ordering, not on a count. Lever: start the next file's `reserve` from the same
      overlap point as its sealing; B5 reddens while B4 stays green, which is what tells the two properties
      apart. Without B5, B4 is equally satisfied by the parallelisation this CR forbids. · Done when:
      `npm run check` green, B4 red before and green after, B5 green and red under its lever — **or** the phase
      is closed unbuilt with the measurement recorded, which is a legitimate outcome, not a failure.

- [ ] 4 docs + coverage — **supersede the claims, in the shape each one needs.** `uc.memory.upload.md`'s
      quality note *"Sequential, not parallel"* is **narrowed** to the wire (one file at a time on the wire;
      the on-device stage may run ahead) — and only if phase 3 was built; if it was not, the note stands
      unedited and the measurement goes in the retro instead. Main-flow step 3 gains the one sentence that a
      picked file shows its stage (`preparing` is not `uploading`). `uc.memory.upload.yaml` is **extended**:
      `optimistic-in-grid-tiles` gains the honest-status half with B1 as its ref, and a new criterion covers
      the decode-once property with B3 — both under this use-case, because each describes what *this
      operation does*, not a property with other triggers. `docs/architecture/README.md:299`'s reachability
      sentence is **re-read and left standing only if phase 3 kept it true** — the wire is still sequential, so
      it should be; confirm it rather than assume it, and if phase 3 was closed unbuilt it is untouched.
      `en.memory.derivatives.md` § *Generation & attachment* is **edited** to say the thumbnail derives from
      the preview. · **Re-run the claim locator at this phase, not only from refine** — phases 1–3 author new
      comments about staging and ordering, so this phase's corpus is one the earlier phases grew. Read
      `uc.memory.upload.md` end to end, including its bilingual reader-facing section, rather than trusting
      the fragment grep. · Done when: `python3 scripts/validate_coverage.py` green and the claim locator
      returns no site still asserting an unqualified sequential queue.

## Definition of done

`npm run check` green in `web/`; `contracts/` and `backend/` untouched, with a `git status` proving it. Every
guard green: `for f in scripts/validate_*.py; do python3 "$f"; done` — run it **before the first edit** too, so
a red afterwards is attributable to this CR rather than inherited from the shared tree. `docs/coverage/`
updated with B1 and B3 as refs and `python3 scripts/validate_coverage.py` green.

**No permanent guard is promoted.** The two structural invariants are each already held by something stronger
than a grep: *decode once* is enforced by B3's counting stub, which fails on any reintroduced decode rather
than on a spelling; *the wire stays serial* is enforced by B5, which asserts disjoint call windows and would
not be helped by a check for code nobody has a reason to write. A guard here would assert the absence of a
string, which is the weaker claim.

**Live proof, once the phases are green:** pick a large batch into a Memory against the dev backend and watch
the grid. The deliverable is that the tiles say *"Preparing…"* while they are being prepared — and that the
first one stops saying it sooner than it does today. Record both timings (before, and after each phase that
shipped) in the retrospective; they are the entry condition for the Worker CR, if it is ever written.

## Retrospective

_To be filled at implement time — what went wrong in planning, in execution, and in process/collaboration
(did we honour the conventions we already agreed: language, workflow, the memory notes?), root cause, and one
concrete "next time". Record the enqueue→first-PUT measurement here, before and after, including the phase-3
entry decision and how it went._
