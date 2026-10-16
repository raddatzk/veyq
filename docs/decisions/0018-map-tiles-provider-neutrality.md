# ADR 0018 — Map tiles: raster via Leaflet, third-party-only, coupled with geocoding (OSM reference)

- Status: Accepted; amended 2026-10-16 by [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  (inbox #458): on a server whose map is switched off, a place is named by hand without a map.
- Date: 2026-07-02

## Context

The **set-location** editor ([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)) shows a slippy
map with the Memory's medoid pin; the user pans/zooms to frame the place, and the framed centre + zoom drive the
reverse-geocoded name ([ADR 0016](0016-geocoding-provider-neutrality.md)). A later **tenant map** plots every
Memory as a pin. Both need **map tiles** — the basemap imagery — from a **tile server**, the second thing (besides
geocoding) that reaches outside the device. The tensions mirror ADR 0016:

- **Operator-blindness is about the *veyq* operator.** A tile request reveals the **viewport** (roughly where the
  user is looking) + client IP to whoever serves the tiles. If the **operator** served them, the operator would
  see the viewport → not blind. So — exactly as for the geocoder — tiles **must come from a party the operator
  does not control**: a third party (or, fully blind, bundled on-device data later). **Operator-self-hosted tiles
  are rejected**, not preferred; "self-hostable by default" means the veyq *backend* self-hosts, not that the
  operator runs every auxiliary service.
- **Raster or vector?** **Raster** (`{z}/{x}/{y}.png`) with **Leaflet** is simplest to render; **vector**
  (MapLibre GL) needs a style/font/sprite pipeline and a heavier client. The place UI needs a readable basemap,
  not cartographic polish — raster + Leaflet wins.
- **Tiles gate geocoding.** You frame the pin *on the map*; without tiles there is no map, so there is no framing
  and no name to geocode — "no tiles, no geocoding". Tiles + geocoding are therefore **one coupled consent**, not
  two independent knobs.

Considered and rejected:

- **Commercial tile vendors (Google / Mapbox / Maptiler keys).** Vendor lock-in + an API-key/identity leak — rejected.
- **Operator-self-hosted or operator-proxied tiles.** The operator would see the viewport stream (and carry the
  bandwidth) → breaks blindness. Rejected; tiles go client-direct to a third party.
- **Vector tiles / MapLibre GL now.** The self-host style pipeline is more than this needs; the seam doesn't
  preclude it later.
- **A separate tiles-only vs geocoding toggle.** Rejected in favour of the single two-stage consent below (tiles
  ⊃ geocoding).

## Decision

**Map tiles are raster, rendered by Leaflet, behind a provider-neutral tile-URL seam, fetched client-direct from
a third-party (never the operator, never a proxy). Tiles + reverse geocoding are a single two-stage, consented
opt-in.**

- **The seam is a tile-URL template** `https://…/{z}/{x}/{y}.png` (+ attribution + max-zoom); Leaflet's
  `TileLayer` consumes it. Swapping providers is a URL + attribution change — no vendor-locked client, no API key.
- **Third-party only, veyq-blind by construction.** Tiles are fetched **client-direct** by the browser, **never
  through the veyq backend and never from an operator-run tile server** (either would hand the operator the
  viewport). A tile request carries **no veyq identity, no Memory context, no pin coordinate** — just the
  `{z,x,y}` viewport. Reference = the **OSM standard raster tile server** (ODbL); its **OSMF usage policy** is
  honored with visible attribution and lazy, viewport-only loads.
- **Two-stage, coupled consent** — governed by the per-space model in
  [ADR 0016](0016-geocoding-provider-neutrality.md): **off** ⇒ no map ⇒ **no location feature at all** in that
  space (no pin, no name); **on** ⇒ tiles **and** geocoding (the basemap loads and reverse-geocoding is
  available; a name may still be typed manually on the map). There is no tiles-on/geocoding-off middle stage.
  *Amended 2026-10-16 (ADR 0088, inbox #458):* where the **server** serves no map (`memories.map` off), there is
  nothing to consent to and the place is set **without a map**: a typed name, the position from the photos' GPS or
  none (a place that is a name only, which no map pins). No geocoder is asked there either — tiles still gate
  geocoding in the place editor. A member who declines the consent on a server that does serve a map still gets no
  location feature, as above.
- **Attribution is mandatory** wherever OSM tiles render (© OpenStreetMap contributors).

## Consequences

- **The veyq operator never sees the map viewport or a pin coordinate** — tiles are client-direct to a party the
  operator doesn't run; nothing map-related reaches the veyq backend. Operator-self-hosting would forfeit this, so
  it isn't offered.
- **No vendor lock-in.** Switching tile providers (public OSM ↔ another permitted server ↔ a future vector
  provider) is a tile-URL / thin-factory change behind the same seam.
- **One consent covers the map experience.** Because tiles gate geocoding, a member either has the map + naming or
  neither — consented per space (personal pre-opt-in, owner-soft-enable, unanimity-binds; see ADR 0016). A
  non-consenting member gets **no tiles → no map**, and sees resolved names only as text.
- **Pairs with ADR 0016.** Tiles draw the basemap; geocoding turns the framed pin into a name; both are
  client-direct, identity-free, third-party boundaries under one opt-in — documented together in
  [security-model](../crosscutting/security-model.md).
- **Limitation (honest).** With tiles enabled, the tile server learns the viewport tiles + client IP — a
  *consented, non-veyq* boundary, distinct from the operator-blind invariant.

> The **personal-space** two-stage toggle (off / tiles+geocoding) lands with the set-location slice of
> `uc.memory.place` (cr-0069) and is reused by the later tenant map. The **group** consent mechanics live in
> ADR 0016.
