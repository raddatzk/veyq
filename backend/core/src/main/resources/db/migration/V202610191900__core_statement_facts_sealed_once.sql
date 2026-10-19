-- A statement's facts are sealed once (ADR 0093 § 7, owner 2026-10-19): veyq_moderation_statement.sealed_member_facts
-- holds the facts sealed under the statement's fresh K_facts (moderation-statement-facts [caseId, statementId]), NULL
-- without facts; each member's recipient row holds K_facts wrapped to that member's identity (wrapped_facts_key,
-- moderation-facts-key [caseId, statementId, accountId]) in place of a copy of the facts. sealed_facts on the statement
-- stays the log's copy, sealed to the moderation key.
--
-- The per-member copies are dropped, not converted: only the moderator's browser could re-seal them, and no release
-- carried them. A statement written before this migration keeps its log copy; its members no longer read its facts.
ALTER TABLE veyq_moderation_statement ADD COLUMN sealed_member_facts TEXT;

ALTER TABLE veyq_moderation_statement_recipient DROP COLUMN sealed_facts;

ALTER TABLE veyq_moderation_statement_recipient ADD COLUMN wrapped_facts_key TEXT;
