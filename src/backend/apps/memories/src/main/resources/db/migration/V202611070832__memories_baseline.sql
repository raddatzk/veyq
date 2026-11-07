-- The Memories application's schema as one baseline (ADR 0079). It sorts after the core baseline and its foreign
-- keys point at core tables (veyq_scope) or at Memories tables created before them in this file; no core table
-- references a table created here. Same type rules as the core baseline: TEXT for opaque base64, MEDIUMTEXT where a
-- contract cap passes the 65 535-byte TEXT ceiling (scripts/validate_maxlength_storage.py), DATETIME(3) for every
-- time. Every id and id reference is the native UUID type (see the core baseline's header; inbox #387); the synthetic
-- composite keys and owner_key stay VARCHAR.
-- Operator-blind: names, dates, places, EXIF and item favorites are sealed under keys the server never holds. A
-- medium's original and derivatives are blobs of core's blob service (veyq_application_blob, held by the medium's id;
-- ADR 0113), so no table here names a blob.

-- Per-uploader buckets within a Memory (ADR 0034), keyed by an opaque owner_key ("user:{userId}" |
-- "share:{shareIdentityId}"). One primary per (node_id, owner_key) is held by the node row's lock in code
-- (ADR 0034), not by an index: the partial unique index it would need is outside what H2 and MariaDB both have.
-- name_editor_user_id / name_editor_identity_id: who last wrote sealed_name (ADR 0093 § 8) - a member, or the guest
-- identity whose display name a contributor's primary copies; at most one, null exactly while sealed_name is. No
-- foreign key, as share_identity_id on veyq_media has none: MediaGuestIdentityUsage keeps the identity while one names it.
CREATE TABLE veyq_bucket (
    id                      UUID NOT NULL PRIMARY KEY,
    scope_id                UUID NOT NULL,
    node_id                 UUID NOT NULL,
    owner_key               VARCHAR(64) NOT NULL,
    parent_bucket_id        UUID NULL,
    sealed_name             TEXT NULL,
    is_primary              BOOLEAN NOT NULL,
    created_at              DATETIME(3) NOT NULL,
    name_editor_user_id     UUID NULL,
    name_editor_identity_id UUID NULL,
    CONSTRAINT fk_bucket_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);
CREATE INDEX ix_bucket_node_owner ON veyq_bucket (node_id, owner_key);
CREATE INDEX ix_bucket_parent ON veyq_bucket (parent_bucket_id);

-- A medium of a Memory. Its original and derivative tiers are blobs of core's blob service (veyq_application_blob, held
-- by the medium's id; ADR 0113), so no column here names a blob. node_id carries no foreign key (nodes are soft-deleted;
-- the media are reclaimed by code). updated_at is set at attach and keeps the millisecond the client's JS Date round-trips;
-- it is the medium's only time besides deleted_at: no separate created_at, which nothing read but as its fallback (#398).
-- item_position is the medium's place in its node's list (ADR 0033 decisions 3 and 6): the node's item revision its last
-- change bumped to, stamped under the revision row's lock that bump holds to the commit, so places become visible in
-- commit order. The gallery's keyset is (item_position, id) (ix_media_keyset, so a page is a range read, not a sort of
-- the node): a list read in pages, and the live poll after it, never see a row land behind a cursor they already passed.
-- meta_revision is wrapped_meta's compare-and-set counter (ADR 0077) and deliberately not the node's item-list revision
-- (core's veyq_node_items_revision). ix_media_share_identity keeps a link's usage sum, taken under the tenant lock, off a
-- table scan (ADR 0073). meta_editor_user_id / meta_editor_identity_id: who last wrote wrapped_meta (ADR 0093 § 8) - the
-- uploader at attach, then the member who edits it; at most one, null exactly while wrapped_meta is; no foreign key, as
-- share_identity_id.
CREATE TABLE veyq_media (
    id                      UUID NOT NULL PRIMARY KEY,
    scope_id                UUID NOT NULL,
    node_id                 UUID NOT NULL,
    wrapped_meta            TEXT NULL,
    wrapped_exif            TEXT NULL,
    share_identity_id       UUID NULL,
    updated_at              DATETIME(3) NOT NULL,
    deleted_at              DATETIME(3) NULL,
    deleted_by              UUID NULL,
    bucket_id               UUID NULL,
    created_by              VARCHAR(64) NULL,
    meta_revision           BIGINT NOT NULL DEFAULT 0,
    meta_editor_user_id     UUID NULL,
    meta_editor_identity_id UUID NULL,
    item_position           INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_media_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_media_bucket FOREIGN KEY (bucket_id) REFERENCES veyq_bucket (id) ON DELETE SET NULL
);
CREATE INDEX ix_media_node ON veyq_media (node_id);
CREATE INDEX ix_media_scope ON veyq_media (scope_id);
CREATE INDEX ix_media_bucket ON veyq_media (bucket_id);
CREATE INDEX ix_media_share_identity ON veyq_media (share_identity_id);
CREATE INDEX ix_media_keyset ON veyq_media (node_id, item_position, id);

-- Per-(member, scope, node) item favorites (ADR 0068), one synthetic id `userId|scopeId|nodeId`. The marks are written
-- as 16-byte media ids beside the self-tag (inbox #500, ADR 0068 § 4), sealed under a value key of their own, minted at
-- every write; only that key's wrap under the scope key is stored beside them (wrapped_value_key, seal purpose
-- `value-key`, at most 128 characters), so Memories' scope re-key participant rewrites the wrap only (ADR 0115). The
-- rotator reads and stages them by the opaque mark_id, never by the row's own id, which spells the member's account id:
-- the rows stay as unattributed to the rotator as the node's read serves them (ADR 0068 § 9). The scope FK is
-- load-bearing: it puts the table in the erasure closure (scripts/validate_scope_erasure_arity.py). node_id has no FK on
-- purpose: node deletion is soft, so a cascade would destroy marks when a Memory is trashed.
CREATE TABLE veyq_scope_item_favorites (
    id                VARCHAR(110) NOT NULL,
    user_id           UUID NOT NULL,
    scope_id          UUID NOT NULL,
    node_id           UUID NOT NULL,
    ciphertext        MEDIUMTEXT NOT NULL,
    mark_id           UUID NOT NULL,
    wrapped_value_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_favorites_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_item_favorites_mark ON veyq_scope_item_favorites (mark_id);
CREATE INDEX ix_item_favorites_scope_node ON veyq_scope_item_favorites (scope_id, node_id);
CREATE INDEX ix_item_favorites_node ON veyq_scope_item_favorites (node_id);
