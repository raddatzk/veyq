-- The new-case notice of a case (ADR 0093 § 2, ADR 0099 § 7; owner decision Q7 of cr-report-and-hold): notice_due is
-- URGENT (the first three categories: mailed at once) or BUNDLE (the others: in at most one mail a day) while the "new
-- case" mail is owed, and NULL once it went out; noticed_at and notice_kind say when and in which kind of mail. The day
-- of a bundle is counted from the last bundle's noticed_at.
ALTER TABLE veyq_moderation_case ADD COLUMN notice_due VARCHAR(8);
ALTER TABLE veyq_moderation_case ADD COLUMN noticed_at TIMESTAMP(3);
ALTER TABLE veyq_moderation_case ADD COLUMN notice_kind VARCHAR(8);
CREATE INDEX ix_moderation_case_notice ON veyq_moderation_case (notice_due);
