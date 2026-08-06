-- First-run setup: the single-owner lock plus the owner account and its Master Key wraps.
-- Types kept to the H2 ∩ MariaDB common subset (TEXT for opaque base64 ciphertext, VARCHAR for short).

CREATE TABLE veyq_server_setup (
    id            INT NOT NULL PRIMARY KEY,
    instance_name VARCHAR(100) NOT NULL,
    completed_at  TIMESTAMP NOT NULL
);

CREATE TABLE veyq_user (
    id                           VARCHAR(36) NOT NULL PRIMARY KEY,
    display_name                 VARCHAR(100) NOT NULL,
    server_role                  VARCHAR(32) NOT NULL,
    suite_id                     VARCHAR(16) NOT NULL,
    identity_public_key          TEXT NOT NULL,
    wrapped_identity_private_key TEXT NOT NULL,
    auth_public_key              TEXT NOT NULL,
    recovery_master_key_tag      TEXT,
    created_at                   TIMESTAMP NOT NULL
);

CREATE TABLE veyq_master_key_wrap (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    method      VARCHAR(16) NOT NULL,
    wrapped_key TEXT NOT NULL,
    CONSTRAINT fk_master_key_wrap_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);
