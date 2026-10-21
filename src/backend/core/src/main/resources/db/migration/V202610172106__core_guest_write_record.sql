-- The guest-write record (ADR 0093 § 10, en.moderation.guest-write-record): one row per guest write that enters a
-- guest as last editor, holding what the server already sees of it — the client address of core's one resolver (ADR
-- 0094) in text form, its source port where one is known, the server time in milliseconds UTC — the share identity
-- that wrote, and the object written (object_kind IDENTITY, NODE or ITEM; field_group `name`, or none for an upload's
-- content). No User-Agent, no content. expires_at is observed_at plus the retention as it stood at the write; the purge
-- also deletes a row older than the retention as it stands now, so no guest is kept longer than the page told them.
-- The row goes with its identity (cascading) and with its object (core's erasers, an application's through core). The
-- instance backup dumps this table's schema and leaves out its rows (src/tools/instance_backup.py).
CREATE TABLE veyq_guest_write_record (
    id                UUID         NOT NULL PRIMARY KEY,
    share_identity_id UUID         NOT NULL,
    object_kind       VARCHAR(16)  NOT NULL,
    object_id         UUID         NOT NULL,
    field_group       VARCHAR(16),
    client_ip         VARCHAR(45)  NOT NULL,
    client_port       INT,
    observed_at       TIMESTAMP(3) NOT NULL,
    expires_at        TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_guest_write_record_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id) ON DELETE CASCADE
);
CREATE INDEX ix_guest_write_record_object ON veyq_guest_write_record (object_id);
CREATE INDEX ix_guest_write_record_observed ON veyq_guest_write_record (observed_at);
CREATE INDEX ix_guest_write_record_expires ON veyq_guest_write_record (expires_at);

-- The retention N in days, 1 to 30 (ADR 0092 § 2), once the Server Owner set it: one row, id 1. No seeded row — no
-- row reads as the default, 7 (GuestWriteRecords.retentionDays).
CREATE TABLE veyq_guest_write_retention (
    id             INT NOT NULL PRIMARY KEY,
    retention_days INT NOT NULL
);

-- The operating mode applied once (ADR 0088 decision 14, ADR 0093 § 14): the switch's preset is off in PRIVATE, so an
-- instance already running in that mode gets the switch's instance-level off row; an ORGANISATION instance and one
-- with no mode chosen (it runs as ORGANISATION) get none.
INSERT INTO veyq_capability_switch (id)
SELECT 'core.public-shares.guest-write-record' FROM veyq_operator_details WHERE operating_mode = 'PRIVATE';
