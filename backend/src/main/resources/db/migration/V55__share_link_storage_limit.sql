-- The per-link storage limit (ADR 0073 §4): the most any one CONTRIBUTOR or UPLOADER link of the tenant stores, set
-- by the tenant owner. It lives on the tenant row because the tenant row is also what a link start locks, so the
-- limit is read under the same lock the reservation is taken under (ADR 0073 §5). The default is 10 GiB, so every
-- existing tenant carries it; there is no "unlimited", and 0 switches link uploads off. TenantEntity repeats the
-- default for a tenant born through the entity.
ALTER TABLE veyq_tenant ADD COLUMN share_link_limit_bytes BIGINT NOT NULL DEFAULT 10737418240;

-- A link's usage sums the media its identities contributed (V32 added the column bare). Every link start takes that
-- SUM while holding the tenant lock, so it must not scan the media table.
CREATE INDEX ix_media_share_identity ON veyq_media (share_identity_id);
