-- The platform's (core's) schema as one baseline (ADR 0079): every core table in dependency order. Each application's
-- tables live in its own baseline, at a later version, and reference core tables only; no core table references them.
--
-- Operator-blind throughout: a column the server could read content from does not exist. TEXT holds opaque
-- base64 ciphertext, VARCHAR hashes and tokens, UUID every id that is exactly a UUID. TEXT is a 65 535-byte ceiling on MariaDB (H2 does not
-- enforce it), so a contract cap above 65 532 base64 chars needs MEDIUMTEXT — scripts/validate_maxlength_storage.py
-- holds the two sides together. Types stay in what H2 MODE=MariaDB (the test engine) and MariaDB both parse.
-- Constraint and index names are cited by code, guards and tests: renaming one is a schema change.
--
-- Ids are MariaDB's native UUID type (inbox #387): 16 bytes, printed as the canonical lowercase string, so the entities
-- keep `String id` with no converter and a SQL console still reads them. Only a column that holds nothing but a UUID is
-- one (every id, FK and *_user_id / *_scope_id style reference); the share id (a 256-bit token), the synthetic composite
-- keys `userId|scopeId[|nodeId]`, owner_key, slug_token and every hash stay VARCHAR. A non-UUID string in a UUID column
-- is error 1292 on INSERT, and matches nothing on a SELECT, so each id enters through a `format: uuid` contract field,
-- whose type (java.util.UUID) refuses it with a 400/404 before a query is built.
--
-- Every time column is DATETIME(3), never TIMESTAMP (inbox #509): MariaDB's TIMESTAMP counts seconds in 32 bits and ends
-- at 2038-01-19T03:14:07Z, while DATETIME holds 1000-01-01 to 9999-12-31 and converts nothing. Hibernate writes an
-- Instant as its UTC wall clock, so every time column holds UTC by convention, whatever the session's time_zone. The
-- millisecond everywhere, not whole seconds: MariaDB truncates a fraction to the column's precision and H2 rounds it,
-- so a whole-second column stored, on H2, a time up to half a second after the moment it was written.
-- scripts/validate_time_columns.py keeps a TIMESTAMP column from coming back.
--
-- The two tables this baseline seeds (veyq_external_service, veyq_release_check) are configuration, not data: the
-- restore's emptiness gate skips them (src/tools/instance_backup.py, NOT_DATA).

-- First-run setup: the single-owner lock.
CREATE TABLE veyq_server_setup (
    id INT NOT NULL PRIMARY KEY
);

-- An account. Its keys are public keys or ciphertext; the password never reaches the server (ADR 0003).
-- email_confirmed_at is a fact about the address, not a permission: nothing is gated on it (ADR 0074).
-- secret_key_proof_public_key is the Ed25519 public key rotation, change-password and recovery are signed under
-- (ADR 0060, ADR 0054).
-- donation_banner_dismissed: whether the holder dismissed veyq's donation banner (uc.platform.support-the-project,
-- ADR 0124), account-global like require_group_add_consent. Low-sensitivity server-visible operational metadata,
-- never content; served only to the account holder.
CREATE TABLE veyq_user (
    id                           UUID NOT NULL PRIMARY KEY,
    server_role                  VARCHAR(32) NOT NULL,
    suite_id                     VARCHAR(16) NOT NULL,
    identity_public_key          TEXT NOT NULL,
    wrapped_identity_private_key TEXT NOT NULL,
    auth_public_key              TEXT NOT NULL,
    email                        VARCHAR(254) NOT NULL,
    require_group_add_consent    BOOLEAN NOT NULL DEFAULT FALSE,
    secret_key_proof_public_key  TEXT NULL,
    email_confirmed_at           DATETIME(3) NULL,
    donation_banner_dismissed    BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_veyq_user_email ON veyq_user (email);

-- A Master Key wrap per unlock method; kdf_params are opaque to the server (null for device/passkey).
CREATE TABLE veyq_master_key_wrap (
    id          UUID NOT NULL PRIMARY KEY,
    user_id     UUID NOT NULL,
    method      VARCHAR(16) NOT NULL,
    wrapped_key TEXT NOT NULL,
    kdf_params  TEXT NULL,
    CONSTRAINT fk_master_key_wrap_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- The instance singleton: the public auth salt (ADR 0003). The config-secret KEK's fingerprint is not held here:
-- every sealed value carries the fingerprint of the key that sealed it (ADR 0089 decision 4).
CREATE TABLE veyq_instance (
    id            INT NOT NULL PRIMARY KEY,
    instance_salt TEXT NOT NULL
);

-- The object-store setting (ADR 0089): one row once the Server Owner has set a store, none before. bundled = a
-- reference to the deployment's bundled-store hint, whose values are never copied here, so every connection
-- column is null for it. The secrets are sealed under the config-secret KEK (ADR 0050), each with the
-- fingerprint of the key that sealed it. check_* is the last check of THIS setting, cleared when it changes;
-- check_steps is its per-step result as JSON.
CREATE TABLE veyq_store_setting (
    id                             INT NOT NULL PRIMARY KEY,
    bundled                        BOOLEAN NOT NULL,
    store_type                     VARCHAR(16) NULL,
    endpoint                       VARCHAR(2048) NULL,
    public_endpoint                VARCHAR(2048) NULL,
    bucket                         VARCHAR(63) NULL,
    region                         VARCHAR(64) NULL,
    access_key                     VARCHAR(256) NULL,
    secret_key_ciphertext          TEXT NULL,
    secret_key_nonce               TEXT NULL,
    secret_key_fingerprint         VARCHAR(64) NULL,
    garage_admin_endpoint          VARCHAR(2048) NULL,
    garage_admin_token_ciphertext  TEXT NULL,
    garage_admin_token_nonce       TEXT NULL,
    garage_admin_token_fingerprint VARCHAR(64) NULL,
    check_passed                   BOOLEAN NULL,
    checked_at                     DATETIME(3) NULL,
    check_steps                    TEXT NULL
);

-- The capability switches (ADR 0088 decision 12): one row per register entry the Server Owner switched off, none
-- for an entry that is on. id is the catalog's dotted id (contracts/platform/capabilities.json), not a row id, so
-- VARCHAR. An entry's effective state is the AND along its path, computed by the server, never stored.
CREATE TABLE veyq_capability_switch (
    id VARCHAR(64) NOT NULL PRIMARY KEY
);

-- A login challenge is keyed by its nonce, not by the email: one row per email let anyone who knew an address
-- replace or burn the challenge a device was still signing. VARCHAR because a TEXT cannot be a MariaDB key.
CREATE TABLE veyq_login_challenge (
    nonce      VARCHAR(64) NOT NULL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    expires_at DATETIME(3) NOT NULL
);

-- Sessions (ADR 0004): a family per login/device; only token HASHES are stored.
CREATE TABLE veyq_session_family (
    id           UUID NOT NULL PRIMARY KEY,
    user_id      UUID NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_session_family_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

CREATE TABLE veyq_refresh_token (
    id         UUID NOT NULL PRIMARY KEY,
    family_id  UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    generation INT NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES veyq_session_family (id)
);
CREATE UNIQUE INDEX ux_refresh_token_hash ON veyq_refresh_token (token_hash);
-- The hourly purge deletes by expires_at; without this index MariaDB next-key-locks every row it scans until
-- commit (measured 10-13 s of blocked refreshes at ~4 M rows). Deliberately not on veyq_access_token or
-- veyq_login_challenge: their rows are mostly expired when the purge runs, and the optimizer full-scans anyway.
CREATE INDEX ix_refresh_token_expires ON veyq_refresh_token (expires_at);

CREATE TABLE veyq_access_token (
    id         UUID NOT NULL PRIMARY KEY,
    family_id  UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_access_token_family FOREIGN KEY (family_id) REFERENCES veyq_session_family (id)
);
CREATE UNIQUE INDEX ux_access_token_hash ON veyq_access_token (token_hash);

-- A tenant, resolved by Host. The terms (ADR 0035) are the space owner's free text as a per-locale JSON map,
-- so utf8mb4 can take them past the TEXT ceiling: MEDIUMTEXT. share_link_limit_bytes (ADR 0073 §4) and
-- storage_quota_bytes (§1, null = no quota) live here because every upload start locks this row (§5).
-- TenantEntity repeats the link-limit default for a tenant born through the entity. deletion_due_at is when
-- TenantPurge erases a tenant in PENDING_DELETION (ADR 0101); NULL for every other status.
CREATE TABLE veyq_tenant (
    id                       UUID NOT NULL PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL,
    domain                   VARCHAR(253) NOT NULL,
    owner_email              VARCHAR(254) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    terms_markdown_by_locale MEDIUMTEXT NULL,
    terms_revision           INT NOT NULL DEFAULT 0,
    share_link_limit_bytes   BIGINT NOT NULL DEFAULT 10737418240,
    storage_quota_bytes      BIGINT NULL,
    deletion_due_at          DATETIME(3) NULL,
    CONSTRAINT ux_tenant_domain UNIQUE (domain)
);

-- A tenant invite: only the SHA-256 token hash is stored. A member invite is a placeholder member (ADR 0111 decisions
-- 1-5): it holds the invite key the owner signed and a grant wrapped to it, like a membership holds one.
-- invite_public_key / invite_key_signature / signer_*: the invite key's public bundle, the owner's signature over it,
-- and the owner's identity key as it signed, so a later rotation of the owner does not void the invite. wrapped_grant /
-- granted_epoch / grant_signature / granter_*: the grant a membership carries — the epoch key wrapped to the invite key,
-- signed by its granter (the inviting owner, then whoever's rotation re-wrapped it). anchor_*: the invite's anchor (ADR
-- 0111 decision 19) — the inviting owner's own grant of the tenant key, sealed to the invite key, its epoch and the
-- owner's `grant` signature over it; written at mint and reissue (equal to the grant), moved only by a tenant rotation
-- its signer commits. Every one of these is null on an owner invite (it carries no key) and on every invite that is no
-- longer ACTIVE: the statement that ends one nulls them all. ix_tenant_invite_signer: the securing's plan reads the
-- open invites an account signed, in every tenant.
CREATE TABLE veyq_tenant_invite (
    id                          UUID NOT NULL PRIMARY KEY,
    tenant_id                   UUID NOT NULL,
    owner_email                 VARCHAR(254) NOT NULL,
    token_hash                  VARCHAR(64) NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    expires_at                  DATETIME(3) NOT NULL,
    created_at                  DATETIME(3) NOT NULL, -- the millisecond matters: the newest invite of a tenant is the current one
    role                        VARCHAR(16) NOT NULL DEFAULT 'OWNER',
    invite_public_key           TEXT NULL,
    invite_key_signature        TEXT NULL,
    signer_user_id              UUID NULL,
    signer_identity_public_key  TEXT NULL,
    wrapped_grant               TEXT NULL,
    granted_epoch               INT NULL,
    grant_signature             TEXT NULL,
    granter_user_id             UUID NULL,
    granter_identity_public_key TEXT NULL,
    anchor_wrapped_grant        TEXT NULL,
    anchor_epoch                INT NULL,
    anchor_grant_signature      TEXT NULL,
    CONSTRAINT fk_tenant_invite_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_tenant_invite_token_hash ON veyq_tenant_invite (token_hash);
CREATE INDEX ix_tenant_invite_tenant ON veyq_tenant_invite (tenant_id);
CREATE INDEX ix_tenant_invite_signer ON veyq_tenant_invite (signer_user_id);

-- A scope (TENANT / GROUP / PERSONAL). It holds no key. personal_owner is the owning account on a PERSONAL
-- scope and null on every other kind, so ux_scope_personal_owner allows one personal scope per member and
-- tenant (both engines admit any number of nulls in a unique index). name_editor_user_id / icon_editor_user_id: the
-- member who last wrote encrypted_name / sealed_icon (ADR 0093 § 8, en.platform.field-editor) - no foreign key, a
-- member who left stays named; only a member writes either, so there is no guest column. Null exactly while the field
-- is (ScopesStore writes both together; the per-writer slice tests pin it). A group's name and icon are each sealed
-- under a value key of their own, minted at every write; only its wrap under the scope key is stored beside the value
-- (wrapped_name_value_key, wrapped_icon_value_key: seal purpose `value-key`, base64, at most 128 characters), non-null
-- exactly when the value is, so a rotation re-wraps keys and never rewrites a value (ADR 0115).
CREATE TABLE veyq_scope (
    id                     UUID NOT NULL PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    kind                   VARCHAR(16) NOT NULL,
    encrypted_name         TEXT NULL,
    current_epoch          INT NOT NULL DEFAULT 0,
    deleted_at             DATETIME(3) NULL,
    name_epoch             INT NOT NULL DEFAULT 0,
    sealed_icon            TEXT NULL,
    icon_epoch             INT NOT NULL DEFAULT 0,
    member_removals        INT NOT NULL DEFAULT 0,
    personal_owner         UUID NULL,
    name_editor_user_id    UUID NULL,
    icon_editor_user_id    UUID NULL,
    wrapped_name_value_key VARCHAR(128) NULL,
    wrapped_icon_value_key VARCHAR(128) NULL,
    CONSTRAINT fk_scope_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_scope_personal_owner ON veyq_scope (tenant_id, personal_owner);
CREATE INDEX ix_scope_tenant ON veyq_scope (tenant_id);

-- Membership: (user, scope, role, grant) (ADR 0007). wrapped_grant is null for a grant-less member until a
-- key-holder delivers it. The fk_m21_* names are the table's constraint names and are kept as they are.
CREATE TABLE veyq_membership (
    id                          UUID NOT NULL PRIMARY KEY,
    user_id                     UUID NOT NULL,
    tenant_id                   UUID NOT NULL,
    scope_id                    UUID NOT NULL,
    role                        VARCHAR(16) NOT NULL,
    wrapped_grant               TEXT NULL,
    created_at                  DATETIME(3) NOT NULL, -- the millisecond matters: the earliest-joined member is the orphan rule's successor
    granted_epoch               INT NOT NULL DEFAULT 0,
    pending_consent             BOOLEAN NOT NULL DEFAULT FALSE,
    sealed_name_key             TEXT NULL,
    added_by                    UUID NULL,
    granter_user_id             UUID NULL,
    grant_signature             TEXT NULL,
    granter_identity_public_key TEXT NULL,
    CONSTRAINT fk_m21_user FOREIGN KEY (user_id) REFERENCES veyq_user (id),
    CONSTRAINT fk_m21_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id),
    CONSTRAINT fk_m21_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_membership_user_scope ON veyq_membership (user_id, scope_id);

-- The node tree (ADR 0079: the platform's). parent_id is the parent node id, or the SCOPE id for a root node, so
-- it is not a foreign key; slug uniqueness is per parent slot. ux_node_parent_slug spans trashed rows on
-- purpose: a trash frees the slug by writing the node's own id into slug_token (ADR 0061), because neither
-- engine pair has a filtered index. No column here is an application's (inbox #341): a Memory's cover is an
-- attachment (veyq_node_attachment, slot COVER) and its item-list counters are core's own table (veyq_node_items_revision).
-- created_at orders equal-named siblings, then the id (ADR 0015). Who last wrote each sealed field group (ADR 0093 § 8,
-- en.platform.field-editor): name_editor_* for encrypted_name, meta_editor_* for wrapped_meta - a member's id (no foreign
-- key, as the attachment's uploader: a member who left stays named) or a guest identity's (fk_node_*_editor_identity,
-- below), exactly one; the name always has one, the meta one exactly while it is set (ck_node_name_editor,
-- ck_node_meta_editor). Not share_identity_id: that column names an identity folder's owner and a move clears it.
-- A node's key (ADR 0115, ADR 0114 decisions 3-4): epoch is the node's OWN key's (0 at its mint, +1 with every
-- replacement); scope_epoch is the scope's epoch when the key was minted, which the `content-key` and `node-name` seals
-- bind. A node lags exactly when it is marked due (rekey_due): a rotation's commit marks every node of its scope, a
-- link's close every node below the link's root, and the node's re-key commit clears the mark. ix_node_scope_due
-- answers "is any node of this scope due?" (ScopeRekeying) with one range read.
CREATE TABLE veyq_node (
    id                      UUID NOT NULL PRIMARY KEY,
    scope_id                UUID NOT NULL,
    parent_id               UUID NOT NULL,
    kind                    VARCHAR(16) NOT NULL,
    encrypted_name          TEXT NOT NULL,
    slug_token              VARCHAR(128) NOT NULL,
    order_index             INT NOT NULL,
    wrapped_content_key     TEXT NOT NULL,
    created_at              DATETIME(3) NOT NULL,
    wrapped_meta            TEXT NULL,
    epoch                   INT NOT NULL DEFAULT 0,
    deleted_at              DATETIME(3) NULL,
    sealed_drop_private_key TEXT NULL,
    meta_revision           BIGINT NOT NULL DEFAULT 0,
    placement_revision      BIGINT NOT NULL DEFAULT 0,
    share_identity_id       UUID NULL,
    name_editor_user_id     UUID NULL,
    name_editor_identity_id UUID NULL,
    meta_editor_user_id     UUID NULL,
    meta_editor_identity_id UUID NULL,
    scope_epoch             INT NOT NULL DEFAULT 0,
    rekey_due               BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_node_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT ck_node_name_editor CHECK ((name_editor_user_id IS NULL AND name_editor_identity_id IS NOT NULL)
        OR (name_editor_user_id IS NOT NULL AND name_editor_identity_id IS NULL)),
    CONSTRAINT ck_node_meta_editor CHECK ((wrapped_meta IS NULL AND meta_editor_user_id IS NULL AND meta_editor_identity_id IS NULL)
        OR (wrapped_meta IS NOT NULL AND meta_editor_user_id IS NULL AND meta_editor_identity_id IS NOT NULL)
        OR (wrapped_meta IS NOT NULL AND meta_editor_user_id IS NOT NULL AND meta_editor_identity_id IS NULL))
);
CREATE UNIQUE INDEX ux_node_parent_slug ON veyq_node (parent_id, slug_token);
-- A guest's identity folder (ADR 0080 decision 11): share_identity_id names the WRITE_ONLY identity that holds the folder
-- in its link's root (a drop box). One per (parent, identity): a NULL never collides, so every other node is free of it.
CREATE UNIQUE INDEX ux_node_parent_identity ON veyq_node (parent_id, share_identity_id);
CREATE INDEX ix_node_scope ON veyq_node (scope_id);

-- A scope's tree revision (ADR 0081): one monotonic counter per scope, bumped in the SAME transaction as every change
-- a level view shows (a node created, renamed, moved, trashed, restored, purged; a blob attached to, replaced on or
-- removed from a node) and served as the node listing's ETag. A row of its own, not a column of veyq_scope: the scope
-- row is the tree's lock, and a bump that took it again after a node row would invert the documented lock order.
-- No row reads as 0; the first bump inserts it, under the scope lock every bumping writer already holds. Not
-- cascading: the node eraser (phase 700) deletes it before the scope row goes.
CREATE TABLE veyq_scope_tree_revision (
    scope_id UUID NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_tree_revision_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

-- A node's item-list counters (ADR 0033, inbox #410): the platform's change signal for the records an application keeps
-- INSIDE a node (Memories' media, Expenses' revisions), one row per node whose items ever changed. revision moves on
-- every change and is the list's ETag; delete_revision only on a removal, gating the id-set reconcile. One row, so one
-- read is a consistent pair. No row reads as 0/0; the first bump inserts it under the node row's lock
-- (NodeItemsRevisions.bump). Not the tree revision above: that one covers the node list, this one the items inside one
-- node. fk_items_revision_node is not cascading: the node-subtree and scope erasers delete the row before the node goes.
CREATE TABLE veyq_node_items_revision (
    node_id         UUID NOT NULL PRIMARY KEY,
    revision        INT NOT NULL,
    delete_revision INT NOT NULL,
    CONSTRAINT fk_items_revision_node FOREIGN KEY (node_id) REFERENCES veyq_node (id)
);

-- An open upload session (ADR 0013): bound to its opener (an account or a share identity, never both) and to
-- the ids its K_blob wrap is sealed to (blob_id, owner_id, blob_role; ADR 0072). blob_id is unique so two open
-- sessions never promise one blob id. ix_upload_session_storage_key: the bucket reconcile asks every claim table for
-- one listed page's storage keys at a time (`storage_key IN (...)`); without it each page is a full scan.
CREATE TABLE veyq_upload_session (
    id                       UUID NOT NULL PRIMARY KEY,
    scope_id                 UUID NOT NULL,
    storage_key              VARCHAR(64) NOT NULL,
    chunk_count              INT NOT NULL,
    byte_size                BIGINT NOT NULL,
    wrapped_blob_key         TEXT NOT NULL,
    wrapped_meta             TEXT NULL,
    opener_user_id           UUID NULL,
    opener_share_identity_id UUID NULL,
    last_window_at           DATETIME(3) NOT NULL,
    blob_id                  UUID NULL,
    owner_id                 UUID NULL,
    blob_role                VARCHAR(16) NULL,
    CONSTRAINT fk_upload_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_upload_session_blob_id ON veyq_upload_session (blob_id);
CREATE INDEX ix_upload_scope ON veyq_upload_session (scope_id);
CREATE INDEX ix_upload_session_storage_key ON veyq_upload_session (storage_key);

-- A completed blob: the chunk manifest and the K_blob wrap; the bytes stay in the object store under
-- storage_key/<index>. wrapped_meta is the blob's metadata sealed under its own K_blob (the blob-meta seal),
-- copied from its upload session and written once: no statement updates it. ix_blob_storage_key serves the bucket
-- reconcile's page probe (`storage_key IN (...)`), as on every claim table.
CREATE TABLE veyq_blob (
    id               UUID NOT NULL PRIMARY KEY,
    scope_id         UUID NOT NULL,
    storage_key      VARCHAR(64) NOT NULL,
    chunk_count      INT NOT NULL,
    byte_size        BIGINT NOT NULL,
    wrapped_blob_key TEXT NOT NULL,
    wrapped_meta     TEXT NULL,
    manifest         TEXT NULL,
    CONSTRAINT fk_blob_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_blob_scope ON veyq_blob (scope_id);
CREATE INDEX ix_blob_storage_key ON veyq_blob (storage_key);

-- The background-task pull queue (ADR 0021): opaque envelopes routed by scope and capability tag. created_at's
-- millisecond is load-bearing: the claim order and the "newest covering job" of a compromise rotation read it,
-- and a dead-letter and its re-queue can land in one second.
CREATE TABLE veyq_job (
    id               UUID NOT NULL PRIMARY KEY,
    scope_id         UUID NOT NULL,
    capability_tag   VARCHAR(64) NOT NULL,
    resource_ref     VARCHAR(64) NULL,
    status           VARCHAR(16) NOT NULL,
    lease_holder     UUID NULL,
    lease_expires_at DATETIME(3) NULL,
    attempts         INT NOT NULL,
    max_attempts     INT NOT NULL,
    sealed_body      MEDIUMTEXT NULL,
    sealed_result    MEDIUMTEXT NULL,
    created_at       DATETIME(3) NOT NULL,
    rotated_epoch    INT NULL,
    CONSTRAINT fk_job_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_job_claimable ON veyq_job (scope_id, capability_tag, status);

-- Per-tenant member identity (ADR 0023). sealed_avatar is the member's profile picture (ADR 0084): one constant-length
-- ciphertext sealed under K_tenant, null = none; avatar_revision is bumped on every set and clear (it is the avatar
-- read's ETag) and rides the directory, never the bytes. sealed_join_record is the member's join record, opaque
-- ciphertext under the tenant key (field `member-join-record`), written once at the invite's accept beside the name and
-- picture; null for a tenant's first owner (ADR 0111). join_signer_user_id: who rooted the join (ADR 0111 decision 21),
-- the signer of the invite it was accepted through, written at accept with the record; join_endorsement that owner's
-- endorsement of the sealed record, written by their securing — null until then, for a record their securing declared,
-- and for the tenant's first owner. No foreign key on the signer: the id is kept when the signer's account is erased
-- (AccountPurge), as a grant keeps its granter's. Each of name, picture and join record is sealed under a value key of
-- its own, minted at every write; wrapped_*_value_key is that key's wrap under the tenant key (seal purpose `value-key`,
-- at most 128 characters), non-null exactly when its value is, so a rotation re-wraps keys and never rewrites a value
-- (ADR 0115).
CREATE TABLE veyq_tenant_profile (
    id                            UUID NOT NULL PRIMARY KEY,
    user_id                       UUID NOT NULL,
    tenant_id                     UUID NOT NULL,
    username                      VARCHAR(32) NOT NULL,
    sealed_name                   TEXT NULL,
    sealed_avatar                 TEXT NULL,
    avatar_revision               INT NOT NULL DEFAULT 0,
    sealed_join_record            TEXT NULL,
    join_signer_user_id           UUID NULL,
    join_endorsement              TEXT NULL,
    wrapped_name_value_key        VARCHAR(128) NULL,
    wrapped_avatar_value_key      VARCHAR(128) NULL,
    wrapped_join_record_value_key VARCHAR(128) NULL,
    CONSTRAINT fk_tp_user FOREIGN KEY (user_id) REFERENCES veyq_user (id),
    CONSTRAINT fk_tp_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_tp_user_tenant ON veyq_tenant_profile (user_id, tenant_id);
CREATE UNIQUE INDEX ux_tp_tenant_username ON veyq_tenant_profile (tenant_id, username);
CREATE INDEX ix_tp_join_signer ON veyq_tenant_profile (join_signer_user_id);

-- A scope's succession statements (ADR 0115 decision 5): a scope key is replaced, never chained, so nothing seals an
-- older scope key under a newer one. Each epoch n >= 1 keeps its succession statement instead: the epoch's succession
-- public key and the signature of epoch n - 1's succession key over (scope, n, that key) - public values the server
-- stores and serves and never verifies. The scope FK puts the table in the scope erasure's closure
-- (validate_scope_erasure_arity.py).
CREATE TABLE veyq_scope_succession (
    scope_id              UUID NOT NULL,
    epoch                 INT NOT NULL,
    succession_public_key VARCHAR(44) NOT NULL,
    succession_signature  VARCHAR(88) NOT NULL,
    CONSTRAINT pk_scope_succession PRIMARY KEY (scope_id, epoch),
    CONSTRAINT fk_scope_succession_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

-- Per-(user, scope) pinned-node set (ADR 0068; core's since the pin names a node of any kind), sealed under the
-- member's master key, never K_SC. The natural key is one synthetic id (`userId|scopeId`) so an upsert is a
-- primary-key lookup. No foreign key: the scope eraser (phase 600) deletes it with the scope.
CREATE TABLE veyq_scope_favorites (
    id         VARCHAR(73) NOT NULL,
    user_id    UUID NOT NULL,
    scope_id   UUID NOT NULL,
    ciphertext MEDIUMTEXT NOT NULL,
    PRIMARY KEY (id)
);

-- The identity-pin store's HEAD (ADR 0028, ADR 0104), one row per account: its version, entry count and the digest of
-- the whole entry set, sealed under the Master Key at its revision, which is the store's one compare-and-set counter.
-- The entries are veyq_identity_pin_entry's rows. A born-empty account has no row.
CREATE TABLE veyq_identity_pins (
    account_id  UUID NOT NULL PRIMARY KEY,
    sealed_blob TEXT NULL,
    revision    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_identity_pins_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- A key-holder's decline of a grant-delivery job (ADR 0028): worker_id is the user id, so the decline covers
-- every device of that identity, while a different key-holder still delivers.
CREATE TABLE veyq_job_decline (
    id        UUID NOT NULL PRIMARY KEY,
    job_id    UUID NOT NULL,
    worker_id UUID NOT NULL,
    reason    VARCHAR(256) NULL,
    CONSTRAINT fk_job_decline_job FOREIGN KEY (job_id) REFERENCES veyq_job (id)
);
CREATE UNIQUE INDEX ux_job_decline ON veyq_job_decline (job_id, worker_id);

-- A public share (ADR 0029). A deleted link is a tombstone: the row keeps its ids and creation time, every
-- ciphertext column goes and deleted marks it — a flag, not a time: nothing reads when a link was deleted (#398). wrap
-- is null on a WRITE_ONLY share, which carries the drop public key instead (ADR 0031). A closed link carries no key of
-- the root (ADR 0114 decision 5): a close clears wrap and sealed_drop_public_key, and a reopen brings the root's current
-- one. expiry_closed records that the end-date sweep closed the link (ADR 0114 decision 6), so it marks each expired link
-- once; a reopen (an edit moving the end date into the future or clearing it, a re-activation) clears it.
-- write_verifier = SHA-256 of the link's write token (ADR 0076); null takes no new guest.
-- sealed_share_key is the link's recovery copy, written once at the mint with sealed_share_key_epoch, the scope's epoch
-- then (the re-issue rule, ADR 0075 point 4, reads it); it is sealed under a value key of its own, and only that key's
-- wrap under the scope key (wrapped_recovery_value_key, seal purpose `value-key`, at most 128 characters) is what a
-- rotation rewrites (ADR 0115). Null exactly for a link minted without a recovery copy.
-- name_editor_user_id, note_editor_user_id, share_name_editor_user_id: the member who last wrote sealed_name,
-- sealed_note, sealed_share_name (ADR 0093 § 8) - no foreign key, members only, null exactly while the field is (the
-- tombstone clears them with the seals; SharingStore writes each with its field).
CREATE TABLE veyq_share (
    id                         VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_account              UUID NOT NULL,
    scope_id                   UUID NOT NULL,
    node_id                    UUID NOT NULL,
    role                       VARCHAR(16) NOT NULL,
    wrap                       TEXT NULL,
    sealed_name                TEXT NULL,
    enabled                    BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at                 DATETIME(3) NULL,
    created_at                 DATETIME(3) NOT NULL,
    sealed_note                TEXT NULL,
    sealed_share_key           TEXT NULL,
    sealed_share_name          TEXT NULL,
    password_required          BOOLEAN NOT NULL DEFAULT FALSE,
    pw_salt                    TEXT NULL,
    pw_verifier                TEXT NULL,
    sealed_password            TEXT NULL,
    sealed_drop_public_key     TEXT NULL,
    sealed_share_key_epoch     INT NULL,
    member_removals_at_mint    INT NOT NULL DEFAULT 0,
    reissue_only               BOOLEAN NOT NULL DEFAULT FALSE,
    deleted                    BOOLEAN NOT NULL DEFAULT FALSE,
    write_verifier             TEXT NULL,
    name_editor_user_id        UUID NULL,
    note_editor_user_id        UUID NULL,
    share_name_editor_user_id  UUID NULL,
    expiry_closed              BOOLEAN NOT NULL DEFAULT FALSE,
    wrapped_recovery_value_key VARCHAR(128) NULL,
    CONSTRAINT fk_share_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_share_owner ON veyq_share (owner_account);
CREATE INDEX ix_share_node ON veyq_share (node_id);

-- An anonymous guest identity on a share (ADR 0030).
CREATE TABLE veyq_share_identity (
    id                   UUID NOT NULL PRIMARY KEY,
    share_id             VARCHAR(64) NOT NULL,
    sealed_display_name  TEXT NOT NULL,
    token_hash           VARCHAR(64) NOT NULL,
    wrapped_identity_key TEXT NULL,
    CONSTRAINT fk_share_identity_share FOREIGN KEY (share_id) REFERENCES veyq_share (id)
);
CREATE INDEX ix_share_identity_lookup ON veyq_share_identity (share_id, token_hash);

-- A node's guest identities, declared here, after veyq_share_identity exists. Not cascading: an identity a folder names
-- is kept by NodeGuestIdentityUsage; the scope erase deletes the nodes (phase 700) before the identities (phase 800). An
-- identity that last wrote a name or a meta is kept the same way while a row names it.
ALTER TABLE veyq_node ADD CONSTRAINT fk_node_share_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id);
ALTER TABLE veyq_node ADD CONSTRAINT fk_node_name_editor_identity FOREIGN KEY (name_editor_identity_id) REFERENCES veyq_share_identity (id);
ALTER TABLE veyq_node ADD CONSTRAINT fk_node_meta_editor_identity FOREIGN KEY (meta_editor_identity_id) REFERENCES veyq_share_identity (id);
CREATE INDEX ix_node_scope_due ON veyq_node (scope_id, rekey_due);

-- A sealed file on a node (ADR 0079 Consequences): one blob per (node, slot). slot is a token the owning application
-- names (Depot's FILE_CONTENT, Memories' COVER — each equal to the blob role its wrap is sealed to); core
-- stores and compares it, never names one. Who uploaded the slot's CURRENT blob (ADR 0083 Addendum; a replace moves it):
-- share_identity_id for a link's guest, uploader_user_id for a member - exactly one of the two (ck_attachment_uploader).
-- uploader_user_id is no foreign key, as Memories' owner_key is none: a member who left stays named (a "former member").
-- blob_id is unique: one blob row has exactly one reference, so freeing the attachment frees the blob.
-- No foreign key here cascades, on purpose: a cascade would drop the row without BlobUploadStore.deleteBlobRow,
-- leaving its blob row and store objects orphaned with no sweep to find them. The erasers delete these rows first
-- (AttachmentNodeSubtreeEraser, AttachmentScopeEraser); a path that forgets is refused by the database, loudly.
-- Keep blob_id on a line of its own: scripts/validate_blob_reference_arity.py reads the column declaration line by line.
CREATE TABLE veyq_node_attachment (
    id                UUID NOT NULL PRIMARY KEY,
    scope_id          UUID NOT NULL,
    node_id           UUID NOT NULL,
    slot              VARCHAR(16) NOT NULL,
    blob_id           UUID NOT NULL,
    share_identity_id UUID NULL,
    uploader_user_id  UUID NULL,
    CONSTRAINT ux_attachment_node_slot UNIQUE (node_id, slot),
    CONSTRAINT ux_attachment_blob UNIQUE (blob_id),
    CONSTRAINT fk_attachment_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_attachment_node FOREIGN KEY (node_id) REFERENCES veyq_node (id),
    CONSTRAINT fk_attachment_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_attachment_share_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id),
    CONSTRAINT ck_attachment_uploader CHECK ((uploader_user_id IS NULL AND share_identity_id IS NOT NULL)
        OR (uploader_user_id IS NOT NULL AND share_identity_id IS NULL))
);
CREATE INDEX ix_attachment_scope ON veyq_node_attachment (scope_id);
CREATE INDEX ix_attachment_share_identity ON veyq_node_attachment (share_identity_id);

-- A member's acceptances of the terms (ADR 0035 decisions 4 and 6): one row per accepted pair — the space's terms
-- revision and the operator section's revision — kept as evidence; the gate asks whether the member's history holds
-- the current pair. No foreign key to the account or the tenant: the rows outlive both while a moderation case names the
-- member (ADR 0101 decisions 6 and 10), kept under the opaque ids to that case's log retention date. AccountPurge and
-- TenantPurge delete the rows no case keeps; AccountPurge's sweep of kept rows deletes the rest once none does.
CREATE TABLE veyq_tenant_terms_acceptance (
    id                UUID NOT NULL PRIMARY KEY,
    user_id           UUID NOT NULL,
    tenant_id         UUID NOT NULL,
    accepted_revision INT NOT NULL,
    operator_revision INT NOT NULL,
    accepted_at       DATETIME(3) NOT NULL,
    CONSTRAINT ux_terms_acceptance UNIQUE (user_id, tenant_id, accepted_revision, operator_revision)
);
-- An index on tenant_id. Its fk_ name is that of a foreign key the table does not have (the rows outlive their tenant,
-- above); nothing cites it.
CREATE INDEX fk_terms_acceptance_tenant ON veyq_tenant_terms_acceptance (tenant_id);

-- Per-tenant SMTP settings (ADR 0048-0050). The password is sealed under the env KEK, never plaintext, and
-- carries the fingerprint of the key that sealed it (ADR 0089 decision 4).
CREATE TABLE veyq_tenant_mail_settings (
    tenant_id            UUID NOT NULL PRIMARY KEY,
    host                 VARCHAR(253) NULL,
    port                 INT NULL,
    from_address         VARCHAR(254) NULL,
    username             VARCHAR(254) NULL,
    password_ciphertext  TEXT NULL,
    password_nonce       TEXT NULL,
    password_fingerprint VARCHAR(64) NULL,
    tls                  BOOLEAN NOT NULL DEFAULT TRUE,
    enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    subject_template     VARCHAR(200) NULL,
    body_template        TEXT NULL,
    CONSTRAINT fk_mail_settings_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

-- One-time password-recovery tokens (ADR 0054): only the hash is stored. Not the second factor — a live token
-- only releases the recovery wrap, which the Secret Key alone opens. email is the address actually mailed.
CREATE TABLE veyq_account_recovery (
    id         UUID NOT NULL PRIMARY KEY,
    user_id    UUID NOT NULL,
    email      VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_account_recovery_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE INDEX ix_account_recovery_token_hash ON veyq_account_recovery (token_hash);
CREATE INDEX ix_account_recovery_user_status ON veyq_account_recovery (user_id, status);

-- The sealed device register (ADR 0004). Plain TEXT on purpose: the contract caps it inside the TEXT ceiling.
CREATE TABLE veyq_device_register (
    account_id  UUID NOT NULL PRIMARY KEY,
    sealed_blob TEXT NULL,
    revision    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_device_register_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- A device-pairing channel (ADR 0059). user_id is null until the scanning side delegates.
-- delegated_family_id deliberately has NO foreign key: SessionPurge deletes families whose tokens all expired,
-- and an FK would make that delete fail against a pairing row still in its retention window.
CREATE TABLE veyq_device_pairing (
    id                  UUID NOT NULL PRIMARY KEY,
    handle_hash         VARCHAR(64) NOT NULL,
    user_id             UUID NULL,
    delegated_family_id UUID NULL,
    answer              TEXT NULL,
    envelope            TEXT NULL,
    status              VARCHAR(16) NOT NULL,
    expires_at          DATETIME(3) NOT NULL,
    created_at          DATETIME(3) NOT NULL,
    CONSTRAINT fk_device_pairing_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE UNIQUE INDEX ux_device_pairing_handle_hash ON veyq_device_pairing (handle_hash);
CREATE INDEX ix_device_pairing_user_status ON veyq_device_pairing (user_id, status);

-- The store-object reclaim list (ADR 0062). Deliberately opaque: no scope, media or node id, because after an
-- erasure these rows are the only trace of it. No UNIQUE on storage_key, and that is not an oversight: a
-- committed blob keeps its session's storage_key, so one prefix legitimately arrives twice, and a unique index
-- would turn that into a constraint violation inside someone else's transaction. ix_store_reclaim_storage_key serves
-- the bucket reconcile's page probe, as on every claim table.
CREATE TABLE veyq_store_reclaim (
    id              UUID NOT NULL PRIMARY KEY,
    storage_key     VARCHAR(64) NOT NULL,
    chunk_count     INT NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    attempts        INT NOT NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    abandoned_at    DATETIME(3) NULL
);
CREATE INDEX ix_store_reclaim_due ON veyq_store_reclaim (abandoned_at, next_attempt_at);
CREATE INDEX ix_store_reclaim_storage_key ON veyq_store_reclaim (storage_key);

-- One-time address-confirmation tokens (ADR 0074), shaped like veyq_account_recovery.
CREATE TABLE veyq_email_confirmation (
    id         UUID NOT NULL PRIMARY KEY,
    user_id    UUID NOT NULL,
    email      VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_email_confirmation_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE UNIQUE INDEX ux_email_confirmation_token_hash ON veyq_email_confirmation (token_hash);
CREATE INDEX ix_email_confirmation_user_status ON veyq_email_confirmation (user_id, status);

-- A pending change of the sign-in address (uc.account.change-email, ADR 0074 Addendum): the new address and the auth
-- public key derived under its salt, installed together when the link mailed to the new address is confirmed. At
-- most one per account (a newer request replaces it); spent by deleting it; an expired one is purged.
CREATE TABLE veyq_email_change (
    id              UUID NOT NULL PRIMARY KEY,
    user_id         UUID NOT NULL,
    new_email       VARCHAR(254) NOT NULL,
    auth_public_key TEXT NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    expires_at      DATETIME(3) NOT NULL,
    CONSTRAINT fk_email_change_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE UNIQUE INDEX ux_email_change_user ON veyq_email_change (user_id);
CREATE UNIQUE INDEX ux_email_change_token_hash ON veyq_email_change (token_hash);
CREATE INDEX ix_email_change_expires ON veyq_email_change (expires_at);

-- Every share id the scope purge erased (the id only), so create never mints one twice (ADR 0029 §7).
CREATE TABLE veyq_share_retired_id (
    id VARCHAR(64) NOT NULL PRIMARY KEY
);

-- The tenant level of the capability register (ADR 0088 decisions 2 and 12): one row per entry a tenant owner
-- switched off for their tenant, none for an entry that is on. capability_id is the catalog's dotted id
-- (contracts/platform/capabilities.json), so VARCHAR. Only an entry the catalog lets a tenant switch is ever written.
-- The effective state is the AND of the instance level (veyq_capability_switch) and this one along the path,
-- computed by the server, never stored. Removed with its tenant.
CREATE TABLE veyq_tenant_capability_switch (
    id            UUID NOT NULL PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    capability_id VARCHAR(64) NOT NULL,
    CONSTRAINT ux_tenant_capability_switch UNIQUE (tenant_id, capability_id),
    CONSTRAINT fk_tenant_capability_switch_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

-- Requirements on capability entries (ADR 0088 decision 8): a value per requirement and level, none for one that is
-- not set. `requirement` is the catalog's requirement name (contracts/platform/capabilities.json), unique in the catalog,
-- so VARCHAR; `setting` is 1 for a set flag, else a number of days (1 to 3650). The value in effect for a tenant is the
-- strictest of both levels, computed by the server, never stored. The tenant's rows (below) are removed with its tenant.
CREATE TABLE veyq_capability_requirement (
    requirement VARCHAR(64) NOT NULL PRIMARY KEY,
    setting     INT NOT NULL
);

CREATE TABLE veyq_tenant_capability_requirement (
    id          UUID NOT NULL PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    requirement VARCHAR(64) NOT NULL,
    setting     INT NOT NULL,
    CONSTRAINT ux_tenant_capability_requirement UNIQUE (tenant_id, requirement),
    CONSTRAINT fk_tenant_capability_requirement_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

-- The outside services a member's browser calls (ADR 0088 decision 7, ADR 0089): one row per service the Server Owner
-- configured, keyed by its register entry (contracts/platform/capabilities.json, source external-service), none for one
-- that is not configured — its entry is then not active and the served CSP leaves its origin out. A new instance starts
-- with the rows seeded below: OpenStreetMap's tiles with their attribution (ADR 0018), Nominatim (ADR 0016), the HIBP
-- range API, and the day's exchange-rate table Expenses' editor fills a foreign entry's rate from (ADR 0108 decision 8):
-- Frankfurter's endpoint for the European Central Bank's euro reference rates, a template the browser fills with the
-- device's date and fetches straight from the provider — v2's ECB table (30 rows, base EUR, measured 2026-10-25), never
-- the blended default, because the rates are the ECB's alone (owner 2026-10-25) and v1 is deprecated. The Server Owner
-- replaces each (a mirror, a self-hosted Frankfurter's same endpoint) or removes it, which switches its use off and its
-- origin out of the CSP.
CREATE TABLE veyq_external_service (
    capability_id VARCHAR(64) NOT NULL PRIMARY KEY,
    url           VARCHAR(512) NOT NULL,
    attribution   VARCHAR(256) NULL
);

INSERT INTO veyq_external_service (capability_id, url, attribution) VALUES
    ('memories.map', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', '© OpenStreetMap contributors'),
    ('memories.places', 'https://nominatim.openstreetmap.org', NULL),
    ('core.breached-password', 'https://api.pwnedpasswords.com/range/', NULL),
    ('expenses.exchange-rates', 'https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}', NULL);

-- The release check and the instance mail (ADR 0097).
--
-- veyq_release_check: the check's setting, one row, seeded. On by default with the published GitHub Releases as its
-- source (source NULL), so an instance that never opens the setting still hears of a new release. last_success_at is the
-- last check that read the source.
CREATE TABLE veyq_release_check (
    id              INT NOT NULL PRIMARY KEY,
    enabled         BOOLEAN NOT NULL,
    source          VARCHAR(2048) NULL,
    last_success_at DATETIME(3) NULL
);

INSERT INTO veyq_release_check (id, enabled, source, last_success_at) VALUES (1, TRUE, NULL, NULL);

-- What the check found (ADR 0097 decision 2), one row per notice. notice_key is kind, release, date and lead in one
-- string, unique, so a second check that finds the same thing writes nothing; a nullable column in a unique index
-- would not hold that on MariaDB, where NULLs never collide. mailed_at is set once the notice was mailed.
CREATE TABLE veyq_release_notice (
    id              UUID NOT NULL PRIMARY KEY,
    notice_key      VARCHAR(96) NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    release_version VARCHAR(32) NOT NULL,
    notice_date     DATE NULL,
    days_ahead      INT NULL,
    found_at        DATETIME(3) NOT NULL,
    mailed_at       DATETIME(3) NULL
);
CREATE UNIQUE INDEX ux_release_notice_key ON veyq_release_notice (notice_key);

-- The instance's own SMTP settings (ADR 0097 decision 3), one row once saved. Sends only instance notices, only to
-- the Server Owner's account address; never a tenant's mail. The password is sealed under the config-secret KEK with
-- the fingerprint of the key that sealed it (ADR 0050, ADR 0089 decision 4). owner_user_id is the Server Owner who
-- saved them, whose address the mail goes to; no foreign key, so this row never keeps an account from being erased (a
-- missing user is sent nothing). The confirmation (decision 5): code_hash is the hash of the one-time code the test
-- mail carried, valid until code_expires_at and while code_attempts wrong entries stay below the limit, for the address
-- it was sent to (code_address); confirmed_address is the address a code confirmed. test_mail_failed says the last
-- save's test mail could not be delivered.
CREATE TABLE veyq_instance_mail_settings (
    id                   INT NOT NULL PRIMARY KEY,
    host                 VARCHAR(253) NULL,
    port                 INT NULL,
    from_address         VARCHAR(254) NULL,
    username             VARCHAR(254) NULL,
    password_ciphertext  TEXT NULL,
    password_nonce       TEXT NULL,
    password_fingerprint VARCHAR(64) NULL,
    tls                  BOOLEAN NOT NULL,
    owner_user_id        UUID NULL,
    code_hash            VARCHAR(64) NULL,
    code_address         VARCHAR(254) NULL,
    code_expires_at      DATETIME(3) NULL,
    code_attempts        INT NOT NULL,
    test_mail_failed     BOOLEAN NOT NULL,
    confirmed_address    VARCHAR(254) NULL,
    confirmed_at         DATETIME(3) NULL
);

-- Moderators and the instance moderation key (ADR 0099), and the moderation log (ADR 0093 § 11).
--
-- veyq_moderator: one row per account the Server Owner appointed, surfaced as the MODERATOR role while accepted_at is
-- set and removed_at is not. appointed_by is the Server Owner who appointed it. A removal (by the Server Owner or the
-- moderator's own step-down) sets removed_at and clears the address confirmation; a reappointment starts the row over.
-- The address confirmation (ADR 0097 decision 5, for moderation notices) has the columns veyq_instance_mail_settings has:
-- code_hash is the hash of the one-time code, valid until code_expires_at and while code_attempts stays below the limit,
-- for the address it was sent to (code_address); code_failed says the last code could not be mailed;
-- confirmed_address is the address a code confirmed.
CREATE TABLE veyq_moderator (
    account_id        UUID NOT NULL PRIMARY KEY,
    appointed_by      UUID NOT NULL,
    appointed_at      DATETIME(3) NOT NULL,
    accepted_at       DATETIME(3) NULL,
    removed_at        DATETIME(3) NULL,
    code_hash         VARCHAR(64) NULL,
    code_address      VARCHAR(254) NULL,
    code_expires_at   DATETIME(3) NULL,
    code_attempts     INT NOT NULL DEFAULT 0,
    code_failed       BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_address VARCHAR(254) NULL,
    confirmed_at      DATETIME(3) NULL,
    CONSTRAINT fk_moderator_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- One row per epoch of the instance moderation key: its public bundle (the X-Wing encapsulation key reports seal to,
-- the Ed25519 key a key proof verifies under) and why it was minted (INITIAL, REMOVAL, IDENTITY_ROTATION, COMPROMISE,
-- BY_HAND). Never M_n or a private half. Who minted it and when is the moderation log's EPOCH_MINTED event, kept under
-- the log's retention rather than for as long as the epoch lives. rotation_due_at is set on the CURRENT epoch when a holder of it
-- leaves or rotates their identity, and the mint of the next epoch is what clears the warning; rotation_due_mailed_at
-- is when the Server Owner was mailed about it (once per epoch).
CREATE TABLE veyq_moderation_epoch (
    epoch                  INT NOT NULL PRIMARY KEY,
    kem_public_key         TEXT NOT NULL,
    sign_public_key        VARCHAR(64) NOT NULL,
    reason                 VARCHAR(24) NOT NULL,
    rotation_due_at        DATETIME(3) NULL,
    rotation_due_mailed_at DATETIME(3) NULL
);

-- A grant: M_n sealed to one moderator's identity (sealTo purpose moderation-grant [epoch]), one per (moderator,
-- epoch). delivered_by is the holder whose client sealed it, or the Server Owner whose admin-web minted it; when is the
-- log's DELIVERED or EPOCH_MINTED event.
CREATE TABLE veyq_moderation_grant (
    id           UUID NOT NULL PRIMARY KEY,
    moderator_id UUID NOT NULL,
    epoch        INT NOT NULL,
    sealed_grant TEXT NOT NULL,
    delivered_by UUID NOT NULL,
    CONSTRAINT fk_moderation_grant_moderator FOREIGN KEY (moderator_id) REFERENCES veyq_moderator (account_id),
    CONSTRAINT fk_moderation_grant_epoch FOREIGN KEY (epoch) REFERENCES veyq_moderation_epoch (epoch)
);
CREATE UNIQUE INDEX ux_moderation_grant ON veyq_moderation_grant (moderator_id, epoch);

-- A key proof's challenge: 32 random bytes (as their base64), bound to the account it was issued to, single use,
-- deleted on use and purged once past expires_at.
CREATE TABLE veyq_moderation_challenge (
    challenge  VARCHAR(64) NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    expires_at DATETIME(3) NOT NULL
);
CREATE INDEX ix_moderation_challenge_expires ON veyq_moderation_challenge (expires_at);

-- The moderation log (ADR 0093 § 11), append-only. Structured fields in the clear: the event, its time, the moderator
-- it concerns, the account that acted, the case (NULL for an instance event), the epoch and a reason category, the id a
-- measure or a proposal named (target_id), and an extension's new end (hold_until; the event's own expires_at stays its
-- retention date). Free text only as sealed_text, sealed to the moderation key. expires_at is NULL while the moderator
-- it concerns is on the roster and set by their removal (31 December of the third year after the later of the removal
-- and the event); a scheduled purge deletes what has expired. No foreign keys: the log outlives the rows it names, until
-- its own date. A case's records go together with its events (ModerationRecordsPurge), which it finds by case
-- (ix_moderation_event_case).
CREATE TABLE veyq_moderation_event (
    id           UUID NOT NULL PRIMARY KEY,
    event        VARCHAR(24) NOT NULL,
    occurred_at  DATETIME(3) NOT NULL,
    moderator_id UUID NULL,
    actor_id     UUID NULL,
    case_id      UUID NULL,
    epoch        INT NULL,
    reason       VARCHAR(24) NULL,
    sealed_text  TEXT NULL,
    expires_at   DATETIME(3) NULL,
    target_id    VARCHAR(64) NULL,
    hold_until   DATETIME(3) NULL
);
CREATE INDEX ix_moderation_event_moderator ON veyq_moderation_event (moderator_id);
CREATE INDEX ix_moderation_event_expires ON veyq_moderation_event (expires_at);
CREATE INDEX ix_moderation_event_case ON veyq_moderation_event (case_id);

-- The operator details (ADR 0093 §§ 11 and 14, ADR 0035 decisions 6-8), one row once the Server Owner saved a value. No
-- seeded row: a write finds or creates it under the instance row's lock (OperatorDetailsStore). There is no operating
-- mode: every instance serves one form of each fixed text (ADR 0093 § 14).
-- contact_email, contact_languages (comma-joined language tags), statement_email, the provider_* columns,
-- supervisory_authority and data_protection_officer (where one is appointed; the privacy notice renders it) are public
-- operational data, typed by the Server Owner to be published. notification_email is not public: it is where moderation
-- notices go once confirmed (ADR 0097 decisions 3 and 5). The confirmation: code_hash is the hash of the one-time code
-- mailed to code_address, valid until code_expires_at and while code_attempts wrong entries stay below the limit;
-- confirmed_address is the address a code confirmed, so a changed notification address unconfirms without a write.
-- code_failed says the last save's code could not be mailed.
CREATE TABLE veyq_operator_details (
    id                      INT NOT NULL PRIMARY KEY,
    contact_email           VARCHAR(254) NULL,
    contact_languages       VARCHAR(512) NULL,
    statement_email         VARCHAR(254) NULL,
    notification_email      VARCHAR(254) NULL,
    provider_name           VARCHAR(200) NULL,
    provider_address        VARCHAR(500) NULL,
    provider_email          VARCHAR(254) NULL,
    provider_representative VARCHAR(200) NULL,
    supervisory_authority   VARCHAR(500) NULL,
    code_hash               VARCHAR(64) NULL,
    code_address            VARCHAR(254) NULL,
    code_expires_at         DATETIME(3) NULL,
    code_attempts           INT NOT NULL,
    code_failed             BOOLEAN NOT NULL,
    confirmed_address       VARCHAR(254) NULL,
    confirmed_at            DATETIME(3) NULL,
    data_protection_officer VARCHAR(500) NULL
);

-- The bucket reconcile (ADR 0062 § Consequences). Two tables, neither of them anyone's data: a fresh instance's first
-- pass writes both, so src/tools/instance_backup.py names them in NOT_DATA, and a restore empties both after loading its
-- dump, so every first-seen clock restarts and the exclusive-bucket confirmation must be given again. Counts, instants
-- and opaque storage keys only: no scope, no node (ADR 0062 Decision 6).
--
-- The reconcile's state: one row, id 1, created by the first tick. The pass in progress (its page counter is the
-- compare-and-set every page's write goes through), the last completed pass, the last failure, and the Server Owner's
-- two switches. `*_digest` names the bucket a pass listed or a confirmation was given for (BlobStore.bucketDigest).
CREATE TABLE veyq_store_reconcile (
    id                         INT NOT NULL PRIMARY KEY,
    store_digest               VARCHAR(64) NULL,
    pass_no                    BIGINT NOT NULL,
    pass_started_at            DATETIME(3) NULL,
    pass_page                  BIGINT NOT NULL,
    pass_cursor                VARCHAR(1024) NULL,
    pass_objects               BIGINT NOT NULL,
    pass_orphan_objects        BIGINT NOT NULL,
    pass_orphan_bytes          BIGINT NOT NULL,
    pass_not_veyq_objects      BIGINT NOT NULL,
    last_listed_at             DATETIME(3) NULL,
    completed_started_at       DATETIME(3) NULL,
    completed_at               DATETIME(3) NULL,
    completed_objects          BIGINT NULL,
    completed_orphan_objects   BIGINT NULL,
    completed_orphan_bytes     BIGINT NULL,
    completed_not_veyq_objects BIGINT NULL,
    completed_enqueued_keys    BIGINT NULL,
    failed_at                  DATETIME(3) NULL,
    failure                    VARCHAR(32) NULL,
    exclusive_confirmed_at     DATETIME(3) NULL,
    exclusive_store_digest     VARCHAR(64) NULL,
    paused_at                  DATETIME(3) NULL,
    clocks_restarted_at        DATETIME(3) NULL,
    clocks_restart_reason      VARCHAR(16) NULL
);

-- One row per storage key no row claimed in any pass since `first_seen_at` (the store's clock): the candidates. A
-- completed pass drops every row whose `last_seen_pass` is not its own, so a row that survives was an orphan in every
-- pass since it was first seen. `chunk_count` is the highest orphan index seen plus one — what a reclaim row deletes.
CREATE TABLE veyq_store_orphan (
    storage_key    VARCHAR(64) NOT NULL PRIMARY KEY,
    chunk_count    INT NOT NULL,
    objects        BIGINT NOT NULL,
    bytes          BIGINT NOT NULL,
    first_seen_at  DATETIME(3) NOT NULL,
    last_seen_pass BIGINT NOT NULL
);
CREATE INDEX ix_store_orphan_first_seen ON veyq_store_orphan (first_seen_at);

-- Reports and cases (ADR 0093 §§ 1-2, 5; uc.moderation.report). No foreign key into a space, a node, a share or an
-- account: a report outlives what it reports (a delete, a revoke, the space's erasure), and its case with it.
--
-- veyq_moderation_report: one row per report. In the clear the epoch its sealed part is sealed to, when it was received,
-- its category, and who sent it — the reporter's account (a member: the cap on open reports counts it) or the link it
-- came through (a guest). sealed_report is K_report, the reason, the contact and the context sealed to that epoch's
-- public half (sealTo purpose moderation-report [epoch]) — opaque to the server. sealed_contact is the reporter's
-- contact, sealed on its own to the report's epoch (moderation-report-contact [epoch]) so that it can go alone, six
-- months after the decision on the report was told (ADR 0093 § 11) — the rest of the report stays with its case. NULL
-- when the reporter left none, and once it went.
CREATE TABLE veyq_moderation_report (
    id               UUID NOT NULL PRIMARY KEY,
    epoch            INT NOT NULL,
    received_at      DATETIME(3) NOT NULL,
    category         VARCHAR(24) NOT NULL,
    sealed_report    TEXT NOT NULL,
    reporter_account UUID NULL,
    reporter_share   VARCHAR(64) NULL,
    sealed_contact   TEXT NULL
);
CREATE INDEX ix_moderation_report_account ON veyq_moderation_report (reporter_account);

-- veyq_moderation_case: one per reported item (NODE, ITEM or LINK and its id; a LINK's id is its share id) in the space
-- it lies in. category is the most urgent of its reports. open_key is the item ("KIND:id") while the case is OPEN and
-- NULL once it is closed: unique, so the database holds "one open case per item" and a closed case never blocks a new one.
-- The new-case notice (ADR 0093 § 2, ADR 0099 § 7): notice_due is URGENT (the first three categories: mailed at once) or
-- BUNDLE (the others: in at most one mail a day) while the "new case" mail is owed, and NULL once it went out;
-- noticed_at and notice_kind say when and in which kind of mail. The day of a bundle is counted from the last bundle's
-- noticed_at. decided_at is when its first measure was taken, closed_at when it was dismissed or its last hold ended
-- after a decision (the date its events are kept from, ADR 0093 § 11). origin is what opened it: a report (REPORT) or
-- an authority's order (ORDER) — an order's case has no report, is decided from the start, and its open_key is
-- "ORDER:<orderId>", so it never takes a report's place as the item's one open case. reminders_sent counts the
-- reminders that went out to its moderators at 30, 75 and 90 days after its first report (ADR 0093 § 11, DSA Art. 16(6)
-- "timely"; CaseReminders), 0 to 3, so each one goes once: no case closes itself.
CREATE TABLE veyq_moderation_case (
    id             UUID NOT NULL PRIMARY KEY,
    item_kind      VARCHAR(8) NOT NULL,
    item_id        VARCHAR(64) NOT NULL,
    scope_id       UUID NOT NULL,
    category       VARCHAR(24) NOT NULL,
    state          VARCHAR(16) NOT NULL,
    open_key       VARCHAR(80) NULL,
    notice_due     VARCHAR(8) NULL,
    noticed_at     DATETIME(3) NULL,
    notice_kind    VARCHAR(8) NULL,
    decided_at     DATETIME(3) NULL,
    closed_at      DATETIME(3) NULL,
    origin         VARCHAR(8) NOT NULL DEFAULT 'REPORT',
    reminders_sent INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_moderation_case_open ON veyq_moderation_case (open_key);
CREATE INDEX ix_moderation_case_notice ON veyq_moderation_case (notice_due);

-- veyq_report_share: what one report hands over for one case's item — its keys sealed under that report's K_report
-- (seal purpose report-share [itemKind, itemId] or report-link [shareId]). In no listing, revocable by nobody, served
-- only to a moderator with the key proof (the review's), never back to the client that minted it.
CREATE TABLE veyq_report_share (
    id          UUID NOT NULL PRIMARY KEY,
    report_id   UUID NOT NULL,
    case_id     UUID NOT NULL,
    sealed_keys TEXT NOT NULL,
    CONSTRAINT fk_report_share_report FOREIGN KEY (report_id) REFERENCES veyq_moderation_report (id),
    CONSTRAINT fk_report_share_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_report_share ON veyq_report_share (report_id, case_id);
CREATE INDEX ix_report_share_case ON veyq_report_share (case_id);

-- Holds (ADR 0093 § 4, en.moderation.hold): a snapshot of what was reported, and a pin on its store objects that the
-- reclaim sweep skips. No foreign key into a space, a node, a share or a blob: erasing what was reported leaves the hold.
--
-- veyq_hold: one per case at a time. hold_trigger names why it ends when it does (HoldTrigger: CASE_OPEN, MEASURE,
-- EXTENDED, ORDER); expires_at is its end — a case-open hold 90 days after the report that took it. The purge
-- deletes a hold past its end with its rows and pins. floor_until is the end an authority's order sets: no extension,
-- lift or decision ends the hold before it; NULL for a hold no order set. ceiling_until is the latest end a measure or a
-- statement of reasons may move a hold to — set on a hold of a child-sexual-abuse case, 90 days after the report that
-- took it or that made the case one of that category (§ 184b(5) StGB: keeping it serves only the report to an
-- authority), NULL on every other hold. Only a logged extension goes past it, and floor_until always wins (ADR 0093
-- §§ 4, 13).
CREATE TABLE veyq_hold (
    id            UUID NOT NULL PRIMARY KEY,
    case_id       UUID NULL,
    hold_trigger  VARCHAR(24) NOT NULL,
    expires_at    DATETIME(3) NOT NULL,
    floor_until   DATETIME(3) NULL,
    ceiling_until DATETIME(3) NULL,
    CONSTRAINT fk_hold_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE INDEX ix_hold_case ON veyq_hold (case_id);
CREATE INDEX ix_hold_expires ON veyq_hold (expires_at);

-- veyq_hold_row: one copied row — its source table and id, and the row as JSON of its columns as stored: ciphertext and
-- metadata, nothing the server could open.
CREATE TABLE veyq_hold_row (
    id           UUID NOT NULL PRIMARY KEY,
    hold_id      UUID NOT NULL,
    source_table VARCHAR(64) NOT NULL,
    source_id    VARCHAR(64) NOT NULL,
    payload      MEDIUMTEXT NOT NULL,
    CONSTRAINT fk_hold_row_hold FOREIGN KEY (hold_id) REFERENCES veyq_hold (id)
);
CREATE INDEX ix_hold_row_hold ON veyq_hold_row (hold_id);

-- veyq_hold_pin: a store object prefix (storage_key/0 … storage_key/(chunk_count-1)) the hold keeps. While expires_at is
-- in the future, StoreReclaimStore.due leaves a reclaim row of that key out — never deferring it, so its attempts stay —
-- and a backup's store leg lists it (src/tools/instance_backup.py, blob_index).
CREATE TABLE veyq_hold_pin (
    id          UUID NOT NULL PRIMARY KEY,
    hold_id     UUID NOT NULL,
    storage_key VARCHAR(64) NOT NULL,
    chunk_count INT NOT NULL,
    expires_at  DATETIME(3) NOT NULL,
    CONSTRAINT fk_hold_pin_hold FOREIGN KEY (hold_id) REFERENCES veyq_hold (id)
);
CREATE INDEX ix_hold_pin_key ON veyq_hold_pin (storage_key, expires_at);
CREATE INDEX ix_hold_pin_hold ON veyq_hold_pin (hold_id);

-- The guest-write record (ADR 0093 § 10, en.moderation.guest-write-record): one row per guest write that enters a
-- guest as last editor, holding what the server already sees of it — the client address of core's one resolver (ADR
-- 0094) in text form, its source port where one is known, the server time in milliseconds UTC, the request's Origin
-- header (request_origin) — the share identity that wrote, and the object written (object_kind IDENTITY, NODE or ITEM;
-- field_group `name`, or none for an upload's content). No User-Agent, no content. Since the guest share API answers
-- every origin (ADR 0121), a foreign page can make a visitor's browser write through a link it knows, and the record
-- then names the visitor's address; the origin tells such a write apart. Only the shapes RequestOrigin keeps reach it —
-- a serialized origin (at most 255 characters), the literal `null` of an opaque origin, the marker `invalid` — or NULL
-- when the request carried none. The record has no switch: it is kept wherever a guest may write (ADR 0088 decision
-- 14). expires_at is observed_at plus the retention as it stood at the write; the purge also deletes a row older than
-- the retention as it stands now, so no guest is kept longer than the page told them. The row goes with its identity
-- (cascading) and with its object (core's erasers, an application's through core). The instance backup dumps this
-- table's schema and leaves out its rows (src/tools/instance_backup.py).
CREATE TABLE veyq_guest_write_record (
    id                UUID NOT NULL PRIMARY KEY,
    share_identity_id UUID NOT NULL,
    object_kind       VARCHAR(16) NOT NULL,
    object_id         UUID NOT NULL,
    field_group       VARCHAR(16) NULL,
    client_ip         VARCHAR(45) NOT NULL,
    client_port       INT NULL,
    observed_at       DATETIME(3) NOT NULL,
    expires_at        DATETIME(3) NOT NULL,
    request_origin    VARCHAR(255) NULL,
    CONSTRAINT fk_guest_write_record_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id) ON DELETE CASCADE
);
CREATE INDEX ix_guest_write_record_object ON veyq_guest_write_record (object_id);
CREATE INDEX ix_guest_write_record_observed ON veyq_guest_write_record (observed_at);
CREATE INDEX ix_guest_write_record_expires ON veyq_guest_write_record (expires_at);

-- The retention N in days, 1 to 30 (ADR 0092 § 2), once the Server Owner set it: one row, id 1. No seeded row — no
-- row reads as the default, 7 (GuestWriteRecords.retentionDays).
CREATE TABLE veyq_guest_write_retention (
    id             INT NOT NULL PRIMARY KEY,
    retention_days INT NOT NULL
);

-- Measures and proposals (ADR 0093 §§ 4-6, ADR 0099 § 6; uc.moderation.take-a-measure, uc.moderation.review-a-case).
--
-- veyq_moderation_block: one measure — a row that deletes nothing and holds until it is lifted (lifted_at set; the row
-- stays as the record). kind is SHARE (a link), CONTENT_NODE / CONTENT_ITEM (a node and everything below it, or an
-- application's item), CONTENT_UPLOADER (everything one uploader had put in a space, listed in
-- veyq_moderation_block_reach), GUEST_IDENTITY (a guest uploader; share_id is the link it came through, blocked with
-- it), MEMBER_SIGN_IN (an account, the Server Owner's), or REPORTER_ACCOUNT / REPORTER_LINK (a reporter barred, ADR 0093
-- § 5). target_id is the blocked id; scope_id the space of a content block (every listing asks per space); case_id the
-- case it answers (NULL for a Server Owner's step taken on no case). active_key is "KIND:target" while the measure is in
-- force and NULL once lifted: unique, so one measure per target and kind is held by the database. No foreign key into a
-- space, a node, a share or an account: a measure hangs on ids and outlives what it names (ADR 0093 § 6).
-- held_objects: a content measure hangs on the stored object (ADR 0093 § 6, ADR 0013 § 5) — a measure on an
-- application's item whose stored objects other blob rows of its node still name (a cover or a banner sharing a medium's
-- object) does not end when the item is erased. It lists those objects' storage keys, space-separated, while the measure
-- stands on them alone; every row naming one is refused as blocked, and the measure ends when the last row naming any of
-- them goes (BlobUploadStore.deleteBlobRow, ScopePurge) or a moderator lifts it. A label, not a claim: no object is kept
-- alive by a measure (the blob rows keep it), so it is no table of the reconcile's claims or the backup's blob index.
-- ix_moderation_block_case_lifted: a case-less step's records are found by their measure (ModerationRecordsPurge).
CREATE TABLE veyq_moderation_block (
    id           UUID NOT NULL PRIMARY KEY,
    kind         VARCHAR(24) NOT NULL,
    target_id    VARCHAR(64) NOT NULL,
    share_id     VARCHAR(64) NULL,
    scope_id     UUID NULL,
    case_id      UUID NULL,
    taken_by     UUID NOT NULL,
    taken_at     DATETIME(3) NOT NULL,
    lifted_at    DATETIME(3) NULL,
    lifted_by    UUID NULL,
    active_key   VARCHAR(96) NULL,
    held_objects TEXT NULL
);
CREATE UNIQUE INDEX ux_moderation_block_active ON veyq_moderation_block (active_key);
CREATE INDEX ix_moderation_block_target ON veyq_moderation_block (target_id);
CREATE INDEX ix_moderation_block_share ON veyq_moderation_block (share_id);
CREATE INDEX ix_moderation_block_scope ON veyq_moderation_block (scope_id);
CREATE INDEX ix_moderation_block_case ON veyq_moderation_block (case_id);
CREATE INDEX ix_moderation_block_case_lifted ON veyq_moderation_block (case_id, lifted_at);

-- veyq_moderation_proposal: a Server Owner's step a moderator proposed for a case (ADR 0099 § 6) — MEMBER_SIGN_IN
-- (target: the member's account) or TENANT_OFFLINE (target: the tenant). outcome OPEN until the Server Owner takes the
-- step (TAKEN, set by the block or the suspension itself) or declines it (DECLINED). open_key is "caseId:STEP:target"
-- while OPEN: one open proposal per case, step and target.
CREATE TABLE veyq_moderation_proposal (
    id          UUID NOT NULL PRIMARY KEY,
    case_id     UUID NOT NULL,
    step        VARCHAR(24) NOT NULL,
    target_id   UUID NOT NULL,
    proposed_by UUID NOT NULL,
    proposed_at DATETIME(3) NOT NULL,
    outcome     VARCHAR(16) NOT NULL,
    settled_at  DATETIME(3) NULL,
    settled_by  UUID NULL,
    open_key    VARCHAR(160) NULL,
    CONSTRAINT fk_moderation_proposal_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_proposal_open ON veyq_moderation_proposal (open_key);
CREATE INDEX ix_moderation_proposal_target ON veyq_moderation_proposal (step, target_id);
CREATE INDEX ix_moderation_proposal_case ON veyq_moderation_proposal (case_id);

-- Statements of reasons and objections (ADR 0093 §§ 4, 7, 14, ADR 0099 § 5; uc.moderation.object-to-a-measure).
--
-- veyq_moderation_statement: one per measure, written with it. ground is the clear ground (a report category's value);
-- basis REPORT, ORDER or OWN_FINDING; provision the provision of the law or the clause of the terms the ground relies on,
-- a short reference in the moderator's or the Server Owner's own words, in the clear beside the ground — never the
-- facts, which stay sealed (DSA Art. 17(3)(d), (e)); NULL where none was named. case_id is NULL for a Server Owner's step
-- over content that answers no case (a sign-in block or a tenant taken offline on their own finding): its statement hangs
-- on the step's measure row alone, and goes with it (ModerationRecordsPurge). epoch + sealed_facts are the log's copy of
-- the facts, sealed to the moderation key (moderation-note [epoch, caseId]), NULL without facts; sealed_member_facts is
-- the facts sealed once under the statement's fresh K_facts (moderation-statement-facts [caseId, statementId]), which
-- each member's recipient row wraps to that member. sent_at is when it went out — NULL where nobody could be told (no
-- member among its recipients): the measure's hold then counts from the decision (ADR 0093 § 4). deferred_until is when
-- a statement held back by an authority's deferral goes out (TCO Art. 11(3), ADR 0093 § 7) while sent_at is NULL; the
-- hourly cleanup sends it then and clears it.
CREATE TABLE veyq_moderation_statement (
    id                  UUID NOT NULL PRIMARY KEY,
    measure_id          UUID NOT NULL,
    case_id             UUID NULL,
    ground              VARCHAR(24) NOT NULL,
    basis               VARCHAR(16) NOT NULL,
    epoch               INT NULL,
    sealed_facts        TEXT NULL,
    sent_at             DATETIME(3) NULL,
    deferred_until      DATETIME(3) NULL,
    provision           VARCHAR(200) NULL,
    sealed_member_facts TEXT NULL,
    CONSTRAINT fk_moderation_statement_measure FOREIGN KEY (measure_id) REFERENCES veyq_moderation_block (id),
    CONSTRAINT fk_moderation_statement_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_statement_measure ON veyq_moderation_statement (measure_id);
CREATE INDEX ix_moderation_statement_case ON veyq_moderation_statement (case_id);
CREATE INDEX ix_moderation_statement_deferred ON veyq_moderation_statement (deferred_until);

-- veyq_moderation_statement_recipient: who a statement names — a member's account (ACCOUNT) or a guest identity (GUEST,
-- told nothing in veyq) — and why (roles, comma-joined: SHARE_CREATOR, UPLOADER, SCOPE_OWNER). wrapped_facts_key is the
-- statement's K_facts wrapped to that member's identity (moderation-facts-key [caseId, statementId, accountId]), NULL
-- without facts; mailed_at when the instance mail telling them went out, NULL until it did (the hourly cleanup retries for
-- seven days).
CREATE TABLE veyq_moderation_statement_recipient (
    id                UUID NOT NULL PRIMARY KEY,
    statement_id      UUID NOT NULL,
    recipient_kind    VARCHAR(8) NOT NULL,
    recipient_id      VARCHAR(64) NOT NULL,
    roles             VARCHAR(64) NOT NULL,
    mailed_at         DATETIME(3) NULL,
    wrapped_facts_key TEXT NULL,
    CONSTRAINT fk_statement_recipient_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id)
);
CREATE UNIQUE INDEX ux_statement_recipient ON veyq_moderation_statement_recipient (statement_id, recipient_id);
CREATE INDEX ix_statement_recipient_id ON veyq_moderation_statement_recipient (recipient_id);

-- veyq_moderation_objection: the one objection to a statement's measure (unique on statement_id: one per measure, ADR
-- 0093 § 7), sealed to the moderation key (moderation-objection [epoch, statementId]). decided_at/decided_by/outcome
-- (UPHELD or LIFTED) once a moderator decided it; own_review when the account that took the measure decided it, there
-- being no other accepted moderator holding the key (ADR 0099 § 5).
CREATE TABLE veyq_moderation_objection (
    id               UUID NOT NULL PRIMARY KEY,
    statement_id     UUID NOT NULL,
    case_id          UUID NOT NULL,
    objector_account UUID NOT NULL,
    epoch            INT NOT NULL,
    sealed_objection TEXT NOT NULL,
    received_at      DATETIME(3) NOT NULL,
    decided_at       DATETIME(3) NULL,
    decided_by       UUID NULL,
    outcome          VARCHAR(8) NULL,
    own_review       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_moderation_objection_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id),
    CONSTRAINT fk_moderation_objection_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_objection_statement ON veyq_moderation_objection (statement_id);
CREATE INDEX ix_moderation_objection_case ON veyq_moderation_objection (case_id);

-- An authority's order and the deferral of a statement (ADR 0093 §§ 4, 7, 12; uc.moderation.authority-order).
--
-- veyq_authority_order: one row per order entered, by a moderator or the Server Owner (entered_by). kind REMOVAL,
-- PRESERVATION or INFORMATION; share_id the share it names (no foreign key: an order naming a share the server does not
-- know is recorded all the same, with no case); received_at when it reached the contact point, as entered — the hour's
-- clock is the operator's, and this row is its proof; entered_at when the server recorded it. epoch + sealed_reference
-- the authority's reference sealed by the entering client to the moderation key (moderation-order [epoch, id]); the id is
-- the client's, so a retry is the same order. case_id the case it opened (NULL when its share was not found); blocked_at
-- when the share and its content were both blocked (a REMOVAL); hold_until the end the order sets for its hold.
CREATE TABLE veyq_authority_order (
    id               UUID NOT NULL PRIMARY KEY,
    kind             VARCHAR(16) NOT NULL,
    share_id         VARCHAR(64) NOT NULL,
    received_at      DATETIME(3) NOT NULL,
    entered_at       DATETIME(3) NOT NULL,
    entered_by       UUID NOT NULL,
    epoch            INT NOT NULL,
    sealed_reference TEXT NOT NULL,
    case_id          UUID NULL,
    blocked_at       DATETIME(3) NULL,
    hold_until       DATETIME(3) NULL,
    CONSTRAINT fk_authority_order_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE INDEX ix_authority_order_case ON veyq_authority_order (case_id);
CREATE INDEX ix_authority_order_entered ON veyq_authority_order (entered_at);

-- veyq_moderation_deferral: one authority's request to defer a statement — the first, and at most one renewal (unique on
-- statement_id + renewal). The reason is sealed to the moderation key (epoch + sealed_reason, moderation-note [epoch,
-- caseId]), or it is the reference of the order it came with (order_id, sealed_reason NULL); entered_by who entered it.
-- Its end (at most six weeks after it was entered) is the statement's deferred_until while it holds, and when it was
-- entered and until when is the log's STATEMENT_DEFERRED event.
CREATE TABLE veyq_moderation_deferral (
    id            UUID NOT NULL PRIMARY KEY,
    statement_id  UUID NOT NULL,
    case_id       UUID NOT NULL,
    order_id      UUID NULL,
    epoch         INT NULL,
    sealed_reason TEXT NULL,
    entered_by    UUID NOT NULL,
    renewal       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_moderation_deferral_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id),
    CONSTRAINT fk_moderation_deferral_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id),
    CONSTRAINT fk_moderation_deferral_order FOREIGN KEY (order_id) REFERENCES veyq_authority_order (id)
);
CREATE UNIQUE INDEX ux_moderation_deferral ON veyq_moderation_deferral (statement_id, renewal);
CREATE INDEX ix_moderation_deferral_case ON veyq_moderation_deferral (case_id);

-- The account erasure (ADR 0101; uc.account.erase-account): one row per account whose erasure is pending —
-- requested_at and requested_by (SELF or SERVER_OWNER), due_at the purge's date (the request plus the scope trash's
-- retention), mailed_at when the instance mail telling the address went out (NULL until it did; the hourly purge
-- retries). A row of its own, not columns on veyq_user, so the purge's work list is one query. It goes with the account
-- (AccountPurge deletes it first); a stop deletes it.
CREATE TABLE veyq_account_erasure (
    account_id   UUID NOT NULL PRIMARY KEY,
    requested_at DATETIME(3) NOT NULL,
    requested_by VARCHAR(16) NOT NULL,
    due_at       DATETIME(3) NOT NULL,
    mailed_at    DATETIME(3) NULL,
    CONSTRAINT fk_account_erasure_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
CREATE INDEX ix_account_erasure_due ON veyq_account_erasure (due_at);

-- B, the days a backup is kept (ADR 0056 decision 8), once the Server Owner set it: one row, id 1, 7 to 365. No seeded
-- row — no row reads as the default, 30 (BackupRetention). The capture tooling reads it over the connection it dumps
-- through (src/tools/instance_backup.py), so it is instance configuration and none of the restore's data (NOT_DATA).
CREATE TABLE veyq_backup_retention (
    id             INT NOT NULL PRIMARY KEY,
    retention_days INT NOT NULL
);

-- The erasure ledger's outbox (ADR 0056 decision 8): one row per hard erasure no restored row would trigger again, and
-- per moderation block, lift and case, written in the erasure's own transaction. A sweep appends the unsent rows to
-- `erasures.log` beside leg (c)'s key and stamps sent_at; a sent row goes once it is older than B. `line` is the JSON
-- line the file receives — a kind, opaque ids, storage keys and times, never content or a name; `subject_id` is the id
-- it names, kept apart for the tests and the start's count.
CREATE TABLE veyq_erasure_outbox (
    id          UUID NOT NULL PRIMARY KEY,
    kind        VARCHAR(16) NOT NULL,
    subject_id  VARCHAR(64) NOT NULL,
    line        VARCHAR(1024) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    sent_at     DATETIME(3) NULL
);
CREATE INDEX ix_erasure_outbox_sent ON veyq_erasure_outbox (sent_at, recorded_at);

-- The recipients the Server Owner names for the instance privacy notice (ADR 0035 decision 8) — public operational data,
-- typed to be published, shown only through the notice. No seeded row. One row per recipient, `list_index` its place in
-- the list (0-based) and its key; the list is replaced whole on every save (OperatorDetailsStore.replaceRecipients).
-- `kind` is one of HOSTING, OBJECT_STORAGE, MAIL_RELAY, BACKUP, OTHER (RecipientKind), which the notice names in each
-- language, so the German and the English notice never mix languages; `description` is the operator's own words for an
-- OTHER one only — NULL for every other kind (OperatorDetailsHandler refuses a description that does not fit its kind).
-- `country` is an ISO 3166-1 alpha-2 code, named in each page's own language through the JDK's locale data
-- (core/terms/RecipientCountries); NULL is "not named".
CREATE TABLE veyq_operator_recipient (
    list_index  INT NOT NULL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    description VARCHAR(120) NULL,
    country     VARCHAR(2) NULL
);

-- The identity-pin store's entries (ADR 0104, inbox #491): one sealed entry per pin under the head (veyq_identity_pins),
-- at the opaque id only the account can compute (HMAC-SHA256 of the contact's id under a Master-Key-derived key, 64
-- lowercase hex characters). No stored time: an entry carries its first-seen time inside its seal.
CREATE TABLE veyq_identity_pin_entry (
    account_id   UUID NOT NULL,
    pin_id       VARCHAR(64) NOT NULL,
    sealed_entry TEXT NOT NULL,
    CONSTRAINT pk_identity_pin_entry PRIMARY KEY (account_id, pin_id),
    CONSTRAINT fk_identity_pin_entry_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- Everything from one uploader (ADR 0093 §§ 1, 6; a moderator's UPLOADER_CONTENT step, uc.moderation.take-a-measure):
-- a measure of kind CONTENT_UPLOADER names the uploader (target_id: an account's or a guest identity's id; share_id the
-- guest's link) and the space (scope_id), and blocks exactly the items it counted when it was taken — listed here, one row
-- per item: item_kind NODE (a node whose content the uploader put there, a Depot file) or ITEM (an application's item, a
-- Memories medium), target_id its id. Never what the uploader adds later: a person decided on what existed. An item's
-- erasure deletes its row (MeasureTargets.erased), and the measure ends with its last one or with its uploader. The rows
-- go with their measure's (ModerationRecordsPurge deletes them first; the foreign key refuses the other order). Clear ids
-- only, as every measure's.
CREATE TABLE veyq_moderation_block_reach (
    block_id  UUID NOT NULL,
    item_kind VARCHAR(8) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (block_id, item_kind, target_id),
    CONSTRAINT fk_moderation_block_reach_block FOREIGN KEY (block_id) REFERENCES veyq_moderation_block (id)
);
CREATE INDEX ix_moderation_block_reach_target ON veyq_moderation_block_reach (target_id);

-- ADR 0109: a scope rotation re-keys every node below it. One node's re-key arrives in staged parts before its commit (a
-- folder of many children, a Memory of many photos): each row is one ciphertext value the commit swaps into place, keyed
-- by the client-minted re-key id. Nothing reads a staged value but the commit; a stage no commit took is deleted after a
-- day. No foreign key: the purge and the scope's erasure delete these rows themselves (core's re-key erasers).
CREATE TABLE veyq_node_rekey_stage (
    id          UUID NOT NULL,
    rekey_id    UUID NOT NULL,
    scope_id    UUID NOT NULL,
    node_id     UUID NOT NULL,
    part        VARCHAR(32) NOT NULL,
    item_id     VARCHAR(64) NOT NULL,
    field       VARCHAR(32) NOT NULL,
    stage_value TEXT NOT NULL,
    revision    BIGINT NULL,
    created_at  DATETIME(3) NOT NULL,
    CONSTRAINT pk_node_rekey_stage PRIMARY KEY (id),
    CONSTRAINT ux_node_rekey_stage_value UNIQUE (rekey_id, part, item_id, field)
);
CREATE INDEX ix_node_rekey_stage_node ON veyq_node_rekey_stage (node_id);
CREATE INDEX ix_node_rekey_stage_scope ON veyq_node_rekey_stage (scope_id);
CREATE INDEX ix_node_rekey_stage_created ON veyq_node_rekey_stage (created_at);

-- An application left switched off in a tenant (uc.platform.erase-switched-off-application, ADR 0079 decision 8,
-- ADR 0088 decision 5): one row per tenant and application while the application is off for the tenant at either level,
-- and while a due erasure is still running. It is ApplicationErasurePurge's work list, the day the banner names and the
-- claims of the two mails. `application` is the register id (the application's one-segment root entry), never a table
-- name. `switch_level` is the level that made the row (TENANT or INSTANCE), for the notice's words. A row that exists
-- keeps its `off_since` and `due_at`, so a day members were told never moves. Erased with its tenant.
CREATE TABLE veyq_application_erasure (
    tenant_id          UUID NOT NULL,
    application        VARCHAR(64) NOT NULL,
    switch_level       VARCHAR(16) NOT NULL,
    off_since          DATETIME(3) NOT NULL,
    due_at             DATETIME(3) NOT NULL,
    notice_mailed_at   DATETIME(3) NULL,
    reminder_mailed_at DATETIME(3) NULL,
    CONSTRAINT pk_application_erasure PRIMARY KEY (tenant_id, application),
    CONSTRAINT fk_application_erasure_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);
CREATE INDEX ix_application_erasure_due ON veyq_application_erasure (due_at);

-- Securing the account (ADR 0111 decisions 6-10): the account's key chain, the stage its rotation grants every scope
-- through, and the pending re-key each re-granted scope waits on. Everything here cascades from the account (and the
-- scope), so an erasure or a purge needs no step of its own.
--
-- One row per step of an account's key chain (ADR 0111 decision 7). Step 0 (idx 0) is the identity bundle and
-- Secret-Key proof public key the account was born with, unsigned; step i >= 1 retires the keys of step i-1, signed by
-- both of them. `head` is SHA-256("veyq/v1/key-chain/head" || statement) of the step, base64 - derived, kept so every
-- directory read serves it without rebuilding the chain. Public keys and signatures only.
CREATE TABLE veyq_key_chain_step (
    id                          UUID NOT NULL PRIMARY KEY,
    user_id                     UUID NOT NULL,
    idx                         INT NOT NULL,
    identity_public_key         TEXT NOT NULL,
    secret_key_proof_public_key TEXT NOT NULL,
    identity_signature          TEXT NULL,
    proof_signature             TEXT NULL,
    head                        VARCHAR(44) NOT NULL,
    created_at                  DATETIME(3) NOT NULL,
    CONSTRAINT ux_key_chain_step UNIQUE (user_id, idx),
    CONSTRAINT fk_key_chain_step_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);

-- The account's one open stage of self-grants: the identity it declares, and when it expires (server-observed). A
-- second open replaces the first. Inert until a Secret-Key-proved publish applies it.
CREATE TABLE veyq_rotation_stage (
    id                  UUID NOT NULL PRIMARY KEY,
    user_id             UUID NOT NULL,
    identity_public_key TEXT NOT NULL,
    expires_at          DATETIME(3) NOT NULL,
    CONSTRAINT ux_rotation_stage_user UNIQUE (user_id),
    CONSTRAINT fk_rotation_stage_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);
CREATE INDEX ix_rotation_stage_expires ON veyq_rotation_stage (expires_at);

-- One staged self-grant per scope: the scope's epoch key sealed to the stage's identity, signed by it (verified at
-- upload). A purged scope takes its staged grant with it.
CREATE TABLE veyq_rotation_stage_grant (
    id              UUID NOT NULL PRIMARY KEY,
    stage_id        UUID NOT NULL,
    scope_id        UUID NOT NULL,
    epoch           INT NOT NULL,
    wrapped_grant   TEXT NOT NULL,
    grant_signature TEXT NOT NULL,
    CONSTRAINT ux_rotation_stage_grant UNIQUE (stage_id, scope_id),
    CONSTRAINT fk_rotation_stage_grant_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_grant_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);

-- Per scope the publish re-granted: the epoch it had then (ADR 0111 decision 8). While the scope's current epoch still
-- equals it, the account (any of its devices) may commit the next one without being an owner; the states read derives
-- from it. A later rotation replaces the account's rows.
CREATE TABLE veyq_rotation_regrant (
    id                UUID NOT NULL PRIMARY KEY,
    user_id           UUID NOT NULL,
    scope_id          UUID NOT NULL,
    epoch_at_rotation INT NOT NULL,
    CONSTRAINT ux_rotation_regrant UNIQUE (user_id, scope_id),
    CONSTRAINT fk_rotation_regrant_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_regrant_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);

-- A member's verification of another member of the tenant (ADR 0111 decisions 12-13): "in this tenant, this verifier at
-- this head of their key chain confirmed this subject at this head of theirs", signed by the verifier's identity and
-- sealed under a value key of its own (field `member-verification`), whose wrap under the tenant key of `epoch` is
-- wrapped_value_key (seal purpose `value-key`, at most 128 characters; ADR 0115): a rotation rewrites the wrap only.
-- Opaque to the server, which stores and relays it and checks nothing of its content. One row per (tenant, verifier,
-- subject): the verifier's put replaces it.
--
-- The rows go with either account (an erasure) and with the tenant (its purge) by cascade, so neither needs a step of
-- its own; a member's removal from the tenant deletes every row of that tenant naming them, as verifier or as subject,
-- in the removal's transaction (VerificationMembershipEraser).
CREATE TABLE veyq_verification (
    tenant_id         UUID NOT NULL,
    verifier_user_id  UUID NOT NULL,
    subject_user_id   UUID NOT NULL,
    epoch             INT NOT NULL,
    sealed_statement  TEXT NOT NULL,
    created_at        DATETIME(3) NOT NULL,
    wrapped_value_key VARCHAR(128) NOT NULL,
    CONSTRAINT pk_verification PRIMARY KEY (tenant_id, verifier_user_id, subject_user_id),
    CONSTRAINT fk_verification_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_verifier FOREIGN KEY (verifier_user_id) REFERENCES veyq_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_subject FOREIGN KEY (subject_user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);
CREATE INDEX ix_verification_subject ON veyq_verification (tenant_id, subject_user_id);

-- An invite carries its owner's anchor, and an owner's securing re-signs what it rooted (ADR 0111 decisions 19-21).
-- One staged re-signed invite per (stage, invite) (decision 20): the anchor epoch it was signed at, and the stage
-- identity's two signatures (verified at upload) — or both null, a declared invite the publish ends. Goes with its stage
-- (a re-open, the publish, the expiry purge) and with its invite.
CREATE TABLE veyq_rotation_stage_invite (
    id                     UUID NOT NULL PRIMARY KEY,
    stage_id               UUID NOT NULL,
    invite_id              UUID NOT NULL,
    anchor_epoch           INT NOT NULL,
    invite_key_signature   TEXT NULL,
    anchor_grant_signature TEXT NULL,
    CONSTRAINT ux_rotation_stage_invite UNIQUE (stage_id, invite_id),
    CONSTRAINT fk_rotation_stage_invite_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_invite_invite FOREIGN KEY (invite_id) REFERENCES veyq_tenant_invite (id) ON DELETE CASCADE
);

-- One staged endorsement per (stage, tenant, member) (decision 21): the stage identity's signature over the member's
-- sealed join record (verified at upload), or null for a declared record; and the SHA-256 of the record it was staged
-- against, so a record replaced before the publish (a rejoin) is refused there rather than endorsed unseen.
CREATE TABLE veyq_rotation_stage_endorsement (
    id            UUID NOT NULL PRIMARY KEY,
    stage_id      UUID NOT NULL,
    tenant_id     UUID NOT NULL,
    user_id       UUID NOT NULL,
    record_digest VARCHAR(44) NOT NULL,
    endorsement   TEXT NULL,
    CONSTRAINT ux_rotation_stage_endorsement UNIQUE (stage_id, tenant_id, user_id),
    CONSTRAINT fk_rotation_stage_endorsement_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_endorsement_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_endorsement_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);

-- A holder of the instance moderation key keeps it through securing their account (ADR 0099 § 4, ADR 0060 Decision 4):
-- one staged epoch per (stage, epoch), its M_n re-sealed by the caller's client to the identity the stage declares —
-- opaque here — or null, a declared epoch the publish deletes. The publish writes each re-sealed one as the caller's
-- grant in place of the grant it deletes. Goes with its stage (a re-open, the publish, the expiry purge, the account's
-- erasure through the stage's own cascade). No foreign key to the epoch: the publish writes only epochs the caller
-- still holds, read under the current epoch's lock.
CREATE TABLE veyq_rotation_stage_moderation_grant (
    id           UUID NOT NULL PRIMARY KEY,
    stage_id     UUID NOT NULL,
    epoch        INT NOT NULL,
    sealed_grant TEXT NULL,
    CONSTRAINT ux_rotation_stage_moderation_grant UNIQUE (stage_id, epoch),
    CONSTRAINT fk_rotation_stage_moderation_grant_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE
);

-- ADR 0112 decision 1: the blobs an application stores, managed by core's blob service — any number per node, each in a
-- role its application declares and held by an owner, an application-side id core stores and compares and never
-- resolves (Expenses' entry id, Memories' medium id). The server holds what ADR 0112 decision 6 names and nothing else:
--
-- - `id`: the client-minted id (ADR 0072 decision 3), the one the application's own sealed records name it by
--   (Expenses: a receipt id in an entry's revision).
-- - `node_id`: the node it belongs to; its `K_blob` is wrapped under the node's key and bound to (blob, node, `role`) in
--   its `veyq_blob` row (no new blob purpose). No FK, as an application's record rows: a node's trash is soft, and its
--   purge erases these rows first (`ApplicationBlobNodeSubtreeEraser`, phase 160).
-- - `owner_id`: the application's record that holds it. Plain, no FK: a record may get its first row after its blobs.
-- - `role`: the application's open `BlobRole` token (16 characters, the contract's bound).
-- - `blob_id`: the blob — an arm of `veyq_blob` (validate_blob_reference_arity.py), with its foreign key; unique, as
--   every arm's blob is its own.
-- - `writer_user_id` / `writer_identity_id`: who completed it, a member or a link identity, exactly one
--   (ck_application_blob_writer), the pair `PrincipalKey` names.
-- - `created_at`: when the server stored it. `kept_at`: when its owner last kept it — set by the application's write
--   (`ApplicationBlobs.keep`), cleared for good by a release by name. `released_at`: when it was released, by name or with
--   its owner's end; the owner's next keep clears the second kind. The purge erases a row once the trash retention has
--   passed since its release, or since its completion while nothing ever kept it.
--
-- The scope FK puts the table in the scope erasure's closure (validate_scope_erasure_arity.py).
CREATE TABLE veyq_application_blob (
    id                 UUID NOT NULL,
    scope_id           UUID NOT NULL,
    node_id            UUID NOT NULL,
    owner_id           UUID NOT NULL,
    role               VARCHAR(16) NOT NULL,
    blob_id            UUID NOT NULL,
    writer_user_id     UUID NULL,
    writer_identity_id UUID NULL,
    created_at         DATETIME(3) NOT NULL,
    kept_at            DATETIME(3) NULL,
    released_at        DATETIME(3) NULL,
    CONSTRAINT pk_application_blob PRIMARY KEY (id),
    CONSTRAINT ux_application_blob_blob UNIQUE (blob_id),
    CONSTRAINT fk_application_blob_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_application_blob_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_application_blob_identity FOREIGN KEY (writer_identity_id) REFERENCES veyq_share_identity (id),
    CONSTRAINT ck_application_blob_writer CHECK ((writer_user_id IS NULL AND writer_identity_id IS NOT NULL)
        OR (writer_user_id IS NOT NULL AND writer_identity_id IS NULL))
);
-- A node's blobs in id order (its reads, its re-key, its purge); an owner's (a keep, a release, a report, a hold).
CREATE INDEX ix_application_blob_node ON veyq_application_blob (node_id, id);
CREATE INDEX ix_application_blob_owner ON veyq_application_blob (owner_id);
CREATE INDEX ix_application_blob_scope ON veyq_application_blob (scope_id);
CREATE INDEX ix_application_blob_identity ON veyq_application_blob (writer_identity_id);
CREATE INDEX ix_application_blob_user ON veyq_application_blob (writer_user_id);
-- The purge's selections: the released ones, and the never kept ones, oldest first.
CREATE INDEX ix_application_blob_released ON veyq_application_blob (released_at);
CREATE INDEX ix_application_blob_created ON veyq_application_blob (created_at);

-- The scope-level rows a rotation re-wraps (ADR 0115 decision 2), staged in pages before its commit under the rotator's
-- re-key id: one row per (owner part, row, column). A table of its own rather than `veyq_node_rekey_stage`: a
-- scope-level row is addressed by no node (a verification by two account ids, longer than that table's item id). A
-- rotation stages wraps only: a value key's wrap (`value-key`: 1 + 12 + 32 + 16 bytes, 84 base64 characters, a contract
-- cap of 128) or a scope-root entry's part — its key wrap (84, cap 256), its slug token (an HMAC-SHA256, 44, cap 128)
-- and its name (a field seal of any name a member may write, the contract's cap of 4,096). The name is the largest, so
-- `stage_value` is TEXT (65,535 bytes); a column of its own for the short parts would add a rule and save nothing, as
-- TEXT stores what it holds. A wrap is short enough to compare itself: `replaced` is the wrap the rotator re-wrapped, as
-- it read it (VARCHAR(128), the wrap columns' width), and the commit refuses a row that holds another — no digest on
-- either side. A root entry is held by `revision`, its placement revision. Nothing reads a staged value but the
-- rotation's commit; a stage no commit took is deleted after a day. No foreign key: the scope's erasure deletes these
-- rows itself (`ScopeRekeyStageScopeEraser`).
CREATE TABLE veyq_scope_rekey_stage (
    id          UUID NOT NULL,
    rekey_id    UUID NOT NULL,
    scope_id    UUID NOT NULL,
    part        VARCHAR(32) NOT NULL,
    item_id     VARCHAR(80) NOT NULL,
    field       VARCHAR(32) NOT NULL,
    stage_value TEXT NOT NULL,
    replaced    VARCHAR(128) NULL,
    revision    BIGINT NULL,
    created_at  DATETIME(3) NOT NULL,
    CONSTRAINT pk_scope_rekey_stage PRIMARY KEY (id),
    CONSTRAINT ux_scope_rekey_stage_value UNIQUE (rekey_id, part, item_id, field)
);
CREATE INDEX ix_scope_rekey_stage_scope ON veyq_scope_rekey_stage (scope_id);
CREATE INDEX ix_scope_rekey_stage_created ON veyq_scope_rekey_stage (created_at);
