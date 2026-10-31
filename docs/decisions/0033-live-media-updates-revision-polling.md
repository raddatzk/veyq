# ADR 0033 — Live item lists: a per-node revision counter + conditional GET + keyset delta, short-polled

- Status: Accepted
- Built: yes
- Date: 2026-07-09
- Model: [Applications § How an application publishes a revisioned list](../apps/README.md#how-an-application-publishes-a-revisioned-list)

## Context

An open Memory gallery should reflect **other members' media changes live** — a contributor's uploads appear,
a removed member's photos disappear — without a manual reload (inbox #48). Two coupled needs: a **cheap
"did it change?"** check (fast opens, no work when idle) and **live insert/remove** of others' changes. The
server is **operator-blind**: it sees opaque `mediaId`s + node membership + exact timestamps (a medium's
`updated_at` to the millisecond), never content.

**This is the platform's mechanism, with Memories' media as its first user** (inbox #410). The counter, the
conditional read and the delete-gated id set are written once, in core, for any application that keeps a list of
**records** (not nodes) inside a node; the text below says "media" because that is the list that exists, and says
where core's part ends and the application's begins.

The obvious shape — and the one an earlier version of this product shipped — is a **hanging long-poll** carrying
a **keyset cursor** `(updatedAt, id)` for adds/edits **plus a SHA-256 hash over the sorted `mediaId` set** for
deletes (a count would miss a delete+add that nets zero; a set-hash catches it). It works and is real-time, at a
real cost: the server recomputes that **O(N) id-scan + hash on every ~750 ms tick, per open gallery**, and holds
a connection per viewer. We want the same **delete-robustness** without the per-tick O(N) cost, and simpler.

When this was decided the gallery's list had none of the pieces: it returned every medium with no timestamp, no cursor
and no conditional, an in-place meta edit touched no clock, and the node carried no change signal.

## Decision

**A per-node media-revision counter is the cheap change signal; a conditional GET short-circuits the idle case;
on a change the client pulls a keyset delta + reconciles deletes against the returned id-set. Short-poll v1.**

1. **The node's item revision — a per-node counter, the cheap signal.** A monotonic number per node, kept by
   **core** (no row reads as 0, the first change births it) and written only through core's bump, which the writer
   calls **in its own transaction** as it changes a record. For Memories' gallery it is **bumped on every media
   mutation**: an attach (a member's upload and a link guest's), a meta edit, a delete (the trash and a guest's
   delete of its own), a restore, and a re-bucketing that changes a row's bucket. **Cover changes are excluded** — a
   cover is core's node attachment (`setNodeCover` / `removeNodeCover`), which writes no media row and is never
   listed, so a cover swap must not force a re-list. Reading it is **O(1)** — this is what replaces the O(N)-per-tick
   hash. It is opaque operational metadata (a number), so it is **operator-blind**.

2. **Conditional GET on the list.** The response carries an **ETag derived from the revision**; a poll
   sends `If-None-Match`. The wire pieces are shared contract components every application's list `$ref`s, and one
   place in core decides *304 / delta / delta + id set*. Unchanged ⇒ **`304 Not Modified`** (tiny, no body, no media
   query) — the common idle case. Changed ⇒ `200` with the delta below. One endpoint covers both #48 sub-ideas
   (skip-when-unchanged + fetch-on-change).

3. **On change, a keyset delta — never a full re-list.** The client sends its cursor `(position, id)`; the
   server returns **only media past it** (tie-broken by id). A medium's **position** is the node's item revision its
   last change bumped to: the bump returns it, and the writer stamps it on the row in the same transaction, while the
   revision row's lock — which the bump holds to the commit — is still held. So positions become visible in the order
   they were handed out, and a client that has passed one never sees a row land behind it. **Rejected: a timestamp as the
   keyset.** A change time stamped before the revision lock lets a writer that took its stamp first and committed last
   land behind a cursor a poll in between had already passed — its change is never delivered, and a millisecond tie
   breaks the same way. Every writer that moves a medium to the end of the list stamps it — an attach, a meta edit, a
   restore, a block or its lift; a move between buckets and a reparent bump the revision without moving the row. The
   medium's change time stays its own field and is served as such. So an **active upload streams the handful of new items each poll**, not the
   whole gallery every interval — the failure mode a full-re-list-on-change would have. The client upserts the delta by
   `mediaId` and continues from the `cursor` the server answered: opaque to it, never assembled from a row's fields.

4. **Delete-reconcile via the current id-set, gated by a second counter.** A keyset delta is add/edit-only — it
   cannot express a removal. So a `200` **may** also carry the node's **current `mediaId` set** (ids only); the
   client **drops any id it no longer sees** — this catches deletes and, because it is the *set* (not a count), a
   **net-zero delete+add** too — the insight worth keeping. But the id-set is **O(N)**, so it must not ride *every*
   change — an active upload would re-send the growing full set each poll. A **second per-node counter,
   `delete_revision`, bumped *only* on a delete** (never on attach/meta-edit — so a delete bumps *both* counters,
   an add/edit only the revision) gates it: the client echoes its last-seen `delete_revision`, and the server
   includes the id-set **only when `client.delete_revision < node.delete_revision`** — i.e. only when a delete
   actually happened since the client last synced. Pure adds/edits stay **delta-only**; the O(N) id-set scan is
   paid solely on real deletes (rare, and self-limiting — one reconcile re-syncs the client). Both counters live on
   the same row, so a single read returns a **consistent pair** — no torn read can strand a delete.

5. **Short-poll transport for v1.** A client with a Memory open polls on an interval, **gated on tab visibility**
   (core's live poller, the loop the level views run on too: a return to the foreground or focus ticks at once, the
   interval pauses when hidden — a background poll is pure waste — and it is jittered and backs off on errors). The
   cadence is about 20 s. The conditional read, the delta merge and the cursor are core's too, so an application
   supplies only its fetch and its reducer. It stops when the Memory closes; a poll still in flight then writes
   nothing, and a tick never overlaps the previous one. With the delta, even an active upload is cheap per poll. A few
   seconds of latency is fine for a photo grid; **long-poll / a push channel** (sub-second, shared later with #6 live
   journal) is a deferred upgrade.

6. **The first load is the same keyset, in pages.** A list read without `since` is not special: it is a delta from the
   beginning. Every read takes a `limit` up to a fixed cap (the contract states it) and answers `more` when rows remain
   past the page, and the page's `cursor`; the client asks again with that
   cursor, and without `If-None-Match`, until `more` is false. A poll whose delta is longer than a page pages the same
   way. **The revisions a client keeps are the first page's, the cursor the last page's.** The server reads the
   revisions before the rows (as ADR 0081 decision 3 orders it), so a change committed while the client pages moves the
   revision past the one it kept, and its next poll pulls that change as a delta; an edit to a row already paged moves
   the row to the end, where it is delivered again and upserted; a delete moves `delete_revision` past the kept one, so
   the next poll carries the id set; keeping the last page's revisions instead would lose that change.
   Each page is read with the same number of queries whatever it holds — its originals, their derivatives and every blob
   they name, one query each, never one per photo. Paging bounds one answer's size and the server's memory for it. It does not give the client its
   first screen: the gallery's order is the sealed capture time, which only the device reads, so the page order is not
   the display order and the client needs every page before the order is final
   ([ADR 0105](0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md) decision 4). The
   Trash tab's listing pages on the same keyset, its order by deletion time the client's once it holds
   every page. **A guest's listing pages by the same rule on another key: the media id**, continued
   after the last id it was given, and it lists the one node a guest has open rather than the whole shared
   subtree. A position is the order of the Memory's changes, and a guest is told no time of the Memory's activity (ADR
   0081 decision 4; it is served no `updatedAt` either), so its key is the one it already holds.

7. **A guest polls too, on a tag of its own.** A share view equals the member view, so a guest's open gallery picks up
   a second uploader's photo as a member's does, through the same client loop. Its tag is not the node's item revision:
   that moves on changes a guest is not shown (a medium moved between buckets, an edit undone) and would tell an
   anonymous visitor when the Memory is worked on. It is ADR 0081 decision 4's rule — the listing's first page carries
   an `ETag` that is a truncated SHA-256 over everything the read serves, every page of it, and the identities beside
   the media; `If-None-Match` with it answers `304`. A guest has no keyset into the Memory's changes (its pages run by
   id), so a change answers the **whole listing**, and the client merges it with every id it holds as the id set — adds
   insert, a medium gone from the link drops, by the one merge. The first page's tag is read before its rows, so a
   medium added while the guest pages moves the tag past the kept one and the next poll re-reads. **The idle poll is
   cheap because the digest is remembered** by what it is a function of (per instance, bounded): the asked nodes and
   their item revisions — every medium writer bumps one, and a medium's blobs are written with it — and the content
   blocks that reach those nodes, which bump nothing. An idle guest poll reads the gate, the blocks and the revisions and
   compares, with the same constant number of statements whatever the Memory holds; only a changed key reads the whole
   listing, once per change per instance, shared by every link onto the node, and serves the first page from that
   read. Two guests that miss at once each recompute; nothing single-flights them.

How the server computes one poll response of the gallery's list — the **O(1) revision compare short-circuits the idle
case** (no media query at all), and only a genuine change runs the two queries (keyset delta + current id-set):

```mermaid
---
title: "Server: computing one listNodeMedia poll response"
---
flowchart TB
  START(["start"])
  READ["read the node's revision + delete_revision<br>(O(1), one row)"]
  ETAG["etag := tag(revision)"]
  Q1{"client's If-None-Match == etag?"}
  R304["respond 304 Not Modified"]
  DELTA["delta := one page of media rows past the client cursor (position, id)<br>(keyset range, at most limit rows, more if any remain)"]
  Q2{"client delete_revision < node delete_revision?"}
  IDSET["idSet := every mediaId of this node<br>(ids only)"]
  R200A["respond 200 → delta + idSet + both revisions"]
  R200B["respond 200 → delta + both revisions<br>(no id-set)"]
  STOP(["stop"])
  N1["revision : bumped by every gallery-media mutation<br>— attach, setMeta, delete (a cover change bumps neither).<br>delete_revision : bumped ONLY by a delete."]
  N2["no body, no media rows read — the idle fast path"]
  N3["delta → client upserts adds + in-place edits<br>idSet → client drops any id it no longer sees (deletes)"]
  N4["pure adds/edits — the O(N) id-set is skipped"]

  START --> READ
  READ --> ETAG
  ETAG --> Q1
  Q1 -->|"equal — nothing changed"| R304
  Q1 -->|"differs — a mutation bumped the revision"| DELTA
  R304 --> STOP
  DELTA --> Q2
  Q2 -->|"a delete happened"| IDSET
  IDSET --> R200A
  Q2 -->|"adds / edits only"| R200B
  R200A --> STOP
  R200B --> STOP
  READ -.- N1
  R304 -.- N2
  R200A -.- N3
  R200B -.- N4

  classDef note fill:#fffdf0,stroke:#c9b458,stroke-dasharray:4 3,color:#5c5326
  class N1,N2,N3,N4 note
```

The revision is a monotonic count of *mutations* (not of media), so any change moves it — that is what turns
the idle case into an `O(1)` `304`. On a change, adds + in-place meta edits ride the **keyset delta** (keyed by
`mediaId`). The full `mediaId` **set** rides along **only when `delete_revision` advanced** — a real delete — and
the client diffs it to drop removals, catching a **net-zero delete+add** too (a *set*, not a count). A pure
add/edit never carries the O(N) id-set, and nothing does per idle tick.

## Consequences

- **Idle is cheap (O(1) 304), an active upload is cheap (delta only — no id-set until a delete), deletes are robust
  (the id-set, gated by `delete_revision`), and it stays operator-blind** — two counters + opaque ids + exact
  timestamps, no content, no new operator-visible signal beyond the poll access-pattern the operator already sees
  on reads.
- **A live poll also tightens the `auto`-date self-heal window** ([uc.memory.date](../features/memory/use-cases/uc.memory.date.md)):
  the reconcile sees others' new media sooner. **The item revision is a read-cache validator, never a write
  precondition** — it moves on every attach, trash, restore, move and meta change, so a compare-and-set on it would
  turn every concurrent upload into a date-edit conflict. The sealed meta blobs carry their own `metaRevision` for
  that ([ADR 0077](0077-a-write-carries-the-version-it-read.md)).
- **New server state:** two counters per node (core's, erased with the node), a change time and a position per
  record (decisions 3, 6); the list gains a conditional GET, a cursor and the id set on a `200` **only when the
  client's delete counter lags**. All opaque/operational — no key, no plaintext. The tables are the current picture's
  ([Applications § How an application publishes a revisioned list](../apps/README.md#how-an-application-publishes-a-revisioned-list)).
- **Scope: own-node list only.** The gallery lists the open node's own media (chapters open to their own
  galleries), so a **per-node** revision suffices. A Memory+chapters **subtree** aggregation (inbox #16) would
  need a subtree-max revision — **out of scope**, a later slice. The scope's *node list* is a different signal
  ([ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)); this one covers the records inside
  one node. A moderator's block of an **item** moves this revision; a block of the **node**, or of a folder above it,
  moves the scope's tree revision only, so a member's gallery poll stays a `304` and the open Memory learns it through
  the host's tree poll, which runs on every opened node's page (ADR 0081 decision 5, inbox #503). A guest's tag, a digest
  of the rows (decision 7), carries both.
- **Lock order, and why the table sits where it does.** The bump runs after the writer's record rows and takes **no
  scope lock** (unlike ADR 0081's tree bump: gallery writers lock a media row, not the scope), so the first-bump insert
  is decided under the **node row's** lock with the update asked again beneath it — record rows, then the node row,
  then the revision row, the order every gallery writer already takes, the revision row a leaf nothing waits behind.
- **Adding a second user.** An application with its own records inside a node (Expenses' entries, Inventory's items)
  follows the recipe in [Applications § How an application publishes a revisioned list](../apps/README.md#how-an-application-publishes-a-revisioned-list).
  It owns no counter table of its own.
- **Rejected — an O(N) set-hash per tick:** correct + delete-robust, but recomputes the whole id-scan +
  SHA-256 every ~750 ms per open gallery; the revision counter delivers the same robustness at O(1). We
  keep the *set-not-count* insight (via the on-change id-set), drop the per-tick cost.
- **Rejected — full re-list on any change:** re-downloads + re-decrypts the entire gallery every poll during an
  active upload (the primary live case) — the keyset delta exists precisely to avoid this.
- **Rejected — a counter table per application, or a generic table keyed by a list name:** each application would
  re-implement the first-bump race and the erasure ordering (the bug that bought the lock-order tests), and nothing
  would stop one from forgetting. A node holds one list today (a node's kind decides which application owns it), so a
  second list in one node would add a `list` column to the key — not built until a kind needs it.
- **Rejected — a count-only or `max(updated_at)`-only signal:** a count misses a net-zero delete+add; a max misses
  a delete of a non-newest media. The counter (bumped on *every* mutation) + the id-set reconcile cover both.
- **Rejected (deferred) — WebSocket/SSE push:** lower latency, but more infra and a **held per-viewer connection
  is a stronger presence signal** to the operator than a coarse poll; folded into a later push channel shared with
  #6 (live journal co-edit).
