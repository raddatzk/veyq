-- An invite carries its owner's anchor, and an owner's securing re-signs what it rooted (ADR 0111
-- decisions 19, 20 and 21). No existing row is backfilled: no release exists (`release: none`), so every database is
-- recreated, and an invite or a profile older than this file exists only in a dev database.

-- The invite's anchor (decision 19): the inviting owner's own grant of the tenant key, sealed to the invite key, its epoch
-- and the owner's `grant` signature over it. Written at mint and reissue (equal to the grant), moved only by a tenant
-- rotation its signer commits, and nulled with the key and the grant by every statement that ends the invite.
ALTER TABLE veyq_tenant_invite ADD COLUMN anchor_wrapped_grant TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN anchor_epoch INT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN anchor_grant_signature TEXT NULL;
-- The securing's plan reads the open invites an account signed, in every tenant.
CREATE INDEX ix_tenant_invite_signer ON veyq_tenant_invite (signer_user_id);

-- Who rooted a member's join (decision 21): the signer of the invite it was accepted through, written at accept with the
-- join record; and that owner's endorsement of the sealed record (decision 21), written by their securing — null until
-- then, for a record their securing declared, and for the tenant's first owner. No foreign key: the id is kept when the
-- signer's account is erased (`AccountPurge`), as a grant keeps its granter's.
ALTER TABLE veyq_tenant_profile ADD COLUMN join_signer_user_id UUID NULL;
ALTER TABLE veyq_tenant_profile ADD COLUMN join_endorsement TEXT NULL;
CREATE INDEX ix_tp_join_signer ON veyq_tenant_profile (join_signer_user_id);

-- One staged re-signed invite per (stage, invite) (decision 20): the anchor epoch it was signed at, and the stage
-- identity's two signatures (verified at upload) — or both null, a declared invite the publish ends. Goes with its stage
-- (a re-open, the publish, the expiry purge) and with its invite.
CREATE TABLE veyq_rotation_stage_invite (
    id                     UUID NOT NULL PRIMARY KEY,
    stage_id               UUID NOT NULL,
    invite_id              UUID NOT NULL,
    anchor_epoch           INT  NOT NULL,
    invite_key_signature   TEXT NULL,
    anchor_grant_signature TEXT NULL,
    CONSTRAINT ux_rotation_stage_invite UNIQUE (stage_id, invite_id),
    CONSTRAINT fk_rotation_stage_invite_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_invite_invite FOREIGN KEY (invite_id) REFERENCES veyq_tenant_invite (id) ON DELETE CASCADE
);

-- One staged endorsement per (stage, tenant, member) (decision 21): the stage identity's signature over the member's
-- sealed join record (verified at upload), or null for a declared record; and the SHA-256 of the record it was staged
-- against, so a record replaced before the publish (a rejoin) is refused there rather than endorsed unseen.
CREATE TABLE veyq_rotation_stage_endorsement (
    id            UUID        NOT NULL PRIMARY KEY,
    stage_id      UUID        NOT NULL,
    tenant_id     UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    record_digest VARCHAR(44) NOT NULL,
    endorsement   TEXT        NULL,
    CONSTRAINT ux_rotation_stage_endorsement UNIQUE (stage_id, tenant_id, user_id),
    CONSTRAINT fk_rotation_stage_endorsement_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_endorsement_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_rotation_stage_endorsement_user FOREIGN KEY (user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);
