-- The guest-write record keeps the request's Origin header beside the address, port and time (ADR 0093 § 10): since
-- the guest share API answers every origin (ADR 0121), a foreign page can make a visitor's browser write through a
-- link it knows, and the record then names the visitor's address; the origin tells such a write apart. Only the shapes
-- RequestOrigin keeps reach it — a serialized origin (at most 255 characters), the literal `null` of an opaque origin,
-- the marker `invalid` — or NULL when the request carried none, as every row written before this column did. It is the
-- same row, so retention, purge, erasure and a hold's copy follow the row's.
ALTER TABLE veyq_guest_write_record ADD COLUMN request_origin VARCHAR(255);
