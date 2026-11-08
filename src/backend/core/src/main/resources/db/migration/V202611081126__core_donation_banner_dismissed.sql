-- Whether the account holder dismissed veyq's donation banner (uc.platform.support-the-project, ADR 0122):
-- account-global, like require_group_add_consent beside it. Low-sensitivity server-visible operational metadata,
-- never content; served only to the account holder. An existing account starts at FALSE: it sees the banner once.
ALTER TABLE veyq_user ADD COLUMN donation_banner_dismissed BOOLEAN NOT NULL DEFAULT FALSE;
