-- Admission is a key-holder act (ADR 0020 move 6) and every grant says who sealed it (ADR 0028 Decision 6).
--
-- A membership that is to receive a grant, and a tenant member invite that will become one, carries an admission:
-- a client-minted id, the scope epoch whose key computed its tag, and the tag — an HMAC only a holder of that epoch
-- key can compute. The server stores and relays all three and never verifies them (it holds no key); accepting an
-- invite copies them onto the new membership, and a rotation re-tags every open one under the new epoch.
--
-- Every stored grant carries its granter and the granter's Ed25519 identity signature over it. The recipient checks
-- the signature before the key inside is used; the server stores and relays it and never verifies it.
--
-- All nullable, and nothing is migrated: a membership or grant written before this migration has none, so no client
-- delivers to it or opens it. The development instance is reset before V1 (cr-admission-is-a-key-holder-act).
ALTER TABLE veyq_membership ADD COLUMN admission_id VARCHAR(36) NULL;
ALTER TABLE veyq_membership ADD COLUMN admission_epoch INT NULL;
ALTER TABLE veyq_membership ADD COLUMN admission_tag TEXT NULL;
ALTER TABLE veyq_membership ADD COLUMN granter_user_id VARCHAR(36) NULL;
ALTER TABLE veyq_membership ADD COLUMN grant_signature TEXT NULL;

ALTER TABLE veyq_tenant_invite ADD COLUMN admission_id VARCHAR(36) NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN admission_epoch INT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN admission_tag TEXT NULL;
