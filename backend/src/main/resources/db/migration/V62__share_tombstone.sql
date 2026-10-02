-- ADR 0029 §7: a deleted share link is a TOMBSTONE, not a row delete. The row keeps its id, scope, node, role and
-- dates (its guest identities hang off it by foreign key, and an uploader guest's wrapped key opens their uploads);
-- every ciphertext column goes, so sealed_name may now be null, and deleted_at marks it. A share id is never minted
-- twice: the scope purge must remove share rows, and records each erased id here (the id only) for create to refuse.
ALTER TABLE veyq_share ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE veyq_share MODIFY COLUMN sealed_name TEXT NULL;
CREATE TABLE veyq_share_retired_id (
    id VARCHAR(64) NOT NULL PRIMARY KEY
);
