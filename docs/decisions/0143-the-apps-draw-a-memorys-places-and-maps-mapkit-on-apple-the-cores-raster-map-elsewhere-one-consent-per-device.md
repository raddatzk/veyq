# ADR 0143 — The apps draw a Memory's places and maps: MapKit on Apple, the core's raster map elsewhere, one consent per device
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows) and in the privacy notice; passed on Android, Linux and
  Windows (§ Consequences); Apple's passes, on the unlocked Mac and an iPhone, are the owner's
  ([native-apps](../crosscutting/native-apps.md) § Open)
- Date: 2026-11-16
- Model: [native-apps](../crosscutting/native-apps.md) (the services and the consent:
  [Memories § Places and the map](../apps/memories.md#places-and-the-map))

## Context

The web curates a Memory's **date** and **place** ([uc.memory.date](../features/memory/use-cases/uc.memory.date.md),
[uc.memory.place](../features/memory/use-cases/uc.memory.place.md)) and draws **maps**: a level's map with every located
Memory as a pin, the photo panel's map, and a Memory's **Map** tab with its photos and **GPX tracks**
([uc.memory.tracks](../features/memory/use-cases/uc.memory.tracks.md),
[ADR 0130](0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md)) — under one consent per browser for the tile
service and the geocoder ([ADR 0016](0016-geocoding-provider-neutrality.md),
[ADR 0018](0018-map-tiles-provider-neutrality.md)). The apps show a medium's place as text and nothing else: no Memory's
date or place, no map, no track.

What the apps have to build on (read 2026-11-16):

- **The services are capabilities, not endpoints.** `memories.map` (a `{z}/{x}/{y}` template and its attribution) and
  `memories.places` (a Nominatim-compatible base) reach a client as a capability's `serviceUrl` and `serviceAttribution`;
  the seeded defaults are OpenStreetMap's standard tile server and public Nominatim. There is no backend proxy and no
  tile or geocoding route. The core's `TenantSession` keeps `serviceUrl` and drops the attribution; offline it reads no
  capabilities.
- **A third party is called through `VeyqAccount.outside`** (no bearer, no cookie); no request of the apps sets a
  `User-Agent`, so Ktor's default goes out. The core has no map, tile, geocoder, GPX or consent code.
- **The providers' policies** (operations.osmfoundation.org, read 2026-11-16): an app identifies itself by a distinct,
  stable `User-Agent` (a library's default is blocked) and needs no `Referer`; tiles honour `Cache-Control` and `ETag`,
  are never fetched ahead of what is viewed and never used offline on `tile.openstreetmap.org`; the attribution is
  visible; Nominatim allows one request a second, results cached by the client, its limits per application.
- **The map libraries a platform could take:** osmdroid is archived; MapLibre Native Android is a GL vector engine
  (an 18.7 MB AAR and three more artifacts) with its own HTTP stack and an on-disk resources cache; libshumate is not in
  the GNOME 51 runtime and its downloader always opens a 100 MB file cache; Mapsui.WinUI pulls six native-backed
  packages and the `WindowsAppSDK` metapackage; WinUI's own `MapControl` takes an Azure Maps token and no tile source.
  **Apple's MapKit** draws a map, polylines and annotations in SwiftUI; its reverse geocoder
  (`MKReverseGeocodingRequest`, `CLGeocoder`'s being deprecated from iOS/macOS 26) answers a city, a region and a full
  address, but no road, suburb, county or state level.
- **No new permission is needed:** Android declares `INTERNET`, the MSIX `internetClient`, the Flatpak shares the
  network, and MapKit asks for none where the app never asks where the device is.

[ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) makes an app's logic the
Kotlin core's; this ADR applies it to the map services, the date, the place and the tracks. The owner chose Apple's own
map on iOS and macOS (2026-11-15) and answered the rest on 2026-11-16 (decisions 2, 3 and 13).

## Decision

1. **Which map each platform draws.**
   - **iOS and macOS: MapKit** — SwiftUI's `Map`: a track as a `MapPolyline` with its activity's dash, the photos' dots
     and Memories' pins as `Annotation`s, the camera read by `onMapCameraChange`. MapKit's attribution and Legal link
     stay where MapKit puts them. No `showsUserLocation` and no location key: the app never asks where the device is.
   - **Android, Linux, Windows: the core's raster map.** The core holds the viewport and everything that decides (`map/`,
     decision 2): which `{z,x,y}` tiles cover a viewport (Web Mercator, 256 px tiles, a fractional zoom drawn from the
     floor level scaled), every overlay point projected to normalised Mercator coordinates, clustering, framing a set of
     points, and the gated fetch of each tile. A platform draws what it is handed — tile images at their places,
     polylines, dots, pins — and reports gestures (drag, pinch, wheel, double-tap, `+`/`−` and the arrow keys): Android a
     Compose `Canvas` (`drawImage`, `drawPath` with a dash `PathEffect`), Linux a `gtk::Widget` whose `snapshot` appends
     textures and a dashed `gsk::Path` stroke, Windows a `Canvas` of `Image`s and `Shapes.Polyline`s (a pin is no
     `Button`). No new dependency on any platform.
   - **Why the core's own map:** the map is a tile pyramid, an affine transform and a few overlays (ADR 0018: "a readable
     basemap, not cartographic polish"); each library brings its own fetch, cache and identity, which would be tamed
     three times, while the core's fetch is the one place the consent, the hosts, the identification and the no-disk rule
     are enforced and tested.
2. **The core owns the map services: the consent, the gate, the tiles, the geocoder** — `core/…/map/`, platform-generic,
   beside `audio/`:
   - **`MapServices`**, per signed-in tenant: whether the server offers a map (`memories.map` served with a template) and
     a geocoder (`memories.places`), their hosts and the tile attribution (`TenantSession` keeps `serviceAttribution`);
     the device's **consent** (`DeviceStore` key `maps-consent`, the `{tiles, places}` hosts as the web records them; per
     device, never per account); `available` = offered and the consent holds for the served hosts; `renewal` (which new
     host asks again, ADR 0016's rule); `turnOn()`, `turnOff()`. A withdrawal removes the key, and every map model
     observing it drops its tiles, cancels what is in flight and clears the geocoder's queue at once.
   - **On Apple the tiles' recipient is Apple Maps**, recorded as that and named in the consent and in the settings in
     place of the tile host (owner, 2026-11-16). **Off means off everywhere:** where `memories.map` is off — by the
     Server Owner or a space's owner, or no address is configured — MapKit draws no map either (owner, 2026-11-16).
     Where MapKit fetches, the core's `available` is what mounts the `Map`.
   - **`MapTiles`**: the tile set for a viewport, fetched only while `available`, only from the served template's host,
     only over `https`, only tiles in view (no ring ahead), through `VeyqAccount.outside` with decision 3's
     identification. A tile's bytes go to the platform as they came (PNG or JPEG); the core decodes nothing.
   - **`Geocoder`**: the port of the web's `geocode.ts` and `placeName.ts` — `reverse(lat, lng, lang) → levels`, the cache
     on the coordinate rounded to three decimals with the language, the queue at least one second apart, no retry, a
     failure resolving to nothing. **On every platform it calls the operator-configured geocoder**, on Apple too (owner,
     2026-11-16); `lang` is the app's language, as the web's is the reader's.
   - **Played in tests:** `PlayedMapServices` counts every request the map path sends (URL, headers) — the instrument
     every consent test reads.
3. **A map request names the app and the instance, never the person.** Every tile and geocoder request the core sends
   carries `User-Agent: veyq/<version> (+https://veyq.app; <instance origin>)` and no `Referer`, cookie or token. The
   instance's origin is what the web's `Referer` already tells the same providers
   ([ADR 0016 § Request discipline](0016-geocoding-provider-neutrality.md#request-discipline--the-providers-policy-is-part-of-the-decision-not-an-optimisation)),
   so a provider's per-application limits and blocks stay per instance, as on the web.
4. **Tiles are kept in memory for the session, and sealed in the offline store across launches** (amended 2026-11-18,
   inbox #939). `MapTiles` keeps a bounded LRU of about 300 tiles with each one's `ETag`/`Last-Modified` and expiry,
   revalidated conditionally once expired, and drops it all at a withdrawal, a lock, a sign-out and a tenant switch
   (`closeTenant()`, where the player's readers close). Where the account keeps an offline store
   ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)),
   each tile served (never a `no-store` one) is also kept there sealed, in its **automatic tier**, per tenant and named by
   the served template and its `z/x/y` (`TileShelf`, `KeptRead.TILE`); a tile not in memory is looked for there first,
   served without a request while fresh, else revalidated with its validators. It counts against the person's limit, is
   evicted least recently viewed first, goes with "Clear cache" and a wipe, is never kept at the limit `0` or where the
   device keeps no trust, and stays through the sweep while its tenant is listed. **The consent is per device, the store
   per account**: a tenant's kept tiles are dropped whenever its map services are not shown for want of the consent or the
   service — this account's at once at a withdrawal, another account's at its next open — and another template's when the
   served one changed. **No kept tile is shown offline, from any host**: the store is read only behind the same gate as a
   request (offered, consented for the hosts served now, online), as `tile.openstreetmap.org` forbids offline use and
   decision 9 draws no map offline; nothing is fetched ahead of what is viewed. Nothing else the map path fetches is
   written to disk (a geocode stays the memory cache's and the sealed name's).
5. **The date and the place are the core's port of the web's** — `core/…/memories/place/`: `MemoryDate` (its four
   modes, `normalize`, `computeAutoRange` over trustworthy capture times) and `MemoryLocation` (`normalize`, the
   200-code-point name, `WORLD_VIEW` and its "no point" reading, `computeMedoid`, `mediaGpsPoints`,
   `isLocationPending`), both sealed into the node's `wrappedMeta` through `NodeMetaWrite` with every other field kept
   verbatim (the cover's path) at `metaRevision`; a `Stale` answer re-applies only its own change
   ([uc.memory.concurrent-curation](../features/memory/use-cases/uc.memory.concurrent-curation.md)), and a date editor
   whose date changed underneath re-opens on the current value, as the web's.
   - **The settle points** (uc.memory.date § How auto is derived, uc.memory.place A2): an upload batch settling, the
     Memory opening (the self-heal) and a medium's trash each reconcile the `auto` range and the `auto` position,
     re-sealed only when changed, and resolve a pending geocoded name where the consent holds.
   - **Shown:** under the Memory's header a facts line — its date range and its place name, each a button to its editor
     where `MemoryRights.editMeta` holds; a Memories row shows the range as its second line. Every list stays ordered by
     last use ([ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md) decision 5):
     the date is shown, not sorted by.
   - **The editors** are the core's models (`DateEditor`, `PlaceEditor`), drawn by each platform: the four modes with the
     platform's date picker (a range with its end before its start refused); the place editor's map with the fixed
     centre pin, a pan flipping `auto` to `fixed`, *Follow the photos*, the level picker from the reverse lookup 700 ms
     after the map settles, the typed name (`manual`), *Remove place*; without a map or without the consent the name field,
     with the consent's offer where the map would stand — no dialog between the member and the name.
   - **Offline** both are shown and neither is edited
     ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
     decision 7).
6. **The Memories map is the bar's Map tab.** It draws every Memory of the application's list (the All tab's set) whose
   place has a position, unsealed on the device from the `wrappedMeta` the tree already holds — no media load, no
   locations endpoint. A pin carries the Memory's cover where its row has one and opens the Memory. The core clusters by
   a grid in Mercator pixels at the shown zoom (the port of the web's `clusterPins`, which needs only the zoom and the
   coordinates, so Apple hands it its camera's zoom); a cluster's tap zooms to fit its pins and at the maximum zoom lists
   them. Linux and Windows gain Memories' bar tabs.
7. **The photo panel's map and the photo's place name.** A photo with GPS shows in its details a small map at zoom 15
   centred on it, with the direction needle where the EXIF gives one, and *Turn map services off* beside its
   attribution; the coordinate row stays. The panel resolves the photo's name once ever and seals it into its listed
   tier (`placeName`, `placeLevel`) under the web's gates (a coordinate and no name, the consent, a geocoder, not while
   the slideshow runs, the medium's meta door), through the raw read-modify-write `TrackLabel` already does, every other
   field verbatim.
8. **The Map view and its tracks.** The Memory screen's view switch becomes **Photos | Music | Map** (Music where ADR 0142
   shows it; Map always, as the web's tab is): the map with the photos' dots and the node's own tracks (ADR 0130
   decisions 3–5), the list under it — the show/hide switch, the colour swatch with the activity's dash, the activity
   above the name, distance, duration, climb, descent — and per track **Edit**, **Download GPX** (the gallery's save),
   **Remove** (the gallery's trash with Undo) and **Report** (`MediumReport`'s `ITEM`). **Add GPX track** where `upload`
   holds, through the platform's document picker filtered to `.gpx`, each accepted file's name and activity asked before
   anything is uploaded and sealed at birth through `MediaUploads`' facts; *Cancel* uploads nothing.
   - **`core/…/memories/tracks/`**: the port of the web's `gpx.ts` over a **minimal XML reader of the core's own** — a
     document type declaration refused before anything is read, so only the five predefined entities and character
     references exist; elements, attributes, text, CDATA, comments and processing instructions; `trk/trkseg/trkpt`, else
     `rte/rtept`, with `ele` and `time` — plus the track's numbers, the line's simplification, the name, the colours and
     ADR 0130's bounds (25 MiB, at least two points, 50 per node, 2,000 drawn per segment), each refusal its own word
     naming the file. `TrackLabel` gains `trackActivity` (ADR 0130's ten tokens; an unknown one read as none and kept
     until edited) and the conflict rule of ADR 0130 decision 9.
   - **The tracks are read only while the Map view shows**, one at a time, kept parsed while the page stays on its node.
9. **Offline: no map; the tracks' list from kept originals.**
   - **No map is drawn offline**, on any platform, MapKit included: offline the capabilities are not read, so the app
     does not know which hosts the server serves now, and a consent holds only for the hosts it names; tiles are never
     kept (decision 4), and `tile.openstreetmap.org` permits no offline use. The map's place says the map needs a
     connection. **An account that loses its server mid-session loses its map too**, though the capabilities were read
     before: `MapServices` follows the account's reach, offline from the moment the server is lost (its tiles dropped as
     at a withdrawal) and shown again, unasked, once it is back (`MapConsentTest`; found by the Android pass, below).
   - **A track's original is kept like a video's** (ADR 0140 decision 8: an original is kept where the app reads one to
     show the item): a pin keeps the node's tracks, and offline the Map view lists them with their numbers, download
     included.
10. **What each map service learns** — the rows [security-model](../crosscutting/security-model.md) carries under its
    third-party boundary:

    | Service | Learns | Never learns |
    |---|---|---|
    | The tile host (Android, Linux, Windows; the web) | the tiles in view `{z,x,y}`, the device's IP address, `veyq` and the instance's origin (decision 3) | a track's points, a pin, a photo's coordinate, a name, any veyq id or token |
    | Apple Maps (iOS, macOS) | the region in view (as tiles), the device's IP address, and what MapKit sends of its own (Apple documents rotating random identifiers, not the Apple Account — remembered, not measurable through TLS) | the same as the tile host's column: the overlays are drawn by MapKit on the device (remembered from Apple's documentation; a residual of a closed library) |
    | The geocoder (every platform) | a bare coordinate, the language, the device's IP address, `veyq` and the instance's origin | which Memory, whose, or any veyq id |
    | The veyq operator | nothing more than today: a sealed `wrappedMeta`, a sealed listed tier rewritten, one more medium without tiers | a coordinate, a viewport, a consent, a place name, that a Memory holds a track |
11. **The bridge (Linux, Windows).** `Watched.Map(map)` → `MapShown`: the tiles in view as `{tile, x, y, size}` (`tile`
    its `z/x/y`) in normalised Mercator space, the overlays (tracks with colour, dash and selection, dots, pins with cover ids,
    clusters), the attribution text, and the state `notOffered` | `consent(hosts, renewal)` | `shown` | `offline`.
    `Map.View(map, centre, zoom, width, height, scale)` comes from the host as it settles and at most every 100 ms during
    a gesture; `Map.Tile(map, tile)` answers `Answer.Bytes`
    ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)), the
    host translating its last frame by the drag meanwhile; `Map.TurnOn|TurnOff`; and the editors' and the Map view's
    calls (`Place.*`, `Date.*`, `Tracks.Add(paths)|Edit|Remove|Download|Report|Show`). Host-side the transform from
    normalised coordinates to pixels is the viewport's one affine formula; nothing else is computed there. `MapShown` is
    under `SecretFreeSchemaTest`, its attribution and hosts in named fields; a map closes with its page and its tenant.
12. **No new permission, no new dependency.** Every platform already reaches the network; MapKit needs no entitlement on
    iOS or on the unsandboxed macOS app, and nothing asks for the device's location. If a MapKit map on the macOS build
    turns out to need the Maps entitlement, it goes through the store's validator before it is added.
13. **The privacy notice names the app and Apple Maps.** The instance notice's `browser-services` passage says "your
    browser or the veyq app", and gains a line, rendered exactly while `memories.map` is active for the host's space,
    that the veyq app on iPhone, iPad and Mac shows maps from Apple Maps (Apple Inc., United States) instead, which then
    sees the area shown and the device's IP address (the owner's text, 2026-11-16, EN and DE). A recipient the notice
    does not name is the cheap safe side to close now; the rest of the notice's "your browser" wording predates the apps
    and is inbox #942.

## Consequences

- **The operator's view is unchanged** (decision 10's last row): a date, a place, a coordinate, a place name and a track
  stay sealed under the node's or the medium's key; nothing map-related reaches the backend, and the contracts gain no
  operation — the notice's text (decision 13) is the one contract change.
- **A third-party request from an app identifies itself by `User-Agent`**, where the web's does by `Referer`; both name
  the instance's origin and neither the person. [ADR 0016](0016-geocoding-provider-neutrality.md)'s consent "per
  browser" is, in the apps, one per device; its rules (the hosts named, a new host asked again, a withdrawal at once)
  are the same and held by the core.
- **Apple is a recipient on iOS and macOS.** The consent there names Apple Maps for the map and the operator's geocoder
  for place names; the notice names Apple (decision 13). What MapKit keeps on disk is Apple's, named in
  [`src/mobile/CLAUDE.md`](../../src/mobile/CLAUDE.md) § Nothing on disk.
- **Measured 2026-11-16 on Android, as its map was built: the Compose map's pan and pinch, frame time recorded.** A throwaway API 36
  emulator (Pixel 8 profile, `-gpu host`, 60 Hz panel; `dumpsys display`: `renderFrameRate 60.0`) on the developer's Mac,
  the Memories bar's Map tab over Lisbon at zoom 16 with real tiles from `tile.openstreetmap.org` after the consent was
  given in the app: a scripted gesture injected as raw multi-touch events at 60 Hz (`adb exec-in` into the emulator's
  `virtio_input_multi_touch` device, paced from the Mac) — four one-finger pans and a pinch out by 5× and back by 3.3× —
  crossing levels 16 to 18 and drawing 32 distinct tiles (25–32 per run, counted by a log line kept for the run only).
  `dumpsys gfxinfo` over the gesture, two runs at a 1-minute load of 5–6 on the 14-core host: 267 and 279 frames, **50th
  percentile 32 / 27 ms, 90th 61 / 42 ms, 99th 105 / 89 ms**, 38 % / 30 % janky, no slow bitmap upload (tiles are
  decoded as `HARDWARE` bitmaps off the main thread). **The same emulator drags a plain list** (Home, the same 60 Hz
  injected drags) at 50th 32 ms, 90th 61 ms, 99th 150 ms, 20 % janky: it holds no 60 Hz frame budget for any screen, so
  "without a dropped frame" is not observable here, and the map's frame time is the list's at the median with a longer
  tail where a pinch crosses a level. Two changes the measurement bought: the tiles are asked from a snapshot flow and
  read only in the draw phase (a moving view redraws the canvas, nothing recomposes), and a level's stand-in tiles are
  drawn only where a tile is still missing (no overdraw once settled). Under a host load of 20+ the same gesture fell to
  34–69 frames, so a number from a busy machine says nothing; a phone's GPU profile bars remain the owner's device pass
  (below). Decision 1's Compose `Canvas` stands: no library would change the emulator's budget, and the median matches a list.
- **Measured 2026-11-16 on macOS 26.7, before the Apple map was built:** a probe built as the Mac app is —
  unsandboxed, ad-hoc signed (`codesign -s -`), **no entitlement at all** (`codesign -d --entitlements -` empty) — ran
  headless from its binary while the screen was locked; `MKMapSnapshotter` answered a drawn map of Munich and one of
  El Chaltén (a place no earlier map on this Mac had shown) within a second each, so a MapKit map loads without a Maps
  entitlement (decision 12 holds; nothing is added). Its `MKMapView` in a window reported neither loaded nor failed
  while the screen was locked: a window's map is drawn only on an unlocked screen. What the run left, listed by
  `find … -newer <marker>` over `~/Library/{Caches,HTTPStorages,Preferences,Application Support,Saved Application State}`
  and the user's `DARWIN_USER_CACHE_DIR`/`TEMP_DIR`: **in the app's own places only Metal's compiled shaders**
  (`$DARWIN_USER_CACHE_DIR/<bundle id>/com.apple.metal/…`: shader functions, no map data) — no `~/Library/Caches/<bundle
  id>`, no `HTTPStorages`, no preferences. **Outside the app, Apple's map daemons wrote**: `~/Library/Caches/GeoServices/`
  (`ResourceManifest.pbd`, `DirectReadConfigStore.plist` and regional icon packs — Budapest's and Italy's transit icons,
  none of the regions shown), `~/Library/Caches/com.apple.geoanalyticsd/APDB.db-wal` (Apple's map analytics database,
  3 MB, written at the run), and `geod`'s tile cache lives in its own container, `~/Library/Containers/com.apple.geod/`,
  which macOS's privacy protection kept from being listed (the listing waited on a permission no one could grant).
  **Residual (Apple's, of a closed system service):** the tiles of the region a member viewed may stay in `geod`'s cache,
  shared with every app and Apple Maps itself, at rest outside the app, which the app can neither list nor clear — the
  record of places that decision 4 keeps off disk on the other platforms; and `geoanalyticsd` records map use under
  Apple's own analytics settings. iOS was not measured (its simulators do not boot on this Mac; a device's container is
  the owner's pass, below).
- **Measured 2026-11-16 on Linux and Windows, before their maps were drawn: a dash draws as a dash.** A mountain-biking
  track's pattern (10, 5, 2, 5) was stroked dash-dot by `gsk::Stroke::set_dash` in the Linux container's GTK 4.22 (under
  Broadway) and by a `Polyline`'s `StrokeDashArray` on the Windows VM, so neither draws the dash as segments.
- **The passes, run 2026-11-16** against the dev instance (`veyq.localhost:8080`) with the seeded OSM services
  (`tile.openstreetmap.org`, `nominatim.openstreetmap.org`), the seeded principal signed in by each platform's devlogin
  helper, over Memories of three geotagged photos in Munich made for the pass (moved to the trash after it; every
  session a pass signed in was signed out, `DevSecondSessionTest.aLeftOverSessionIsSignedOut`).
  - **Android** — a throwaway API 36 emulator (deleted after), the debug build, driven over `uiautomator dump`. The request
    log is the debug build's `DevNetLog`: an OkHttp network interceptor that writes every request to a host outside the
    developer's instance to logcat `veyq-net` as it leaves — its URL, the `User-Agent` sent, and whether a `Referer`, a
    `Cookie` or an `Authorization` went with it (presence only), read with `adb logcat -v time -s veyq-net:I`. **The whole
    pass: 89 requests, 87 tiles from `tile.openstreetmap.org` and 2 reverse geocodes from `nominatim.openstreetmap.org`,
    every one `ua=[veyq/1 (+https://veyq.app; http://veyq.localhost:8080)] referer=no cookie=no authorization=no -> 200`;
    no other host; no line before the consent was given.** A geocode left as
    `GET https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=48.139&lon=11.58&accept-language=en`
    — the three-decimal coordinate and the language alone. Clicked: each Memory's date following its upload; the consent
    in context naming both hosts; a place set and a level picked (*Old Town*), and a second Memory's lookup answered from
    the cache (the same three-decimal spot); the Map tab's pin and a cluster of two Memories 7 m apart, zoomed by its tap
    to the maximum and there listed ("2 Memories here"); a photo's map at zoom 15 with its direction and its name, and
    after the app was reinstalled and signed in again the same photo's `Place: Munich` at once with **0 geocodes** (the
    sealed listed tier); a GPX track added as *Mountain biking*, drawn dash-dot, selected (framed), hidden and shown,
    removed and restored by Undo; *Turn map services off* on the Map view — the offer in the map's place within a second,
    the log unchanged over the next 10 s, `maps-consent` gone from `shared_prefs/veyq.trust.xml`; Settings → Map services
    naming both hosts and turned on without sending anything; offline (`adb shell cmd connectivity airplane-mode enable`)
    a pinned Memory's Map view saying "The map needs a connection." above its listed track, and the Memories map the
    same, back unasked once online; the app's files afterwards (`run-as me.raddatz.veyq find . -type f`) holding no tile,
    no geocoder answer and no viewport.
  - **Linux** — the container, under Broadway, driven from headless Chrome. A build has no request log, so a line in
    `MapHttp.get` for the pass only (never committed) printed each request as Ktor sent it: **148 requests, all `200` —
    140 tiles and 8 geocodes, each with `User-Agent: veyq/1 (+https://veyq.app; http://veyq.localhost:8080)`;
    `grep -ciE 'referer|cookie|authoriz|bearer'` over them 0; 0 before the consent.** Clicked: the consent in context;
    a place set by two bursts of arrow-key pans, which sent **two geocodes, each at its burst's final position**, none
    for the twelve positions panned past; *Waldperlach* picked and saved; the Map tab's clusters ("3" and "2"), one framed
    by a tap and listed at the maximum zoom, a Memory opened from the list; a photo's map and its name, and after a
    restart **0 geocodes** for it; a photo never opened before asking once, and nothing on its second open; a GPX track
    through GTK's file dialog, edited to dash-dot, hidden, selected (framed), downloaded (`sha256sum` equal to the file
    added), removed and restored by Undo; *Turn map services off* on the Map view dropping every tile in the same update,
    nothing sent after it; a pinned Memory started cold offline, and one whose server went away mid-session (after the fix
    below), each saying "The map needs a connection." above its listed track with 0 map requests.
  - **Windows** — the ARM VM, the debug build, driven over UI Automation. Clicked: *Turn map services off* on the Map tab
    (the offer naming both hosts in the map's place 22 ms later) and on again from it; the Map tab's three pins
    clustering to one "3" at zoom 7, framed by its click; a place set by arrow-key pans, the picker listing the final
    spot's five levels, *Alexisquartier* picked and saved; a photo's map at zoom 15 and its name resolved once, read
    after a restart from the sealed listed tier with no lookup; a GPX track through the open picker, drawn dotted
    (*Running*), selected (framed), hidden and shown, downloaded (`Get-FileHash` equal), removed and restored by Undo;
    *Turn map services off* on the Map view (42 ms) and on again; a Memory made available offline.
  - **Found and fixed by the passes.** An app that lost its server mid-session went on drawing the map (Android and
    Linux): decision 9 now says why it must not, and `MapConsentTest` was red before the fix. Linux's level picker kept the
    first spot's names after a pan to a spot with the same levels (its test in `memory_facts.rs` red before). The
    geocoder sent each superseded pan's lookup a second apart before the last one (Linux and Windows): a lookup no caller
    waits for any more is now dropped unsent (`GeocoderTest`, `PlaceEditorTest`). A zoom without a pan asks nothing new,
    on purpose: the lookup is always made at `zoom=18`, and its one answer holds every level the picker offers.
  - **Not run.** **Apple's passes**: the Mac's screen locked during both attempts, so nothing was clicked on the Mac,
    and no iOS simulator boots on this Mac (CoreSimulator out of date), so iOS was neither clicked nor its on-disk listing
    taken — the owner's, on the unlocked Mac and an iPhone ([native-apps](../crosscutting/native-apps.md) § Open).
    **Windows offline**: the VM reaches the backend through a standing ssh tunnel a pass may not stop, and cutting the VM
    off is a firewall change, the owner's; offline is the core's `MapServices`, the code Linux ran. **A request log on
    Windows and Linux builds**: Windows' standard output goes nowhere, and Linux's line was the pass's own (built since,
    inbox #947, below).
    Android's *Download GPX* and *Report* of a track (seen in its menu; Linux and Windows downloaded); a phone's 60 Hz
    frame budget (the owner's device pass).
  - **Seen, fixed since**: the raster map first fetched zoom 2's four world tiles before the framed view (#945, 2026-11-18,
    below). Seen and fixed since (2026-11-18): Android's add buttons were nameless in the accessibility tree, Android's *Map
    services on this device* toggled only from its switch, and Windows named a cluster marker for one of its Memories
    (now "3 Memories here", `map_cluster_title`).
- **A tile survives a launch, sealed** (decision 4 as amended 2026-11-18, inbox #939): the tile policy's "sufficient local
  cache" is the store's automatic tier, under the person's limit; what it costs is ADR 0140's own residual — a thief who
  opens the store reads which tiles were viewed, as they read the places they are tiles of. Measured by `MapTileShelfTest`
  (a tile viewed in one launch served in the next without a request; counted, kept by the sweep, dropped by Clear cache,
  not kept at the limit `0`; a withdrawal dropping it) and `MapTilesTest` (the store read only behind the gate, a
  `no-store` tile never kept). **Apple**: MapKit's tiles are `geod`'s cache, not the app's (above); nothing changes there.
- **A map opens on its framing, never on the world first** (inbox #945, seen in the Android pass): the Map view and the
  Memories map show no view and ask for no tile while what they frame is read (`MemoryLocation.opening`,
  `TrackList.State.framed`; the bridge's `HeldMap` and Android's `MapCamera.opened`), then open framed; the world view is
  only where there is nothing to frame. `MapDispatchTest`'s `theMapViewOpensOnItsFramingNeverOnTheWorldFirst` was red
  before (a view and zoom 2's tiles placed while the tracks read). Measured 2026-11-18 on a throwaway API 36 emulator (its `veyq-net` log): a
  Memory's Map view with one GPX track asked its nine framed tiles at zoom 16 and none at zoom 2; the Memories map of a
  list with no located Memory framed the world, its eight zoom-2 tiles being its framing. That a tile survives a launch was
  not run live: it needs a device that keeps a trust, and the dev principal is at the server's device limit (`add wrap
  409`, on Linux the same day), so the tests above hold it.
- **A request log in the desktops' debug builds** (inbox #947): Android's `DevNetLog` has its Linux and Windows twin — the
  host's `Config.netLog`, set by a debug build alone, installs a Ktor plugin on every client the core makes, and each
  request to a host outside `*.localhost` is one `Net.Log` line (the address, the `User-Agent` sent, whether a `Referer`,
  cookie or `Authorization` went, the status; a presigned query said as `<presigned>`) on Linux's stderr and in Windows'
  `log.txt`, as `veyq-net …` (`NetLogTest`).
- **Offline there is no map** (decision 9): a pinned Memory's Map view lists its tracks and nothing is drawn under them.
  ADR 0140 decision 8's kept originals gain a GPX track's.
- **A Memory's date and place stay current wherever it is curated:** today an app's upload leaves a web-made `auto` range
  stale until a browser opens the Memory; with decision 5's settle points it does not.
- **Not decided here:** a Memory's participants, a medium's capture-time correction and the banner (since built by ADR 0144); the
  place in the apps' creation dialog (since built as the web's opt-in, uc.memory.place A1 — the web asks no date there); a person's profile map, the apps having no profile page
  (then #941; both since decided in [ADR 0144](0144-the-apps-curate-a-memorys-participants-a-persons-profile-a-mediums-capture-time-and-the-banner-the-webs-rules-in-the-core.md)); the notice's other "your browser" wording (#942).

## Alternatives considered

- **MapLibre Native on Android** — a GL vector engine of 18.7 MB plus three artifacts for the raster tiles ADR 0018
  chose over vector, its own HTTP stack and on-disk resources cache, and a consent gate each UI would have to hold around
  a library that fetches on its own.
- **osmdroid** — archived, which the dependency rule forbids.
- **libshumate on Linux** — not in the GNOME 51 runtime, so a bundled module, and its downloader always keeps a 100 MB
  tile cache on disk, a record of the places a member looked at.
- **Mapsui on Windows** — a rendering stack of six native-backed packages and the `WindowsAppSDK` metapackage beside the
  app's split packages.
- **WinUI's `MapControl`** — Azure Maps by token only, a commercial service with no tile source.
- **A WebView running the web's Leaflet** — a browser engine inside each app (WebView2 on Windows, WebKitGTK on Linux,
  neither in the app today) for one view, with its own cache to tame, against
  [ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)'s native UI.
- **MapKit with the operator's tiles on Apple** (`MKTileOverlay`, `canReplaceMapContent`) — the owner chose Apple's map;
  it stays the change to make should Apple ever have to stop being a recipient.
- **Apple's geocoder on Apple** (`MKReverseGeocodingRequest`) — it has no road, suburb, county or state level, so the
  level picker could not offer what it offers everywhere else; a space would carry names from two gazetteers; the
  operator's switch for place names would not hold there; and Apple would also learn every coordinate being named.
- **A consent per account** — ADR 0016 makes it the device's; an account switch must not change what the device sends.
- **Each platform's own gate** — five places to forget one; the core's fetch is the gate.
- **`User-Agent: veyq/<version>` alone** — every instance's apps would be one application to Nominatim's per-application
  limit, and one misbehaving instance would have every veyq app blocked.
- **A synthetic `Referer`** — the policy asks apps for a `User-Agent`, and a header pretending to be a page is what it
  calls impersonation.
- **A plaintext tile cache on disk** — the tiles a member viewed are a map of where their Memories are, which the sealed
  places exist to hide from whoever holds the device; the app keeps nothing at rest it did not choose.
- **Tiles in the offline store served offline** — `tile.openstreetmap.org` forbids offline use (decision 4: read only
  behind the gate). **A device-wide tile cache beside the accounts' stores** — a key no account holds, so either plaintext
  or a second sealed store with its own key; the per-account store is what exists, and the consent's per-device reach is
  kept by dropping a tenant's tiles where the consent does not hold (decision 4).
- **Sorting the Memories list by start date**, as the web's grid does — ADR 0122 orders every list by use.
- **The date and the place in the creation dialog now** — editing them right after creating does the same, and the
  dialog is five platforms' forms (since built: the place's opt-in, as the web's; the web asks no date).
- **A map view on Home's levels**, as the web's level toggle — Home is core's and lists every application's folders;
  Memories' map belongs to its bar (ADR 0122 decision 4).
- **Spiderfying a cluster** — a Leaflet gesture no platform's map has.
- **A name per open of a photo** — ADR 0016's discipline: once per place, sealed.
- **An XML library** (`xmlutil` or a platform's parser) — a dependency on five targets, or a platform deciding a rule
  (ADR 0136), for three elements; the core already reads XMP itself.
- **Drawing the tracks over a blank plane offline** — a map without its map, a second look to design on five platforms
  for a state a connection ends.

Explained for users on the docs site: [Set the location](../../site/src/content/docs/memories/set-the-location.mdx) and
[Add GPX tracks](../../site/src/content/docs/memories/add-gpx-tracks.mdx) (§ In the app),
[Is my data safe?](../../site/src/content/docs/privacy/is-my-data-safe.md) (§ Maps, only when you turn them on) and [Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx) (a viewed tile kept); for
Server Owners [Capabilities](../../site/src/content/docs/run/capabilities.mdx) (§ External services); for researchers
the [threat model](../../site/src/content/docs/security/threat-model.md) (the third-party row).
