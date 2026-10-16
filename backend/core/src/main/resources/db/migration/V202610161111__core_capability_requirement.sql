-- Requirements on capability entries (ADR 0088 decision 8): a value per requirement and level, none for one that is
-- not set. `requirement` is the catalog's requirement name (contracts/platform/capabilities.json), unique in the catalog,
-- so VARCHAR; `setting` is 1 for a set flag, else a number of days (1 to 3650). The value in effect for a tenant is the
-- strictest of both levels, computed by the server, never stored. The tenant's rows are removed with its tenant.
CREATE TABLE veyq_capability_requirement (
    requirement VARCHAR(64) NOT NULL PRIMARY KEY,
    setting     INT         NOT NULL
);

CREATE TABLE veyq_tenant_capability_requirement (
    id          UUID        NOT NULL PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    requirement VARCHAR(64) NOT NULL,
    setting     INT         NOT NULL,
    CONSTRAINT ux_tenant_capability_requirement UNIQUE (tenant_id, requirement),
    CONSTRAINT fk_tenant_capability_requirement_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);
