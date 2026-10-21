-- Holds (ADR 0093 § 4, en.moderation.hold): a snapshot of what was reported, and a pin on its store objects that the
-- reclaim sweep skips. No foreign key into a space, a node, a share or a blob: erasing what was reported leaves the hold.
--
-- veyq_hold: one per case at a time (more triggers come with the review and an authority's order). hold_trigger names
-- why it exists (CASE_OPEN); expires_at is its end — a case-open hold 90 days after the report that took it. The purge
-- deletes a hold past its end with its rows and pins.
CREATE TABLE veyq_hold (
    id           UUID         NOT NULL PRIMARY KEY,
    case_id      UUID,
    hold_trigger VARCHAR(24)  NOT NULL,
    expires_at   TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_hold_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE INDEX ix_hold_case ON veyq_hold (case_id);
CREATE INDEX ix_hold_expires ON veyq_hold (expires_at);

-- veyq_hold_row: one copied row — its source table and id, and the row as JSON of its columns as stored: ciphertext and
-- metadata, nothing the server could open.
CREATE TABLE veyq_hold_row (
    id           UUID        NOT NULL PRIMARY KEY,
    hold_id      UUID        NOT NULL,
    source_table VARCHAR(64) NOT NULL,
    source_id    VARCHAR(64) NOT NULL,
    payload      MEDIUMTEXT  NOT NULL,
    CONSTRAINT fk_hold_row_hold FOREIGN KEY (hold_id) REFERENCES veyq_hold (id)
);
CREATE INDEX ix_hold_row_hold ON veyq_hold_row (hold_id);

-- veyq_hold_pin: a store object prefix (storage_key/0 … storage_key/(chunk_count-1)) the hold keeps. While expires_at is
-- in the future, StoreReclaimStore.due leaves a reclaim row of that key out — never deferring it, so its attempts stay —
-- and a backup's store leg lists it (src/tools/instance_backup.py, blob_index).
CREATE TABLE veyq_hold_pin (
    id          UUID         NOT NULL PRIMARY KEY,
    hold_id     UUID         NOT NULL,
    storage_key VARCHAR(64)  NOT NULL,
    chunk_count INT          NOT NULL,
    expires_at  TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_hold_pin_hold FOREIGN KEY (hold_id) REFERENCES veyq_hold (id)
);
CREATE INDEX ix_hold_pin_key ON veyq_hold_pin (storage_key, expires_at);
CREATE INDEX ix_hold_pin_hold ON veyq_hold_pin (hold_id);
