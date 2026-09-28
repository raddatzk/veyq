-- The UPLOADER guest never holds K_mem (ADR 0031 point 7). A Memory carries its drop PRIVATE key sealed under K_mem
-- (set once, by its first UPLOADER share); an UPLOADER share carries the drop PUBLIC key sealed under K_share in place
-- of the K_mem wrap, so `wrap` becomes nullable; an UPLOADER identity carries its own key wrapped to the drop public
-- key. All three are opaque base64 (TEXT, as every other sealed field). Nothing is migrated: a stored-format change
-- before V1 — an existing UPLOADER share keeps its wrap and needs the instance reset.
ALTER TABLE veyq_node ADD COLUMN sealed_drop_private_key TEXT NULL;
ALTER TABLE veyq_share ADD COLUMN sealed_drop_public_key TEXT NULL;
ALTER TABLE veyq_share MODIFY COLUMN wrap TEXT NULL;
ALTER TABLE veyq_share_identity ADD COLUMN wrapped_identity_key TEXT NULL;
