# UC: Memory live media updates — others' adds/removes surface into the open gallery

- **ID:** uc.memory.live-updates
- **Primary actor:** A member with an **unlocked vault** viewing a Memory's **gallery** in a **shared scope**
  (group / tenant) or a **contributor share**, while other members or contributors add or remove media in the
  same Memory.
- **Goal:** The open gallery **reflects others' media changes live** — newly attached media **auto-inserts** as
  tiles, removed media **drops out** — with **no manual reload**; and a re-open is **fast** because a cheap
  "nothing changed" check skips the media re-list. **Operator-blind** throughout (an opaque revision counter +
  opaque `mediaId`s; the server never sees content).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held to decrypt media). The
  gallery lists the node's **own** media ([uc.memory.gallery](uc.memory.gallery.md)).
- **Postconditions (success):** The grid stays current with the server's media set while open; re-opens that
  found no change did **no** media query (a `304`). The server stored/served only the opaque revision counter,
  the keyset delta, and the current `mediaId` set — no key, no plaintext.

## Main flow

1. **Open a Memory.** The gallery lists its media once (full list) and caches the media **plus** the node's
   the node's item revision (the ETag; core's, `veyq_node_items_revision`) and the newest `(updatedAt, mediaId)` as its cursor.
2. **While open, poll.** On an interval — **only while the tab is foregrounded** — the client re-requests
   `listNodeMedia` with `If-None-Match: <cached revision>`.
3. **Nothing changed ⇒ `304`.** The client does nothing; no media query ran server-side.
4. **Something changed ⇒ `200` + delta.** The client **upserts** the delta media by `mediaId` (new tiles
   auto-insert into the justified grid, laid out beside any optimistic upload tiles under the one ordering rule
   [uc.memory.gallery](uc.memory.gallery.md) owns, cr-0074), **drops** any
   cached `mediaId` no longer in the returned id-set (removals disappear), advances its cursor + revision, and
   re-sorts chronologically.
5. **Close / navigate away ⇒ stop polling.** The interval + visibility listener are torn down.

## Alternate / exception flows

- **E1 — active upload by another member:** each poll returns only the **few new items** since the cursor (the
  keyset delta), not the whole gallery — the grid fills in incrementally, cheaply, as the upload streams.
- **E2 — a delete (share delete-own, cr-0102) or a net-zero delete+add:** caught by the **id-set** in the `200`
  (a *set*, not a count) — the removed tile drops, any concurrent add inserts.
- **E3 — tab backgrounded:** the poll **pauses** (visibility-gated) and **ticks once immediately on return** to
  foreground, so a returning viewer catches up at once without polling in the background.
- **E4 — vault locked / Memory not open:** no `K_node`, no polling — live updates only run for an open, unlocked
  gallery.
- **E5 — a media that fails to decrypt (wrong/rotated key, corrupt):** best-effort — that tile is skipped, never a
  broken grid; the rest render.
- **E6 — the poll request errors / offline:** back off and retry; the last-rendered grid stays; recovery re-syncs
  on the next successful poll (the revision compare makes recovery a single round-trip).

## Quality notes

- **Operator-blind.** The server sees an **opaque counter** + opaque `mediaId`s + exact timestamps (a medium's
  change time, to the millisecond) + the poll access-pattern it already sees on reads — never content, never a key. No new operator-visible signal. (The full
  rationale + the rejected alternatives: [ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md).)
- **Cheap idle, cheap active.** Idle re-opens/polls are an **O(1)** counter compare → `304`; an active upload is a
  **keyset delta**, not a full re-list. Neither re-serializes the whole gallery — the failure mode a naïve
  poll-then-full-list would hit.
- **Delete-robust by an id-set, not a count.** When a delete happens the server returns the current `mediaId` set;
  the client diffs it to drop removals and catch net-zero delete+add — a **count** cannot see a delete+add that
  nets to zero, which is the whole reason this is a set. It costs no per-tick O(N) hash and does not ride pure
  adds/edits (a second `delete_revision` counter gates it —
  [ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md)).
- **Tightens the `auto`-date self-heal.** New media surfacing into an open grid lets the cr-0067 `auto`-date
  reconcile ([uc.memory.date](uc.memory.date.md) E5) see others' additions sooner — the same item revision the
  date use-case foreshadowed as the blind-safe upgrade.
- **Reuse the grid — never invent.** Auto-insert reuses the optimistic-tile grid + `mergeItems` reconcile
  (cr-0074); the poll loop is the client's one `createVisibilityPoller` (`web/src/shared/state/`: interval +
  visibility gate, no overlapping ticks, nothing applied after a stop).
- **Deferred (out of scope):** long-poll / push for sub-second latency (shared later with the live journal, #6);
  a **subtree** (Memory + chapters) aggregated revision (the gallery is own-node); a keyset delta is add/edit-only
  by design, deletes ride the id-set.

## What this means for you / Was das für dich bedeutet

**EN —** When you're looking at a shared memory and someone else adds photos — or removes theirs — you **see it
happen**, no refresh needed. Re-opening a memory that hasn't changed is instant, because your device just asks
"anything new?" and hears "no". As always, it's all sealed with the memory's own key on your device; the server
only ever moves scrambled data around.

**DE —** Wenn du eine geteilte Erinnerung offen hast und jemand anderes Fotos hinzufügt — oder eigene entfernt —
**siehst du das live**, ganz ohne Neuladen. Eine unveränderte Erinnerung erneut zu öffnen geht sofort, weil dein
Gerät nur kurz fragt „gibt's was Neues?" und ein „nein" hört. Wie immer ist alles mit dem eigenen Schlüssel der
Erinnerung auf deinem Gerät verschlüsselt; der Server bewegt nur verschlüsselte Daten.

## Related

- Feature: [Memory](../_index.md)
- Use-cases: [uc.memory.gallery](uc.memory.gallery.md) (the media list this keeps live), [uc.memory.date](uc.memory.date.md)
  (its `auto` self-heal that the same item revision tightens)
- Decisions: [ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md) (the revision counter +
  conditional GET + keyset delta + delete-reconcile, short-poll v1), [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md)
  (the media transport the tiles fetch through)
- Future: long-poll / push (sub-second, shared with live journal co-edit #6); Memory+chapters aggregated live grid
- End-user view (docs site): [Look through the gallery](../../../../site/src/content/docs/memories/the-gallery.mdx)
