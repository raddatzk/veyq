-- Contributor public shares (uc.sharing.share-contributor, cr-0101, ADR 0030). A CONTRIBUTOR share lets an
-- anonymous visitor ADD photos to a shared Memory (not just view). Before contributing they name themselves;
-- the name is sealed under K_mem (operator-blind) and stored as an anonymous SHARE IDENTITY — the forward-compat
-- hook for buckets. Each contributed media is stamped with that identity. The server stays operator-blind: it
-- holds only the ciphertext name + a capability-token hash + opaque ids, never the name or the person.
-- Types kept to the H2 ∩ MariaDB common subset (TEXT for opaque base64, VARCHAR for short ids, TIMESTAMP).
CREATE TABLE veyq_share_identity (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,  -- opaque identity id (UUID)
    share_id            VARCHAR(64) NOT NULL,              -- the contributor share this identity belongs to
    sealed_display_name TEXT        NOT NULL,              -- the contributor's display name sealed under K_mem, opaque base64
    token_hash          VARCHAR(64) NOT NULL,              -- SHA-256 hash of the unguessable contributor capability token
    created_at          TIMESTAMP   NOT NULL,
    CONSTRAINT fk_share_identity_share FOREIGN KEY (share_id) REFERENCES veyq_share (id)
);

CREATE INDEX ix_share_identity_lookup ON veyq_share_identity (share_id, token_hash);

-- The contributor identity that added a media (cr-0101); null for media the curator uploaded. Opaque link only.
ALTER TABLE veyq_media ADD COLUMN share_identity_id VARCHAR(36);
