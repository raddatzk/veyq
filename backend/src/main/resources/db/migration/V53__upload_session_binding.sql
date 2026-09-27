-- An upload session carries the ids its K_blob wrap is sealed bound to (ADR 0072): the client-minted blob id the
-- blob is stored under, the owner it belongs to (the client-minted media id, or the node for a cover) and its role
-- (ORIGINAL / THUMBNAIL / PREVIEW / COVER). Completion checks owner and role against where the blob is attached,
-- and stores the blob under blob_id — so the wrap opens exactly where it lands.
--
-- Nullable because a session opened before this migration has none of them: it cannot complete (it fails
-- upload_mismatch) and ages out through the stale-session purge, so nothing is migrated. blob_id is unique so two
-- open sessions can never promise the same blob id; UNIQUE admits any number of NULLs on both engines.
ALTER TABLE veyq_upload_session ADD COLUMN blob_id VARCHAR(36) NULL;
ALTER TABLE veyq_upload_session ADD COLUMN owner_id VARCHAR(36) NULL;
ALTER TABLE veyq_upload_session ADD COLUMN blob_role VARCHAR(16) NULL;
CREATE UNIQUE INDEX ux_upload_session_blob_id ON veyq_upload_session (blob_id);
