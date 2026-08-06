-- cr-0073: a Memory's cover can be a brand-new uploaded image that is NOT one of its media — committed as a
-- standalone cover blob (a veyq_blob row) attached to the node with NO veyq_media row (so it never appears in
-- the gallery, has no derivative tiers). The link is a single nullable column on the node, mirroring how V17
-- added wrapped_meta: cover_blob_id is an opaque blob id, cover_byte_size the ciphertext size (for the byte
-- budget + retention). The blind server thereby knows "this node has a cover blob of N bytes" — the same class
-- of opaque metadata it already holds for media — but never the image, the crop, or that wrappedMeta.cover
-- points at it (all sealed under K_mem). This column makes the blob reclaimable on replace + node-delete
-- without a reference count over ciphertext.
ALTER TABLE veyq_node ADD COLUMN cover_blob_id VARCHAR(36) NULL;
ALTER TABLE veyq_node ADD COLUMN cover_byte_size BIGINT NULL;
