-- The tenant level of the capability register (ADR 0088 decisions 2 and 12): one row per entry a tenant owner
-- switched off for their tenant, none for an entry that is on. capability_id is the catalog's dotted id
-- (contracts/platform/capabilities.json), so VARCHAR. Only an entry the catalog lets a tenant switch is ever written.
-- The effective state is the AND of the instance level (veyq_capability_switch) and this one along the path,
-- computed by the server, never stored. Removed with its tenant.
CREATE TABLE veyq_tenant_capability_switch (
    id            UUID        NOT NULL PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    capability_id VARCHAR(64) NOT NULL,
    CONSTRAINT ux_tenant_capability_switch UNIQUE (tenant_id, capability_id),
    CONSTRAINT fk_tenant_capability_switch_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);
