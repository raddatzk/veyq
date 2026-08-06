-- Media derivatives (en.memory.derivatives, cr-0045). A media's `blob_id` (veyq_media) stays the **original**
-- (pristine) blob; its role-tagged derivative tiers — THUMBNAIL (grid), PREVIEW (lightbox) — each their own
-- encrypted blob, link here. Each derivative blob keeps its **own** K_blob (wrapped under K_mem); never a key
-- shared across tiers (a shared key would let the blind store splice a chunk across derivatives — the
-- chunk_frame AAD only binds index‖total within a blob). No backfill: the instance is treated as fresh, so
-- there is no migration of pre-derivative media. Operator-blind: only ciphertext blobs + the opaque role tag.
-- Types kept to the H2 ∩ MariaDB common subset (VARCHAR for short opaque ids + the role token).

CREATE TABLE veyq_media_derivative (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    media_id    VARCHAR(36) NOT NULL,
    kind        VARCHAR(16) NOT NULL,  -- THUMBNAIL | PREVIEW (the tier's role)
    blob_id     VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_derivative_media FOREIGN KEY (media_id) REFERENCES veyq_media (id),
    CONSTRAINT fk_derivative_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id)
);

CREATE INDEX ix_derivative_media ON veyq_media_derivative (media_id);
