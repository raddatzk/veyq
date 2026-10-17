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

**A recorded key is not due until no PUT signed for it can still land.** `record` stamps `next_attempt_at` at
`veyq.blob.upload.retention` after the recording — the bound the upload-session purge already derives as
*presign TTL plus one chunk still streaming*, so one number with one meaning rather than a second knob that
would have to be kept at least as large by hand. The wait is needed because several paths drop the row naming a
key inside the TTL of the last window signed for it (a media purged straight from the trash, a replaced or
cleared cover, the over-budget purge), and S3 accepts a PUT on a still-valid URL onto a key that was deleted in
the meantime. Without the wait, a sweep tick between the drop and that PUT deletes the objects and settles the
row, and the PUT re-creates bytes under a key no row, no reclaim row and no quota names. The wait is sufficient
because no URL for a key is signed after its row drop: a window is signed only for a live upload session.

**2. `BlobStore.delete` reports what it could not delete.** It returns the keys that failed instead of `Unit`,
and `S3BlobStore` stops swallowing per-key failures silently. Without this the rest of this ADR is inert: the
sweep would hand its keys over, see a normal return, and clear the reclaim row whether or not the bytes went —
the list would be written and drained faithfully while leaking exactly as before. **And it would be green
everywhere**, because the test-scope `RecordingBlobStore` *throws* on a refused key while the real
`S3BlobStore` does not, so the hermetic suite would prove a retry path production can never enter. Returning
rather than throwing is what the sweep needs: a batch is partly deletable, and *which* keys failed is what
decides whether the row stays.

**3. Exactly one component deletes store objects.** `StoreReclaimSweep` drains the list; no other code calls
`blobStore.delete`. This collapses nine call sites to one, so there is exactly one place that can fail, one
place that retries, and one place to read to know what the system does with bytes. The cost is latency: bytes
linger up to the not-before of Decision 1 plus one sweep interval after the rows are gone. Nothing user-facing
depends on the object being gone at the instant the row is — every one of these paths has already removed the
item from every read.

**4. `ScopePurge` loses its exception.** It writes reclaim rows inside its per-scope transaction like everyone
else, and stops deleting objects itself. Its mirror hazard disappears without needing a design of its own, and
the *objects-first* shape its docstring defends is no longer needed by anything.

**5. The retry is bounded and its give-up state is named.** After a configured number of attempts with backoff,
the row is marked abandoned and logged once at `ERROR`. This is the point of the whole decision: the leak is not
claimed to be impossible — a permanently unreachable store still loses bytes — but it is now **a row you can
count**. `SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL` is the answer to *"how many
bytes survived a failed delete?"*, which nothing could answer before. An unbounded retry was rejected for the
reason this ADR exists: a queue that grows silently is the same disease one level up.

**6. A reclaim row holds an opaque key and nothing else.** No `scope_id`, no `media_id`, no node. Group erasure
must leave nothing behind that describes the erased group, and a per-scope count of object sizes is exactly
such a description. The list is legible to the operator only as "n objects pending deletion", which is what
they already learn from the bucket. **The cost is stated here rather than discovered later: nothing can
attribute a stuck object.** An operator sees *n pending, n abandoned* and cannot tell which space, Memory or
group the bytes belonged to. Making the row source-dependent — opaque only where a scope was erased — would buy
that back, and was rejected: a row whose shape varies by writer is read wrong by whoever meets the other shape
first, and the erasure path is the one that matters most.

**7. It is called a reclaim list — not a job, not a purge, not an outbox.** `veyq_job` is taken and means the
operator-blind member task queue; a reader who follows the word lands in the wrong feature. Every existing
`*Purge` finds rows older than a retention window and hard-deletes them, and this one has no retention window at
all — it drains a work list — so the `Sweep` suffix marks the different shape deliberately. *Outbox* imports a
messaging pattern this codebase does not have.

## Consequences

- **The leak becomes measurable, which was the whole ask.** A failed delete is a row with `attempts > 0`; a
  permanently failed one is a row with `abandoned_at`. Both are countable with SQL, on any instance, without a
  bucket listing, a metrics stack, or a log parser.
- **The test seam is stronger than production, and that had to be decided, not discovered.** A refused delete
  is reachable in the hermetic suite only because `RecordingBlobStore` throws; the real store swallows. Any
  design here that leaves `BlobStore.delete` returning `Unit` is provable in tests and broken in production —
  which is why Decision 2 is a decision and not an implementation detail.
