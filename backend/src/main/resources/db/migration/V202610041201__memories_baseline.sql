-- The Memories application's schema as one baseline (ADR 0079). It sorts after the core baseline and its foreign
-- keys point at core tables (veyq_scope, veyq_blob) or at Memories tables created before them in this file; no
-- core table references a table created here. Same type rules as the core baseline: TEXT for opaque base64,
-- MEDIUMTEXT where a contract cap passes the 65 535-byte TEXT ceiling (scripts/validate_maxlength_storage.py).
-- Operator-blind: names, dates, places, EXIF and favorites are sealed under keys the server never holds.

-- Per-(user, scope) pinned-Memory set, sealed under K_SC. The natural key is one synthetic id
-- (`userId|scopeId`) so an upsert is a primary-key lookup.
CREATE TABLE veyq_scope_favorites (
    id         VARCHAR(73)  NOT NULL,
    user_id    VARCHAR(36)  NOT NULL,
    scope_id   VARCHAR(36)  NOT NULL,
    ciphertext MEDIUMTEXT   NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

-- Per-uploader buckets within a Memory (ADR 0034), keyed by an opaque owner_key ("user:{userId}" |
-- "share:{shareIdentityId}"). One primary per (node_id, owner_key) is held by the node row's lock in code
-- (ADR 0034), not by an index: the partial unique index it would need is outside what H2 and MariaDB both have.
CREATE TABLE veyq_bucket (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id         VARCHAR(36) NOT NULL,
    node_id          VARCHAR(36) NOT NULL,
    owner_key        VARCHAR(64) NOT NULL,
    parent_bucket_id VARCHAR(36),
    sealed_name      TEXT,
    is_primary       BOOLEAN     NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT fk_bucket_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);
CREATE INDEX ix_bucket_node_owner ON veyq_bucket (node_id, owner_key);
CREATE INDEX ix_bucket_parent ON veyq_bucket (parent_bucket_id);

-- A medium: its original blob attached to a node. node_id carries no foreign key (nodes are soft-deleted; the
-- media are reclaimed by code). updated_at is the keyset cursor's sort key and TIMESTAMP(3) on purpose: the
-- cursor round-trips through a JS Date, which holds milliseconds, so a finer stored value would read as later
-- than the echoed cursor and be re-fetched forever. meta_version is wrapped_meta's compare-and-set counter
-- (ADR 0077) and deliberately not the node's media_revision. ix_media_share_identity keeps a link's usage sum,
-- taken under the tenant lock, off a table scan (ADR 0073).
CREATE TABLE veyq_media (
    id                VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id          VARCHAR(36) NOT NULL,
    node_id           VARCHAR(36) NOT NULL,
    blob_id           VARCHAR(36) NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    wrapped_meta      TEXT NULL,
    wrapped_exif      TEXT NULL,
    share_identity_id VARCHAR(36),
    updated_at        TIMESTAMP(3),
    deleted_at        TIMESTAMP(3),
    deleted_by        VARCHAR(36),
    bucket_id         VARCHAR(36),
    created_by        VARCHAR(64),
    meta_version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_media_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_media_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_media_bucket FOREIGN KEY (bucket_id) REFERENCES veyq_bucket (id) ON DELETE SET NULL
);
CREATE INDEX ix_media_node ON veyq_media (node_id);
CREATE INDEX ix_media_scope ON veyq_media (scope_id);
CREATE INDEX ix_media_bucket ON veyq_media (bucket_id);
CREATE INDEX ix_media_share_identity ON veyq_media (share_identity_id);

-- A medium's derivative tiers (THUMBNAIL, PREVIEW), each its own blob under its own K_blob: a key shared across
-- tiers would let the blind store splice a chunk from one tier into another.
CREATE TABLE veyq_media_derivative (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    media_id    VARCHAR(36) NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    blob_id     VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_derivative_media FOREIGN KEY (media_id) REFERENCES veyq_media (id),
    CONSTRAINT fk_derivative_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id)
);
CREATE INDEX ix_derivative_media ON veyq_media_derivative (media_id);

-- Per-(member, scope, node) item favorites (ADR 0068), one synthetic id `userId|scopeId|nodeId`. The scope FK is
-- load-bearing: it puts the table in the erasure closure (scripts/validate_scope_erasure_arity.py). node_id has
-- no FK on purpose: node deletion is soft, so a cascade would destroy marks when a Memory is trashed.
CREATE TABLE veyq_scope_item_favorites (
    id         VARCHAR(110) NOT NULL,
    user_id    VARCHAR(36)  NOT NULL,
    scope_id   VARCHAR(36)  NOT NULL,
    node_id    VARCHAR(36)  NOT NULL,
    ciphertext MEDIUMTEXT   NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_favorites_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_item_favorites_scope_node ON veyq_scope_item_favorites (scope_id, node_id);
CREATE INDEX ix_item_favorites_node ON veyq_scope_item_favorites (node_id);
