# Enabler: Node metadata — the sealed `wrappedMeta` blob

- **ID:** en.memory.node-meta
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **built** — the envelope + the `date` sub-object shipped with **cr-0067**
  ([uc.memory.date](../use-cases/uc.memory.date.md)), then `location` ([uc.memory.place](../use-cases/uc.memory.place.md),
  cr-0069), `cover` ([uc.memory.cover](../use-cases/uc.memory.cover.md)) and `participants`
  ([uc.memory.participants](../use-cases/uc.memory.participants.md)). The envelope itself is core's since inbox #411
  (`web/src/core/node/nodeMeta.ts`).
- **Provides:** **one** optional, opaque, **client-versioned** metadata blob (`wrappedMeta`) on a content node
  (MEMORY/CHAPTER), sealed under that node's **`K_mem`** — the single sealed home for a Memory's curated
  node-level metadata (its **date**, and next its **place**). Materialized so a browse card / a tenant map can
  show + sort by it **without loading the Memory's media**.
- **Serves (use-cases):** [uc.memory.date](../use-cases/uc.memory.date.md) (the `date` sub-object),
  [uc.memory.place](../use-cases/uc.memory.place.md) (the `location` sub-object),
  [uc.memory.concurrent-curation](../use-cases/uc.memory.concurrent-curation.md) (every writer of the blob,
  version-checked).
- **Depends on:** [en.memory.tree](en.memory.tree.md) — the node model + `K_mem` birth/open this seals under;
  through it `uc.account.unlock` (identity → `K_SC` → `K_mem`). Applies
  [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) ("capture time, location" as `K_mem` content) at
  the **node** level.
- **Preconditions:** an **unlocked** vault; the node's **`K_mem`** is unwrapped (its Memory is open, or its
  parent chain resolved).
- **Postconditions (success):** the node carries an opaque `wrappedMeta` ciphertext; the server stored + echoes
  it verbatim and never inspected it — no day, coordinate, or place name crosses.

## The envelope

- **`wrappedMeta`** — an optional field on a content node, **AEAD-sealed under the node's `K_mem`** (purpose
  `node-meta`, bound to the node — [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)),
  wire-encoded as opaque **base64**. It carries a **client-only, versioned** JSON blob whose shape can **grow
  without a contract change** (the operator never learns a field — the lesson from the media EXIF
  tiers):

  ```
  { v: 1,
    date?:     { mode: 'auto' | 'single' | 'range' | 'ongoing',
                 from?: 'YYYY-MM-DD',        // start day  (single ⇒ from == to; ongoing ⇒ no `to`)
                 to?:   'YYYY-MM-DD' },       // end day    (absent for ongoing / undated auto)
    location?: { name:         string,        // the label — MANDATORY, except a still-PENDING auto place (see below)
                 nameSource:   'geocoded' | 'manual',   // manual = frozen; geocoded = may refresh on anchor move
                 derived?:     { lat, lng },   // map position = the MEDOID of media GPS (min summed Haversine dist — never the mean)
                 derivedMode?: 'fixed' | 'auto',        // 'auto' follows the medoid (default); 'fixed' freezes the framed map centre
                 nameAnchor?:  { lat, lng, level } } }  // coord + granularity level (road…country) a geocoded name resolved at
  ```

  - **`date`** ([uc.memory.date](../use-cases/uc.memory.date.md)) — the effective day range, materialized for
    every mode; `auto` = min→max over the Memory's *trustworthy* `capturedAt`.
  - **`location`** ([uc.memory.place](../use-cases/uc.memory.place.md)) — one named place, set **intent-first**
    (cr-0069): the editor opens on the **medoid** of the media GPS with the pin as the map's centre crosshair
    ("Karte verschieben" — you move the map, the centre is the place). Leaving it there = `auto` (follows the
    photos, re-medoids as media arrive); **moving the map by hand** takes over → `fixed` (the framed centre is
    frozen). The medoid is the actual photo minimizing the summed **Haversine** distance (a real spot, robust to
    an outlier → never the sea, never the mean). The **name** comes from the reverse-geocoded **address
    hierarchy** — the user picks the granularity `level` (road…country) or types their own; `nameAnchor` records
    the coordinate **and that level** so an `auto` refresh keeps the grain. `name` is mandatory **except a
    still-*pending* `auto` place**: an `auto` + `geocoded` location armed at creation with **no name/position
    yet** (the "follows your photos" opt-in), whose position and name are **materialized by the first geotagged
    upload** (the `MemoryDetail` self-heal). Reverse-only, opt-in, veyq-blind geocoding:
    [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md).

- **Threaded through the existing node CRUD — no new endpoint.** Set at `createNode`; updated by the **partial**
  `updateNode` (`PATCH …/nodes/{nodeId}`) with **partial semantics** — a non-null `wrappedMeta` replaces it, an
  omitted/null one leaves it unchanged (so a rename/move that omits it is safe); returned (small) on `listNodes`.
  The blind server threads the ciphertext verbatim through `NodeEntity` / store / handler / resource.
- **Version-checked** ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)). `listNodes`
  returns each node's `metaVersion` (born 0). An `updateNode` that carries `wrappedMeta` must also carry the
  `metaVersion` it was built from (else `400`); the server compare-and-sets it and stores `metaVersion + 1`, or
  writes nothing — a rename in the same request included — and answers `409 stale_version` with the current blob
  and version. Only this write moves the counter.
- **Schema** (core baseline): `veyq_node.wrapped_meta TEXT NULL` and `veyq_node.meta_version`; the same counter
  on `veyq_media` (Memories baseline).

## Materialize, don't recompute — the reason it lives on the node

The browse card grid (and, later, the tenant map) show + sort by this metadata **but never load a Memory's
media**. So the effective value must **already be sealed on the node**, not computed at display from the media.
That is the whole reason `wrappedMeta` sits on the node and is returned by `listNodes`: a client unseals each
node's blob under its `K_mem` and renders — no media fetch, no server-side computation, operator-blind. Each
use-case defines **how** its sub-object is materialized — the date's `auto` reconcile over the media; the
place's deliberate **"set location"** (the medoid of the media GPS, formed on demand).

**No "locations API".** The tenant map plots every Memory from the sealed `location` that `listNodes` already
returns, unsealed client-side — a dedicated locations endpoint would hand the operator the coordinates, so
there is none; the blind server cannot geo-query what it never sees (the client fetches all + plots what it can
open).

## Decisions / notes

- **One extensible blob, not many fields.** Date now, place next, future metadata later — all in the same
  client-versioned `wrappedMeta`, so the contract + DB schema don't churn per addition.
- **Best-effort unseal for display, never for a write.** A malformed or wrong-key blob *displays* as absent (no
  metadata), never a broken header/card/pin. A **writer** treats only an absent blob as empty: a present one that
  does not open is refused, because rebuilding it from empty would re-seal one field and drop every sibling
  ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) point 4).
- **Operator-blind at the node level** ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)): the server
  sees the node shape + its timestamps (to the second) + this opaque blob; never its contents.

## Related

- Use-cases: [uc.memory.date](../use-cases/uc.memory.date.md), [uc.memory.place](../use-cases/uc.memory.place.md),
  [uc.memory.concurrent-curation](../use-cases/uc.memory.concurrent-curation.md)
- Enablers: [en.memory.tree](en.memory.tree.md) (the node + `K_mem` substrate this builds on)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (content-key hierarchy; `K_mem` seals
  "capture time, location")
