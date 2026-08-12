-- The sealed device register — the operator-blind naming layer over unnamed session families
-- (en.account.device-register, ADR 0004). One sealed blob per account: symWrap(masterKey, blob,
-- "device-register"), holding a stable client-side device id, its display label, and the device<->family join.
-- The server stores + relays ONE opaque ciphertext and never interprets it — it learns no device name, no
-- device count, and above all no device<->family link, which is the link ADR 0004's unnamed families exist to
-- withhold. `version` is a monotonic optimistic-concurrency counter (born 0, bumped on each PUT); a stale PUT
-- loses the compare-and-set and gets 409 stale_version. Born empty: an account with no row reads as
-- { blob: null, version: 0 }.
--
-- sealed_blob stays plain TEXT, deliberately unlike veyq_identity_pins (which V41 had to widen to MEDIUMTEXT
-- for its 1 MiB cap): this register holds a handful of short labels and joins, so the contract caps it at
-- 65532 — inside the MariaDB TEXT ceiling, which is what scripts/validate_maxlength_storage.py checks.
CREATE TABLE veyq_device_register (
    account_id  VARCHAR(36) NOT NULL PRIMARY KEY,
    sealed_blob TEXT,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_device_register_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
