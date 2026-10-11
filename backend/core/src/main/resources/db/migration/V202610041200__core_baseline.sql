-- The platform's (core's) schema as one baseline (ADR 0079): every core table in dependency order. The
-- Memories application's tables live in its own baseline, at a later version, and reference core tables only;
-- no core table references them.
--
-- Operator-blind throughout: a column the server could read content from does not exist. TEXT holds opaque
-- base64 ciphertext, VARCHAR hashes and tokens, UUID every id that is exactly a UUID. TEXT is a 65 535-byte ceiling on MariaDB (H2 does not
-- enforce it), so a contract cap above 65 532 base64 chars needs MEDIUMTEXT — scripts/validate_maxlength_storage.py
-- holds the two sides together. Types stay in what H2 MODE=MariaDB (the test engine) and MariaDB both parse.
-- Constraint and index names are cited by code, guards and tests: renaming one is a schema change.
--
-- Ids are MariaDB's native UUID type (inbox #387, decided 2026-10-11, before any release): 16 bytes, printed as the
-- canonical lowercase string, so the entities keep `String id` with no converter and a SQL console still reads them.
-- Only a column that holds nothing but a UUID is one (every id, FK and *_user_id / *_scope_id style reference);
-- the share id (a 256-bit token), the synthetic composite keys `userId|scopeId[|nodeId]`, owner_key, slug_token and
-- every hash stay VARCHAR. A non-UUID string in a UUID column is error 1292 on INSERT, and matches nothing on a
-- SELECT, so each id enters through a `format: uuid` contract field, whose type (java.util.UUID) refuses it with a
-- 400/404 before a query is built. A database created from the VARCHAR(36) form of this baseline is recreated
-- (`docker compose -f infra/docker-compose.yml down -v`), not migrated: nothing was released.

-- First-run setup: the single-owner lock.
CREATE TABLE veyq_server_setup (
    id           INT NOT NULL PRIMARY KEY
);

-- An account. Its keys are public keys or ciphertext; the password never reaches the server (ADR 0003).
-- email_confirmed_at is a fact about the address, not a permission: nothing is gated on it (ADR 0074).
-- secret_key_proof_public_key is the Ed25519 public key rotation, change-password and recovery are signed under
-- (ADR 0060, ADR 0054).
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
    email_confirmed_at           TIMESTAMP NULL
);
CREATE UNIQUE INDEX ux_veyq_user_email ON veyq_user (email);

