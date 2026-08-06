-- Live media updates (uc.memory.live-updates, cr-0107, ADR 0033). An open Memory gallery reflects other
-- members'/contributors' media adds + removes live, and an unchanged re-open is free. The cheap change signal
-- is a per-node counter: media_revision is bumped by every gallery-media mutation (attach / meta-edit / delete;
-- NOT a cover change), so an unchanged poll is an O(1) counter compare (a conditional-GET 304, no media query).
-- delete_revision is bumped ONLY by a delete (so a delete moves BOTH counters) and gates the O(N) id-set
-- delete-reconcile: the server returns the node's current mediaId set only when a client's last-seen
-- delete_revision lags. Both counters live on the same veyq_node row → a single read is a consistent pair.
-- updated_at is the media's keyset-cursor sort key (= created_at at attach, bumped on a meta edit), so a change
-- streams only the media past the client's (updatedAt, id) cursor — never a full re-list. All opaque operational
-- metadata (counters + coarse timestamps): the server stays operator-blind. Types kept to the H2 ∩ MariaDB
-- common subset (INT for the counters, TIMESTAMP for the clock); the counters default 0, so existing rows are
-- untouched and every gallery starts at revision 0.

ALTER TABLE veyq_node ADD COLUMN media_revision INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_node ADD COLUMN delete_revision INT NOT NULL DEFAULT 0;

-- Nullable in the DB (the common subset has no portable ALTER ... SET NOT NULL), but always populated in code
-- (attach stamps it = created_at, a meta edit bumps it) and backfilled here for existing rows; the read path
-- coalesces updated_at ?? created_at defensively. **TIMESTAMP(3)** = millisecond precision (both H2 and MariaDB
-- accept the (3) — it stays in the common subset): the keyset cursor round-trips through a JS `Date`, which holds
-- only milliseconds, so the stored precision must be ≤ ms or the wire value the client echoes back would read as
-- strictly-earlier than the row and re-fetch it forever. Millisecond granularity also all but eliminates the
-- same-instant collision that a coarser (whole-second) timestamp would leave to the id tie-break.
ALTER TABLE veyq_media ADD COLUMN updated_at TIMESTAMP(3);
UPDATE veyq_media SET updated_at = created_at WHERE updated_at IS NULL;
