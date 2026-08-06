# UC: Memory date — sealed, curated date

- **ID:** uc.memory.date
- **Primary actor:** A member with an **unlocked vault** who has a Memory (or chapter) open and wants to give it
  a **date**.
- **Goal:** Curate a Memory's **date** — a mode-switch (`auto` / `single` / `range` / `ongoing`) — **sealed under
  the Memory's `K_mem`** and shown on the Memory's **browse card** (cover + title + range, grid sorted by start
  date) and its detail header — **operator-blind** (the veyq server never sees a day).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_mem`** is held). The node model
  already carries per-node sealed fields (`encryptedName`, `wrappedContentKey`); this adds one more,
  `wrappedMeta`.
- **Postconditions (success):** The Memory shows its effective date; the user can switch the date mode and enter
  manual values. The server stored only an **opaque `wrappedMeta` blob** + the unchanged node shape.

## How `auto` is derived

The date's sealed **shape** + envelope (sealing under `K_mem`, versioning, `updateNode` / `listNodes` threading,
materialization on the node) is the enabler [en.memory.node-meta](../enablers/en.memory.node-meta.md). What this
use-case adds is the **`auto`** behaviour — the other three modes just carry the user's typed days:

- **`auto` counts only *trustworthy* capture times.** A media's `capturedAt` (cr-0066) is the EXIF
  `DateTimeOriginal` **or**, absent that, a **file-modification-date fallback**. A file date is not a real
  capture time — an edited photo whose EXIF was dropped would carry its *edit* date and wrongly stretch the
  range — so the capture-time tier records `capturedAtSource: 'exif' | 'file'`, and **`auto` includes a media
  only when its `capturedAt` is EXIF-sourced *or* the user set a `capturedAtOverride`** (an intentional
  correction). File-fallback media (and older source-less media) are excluded; a Memory with only such media
  has an empty `auto` range (set it manually). (The media grid still *sorts* by the effective `capturedAt`
  regardless of source — cr-0066 unchanged.)

- **`auto` is materialized by a full-list reconcile** — `min → from`, `max → to` (day-truncated) over the
  Memory's trustworthy `capturedAt` (the always-listed capture-time tier, [uc.memory.gallery](uc.memory.gallery.md)),
  re-sealed **only if the range changed**, at three settle points: an upload batch finishing, the Memory opening
  (the **self-heal**), and a media delete. (Aggregating a Memory *plus its chapters'* media is a later slice.)

## Main flow

1. **In the browse grid**, each Memory **card** shows its sealed range (cover + title + range), and the grid is
   **sorted by start date**. The range is read from the node's `wrappedMeta` (unsealed under that node's
   `K_mem`) — no media load.
2. With a Memory **open**, the header shows the same **effective date**, and the user **edits** it → a **mode
   switch** (`auto` / `single` / `range` / `ongoing`) reveals the fitting inputs: none for `auto`; one day
   picker for `single`; from/to for `range`; a start for `ongoing`.
3. On save, the client seals `{ v, date }` under the Memory's **`K_mem`** and PATCHes `wrappedMeta`; the header
   (and, next browse, the card) re-render. Choosing `auto` re-materializes the range from the current media.
4. **When media is added or removed** from an `auto` Memory, the client recomputes min/max over its media and
   re-seals `wrappedMeta` so the card stays correct (self-healed on the next open as a safety net).

## Alternate / exception flows

- **E1 — no dated media in `auto`:** the header shows a neutral "no date yet" state; adding dated media fills it.
- **E2 — vault locked / Memory not open:** without the node's `K_mem` the `wrappedMeta` can't be unsealed → the
  date is unavailable until unlock + open.
- **E3 — malformed / wrong-key `wrappedMeta`:** best-effort — treated as no manual date (falls back to `auto`),
  never a broken header.
- **E4 — invalid manual range (`to` < `from`):** rejected in the editor before sealing.
- **E5 — concurrent members change media (groups/tenant):** the materialized `auto` range is a **derived,
  self-healing** aggregate. Two members adding media to the same Memory at once can leave a card's range
  transiently too narrow (a blind last-write-wins on `wrappedMeta`); the **self-heal reconcile on the next open**
  (E-flow above) recomputes from the now-complete media list and corrects it. No data loss — the media stays
  correctly sealed; only the cached display range lags. (A later live-update/poll feature will surface others'
  new media into open grids and shrink the window further.)

## Quality notes

- **Operator-blind.** The veyq server sees the node shape + coarse timestamps + the **opaque `wrappedMeta`**
  blob; never a day. The date is sealed under `K_mem` and unsealed only on device. (The blob envelope +
  operator-blindness at the node level: [en.memory.node-meta](../enablers/en.memory.node-meta.md).)
- **`auto` reuses the capture-time tier** the gallery already lists (cr-0064/0066); it is materialized into
  `wrappedMeta` by a full-list reconcile at settle points (a small extra `updateNode` PATCH) so the browse grid
  can show + sort by the range without ever loading a Memory's media.
- **Eventual consistency over locking (deliberate).** The `auto` range is a derived, self-healing display value.
  Rather than add per-write optimistic concurrency (a blind server `media_rev` + compare-and-set), we accept that
  a concurrent-member add can briefly narrow a card's range and let the **open-time self-heal** correct it — no
  data loss, no operator-blindness impact. Materialize the range only **after an upload finishes**, never
  mid-upload. (If strict consistency is ever needed, the `media_rev` CAS is the blind-safe upgrade.)
- **Reuse the existing UI/UX — never invent.** The date picker + ISO formatting are existing components
  (`IsoDateField` / `memoryDateRange`); the 4-mode switch realizes the approved
  mockup (the pre-E2EE design carried only a flat start/end range).

## What this means for you / Was das für dich bedeutet

**EN —** Give a memory a **when**. The date can just **figure itself out** from your photos (earliest to latest),
or you can set a single day, a span, or an open-ended "since…". It's all locked with the memory's own key on your
device, so the server only ever stores scrambled data — it never learns the day you were there.

**DE —** Gib einer Erinnerung ein **Wann**. Das Datum kann sich **automatisch** aus deinen Fotos ergeben
(frühestes bis spätestes), oder du setzt einen einzelnen Tag, einen Zeitraum oder ein offenes „seit…". Alles wird
mit dem eigenen Schlüssel der Erinnerung auf deinem Gerät verschlüsselt; der Server speichert nur verschlüsselte
Daten und erfährt nie den Tag, an dem du dort warst.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Use-cases: [uc.memory.place](uc.memory.place.md) (the node's sealed `location`, sharing the same `wrappedMeta`),
  [uc.memory.browse](uc.memory.browse.md) (the node CRUD + `updateNode` this folds into),
  [uc.memory.gallery](uc.memory.gallery.md) (the capture-time tier that `auto` derives from),
  [uc.memory.chapters](uc.memory.chapters.md) (a chapter is a node too — it carries `wrappedMeta` the same way)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_mem seals "capture time, location"
  — this UC applies it at the node level)
- Future: the **aggregated** chronological date/gallery across a Memory + its chapters
