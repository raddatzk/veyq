-- The operator-blind background-task pull queue (ADR 0021, cr-0076). A job is a scope-bound, opaque envelope:
-- the server routes by cleartext scope_id + an OPAQUE capability_tag (from the public registry in
-- contracts/tasks/tags/), stores a sealed body/result it never unseals, and coordinates an at-least-once
-- lease. Workers are remote key-holders (interactive clients or the later agent) that pull over HTTP, so the
-- claim is a thin atomic DB compare-and-set — no server-side execution framework fits (see cr-0076 /
-- docs/crosscutting/background-tasks.md, the buy-vs-build resolution). Types kept to the H2 ∩ MariaDB subset.
--
-- status: PENDING (claimable) | LEASED (held, lease_expires_at in the future) | DONE | FAILED (dead-lettered).
-- A LEASED job whose lease has expired is lazily reclaimable (crash → another worker re-leases). attempts is
-- incremented on each (re)claim; reaching max_attempts dead-letters the job. For server-structural jobs like
-- grant-delivery the body is not sealed (nothing to hide — the cleartext envelope + resolved target suffice),
-- so sealed_body is NULL; resource_ref carries the target membership id.
CREATE TABLE veyq_job (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id         VARCHAR(36) NOT NULL,
    capability_tag   VARCHAR(64) NOT NULL,
    resource_ref     VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    lease_holder     VARCHAR(36),
    lease_expires_at TIMESTAMP,
    attempts         INT NOT NULL,
    max_attempts     INT NOT NULL,
    sealed_body      TEXT,
    sealed_result    TEXT,
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT fk_job_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

-- The claim query filters by (scope_id, capability_tag, status); index it for the pull path.
CREATE INDEX ix_job_claimable ON veyq_job (scope_id, capability_tag, status);
