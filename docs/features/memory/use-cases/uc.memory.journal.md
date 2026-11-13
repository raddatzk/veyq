# UC: Memory journal — write a Memory's or a Chapter's text together, live

- **ID:** uc.memory.journal
- **Status:** **planned** ([ADR 0133](../../../decisions/0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md),
  its live delivery [ADR 0134](../../../decisions/0134-core-pushes-a-lists-new-position-to-the-clients-that-edit-it-on-a-stream.md),
  a guest's evidence row [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10). Nothing is built.
- **Primary actor:** Anyone who may write the Memory or Chapter on screen: a member who may write its scope, or a guest
  of a `READ_WRITE` link onto it. **Secondary:** a guest of a `READ` link, who reads it; other writers with the
  same journal open at the same time.
- **Goal:** Write the Memory's (or Chapter's) story in markdown, with its photos, Chapters, map and tracks embedded,
  together with others at the same time — each sees the others' edits within a second — while the server stores and
  relays only sealed updates it cannot read.
- **Preconditions:** The node's `K_node` is held (a member's scope key, or the link's key); the scope is not pending
  deletion; Memories and `memories.journal` are switched on (to write); the node is not blocked by a moderator.
- **Postconditions (success):** Every writer's edits are in the journal's sealed log, every client with it open shows
  the same text, and the journal's newest snapshot revision (once a member's client compacts) holds the text as its
  projection and its CRDT state.

## Main flow — write together

1. With a Memory or Chapter open, the user opens its **Journal**. The client reads the newest snapshot revision and the
   updates past it, opens them under their `K_jrev`, merges them, and shows the text in the editor: markdown source
   with a live preview in place — headings styled, embeds drawn as cards.
2. The user types. At most once a second the client seals what changed as one update (under the snapshot revision's
   `K_jrev`, with its sequence number) and sends it.
3. Another writer with the journal open receives the change within a second (ADR 0134's stream announces the position;
   its client reads the update past its cursor) and sees it merged into their text; both edit on, and both texts
   converge.
4. The user adds an embed from the editor's menu — photos of this node, a Chapter, the map, a track — which inserts its
   token; the preview draws it as a card.
5. When the user closes the editor after writing (or opens a journal with a long tail), a member's client checks the
   newest snapshot against its predecessor, writes a new snapshot revision under a fresh key, and the log past it
   becomes the next one's tail. Nobody notices: the text does not change.

## Alternate / exception flows

- **A1 — a `READ_WRITE` link guest writes** exactly as a member does (steps 1–4), with no compaction (step 5), no
  history and no restore; its page shows the guest-write record's first-layer sentence. The server keeps one evidence
  row per writing session (ADR 0093 § 10).
- **A2 — a `READ` link guest reads** the journal as it stands, with its embeds; it picks up others' edits on the
  poll, not live. A `WRITE_ONLY` link shows no journal.
- **A3 — who is here:** the editor shows who else has the journal open for editing — members by name, guests by their
  guest name, for members; a guest sees only how many others are there.
- **A4 — contributors:** the journal lists who has written in it — the server's attribution, never claimed as proven;
  a member reads names, a guest none; an erased account shows as a former member.
- **A5 — history and restore (members):** the history lists the kept earlier snapshot revisions with their time and
  writer; a member opens one, reads it, and **Restore** turns the text back into it as one more edit, which the others
  see as any edit and which is undone by restoring the revision before it. Revisions older than the trash retention
  are gone.
- **A6 — a snapshot that does not match:** a client that holds the predecessor finds the newest snapshot differs from
  the predecessor plus its updates, says so with the snapshot's writer, and offers a member the restore (A5). A snapshot
  that does not open is skipped: the client opens the newest one it can and replays the kept updates after it.
- **A7 — a gap:** a client that misses an update of some writer (its sequence jumps) says the journal is incomplete and
  holds that writer's later updates until the missing one arrives; an update that does not open is skipped and marked.
- **A8 — two compactors at once:** the second snapshot is refused (`409 stale_revision`); its client drops it and reads
  the winner's. An update sent under a revision that has just been replaced is refused (`409`); the client re-seals it
  under the new key and sends it again. No edit is lost.
- **A9 — a scope rotation:** an update under a key from before the rotation is refused; a member's client compacts
  under a fresh key first, and writers carry on under it; a guest waits for that.
- **A10 — export and import:** a Memory's export carries each journal as markdown, every embed token pointing at the
  exported file it names; an import starts a fresh journal from it, the importer its only contributor.
- **A11 — search:** a member's search finds a journal by the text of its newest snapshot revision.
- **A12 — a Chapter's journal seen through the Chapter's link:** an embed of the parent Memory's photo is a placeholder.
- **A13 — offline:** edits made while the connection is down stay in the open tab and are sent when it returns; they
  merge like any other. A reload before that loses them.
- **E1 — switched off:** with `memories.journal` (or Memories) off, the journal is read-only: no edit, compaction or
  restore; it stays readable.
- **E2 — blocked:** a moderator's content block of the node or the journal refuses every update, snapshot and restore
  (`451`).
- **E3 — the tail is full:** past the tail's cap the server refuses updates until a member's client compacts; a guest's
  editor says it waits for a member.
- **E4 — vault locked / key not held:** nothing is read or written.

## Quality notes

- **Operator-blind.** The server stores sealed updates and snapshots it cannot read and cannot merge; it learns who
  writes when and roughly how much, and who has a journal open for editing — never the text, the passage or an embed
  (ADR 0133 Consequences, ADR 0134 decision 7).
- **Convergence is the client's.** Every client applies updates in the server's order and pins each writer id to its
  server-attested writer (ADR 0133 decision 5).
- **No time pressure in the text:** a flush is at most one update a second, padded to a size class.

## User impact

The story of a trip lives beside its photos, and the people who were there write it together at the same time, as in a
shared document, without the server being able to read a line of it.

## Related

- Feature: [Memory](../_index.md)
- Concept and model: [Memories: the live journal](../../../apps/journal.md)
- Enablers: [en.moderation.guest-write-record](../../moderation/enablers/en.moderation.guest-write-record.md) (the
  guest's session row), [en.memory.media](../enablers/en.memory.media.md) (the media an embed names)
- Use-cases: [uc.memory.chapters](uc.memory.chapters.md), [uc.memory.tracks](uc.memory.tracks.md),
  [uc.memory.live-updates](uc.memory.live-updates.md) (the poll a `READ` guest reads on),
  [uc.platform.search](../../platform/use-cases/uc.platform.search.md),
  [uc.platform.export-import](../../platform/use-cases/uc.platform.export-import.md)
- Decisions: [ADR 0133](../../../decisions/0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md),
  [ADR 0134](../../../decisions/0134-core-pushes-a-lists-new-position-to-the-clients-that-edit-it-on-a-stream.md),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10
