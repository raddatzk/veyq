# UC: Two members curate one Memory at once

- **ID:** uc.memory.concurrent-curation
- **Primary actor:** Two or more members of a group space, each with an unlocked vault, who have the same Memory
  open — or one of them has it open while the other edits.
- **Goal:** Whatever one member sets on a Memory or a photo — a date, a place, a cover, a photo's corrected time
  — survives the other member's edits and the app's own automatic updates, unless the other member changed
  **that same thing** on purpose.
- **Preconditions:** Both members hold the Memory's `K_node`. Each client holds a copy of the Memory's sealed
  `wrappedMeta` (from the tree it loaded) and of each photo's sealed `wrappedMeta` (from the gallery list), each
  with the `metaRevision` it was listed at.
- **Postconditions (success):** Every sealed meta blob on the server is the result of each writer applying
  **only its own change** to the newest blob. A member whose own field was changed meanwhile is shown the
  current value instead of silently replacing it.

## Main flow

1. A writer starts from its copy of the blob and its `metaRevision`. The writers are the three Memory editors
   (date, place, cover — [uc.memory.date](uc.memory.date.md), [uc.memory.place](uc.memory.place.md),
   [uc.memory.cover](uc.memory.cover.md)), the two self-heals that run when a Memory opens or its media list
   refreshes (the `auto` date range and the `auto` place medoid), the lightbox's capture-time editor, and the
   photo backfill of the resolved place name. (A second backfill, of a sniffed media type, is gone: the type is the
   original's write-once blob meta since inbox #397.)
2. It opens the blob, applies its one change, re-seals the whole blob and sends it with the `metaRevision` it
   started from ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)).
3. If nobody wrote in between, the server stores it at `metaRevision + 1`, and the client updates its copy to the
   blob it sent and that revision.
4. If somebody wrote in between, the server stores nothing and answers `409 stale_revision` with the current blob
   and its revision. The client re-runs step 2 on that blob — at most five times — and the change of the member
   who wrote first survives.
5. The client's copy (the tree, the gallery item) is replaced by what the server last told it, whether or not the
   retry ended in a write.

## Alternate / exception flows

- **E1 — the same field, changed on purpose by both.** An editor whose own field differs in the fresh blob from
  the value it opened with does not retry. It re-opens on the current value with a one-line notice; the member
  decides again.
- **E2 — a self-heal from a stale tree.** A self-heal re-runs its whole decision on the fresh blob. If a peer set
  a manual date meanwhile, the `auto` range is not written; if a peer pinned the place (`fixed`), the medoid is
  not written. Nothing is shown — nobody asked for anything.
- **E3 — a backfill that is no longer needed.** If the fresh photo blob already carries the place name or media
  type, nothing is written.
- **E4 — a cover save that already moved the cover blob.** An uploaded cover (or a switch away from one) has
  replaced the node's one cover blob before the meta write. That save's own cover is re-applied on the fresh
  blob instead of re-opening on the peer's — the peer's cover blob is already gone.
- **E5 — a blob that does not open.** A present blob the client cannot open (or parse) is never rebuilt from
  empty: a self-heal or backfill stops, an editor saves nothing and says so. Display still treats it as "no
  metadata" ([uc.memory.date](uc.memory.date.md) E3).
- **E6 — five conflicts in a row.** The writer gives up; an editor reports the save as failed, a self-heal waits
  for the next open.
- **E7 — a mark or pin toggled before the set loaded.** The toggle does nothing until the member's own set has
  loaded, so it can never write a one-element set over the stored one.

## Quality notes

- **Operator-blind.** The `409` body carries ciphertext the caller can already list; the server sees a revision
  number per blob and that two writes raced, nothing inside.
- **Which writes are not covered, and why.** A role change carries only the value the member typed, and a share,
  a profile or a pin set has one writer; those stay last-writer-wins, declared per operation in the contract
  ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) point 6). A rename and a move are
  revision-checked on a revision of their own, `placementRevision` — not because of a lost edit, but because the
  name and key wrap they seal are bound to it, so one sealed for the old parent must never land after a move
  ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 3).

## What this means for you / Was das für dich bedeutet

**EN —** Several of you can work on the same memory at the same time. If someone sets the place while you are
looking at the memory, the place stays — opening the memory or adding a date never erases it. If two of you
change the very same thing, the app shows you what the other person chose and lets you decide again, instead of
silently overwriting it.

**DE —** Mehrere von euch können gleichzeitig an derselben Erinnerung arbeiten. Setzt jemand den Ort, während
du die Erinnerung ansiehst, bleibt der Ort erhalten — das Öffnen der Erinnerung oder ein neues Datum löscht ihn
nie. Ändert ihr beide genau dasselbe, zeigt dir die App, was die andere Person gewählt hat, und du entscheidest
neu, statt es stillschweigend zu überschreiben.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the node blob and its `metaRevision`),
  [en.memory.media](../enablers/en.memory.media.md) (the media blob and its `metaRevision`),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the toggles of E7)
- Use-cases: [uc.memory.date](uc.memory.date.md), [uc.memory.place](uc.memory.place.md),
  [uc.memory.cover](uc.memory.cover.md), [uc.memory.gallery](uc.memory.gallery.md) (the lightbox writers)
- Decisions: [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)
