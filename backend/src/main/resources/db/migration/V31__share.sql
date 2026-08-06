-- Public viewer shares (uc.sharing.share-public-viewer, cr-0100, ADR 0029). A share turns one personal-scope
-- Memory into a read-only, anonymous, revocable link /s/{shareId}#k=<K_share>. The share adds an independent
-- second wrap of the Memory's content key — wrap = symWrap(K_share, K_mem) (ADR 0012) — whose secret K_share
-- rides the URL fragment and never reaches the server. The server is operator-blind: it holds only the opaque
-- 256-bit share id, the wrap, the plaintext role, the K_share-sealed name, and the shared node id — never
-- K_share, K_mem, a name, the viewer, or which media a viewer opens (blobs go direct-from-store). Soft (L1)
-- revoke: enabled=false / a passed expires_at makes the guest resolve + blob GET 404. Types kept to the
-- H2 ∩ MariaDB common subset (TEXT for opaque base64, VARCHAR for short ids, BOOLEAN, TIMESTAMP).
CREATE TABLE veyq_share (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,  -- 256-bit random base64url share id (43 chars); half the capability
    owner_account  VARCHAR(36)  NOT NULL,              -- the veyq user who created the share (list/revoke authz)
    scope_id       VARCHAR(36)  NOT NULL,              -- the shared node's scope (personal only, v1) — for the subtree gate
    node_id        VARCHAR(36)  NOT NULL,              -- the shared Memory (subtree root)
    role           VARCHAR(16)  NOT NULL,              -- VIEWER (v1); the plaintext capability
    wrap           TEXT         NOT NULL,              -- symWrap(K_share, K_mem), opaque base64
    sealed_name    TEXT         NOT NULL,              -- the Memory name re-sealed under K_share, opaque base64
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE, -- false once revoked (soft L1) — the guest paths then 404
    expires_at     TIMESTAMP,                          -- optional expiry; a passed instant 404s the guest paths
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT fk_share_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

CREATE INDEX ix_share_owner ON veyq_share (owner_account);
CREATE INDEX ix_share_node ON veyq_share (node_id);
