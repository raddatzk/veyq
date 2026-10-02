-- A grant is verified under the key its granter signed it with (ADR 0028 § What is still open, inbox #233).
--
-- Every grant row keeps a copy of the granter's identity public bundle as it was when the server wrote the grant,
-- and the reads serve that copy instead of the granter's current key. An account-key rotation (ADR 0060) replaces
-- the current key, so serving it failed every grant the rotator had signed before any recipient's pin was read. The
-- copy is a public key the server already stores (veyq_user.identity_public_key), set on a row it already writes:
-- non-null exactly when granter_user_id is. The recipient's pin, not this value, decides whether the key is trusted.
ALTER TABLE veyq_membership ADD COLUMN granter_identity_public_key TEXT NULL;

-- Backfill from the granter's current key. That is the key that signed every grant whose granter has not rotated
-- since; for a granter who already rotated it is as wrong as the read it replaces, and no history of retired keys
-- exists to do better. A granter whose account row is gone leaves it null, which the client refuses as unsigned,
-- exactly as before.
UPDATE veyq_membership SET granter_identity_public_key =
    (SELECT u.identity_public_key FROM veyq_user u WHERE u.id = veyq_membership.granter_user_id)
WHERE granter_user_id IS NOT NULL;
