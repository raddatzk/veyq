-- The store-object reclaim list (ADR 0062). Every path that deletes a row carrying a `storage_key` writes the
-- key here in the SAME transaction, and exactly one component — StoreReclaimSweep — deletes the objects. Before
-- this table the keys were handed to a post-commit `blobStore.delete` that returned `Unit` and swallowed every
-- per-key failure, so a refused delete left bytes nothing could find and nothing could count.
--
-- One row per storage-key PREFIX, not per chunk object: the sweep enumerates `storage_key/<i>` for
-- `i in 0 until chunk_count` exactly as the writers did. A row is therefore one unit of work and one retry.
--
-- **Deliberately opaque — no scope_id, no media_id, no node_id** (ADR 0062 Decision 6). After a group erasure
-- these rows are the only surviving trace of it, and a per-scope list of object counts is a description of the
-- erased group. The price is stated in that decision rather than discovered here: nothing can attribute a stuck
-- object, so an operator learns "n pending, n abandoned" and never which space the bytes belonged to.
--
-- **No UNIQUE on storage_key, and that is not an oversight.** `MediaStore.persistBlob` gives the committed blob
-- the SAME `storage_key` as the upload session it was promoted from, and the over-budget purge in `MediaHandler`
-- deliberately records a session's objects while leaving its row standing for `UploadSessionPurge` to reclaim
-- later — so the same prefix legitimately reaches this table twice. A unique index would turn that into a
-- constraint violation inside someone else's transaction, which is the one thing this table exists to prevent.
-- A second delete of an already-absent object is a no-op at every S3-compatible store.
CREATE TABLE veyq_store_reclaim (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    storage_key     VARCHAR(64) NOT NULL,
    chunk_count     INT         NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt_at TIMESTAMP   NOT NULL,
    abandoned_at    TIMESTAMP   NULL
);

-- The sweep's only read: rows not given up on, whose backoff has elapsed. Leading `abandoned_at` because that is
-- the equality half of the predicate and abandoned rows accumulate — they are kept precisely so
-- `SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL` stays answerable (Decision 5), so the
-- sweep must be able to skip them without reading them.
CREATE INDEX ix_store_reclaim_due ON veyq_store_reclaim (abandoned_at, next_attempt_at);
