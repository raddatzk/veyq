---
id: cr-media-detail-panel-and-place-name
title: The detail panel shows what the photo knows — and where it was
status: planned
satisfies: [uc.memory.gallery, uc.memory.upload, uc.memory.place]
adrs: ["0016", "0018", "0044"]
invariants:
  - Operator-blind — every new field is sealed under `K_mem`; no new server-visible contract field.
  - The listed tier's 512-base64 cap — a new listed field must not push a worst-case tier over it.
  - The third-party boundary — client-direct, identity-free, opt-in, results sealed (ADR 0016).
  - Self-hostable — no new dependency; the geocoder/tile origin stays the configured seam.
---

## Goal

Opening a photo shows what the photo itself knows. Today the panel stops at camera, lens, focal length,
aperture, exposure, ISO, dimensions and a raw `48.137000, 11.575000`. This CR adds the file (original name,
plaintext size, format), the exposure facts we already read past (exposure compensation, flash,
35 mm-equivalent focal length), its provenance (editing software, artist/copyright, and the title, description
and rating an editor wrote into XMP/IPTC), and the rest of the geo block (altitude, camera view direction,
positioning accuracy) — and, where the user has consented to maps, a **resolved place name** and a small map
instead of the bare coordinate.

Every field rides in the two **client-only, versioned** sealed blobs that already exist, so there is no
contracts, backend or migration delta and the operator learns nothing new ([ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)).
The work is web + docs.

## Context & constraints

Owning docs: [`uc.memory.gallery`](../docs/features/memory/use-cases/uc.memory.gallery.md) (the panel),
[`uc.memory.upload`](../docs/features/memory/use-cases/uc.memory.upload.md) (extraction + sealing),
[`uc.memory.place`](../docs/features/memory/use-cases/uc.memory.place.md) +
[ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md) /
[ADR 0018](../docs/decisions/0018-map-tiles-provider-neutrality.md) (the geocoding boundary),
[`en.memory.media`](../docs/features/memory/enablers/en.memory.media.md) (the filename slice),
[`cc.security.csp`](../docs/crosscutting/cc.security.csp.md) and
[`security-model`](../docs/crosscutting/security-model.md) § the third-party boundary.

- **`MediaExif` is client-only and versioned** (`media.ts:89`) — a
  new field costs no contract change. That is why this CR is cheap.
- **The listed tier is capped at 512 base64 chars** — nine sites in `contracts/api/media.openapi.yaml` plus two
  in `sharing.openapi.yaml` — i.e. ≈355 bytes of JSON, and it already carries nine fields
  (`media.ts:75-86`). So the place name is the **only** new field
  allowed into it, it is **length-clamped** before sealing, and the cap is asserted against a worst-case tier
  (B2.7). Overflowing it would not just fail the geocode: `persistMeta` is shared with the capture-time edit,
  so an over-long tier breaks *that* PUT too. Everything panel-only goes into the lazy tier, capped at 16384.
- **The place name revisits a settled decision, and the CR must say how.**
  `uc.memory.place.md:90-93` promoted GPS *out* of
  the per-item EXIF blob into the listed tier precisely to avoid per-item work, "chosen over lazy per-item EXIF
  fetch". That rejection stands and is untouched: it was about computing **one Memory's medoid** from N
  per-item fetches on **every open**. This CR resolves **one name, once ever, for the photo the user is
  looking at**, then seals it into the tier the medoid already reads. Different frequency, different trigger,
  same boundary — but the decision is adjacent enough that phase 4 records the distinction where a reader will
  look for it.
- **ADR 0016 is silent on request volume**, and `geocode.ts` has no cache, no throttle and no 429 handling —
  the only volume control in the repo is a 700 ms pan debounce in
  `MemoryLocationEditor.vue:66`. Public
  Nominatim's policy is not silent: `measured` (WebFetch of <https://operations.osmfoundation.org/policies/nominatim/>,
  2026-09-20) it is "an absolute **maximum of 1 request per second**", bulk geocoding "is **not** encouraged",
  "Results **must be cached** on your side", and clients "sending repeatedly the same query may be classified
  as faulty and blocked". Caching is therefore a **policy obligation**, not an optimisation — so phase 2 adds
  the discipline *and* writes it into ADR 0016, which currently has no home for it.
- **The policy's identifying-header requirement is met by accident, and one header would break it.** It wants
  "a valid HTTP Referer or User-Agent identifying the application". `measured`
  (`grep -rni referrer --include='*.ts' --include='*.kt' --include='*.html' --include='*.conf' --include='*.yaml' --include='*.properties' . | grep -v node_modules`
  → no `Referrer-Policy` anywhere): the browser default sends our origin as `Referer`. A future hardening pass
  adding `Referrer-Policy: no-referrer` would silently put us outside the policy. Phase 2 leaves that trap as a
  comment on the fetch, not in this file.
