-- A member invite may be bound to nobody (ADR 0129 decision 2): a link invite, which its owner hands over, carries no
-- address. owner_email is null exactly for such an invite; a mailed member invite and every owner invite keep theirs.
-- Readers branch on the null: no supersede-on-mint, the tenant's rate bucket only, no address match at accept, the
-- typed address for a new account, and no address confirmation by the redemption (ADR 0129 decision 4).
ALTER TABLE veyq_tenant_invite MODIFY COLUMN owner_email VARCHAR(254) NULL;
