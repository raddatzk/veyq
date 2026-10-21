-- The bucket reconcile (ADR 0062 § Consequences, cr-bucket-reconcile). Two tables, neither of them anyone's data: a
-- fresh instance's first pass writes both, so src/tools/instance_backup.py names them in NOT_DATA, and a restore empties
-- both after loading its dump, so every first-seen clock restarts and the exclusive-bucket confirmation must be given
-- again. Counts, instants and opaque storage keys only: no scope, no node (ADR 0062 Decision 6).

-- The reconcile's state: one row, id 1, created by the first tick. The pass in progress (its page counter is the
-- compare-and-set every page's write goes through), the last completed pass, the last failure, and the Server Owner's
-- two switches. `*_digest` names the bucket a pass listed or a confirmation was given for (BlobStore.bucketDigest).
CREATE TABLE veyq_store_reconcile (
    id                          INT           NOT NULL PRIMARY KEY,
    store_digest                VARCHAR(64)   NULL,
    pass_no                     BIGINT        NOT NULL,
    pass_started_at             TIMESTAMP(3)  NULL,
    pass_page                   BIGINT        NOT NULL,
    pass_cursor                 VARCHAR(1024) NULL,
    pass_objects                BIGINT        NOT NULL,
    pass_orphan_objects         BIGINT        NOT NULL,
    pass_orphan_bytes           BIGINT        NOT NULL,
    pass_not_veyq_objects       BIGINT        NOT NULL,
    last_listed_at              TIMESTAMP(3)  NULL,
    completed_started_at        TIMESTAMP(3)  NULL,
    completed_at                TIMESTAMP(3)  NULL,
    completed_objects           BIGINT        NULL,
    completed_orphan_objects    BIGINT        NULL,
    completed_orphan_bytes      BIGINT        NULL,
    completed_not_veyq_objects  BIGINT        NULL,
    completed_enqueued_keys     BIGINT        NULL,
    failed_at                   TIMESTAMP(3)  NULL,
    failure                     VARCHAR(32)   NULL,
    exclusive_confirmed_at      TIMESTAMP(3)  NULL,
    exclusive_store_digest      VARCHAR(64)   NULL,
    paused_at                   TIMESTAMP(3)  NULL,
    clocks_restarted_at         TIMESTAMP(3)  NULL,
    clocks_restart_reason       VARCHAR(16)   NULL
);

-- One row per storage key no row claimed in any pass since `first_seen_at` (the store's clock): the candidates. A
-- completed pass drops every row whose `last_seen_pass` is not its own, so a row that survives was an orphan in every
-- pass since it was first seen. `chunk_count` is the highest orphan index seen plus one — what a reclaim row deletes.
CREATE TABLE veyq_store_orphan (
    storage_key     VARCHAR(64)  NOT NULL PRIMARY KEY,
    chunk_count     INT          NOT NULL,
    objects         BIGINT       NOT NULL,
    bytes           BIGINT       NOT NULL,
    first_seen_at   TIMESTAMP(3) NOT NULL,
    last_seen_pass  BIGINT       NOT NULL
);
CREATE INDEX ix_store_orphan_first_seen ON veyq_store_orphan (first_seen_at);

-- The reconcile asks every claim table for one listed page's storage keys at a time (`storage_key IN (...)`); without
-- these, each page is a full scan of each table.
CREATE INDEX ix_blob_storage_key ON veyq_blob (storage_key);
CREATE INDEX ix_upload_session_storage_key ON veyq_upload_session (storage_key);
CREATE INDEX ix_store_reclaim_storage_key ON veyq_store_reclaim (storage_key);
