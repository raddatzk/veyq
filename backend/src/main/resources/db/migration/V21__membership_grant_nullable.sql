-- Grant-less ("hollow") membership — role without a grant (ADR 0007; the member-add delivery slice, cr-0076).
-- A *pending* member has a membership row but NO wrapped_grant until an online key-holder delivers it via a
-- grant-delivery job; they land in the Personal fallback ("awaiting access") meanwhile. So wrapped_grant must
-- be NULLable. There is no ALTER-COLUMN-nullability syntax common to H2 (test default) and MariaDB, so the
-- table is rebuilt via a fresh table + RENAME — the same portable pattern V9 used to drop an index. Nothing
-- FK-references veyq_membership, so the drop is safe.
CREATE TABLE veyq_membership_v21 (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(36) NOT NULL,
    tenant_id     VARCHAR(36) NOT NULL,
    scope_id      VARCHAR(36) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    wrapped_grant TEXT,
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT fk_m21_user   FOREIGN KEY (user_id)   REFERENCES veyq_user (id),
    CONSTRAINT fk_m21_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id),
    CONSTRAINT fk_m21_scope  FOREIGN KEY (scope_id)  REFERENCES veyq_scope (id)
);
INSERT INTO veyq_membership_v21 (id, user_id, tenant_id, scope_id, role, wrapped_grant, created_at)
SELECT id, user_id, tenant_id, scope_id, role, wrapped_grant, created_at FROM veyq_membership;
DROP TABLE veyq_membership;
ALTER TABLE veyq_membership_v21 RENAME TO veyq_membership;

-- A user holds at most one membership per scope (unchanged from V9).
CREATE UNIQUE INDEX ux_membership_user_scope ON veyq_membership (user_id, scope_id);
