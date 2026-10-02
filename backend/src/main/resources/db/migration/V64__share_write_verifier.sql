-- ADR 0076: a guest naming themself on a link proves they hold its key, not only its id. The client derives
-- writeToken = HKDF(K_share, "veyq/v1/share-write:" + shareId) and the share stores only SHA-256(writeToken) (opaque
-- base64) — the password verifier's shape, with the link's key in place of the password. veyq_share.id stays raw on
-- purpose: members get it back to rebuild the link, two seal contexts bind it (the recovery copy of K_share and every
-- guest's sealed name), and it rides every guest request path anyway; this column is what gates the write instead.
-- Nullable: a share minted before this column takes no new guest (fail closed) until a curator re-issues it.
ALTER TABLE veyq_share ADD COLUMN write_verifier TEXT NULL;
