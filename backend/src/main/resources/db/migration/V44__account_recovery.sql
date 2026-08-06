-- cr-account-recovery (ADR 0054): one-time tokens for password recovery.
--
-- Shape copied deliberately from veyq_tenant_invite: only the SHA-256 token HASH is stored, never the token, so
-- a database dump yields nothing that can be presented back (the ADR 0004 model). The raw token exists only in
-- the mail, carried in the link's URL fragment.
--
-- This table is NOT the second factor. Holding a live token only buys the release of the RECOVERY wrap, which is
-- opened by the Secret Key alone — so a leaked row, or an operator minting one for themselves, still yields
-- ciphertext they cannot open. The possession proof is veyq_user.recovery_master_key_tag, written at account
-- birth since V1 and finally read by this flow.
--
-- `email` is stored alongside `user_id` because it is the address actually mailed: an account's login address can
-- be corrected later, and a token must not be redeemable against an address it was never sent to.
CREATE TABLE veyq_account_recovery (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL,
    email       VARCHAR(254) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_account_recovery_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- Redemption is always a lookup by hash; the index keeps that a point read as the table accumulates spent rows
-- between purges.
CREATE INDEX ix_account_recovery_token_hash ON veyq_account_recovery (token_hash);

-- Superseding an outstanding token on a fresh request scans by (user, status).
CREATE INDEX ix_account_recovery_user_status ON veyq_account_recovery (user_id, status);
