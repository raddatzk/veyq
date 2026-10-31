# ADR 0081 — A scope's tree revision is the level view's change signal: the live refresh is a conditional GET

- Status: Accepted
- Built: yes
- Date: 2026-10-12
- Model: [en.memory.tree](../features/memory/enablers/en.memory.tree.md)

## Context

An open level view picks up what others changed — a share guest's upload, a peer's new Memory or folder, a deleted
file — without a reload. It first did so by re-reading the **whole list** every ~15 s and comparing a fingerprint of
its ciphertext rows on the client: correct, but every open tab paid the server's full tree read (or the guest's full
resolve) and the client's parse and compare on every tick, idle or not — the cost
[ADR 0033](0033-live-media-updates-revision-polling.md) refused for a Memory's gallery and replaced by a revision counter and a
conditional GET. The level view is the same problem one level up: a cheap "did anything change?" that costs nothing
when nothing did. Two things differ from ADR 0033: what changes is the **tree** (nodes and what hangs off them, not
one Memory's media), and the other reader is an **anonymous guest**, who must not learn more than the link shows.

## Decision

1. **One monotonic counter per scope.** Bumped in the **same transaction** as every write a level view shows, by one
   atomic increment, as that write's last statement. A row of its own, not a column of the scope row: the scope row is
   the tree's lock, and a bump that took it *after* a node row would invert the lock order every writer keeps
   (scope → node). **Every caller already holds the scope lock**, and that is the whole concurrency story: it
   serializes the first bump's insert (no row reads as 0; ADR 0033's media counter needs a second ask under the node
   row's lock because its bumps do not hold one), and it makes the revision row a leaf — nothing waits on it while
   holding anything else. The scope's erase deletes the row before the scope row goes.

2. **Every write a level view shows bumps it:** a node's create (a guest's included, and a drop-box guest's identity
   folder), its rename, move, re-seal and reorder, a trash, a restore, the expired-trash purge and a guest's
   take-back of its placeholder, a blob attached to or removed from a node, a re-key's commit, and the first
   `WRITE_ONLY` link of a root setting its sealed drop key. A source guard is red on a writer of node or attachment rows
   that neither bumps nor is exempt, and it is the list of the sites. A refused write (a stale revision, a taken slug,
   a conflicting attach, an already-trashed delete, a no-op update) bumps nothing. A scope's erase bumps nothing (the
   row goes with it); an unfinished upload's session purge changes no listing. A node's item-list revision (core's,
   ADR 0033) stays as it is; a Memory created, renamed, moved or deleted is a node change like any other.

3. **Members: a conditional GET on `listNodes`.** The `200` carries the revision as its `ETag` (`"7"`); `If-None-Match`
   with the current one answers `304` — no body, no row read. The access gate runs first (a non-member learns nothing,
   not even a match), and the revision is read **before** the rows: a change committing in between puts newer rows under
   the older tag and the next poll re-reads them, where the other order would tag older rows with a newer revision and
   strand the change. An unparseable echo is a plain `200`, never an error.

4. **Guests: a digest of what the response serves, not the scope revision.** The scope revision moves on every change in
   the *space*, shared subtree or not; as a guest's tag it would tell an anonymous visitor *when anything in the rest of
   the space changes* — activity the link was not made to show (a guest's listing carries no `createdAt` for the same
   reason). So the resolve's `ETag` is a truncated SHA-256 over the unlocked share as served — every field, so a field
   added later is covered without anyone remembering to — and over the attachments on the served nodes: a file
   finishing its upload changes no node row and a guest sees it. It is a function of nothing the guest cannot already read, and a `WRITE_ONLY` link,
   served no node, has a constant one. The cost is the reads `resolveShare` already made; what is saved is the body, the
   client's parse and decryption, and the compare. A `404` — revoked, expired, deleted, its space going, one non-enumerable
   answer — is the same as ever, and the polling guest now notices it: the page flips to its unavailable state (a changed
   password to the gate). A failure that says nothing about the link (the network, a 429, a 5xx) backs the poller off and
   leaves the page standing. **The rule is every guest list's, not the resolve's alone:** the guest's gallery is tagged
   the same way ([ADR 0033](0033-live-media-updates-revision-polling.md) decision 7).