- **Only a personal space can reach this.** ADR 0016's group consent state machine is unbuilt — `useMapConfig`
  is a single personal toggle (`useMapConfig.ts:40-62`). So the
  place name resolves in a personal space in the user's own locale; the **tenant-language** rule for shared
  resolved names ([ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md) § sealed names) stays
  deferred to the groups CR along with the state machine. Phase 2 states this, it does not build it.
- **The filename is not a display change.** It is not sealed today at all, and
  `en.memory.media.md:39` parks "an encrypted
  original filename" in a later slice. This CR lands that slice. `uc.memory.gallery.md:14`'s "no pixel, no
  filename" claim describes what the *server* sees and stays true — the name is sealed, never sent (B1.6).
- **`exifr` is mocked in the suite** (`media.test.ts:12`). The
  tests prove our *mapping* and never that the library yields a tag under the options we pass, so every new tag
  name is a guess until B1.4 says otherwise. That test runs **first**.
- No `scripts/validate_*.py` anchors on `MediaExif`/`MediaMeta` or on any of the prose this CR corrects
  (`measured`: `grep -rn -iE "MediaExif|MediaMeta" scripts/*.py` → 0;
  `grep -rn -iE "exif|camera|lens|lightbox|wrappedMeta" scripts/*.py` → 0). The guards that *will* react —
  `validate_coverage.py`, `validate_doc_links.py`, `validate_adr_citations.py`, `validate_feature_format.py` —
  are not edited.

### Sequencing — two live CRs touch the same two functions

`measured` (`grep -n "sealMetadata\|readExif" changes/cr-*.md`): **`cr-gallery-one-order-for-every-tile`**
folds the capture-time rule that `readExif` + `sealMetadata` apply into one shared definition and extends
`uc.memory.gallery.yaml#date-sorted-grid`; **`cr-upload-prepare-visibly-and-decode-once`** makes
`sealMetadata` consume a decode result instead of re-probing. Phase 1 here edits both functions and phase 5
edits the same coverage file. **Land those two first** — then phase 1 adds fields to one settled shape instead
of racing a refactor of it. Nothing in phases 2–3 collides.

## Phases

- [ ] **1 web — the file and the fields we read past.** `AddMediaSource` gains `name`; both construction sites
      (L1) pass it; `sealMetadata` seals it in the **lazy** tier. `readExif` widens to
      `{tiff, exif, gps, xmp, iptc}` and maps `ExposureBiasValue`, `Flash`, `FocalLengthIn35mmFormat`,
      `Software`, `Artist`, `Copyright`, `Rating`, title/description, `GPSAltitude` (+`GPSAltitudeRef`),
      `GPSImgDirection` (+`GPSImgDirectionRef`), `GPSHPositioningError`. Panel rows + `en`/`de` keys (L2).
      Two traps: the displayed size is the **plaintext** one, `byteSize − 28·chunkCount`, the formula already
      written at `media.ts:776` — never raw `byteSize`; and `Flash`
      is a **bitfield**, so "fired" is `Flash & 0x1` and a truthy `16` means *not* fired.
      · Done when: `npm run check` green, B1.1–B1.6.

- [ ] **2 web + ADR — the sealed place name.** `MediaMeta` gains `placeName` + `placeLevel`; **`metaHasContent`
      learns both**; a third `persistMeta` caller (`backfillPlaceName`, shaped like `backfillMediaType`,
      `media.ts:651`). On panel open, when the item has GPS,
      `geocodingAvailable` is true, `readonly` is false and no name is sealed yet: `reverseGeocode` →
      `defaultLevel` → clamp → seal. Request discipline: a module cache keyed on the **rounded** coordinate
      (3 decimals ≈ 110 m, so one spot is one lookup) and a **serialized ≥1 s** queue. ADR 0016 gains a
      *request discipline* section — **added, not edited**: cache-and-seal is an obligation of the provider's
      policy, and a per-photo trigger is the first caller that can generate volume.
      · Done when: `npm run check` green, B2.1–B2.7, `python3 scripts/validate_adr_citations.py` green.

- [ ] **3 web — the mini map in the panel.** Reuse [`MemoryMap.vue`](../web/src/features/memory/components/MemoryMap.vue)
      (`tileUrl`/`attribution`/`maxZoom`/`center`/`zoom`/`marker`), gated on `mapsAvailable`; phase 1's
      `GPSImgDirection` draws the view direction. No consent → the text rows stand alone.
      · Done when: `npm run check` green, B3.1–B3.2.

