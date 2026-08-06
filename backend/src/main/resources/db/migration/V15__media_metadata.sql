-- cr-0064: seal each media's capture time + EXIF at upload, operator-blind, in two tiers.
--
-- Tier 1 (layout): cr-0059's wrapped_dimensions {w,h} becomes the versioned geometry tier {v,w,h} — same
-- column, renamed to wrapped_layout, still opaque ciphertext (base64) returned with every listNodeMedia and
-- kept minimal. RENAME COLUMN is in the H2 (2.x) ∩ MariaDB (10.5.2+) common subset; only smoke data exists, so
-- no backfill.
ALTER TABLE veyq_media RENAME COLUMN wrapped_dimensions TO wrapped_layout;

-- Tier 2 (EXIF): the full bundle (capture time, camera, lens, aperture, ISO, GPS, …) sealed under K_mem — set
-- at completeMedia, NOT returned by listNodeMedia (kept constant-small); the lightbox lazy-loads it via
-- getMediaMeta. Opaque ciphertext, never inspected. Nullable: absent when the source carried no readable EXIF.
ALTER TABLE veyq_media ADD COLUMN wrapped_exif TEXT NULL;
