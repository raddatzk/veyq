-- An authority's order and the deferral of a statement (ADR 0093 §§ 4, 7, 12; uc.moderation.authority-order).
--
-- veyq_authority_order: one row per order entered, by a moderator or the Server Owner (entered_by). kind REMOVAL,
-- PRESERVATION or INFORMATION; share_id the share it names (no foreign key: an order naming a share the server does not
-- know is recorded all the same, with no case); received_at when it reached the contact point, as entered — the hour's
-- clock is the operator's, and this row is its proof; entered_at when the server recorded it. epoch + sealed_reference
-- the authority's reference sealed by the entering client to the moderation key (moderation-order [epoch, id]); the id is
-- the client's, so a retry is the same order. case_id the case it opened (NULL when its share was not found); blocked_at
-- when the share and its content were both blocked (a REMOVAL); hold_until the end the order sets for its hold.
CREATE TABLE veyq_authority_order (
    id               UUID         NOT NULL PRIMARY KEY,
    kind             VARCHAR(16)  NOT NULL,
    share_id         VARCHAR(64)  NOT NULL,
    received_at      TIMESTAMP(3) NOT NULL,
    entered_at       TIMESTAMP(3) NOT NULL,
    entered_by       UUID         NOT NULL,
    epoch            INT          NOT NULL,
    sealed_reference TEXT         NOT NULL,
    case_id          UUID,
    blocked_at       TIMESTAMP(3),
    hold_until       TIMESTAMP(3),
    CONSTRAINT fk_authority_order_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE INDEX ix_authority_order_case ON veyq_authority_order (case_id);
CREATE INDEX ix_authority_order_entered ON veyq_authority_order (entered_at);

-- What opened a case: a report (REPORT) or an authority's order (ORDER) — an order's case has no report, is decided from
-- the start, and its open_key is "ORDER:<orderId>", so it never takes a report's place as the item's one open case.
ALTER TABLE veyq_moderation_case ADD COLUMN origin VARCHAR(8) NOT NULL DEFAULT 'REPORT';

-- A hold's floor: the end an authority's order sets. No extension, lift or decision ends the hold before it (ADR 0093
-- § 4); NULL for a hold no order set.
ALTER TABLE veyq_hold ADD COLUMN floor_until TIMESTAMP(3);

-- A statement held back by an authority's deferral (TCO Art. 11(3), ADR 0093 § 7): deferred_until is when it goes out
-- while sent_at is NULL; the hourly cleanup sends it then and clears it.
ALTER TABLE veyq_moderation_statement ADD COLUMN deferred_until TIMESTAMP(3);

-- veyq_moderation_deferral: one authority's request to defer a statement — the first, and at most one renewal (unique on
-- statement_id + renewal). The reason is sealed to the moderation key (epoch + sealed_reason, moderation-note [epoch,
-- caseId]), or it is the reference of the order it came with (order_id, sealed_reason NULL); entered_by who entered it.
-- Its end (at most six weeks after it was entered) is the statement's deferred_until while it holds, and when it was
-- entered and until when is the log's STATEMENT_DEFERRED event.
CREATE TABLE veyq_moderation_deferral (
    id            UUID         NOT NULL PRIMARY KEY,
    statement_id  UUID         NOT NULL,
    case_id       UUID         NOT NULL,
    order_id      UUID,
    epoch         INT,
    sealed_reason TEXT,
    entered_by    UUID         NOT NULL,
    renewal       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_moderation_deferral_statement FOREIGN KEY (statement_id) REFERENCES veyq_moderation_statement (id),
    CONSTRAINT fk_moderation_deferral_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id),
    CONSTRAINT fk_moderation_deferral_order FOREIGN KEY (order_id) REFERENCES veyq_authority_order (id)
);
CREATE UNIQUE INDEX ux_moderation_deferral ON veyq_moderation_deferral (statement_id, renewal);
CREATE INDEX ix_moderation_deferral_case ON veyq_moderation_deferral (case_id);
CREATE INDEX ix_moderation_statement_deferred ON veyq_moderation_statement (deferred_until);
