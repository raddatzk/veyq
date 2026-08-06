-- The owner's login email: identifier and source of the deterministic Model B auth salt.
-- Server-visible identity metadata (not content). Unique per user.

ALTER TABLE veyq_user ADD COLUMN email VARCHAR(254) NOT NULL;
CREATE UNIQUE INDEX ux_veyq_user_email ON veyq_user (email);
