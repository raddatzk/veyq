-- A member invite is a placeholder member (ADR 0111 decisions 1-5): it holds the invite key the owner signed and a
-- grant wrapped to it, like a membership holds one. The admission tag (ADR 0020 move 6) is gone with everything that
-- carried it. A member's join record is written once, at the invite's accept, beside its name and picture.

ALTER TABLE veyq_tenant_invite DROP COLUMN admission_id;
ALTER TABLE veyq_tenant_invite DROP COLUMN admission_epoch;
ALTER TABLE veyq_tenant_invite DROP COLUMN admission_tag;

-- The invite key's public bundle and the owner's signature over it, and the owner's identity key as it signed, so a
-- later rotation of the owner does not void the invite. Null on an owner invite (it carries no key) and on every
-- invite that is no longer ACTIVE: the statement that ends one nulls all nine columns.
ALTER TABLE veyq_tenant_invite ADD COLUMN invite_public_key TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN invite_key_signature TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN signer_user_id UUID NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN signer_identity_public_key TEXT NULL;
-- The grant a membership carries: the epoch key wrapped to the invite key, signed by its granter (the inviting owner,
-- then whoever's rotation re-wrapped it).
ALTER TABLE veyq_tenant_invite ADD COLUMN wrapped_grant TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN granted_epoch INT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN grant_signature TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN granter_user_id UUID NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN granter_identity_public_key TEXT NULL;

ALTER TABLE veyq_membership DROP COLUMN admission_id;
ALTER TABLE veyq_membership DROP COLUMN admission_epoch;
ALTER TABLE veyq_membership DROP COLUMN admission_tag;

-- Opaque ciphertext under the tenant key (field `member-join-record`), null for a tenant's first owner.
ALTER TABLE veyq_tenant_profile ADD COLUMN sealed_join_record TEXT NULL;
