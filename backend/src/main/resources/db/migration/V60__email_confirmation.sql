-- cr-email-address-confirmation (ADR 0074): record address control where nothing else proves it.
--
-- `email_confirmed_at` is a fact about the address, not a permission: the account holder showed, at that time,
-- that they read mail sent to `veyq_user.email`. Nothing is gated on it. Nullable with no backfill: before V1
-- there is no installed base, so every existing row starts unconfirmed.
ALTER TABLE veyq_user ADD COLUMN email_confirmed_at TIMESTAMP NULL;

-- One-time address-confirmation tokens, shaped like veyq_account_recovery (V44): only the SHA-256 token HASH is
-- stored, never the token, and the row binds the account to the address the link was mailed to — a token must not
-- confirm an address it was never sent to.
CREATE TABLE veyq_email_confirmation (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL,
    email       VARCHAR(254) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_email_confirmation_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- Redemption is a point read by hash, and a hash names at most one row.
CREATE UNIQUE INDEX ux_email_confirmation_token_hash ON veyq_email_confirmation (token_hash);

-- Superseding the outstanding token on a fresh request scans by (user, status).
CREATE INDEX ix_email_confirmation_user_status ON veyq_email_confirmation (user_id, status);
