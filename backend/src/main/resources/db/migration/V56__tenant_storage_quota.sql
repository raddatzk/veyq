-- The tenant's storage quota (ADR 0073 §1): the most the tenant may hold in the instance's store, set by the Server
-- Owner on the admin channel. NULL means no quota, which is the default — on a self-hosted instance a ceiling nobody
-- chose is a bug. It lives on the tenant row for the same reason as the per-link limit (V55): every upload start into
-- the tenant locks that row, so the quota is read under the lock the reservation is taken under (ADR 0073 §5).
ALTER TABLE veyq_tenant ADD COLUMN storage_quota_bytes BIGINT NULL;
