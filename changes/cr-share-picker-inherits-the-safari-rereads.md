---
id: cr-share-picker-inherits-the-safari-rereads
title: E3's re-read is a property of the picker, so the contributor's picker gets it too
status: planned
satisfies:
  - uc.memory.upload
  - uc.sharing.share-contributor
  - uc.sharing.share-uploader
adrs: []
invariants:
  - A contributed photo that the visitor picked is uploaded — losing it silently is worse than failing loudly.
---

## Goal

[uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) E3 describes a Safari workaround in
three parts and attributes all three to **"the picker"**, not to one screen. Two of them — accept-by-extension
when `type` is empty, and infer `mediaType` from the extension — already sit in shared helpers and therefore
reach every caller. The third, **re-read `input.files` over a retry window and merge by signature**, is written
inline in `MemoryDetail.vue` and reaches only the curator. A contributor uploading from Safari's Photos gallery
therefore silently loses whatever the browser had not yet populated.

The doc is ahead of the code here, so this is a work order and not a prose correction. The fix is to lift the
missing third part into the helper block that already exists for exactly this purpose, and to make both pickers
call it.

## Context & constraints

- [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) § E3 — the governing text. Note its
  own constraint, which the lifted helper must keep: *"The window never delays the files already in hand"* —
  each re-read enqueues only what is genuinely new, as an additional batch. A helper that gathers first and
  enqueues once would satisfy the letter and break the rule the sentence exists for.
- [uc.sharing.share-contributor](../docs/features/sharing/use-cases/uc.sharing.share-contributor.md) § Main flow
  step 4 and [uc.sharing.share-uploader](../docs/features/sharing/use-cases/uc.sharing.share-uploader.md) § Main
  flow step 4 both describe picking images and neither has an E-flow for Safari. Neither file links
  `uc.memory.upload` at all. They get a **cross-reference**, never a copy of E3.
- `web/src/features/memory/uploadQueue.ts` already carries the section header
  `--- file-selection helpers (Safari Photos import, uc.memory.upload E3) ---` and already owns
  `isSupportedMediaFile`, `inferMediaType` and `mergeFiles`. The home exists; only the orchestration is missing
  from it.
- The `UploadTarget` union is what already makes the two paths interchangeable — the curator's target carries
  `{ scopeId, nodeId, memoryKey }`, the contributor's carries a share-scoped `upload` closure holding
  `contributorToken`/`pwToken`. **The lifted helper must not learn about either**; it takes an input element and
  an enqueue callback and nothing else.

### The trap this CR exists to avoid

`ShareView.vue` does not merely omit the re-read — it clears `input.value` **synchronously in the same tick** as
the single read. Adding a retry ladder without moving that clear produces a helper that re-reads an input the
caller has already emptied: four timers, four empty `FileList`s, a green-looking implementation and the same
silent loss. **The clear is part of the window's teardown, not part of the pick.**

Second trap, inherited: the curator's four `setTimeout`s have no `clearTimeout` and the component has no
unmount hook. Today that is guarded by null checks rather than by design. The lifted helper **owns its own
teardown** and returns a canceller; both callers wire it to unmount.

## Phases

- [ ] 1 web — write the contributor's picker test FIRST, against the untouched tree, and see it red. Then lift
      the orchestration (retry ladder, `enqueued` accumulator, tail-slice enqueue, deferred input clear,
      teardown) out of `MemoryDetail.vue` into `uploadQueue.ts`'s existing file-selection block, and make both
      pickers call it. ·
      Done when: `cd web && npm run check` green, and the new test passes for the reason it was written.
- [ ] 2 docs + coverage — cross-reference E3 from both sharing use-cases; widen E3's own wording only if the
      lift changes what is true. Coverage YAML for each `satisfies:` id. ·
      Done when: `python3 scripts/validate_coverage.py` and `python3 scripts/validate_doc_links.py` green.

## Locator — run these, do not trust a count from this file

```bash
# every picker in the client, and what each does with input.files
grep -rn 'type="file"' web/src --include=*.vue
grep -rn 'input\.files\|input\.value' web/src --include=*.vue --include=*.ts

# the helper block the lift lands in, and everything that already imports from it
grep -n 'file-selection helpers' web/src/features/memory/uploadQueue.ts
grep -rn 'isSupportedMediaFile\|inferMediaType\|mergeFiles' web/src

# who tests either picker today
grep -rln 'onFilesPicked\|filesPicked' web/src
```

## Bite tests — per test, the lever that reddens it

1. **The contributor loses a late file.** A `ShareView` test that redefines `input.files` after the pick and
   advances timers, asserting the late file is enqueued as an additional batch. **Lever: the live defect** — it
   is written and seen red against the untouched tree before any source edit, and the reason it is red must be
   the missing enqueue, not a missing mount or a stubbed module. `MemoryDetail.optimistic.test.ts` is the
   reference for the `Object.defineProperty` mechanics and for keeping `mergeFiles`/`isSupportedMediaFile` real
   via `...actual`.
2. **The window does not delay the files already in hand.** Assert the FIRST batch is enqueued before any timer
   advances. **Lever:** make the helper gather-then-enqueue instead of enqueue-then-top-up; this test goes red
   while test 1 stays green. Without it, a correct-looking helper can reintroduce exactly the regression E3's
   last sentence records.
3. **The clear happens after the window, not inside the pick.** **Lever:** move the `input.value = ''` back to
   the synchronous path; test 1 goes red again. This is a control on test 1 — without it, test 1 alone cannot
   say whether the fix was the ladder or the clear.

## Definition of done

`cd web && npm run check` green; `python3 scripts/validate_coverage.py` green; both sharing use-cases resolve
their new cross-reference under `validate_doc_links.py`. Every bite test above has been **seen red by its own
named lever**, not merely observed green.

No permanent guard is promoted: the structural claim here ("only one implementation of the re-read exists") is
already carried by the single helper plus its callers, and a grep guard over two call sites would restate the
import graph rather than protect it.

## Retrospective

<!-- filled at close -->
