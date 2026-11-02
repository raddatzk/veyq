-- ADR 0115 (owner 2026-11-02): a rotation re-wraps keys and never rewrites a value. A member's item marks on a node are
-- sealed under a value key of their own, minted at every write, and only that key is under the scope key: its wrap
-- (seal purpose `value-key`, base64, at most 128 characters) beside the row. Memories' scope re-key participant rewrites
-- the wrap only. No instance is live and the dev database is reset: a row written before this one has no wrap and none
-- can be made for it here (the server holds no key), so it is deleted — read by nobody without one.
DELETE FROM veyq_scope_item_favorites;
ALTER TABLE veyq_scope_item_favorites ADD COLUMN wrapped_value_key VARCHAR(128) NOT NULL;
