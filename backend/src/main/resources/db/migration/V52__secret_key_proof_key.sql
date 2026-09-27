-- cr-cold-credential-proves-the-secret-key (ADR 0060 Decision 2, ADR 0054 Decision 1): every account carries a
-- Secret-Key proof key, and rotation, change-password and recovery completion must each carry a signature under it.
--
-- The column holds only an Ed25519 PUBLIC key (base64), derived by the client from the Secret Key alone. It is
-- nullable because nothing is migrated: an account born before this column cannot produce a signature, so it can
-- neither rotate, change its password nor recover until it is re-registered — the development instance is reset
-- before V1 instead.
--
-- recovery_master_key_tag goes. It was a hash of the Master Key, which a linked device can compute and a database
-- dump contains, so it never proved the Secret Key; keeping it beside the signature would be a second, weaker proof
-- for the same gate.
ALTER TABLE veyq_user ADD COLUMN secret_key_proof_public_key TEXT NULL;
ALTER TABLE veyq_user DROP COLUMN recovery_master_key_tag;
