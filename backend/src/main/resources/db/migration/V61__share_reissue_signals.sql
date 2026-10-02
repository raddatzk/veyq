-- ADR 0075 points 4-5: what decides that a share link comes back only by re-issue. A scope counts the removals of
-- its accepted members (member_removals, bumped in the removal's own transaction), and each share stamps the count
-- it was minted under (member_removals_at_mint) — a tenant never rotates on a removal, so its epoch carries no
-- removal signal. reissue_only marks a share a bulk switch-off turned off. Both start at 0 / false: pre-V1
-- migrations assume an instance reset, so there is no removal history to reconstruct.
ALTER TABLE veyq_scope ADD COLUMN member_removals INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_share ADD COLUMN member_removals_at_mint INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_share ADD COLUMN reissue_only BOOLEAN NOT NULL DEFAULT FALSE;
