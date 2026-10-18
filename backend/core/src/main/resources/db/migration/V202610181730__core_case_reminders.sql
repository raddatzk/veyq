-- An undecided case's reminders (ADR 0093 § 11, owner 2026-10-18; DSA Art. 16(6) "timely"): no case closes itself, so
-- its moderators are reminded at 30, 75 and 90 days after its first report (CaseReminders). reminders_sent counts the
-- reminders that went out, 0 to 3, so each one goes once; it goes with the case row (ModerationRecordsPurge).
ALTER TABLE veyq_moderation_case ADD COLUMN reminders_sent INT NOT NULL DEFAULT 0;
