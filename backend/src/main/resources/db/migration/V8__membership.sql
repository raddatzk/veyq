-- Tenant activation (uc.admin.create-tenant Slice B, cr-0010). Membership is the (user, scope, role, grant)
-- relation (ADR 0007): the first row is the owner's, born when they accept the invite and cold-birth the
-- TENANT scope. The TENANT root scope is keyed by the tenant id for now. `wrapped_grant` is the scope root
-- key sealed to the member's identity public key — opaque ciphertext the server stores but never opens
-- (operator-blind). Types kept to the H2 ∩ MariaDB common subset.
CREATE TABLE veyq_membership (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(36) NOT NULL,
    tenant_id     VARCHAR(36) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    wrapped_grant TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES veyq_user (id),
    CONSTRAINT fk_membership_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);

-- One membership per (user, tenant): a user holds at most one role + grant in a given tenant.
CREATE UNIQUE INDEX ux_membership_user_tenant ON veyq_membership (user_id, tenant_id);
