-- ADR 0113 decision 9: a medium's original and derivatives are blobs of core's blob service now (`veyq_application_blob`,
-- held by the medium), so `veyq_media.blob_id` and `veyq_media_derivative` go — and the media they named are discarded,
-- not migrated (no instance is live; the owner chose a reset of the dev instances' media over a migration).
--
-- **This migration deletes no row: it refuses to run while one is left that the new code could not read.** Each CHECK
-- below can hold for no such row, so adding it fails on a table that holds one — and the instance does not start,
-- naming the constraint:
--
--   - `ck_hold_row_media_reset_first`: a hold's copy of a `veyq_media` or `veyq_media_derivative` row. A held copy keeps
--     the shape it was copied in, and after the move no code reads a medium's blob from that shape.
--   - `ck_media_reset_first`: a medium.
--   - `ck_media_derivative_reset_first`: a derivative.
--
-- The reset before it is a manual step (infra/README.md § Memories' media moved to core): every medium deleted and
-- emptied from its Memory's trash, or every Memory deleted and purged — today's erasure of a medium, its blob rows and
-- its store objects (put on the reclaim list) — or, where a hold holds one, the dev instance reset whole with its store
-- volume. Deleting here in SQL would be a second copy of that erasure that no arity guard watches, and a bare DROP would
-- leave the media's blob rows with no arm and their objects in the store.
--
-- MariaDB runs DDL outside a transaction, so each check is added and dropped again before the next: a run refused at any
-- of them leaves no constraint behind, and the same migration runs again once the rows are gone. The derivative table's
-- check goes with its table.
ALTER TABLE veyq_hold_row ADD CONSTRAINT ck_hold_row_media_reset_first CHECK (source_table NOT IN ('veyq_media', 'veyq_media_derivative'));
ALTER TABLE veyq_hold_row DROP CONSTRAINT ck_hold_row_media_reset_first;
ALTER TABLE veyq_media ADD CONSTRAINT ck_media_reset_first CHECK (id IS NULL);
ALTER TABLE veyq_media DROP CONSTRAINT ck_media_reset_first;
ALTER TABLE veyq_media_derivative ADD CONSTRAINT ck_media_derivative_reset_first CHECK (id IS NULL);
DROP TABLE veyq_media_derivative;
ALTER TABLE veyq_media DROP FOREIGN KEY fk_media_blob;
ALTER TABLE veyq_media DROP COLUMN blob_id;
