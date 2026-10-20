-- Reports and cases (ADR 0093 §§ 1-2, 5; uc.moderation.report). No foreign key into a space, a node, a share or an
-- account: a report outlives what it reports (a delete, a revoke, the space's erasure), and its case with it.
--
-- veyq_moderation_report: one row per report. In the clear the epoch its sealed part is sealed to, when it was received,
-- its category, and who sent it — the reporter's account (a member: the cap on open reports counts it) or the link it
-- came through (a guest). sealed_report is K_report, the reason, the contact and the context sealed to that epoch's
-- public half (sealTo purpose moderation-report [epoch]) — opaque to the server.
CREATE TABLE veyq_moderation_report (
    id               UUID         NOT NULL PRIMARY KEY,
    epoch            INT          NOT NULL,
    received_at      TIMESTAMP(3) NOT NULL,
    category         VARCHAR(24)  NOT NULL,
    sealed_report    TEXT         NOT NULL,
    reporter_account UUID,
    reporter_share   VARCHAR(64)
);
CREATE INDEX ix_moderation_report_account ON veyq_moderation_report (reporter_account);

-- veyq_moderation_case: one per reported item (NODE, ITEM or LINK and its id; a LINK's id is its share id) in the space
-- it lies in. category is the most urgent of its reports. open_key is the item ("KIND:id") while the case is OPEN and
-- NULL once it is closed: unique, so the database holds "one open case per item" and a closed case never blocks a new one.
CREATE TABLE veyq_moderation_case (
    id        UUID        NOT NULL PRIMARY KEY,
    item_kind VARCHAR(8)  NOT NULL,
    item_id   VARCHAR(64) NOT NULL,
    scope_id  UUID        NOT NULL,
    category  VARCHAR(24) NOT NULL,
    state     VARCHAR(16) NOT NULL,
    open_key  VARCHAR(80)
);
CREATE UNIQUE INDEX ux_moderation_case_open ON veyq_moderation_case (open_key);

-- veyq_report_share: what one report hands over for one case's item — its keys sealed under that report's K_report
-- (seal purpose report-share [itemKind, itemId] or report-link [shareId]). In no listing, revocable by nobody, served
-- only to a moderator with the key proof (the review's), never back to the client that minted it.
CREATE TABLE veyq_report_share (
    id          UUID NOT NULL PRIMARY KEY,
    report_id   UUID NOT NULL,
    case_id     UUID NOT NULL,
    sealed_keys TEXT NOT NULL,
    CONSTRAINT fk_report_share_report FOREIGN KEY (report_id) REFERENCES veyq_moderation_report (id),
    CONSTRAINT fk_report_share_case FOREIGN KEY (case_id) REFERENCES veyq_moderation_case (id)
);
CREATE UNIQUE INDEX ux_report_share ON veyq_report_share (report_id, case_id);
CREATE INDEX ix_report_share_case ON veyq_report_share (case_id);
