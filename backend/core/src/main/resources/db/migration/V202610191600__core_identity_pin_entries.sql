-- The identity-pin store as one sealed entry per pin under a sealed head (ADR 0104, inbox #491). veyq_identity_pins
-- keeps one row per account, now the HEAD: its version, entry count and the digest of the whole entry set, sealed under
-- the Master Key at its revision, which stays the store's one compare-and-set counter. The entries live here, one row per
-- pin at the opaque id only the account can compute (HMAC-SHA256 of the contact's id under a Master-Key-derived key, 64
-- lowercase hex characters). No stored time: an entry carries its first-seen time inside its seal.
--
-- No legacy path (owner decision Q3, 2026-10-19: veyq is not live): the single-blob stores are dropped rather than
-- converted. A device that held one refuses the now-missing store as rolled back (ADR 0028 Decision 7) until its person
-- starts the list over; the dev instance is re-seeded instead.
DELETE FROM veyq_identity_pins;
ALTER TABLE veyq_identity_pins MODIFY COLUMN sealed_blob TEXT;

CREATE TABLE veyq_identity_pin_entry (
    account_id   UUID        NOT NULL,
    pin_id       VARCHAR(64) NOT NULL,
    sealed_entry TEXT        NOT NULL,
    CONSTRAINT pk_identity_pin_entry PRIMARY KEY (account_id, pin_id),
    CONSTRAINT fk_identity_pin_entry_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
