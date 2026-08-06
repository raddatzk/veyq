-- Identity pinning — the operator-blind, cross-device pin store (en.account.identity-pinning, cr-0098, ADR 0028).
-- One sealed blob per account: symWrap(masterKey, blob, "identity-pins"), the client's TOFU memory of each
-- contact's identity key. The server stores + relays ONE opaque ciphertext and never interprets it — no userId,
-- no key, no count. `version` is a monotonic optimistic-concurrency counter (born 0, bumped on each PUT); a stale
-- PUT loses the compare-and-set and gets 409 stale_version. Born empty: an account with no row reads as
-- { blob: null, version: 0 }.
CREATE TABLE veyq_identity_pins (
    account_id  VARCHAR(36) NOT NULL PRIMARY KEY,
    sealed_blob TEXT,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_identity_pins_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
