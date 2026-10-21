-- No operating mode (ADR 0093 § 14, revised 2026-10-21): every instance serves one form of each fixed text, nothing
-- the Server Owner sets is required and nothing warns, so the mode the Server Owner chose at setup is no longer stored.
ALTER TABLE veyq_operator_details DROP COLUMN operating_mode;