- **A structural invariant worth guarding falls out.** "Exactly one call site of `blobStore.delete` outside the
  store itself" is a one-line grep, and it pins Decision 2 — which is the decision every future reclaim path
  will be tempted to bypass by calling the store directly. It ships as a permanent guard, not a one-shot check.
- **Two durable sentences elsewhere stop being true and are corrected with this.**
  [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) § Context 2 asserts *every* store deletion
  runs best-effort after the commit (already false for `ScopePurge` before this ADR, and false for all of them
  after it), and [ADR 0061](0061-node-deletion-reversible-node-trash.md) Decision 7 pins the same wording for
  the node purge.
- **Backup `verify` keeps tolerating orphaned objects, on a better reason.** A capture taken between a row
  delete and the sweep tick that drains it legitimately holds objects whose rows are gone — so the asymmetry
  stands, but because reclaim is *pending*, not because deletion is *unreliable*.
- **A bucket-versus-database reconcile counts the objects no row claims, and reclaims them only after a grace, on a
  bucket the Server Owner confirmed is this instance's alone** (owner 2026-10-17, inbox #134; decided, not yet built —
  cr-bucket-reconcile). Until this ADR an orphaned object was the documented steady state, so a report of them asserted
  nothing; once reclaim is guaranteed, "objects no row claims and no reclaim row explains" is an anomaly with a meaning.
  What made it worth building is that veyq itself now produces them: an in-place rollback
  ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §7) empties the database and restores the
  pre-upgrade capture with the check only, so every upload made under the failed release stays in the store with no
  row and no reclaim row — bytes nobody can read, since their wrapped keys were in the emptied database. Its shape:
  - **In the backend, on a read-only port operation** — `BlobStore.list(startAfter, maxKeys)`, ListObjectsV2 with
    `start-after`, so the cursor is a key that survives a restart (a continuation token is opaque and may expire).
    Bounded per tick, resumable from that cursor, one pass every period. Chosen over the operator tool on
    `scripts/instance_backup.py`'s rclone listing, which needs no port but runs only when someone runs it, cannot put a
    number in the console, and reads the database and the listing at two moments with nothing to bracket them but
    the operator's timing. The listing is unpartitionable (`storage_key` is a flat UUID, Decision 1), which is the
    price the bound per tick pays.
  - **The keys live in every table a migration gives a `storage_key`** — today `veyq_blob`, `veyq_upload_session` and
    `veyq_store_reclaim`; `scripts/validate_store_reconcile_claims.py` derives that set from the migrations and holds
    the claim query to it, because a table that keeps a key alive and is missing there turns a wrong count into lost
    media. An object is an anomaly only when **none** of them explains it: some row names its prefix with a
    `chunk_count` above its index. The third table is what makes the question answerable at all — before it, an
    object whose row was legitimately deleted a second ago was indistinguishable from one leaked a year ago.
  - **A `storage_key` can legitimately appear twice**, so nothing may assume one row per key.
    `BlobUploadStore.persistBlob` gives a committed blob the same `storage_key` as the upload session it was promoted
    from, and the over-budget purge records a session's objects while deliberately leaving its row standing —
    which is why `veyq_store_reclaim` carries no unique index on that column.
  - **The window that excludes the race is stateable, so no design here needs a heuristic.** The write paths leave
    no moment at which an object exists unnamed. At **presign** time: `presignPut` is reached only through
    `presignPuts`, whose one caller (`BlobUploadHandler.presignWindow`) runs *after* `BlobUploadStore.createSession`
    has committed (`@Transactional`, with no enclosing transaction on the resource or handler), the `storage_key` and
    every chunk key are server-minted and signed into the URL by SigV4, and `persistBlob` writes no object at all — it
    inherits the session's key, and both callers delete the session row in the same transaction that inserts the
    blob row. At **PUT** time, which the presign argument alone does not reach: a signed URL outlives the row that
    named its key for up to the presign TTL, so the reclaim row Decision 1 writes is not due until that window has
    passed — the not-before. What is left is a **read-order** artifact, and the reconcile removes it per listed page:
    the three tables are read in **one** statement (one snapshot — a key promoted from `veyq_upload_session` to
    `veyq_blob` between two SELECTs would be missed by both), **after** the listing, which catches the upload that
    began during it; and each object that statement leaves unexplained is `stat`ed, and counted only if still present
    — a row deleted and reclaim-drained during the listing deleted its object first (the sweep deletes objects before
    it settles the row), so the `stat` does what a read *before* the listing would. The age bound —
    `storeTime − lastModified ≥ veyq.blob.upload.retention`, both instants the store's own (the response's `Date`
    and the object's `LastModified`, so no clock skew enters) — is a second layer, not the primary: an upload
    session's lifetime is unbounded, and its row is what covers it. The probe (`veyq-check/probe`) and every key that
    is not `<uuid>/<index>` are never orphans; they are counted apart as *not veyq's*.
  - **Deletion goes through this ADR's list and nothing else, on three conditions** (the owner's answer to *"does the
    reconcile ever delete?"*). It never calls `BlobStore.delete`; it records a key on `veyq_store_reclaim`, so
    Decision 3's one deleter, its not-before and its retry do the deleting. (1) The Server Owner has **confirmed the
    bucket is this instance's alone**, for the store in use — a changed store setting voids the confirmation; without
    it the reconcile only reports. (2) The key stayed an orphan in **every** pass for the **grace** (default 30 days)
    since it was **first seen**, on the store's clock; a claim row that reappears makes it explained again and drops
    it; and any **restore or rollback restarts every clock** — the restore tool empties the reconcile's tables after
    loading the dump (so the confirmation must be given again), and a gap in the listing longer than a bound restarts
    them for a database brought back by anything else. (3) It is **visible**: the console shows the candidates, their
    age and the date the first will go, and a **pause** stops the enqueueing while the counting goes on. A key any
    claim row names is never enqueued, even for an object past its `chunk_count`, because a reclaim row deletes
    `0 … n−1`.
  - **The closest thing to a prototype is weaker than it sounds**: `scripts/instance_backup.py`'s `verify` counts
    orphaned objects and tolerates them, but it compares a capture's index of the bucket against
    `manifest["store"]["blobs"]`, **one** table and never a bucket, so its orphan count includes every pending
    reclaim and every in-flight upload by construction.