- [ ] **4 docs — the panel's prose, and every claim that enumerates the old field set.** Re-run L5 **at this
      phase**, not from this list: phases 1–3 author new comments about the same tiers, so the site set grows
      (§ *a phase that supersedes prose*, [`README.md:363`](README.md)). Read `uc.memory.gallery.md` **end to
      end** including its bilingual block — the panel has **no prose home at all** today (its only EXIF mention
      is the sort-key rule at `:54`), so this is authoring, not only correcting. Shapes:
      **narrowed** — every enumeration of "camera, lens, aperture, ISO, orientation, dimensions" L5 finds, in
      `contracts/api/media.openapi.yaml`, `sharing.openapi.yaml`, `Entities.kt`, `MediaHandler.kt`,
      `MediaStore.kt`, `media.ts`, `MediaLightbox.vue`, `galleryFakeStore.ts` stop enumerating and name the
      tier's *purpose* (a versioned client-only bundle, panel-only), so the next added field needs no sweep;
      **edited** — `V15__media_metadata.sql:9` and `V18__media_meta.sql:5` (already doubly stale);
      **authored** — `uc.memory.gallery.md` main flow + `**EN —**`/`**DE —**` block; the per-photo-name
      distinction from § Context recorded in `uc.memory.place.md` beside the `:90-93` rejection;
      `en.memory.media.md:39`/`:115` filename slice marked landed;
      **extended** — `wiki/en/is-my-data-safe.md:18` and `wiki/de/sind-meine-daten-sicher.md:19`, whose sealed
      list ends at GPS and capture time.
      · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green.

- [ ] **5 coverage** — `uc.memory.gallery.yaml`'s `lightbox-exif-detail` (`:41`) and `uc.memory.upload.yaml`'s
      `seal-and-upload-operator-blind` (`:8`) both enumerate the field set: narrowed, same shape as phase 4.
      New criteria: the panel's file/exposure/provenance/geo rows (gallery), the sealed filename (upload), and
      the **per-photo resolved name** — a new criterion, never a reuse of `sealed-location-on-node`, whose text
      says "**ONE** named place" per node. None of these files carries `status:` or `gaps:` and none may:
      gallery and upload cover both surfaces, so every new criterion names a resolving test.
      · Done when: `python3 scripts/validate_coverage.py` green.

### Deliberately out of scope

- **Timezone from `GPSDateStamp`/`GPSTimeStamp`.** The UTC fix minus the zone-less local `DateTimeOriginal`
  *is* the true offset and would correct travel photos — but `capturedAt` feeds a Memory's `auto` range
  (`memoryDate.ts:85`), so reinterpreting it moves dates on
  existing Memories. Its own CR, with its own migration question. Phase 1 only reads both tags into the lazy
  tier so the follow-up starts with data.
- **MakerNote.** `exifr` returns it undecoded; per-vendor, per-generation tag tables are exiftool-scale.
- **Serial numbers and XMP face regions.** `BodySerialNumber`/`LensSerialNumber` link a photographer's whole
  corpus to one camera, and `mwg-rs:Regions` carries people's **names**. Neither is extracted: the original
  bytes carry them either way, so not reading them is no privacy gain — but *displaying* them normalises them,
  and putting names on faces is a deliberate product decision, not a by-catch of an EXIF sweep.
- **Making rating/keywords act** (sort, filter, auto-cover, search). This CR *shows* them. Search over sealed
  data is a crypto question, not a query one.
- **The group consent state machine and tenant-language names** — deferred with ADR 0016's unbuilt half.

## Bite tests

Each names its kind, and an injection names its lever (§ *the CR says PER bite-test how it will be bitten*).

