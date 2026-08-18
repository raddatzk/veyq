# ADR 0062 — Store-object reclaim is a durable work list, not a best-effort call

- Status: Accepted
- Date: 2026-08-18

## Context

Every reclaim path in veyq deletes the database rows inside a transaction and the store objects **after** it
commits. That order is deliberate and right: the row is the only thing that knows the `storage_key`, so a
row-only delete is strictly worse than no reclaim at all — `UploadSessionPurge`'s own docstring says so.

But the second half is a bare `runCatching { }` with no `onFailure`, and `BlobStore.delete` returns `Unit`.
There is no failure channel **in the type**, so no caller could report one even if it wanted to. A 503, a
network blip, an expired credential: the object survives with no row anywhere pointing at it, and nothing
notices. Eight of the nine call sites can leak this way (the ninth, the over-budget purge, deliberately leaves
its session rows standing, so the keys are never lost).

Three forces make this worth deciding now rather than measuring first:

- **The failure is unobservable by construction, not by omission.** The backend has no metrics extension, no
  health surface, and nine log statements in all of `backend/src/main` — none of the nine scheduled jobs
  reports what it did. Adding a log line would make future failures visible in a file nobody tails; it would
  not make them *countable*, and it cannot see the bytes already lost.
- **One path carries the mirror hazard instead.** `ScopePurge.eraseScope` deletes the objects **first, inside**
  its per-scope transaction. If that transaction rolls back, the rows return and point at bytes that are gone —
  a valid presigned URL that 404s, which [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)
  § Context 2 names as the skew the read path does not handle. Both directions are invisible for the same
  reason, so both are fixed by the same decision.
- **Nothing is in production, so there is no accumulated stock to measure.** The obvious first move — a
  read-only reconcile that lists the bucket and reports what no row claims — answers *"how much has already
  leaked?"*, and today that number is zero by construction. Measuring it would buy nothing, while the structure
  is cheaper to correct now than it will ever be again.

## Decision

**1. The keys to reclaim are written to a durable list in the same transaction as the row delete.** A new
`veyq_store_reclaim` table takes one row per storage key prefix (`storage_key`, `chunk_count`, `created_at`,
`attempts`, `next_attempt_at`). The transaction is the whole guarantee: the knowledge of which bytes to delete
can no longer die with the row that held it. This needs no listing of the bucket, which matters because
`BlobStore` has no `list` operation and `storage_key` is a flat UUID with no scope prefix — a listing would be
an unpartitionable full-bucket scan.

**2. Exactly one component deletes store objects.** `StoreReclaimSweep` drains the list; no other code calls
`blobStore.delete`. This collapses nine call sites to one, so there is exactly one place that can fail, one
place that retries, and one place to read to know what the system does with bytes. The cost is latency: bytes
linger up to one sweep interval after the rows are gone. Nothing user-facing depends on the object being gone
at the instant the row is — every one of these paths has already removed the item from every read.

**3. `ScopePurge` loses its exception.** It writes reclaim rows inside its per-scope transaction like everyone
else, and stops deleting objects itself. Its mirror hazard disappears without needing a design of its own, and
the *objects-first* shape its docstring defends is no longer needed by anything.

**4. The retry is bounded and its give-up state is named.** After a configured number of attempts with backoff,
the row is marked abandoned and logged once at `ERROR`. This is the point of the whole decision: the leak is not
claimed to be impossible — a permanently unreachable store still loses bytes — but it is now **a row you can
count**. `SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL` is the answer to *"how many
bytes survived a failed delete?"*, which nothing could answer before. An unbounded retry was rejected for the
reason this ADR exists: a queue that grows silently is the same disease one level up.

**5. A reclaim row holds an opaque key and nothing else.** No `scope_id`, no `media_id`, no node. Group erasure
must leave nothing behind that describes the erased group, and a per-scope count of object sizes is exactly
such a description. The list is legible to the operator only as "n objects pending deletion", which is what
they already learn from the bucket.

**6. It is called a reclaim list — not a job, not a purge, not an outbox.** `veyq_job` is taken and means the
operator-blind member task queue; a reader who follows the word lands in the wrong feature. Every existing
`*Purge` finds rows older than a retention window and hard-deletes them, and this one has no retention window at
all — it drains a work list — so the `Sweep` suffix marks the different shape deliberately. *Outbox* imports a
messaging pattern this codebase does not have.

## Consequences

- **The leak becomes measurable, which was the whole ask.** A failed delete is a row with `attempts > 0`; a
  permanently failed one is a row with `abandoned_at`. Both are countable with SQL, on any instance, without a
  bucket listing, a metrics stack, or a log parser.
- **A structural invariant worth guarding falls out.** "Exactly one call site of `blobStore.delete` outside the
  store itself" is a one-line grep, and it pins Decision 2 — which is the decision every future reclaim path
  will be tempted to bypass by calling the store directly. It ships as a permanent guard, not a one-shot check.
- **Two durable sentences elsewhere stop being true and are corrected with this.**
  [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) § Context 2 asserts *every* store deletion
  runs best-effort after the commit (already false for `ScopePurge` before this ADR, and false for all of them
  after it), and [ADR 0061](0061-node-deletion-reversible-node-trash.md) Decision 7 pins the same wording for
  the node purge.
- **Backup `verify` keeps tolerating orphaned objects, on a better reason.** A capture taken between a row
  delete and the next sweep tick legitimately holds objects whose rows are gone — so the asymmetry stands, but
  because reclaim is *pending*, not because deletion is *unreliable*.
- **A bucket-versus-database reconcile becomes worth building later, and only now means something.** Until this
  ADR, an orphaned object was the documented steady state, so a report of them asserted nothing. Once reclaim is
  guaranteed, "objects no row claims and no reclaim row explains" is an anomaly with a meaning — and a report of
  it can have a Definition of done other than *"finds nothing"*. It is deliberately not built here: there is no
  production instance for it to measure.
- **Rejected — leave it best-effort and add a failure log.** Nine lines, and it makes future failures visible.
  It cannot count the bytes, cannot retry, cannot see the `ScopePurge` rollback (there the delete *succeeds*),
  and is unobservable in the hermetic suite, because the `%test` store's `delete` is a no-op and the failure
  would live in the S3 implementation the suite never reaches.
- **Rejected — a `deleted_at` tombstone on `veyq_blob` instead of a separate table.** It saves the table by
  reusing the key and chunk count already on the row, and it is the same guarantee. It loses on the edges: the
  keys live in **two** tables (`veyq_blob` and `veyq_upload_session`), so the flag and the sweep are needed
  twice; it adds a state to a table twelve sites read, including the share read path — the exact shape that
  once served trashed media through a public share for a whole retention window; a blob row surviving its
  group's erasure keeps that group's `scope_id` and size, against Decision 5; and a tombstoned row still counts
  as *required* to backup `verify`, which would go red on healthy instances. A separate table costs one
  migration and clears all four.
- **Rejected — a scheduled sweep that lists the bucket and deletes what no row claims.** It is the only shape
  that can reclaim objects already orphaned, and it is the one no foreign key protects: an in-flight upload
  whose session row is written after the listing is read looks exactly like garbage. Against a leak that this
  ADR prevents at the source and an existing stock of zero, it buys nothing and risks user content.
