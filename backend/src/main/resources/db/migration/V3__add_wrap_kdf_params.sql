-- KDF parameters + salt for a Master Key wrap, so a client can re-derive the unlock KEK at login.
-- Opaque to the server; present for password/recovery methods, null for device/passkey.

ALTER TABLE veyq_master_key_wrap ADD COLUMN kdf_params TEXT;
