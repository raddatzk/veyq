-- Member-add (cr-0077): an invite now carries the ROLE it grants on acceptance. Until now every invite minted
-- an OWNER (tenant activation, cr-0010); a tenant owner can now also mint a MEMBER invite (ADR 0020) — the
-- invitee cold-births their account and joins the ACTIVE tenant grant-less, the scope key delivered later by a
-- key-holder (cr-0076). ADD COLUMN with a DEFAULT is portable across H2 (test) and MariaDB, and backfills every
-- existing invite as OWNER (their historical role).
ALTER TABLE veyq_tenant_invite ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'OWNER';
