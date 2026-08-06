-- Per-tenant member identity (cr-0083, ADR 0023). Replace the global, free-form veyq_user.display_name with a
-- per-(user, tenant) profile: a tenant-unique `username` (plaintext handle) + `sealed_name` (first+last sealed
-- under K_tenant, ciphertext — nullable: a grant-less member seals it only once they hold K_tenant). Instance-
-- wide uniqueness stays with email alone. DROP COLUMN + CREATE TABLE are in the H2 ∩ MariaDB common subset
-- (DROP COLUMN precedent: V6).
ALTER TABLE veyq_user DROP COLUMN display_name;

CREATE TABLE veyq_tenant_profile (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(36) NOT NULL,
    username    VARCHAR(32) NOT NULL,
    sealed_name TEXT,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_tp_user   FOREIGN KEY (user_id)   REFERENCES veyq_user (id),
    CONSTRAINT fk_tp_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
-- One profile per person per tenant; the handle is unique within a tenant.
CREATE UNIQUE INDEX ux_tp_user_tenant ON veyq_tenant_profile (user_id, tenant_id);
CREATE UNIQUE INDEX ux_tp_tenant_username ON veyq_tenant_profile (tenant_id, username);
