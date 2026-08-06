-- cr-0118 (ADR 0037): optional password protection on a share — an operator-blind ACCESS-CONTROL gate (not a
-- crypto gate). The content stays E2EE under the unchanged K_share fragment; the password gates what the server
-- serves. The client derives pwToken = Argon2id(password, pw_salt) and pw_verifier = SHA-256(pwToken); the server
-- stores only the verifier + salt + a K_share-sealed reveal copy (sealed_password) — never the raw password.
-- All nullable / defaulted so existing shares are simply "no password". Types kept to the H2 ∩ MariaDB subset.

ALTER TABLE veyq_share ADD COLUMN password_required BOOLEAN NOT NULL DEFAULT FALSE; -- true → guest resolve/blob GET need the token
ALTER TABLE veyq_share ADD COLUMN pw_salt         TEXT; -- Argon2id salt for pwToken (opaque base64), null when no password
ALTER TABLE veyq_share ADD COLUMN pw_verifier     TEXT; -- SHA-256(pwToken) the server checks (opaque base64), never the raw password
ALTER TABLE veyq_share ADD COLUMN sealed_password TEXT; -- the password sealed under K_share (management-only reveal), or null
