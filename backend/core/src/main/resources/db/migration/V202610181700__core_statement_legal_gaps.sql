-- The statement of reasons' legal gaps (ADR 0093 § 7; DSA Art. 17(1)(c), (d), 17(3)(d), (e)).
--
-- veyq_moderation_statement.provision: the provision of the law or the clause of the terms the ground relies on, a short
-- reference in the moderator's or the Server Owner's own words, in the clear beside the ground — never the facts, which
-- stay sealed. Required in the full form, NULL where none was named.
ALTER TABLE veyq_moderation_statement ADD COLUMN provision VARCHAR(200);

-- case_id is NULL for a Server Owner's step over content that answers no case (a sign-in block or a tenant taken
-- offline on their own finding): its statement hangs on the step's measure row alone, and goes with it
-- (ModerationRecordsPurge). basis widens for OWN_FINDING.
ALTER TABLE veyq_moderation_statement MODIFY case_id UUID NULL;
ALTER TABLE veyq_moderation_statement MODIFY basis VARCHAR(16) NOT NULL;

-- A case-less step's records are found by their measure (ModerationRecordsPurge).
CREATE INDEX ix_moderation_block_case_lifted ON veyq_moderation_block (case_id, lifted_at);
