-- Group invite name via a derived name-key, not a one-off owner seal (uc.spaces.consent-group-add, cr-0096,
-- ADR 0027). Supersedes cr-0094's one-off name-seal: instead of the owner sealing an arbitrary (unverifiable)
-- name plaintext to the invitee, the group name is sealed under a name-key K_name derived from the name's
-- content-epoch key (K_name = HKDF(E_e, "veyq/v1/group-name-key")). A pending invitee is handed the wrapped
-- K_name and opens the SAME canonical encrypted_name the members read, so a modified owner cannot privately show
-- a divergent name.
--
-- name_epoch: the content-key epoch the group name was sealed under (like every node name, ADR 0024 §2) — 0 at
-- birth (forward-secret-ready), bumped only by a rename (out of scope here). Defaults to 0 so existing groups are
-- untouched. The membership's sealed_invite_name column is repurposed to hold the wrapped K_name — renamed to
-- sealed_name_key (RENAME COLUMN is in the H2 2.x ∩ MariaDB 10.5.2+ common subset; V15/V18 precedent). Clean
-- break, pre-production: no name data to migrate. Operator-blind: an opaque epoch index + a wrapped key —
-- never a key the server can use, never a plaintext name.

ALTER TABLE veyq_scope ADD COLUMN name_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_membership RENAME COLUMN sealed_invite_name TO sealed_name_key;
