-- How long moderation keeps what it keeps (ADR 0093 §§ 4, 11, 13; en.moderation.hold, en.moderation.log).
--
-- veyq_hold.ceiling_until: the latest end a measure or a statement of reasons may move a hold to — set on a hold of a
-- child-sexual-abuse case, 90 days after the report that took it or that made the case one of that category (§ 184b(5)
-- StGB: keeping it serves only the report to an authority). NULL on every other hold. Only a logged extension goes past
-- it, and an authority's order's end (floor_until) always wins.
ALTER TABLE veyq_hold ADD COLUMN ceiling_until TIMESTAMP(3);

-- veyq_moderation_report.sealed_contact: the reporter's contact, sealed on its own to the report's epoch
-- (moderation-report-contact [epoch]) so that it can go alone, six months after the decision on the report was told
-- (ADR 0093 § 11) — the rest of the report stays with its case. NULL when the reporter left none, and once it went.
ALTER TABLE veyq_moderation_report ADD COLUMN sealed_contact TEXT;

-- A case's records go together with its events (ModerationRecordsPurge), which it finds by case.
CREATE INDEX ix_moderation_event_case ON veyq_moderation_event (case_id);
