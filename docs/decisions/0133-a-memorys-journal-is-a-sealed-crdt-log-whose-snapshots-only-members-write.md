# ADR 0133 — A Memory's journal is a sealed CRDT log whose snapshots only members write

- Status: Accepted
- Built: no
- Date: 2026-11-13
- Model: [Memories: the live journal](../apps/journal.md)

## Context

The owner wants a **journal** in Memories (inbox #18): the text of a Memory and of each Chapter, written in markdown,
with the Memory's photos, Chapters, map and GPX tracks embedded, and written by several people **at the same time**
(#6), not one writer after the other. The server is operator-blind: it stores and relays sealed bytes it cannot read
and cannot merge, so every merge, every snapshot and every rendering happens in a client. Built today: the safe
markdown renderer (`src/web/src/shared/markdown.ts`, the sanitizer of the Trusted Types policy, [ADR 0117](0117-the-web-client-enforces-trusted-types.md))
and [ADR 0033](0033-live-media-updates-revision-polling.md)'s poll, the platform's only live mechanism. The
earlier, pre-E2EE veyq (`raddatzk/veyq-no-e2ee`) had a journal per Memory and Chapter with two projections — a
markdown body every reader used and a Yjs log editors appended to — and embeds as tokens in the text. The concept
([docs/apps/journal.md](../apps/journal.md) in its concept form, refined with the owner on 2026-11-13) weighed the
options; this record keeps the choices and why. Core's live stream, which the journal is the first user of, is
[ADR 0134](0134-core-pushes-a-lists-new-position-to-the-clients-that-edit-it-on-a-stream.md); a link guest's
evidence row per writing session is [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10.

**Measured** for this record (2026-11-13, throwaway scripts outside the repository, Node 23.11; `yjs` 13.6.33,
`loro-crdt` 1.17.1, `@automerge/automerge` 3.5.0, `@y/y` 14.0.0-rc.29):

- One typing session of 20,000 characters flushed every 5 keystrokes and sealed with AES-256-GCM: a sealed Yjs
  update is 112 B median (123 B p99), Loro 119 B, Automerge 568 B; the Yjs snapshot 57 KiB (Loro 22 KiB), a
  compaction 18 ms (Loro 70 ms), opening a snapshot 2 ms. At 100,000 characters the Yjs snapshot is 325 KiB and its
  compaction 83 ms. Web cost gzipped: Yjs 28 KiB plus lib0, Loro 1.2 MiB of wasm, Automerge 1.15 MiB.
- `@y/y` 14.0.0-rc.29 applies Yjs 13's update-v1 bytes, one by one and as a state, and its own re-encoding reads back
  in 13 to the same text; its `mergeUpdates` of 13's updates too. So starting on v13's update format does not strand
  the stored log at the v14 move (the v14 text API differs; the format does not).
- `Y.encodeStateAsUpdate` gives the **same bytes** for two documents that applied the same 301 updates of two
  interleaving writers in order, in reverse, or through an intermediate snapshot — so a snapshot can be checked byte
  for byte (decision 4).
- **A withheld delete goes unnoticed by Yjs:** a delete-only update carries no struct (`decodeUpdate`: 0 structs, a
  delete set), so a receiver that misses it holds no pending state and shows the deleted text; a withheld insert is
  held pending (`store.pendingStructs`). So gap detection is ours (decision 5).

## Decision

### 1. One journal per Memory and one per Chapter

Each Memory and each Chapter has **one journal**, keyed by its node id (owner 2026-11-13). A Chapter is a day or a
stage, its text is its own, a document stays small, and a report reaches one of them. A journal is a function of
Memories, not an application: its tables are Memories', its switch `memories.journal`
([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)). A Memory's journal and its
Chapters' journals are separate documents; nothing joins them on the server.

**A Memory's view that shows its Chapters' journals together** (the owner's "merging them upward") is **a later
slice, not the first cut** (inbox #853): a read-only page under the Memory's own journal, each Chapter's text in the
order the Chapters tab shows them, headed by the Chapter's name and linking to its journal for editing. It needs no
change to this model, because each journal is readable on its own (decision 6) under its own node's key, which
whoever reads the Memory holds for its Chapters (a member; a guest of the Memory's link — a guest of one Chapter's
link reaches only that Chapter). It is left out of the first cut because it has questions of its own that the single
journal does not answer — one read per Chapter or one batched read, a live view of N journals at once (ADR 0134's
stream subscribes to a bounded set), and how search hits land in it — and the first cut proves the single journal.
**Rejected:** one document per Memory with a section per Chapter — a report or a Chapter's link would reach every
Chapter's text, and a long trip would be one ever-growing document; dated entries (a diary of days) — a Chapter is
already the day.

### 2. The text is one Yjs `Y.Text` of markdown; Yrs in the native apps

A journal is **a CRDT document whose content is the markdown text itself**, one `Y.Text`. The web client uses
**Yjs**; the native apps use **Yrs** (the Rust port, the same wire format) through a UniFFI binding we keep (inbox
#855). Yjs's **update format v1** is the stored format.

- **Why Yjs:** by far the smallest web cost, the most editor bindings, and the one format three independent
  implementations speak (yjs, yrs, y-octo) — the vendor-neutral choice our dependency rule prefers over a single
  project's format. Proton Docs runs it end-to-end encrypted in production.
- **Why one text, not a rich-text tree:** a native app binds a plain-text CRDT to a text field by diffing the field;
  no native rich editor with a CRDT binding exists for Android, so a rich-text tree would leave the apps unable to
  edit. The stored text is also readable forever without any CRDT library (decision 6).
- **Rejected:** **Loro** (the strong second: one Rust core everywhere, an official Swift package, smaller snapshots —
  for 1.2 MiB of wasm on the web and one implementation of its format); **Automerge** (update size, web weight, a beta
  editor binding); **operational transformation** (needs a server that transforms plaintext operations); **a sealed
  document with compare-and-set** (one writer at a time, which the owner ruled out).

### 3. A sealed log of updates and snapshot revisions with keys of their own

In Memories' own tables, keyed by the journal's node:

- **A snapshot revision** — its number, the scope epoch its key was minted at, its key `K_jrev` (random, wrapped under
  the node's `K_node`, bound to scope, node and revision — the shape of an Expenses revision's `K_rev`,
  [ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 4), the position of the last
  update it folds in, its writer, its time, and two seals under `K_jrev`: the **CRDT state** (`encodeStateAsUpdate`,
  v1) and the **projection** (the markdown as it stands, with its embed tokens). Each is padded to a power-of-two size class; the contract caps the state at 4 MiB and the projection at 1 MiB.
- **An update** — the snapshot revision it belongs to, its **position**, its writer (a member, or a link's guest
  identity), its time, and the sealed update under `K_jrev`, its object bound in the AAD (journal, snapshot revision,
  a client-minted update id; [ADR 0072](0072-a-seal-binds-its-object.md)). Padded to size classes (256 B, 1 KiB,
  4 KiB, 16 KiB, 64 KiB); 64 KiB is the cap, so a long paste is several updates.
- **A contributor** — one row per writer who ever wrote an update to the journal (decision 9), with its ordinal in
  the order they first wrote. A member is served the writer ids; a link guest only the ordinals, which stand for a
  writer wherever the guest's client needs one (decision 5), each with the writer's **alias** (decision 9), so a link
  is served no member id ([ADR 0030](0030-contributor-public-share.md) point 8).
- **The alias key** — 32 random bytes the server mints with the journal's first contributor row and keeps in a row of
  its own beside them; never served to anyone, deleted with the journal (decision 9).
- **The position is core's.** The journal is a second list in its node beside the gallery's media, and ADR 0033 left
  exactly that open ("a second list in one node would add a `list` column to the key — not built until a kind needs
  it"): core's `veyq_node_items_revision` gains a list key, the gallery keeps its list, and the journal's updates and
  snapshots bump the journal's. Positions then become visible in commit order as ADR 0033 decision 3 holds, the
  journal's read is that ADR's conditional read (a `304` while nothing moved), and ADR 0134's stream announces a
  position without knowing a journal. **Rejected:** a counter of Memories' own — ADR 0033's Consequences reject a
  counter per application for the first-bump race and the erasure ordering each would re-implement; bumping the
  gallery's revision — every keystroke-second would turn every open gallery's poll into a `200`.
- **A journal update bumps no scope tree revision** ([ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)):
  otherwise every member's level view would re-read once a second while someone types.

Each sealed update's plaintext is our envelope around the Yjs update: the writer's Yjs client id, **a sequence number
per client id without gaps**, and the update itself (decision 5).

### 4. Members' clients compact; the server takes snapshot `n + 1` only over `n`

A client merges the newest snapshot revision with the updates past its folded position (its **tail**); from time to
time a **member's** client writes a new snapshot revision that folds the log in. A link guest's client never does: a
snapshot is the one write that drops earlier states. A member's client compacts when it opens a journal whose tail
holds more than 500 updates (Proton squashes past 500), when it closes the editor after writing, and as the first
write after a scope rotation (decision 11) — jittered, so two members opening at once rarely both try.

- **The race.** A snapshot names the snapshot revision it continues; the server takes `n + 1` only over `n` and
  answers anything else `409 stale_revision`, the platform's compare-and-set. Two compactors: one wins, the other
  drops its snapshot and reads the winner's. Updates keep landing while a compactor works: the server takes updates
  for `n` until `n + 1` exists and answers later ones `409`; the client re-seals its unsent updates under the new key
  (CRDT updates are idempotent, so a re-send is harmless). Updates of `n` past the new snapshot's folded position stay
  and are served with it as its tail, so no edit is lost to a race and no compactor must win against live typing.
- **Each snapshot is checked by the next reader that holds its predecessor.** Its state must equal, **byte for
  byte**, the predecessor's state plus the updates up to the folded position (measured: the encoding is
  order-independent). A client that finds a difference says so, names the snapshot's writer (the server's
  attribution), and offers a member the restore of decision 8. A member's client checks the snapshot it continues
  before compacting over it, so it never folds a forged state forward unawares.
- **A snapshot that does not open** is skipped: a client opens the newest snapshot revision it can decode and replays
  every kept update after it, which the kept revisions (decision 8) make possible.

### 5. Order, gaps and writer ids are checked by the client

- **Every client applies updates in the server's order.** A Yjs writer's id is chosen by its client; a writer who
  reused another's id with different content would make clients that see the two in different orders diverge for
  good. Applying in one order makes them diverge identically, that is, not at all, and a client pins each Yjs client id
  to the server-attested writer (for a guest's client, the writer's contributor ordinal) of the first update that used
  it and drops a later update that reuses it from someone else.
- **A withheld update is found by its writer's sequence.** Yjs alone notices a withheld insert (the next one waits
  pending) but not a withheld delete (measured, Context), so each update's envelope carries a sequence number per Yjs
  client id without gaps, and a client that sees a gap says the journal is incomplete and keeps the later updates
  pending. **Residual:** the newest updates held back look like a stale read — the platform's freshness limit, stated,
  not closed ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 5's wording).
- **An update that does not decode** is skipped and marked; the rest of the journal stays usable.

### 6. Who reads what

- **The web client always merges** the snapshot state and its tail: Yjs is 28 KiB and loads only when a journal
  opens, so a member, a `READ` guest, a `READ_WRITE` guest and a moderator all see the text as it stands.
- **What reads the journal as text** — search (decision 13), a Memory's export (decision 12) and a native app before
  its binding ships — reads the **projection** of the newest snapshot revision and needs no CRDT library. The price: a
  projection lags by its tail until the next snapshot; compacting when an editor closes keeps that short.
- A `READ` guest reads on ADR 0033's poll with that ADR's decision 7 tag of its own; only those who edit hold ADR 0134's stream.
  A `WRITE_ONLY` link has no journal: a CRDT writer must read the state it edits.

### 7. Members and `READ_WRITE` link guests write; only members compact and restore

A `READ_WRITE` guest edits the journal as a member does (owner 2026-11-13; a share view is the member view with
functions removed, inbox #401). It does not compact, restore, or read earlier snapshot revisions. Its updates go
through core's guest gate, are guest writes in [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 10's record — one row per writing session, not per update — and are reportable with a hold copy. The guest's page
shows § 10's first-layer sentence, as every guest write page does.

### 8. Earlier snapshot revisions are kept for the trash retention, and a member restores one

Superseded snapshot revisions and their updates are **kept for the platform's trash retention**
([ADR 0061](0061-node-deletion-reversible-node-trash.md), `veyq.node.trash.retention`), counted from when the next one
superseded them; past it the server deletes them, a structural job on rows and times it already sees
([ADR 0021](0021-operator-blind-background-tasks.md)). The current snapshot revision and its predecessor are kept
whatever their age, so the check of decision 4 always has a predecessor; and a snapshot revision that holds a link
guest's update is kept no shorter than the guest-write record's N, so the evidence row of a guest's session always has
the updates whose times it covers (ADR 0093 § 10).

**A member restores** an earlier state (owner 2026-11-13): the history lists the
kept snapshot revisions with their time and writer, a member opens one's projection, and **Restore** writes the
difference between the current text and that one as an ordinary edit, then compacts. A restore is therefore a
change like any other: concurrent editors' updates still merge into it, it bumps the position, and the snapshot
revision before it stays kept, so a restore is undone by restoring that one. A `READ_WRITE` guest neither sees the
history nor restores.

This is the defence against what the CRDT cannot prevent: a **vandal** — a guest of a leaked link, or anyone with write
— deleting the text with valid updates; and a **broken or forged snapshot** (decision 4). **Residual, stated
plainly:** every kept snapshot revision is text a report of the node, a leaked `K_node` or an excluded party who held
an old `K_jrev` can still reach, for up to the trash retention after it was replaced — the price of being able to go
back. A member who wants a passage gone from the history waits for the window or deletes the node. A member who wants
to destroy the text can, within the window as for every node they may trash and purge; the restore protects against
guests and broken clients, not against the members of the space.

**Rejected:** no restore, with a guest's session undone through the CRDT and only the predecessor kept — cheaper in
retained text, but it protects only while the vandalism sits in the last two snapshot revisions, which two editor
closes can push out, and leaves a forged snapshot that a later honest compaction folded forward unrepairable; a
longer window than the trash retention — more kept text for a rare need.

### 9. Who wrote it: a contributors list, never passage colouring

A journal shows **who has written in it** (owner 2026-11-13): members by their display name and guests by the name
their identity gave itself, for members; a guest is shown no name, as everywhere ([ADR 0030](0030-contributor-public-share.md)
point 8). The list is the journal's contributor rows (decision 3), which the server writes with each writer's first
update and keeps as long as the journal: it knows every update's writer anyway, as it knows every Expenses revision's.
**Rejected:** sealing the list in each snapshot — whatever is sealed under the journal's keys a link guest can read, and
it would carry member ids. It is the server's attribution, never claimed as proven:
there is **no signature per update** — a seal under `K_jrev` proves a key holder wrote it, a signature could not
survive compaction (a snapshot is the compactor's, not each writer's), and content writes are unsigned platform-wide
today; signing with the identity key ([ADR 0028](0028-identity-authenticity-tofu-verification.md) decision 6) is the
platform's later step, and the journal takes it with everyone else. An erased account stays in the text and in the
list as a former member, as an Expenses entry does (ADR 0108 decision 2). **Rejected:** colouring passages by writer —
it would show an unproven attribution at the finest grain, and keeping it costs per-item writer data the projection
does not carry.

**A guest reads pseudonyms, never numbers** (owner 2026-11-13). On a link guest's page — the contributors list, and
"who is here" ([ADR 0134](0134-core-pushes-a-lists-new-position-to-the-clients-that-edit-it-on-a-stream.md) decision 4)
— every other person, member or guest, is a pseudonym in the way a shared whiteboard names an anonymous visitor:
**"Anonymous Fox"**, **"Anonyme Eule"**; the guest's own row is **"You"**. A member's page is unchanged: members by
name, guests by the name they gave. So a guest never learns a member's name or another guest's through the journal,
and still tells the writers apart and sees the same person in both places.

- **Derived from an alias the server computes, named by the client.** A writer's **alias** in a journal is the first 8
  bytes of `HMAC-SHA-256(alias key, writer kind ‖ writer id)` — the journal's alias key (decision 3), the writer a
  member or a link identity. The server serves it to a link guest beside each contributor's ordinal and for each other
  present editor (ADR 0134 decision 4), and marks the guest's own; a member is served none (a member's client names
  people). The guest's client turns an alias into a name from a fixed list in the client, **EN and DE**: 64 animals,
  each with its German gender, so the adjective agrees ("Anonymer Fuchs", "Anonyme Eule", "Anonymes Pferd"), the
  animal the alias's first two bytes modulo 64; the avatar's colour comes from the alias too.
- **Stable per person within the journal, unlinkable across journals.** The same writer has the same alias in a
  journal for its whole life — across visits, reloads, restores and the server's restarts — because it depends only on
  the stored key and the writer. Another journal has another key, so a guest of two links cannot tell that one person
  wrote in both. Keyed, not a plain hash: a guest who knows a member's id elsewhere (a member of the tenant who also
  holds a link) cannot test it against an alias. An import starts a fresh journal and so a fresh key.
- **Collisions are numbered by the client.** Two aliases with the same animal in one journal read "Anonymous Fox" and
  "Anonymous Fox 2", numbered in the order of their contributor ordinals, present editors who have not written after
  them by alias. A writer's suffix never changes once it wrote (a later writer only appends); a present editor's may,
  once it writes. With 64 animals, about one journal in seven with five people sees a suffix (1 − 63·62·61·60 / 64⁴ ≈ 0.15).
- **The server learns nothing new.** It already knows every writer and every stream holder; the alias is computed from
  those and a key it minted, and the word list lives only in the client — the server never handles a pseudonym.
  **Rejected:** deriving the name in the client from the ordinal — a present editor who has not written has none, and
  an ordinal minted at the first stream opening would store who opened a journal; a plain hash of the ids — testable
  by anyone who knows an id; numbering the guests as today — what the owner asked to replace.

### 10. Embeds are tokens inside the sealed text

The old syntax, inside the sealed text, so the server never learns which photo a passage shows:

- `::media:<id>[,<id>…] <label>` — media of the same Memory or Chapter;
- `::chapter:<id>` — a Chapter of the same Memory;
- `::map:` — the node's map, as its Map tab draws it;
- `::track:<id>` — a GPX track, which is a medium ([ADR 0130](0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md)
  decision 1), drawn as its line on a map.

Rendering goes through the safe renderer, extended with the tokens. An embed a viewer cannot open — a parent's photo
in a Chapter's journal seen through the Chapter's link, a removed medium — renders as a placeholder. An application
never embeds another's object ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)). A map or
track embed draws under the one map consent per browser ([ADR 0016](0016-geocoding-provider-neutrality.md),
[ADR 0018](0018-map-tiles-provider-neutrality.md)) and is a placeholder without it.

**The editor** (owner 2026-11-13) is markdown source with a live preview in place: headings styled, embed tokens drawn
as cards where they stand — on the web CodeMirror 6 with `y-codemirror.next` (both MIT, maintained), the form the
native apps can edit too. Rejected: WYSIWYG and blocks (a rich tree, decision 2).

### 11. Against the platform's machinery

- **Scope rotation** ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)): the re-key re-wraps every
  kept `K_jrev` under the new `K_node` and changes no sealed body (its decision 3). A `K_jrev` minted before the
  rotation is one the excluded party may hold, so **no update lands under it**: an update to a snapshot revision
  minted below the scope's current epoch is refused (`409`, its decision 6), and a member's client starts a new
  snapshot revision under a fresh key — a rotation is a forced compaction, not a re-sealing of the log. Until a member
  compacts, a guest's update waits, as a guest's create waits on `node_rekey_pending`.
- **Trash and erasure:** the journal lives and goes with its node (trash, restore, purge).
- **The switch** (`memories.journal`, ADR 0088): off — no update, snapshot or restore; reading kept. Memories off —
  the same, then erased with Memories past the window.
- **Reports and holds** ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)):
  a report of the journal is an `ITEM` whose report share carries the current snapshot revision's `K_jrev` and its
  predecessor's while a tail of it is served — the moderator reads the text as it stands plus the edits since; a
  report of the node carries `K_node` and reaches every kept snapshot revision. The hold copies the reported snapshot
  revisions and their updates (§ 4). A content block refuses every later update, snapshot and restore with `451`.
