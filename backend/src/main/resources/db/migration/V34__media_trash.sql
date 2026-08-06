-- Media trash (uc.memory.delete-media, cr-0110). A first-party delete is a SOFT-delete into a recoverable
-- per-Memory trash: `deleted_at` (null = live) flips the media into the trash, and `deleted_by` records the
-- member who did it (the deletion audit; a user_id, so length 36 like veyq_membership). Trashed rows are
-- excluded from listNodeMedia (both the active keyset delta and the delete-reconcile id-set), so an open gallery
-- drops the tile via the cr-0107 `delete_revision` reconcile; restore clears the flags. The MediaTrashPurge job
-- hard-deletes rows + blobs + store objects past the retention window (default 30 days) — the irreversible half
-- of the reversible/irreversible split (security-model `Deletion & erasure`). Nullable + defaulting null, so
-- existing rows stay live and untouched. Types in the H2 ∩ MariaDB common subset (TIMESTAMP(3) mirrors
-- `updated_at`; VARCHAR for the opaque user id). Operator-blind: the trash state (flag + actor + timestamp) is
-- coarse operational metadata — never content.

ALTER TABLE veyq_media ADD COLUMN deleted_at TIMESTAMP(3);
ALTER TABLE veyq_media ADD COLUMN deleted_by VARCHAR(36);
