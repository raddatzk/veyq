-- The account erasure and the deletion of an active tenant (ADR 0101; uc.account.erase-account, uc.admin.delete-tenant).
--
-- veyq_account_erasure: one row per account whose erasure is pending — requested_at and requested_by (SELF or
-- SERVER_OWNER), due_at the purge's date (the request plus the scope trash's retention), mailed_at when the instance mail
-- telling the address went out (NULL until it did; the hourly purge retries). A row of its own, not columns on
-- veyq_user, so the purge's work list is one query. It goes with the account (AccountPurge deletes it first); a stop
-- deletes it.
CREATE TABLE veyq_account_erasure (
    account_id   UUID         NOT NULL PRIMARY KEY,
    requested_at TIMESTAMP(3) NOT NULL,
    requested_by VARCHAR(16)  NOT NULL,
    due_at       TIMESTAMP(3) NOT NULL,
    mailed_at    TIMESTAMP(3),
    CONSTRAINT fk_account_erasure_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
CREATE INDEX ix_account_erasure_due ON veyq_account_erasure (due_at);

-- When TenantPurge erases a tenant in PENDING_DELETION; NULL for every other status.
ALTER TABLE veyq_tenant ADD COLUMN deletion_due_at TIMESTAMP(3);

-- A member's terms acceptance rows outlive their account while a moderation case names it (ADR 0101 decision 6, ADR
-- 0035 decision 4): kept under the opaque id to that case's log retention date, so the reference to the account can no
-- longer be a foreign key. AccountPurge deletes the rows no case keeps; its sweep deletes the rest once none does.
ALTER TABLE veyq_tenant_terms_acceptance DROP FOREIGN KEY fk_terms_acceptance_user;
