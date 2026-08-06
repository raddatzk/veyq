-- cr-0114 (ADR 0035): server terms of service — operator tenant terms + a member accept-gate.
--
-- Each tenant carries per-locale (DE+EN) markdown terms, serialized as a locale→markdown JSON map in an opaque
-- TEXT column, plus an int terms_version bumped ONLY on a normalized change (idempotent re-saves don't bump).
-- A member's acceptance lives in a dedicated table keyed logically by (user_id, tenant_id) — a surrogate id +
-- a UNIQUE (matching the Panache-friendly membership shape). Terms are the operator's PUBLIC operational
-- content; accepted_version is coarse operational metadata (like membership) — neither is E2EE content, so
-- the operator-blind boundary is unaffected.

ALTER TABLE veyq_tenant ADD COLUMN terms_markdown_by_locale TEXT;
ALTER TABLE veyq_tenant ADD COLUMN terms_version INT NOT NULL DEFAULT 0;

CREATE TABLE veyq_tenant_terms_acceptance (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id          VARCHAR(36) NOT NULL,
    tenant_id        VARCHAR(36) NOT NULL,
    accepted_version INT         NOT NULL,
    accepted_at      TIMESTAMP   NOT NULL,
    CONSTRAINT ux_terms_acceptance UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_terms_acceptance_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);
