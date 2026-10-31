# UC: Memory place — one sealed, named location

- **ID:** uc.memory.place
- **Primary actor:** A member with an **unlocked vault** who has a Memory (or chapter) open and wants to give it
  a **place**.
- **Goal:** Curate a Memory's **place** — **one named location**: a map position derived from the Memory's own
  photo GPS, plus a name that is either typed or reverse-geocoded — **sealed under a field key derived from the Memory's `K_node`** and
  shown on the Memory's detail header and on the **places map** — **operator-blind** (the veyq server never
  sees a coordinate or a place name).
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held). The node's
  `wrappedMeta` blob already exists (shipped for the date, [uc.memory.date](uc.memory.date.md)); place adds a
  `location` sub-object to it.
- **Postconditions (success):** Once set, the Memory shows its named location; the user can "set location" (a
  medoid position + a typed or reverse-geocoded name), move/rename it, and see it as a pin on the places map. The
  server stored only the **opaque `wrappedMeta` blob** + the unchanged node shape.

A Memory carries **one location** — a map position (from its own photo GPS) plus a **name** (typed or
reverse-geocoded). Name and coordinate are **independent**, and setup is **deliberate** (never silent). The
sealed **shape** + how it is materialized live in the enabler
[en.memory.node-meta](../enablers/en.memory.node-meta.md); the reverse-only, veyq-blind **geocoding** decision
is [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md). This use-case is the **user flow**.

## Main flow — set a location on a Memory that has media