-- A Master Key wrap per unlock method; kdf_params are opaque to the server (null for device/passkey).
CREATE TABLE veyq_master_key_wrap (
    id          UUID NOT NULL PRIMARY KEY,
    user_id     UUID NOT NULL,
    method      VARCHAR(16) NOT NULL,
    wrapped_key TEXT NOT NULL,
    kdf_params  TEXT,
    CONSTRAINT fk_master_key_wrap_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- The instance singleton: the public auth salt (ADR 0003) and a non-invertible fingerprint of the active
-- config-secret KEK (ADR 0050; null until the first configured read adopts it).
CREATE TABLE veyq_instance (
    id                        INT NOT NULL PRIMARY KEY,
    instance_salt             TEXT NOT NULL,
    config_secret_fingerprint TEXT
);

-- A login challenge is keyed by its nonce, not by the email: one row per email let anyone who knew an address
-- replace or burn the challenge a device was still signing. VARCHAR because a TEXT cannot be a MariaDB key.
CREATE TABLE veyq_login_challenge (
    nonce      VARCHAR(64) NOT NULL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- Sessions (ADR 0004): a family per login/device; only token HASHES are stored.
CREATE TABLE veyq_session_family (
    id           UUID NOT NULL PRIMARY KEY,
    user_id      UUID NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_session_family_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

CREATE TABLE veyq_refresh_token (
    id         UUID NOT NULL PRIMARY KEY,
    family_id  UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    generation INT NOT NULL,
    status     VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
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
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_access_token_family FOREIGN KEY (family_id) REFERENCES veyq_session_family (id)
);
CREATE UNIQUE INDEX ux_access_token_hash ON veyq_access_token (token_hash);

-- A tenant, resolved by Host. The terms (ADR 0035) are the operator's free text as a per-locale JSON map, so
-- utf8mb4 can take them past the TEXT ceiling: MEDIUMTEXT. share_link_limit_bytes (ADR 0073 §4) and
-- storage_quota_bytes (§1, null = no quota) live here because every upload start locks this row (§5).
-- TenantEntity repeats the link-limit default for a tenant born through the entity.
CREATE TABLE veyq_tenant (
    id                       UUID NOT NULL PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL,
    domain                   VARCHAR(253) NOT NULL,
    owner_email              VARCHAR(254) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    terms_markdown_by_locale MEDIUMTEXT,
    terms_version            INT NOT NULL DEFAULT 0,
    share_link_limit_bytes   BIGINT NOT NULL DEFAULT 10737418240,
    storage_quota_bytes      BIGINT NULL,
    CONSTRAINT ux_tenant_domain UNIQUE (domain)
);

-- A tenant invite: only the SHA-256 token hash is stored. The admission triple (ADR 0020) is relayed, never
-- verified by the server.
CREATE TABLE veyq_tenant_invite (
    id              UUID NOT NULL PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    owner_email     VARCHAR(254) NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    role            VARCHAR(16) NOT NULL DEFAULT 'OWNER',
    admission_id    UUID NULL,
    admission_epoch INT NULL,
    admission_tag   TEXT NULL,
    CONSTRAINT fk_tenant_invite_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_tenant_invite_token_hash ON veyq_tenant_invite (token_hash);
CREATE INDEX ix_tenant_invite_tenant ON veyq_tenant_invite (tenant_id);

-- A scope (TENANT / GROUP / PERSONAL). It holds no key. personal_owner is the owning account on a PERSONAL
-- scope and null on every other kind, so ux_scope_personal_owner allows one personal scope per member and
-- tenant (both engines admit any number of nulls in a unique index).
CREATE TABLE veyq_scope (
    id              UUID NOT NULL PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    kind            VARCHAR(16) NOT NULL,
    encrypted_name  TEXT NULL,
    current_epoch   INT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMP NULL,
    name_epoch      INT NOT NULL DEFAULT 0,
    sealed_icon     TEXT NULL,
    icon_epoch      INT NOT NULL DEFAULT 0,
    member_removals INT NOT NULL DEFAULT 0,
    personal_owner  UUID NULL,
    CONSTRAINT fk_scope_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE INDEX ix_scope_tenant ON veyq_scope (tenant_id);
CREATE UNIQUE INDEX ux_scope_personal_owner ON veyq_scope (tenant_id, personal_owner);

-- Membership: (user, scope, role, grant) (ADR 0007). wrapped_grant is null for a grant-less member until a
-- key-holder delivers it. The fk_m21_* names are the table's constraint names and are kept as they are.
CREATE TABLE veyq_membership (
    id                          UUID NOT NULL PRIMARY KEY,
    user_id                     UUID NOT NULL,
    tenant_id                   UUID NOT NULL,
    scope_id                    UUID NOT NULL,
    role                        VARCHAR(16) NOT NULL,
    wrapped_grant               TEXT,
    created_at                  TIMESTAMP NOT NULL,
    granted_epoch               INT NOT NULL DEFAULT 0,
    pending_consent             BOOLEAN NOT NULL DEFAULT FALSE,
    sealed_name_key             TEXT NULL,
    added_by                    UUID NULL,
    admission_id                UUID NULL,
    admission_epoch             INT NULL,
    admission_tag               TEXT NULL,
    granter_user_id             UUID NULL,
    grant_signature             TEXT NULL,
    granter_identity_public_key TEXT NULL,
    CONSTRAINT fk_m21_user   FOREIGN KEY (user_id)   REFERENCES veyq_user (id),
    CONSTRAINT fk_m21_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id),
    CONSTRAINT fk_m21_scope  FOREIGN KEY (scope_id)  REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_membership_user_scope ON veyq_membership (user_id, scope_id);

-- The node tree (ADR 0079: the platform's). parent_id is the parent node id, or the SCOPE id for a root node, so
-- it is not a foreign key; slug uniqueness is per parent slot. ux_node_parent_slug spans trashed rows on
-- purpose: a trash frees the slug by writing the node's own id into slug_token (ADR 0061), because neither
-- engine pair has a filtered index. No column here is an application's (inbox #341): a Memory's cover is an
-- attachment (veyq_node_attachment, slot COVER) and its gallery counters are Memories' own table. Of this table's
-- times only created_at keeps the millisecond (TIMESTAMP(3)): clients order equal-named siblings by it, then by id
-- (ADR 0015).
CREATE TABLE veyq_node (
    id                      UUID NOT NULL PRIMARY KEY,
    scope_id                UUID NOT NULL,
    parent_id               UUID NOT NULL,
    kind                    VARCHAR(16) NOT NULL,
    encrypted_name          TEXT NOT NULL,
    slug_token              VARCHAR(128) NOT NULL,
    order_index             INT NOT NULL,
    wrapped_content_key     TEXT NOT NULL,
    created_at              TIMESTAMP(3) NOT NULL,
    wrapped_meta            TEXT NULL,
    epoch                   INT NOT NULL DEFAULT 0,
    deleted_at              TIMESTAMP NULL,
    sealed_drop_private_key TEXT NULL,
    meta_version            BIGINT NOT NULL DEFAULT 0,
    placement_version       BIGINT NOT NULL DEFAULT 0,
    share_identity_id       UUID NULL,
    CONSTRAINT fk_node_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_node_parent_slug ON veyq_node (parent_id, slug_token);
CREATE INDEX ix_node_scope ON veyq_node (scope_id);

-- An open upload session (ADR 0013): bound to its opener (an account or a share identity, never both) and to
-- the ids its K_blob wrap is sealed to (blob_id, owner_id, blob_role; ADR 0072). blob_id is unique so two open
-- sessions never promise one blob id.
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
    last_window_at           TIMESTAMP NOT NULL,
    blob_id                  UUID NULL,
    owner_id                 UUID NULL,
    blob_role                VARCHAR(16) NULL,
    CONSTRAINT fk_upload_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_upload_scope ON veyq_upload_session (scope_id);
CREATE UNIQUE INDEX ux_upload_session_blob_id ON veyq_upload_session (blob_id);

-- A completed blob: the chunk manifest and the K_blob wrap; the bytes stay in the object store under
-- storage_key/<index>. wrapped_meta is the blob's metadata sealed under its own K_blob (the blob-meta seal),
-- copied from its upload session and written once: no statement updates it.
CREATE TABLE veyq_blob (
    id               UUID NOT NULL PRIMARY KEY,
    scope_id         UUID NOT NULL,
    storage_key      VARCHAR(64) NOT NULL,
    chunk_count      INT NOT NULL,
    byte_size        BIGINT NOT NULL,
    wrapped_blob_key TEXT NOT NULL,
    wrapped_meta     TEXT NULL,
    manifest         TEXT,
    CONSTRAINT fk_blob_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_blob_scope ON veyq_blob (scope_id);

-- The background-task pull queue (ADR 0021): opaque envelopes routed by scope and capability tag.
CREATE TABLE veyq_job (
    id               UUID NOT NULL PRIMARY KEY,
    scope_id         UUID NOT NULL,
    capability_tag   VARCHAR(64) NOT NULL,
    resource_ref     VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    lease_holder     UUID,
    lease_expires_at TIMESTAMP,
    attempts         INT NOT NULL,
    max_attempts     INT NOT NULL,
    sealed_body      MEDIUMTEXT,
    sealed_result    MEDIUMTEXT,
    created_at       TIMESTAMP NOT NULL,
    rotated_epoch    INT NULL,
    CONSTRAINT fk_job_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_job_claimable ON veyq_job (scope_id, capability_tag, status);

-- Per-tenant member identity (ADR 0023).
CREATE TABLE veyq_tenant_profile (
    id          UUID NOT NULL PRIMARY KEY,
    user_id     UUID NOT NULL,
    tenant_id   UUID NOT NULL,
    username    VARCHAR(32) NOT NULL,
    sealed_name TEXT,
    CONSTRAINT fk_tp_user   FOREIGN KEY (user_id)   REFERENCES veyq_user (id),
    CONSTRAINT fk_tp_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_tp_user_tenant ON veyq_tenant_profile (user_id, tenant_id);
CREATE UNIQUE INDEX ux_tp_tenant_username ON veyq_tenant_profile (tenant_id, username);

-- The scope-key epoch chain (ADR 0024): wrapped_prev links epoch n to n-1.
CREATE TABLE veyq_scope_epoch (
    id           UUID NOT NULL PRIMARY KEY,
    scope_id     UUID NOT NULL,
    epoch        INT         NOT NULL,
    wrapped_prev TEXT        NOT NULL,
    CONSTRAINT fk_scope_epoch_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_scope_epoch ON veyq_scope_epoch (scope_id, epoch);

-- The sealed identity-pin store (ADR 0028); version is the compare-and-set counter. 1 MiB cap: MEDIUMTEXT.
CREATE TABLE veyq_identity_pins (
    account_id  UUID NOT NULL PRIMARY KEY,
    sealed_blob MEDIUMTEXT,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_identity_pins_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- A key-holder's decline of a grant-delivery job (ADR 0028): worker_id is the user id, so the decline covers
-- every device of that identity, while a different key-holder still delivers.
CREATE TABLE veyq_job_decline (
    id          UUID NOT NULL PRIMARY KEY,
    job_id      UUID NOT NULL,
    worker_id   UUID NOT NULL,
    reason      VARCHAR(256),
    CONSTRAINT fk_job_decline_job FOREIGN KEY (job_id) REFERENCES veyq_job (id)
);
CREATE UNIQUE INDEX ux_job_decline ON veyq_job_decline (job_id, worker_id);

-- A public share (ADR 0029). A deleted link is a tombstone: the row keeps its ids and creation time, every
-- ciphertext column goes and deleted marks it — a flag, not a time: nothing reads when a link was deleted (#398). wrap is null on a WRITE_ONLY share, which carries the drop public key
-- instead (ADR 0031). write_verifier = SHA-256 of the link's write token (ADR 0076); null takes no new guest.
CREATE TABLE veyq_share (
    id                      VARCHAR(64)  NOT NULL PRIMARY KEY,
    owner_account           UUID  NOT NULL,
    scope_id                UUID  NOT NULL,
    node_id                 UUID  NOT NULL,
    role                    VARCHAR(16)  NOT NULL,
    wrap                    TEXT         NULL,
    sealed_name             TEXT         NULL,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at              TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL,
    sealed_note             TEXT,
    sealed_share_key        TEXT,
    sealed_share_name       TEXT,
    password_required       BOOLEAN NOT NULL DEFAULT FALSE,
    pw_salt                 TEXT,
    pw_verifier             TEXT,
    sealed_password         TEXT,
    sealed_drop_public_key  TEXT NULL,
    sealed_share_key_epoch  INT NULL,
    member_removals_at_mint INT NOT NULL DEFAULT 0,
    reissue_only            BOOLEAN NOT NULL DEFAULT FALSE,
    deleted                 BOOLEAN NOT NULL DEFAULT FALSE,
    write_verifier          TEXT NULL,
    CONSTRAINT fk_share_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE INDEX ix_share_owner ON veyq_share (owner_account);
CREATE INDEX ix_share_node ON veyq_share (node_id);

-- An anonymous guest identity on a share (ADR 0030).
CREATE TABLE veyq_share_identity (
    id                   UUID NOT NULL PRIMARY KEY,
    share_id             VARCHAR(64) NOT NULL,
    sealed_display_name  TEXT        NOT NULL,
    token_hash           VARCHAR(64) NOT NULL,
    wrapped_identity_key TEXT NULL,
    CONSTRAINT fk_share_identity_share FOREIGN KEY (share_id) REFERENCES veyq_share (id)
);
CREATE INDEX ix_share_identity_lookup ON veyq_share_identity (share_id, token_hash);

-- A guest's identity folder (ADR 0080 decision 11): veyq_node.share_identity_id names the WRITE_ONLY identity that
-- holds the folder in its link's root (a drop box). One per (parent, identity): a NULL never collides, so every other
-- node is free of it. Declared here, after veyq_share_identity exists. Not cascading: an identity a folder names is
-- kept by NodeGuestIdentityUsage; the scope erase deletes the nodes (phase 700) before the identities (phase 800).
ALTER TABLE veyq_node ADD CONSTRAINT fk_node_share_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id);
CREATE UNIQUE INDEX ux_node_parent_identity ON veyq_node (parent_id, share_identity_id);

-- A sealed file on a node (ADR 0079 Consequences, cr-files-application P3): one blob per (node, slot). slot is a
-- token the owning application names (Files' FILE_CONTENT, Memories' COVER — each equal to the blob role its wrap is
-- sealed to); core
-- stores and compares it, never names one. share_identity_id is the guest identity that uploaded it, or null for a
-- member. blob_id is unique: one blob row has exactly one reference, so freeing the attachment frees the blob.
-- No foreign key here cascades, on purpose: a cascade would drop the row without BlobUploadStore.deleteBlobRow,
-- leaving its blob row and store objects orphaned with no sweep to find them. The erasers delete these rows first
-- (AttachmentNodeSubtreeEraser, AttachmentScopeEraser); a path that forgets is refused by the database, loudly.
-- fk_attachment_node was the first foreign key onto veyq_node. Keep blob_id on a line of its own:
-- scripts/validate_blob_reference_arity.py reads the column declaration line by line.
CREATE TABLE veyq_node_attachment (
    id                UUID NOT NULL PRIMARY KEY,
    scope_id          UUID NOT NULL,
    node_id           UUID NOT NULL,
    slot              VARCHAR(16) NOT NULL,
    blob_id           UUID NOT NULL,
    share_identity_id UUID NULL,
    CONSTRAINT ux_attachment_node_slot UNIQUE (node_id, slot),
    CONSTRAINT ux_attachment_blob UNIQUE (blob_id),
    CONSTRAINT fk_attachment_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_attachment_node FOREIGN KEY (node_id) REFERENCES veyq_node (id),
    CONSTRAINT fk_attachment_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_attachment_share_identity FOREIGN KEY (share_identity_id) REFERENCES veyq_share_identity (id)
);
CREATE INDEX ix_attachment_scope ON veyq_node_attachment (scope_id);
CREATE INDEX ix_attachment_share_identity ON veyq_node_attachment (share_identity_id);

-- A member's acceptances of the tenant terms (ADR 0035 §4): one row per accepted version, kept as evidence.
CREATE TABLE veyq_tenant_terms_acceptance (
    id               UUID NOT NULL PRIMARY KEY,
    user_id          UUID NOT NULL,
    tenant_id        UUID NOT NULL,
    accepted_version INT         NOT NULL,
    accepted_at      TIMESTAMP   NOT NULL,
    CONSTRAINT ux_terms_acceptance UNIQUE (user_id, tenant_id, accepted_version),
    CONSTRAINT fk_terms_acceptance_user FOREIGN KEY (user_id) REFERENCES veyq_user (id),
    CONSTRAINT fk_terms_acceptance_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

-- Per-tenant SMTP settings (ADR 0048-0050). The password is sealed under the env KEK, never plaintext.
CREATE TABLE veyq_tenant_mail_settings (
    tenant_id           UUID  NOT NULL PRIMARY KEY,
    host                VARCHAR(253),
    port                INT,
    from_address        VARCHAR(254),
    username            VARCHAR(254),
    password_ciphertext TEXT,
    password_nonce      TEXT,
    tls                 BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    subject_template    VARCHAR(200),
    body_template       TEXT,
    CONSTRAINT fk_mail_settings_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

-- One-time password-recovery tokens (ADR 0054): only the hash is stored. Not the second factor — a live token
-- only releases the recovery wrap, which the Secret Key alone opens. email is the address actually mailed.
CREATE TABLE veyq_account_recovery (
    id          UUID  NOT NULL PRIMARY KEY,
    user_id     UUID  NOT NULL,
    email       VARCHAR(254) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_account_recovery_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE INDEX ix_account_recovery_token_hash ON veyq_account_recovery (token_hash);
CREATE INDEX ix_account_recovery_user_status ON veyq_account_recovery (user_id, status);

-- The sealed device register (ADR 0004). Plain TEXT on purpose: the contract caps it inside the TEXT ceiling.
CREATE TABLE veyq_device_register (
    account_id  UUID NOT NULL PRIMARY KEY,
    sealed_blob TEXT,
    version     BIGINT      NOT NULL DEFAULT 0,
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
    answer              TEXT        NULL,
    envelope            TEXT        NULL,
    status              VARCHAR(16) NOT NULL,
    expires_at          TIMESTAMP   NOT NULL,
    created_at          TIMESTAMP   NOT NULL,
    CONSTRAINT fk_device_pairing_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE UNIQUE INDEX ux_device_pairing_handle_hash ON veyq_device_pairing (handle_hash);
CREATE INDEX ix_device_pairing_user_status ON veyq_device_pairing (user_id, status);

-- The store-object reclaim list (ADR 0062). Deliberately opaque: no scope, media or node id, because after an
-- erasure these rows are the only trace of it. No UNIQUE on storage_key, and that is not an oversight: a
-- committed blob keeps its session's storage_key, so one prefix legitimately arrives twice, and a unique index
-- would turn that into a constraint violation inside someone else's transaction.
CREATE TABLE veyq_store_reclaim (
    id              UUID NOT NULL PRIMARY KEY,
    storage_key     VARCHAR(64) NOT NULL,
    chunk_count     INT         NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt_at TIMESTAMP   NOT NULL,
    abandoned_at    TIMESTAMP   NULL
);
CREATE INDEX ix_store_reclaim_due ON veyq_store_reclaim (abandoned_at, next_attempt_at);

-- One-time address-confirmation tokens (ADR 0074), shaped like veyq_account_recovery.
CREATE TABLE veyq_email_confirmation (
    id          UUID  NOT NULL PRIMARY KEY,
    user_id     UUID  NOT NULL,
    email       VARCHAR(254) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_email_confirmation_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
CREATE UNIQUE INDEX ux_email_confirmation_token_hash ON veyq_email_confirmation (token_hash);
CREATE INDEX ix_email_confirmation_user_status ON veyq_email_confirmation (user_id, status);

-- Every share id the scope purge erased (the id only), so create never mints one twice (ADR 0029 §7).
CREATE TABLE veyq_share_retired_id (
    id VARCHAR(64) NOT NULL PRIMARY KEY
);
