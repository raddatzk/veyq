# ADR 0077 — A write that re-sends what its writer did not edit carries the version it read

- Status: Accepted
- Date: 2026-10-03

## Context

The server cannot merge what it cannot read. Two sealed blobs are written **whole** by **any member** of a
scope, and each holds several fields that different members, and different automatic writers, own:

- **A node's `wrappedMeta`** ([en.memory.node-meta](../features/memory/enablers/en.memory.node-meta.md)): a
  Memory's date, place and cover. The date and place editors, the cover editor, and two **self-heals** (the
  `auto` date range and the `auto` place medoid) all unseal the blob, change one field and re-seal the rest.
- **A media's `wrappedMeta`** ([en.memory.media](../features/memory/enablers/en.memory.media.md)): the
  capture-time correction, the resolved place name and the sniffed media type. The lightbox's date editor and
  two **backfills** do the same read-modify-write.

Every one of those writers re-sends fields it did not edit, from whatever copy it holds. The tree is loaded at
space entry and reloaded only after a create, rename or delete, and the self-heals run whenever a Memory opens or
its media list refreshes — so a member who **only opens** a Memory re-seals the date, place and cover they
loaded minutes ago, and a peer's newer place or cover is gone. Nobody edited anything, and nobody is told.
`uc.memory.date` had accepted this as *"eventual consistency over locking … no data loss"*; that held for the
derived `auto` range alone, which re-derives itself, and not for the place, the cover or a manual date, which do
not.

Two blind writes do the same damage without any concurrency: a blob that **does not open** was treated as
empty, so the next write re-sealed one field and dropped every sibling; and the media writers re-sealed from a
**normalized copy** that did not carry every field (the resolved place name was dropped by every date
correction). A set toggled before it loaded had the same shape: an item-mark or Memory-pin toggle wrote a
one-element set over the stored one.

## Decision

1. **A write that more than one account makes, and that re-sends fields its writer did not edit, carries the
   version it was built from.** Today that is exactly the two blobs above. Each has its own counter
   (`veyq_node.meta_version`, `veyq_media.meta_version`), bumped by the one write that replaces that blob and by
   nothing else. The server writes with a compare-and-set (`… where id = ? and meta_version = ?`) and, on a miss,
   answers **`409 stale_version` carrying the current blob and its version** (`StaleSealedMeta`) and writes
   nothing — a node update that also renamed is refused whole. A success stores `version + 1`, which the client
   derives, so no success response changes shape.
2. **The counter is dedicated.** `media_revision` moves on every attach, trash, restore, move and meta change; a
   compare-and-set on it would turn every concurrent upload into a date-edit conflict.
3. **The merge is the client's, and it re-applies only its own change.** Each writer is a function from the
   fresh blob to the next one (or to "no write"). On a `409` the client re-runs it on the conflict body's blob and
   retries, at most five times — the identity-pin loop ([en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md)).
   - An **editor** whose own field changed underneath it does not retry: it re-opens with the current value and
     says so in one line. A conflict on another field merges silently.
   - A **self-heal** re-runs its whole decision on the fresh blob, including *"a manual date is never
     overwritten"*, and writes nothing when that decision says so.
   - A **backfill** re-applies its one field, and writes nothing when the fresh blob already has it.
   - The **cover editor** is the one exception: once its save has moved the node's single cover-blob slot
     (an upload, whose commit replaces whatever blob the slot held), its own cover wins on the retry —
     re-opening on the peer's cover would show a cover whose blob that save just replaced. A switch **away** from
     an uploaded cover moves nothing before its write: it surfaces a peer's cover like any editor, and reclaims
     the slot only after the write has landed, and only when the cover that write replaced — read from the blob
     the server accepted it over, never from the editor's own copy — was an upload. The slot's delete takes
     whatever sits in it, so deciding from a stale copy deleted a peer's newer upload or left a replaced one.
4. **A writer never seals a blob it could not open, nor one rebuilt from a projection.** Only an **absent** blob
   is a fresh one; a present blob that does not open or parse is refused — a self-heal or backfill stops, an
   editor says so. Writers start from the raw opened blob and keep every field verbatim, known or not. Reads stay
   best-effort: a blob that does not open still displays as "no metadata".
5. **A whole-set write never starts from a set it has not loaded.** The item-mark and Memory-pin toggles refuse
   until their store holds a successful load for that node or scope.
6. **Every other edit is last-writer-wins on purpose, and says why in its contract.** Every PUT/PATCH and every
   POST whose `operationId` starts with an edit verb declares `x-veyq-concurrency: version` or
   `last-writer-wins` with a reason; `scripts/validate_write_concurrency.py` keeps a new edit from skipping that
   decision. The reasons today are two: the write carries only what its writer typed (a rename, a move, a role),
   or only one account writes it (a share is edited by its owner only, a profile by its holder).

## Consequences

- A member who opens a stale Memory no longer erases a peer's place or cover, and a self-heal never overwrites a
  peer's manual date. The price is one extra round trip on a conflict, and on a refusal the editor has to show it.
- The `409` body carries ciphertext the caller can already list, so operator-blindness is unchanged — the server
  learns that two writes raced, which the request timing already told it.
- A blob that never opens now blocks edits to that one Memory or media instead of being silently replaced. That
  is a corrupted or substituted blob (the key opened, the blob did not); refusing is the honest answer, and an
  explicit "reset" is not built.
- **Rejected: `If-Match` → `412`.** The repo's one ETag (`mediaRevision`) validates a read cache for a whole list;
  a per-item precondition header would be a second vocabulary beside the body `version` the pins already use and
  the generated clients already type.
- **Rejected: re-GET on conflict.** Neither entity has a single-item read — the node is listed with its whole
  tree, the media through the per-node delta — so the conflict body carries the current value instead.
- **Rejected: binding the seal to the version** ([ADR 0072](0072-a-seal-binds-its-object.md)'s pin form). It
  would detect an operator rolling a blob back, which is a different threat; the seal stays bound to its node or
  media only.
- **Rejected: a lock.** A lease the server holds across a client's think time is a lock nobody releases when a
  tab closes; a compare-and-set needs nothing held.
