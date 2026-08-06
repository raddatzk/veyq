-- Encrypted media blobs attached to the Memory tree (en.memory.media, cr-0028). A blob is a chunked-AEAD
-- ciphertext sequence sealed client-side under a random K_blob (wrapped under the Memory's K_mem); the bytes
-- live in an external object store (presigned direct-to-store, ADR 0013) under storage_key/<index>, never in
-- this DB. The server is operator-blind: it holds only opaque metadata (chunk_count, byte_size, the per-chunk
-- size manifest), the K_blob wrap, and the media→node link — never a plaintext byte, a content key, or a
-- filename. Types kept to the H2 ∩ MariaDB common subset (BIGINT for sizes, TEXT for opaque base64).

-- An OPEN upload session: opened by start (presign PUT), consumed by complete (stat + attach). Reclaimed by
-- GC when abandoned (a later slice). storage_key is the object-key prefix the chunks are stored under.
CREATE TABLE veyq_upload_session (
    id                VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id          VARCHAR(36) NOT NULL,
    storage_key       VARCHAR(64) NOT NULL,
    chunk_count       INT NOT NULL,
    byte_size         BIGINT NOT NULL,
    wrapped_blob_key  TEXT NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    CONSTRAINT fk_upload_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

CREATE INDEX ix_upload_scope ON veyq_upload_session (scope_id);

-- A completed blob: the chunk manifest (per-chunk ciphertext sizes, JSON array) + the K_blob wrap. The bytes
-- stay in the object store under storage_key/<index>.
CREATE TABLE veyq_blob (
    id                VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id          VARCHAR(36) NOT NULL,
    storage_key       VARCHAR(64) NOT NULL,
    chunk_count       INT NOT NULL,
    byte_size         BIGINT NOT NULL,
    wrapped_blob_key  TEXT NOT NULL,
    manifest          TEXT,
    created_at        TIMESTAMP NOT NULL,
    CONSTRAINT fk_blob_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

CREATE INDEX ix_blob_scope ON veyq_blob (scope_id);

-- A media: links a blob to a node (MEMORY/CHAPTER). A blob may be attached to several nodes; a node may hold
-- many media. node_id is a veyq_node id (no cross-table FK to keep the H2 ∩ MariaDB subset simple).
CREATE TABLE veyq_media (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id    VARCHAR(36) NOT NULL,
    node_id     VARCHAR(36) NOT NULL,
    blob_id     VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_media_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_media_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id)
);

CREATE INDEX ix_media_node ON veyq_media (node_id);
CREATE INDEX ix_media_scope ON veyq_media (scope_id);
