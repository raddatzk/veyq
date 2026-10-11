# ADR 0081 — A scope's tree revision is the level view's change signal: the live refresh is a conditional GET

- Status: Accepted
- Date: 2026-10-12

## Context

An open level view picks up what others changed — a share guest's upload, a peer's new Memory or folder, a deleted
file — without a reload (inbox #408). It first did so by re-reading the **whole list** every ~15 s and comparing a
fingerprint of its ciphertext rows on the client: correct, but every open tab pays the server's full `listNodes` (or
the guest's full `resolveShare`) and the client's parse and compare on every tick, idle or not — the cost
[ADR 0033](0033-live-media-updates-revision-polling.md) refused for a Memory's gallery and replaced by a revision counter and a
conditional GET. The level view is the same problem one level up: a cheap "did anything change?" that costs nothing
when nothing did. Two things differ from ADR 0033: what changes is the **tree** (nodes and what hangs off them, not
one Memory's media), and the other reader is an **anonymous guest**, who must not learn more than the link shows.

## Decision

1. **One monotonic counter per scope, `veyq_scope_tree_revision`.** Bumped in the **same transaction** as every write a
   level view shows, by one atomic `UPDATE … revision + 1` (`NodesStore.bumpTree`), as that write's last statement. A row
   of its own, not a column of `veyq_scope`: the scope row is the tree's lock (`NodesStore.lockScope`, developer-styleguide
   §12), and a bump that took it *after* a node row would invert the lock order every writer keeps (scope → node).
   **Every caller already holds the scope lock**, and that is the whole concurrency story: it serializes the first bump's
   insert (no row reads as 0; ADR 0033's media counter needs a second ask under the node row's lock because its bumps do
   not hold one), and it makes the revision row a leaf — nothing waits on it while holding anything else. The node eraser
   (phase 700) deletes the row before the scope row goes.

2. **The bump sites** — one per path, listed in `TreeRevisionBumpsTest`, which reads the sources and is red on a writer
   of node or attachment rows that is neither listed nor exempt:

   | write | site |
   | --- | --- |
   | node create (member; a guest's `createShareNode`, `READ_WRITE` or inside an identity folder) | `NodesStore.create` |
   | a guest's identity folder (Files' drop box) | `NodesStore.createIdentityFolder` |
   | rename, move, re-seal (`wrappedMeta`), reorder; the same by a `READ_WRITE` guest | `NodesStore.applyUpdate`, when it wrote |
   | trash — a node, a **file** (a deleted file is its trashed node), a subtree | `NodesStore.deleteSubtree`, when it stamped |
   | restore | `NodesStore.restoreSubtree` |
   | the expired-trash purge | `NodeSubtreePurge.eraseExpiredIn` |
   | a blob attached to a node, or replacing one — a file's first upload and its replace, a cover, a guest's `completeShareFile` | `AttachmentStore.attach`, on `Attached` |
   | a blob removed from a node | `AttachmentStore.detach`, on `Detached` |
   | the first `WRITE_ONLY` link of a root sets its sealed drop key (`NodeSummary.sealedDropPrivateKey`) | `SharingStore.create` |

   A refused write (a stale version, a taken slug, a conflicting attach, an already-trashed delete, a no-op update)
   bumps nothing. A scope's erase bumps nothing (the row goes with it). There is no abandon endpoint; an unfinished
   upload's session purge changes no listing. Memories' own `media_revision` stays as it is (ADR 0033); a Memory
   created, renamed, moved or deleted is a node change like any other.

3. **Members: a conditional GET on `listNodes`.** The `200` carries the revision as its `ETag` (`"7"`); `If-None-Match`
   with the current one answers `304` — no body, no row read. The access gate runs first (a non-member learns nothing,
   not even a match), and the revision is read **before** the rows: a change committing in between puts newer rows under
   the older tag and the next poll re-reads them, where the other order would tag older rows with a newer revision and
   strand the change. An unparseable echo is a plain `200`, never an error.

4. **Guests: a digest of what the response serves, not the scope revision.** The scope revision moves on every change in
   the *space*, shared subtree or not; as a guest's tag it would tell an anonymous visitor *when anything in the rest of
   the space changes* — activity the link was not made to show (a guest's listing carries no `createdAt` for the same
   reason, `NodeSummary`). So `resolveShare`'s `ETag` is a truncated SHA-256 over the unlocked `PublicShare` as served
   (every field — the generated model's `toString` names them all, so a field added later is covered without anyone
   remembering to) and over `node|slot|blob` of the attachments on the served nodes: a file finishing its upload changes
   no node row and a guest sees it. It is a function of nothing the guest cannot already read, and a `WRITE_ONLY` link,
   served no node, has a constant one. The cost is the reads `resolveShare` already made; what is saved is the body, the
   client's parse and decryption, and the compare. A `404` — revoked, expired, deleted, its space going, one non-enumerable
   answer — is the same as ever, and the polling guest now notices it: the page flips to its unavailable state (a changed
   password to the gate). A failure that says nothing about the link (the network, a 429, a 5xx) backs the poller off and
   leaves the page standing.

5. **Clients.** The poller echoes the tag of what it shows. `304` is no work: nothing parsed, opened or compared, and the
   sections are told nothing. A `200` replaces the tree only if the fingerprint of its rows differs (the fingerprint stays
   as the fallback for a server that names no tag, and absorbs a change in another folder, which moves the scope's revision
   and not this level's rows) and tells the sections the scope **moved** — Files re-reads its blob listing then, which is
   how an upload completing, a change in no row, reaches an open level. A guest's first poll after a load is a full read
   (the load's response keeps no tag), after which it is conditional; so is the first after the guest's own write.

## Consequences

- **An idle level is a `304`** on both paths: no list, no decryption, no section re-read. An active scope costs one list
  per change per open tab, as before.
- **One more row per scope, one more atomic update per tree write**, behind a lock the write already holds. The
  hot row serializes bumps only for the duration of writes that were serialized on the scope lock already.
- **A new writer of node or attachment rows must bump**, and the source guard says so on the day it is added. What it
  cannot see is *where* the bump sits: that is the per-path tests' (`TreeRevisionCoreSliceTest`,
  `FilesTreeRevisionSliceTest`) and the race test's (`TreeRevisionRaceCoreSliceTest`, on MariaDB) job.
- **Operator-blind:** the server stores a number per scope and a quoted number or a digest crosses the wire; the operator
  already sees every write's timing.
- **Not covered, deliberately:** a Memory's gallery media (its own revision), the trash listing and the members and
  links lists (not polled).

## Rejected

- **The fingerprint alone (what #408 first shipped):** correct, and the full cost per tick per tab.
- **The revision as a column of `veyq_scope`:** one fewer table, and a lock-order inversion on every bump taken after a
  node row (`MediaStore`'s commit of a cover, a primary bucket, the attachment path) — the trap the tree's lock rule exists for.
- **The scope revision as the guest's `ETag`:** the cheapest sound-looking signal and the one that leaks (point 4).
- **A per-subtree or per-folder revision** (bump every ancestor, or the viewed folder): a guest-safe, cheaper-than-digest
  signal, bought by bumping up the whole ancestor chain on every write under a lock-heavy path — not worth it while a
  digest costs only the reads the resolve already does. Revisit if guest polls on very large scopes show.
- **A server-side cache of the guest digest keyed by the scope revision** (so an idle guest poll skips the subtree
  read): sound (read the revision first, then compute; a stale hit only re-computes), and not built. It is an
  optimisation of reads the resolve always did; the first measurement that wants it can add it without changing the contract.
- **A persistence listener that checks every node write bumped:** Hibernate's listeners do not see the bulk
  `UPDATE` that `applyUpdate` is, so the guard would be blind where it matters most.
- **Push (SSE/WebSocket):** as in ADR 0033 — a held per-viewer connection is a stronger presence signal than a coarse poll.
