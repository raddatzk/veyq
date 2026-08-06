-- cr-config-secret-key-file-and-fingerprint (ADR 0050 amendment): a non-invertible HKDF fingerprint of the active
-- config-secret KEK, stored on the instance singleton. It turns a lost/changed key file from a SILENT unseal
-- failure into a DETECTED state (MISMATCH) — the server compares the running key's fingerprint against this on
-- every config-secret read. Nullable = not adopted yet (the first configured read writes it). It is a HASH of a
-- 256-bit full-entropy key, NOT the key — the "no usable secret in a DB dump" property (ADR 0050) holds: a dump
-- gains a fingerprint that is not brute-forceable back to the key.
ALTER TABLE veyq_instance ADD COLUMN config_secret_fingerprint TEXT;