1. With the Memory open, the user triggers **"set location"**.
2. The client forms `derived` as the **medoid** of the Memory's media GPS, read from the **always-listed capture
   tier** (it carries each medium's GPS alongside its `capturedAt`, so this costs no per-item fetch).
3. The client asks for a **name**, one of two ways:
   - **typed** → `manual` (e.g. "at grandma's"); or
   - **framed on the map**, if the reverse lookup is enabled → its centre + zoom become the `nameAnchor`, the
     provider returns a name at that resolution → `geocoded`, still editable.
   On a server without a map the editor is the name field alone (A4). A name is at most **200 code points** (the field's
   `maxlength`; every read and write cuts a longer one, an import's too): the place is the one field of the Memory's sealed
   meta a member types without another bound, and the meta's worst case must stay under its 8192 characters
   (`bannerModel.test.ts`, "the meta budget").
4. On save the client seals `{ v, …, location }` into `wrappedMeta` and PATCHes `wrappedMeta`. The Memory's detail
   header shows the named location, and it appears as a pin on the places map.

## Alternate / exception flows

- **A1 — pre-arm at creation (no media yet):** as with the date, the user may declare a location while creating
  the Memory: a **fallback name** (mandatory) + `nameSource` (`manual` = final, or `geocoded` = a placeholder to
  be resolved), with `derivedMode: 'auto'` forced (there is nothing to snapshot). Then **on the first geotagged
  medium** `derived` forms as the medoid, and — only if the user opted into geocoding — `nameAnchor` forms at a
  default zoom and the name resolves, replacing the fallback. No opt-in ⇒ the fallback simply stands (blind).
- **A2 — the position keeps itself current, until the user overrides:** position is **`auto` by default** and
  re-medoids as media are added (it barely moves). **Moving the pin by hand flips it to `fixed`** — the
  deliberate correction wins and then stays frozen, exactly as a manual date beats `auto`. A `manual` name and a
  `fixed` position are frozen; a `geocoded` name may refresh when its anchor moves.
- **A3 — browse the map:** a **toggle in the tenant root** shows every Memory as a pin, read from each node's
  sealed `location` via `listNodes` — no media load, no locations endpoint. Clicking a pin opens the Memory.
- **A4 — a server that shows no map** (`memories.map` off, by the Server Owner or the tenant owner; ADR 0088,
  inbox #458): the chip opens the editor with **no map and no consent dialog** — there is nothing to consent to. The
  name is **typed** (`manual`); no geocoder is asked, even where `memories.places` is on, since its consent is asked
  only with the map (ADR 0018). The position is the medoid of the photos' GPS (`auto`), a pin set earlier on a map
  (kept, `fixed`), or none: a place that is a **name only** (E3), kept `auto` so the first geotagged photo gives
  it the medoid. Renaming a geocoded place there makes it `manual`; saving it untouched keeps its anchor.

- **E1 — vault locked / Memory not open:** without the node's `K_node` the `wrappedMeta` can't be unsealed → the
  place is unavailable until unlock + open.
- **E2 — malformed / wrong-key `wrappedMeta`:** best-effort — treated as no location; never a broken header/pin.
- **E3 — "set location" with no photo GPS:** `derived` can't be formed. **With a map** the user moves the map to
  set the point by hand (`fixed`). **Without one** (A4) they type a **name** (`manual`) → a valid **name-only**
  location (`derivedMode: 'auto'`, no `derived`): shown in the detail, the profile's rows and an export, but **not**
  on any map (no coordinate) until a geotagged photo gives it the medoid.
- **E4 — reverse-lookup off or unreachable:** the name field falls back to **manual typing** — no external
  request, fully blind. The map/pin still works from `derived`; only the auto-name is unavailable. With the map off
  as well, A4 holds.
- **E5 — a member who declined geocoding opens a shared Memory:** they **see** the stored (sealed) name that
  another member resolved, with **no request from their device** — the opt-in is per-device-request, not
  per-view.
- **E6 — invalid manual range / bad input:** the editor validates before sealing (e.g. a name is required to
  save a location).

## Quality notes

- **Sharing (groups/tenant) — one consent, decided per space, built per browser.** Tiles + geocoding are **one
  opt-in**. What is built is one consent per browser, asked in context and applied in every space; it cannot be
  withdrawn in the interface yet. The per-space consent of a group — and the opt-in in personal settings — are
  decided and **not built** ([ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md); the current
  picture is [Memories § Places and the map](../../../apps/memories.md#places-and-the-map)). Either way a
  **non-consenting** member's device makes no request, so they get **no map** and see an already-resolved name only
  as **text**. A resolved name is sealed in the shared `wrappedMeta`, so members holding `K_node` see it without
  their own device contacting the provider.
- **Multilingual names.** A stored `name` is a **single frozen string**. For **shared** content it resolves in
  the **tenant's configured language** (a tenant setting) — consistent across members, not the random locale of
  whoever resolved it first; a personal space uses the user's own locale. Because `nameAnchor` is kept, an
  opted-in viewer can re-resolve in **their** language on demand. Nominatim returns names from OSM `name:<lang>`
  tags via `accept-language` with **graceful fallback to the local name** (no hard error on an unsupported
  language) — so de+en are well covered and further locales degrade, never break.
- **Operator-blind — the *veyq* operator is the adversary.** The veyq server sees the node shape + its
  timestamps (to the millisecond) + the **opaque `wrappedMeta`** blob; never a place name or a coordinate. The **opt-in
  reverse-geocoding request is a different, consented trust boundary**: it goes **client-direct** to the provider
  (never through the veyq backend — proxying would let the operator see the coordinate), carries **no veyq
  identity** (a bare coordinate), and is transparent. The threat-model clarification + this third-party boundary
  live in [security-model](../../../crosscutting/security-model.md); the provider seam in
  [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md).
- **Place is deliberately set up, then may auto-fill; the medoid keeps it honest.** A location never *appears
  from nothing* — the user deliberately sets one up (at open or at creation) with a mandatory name. From there it
  may auto-fill: the position is the **medoid** of the media GPS (an actual photo spot, never the sea, barely
  moving as media are added — this is what makes `derivedMode: 'auto'` safe), and, **only under the user's opt-in**,
  the `geocoded` name auto-resolves on the first geotagged media (standing consent — see the reverse-lookup note).
- **GPS rides the listed capture tier — like the date.** The medoid (and every `auto` re-medoid on media-add)
  reads GPS **list-level**, the way date-`auto` reads `capturedAt` — GPS is promoted into the always-listed
  capture tier rather than left in the per-item EXIF blob, so an `auto` re-medoid on every upload is no N-fetch
  operation.
  Still sealed (under a field key derived from `K_node`), still operator-blind; no backfill — older media without the listed tier don't
  contribute to the medoid (exactly as they don't to date-`auto`).
- **A per-PHOTO place name.** The detail panel resolves **one** name for the **one** photo in front of the user,
  **once ever**, and seals it into the same listed tier the medoid reads — still client-direct, identity-free and
  gated on the same consent. Why this is no per-item fetch for the medoid, and the request discipline it follows:
  [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md) § *Request discipline* and *What this does not
  reopen*.

## User impact

A Memory shows where it happened and appears as a pin on your map, while veyq's server never learns the place or its
coordinates.

## Related

- Feature: [Memory](../_index.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope this fills)
- Use-cases: [uc.memory.date](uc.memory.date.md) (introduced the shared `wrappedMeta` blob this reuses),
  [uc.memory.browse](uc.memory.browse.md) (the node CRUD + `updateNode` this folds into),
  [uc.memory.gallery](uc.memory.gallery.md) (the media EXIF/GPS tier the medoid derives from)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (K_node seals "capture time, location"
  — this UC applies it at the node level), [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md)
  (reverse-only, provider-neutral geocoding; Nominatim reference; veyq-blind),
  [ADR 0018](../../../decisions/0018-map-tiles-provider-neutrality.md) (map tiles: raster via Leaflet,
  provider-neutral, third-party only and client-direct, veyq-blind)
- Crosscutting: [security-model](../../../crosscutting/security-model.md) (operator-blind = the veyq operator;
  third-party services are a separate consented boundary)
- Future: **on-device coarse names** (a bundled gazetteer, ADR 0016); **derived** place from GPS clusters
  aggregated across a Memory *plus its chapters*
- End-user view (docs site): [Set the location](../../../../site/src/content/docs/memories/set-the-location.mdx)
