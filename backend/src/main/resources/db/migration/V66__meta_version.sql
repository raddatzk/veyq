-- A sealed meta write carries the version it was built from (ADR 0077, uc.memory.concurrent-curation).
--
-- One counter per sealed meta blob, bumped by exactly the write that replaces that blob and by nothing else: a
-- node's `wrapped_meta` (a Memory's date, place and cover) and a media's `wrapped_meta` (its corrected capture
-- time, resolved place name and media type). The write compare-and-sets it, so a member who re-seals a blob from
-- a stale copy is refused instead of erasing what a peer wrote meanwhile. Deliberately NOT `media_revision`: that
-- counter moves on every attach, trash, restore, move and meta change, so a check on it would turn every
-- concurrent upload into a conflict. Every existing row starts at 0, as a newly created one does.
ALTER TABLE veyq_node ADD COLUMN meta_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE veyq_media ADD COLUMN meta_version BIGINT NOT NULL DEFAULT 0;
