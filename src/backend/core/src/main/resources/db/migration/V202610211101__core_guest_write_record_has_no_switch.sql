-- The guest-write record has no switch (ADR 0093 § 10, ADR 0088 decision 14, revised 2026-10-21): it is kept wherever
-- a guest may write, so core.public-shares.contribute and .drop-box decide where. The catalog lost the entry, and an id
-- the register does not advertise reads as off, so its stored switch rows go: the instance's off row an operating mode
-- of PRIVATE set (V202610172106), and any tenant row, which the catalog never allowed but which must not outlive it.
DELETE FROM veyq_capability_switch WHERE id = 'core.public-shares.guest-write-record';
DELETE FROM veyq_tenant_capability_switch WHERE capability_id = 'core.public-shares.guest-write-record';
