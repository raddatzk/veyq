-- Tenant creation, structural half (uc.admin.create-tenant Slice A, cr-0009). A Server Owner creates a
-- tenant (domain-scoped container, resolved by Host) and invites an owner by email. Everything here is
-- operational cleartext + a hashed bootstrap secret — no content key (the cold TENANT-scope birth that
-- activates a tenant is Slice B). Types kept to the H2 ∩ MariaDB common subset.

-- A tenant: a domain↔tenant mapping with an operational display name and the intended owner email.
-- status: PENDING until its owner accepts and cold-births the scope (Slice B), then ACTIVE.
CREATE TABLE veyq_tenant (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    domain      VARCHAR(253) NOT NULL,
    owner_email VARCHAR(254) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT ux_tenant_domain UNIQUE (domain)
);

-- The owner invite. Its own table (multiple rows per tenant, a history) mirroring the session
-- refresh-token model (ADR 0004): only the SHA-256 token HASH is stored, never the token. status:
-- ACTIVE → SUPERSEDED on resend/reassign, → CONSUMED on acceptance (Slice B), → EXPIRED past its TTL.
-- Transitions are soft; a scheduled purge removes terminal-state rows later.
CREATE TABLE veyq_tenant_invite (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL,
    owner_email VARCHAR(254) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_tenant_invite_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id)
);
CREATE UNIQUE INDEX ux_tenant_invite_token_hash ON veyq_tenant_invite (token_hash);
CREATE INDEX ix_tenant_invite_tenant ON veyq_tenant_invite (tenant_id);
