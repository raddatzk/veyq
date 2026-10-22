-- The terms gate compares a pair (ADR 0035 decision 6, revised 2026-10-21): the space's revision and the operator
-- section's revision. The section has one form on every instance, so its variant leaves the acceptance row, and the
-- UNIQUE narrows to the pair. The table is rebuilt rather than altered: H2 and MariaDB share no spelling for dropping a
-- UNIQUE constraint (as V202610181600 found). Where one member accepted both variants of the same pair, the earlier
-- row stays — its time is the evidence of when that pair was first accepted (ADR 0035 § 4); ties go to the lower id.
-- The user reference stays without a foreign key (V202610182100), the tenant's cascades, accepted_at is DATETIME(3)
-- (V202610192230).
CREATE TABLE veyq_tenant_terms_acceptance_prev AS
    SELECT id, user_id, tenant_id, accepted_revision, operator_revision, accepted_at FROM veyq_tenant_terms_acceptance;

DROP TABLE veyq_tenant_terms_acceptance;

CREATE TABLE veyq_tenant_terms_acceptance (
    id                UUID        NOT NULL PRIMARY KEY,
    user_id           UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    accepted_revision INT         NOT NULL,
    operator_revision INT         NOT NULL,
    accepted_at       DATETIME(3) NOT NULL,
    CONSTRAINT ux_terms_acceptance UNIQUE (user_id, tenant_id, accepted_revision, operator_revision),
    CONSTRAINT fk_terms_acceptance_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

INSERT INTO veyq_tenant_terms_acceptance (id, user_id, tenant_id, accepted_revision, operator_revision, accepted_at)
    SELECT p.id, p.user_id, p.tenant_id, p.accepted_revision, p.operator_revision, p.accepted_at
    FROM veyq_tenant_terms_acceptance_prev p
    WHERE NOT EXISTS (
        SELECT 1 FROM veyq_tenant_terms_acceptance_prev q
        WHERE q.user_id = p.user_id AND q.tenant_id = p.tenant_id
          AND q.accepted_revision = p.accepted_revision AND q.operator_revision = p.operator_revision
          AND (q.accepted_at < p.accepted_at OR (q.accepted_at = p.accepted_at AND q.id < p.id))
    );

DROP TABLE veyq_tenant_terms_acceptance_prev;