- **Rejected — leave it best-effort and add a failure log.** Nine lines, and it makes future failures visible.
  It cannot count the bytes, cannot retry, cannot see the `ScopePurge` rollback (there the delete *succeeds*),
  and is unobservable in the hermetic suite, because the `%test` store's `delete` is a no-op and the failure
  would live in the S3 implementation the suite never reaches.
- **Rejected — keep the immediate delete at every call site and use the list only as a safety net**, clearing
  the row on a confirmed success. It removes the latency Decision 3 accepts: bytes would go the moment the rows
  do, rather than up to the not-before plus one sweep interval later — and at that moment a PUT signed shortly
  before can still re-create them, so the immediate delete was never the complete one. It loses on what that
  latency buys — two deleters means two bookkeeping paths and two places that can fail, and the *exactly one
  call site* invariant, this ADR's completeness proof and the one every future reclaim path will be tempted to bypass, becomes unstateable. The
  latency is invisible to the user (the rows, and therefore every read, are already gone) and visible only to
  whoever watches the bucket.
- **Rejected — a `deleted_at` tombstone on `veyq_blob` instead of a separate table.** It saves the table by
  reusing the key and chunk count already on the row, and it is the same guarantee. It loses on the edges: the
  keys live in **two** tables (`veyq_blob` and `veyq_upload_session`), so the flag and the sweep are needed
  twice; it adds a state to a table twelve sites read, including the share read path — the exact shape that
  once served trashed media through a public share for a whole retention window; a blob row surviving its
  group's erasure keeps that group's `scope_id` and size, against Decision 5; and a tombstoned row still counts
  as *required* to backup `verify`, which would go red on healthy instances. A separate table costs one
  migration and clears all four.
- **Rejected — a scheduled sweep that lists the bucket and deletes what no row claims, on its own judgement.** It is
  the only shape that can reclaim objects already orphaned, and the decided reconcile keeps that — but not the
  judgement. Its read-order problem has a stated answer (the reconcile bullet above), so that is not the reason. Three
  are, each turning a wrong judgement into silent, irreversible loss of someone's media, and each is what one of the
  reconcile's conditions answers: (i) a store of the operator's own need not be a dedicated bucket, and two instances
  sharing one would each see the other's objects as veyq-shaped orphans — answered by the Server Owner's confirmation
  that the bucket is exclusive; (ii) after a restore of the *wrong* dump the database is the wrong side, and the
  orphans are the data a restore of the right dump needs — answered by the grace, which every restore restarts; (iii)
  a future table that keeps a key alive and is missing from the claim set turns a wrong count into lost media —
  answered by the guard that derives the claim set from the migrations. Report-only was rejected the other way: the
  rollback's residue is unreadable bytes nobody else will ever free, and freeing them by hand needs exactly the
  classification the reconcile already does.

For Server Owners, on the docs site: [Operations](../../site/src/content/docs/run/operations.md).
