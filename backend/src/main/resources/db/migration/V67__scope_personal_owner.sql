-- A member has one personal scope per tenant (ADR 0002), held by a unique key rather than by a read before the
-- insert (developer-styleguide §12, inbox #278).
--
-- The create used to check "has this member a personal scope here?" and then insert under a client-minted id. Two
-- creates under two ids both read "no" and both inserted, so the member had two personal scopes. The key column
-- holds the owning account on a PERSONAL scope and is null on every other kind. Both engines admit any number of
-- nulls in a unique index, so only PERSONAL rows collide. The account id is already on the scope's membership row,
-- so the server learns nothing new.
ALTER TABLE veyq_scope ADD COLUMN personal_owner VARCHAR(36) NULL;

-- Backfill: a PERSONAL scope's owner is its OWNER membership's account. A PERSONAL scope with no OWNER row keeps
-- null, which is also what the old membership-based check said about it.
UPDATE veyq_scope SET personal_owner =
    (SELECT MIN(m.user_id) FROM veyq_membership m WHERE m.scope_id = veyq_scope.id AND m.role = 'OWNER')
WHERE kind = 'PERSONAL';

-- An instance on which that race already happened holds two PERSONAL scopes for one member and tenant, and the index
-- below would refuse to build, so the server would not start. One scope per pair keeps the key (the smallest id), and
-- the others keep their rows, grants and content with a null key. Nothing is deleted, and the member still has a
-- personal scope, so no further one can be created. The helper table exists because MariaDB refuses an UPDATE whose
-- subquery reads the table being updated.
CREATE TABLE veyq_v67_personal_keep (scope_id VARCHAR(36) NOT NULL PRIMARY KEY);
INSERT INTO veyq_v67_personal_keep (scope_id)
    SELECT MIN(s.id) FROM veyq_scope s WHERE s.personal_owner IS NOT NULL GROUP BY s.tenant_id, s.personal_owner;
UPDATE veyq_scope SET personal_owner = NULL
WHERE personal_owner IS NOT NULL AND id NOT IN (SELECT k.scope_id FROM veyq_v67_personal_keep k);
DROP TABLE veyq_v67_personal_keep;

CREATE UNIQUE INDEX ux_scope_personal_owner ON veyq_scope (tenant_id, personal_owner);
