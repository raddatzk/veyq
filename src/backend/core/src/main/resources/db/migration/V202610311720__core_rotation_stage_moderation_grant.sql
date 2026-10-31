-- A holder of the instance moderation key keeps it through securing their account (ADR 0099 § 4, ADR 0060 Decision 4):
-- one staged epoch per (stage, epoch), its M_n re-sealed by the caller's client to the identity the stage declares —
-- opaque here — or null, a declared epoch the publish deletes. The publish writes each re-sealed one as the caller's
-- grant in place of the grant it deletes. Goes with its stage (a re-open, the publish, the expiry purge, the account's
-- erasure through the stage's own cascade). No foreign key to the epoch: the publish writes only epochs the caller
-- still holds, read under the current epoch's lock.
CREATE TABLE veyq_rotation_stage_moderation_grant (
    id           UUID NOT NULL PRIMARY KEY,
    stage_id     UUID NOT NULL,
    epoch        INT  NOT NULL,
    sealed_grant TEXT NULL,
    CONSTRAINT ux_rotation_stage_moderation_grant UNIQUE (stage_id, epoch),
    CONSTRAINT fk_rotation_stage_moderation_grant_stage FOREIGN KEY (stage_id) REFERENCES veyq_rotation_stage (id) ON DELETE CASCADE
);
