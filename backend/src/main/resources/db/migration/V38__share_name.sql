-- cr-0116 (ADR 0036): an optional private share name — a management-only label sealed under K_share (AAD
-- share-name), distinct from the Memory name and NEVER served to the viewer. The curator's share list shows it
-- as the row title (falling back to the role). Opaque TEXT ciphertext, nullable.

ALTER TABLE veyq_share ADD COLUMN sealed_share_name TEXT;
