-- Item marks are written as 16-byte media ids beside the self-tag instead of as JSON (inbox #500, ADR 0068 § 4): the
-- plaintext inside each sealed blob changed, so a blob written before does not decode any more. No legacy path (owner
-- decision 2026-10-19: veyq is not live): every stored set is dropped rather than read twice. A member's marks start
-- empty again; the contract's cap rose to 262,144 characters, which the MEDIUMTEXT column already holds.
DELETE FROM veyq_scope_item_favorites;
