-- ADR 0036 point 4: a share's recovery copy of K_share is for the Memory's scope members only. It is sealed under
-- the scope key K_SC at the scope's current epoch, not under K_mem — every VIEWER/CONTRIBUTOR link carries K_mem,
-- so a copy under it let one link plus a dump open every sibling share's password, label and note, and rebuild its
-- link. This corrects V37's comment ("openable ONLY with K_mem … the curator always holds K_mem"), which is left
-- as applied (an applied migration is never edited). The epoch the copy is sealed under is stored beside it: null
-- exactly when sealed_share_key is. Nothing is migrated: a copy sealed under K_mem before this no longer opens —
-- a stored-format change before V1, so an existing instance is reset.
ALTER TABLE veyq_share ADD COLUMN sealed_share_key_epoch INT NULL;
