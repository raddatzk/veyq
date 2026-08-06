-- cr-0067: a Memory's curated date (and, a later slice, place) lives one level up from a single media, on the
-- node itself. It is sealed under the node's K_mem (AAD context node-meta) as a client-versioned JSON blob
-- {v, date{mode,from?,to?}, place?} — like the media EXIF tier, its shape can grow without a contract change.
-- Returned with every listNodes so a browse card can show + sort by the range WITHOUT loading the Memory's
-- media. Set at createNode + updated via the partial updateNode PATCH; opaque ciphertext (base64) the blind
-- server stores + echoes, never inspects. Nullable: absent = the implicit auto-with-no-dated-media state.
ALTER TABLE veyq_node ADD COLUMN wrapped_meta TEXT NULL;
