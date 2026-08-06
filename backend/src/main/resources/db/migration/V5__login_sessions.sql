-- Operator-blind login (ADR 0003) + sessions (ADR 0004). The server stores only opaque nonces and
-- token HASHES — never a password, never a usable session secret.

-- One outstanding login challenge per email (single-use, time-bounded). The nonce is opaque to the
-- server; the client signs it with its password-derived auth private key.
CREATE TABLE veyq_login_challenge (
    email      VARCHAR(254) NOT NULL PRIMARY KEY,
    nonce      TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- A session family = one login / device. Revoking a family logs that device out.
CREATE TABLE veyq_session_family (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id      VARCHAR(36) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_session_family_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- Rotating refresh tokens, one chain per family. Only the SHA-256 hash is stored. Presenting a
-- SUPERSEDED token (a replayed older generation) revokes the whole family — reuse detection (ADR 0004).
CREATE TABLE veyq_refresh_token (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    family_id  VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    generation INT NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES veyq_session_family (id)
);
CREATE UNIQUE INDEX ux_refresh_token_hash ON veyq_refresh_token (token_hash);

-- Short-lived access tokens. Only the SHA-256 hash is stored; resolved to a family on each authed call.
CREATE TABLE veyq_access_token (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    family_id  VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_access_token_family FOREIGN KEY (family_id) REFERENCES veyq_session_family (id)
);
CREATE UNIQUE INDEX ux_access_token_hash ON veyq_access_token (token_hash);
