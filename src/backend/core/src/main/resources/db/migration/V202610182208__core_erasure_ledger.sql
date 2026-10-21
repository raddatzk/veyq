-- B, the days a backup is kept (ADR 0056 decision 8), once the Server Owner set it: one row, id 1, 7 to 365. No seeded
-- row — no row reads as the default, 30 (BackupRetention). The capture tooling reads it over the connection it dumps
-- through (src/tools/instance_backup.py), so it is instance configuration and none of the restore's data (NOT_DATA).
CREATE TABLE veyq_backup_retention (
    id             INT NOT NULL PRIMARY KEY,
    retention_days INT NOT NULL
);

-- The erasure ledger's outbox (ADR 0056 decision 8): one row per hard erasure no restored row would trigger again, and
-- per moderation block, lift and case, written in the erasure's own transaction. A sweep appends the unsent rows to
-- `erasures.log` beside leg (c)'s key and stamps sent_at; a sent row goes once it is older than B. `line` is the JSON
-- line the file receives — a kind, opaque ids, storage keys and times, never content or a name; `subject_id` is the id
-- it names, kept apart for the tests and the start's count.
CREATE TABLE veyq_erasure_outbox (
    id          UUID          NOT NULL PRIMARY KEY,
    kind        VARCHAR(16)   NOT NULL,
    subject_id  VARCHAR(64)   NOT NULL,
    line        VARCHAR(1024) NOT NULL,
    recorded_at TIMESTAMP(3)  NOT NULL,
    sent_at     TIMESTAMP(3)
);
CREATE INDEX ix_erasure_outbox_sent ON veyq_erasure_outbox (sent_at, recorded_at);
