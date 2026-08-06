-- Generalise scope identity + the personal scope (en.spaces.personal-scope, cr-0026). Until now the model
-- collapsed "scope == tenant" (membership keyed by tenant_id). A scope is now a first-class row (opaque id +
-- kind TENANT/GROUP/PERSONAL), and membership references the scope it grants (ADR 0007). Operator-blind: a
-- scope row holds NO key; the scope key lives only in the member's ciphertext wrapped_grant.
--
-- The V8 unique index (user_id, tenant_id) must go — a user now holds TENANT + PERSONAL in one tenant. There
-- is no DROP INDEX syntax common to H2 (default) and MariaDB, so membership is rebuilt via a fresh table +
-- RENAME (all in the H2 ∩ MariaDB common subset), which also lets scope_id be NOT NULL from the start.

CREATE TABLE veyq_scope (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(36) NOT NULL,
    kind       VARCHAR(16) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_scope_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE INDEX ix_scope_tenant ON veyq_scope (tenant_id);

-- Backfill the TENANT root scope (id = tenant_id) for every tenant that already has a membership.
INSERT INTO veyq_scope (id, tenant_id, kind, created_at)
SELECT DISTINCT tenant_id, tenant_id, 'TENANT', CURRENT_TIMESTAMP FROM veyq_membership;

-- Rebuild membership with scope_id (fresh constraint names so they don't collide with the old table that
-- still exists during the copy). Existing rows point at their tenant's TENANT scope (scope_id = tenant_id).
CREATE TABLE veyq_membership_new (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(36) NOT NULL,
    tenant_id     VARCHAR(36) NOT NULL,
    scope_id      VARCHAR(36) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    wrapped_grant TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT fk_msm_user   FOREIGN KEY (user_id)   REFERENCES veyq_user (id),
    CONSTRAINT fk_msm_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id),
    CONSTRAINT fk_msm_scope  FOREIGN KEY (scope_id)  REFERENCES veyq_scope (id)
);
INSERT INTO veyq_membership_new (id, user_id, tenant_id, scope_id, role, wrapped_grant, created_at)
SELECT id, user_id, tenant_id, tenant_id, role, wrapped_grant, created_at FROM veyq_membership;
DROP TABLE veyq_membership;
ALTER TABLE veyq_membership_new RENAME TO veyq_membership;

-- A user holds at most one membership per scope (was per tenant).
CREATE UNIQUE INDEX ux_membership_user_scope ON veyq_membership (user_id, scope_id);
