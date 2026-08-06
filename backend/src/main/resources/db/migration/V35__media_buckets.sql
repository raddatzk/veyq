-- cr-0113 / en.memory.buckets / ADR 0034: per-uploader "buckets" within a Memory node — the attribution axis
-- (who contributed a medium), orthogonal to Chapters. A bucket is keyed by an opaque owner_key
-- ("user:{userId}" | "share:{shareIdentityId}") — operator-visible coarse metadata, never a cleartext name.
-- A lazy virtual PRIMARY bucket per uploader (parent_bucket_id NULL) + optional one-level sub-buckets; names are
-- sealed under K_mem (opaque base64), so the operator sees the structure but never a name. Operator-blind
-- throughout.
--
-- Primary-uniqueness (node_id, owner_key) is NOT enforced here, and ensurePrimaryBucket's find-or-create does
-- not enforce it either — it is a read-then-write, so two concurrent resolves each create a primary. The wanted
-- UNIQUE ... WHERE is_primary is a partial index the H2 ∩ MariaDB subset lacks; the portable form (a marker
-- column set only when is_primary, else NULL, + a plain UNIQUE) is what veyq_node already uses for slugs
-- (ux_node_parent_slug, V10). Booked as debt — see ADR 0034 §4 + architecture §11; a later migration adds the
-- index and dedups first.
CREATE TABLE veyq_bucket (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id         VARCHAR(36) NOT NULL,
    node_id          VARCHAR(36) NOT NULL,
    owner_key        VARCHAR(64) NOT NULL,          -- "user:{userId}" | "share:{shareIdentityId}"
    parent_bucket_id VARCHAR(36),                   -- NULL = a primary; else the parent primary (one level only)
    sealed_name      TEXT,                          -- sealed under K_mem (opaque base64); NULL for a member primary
    is_primary       BOOLEAN     NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT fk_bucket_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id) ON DELETE CASCADE
);
CREATE INDEX ix_bucket_node_owner ON veyq_bucket (node_id, owner_key);
CREATE INDEX ix_bucket_parent ON veyq_bucket (parent_bucket_id);

-- The media→bucket link (NULL = the uploader's primary, resolved lazily) + the denormalized attribution
-- (created_by = the owner_key, copied on attach so grouping/move-authz never joins the bucket row).
ALTER TABLE veyq_media ADD COLUMN bucket_id VARCHAR(36);
ALTER TABLE veyq_media ADD COLUMN created_by VARCHAR(64);
ALTER TABLE veyq_media ADD CONSTRAINT fk_media_bucket FOREIGN KEY (bucket_id) REFERENCES veyq_bucket (id) ON DELETE SET NULL;
CREATE INDEX ix_media_bucket ON veyq_media (bucket_id);
