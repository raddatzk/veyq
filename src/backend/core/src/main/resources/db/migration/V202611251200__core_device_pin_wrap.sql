-- A TV's PIN wrap (ADR 0160 decision 3): a DEVICE_PIN wrap keeps the server share S and the PIN verifier V beside its
-- ciphertext (32 bytes each, base64 — null on every other method), and the failed unlocks in a row; the tenth deletes
-- the row. The server holds neither the device's hardware factor nor the PIN, so it can test no PIN.
ALTER TABLE veyq_master_key_wrap ADD COLUMN pin_share VARCHAR(44) NULL;
ALTER TABLE veyq_master_key_wrap ADD COLUMN pin_verifier VARCHAR(44) NULL;
ALTER TABLE veyq_master_key_wrap ADD COLUMN pin_failures INT NOT NULL DEFAULT 0;
