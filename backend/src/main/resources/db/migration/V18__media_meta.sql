-- cr-0068: consolidate the two always-listed per-media tiers into ONE sealed blob, and promote GPS into it.
--
-- The listed tier now carries {v, w, h, capturedAt, capturedAtOverride, capturedAtSource, gpsLat, gpsLng}
-- sealed under K_mem (opaque base64, AAD context media-meta) — geometry (grid), capture time (sort/edit), and
-- GPS (the Memory's place medoid, uc.memory.place). GPS moves OUT of the lazy wrapped_exif (no duplication),
-- mirroring cr-0066 moving capturedAt out of wrapped_exif. The heavy wrapped_exif stays lazy + GPS-free.
--
-- Reuse cr-0064's rename precedent (wrapped_dimensions -> wrapped_layout): RENAME + DROP are in the H2 (2.x)
-- ∩ MariaDB (10.5.2+) common subset. Only smoke data exists, so NO backfill — pre-existing rows keep whatever
-- sat in the renamed column and lose their separate capture-time blob; they re-seal the merged shape on next
-- upload (acceptable, pre-release).
ALTER TABLE veyq_media RENAME COLUMN wrapped_layout TO wrapped_meta;
ALTER TABLE veyq_media DROP COLUMN wrapped_captured_at;
