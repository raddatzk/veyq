-- A content measure hangs on the stored object (owner 2026-10-19, ADR 0093 § 6, ADR 0013 § 5): a measure on an
-- application's item whose stored objects other blob rows of its node still name (a cover or a banner sharing a medium's
-- object) does not end when the item is erased. held_objects lists those objects' storage keys, space-separated, while the
-- measure stands on them alone; every row naming one is refused as blocked, and the measure ends when the last row naming
-- any of them goes (BlobUploadStore.deleteBlobRow, ScopePurge) or a moderator lifts it. A label, not a claim: no object is
-- kept alive by a measure (the blob rows keep it), so it is no table of the reconcile's claims or the backup's blob index.
ALTER TABLE veyq_moderation_block ADD COLUMN held_objects TEXT;