- **Bounds the server enforces on what it sees:** the update and snapshot caps (decision 3), a rate per writer, and a
  **cap on the tail** — past it the server refuses updates until a member's client compacts, so a guest cannot fill
  storage. The journal's rows are **counted in the tenant's storage usage** as Memories' term
  ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) § 4): unlike a sealed field, a journal grows,
  so the contract's caps alone do not bound it.

### 12. Export and import

A Memory's export ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md))
carries each journal as markdown — the text as the exporting client merged it, each embed token rewritten to the
exported file it names — in `contracts/export/memories/content.schema.json`; no CRDT state and no earlier snapshot
revisions. An import starts a fresh journal from that markdown, the importer its first writer and only contributor.

### 13. Search reads the projection

[ADR 0125](0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md) stage 8b indexes each journal's
**projection** — one sealed text per journal, no replay — keyed by its snapshot revision; a journal being edited is
found by the text of its last snapshot. Guests have no search.

## Consequences

- **What the operator learns**, per journal: that it exists (rows under a node), the number of snapshot revisions with
  their times, size classes and writers, every update's time to the millisecond, size class and writer — so **who
  writes when, and roughly how much** (with a flush per second, the number of updates is about the seconds of typing)
  — and, with ADR 0134's stream, who has it open for editing. Not the text, not which passage, not the embeds. Minimised
  by a flush tick of at most one update a second, the size classes and no time stored without a reader. **Not
  minimised:** cover traffic (empty updates on a tick) would hide whether someone types but not that the journal is
  open, for a row a second per open editor — rejected, the residual named.
- **New in core:** a list key on the node items revision. **New in Memories:** four tables (snapshot revisions, updates, contributors, the alias key), the journal's operations
  on the member and the share API, and its term in the storage usage. **New dependencies (web):** `yjs`,
  `y-codemirror.next`, CodeMirror 6.
- **Kept text:** the trash retention's worth of earlier snapshot revisions per journal (decision 8's residual).
- **A long offline edit** merges like any other; the CRDT guarantees convergence, not that two people's paragraphs
  read well together. Unsent updates live in the open tab only: the web client has no local storage for content, so
  surviving a reload is the native apps' job (#438).
- **The applications' append-only gap** the security model asks every such application to state
  ([security-model](../crosscutting/security-model.md#applications-are-compiled-in--never-loaded)) is decision 5's:
  dropped updates are found, the newest withheld ones are not.
