-- The terms gate compares a triple (ADR 0035 decision 6, cr-operator-section-of-the-terms): the space's revision, the
-- operator section's revision and its variant (FULL or REDUCED, by the operating mode). A member's acceptance history
-- keeps one row per accepted triple, so the UNIQUE widens to all three. The table is rebuilt rather than altered: H2 and
-- MariaDB share no spelling for dropping a UNIQUE constraint. The rows already there were accepted before the section
-- existed: they keep their revision and time, with operator_revision 0 and variant NULL, and match no current triple.
CREATE TABLE veyq_tenant_terms_acceptance_prev AS
    SELECT id, user_id, tenant_id, accepted_revision, accepted_at FROM veyq_tenant_terms_acceptance;

DROP TABLE veyq_tenant_terms_acceptance;

CREATE TABLE veyq_tenant_terms_acceptance (
    id                UUID        NOT NULL PRIMARY KEY,
    user_id           UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    accepted_revision INT         NOT NULL,
    operator_revision INT         NOT NULL,
    variant           VARCHAR(16),
    accepted_at       TIMESTAMP   NOT NULL,
    CONSTRAINT ux_terms_acceptance UNIQUE (user_id, tenant_id, accepted_revision, operator_revision, variant),
    CONSTRAINT fk_terms_acceptance_user FOREIGN KEY (user_id) REFERENCES veyq_user (id),
    CONSTRAINT fk_terms_acceptance_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);

INSERT INTO veyq_tenant_terms_acceptance (id, user_id, tenant_id, accepted_revision, operator_revision, variant, accepted_at)
    SELECT id, user_id, tenant_id, accepted_revision, 0, NULL, accepted_at FROM veyq_tenant_terms_acceptance_prev;

DROP TABLE veyq_tenant_terms_acceptance_prev;
