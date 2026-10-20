-- Measures and proposals (ADR 0093 §§ 4-6, ADR 0099 § 6; uc.moderation.take-a-measure, uc.moderation.review-a-case).
--
-- veyq_moderation_block: one measure — a row that deletes nothing and holds until it is lifted (lifted_at set; the row
-- stays as the record). kind is SHARE (a link), CONTENT_NODE / CONTENT_ITEM (a node and everything below it, or an
-- application's item), GUEST_IDENTITY (a guest uploader; share_id is the link it came through, blocked with it),
-- MEMBER_SIGN_IN (an account, the Server Owner's), or REPORTER_ACCOUNT / REPORTER_LINK (a reporter barred, ADR 0093 § 5).
-- target_id is the blocked id; scope_id the space of a content block (every listing asks per space); case_id the case it
-- answers (NULL for a sign-in block the Server Owner took on no case). active_key is "KIND:target" while the measure is
-- in force and NULL once lifted: unique, so one measure per target and kind is held by the database. No foreign key into
-- a space, a node, a share or an account: a measure hangs on ids and outlives what it names (ADR 0093 § 6).
CREATE TABLE veyq_moderation_block (
    id         UUID         NOT NULL PRIMARY KEY,
    kind       VARCHAR(24)  NOT NULL,
    target_id  VARCHAR(64)  NOT NULL,
    share_id   VARCHAR(64),
    scope_id   UUID,
    case_id    UUID,
    taken_by   UUID         NOT NULL,
    taken_at   TIMESTAMP(3) NOT NULL,
    lifted_at  TIMESTAMP(3),
    lifted_by  UUID,
    active_key VARCHAR(96)
);
CREATE UNIQUE INDEX ux_moderation_block_active ON veyq_moderation_block (active_key);
CREATE INDEX ix_moderation_block_target ON veyq_moderation_block (target_id);
CREATE INDEX ix_moderation_block_share ON veyq_moderation_block (share_id);
CREATE INDEX ix_moderation_block_scope ON veyq_moderation_block (scope_id);
CREATE INDEX ix_moderation_block_case ON veyq_moderation_block (case_id);

-- veyq_moderation_proposal: a Server Owner's step a moderator proposed for a case (ADR 0099 § 6, decided with the owner
-- 2) — MEMBER_SIGN_IN (target: the member's account) or TENANT_OFFLINE (target: the tenant). outcome OPEN until the
-- Server Owner takes the step (TAKEN, set by the block or the suspension itself) or declines it (DECLINED). open_key is
-- "caseId:STEP:target" while OPEN: one open proposal per case, step and target.
CREATE TABLE veyq_moderation_proposal (
    id          UUID         NOT NULL PRIMARY KEY,
    case_id     UUID         NOT NULL,
    step        VARCHAR(24)  NOT NULL,
    target_id   UUID         NOT NULL,
    proposed_by UUID         NOT NULL,
    proposed_at TIMESTAMP(3) NOT NULL,
    outcome     VARCHAR(16)  NOT NULL,
    settled_at  TIMESTAMP(3),
    settled_by  UUID,
    open_key    VARCHAR(160),
    CONSTRAINT fk_moderation_proposal_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_proposal_open ON veyq_moderation_proposal (open_key);
CREATE INDEX ix_moderation_proposal_target ON veyq_moderation_proposal (step, target_id);
CREATE INDEX ix_moderation_proposal_case ON veyq_moderation_proposal (case_id);

-- A case's decision and close: decided_at when its first measure was taken, closed_at when it was dismissed or its last
-- hold ended after a decision (the date its events are kept from, ADR 0093 § 11).
ALTER TABLE veyq_moderation_case ADD COLUMN decided_at TIMESTAMP(3);
ALTER TABLE veyq_moderation_case ADD COLUMN closed_at TIMESTAMP(3);

-- A case event's structured fields beyond the reason category: the id a measure or a proposal named, and an
-- extension's new end (its own expires_at stays the event's retention date).
ALTER TABLE veyq_moderation_event ADD COLUMN target_id VARCHAR(64);
ALTER TABLE veyq_moderation_event ADD COLUMN hold_until TIMESTAMP(3);
