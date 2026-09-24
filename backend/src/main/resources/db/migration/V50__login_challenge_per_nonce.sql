-- A login challenge is keyed by its NONCE, not by the email (uc.account.login E4). With one row per email,
-- anyone who knew an address could replace the challenge a device was still signing (by asking for a new one)
-- or burn it (with a garbage verify), so every fresh login of that account failed. An email may now hold
-- several outstanding challenges, and a verify consumes only the one it names.
--
-- Drop and recreate: what is lost is at most one challenge TTL (5 minutes) of logins in flight. The nonce is
-- server-generated, 32 bytes = 44 base64 characters; VARCHAR because a TEXT cannot be a MariaDB key.
DROP TABLE veyq_login_challenge;

CREATE TABLE veyq_login_challenge (
    nonce      VARCHAR(64) NOT NULL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
