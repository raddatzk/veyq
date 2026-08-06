-- A group scope carries a member-readable name, stored as opaque base64 ciphertext on the scope row: the
-- server sees an UNNAMED group and never the plaintext. Originally sealed directly under the scope key K_SC
-- (en.spaces.group-scope, cr-0032, ADR 0014); since cr-0096/ADR 0027 it is sealed under a *derived* name-key
-- K_name = HKDF(scope key, ...), so a pending invitee reads the canonical name before holding K_SC. The column
-- is unchanged — migrations are append-only, only what fills it moved. Nullable — TENANT/PERSONAL scopes are
-- anonymous and carry no per-scope name.
ALTER TABLE veyq_scope ADD COLUMN encrypted_name TEXT NULL;
