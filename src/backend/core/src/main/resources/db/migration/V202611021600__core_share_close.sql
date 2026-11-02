-- ADR 0114: closing a link re-keys the subtree below it.
--
-- Decision 6: an end date closes a link through a server sweep, which marks the root's subtree due and records here
-- that it carried that close out, so it marks each expired link once. A reopen (an edit moving the end date into the
-- future or clearing it, a re-activation) clears it.
ALTER TABLE veyq_share ADD COLUMN expiry_closed BOOLEAN NOT NULL DEFAULT FALSE;

-- Decision 5: a closed link carries no key of the root - a close clears its wrap and its sealed drop public key, and a
-- reopen brings the root's current one. A link switched off before this migration keeps neither (no instance is live,
-- and a re-activation now carries the key anyway).
UPDATE veyq_share SET wrap = NULL, sealed_drop_public_key = NULL WHERE enabled = FALSE;
