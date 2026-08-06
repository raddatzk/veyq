-- cr-0115 (ADR 0036): an editable curator note on a share.
--
-- sealed_note      = the curator's note sealed under K_share (opaque base64) — shown on the share page.
-- sealed_share_key = symWrap(K_mem, K_share), a recoverable copy of the per-share key: openable ONLY with K_mem
--                    (which the operator lacks), so the curator — who always holds K_mem — recovers K_share later
--                    to re-seal (edit) the note in place. K_share never enters the operator's reach.
-- Both are opaque TEXT ciphertext, nullable (a share may carry no note, or predate this migration).

ALTER TABLE veyq_share ADD COLUMN sealed_note TEXT;
ALTER TABLE veyq_share ADD COLUMN sealed_share_key TEXT;
