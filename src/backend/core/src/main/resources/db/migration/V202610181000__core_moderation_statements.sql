-- Statements of reasons and objections (ADR 0093 §§ 4, 7, 14, ADR 0099 § 5; uc.moderation.object-to-a-measure).
--
-- veyq_moderation_statement: one per moderator's measure, written with it. ground is the clear ground (a report
-- category's value); basis REPORT or ORDER; epoch + sealed_facts the facts sealed to the moderation key (moderation-note
-- [epoch, caseId]), NULL without facts. sent_at is when it went out — NULL where nobody could be told (no member among its
-- recipients): the measure's hold then counts from the decision (ADR 0093 § 4). The form (full or reduced) is no column:
-- it is read from the operating mode when the statement is rendered.
CREATE TABLE veyq_moderation_statement (
    id           UUID         NOT NULL PRIMARY KEY,
    measure_id   UUID         NOT NULL,
    case_id      UUID         NOT NULL,
    ground       VARCHAR(24)  NOT NULL,
    basis        VARCHAR(8)   NOT NULL,
    epoch        INT,
    sealed_facts TEXT,
    sent_at      TIMESTAMP(3),
    CONSTRAINT fk_moderation_statement_measure FOREIGN KEY (measure_id) REFERENCES veyq_moderation_block (id),
    CONSTRAINT fk_moderation_statement_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_statement_measure ON veyq_moderation_statement (measure_id);
CREATE INDEX ix_moderation_statement_case ON veyq_moderation_statement (case_id);

-- veyq_moderation_statement_recipient: who a statement names — a member's account (ACCOUNT) or a guest identity (GUEST,
-- told nothing in veyq) — and why (roles, comma-joined: SHARE_CREATOR, UPLOADER, SCOPE_OWNER). sealed_facts are the facts
-- sealed to that member's identity (moderation-facts [caseId, accountId]); mailed_at when the instance mail telling them
-- went out, NULL until it did (the hourly cleanup retries for seven days).
CREATE TABLE veyq_moderation_statement_recipient (
    id             UUID         NOT NULL PRIMARY KEY,
    statement_id   UUID         NOT NULL,
    recipient_kind VARCHAR(8)   NOT NULL,
    recipient_id   VARCHAR(64)  NOT NULL,
    roles          VARCHAR(64)  NOT NULL,
    sealed_facts   TEXT,
    mailed_at      TIMESTAMP(3),
    CONSTRAINT fk_statement_recipient_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id)
);
CREATE UNIQUE INDEX ux_statement_recipient ON veyq_moderation_statement_recipient (statement_id, recipient_id);
CREATE INDEX ix_statement_recipient_id ON veyq_moderation_statement_recipient (recipient_id);

-- veyq_moderation_objection: the one objection to a statement's measure (unique on statement_id: one per measure, ADR
-- 0093 § 7), sealed to the moderation key (moderation-objection [epoch, statementId]). decided_at/decided_by/outcome
-- (UPHELD or LIFTED) once a moderator decided it; own_review when the account that took the measure decided it, there
-- being no other accepted moderator holding the key (ADR 0099 § 5).
CREATE TABLE veyq_moderation_objection (
    id               UUID         NOT NULL PRIMARY KEY,
    statement_id     UUID         NOT NULL,
    case_id          UUID         NOT NULL,
    objector_account UUID         NOT NULL,
    epoch            INT          NOT NULL,
    sealed_objection TEXT         NOT NULL,
    received_at      TIMESTAMP(3) NOT NULL,
    decided_at       TIMESTAMP(3),
    decided_by       UUID,
    outcome          VARCHAR(8),
    own_review       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_moderation_objection_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id),
    CONSTRAINT fk_moderation_objection_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_moderation_objection_statement ON veyq_moderation_objection (statement_id);
CREATE INDEX ix_moderation_objection_case ON veyq_moderation_objection (case_id);
