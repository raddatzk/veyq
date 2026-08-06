-- cr-0059: the gallery's justified grid needs each media's aspect ratio. In the operator-blind model the
-- server can't see dimensions, so the client seals the original's {w,h} under K_mem and stores it here as
-- opaque ciphertext (base64) — set at completeMedia, returned verbatim on list, never inspected. Nullable:
-- absent for media whose frame the client couldn't decode (the grid falls back to a default aspect).
ALTER TABLE veyq_media ADD COLUMN wrapped_dimensions TEXT NULL;