5. **Clients.** The poller echoes the tag of what it shows. `304` is no work: nothing parsed, opened or compared, and the
   sections are told nothing. A `200` replaces the tree only if the fingerprint of its rows differs (the fingerprint stays
   as the fallback for a server that names no tag, and absorbs a change in another folder, which moves the scope's revision
   and not this level's rows) and tells the sections the scope **moved** — Depot re-reads its blob listing then, which is
   how an upload completing, a change in no row, reaches an open level. A guest's first poll after a load is a full read
   (the load's response keeps no tag), after which it is conditional; so is the first after the guest's own write.
   **The poll runs on every page of the tree, not on levels alone:** a folder level and an opened node's page of any
   kind — a Memory, a file, a drop box — for a member and a guest alike. A moderator's block of a node, or of a folder
   above it, bumps this revision and no item revision, so on a Memory's page the gallery's own poll
   ([ADR 0033](0033-live-media-updates-revision-polling.md)) stays a `304` and this one is the read that carries it:
   the row comes back marked, and the host turns the page to *Blocked by the operator* at once, its detail unmounted
   with every object URL it held; a lift brings the page back. A block changes no ciphertext, so the fingerprint counts
   the row's block mark too. **Rejected:** a node block bumping the item revision of every node below it — a write per
   node of the subtree, where the poll already on the page costs nothing new; an idle tree poll costs the same whatever
   the scope holds. A re-read tree replaces the node objects under an open page, so the host keeps the page's node on
   screen by id while the fragment stays (a peer's rename follows to the new path, never a collapse to the scope
   root), and the node's key the same object while it is the same key, so nothing keyed on it reloads.

## Consequences

- **An idle level is a `304`** on both paths: no list, no decryption, no section re-read. An active scope costs one list
  per change per open tab, as before.
- **One more row per scope, one more atomic update per tree write**, behind a lock the write already holds. The
  hot row serializes bumps only for the duration of writes that were serialized on the scope lock already.
- **A new writer of node or attachment rows must bump**, and the source guard says so on the day it is added. What it
  cannot see is *where* the bump sits: that is the per-path tests' and a race test's job, on MariaDB.
- **Operator-blind:** the server stores a number per scope and a quoted number or a digest crosses the wire; the operator
  already sees every write's timing.
- **Not covered, deliberately:** a Memory's gallery media (its own revision), the trash listing and the members and
  links lists (not polled).

## Rejected

- **The fingerprint alone (what first shipped):** correct, and the full cost per tick per tab.
- **The revision as a column of the scope row:** one fewer table, and a lock-order inversion on every bump taken after a
  node row (a cover's commit, a primary bucket, the attachment path) — the trap the tree's lock rule exists for.
- **The scope revision as the guest's `ETag`:** the cheapest sound-looking signal and the one that leaks (point 4).
- **A per-subtree or per-folder revision** (bump every ancestor, or the viewed folder): a guest-safe, cheaper-than-digest
  signal, bought by bumping up the whole ancestor chain on every write under a lock-heavy path — not worth it while a
  digest costs only the reads the resolve already does. Revisit if guest polls on very large scopes show.
- **A server-side cache of the guest digest keyed by the scope revision** (so an idle guest poll skips the subtree
  read): sound (read the revision first, then compute; a stale hit only re-computes), and not built for the resolve. It
  is an optimisation of reads the resolve always did; the first measurement that wants it can add it without changing the
  contract. The guest's media listing did want it — its digest reads every page of a gallery — and has it
  (ADR 0033 decision 7).
- **A persistence listener that checks every node write bumped:** Hibernate's listeners do not see the bulk
  `UPDATE` that `applyUpdate` is, so the guard would be blind where it matters most.
- **Push (SSE/WebSocket):** as in ADR 0033 — a held per-viewer connection is a stronger presence signal than a coarse
  poll. With the conditional read an idle tick costs one revision compare and a `304`, so the poll runs faster than the
  whole-list re-read could; a push or long-poll (ADR 0033's deferred upgrade) is the only way below it.
