-- Moderators and the instance moderation key (ADR 0099), and the moderation log (ADR 0093 § 11).
--
-- veyq_moderator: one row per account the Server Owner appointed, surfaced as the MODERATOR role while accepted_at is
-- set and removed_at is not. appointed_by is the Server Owner who appointed it. A removal (by the Server Owner or the
-- moderator's own step-down) sets removed_at and clears the address confirmation; a reappointment starts the row over.
-- The address confirmation (ADR 0097 decision 5, for moderation notices) has the columns veyq_instance_mail_settings has:
-- code_hash is the hash of the one-time code, valid until code_expires_at and while code_attempts stays below the limit,
-- for the address it was sent to (code_address); code_failed says the last code could not be mailed;
-- confirmed_address is the address a code confirmed.
CREATE TABLE veyq_moderator (
    account_id        UUID         NOT NULL PRIMARY KEY,
    appointed_by      UUID         NOT NULL,
    appointed_at      TIMESTAMP(3) NOT NULL,
    accepted_at       TIMESTAMP(3),
    removed_at        TIMESTAMP(3),
    code_hash         VARCHAR(64),
    code_address      VARCHAR(254),
    code_expires_at   TIMESTAMP(3),
    code_attempts     INT          NOT NULL DEFAULT 0,
    code_failed       BOOLEAN      NOT NULL DEFAULT FALSE,
    confirmed_address VARCHAR(254),
    confirmed_at      TIMESTAMP(3),
    CONSTRAINT fk_moderator_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);

-- One row per epoch of the instance moderation key: its public bundle (the X-Wing encapsulation key reports seal to,
-- the Ed25519 key a key proof verifies under) and why it was minted (INITIAL, REMOVAL, IDENTITY_ROTATION, COMPROMISE,
-- BY_HAND). Never M_n or a private half. Who minted it and when is the moderation log's EPOCH_MINTED event, kept under
-- the log's retention rather than for as long as the epoch lives. rotation_due_at is set on the CURRENT epoch when a holder of it
-- leaves or rotates their identity, and the mint of the next epoch is what clears the warning; rotation_due_mailed_at
-- is when the Server Owner was mailed about it (once per epoch).
CREATE TABLE veyq_moderation_epoch (
    epoch                  INT          NOT NULL PRIMARY KEY,
    kem_public_key         TEXT         NOT NULL,
    sign_public_key        VARCHAR(64)  NOT NULL,
    reason                 VARCHAR(24)  NOT NULL,
    rotation_due_at        TIMESTAMP(3),
    rotation_due_mailed_at TIMESTAMP(3)
);

-- A grant: M_n sealed to one moderator's identity (sealTo purpose moderation-grant [epoch]), one per (moderator,
-- epoch). delivered_by is the holder whose client sealed it, or the Server Owner whose admin-web minted it; when is the
-- log's DELIVERED or EPOCH_MINTED event.
CREATE TABLE veyq_moderation_grant (
    id           UUID         NOT NULL PRIMARY KEY,
    moderator_id UUID         NOT NULL,
    epoch        INT          NOT NULL,
    sealed_grant TEXT         NOT NULL,
    delivered_by UUID         NOT NULL,
    CONSTRAINT fk_moderation_grant_moderator FOREIGN KEY (moderator_id) REFERENCES veyq_moderator (account_id),
    CONSTRAINT fk_moderation_grant_epoch FOREIGN KEY (epoch) REFERENCES veyq_moderation_epoch (epoch)
);
CREATE UNIQUE INDEX ux_moderation_grant ON veyq_moderation_grant (moderator_id, epoch);

-- A key proof's challenge: 32 random bytes (as their base64), bound to the account it was issued to, single use,
-- deleted on use and purged once past expires_at.
CREATE TABLE veyq_moderation_challenge (
    challenge  VARCHAR(64)  NOT NULL PRIMARY KEY,
    account_id UUID         NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL
);
CREATE INDEX ix_moderation_challenge_expires ON veyq_moderation_challenge (expires_at);

-- The moderation log (ADR 0093 § 11), append-only. Structured fields in the clear: the event, its time, the moderator
-- it concerns, the account that acted, the case (NULL for an instance event), the epoch and a reason category. Free
-- text only as sealed_text, sealed to the moderation key. expires_at is NULL while the moderator it concerns is on the
-- roster and set by their removal (31 December of the third year after the later of the removal and the event); a
-- scheduled purge deletes what has expired. No foreign keys: the log outlives the rows it names, until its own date.
CREATE TABLE veyq_moderation_event (
    id           UUID         NOT NULL PRIMARY KEY,
    event        VARCHAR(24)  NOT NULL,
    occurred_at  TIMESTAMP(3) NOT NULL,
    moderator_id UUID,
    actor_id     UUID,
    case_id      UUID,
    epoch        INT,
    reason       VARCHAR(24),
    sealed_text  TEXT,
    expires_at   TIMESTAMP(3)
);
CREATE INDEX ix_moderation_event_moderator ON veyq_moderation_event (moderator_id);
CREATE INDEX ix_moderation_event_expires ON veyq_moderation_event (expires_at);
