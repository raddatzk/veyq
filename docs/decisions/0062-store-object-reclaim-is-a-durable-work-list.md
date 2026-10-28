# ADR 0062 — Store-object reclaim is a durable work list, not a best-effort call

- Status: Accepted
- Built: yes
- Date: 2026-08-18
- Model: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)

## Context

Every reclaim path deletes the database rows in a transaction and the store objects after it commits. The order is
right — the row is the only thing that knows the `storage_key`, so a row-only delete is worse than no reclaim — but the
second half was a bare `runCatching { }`, and `BlobStore.delete` returned `Unit`: no failure channel in the type. A
503, a network blip or an expired credential left an object no row pointed at, and nothing noticed. The failure was
unobservable by construction — no metrics, no health surface, no job reporting what it did — and a log line would make
future failures visible in a file nobody tails without making them countable.

One path carried the mirror hazard: `ScopePurge` deleted objects first, inside its transaction, so a rollback brought
back rows pointing at bytes that were gone ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)
§ Context 2). Both directions are invisible for the same reason, so one decision fixes both. And with nothing in
production there was no leaked stock to measure: the structure was cheaper to correct than ever again.

## Decision

**1. The keys to reclaim are written to a durable list in the same transaction as the row delete.** One row per
storage key prefix on `veyq_store_reclaim`; the transaction is the whole guarantee that the knowledge of which bytes to
delete cannot die with the row. It needs no bucket listing, which matters because a `storage_key` is a flat UUID and a
listing is an unpartitionable full scan. **A recorded key is not due until no PUT signed for it can still land:** its
first attempt waits `veyq.blob.upload.retention`, the bound the upload-session purge already derives as presign TTL plus
one chunk still streaming — one number with one meaning. Several paths drop a row inside the TTL of the last window
signed for it, and S3 accepts a PUT on a still-valid URL onto a deleted key; without the wait that PUT would re-create
bytes no row, reclaim row or quota names. The wait suffices because no URL is signed for a key after its row drop.

**2. `BlobStore.delete` reports what it could not delete.** It returns the failed keys. Without this the list would be
written and drained faithfully while leaking as before — and it would be green everywhere, because the test store threw
on a refused key while the real one swallowed it. Returning rather than throwing fits a batch that is partly deletable.

**3. Exactly one component deletes store objects:** `StoreReclaimSweep`. One place that can fail, retries, and says
what the system does with bytes. The cost is latency — bytes linger up to the not-before plus one sweep interval — which
nothing user-facing sees, since every path has already removed the item from every read.

**4. `ScopePurge` loses its exception.** It records keys in its transaction like every other path, and its mirror
hazard disappears.

**5. The retry is bounded and its give-up state is named.** After a configured number of attempts with backoff, the row
is marked abandoned and logged once at `ERROR`. The leak is not claimed impossible — a permanently unreachable store
still loses bytes — but it is **a row you can count**. An unbounded retry was rejected: a queue that grows silently is
the same disease one level up.

**6. A reclaim row holds an opaque key and nothing else.** No scope, media or node: a group's erasure must leave nothing
that describes the group, and a per-scope count of object sizes is such a description. The cost, stated: nothing can
attribute a stuck object. A row opaque only where a scope was erased was rejected — a row whose shape varies by writer
is read wrong by whoever meets the other shape first.

**7. It is called a reclaim list, swept by a `Sweep`** — not a job (`veyq_job` is the member task queue), not a purge
(a `*Purge` hard-deletes rows past a retention; this drains a work list), not an outbox (a messaging pattern this
codebase does not have).

## Consequences

- **The leak is countable with SQL** — `attempts > 0`, `abandoned_at IS NOT NULL` — on any instance, without a bucket
  listing, a metrics stack or a log parser.
- **The one-deleter rule is a permanent guard** (`scripts/validate_store_reclaim_arity.py`): it is the rule every future
  reclaim path is tempted to bypass.
- **Backup `verify` tolerates orphaned objects** because a capture between a row delete and the sweep holds objects
  whose rows are gone — reclaim pending, not deletion unreliable.
- **A bucket-versus-database reconcile reports the objects no row claims, and reclaims them only through this list,
  after a grace, on a bucket the Server Owner confirmed is this instance's alone.** Once reclaim is guaranteed, an
  object no row and no reclaim row explains is an anomaly with a meaning — and veyq itself produces them: an in-place
  rollback ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) § 7) restores the database under a store
  that kept the failed release's uploads, bytes nobody can read. It runs in the backend, over a read-only `list` on the
  port, rather than in the backup tool, which runs only when someone runs it and cannot put a number in the console. It
  never calls `delete`; each of its three conditions answers one way a wrong judgement would destroy someone's media: a
  bucket shared with another instance (the Server Owner's confirmation), a restore of the wrong dump whose orphans the
  right dump needs (a grace every restore restarts), and a table that keeps a key alive but is missing from the claim set
  (`scripts/validate_store_reconcile_claims.py` derives the set from the migrations). How it lists, what claims an
  object and when the race is excluded: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)
  § The reconcile.
- **Rejected — stay best-effort and add a failure log.** It cannot count the bytes, retry, see the `ScopePurge` rollback
  (where the delete *succeeds*), or be exercised in the hermetic suite, whose store's `delete` is a no-op.
- **Rejected — keep an immediate delete at every call site, with the list as a safety net.** It removes Decision 3's
  latency, but the immediate delete was never complete (a PUT signed shortly before can re-create the bytes), two
  deleters are two bookkeeping paths, and the one-deleter rule becomes unstateable.
- **Rejected — a `deleted_at` tombstone on `veyq_blob`.** Keys live in two tables, so the flag and sweep are needed
  twice; it adds a state to a table many read paths query, the shape that once served trashed media through a share; a
  surviving row keeps its group's scope and size, against Decision 6; and backup `verify` would count it as required.
- **Rejected — a sweep that lists the bucket and deletes what no row claims, on its own judgement.** The reconcile keeps
  the listing but not the judgement: each of its three conditions answers one of the ways that judgement turns into
  silent, irreversible loss. Report-only was rejected the other way: the rollback's residue is bytes nobody else will ever
  free.

For Server Owners, on the docs site: [Operations](../../site/src/content/docs/run/operations.md).
