-- Securing the account (cr-secure-the-account, ADR 0111 decisions 6-10): the account's key chain, the stage its rotation
-- grants every scope through, and the pending re-key each re-granted scope waits on. Everything here cascades from the
-- account (and the scope), so an erasure or a purge needs no step of its own.

-- One row per step of an account's key chain (ADR 0111 decision 7). Step 0 (idx 0) is the identity bundle and
-- Secret-Key proof public key the account was born with, unsigned; step i >= 1 retires the keys of step i-1, signed by
-- both of them. `head` is SHA-256("veyq/v1/key-chain/head" || statement) of the step, base64 - derived, kept so every
-- directory read serves it without rebuilding the chain. Public keys and signatures only. No existing account is
-- backfilled: no release exists, so every database is recreated.
CREATE TABLE veyq_key_chain_step (
    id                          UUID        NOT NULL PRIMARY KEY,
    user_id                     UUID        NOT NULL,
    idx                         INT         NOT NULL,
    identity_public_key         TEXT        NOT NULL,
    secret_key_proof_public_key TEXT        NOT NULL,
    identity_signature          TEXT        NULL,
    proof_signature             TEXT        NULL,
    head                        VARCHAR(44) NOT NULL,
    created_at                  DATETIME(3) NOT NULL,
    CONSTRAINT ux_key_chain_step UNIQUE (user_id, idx),
    CONSTRAINT fk_key_chain_step_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);

-- The account's one open stage of self-grants (cr-secure-the-account decision 3): the identity it declares, and when it
-- expires (server-observed). A second open replaces the first. Inert until a Secret-Key-proved publish applies it.
CREATE TABLE veyq_rotation_stage (
    id                  UUID        NOT NULL PRIMARY KEY,
    user_id             UUID        NOT NULL,
    identity_public_key TEXT        NOT NULL,
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
    epoch           INT  NOT NULL,
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
    epoch_at_rotation INT  NOT NULL,
    CONSTRAINT ux_rotation_regrant UNIQUE (user_id, scope_id),
    CONSTRAINT fk_rotation_regrant_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_regrant_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);
