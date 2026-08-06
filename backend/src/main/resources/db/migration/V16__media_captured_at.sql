-- cr-0066: sort the gallery by capture time. The effective capturedAt must be available cheaply at grid-build
-- for every listed media, so it moves out of the lazy wrapped_exif (cr-0064/0065) into its own always-listed
-- tier: {v, capturedAt, capturedAtOverride} sealed under K_mem — the derived original (EXIF date or the file's
-- creation date) plus any user correction. Set at completeMedia + updated via PUT .../captured-at; returned with
-- every listNodeMedia; opaque ciphertext the server never inspects. Nullable: absent when no date was sealed
-- (the media sorts last). wrapped_exif stays (now date-free on the client side); only smoke data exists, so no
-- backfill (pre-existing media keep their date inside wrapped_exif and simply lack this column).
ALTER TABLE veyq_media ADD COLUMN wrapped_captured_at TEXT NULL;
