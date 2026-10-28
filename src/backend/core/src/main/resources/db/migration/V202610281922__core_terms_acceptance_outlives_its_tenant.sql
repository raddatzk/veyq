-- A member's terms acceptance rows outlive their tenant's erasure while a moderation case names the member, exactly as
-- they outlive the account's (ADR 0101 decisions 6 and 10, ADR 0035 decision 4; inbox #593): kept under the opaque ids
-- to that case's log retention date, so the reference to the tenant can no longer cascade. TenantPurge deletes the rows
-- no case keeps; AccountPurge's sweep of kept rows deletes the rest once none does.
ALTER TABLE veyq_tenant_terms_acceptance DROP FOREIGN KEY fk_terms_acceptance_tenant;
