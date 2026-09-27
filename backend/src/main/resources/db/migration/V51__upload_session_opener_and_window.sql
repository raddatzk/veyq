-- An upload session is bound to whoever opened it, and stays live while it is being windowed (ADR 0013 §2/§3).
--
-- The opener is two nullable references, never both set: the account for a member upload, the share identity for
-- a contributor one. Two columns rather than one opaque key, so a link's open sessions can be summed by its
-- identity (ADR 0073 §4). A session from before this migration has neither and matches no caller; it ages out
-- through the stale-session purge, so nothing is migrated.
--
-- last_window_at is set at start and touched by every window; the stale-session purge keys on it instead of on
-- created_at. Backfilled from created_at, so the rows already open keep the deadline they had.
ALTER TABLE veyq_upload_session ADD COLUMN opener_user_id VARCHAR(36) NULL;
ALTER TABLE veyq_upload_session ADD COLUMN opener_share_identity_id VARCHAR(36) NULL;
ALTER TABLE veyq_upload_session ADD COLUMN last_window_at TIMESTAMP NULL;
UPDATE veyq_upload_session SET last_window_at = created_at;
ALTER TABLE veyq_upload_session MODIFY COLUMN last_window_at TIMESTAMP NOT NULL;
