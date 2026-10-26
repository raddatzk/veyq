-- An application left switched off in a tenant (uc.platform.erase-switched-off-application, ADR 0079 decision 8,
-- ADR 0088 decision 5): one row per tenant and application while the application is off for the tenant at either level,
-- and while a due erasure is still running. It is ApplicationErasurePurge's work list, the day the banner names and the
-- claims of the two mails. `application` is the register id (the application's one-segment root entry), never a table
-- name. `switch_level` is the level that made the row (TENANT or INSTANCE), for the notice's words. A row that exists
-- keeps its `off_since` and `due_at`, so a day members were told never moves. Erased with its tenant.
CREATE TABLE veyq_application_erasure (
    tenant_id          UUID        NOT NULL,
    application        VARCHAR(64) NOT NULL,
    switch_level       VARCHAR(16) NOT NULL,
    off_since          DATETIME(3) NOT NULL,
    due_at             DATETIME(3) NOT NULL,
    notice_mailed_at   DATETIME(3) NULL,
    reminder_mailed_at DATETIME(3) NULL,
    CONSTRAINT pk_application_erasure PRIMARY KEY (tenant_id, application),
    CONSTRAINT fk_application_erasure_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

CREATE INDEX ix_application_erasure_due ON veyq_application_erasure (due_at);