| # | Claim | Kind | Lever / why it reddens |
|---|---|---|---|
| B1.1 | every new EXIF/XMP/IPTC field reaches the sealed lazy tier | live defect | the fields do not exist on the untouched tree; extend the mocked parse at `media.test.ts:334` |
| B1.2 | the panel renders each new row, and omits the ones whose value is absent | live defect | `metadataRows` has no such rows yet |
| B1.3 | the filename survives `File` → `AddMediaSource` → sealed tier | live defect | `AddMediaSource` has no `name` |
| B1.4 | **the tag names and the option set are real** — a fixture JPEG carrying EXIF + XMP + GPS, parsed by the **un-mocked** `exifr`, yields every tag phase 1 depends on | live defect | neither the fixture nor an un-mocked parse exists. The only test that can catch an *invented* tag; the rest mock `exifr`. Fixture is produced by a `scripts/*.py` helper (reproducible, no third-party photo, no licence question) — it is also `web/`'s first binary test asset, so it lands beside the test with the generator named in a comment |
| B1.5 | the listed tier does **not** grow panel-only fields (cr-0068's size discipline) | injection | move `altitude` into `metaTier` in `sealMetadata` → the assertion that `unsealMeta` carries no altitude goes red |
| B1.6 | the filename is sealed, never sent — `uc.memory.gallery.md:14` stays true | injection | put `name` into the `completeMedia` request body instead of the tier → the assertion that no captured request payload contains the filename goes red |
| B2.1 | **no coordinate leaves the device without consent** (ADR 0016) | injection | pass `geocoderUrl` regardless of `geocodingAvailable` → with consent off, GPS present and the panel opened, the assertion that the injected `fetchImpl` was never called goes red |
| B2.2 | **one lookup per place, not per photo** — the provider's caching obligation | injection | drop the rounding from the cache key → twelve photos differing in the 5th decimal, opened in turn, assert exactly **1** fetch → red (12) |
| B2.3 | sealing the name is read-modify-write; it cannot clobber the date or GPS | injection | replace the spread in `backfillPlaceName` with a fresh `{ v: 1, placeName }` → the assertion that `capturedAtOverride` + GPS survive goes red |
| B2.4 | a tier whose **only** content is a place name is not silently dropped | injection | remove the `placeName` clause from `metaHasContent` → `wrappedMeta` comes back `null` and the assertion goes red. This is the trap the clause exists for |
| B2.5 | a read-only public-share viewer never writes | injection | remove the `props.readonly` check → with consent on and GPS present, the assertion that `updateMediaMeta` was never called goes red |
| B2.6 | the panel shows the resolved name with the coordinate still visible beneath it | live defect | no name row exists |
| B2.7 | **a worst-case listed tier stays inside the 512-base64 cap** — otherwise the shared capture-time PUT breaks too | injection | remove the length clamp on the resolved name → sealing a tier that already holds all nine fields plus a maximal name exceeds 512 and the assertion goes red |
| B3.1 | with consent + GPS the panel renders the map centred on the photo's coordinate | live defect | no map in the panel |
| B3.2 | **without consent no tile is requested** | injection | drop the `mapsAvailable` gate → the assertion that no map element exists goes red |

B1.4 runs first in phase 1: a wrong tag name makes every other phase-1 test a green lie, and the field list
shrinks before any panel work is spent on it.

## Locators

Run them; do not trust a count written here (§ *the CR carries the locator*).

- **L1** — every site building an `AddMediaSource` (each passes the filename):
  `grep -rn "lastModified" web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.'`
- **L2** — the panel's row block and the `media.*` keys feeding it, both locales:
  `grep -n "metadataRows" -A 22 web/src/features/memory/components/MediaLightbox.vue`
  `grep -n "noDetail:\|captured:\|camera:\|lens:" web/src/i18n/index.ts`
- **L3** — the listed tier's write path and its content gate:
  `grep -n "persistMeta\|metaHasContent" web/src/features/memory/media.ts`
- **L4** — the consent gate's existing call sites, which the new panel code matches:
  `grep -rn "mapsAvailable\|geocodingAvailable" web/src | grep -v '\.test\.'`
- **L5** — the claims phase 4 corrects (re-run **at** phase 4, the set grows):
  `grep -rni "camera/lens\|camera, lens\|heavy EXIF\|EXIF bundle\|coordinates" --exclude-dir=node_modules --exclude-dir=.git .`
- **L6** — every `wrappedMeta` cap site, before touching the clamp:
  `grep -rn "maxLength: 512" contracts/api/media.openapi.yaml contracts/api/sharing.openapi.yaml`

## Definition of done

`npm run check` green in `web/`; `for f in scripts/validate_*.py; do python3 "$f"; done` green (the glob, not a
remembered subset); `docs/coverage/` updated for all three `satisfies:` use-cases with every new criterion
carrying a resolving test ref and `python3 scripts/validate_coverage.py` green.

No permanent guard is promoted. The structural invariant this CR rests on — a new listed field must not push the
tier past its cap — is pinned by B2.7 rather than a `validate_*.py`, because the cap is a contract literal the
existing `validate_maxlength_storage.py` already governs and the *payload* side is only decidable at seal time,
in the code the test already drives.

An inbox row is **owed, not written here**: `docs/inbox.md` has no row for photo metadata or the detail panel
(row 47 is explicitly closed except for the group state machine and the gazetteer), and a parallel session was
editing that file when this CR was drafted. The new row cross-references 47 (for the consent boundary and the
"chosen over lazy per-item EXIF fetch" decision this CR narrows) and 16.

## Retrospective

_(filled at close — planning, execution, and process/collaboration, both directions, ending in one concrete
"next time".)_
