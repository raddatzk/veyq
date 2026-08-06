-- Consent opt-in for group-add (uc.spaces.consent-group-add, cr-0094, ADR 0014 §5). A person can turn on an
-- account-global "ask before adding me to groups" preference; with it on, a GroupOwner's add becomes a consent
-- proposal held as a soft, grant-less membership (pending_consent) that grant-delivery SKIPS — so K_SC is gated
-- BEFORE the wrap — until the invitee accepts. The owner's device one-off-seals just the group name to the
-- invitee (sealed_invite_name) so they decide informed; added_by records who proposed. Everything defaults to
-- the pre-consent behaviour (flag false, columns null), so existing rows are untouched. Operator-blind: an
-- opaque ciphertext name + a boolean + a flag — never a key, never a plaintext name.

ALTER TABLE veyq_membership ADD COLUMN pending_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE veyq_membership ADD COLUMN sealed_invite_name TEXT NULL;
ALTER TABLE veyq_membership ADD COLUMN added_by VARCHAR(36) NULL;

ALTER TABLE veyq_user ADD COLUMN require_group_add_consent BOOLEAN NOT NULL DEFAULT FALSE;
