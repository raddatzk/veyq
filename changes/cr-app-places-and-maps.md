---
id: cr-app-places-and-maps
title: A Memory's date, place and maps in the apps — the place and date editors, the Memories map, the photo panel's map, the Map view with its GPX tracks, one map consent per device; MapKit on Apple, the core's own raster map elsewhere
status: planned
satisfies: []
adrs: ["0016", "0018", "0130", "0136", "0137", "0140", "0142", "0122", "0123", "0077", "0088"]
invariants:
  - "operator-blind E2EE: a date, a place, a coordinate, a place name and a track stay sealed under the node's or the medium's key; nothing map-related reaches the veyq backend, and the contracts gain no operation (ADR 0016, ADR 0018, ADR 0130)"
  - "no request to a map service leaves a device before its member's consent for exactly the hosts it names, and none after a withdrawal; the consent is the device's, never an account's, an owner's or a group's (ADR 0016)"
  - "a track's points, a photo's coordinate on the map and a Memory's pin never leave the device: they are drawn locally over the map; a tile request carries the viewport, the geocoder a bare coordinate (ADR 0018, ADR 0130 decision 6)"
  - "every rule is the core's (ADR 0136): the consent, the hosts, which tiles, the request discipline, the medoid, the date's auto range, the GPX reading and its bounds; a platform draws images and lines it is handed and reports gestures"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): no tile, no geocoder answer and no viewport is written by the core's map path; what MapKit keeps on Apple is measured and named"
  - "the bridge carries tiles as bytes beside the JSON (ADR 0137), never a key, a seal, a token or a service address outside a named field (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
---

## Goal

The web curates a Memory's **date** and **place** ([uc.memory.date](../docs/features/memory/use-cases/uc.memory.date.md),
[uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md)) and draws **maps**: a level's map with every
located Memory as a pin, the photo panel's map, and a Memory's **Map** tab with its photos and **GPX tracks**
([uc.memory.tracks](../docs/features/memory/use-cases/uc.memory.tracks.md), ADR 0130) — all under **one consent per
device** for the tile service and the geocoder (ADR 0016, ADR 0018). The apps show a medium's place as text and nothing
else: no Memory's date or place, no map, no track (inbox #890, #907).

On Android, iOS, macOS, Linux and Windows the apps then: show a Memory's date and place under its header and on its row,
and edit both where the viewer may write its meta (the web's four date modes; the place editor with its map, its centre
pin, the level picker and the typed name); keep the date's `auto` range and the place's `auto` position current at the
web's settle points; show every Memory with a place on the Memories bar's **Map** tab; show a photo's position on a small
map in its details, naming the place once; show a Memory's **Map** view beside Photos and Music — its photos' dots, its
tracks, the list under them with each track's numbers, adding a track with its name and activity, editing, downloading,
removing and reporting one; and ask, give and withdraw the map consent in context, on every map and under Settings →
**Map services**.

**Map services, native at first on Apple** (owner, 2026-11-15): iOS and macOS draw **MapKit**; Android, Linux and Windows
draw the operator's third-party tile source as the web does (ADR 0018), through a **raster map the core runs and each
platform draws** (D1). A track's points never leave the device on any platform.

## Context & constraints

- **Read first:** ADR 0016 and ADR 0018 (all: the consent, its hosts, the request discipline, third-party only), ADR 0130
  (all: a track is a medium), ADR 0136 (the logic is the core's), ADR 0137 (bytes beside the JSON), ADR 0140 decisions 7–9
  (offline: read-only, kept originals, pins), ADR 0142 (the Memory screen's view switch, `TrackLabel`, `MediumReport`, the
  shape this CR follows), ADR 0122 decisions 4–5 (the bar is the application's; every list by last use);
  [Memories § Places and the map](../docs/apps/memories.md#places-and-the-map),
  [security-model § third-party services](../docs/crosscutting/security-model.md),
  [uc.memory.concurrent-curation](../docs/features/memory/use-cases/uc.memory.concurrent-curation.md); `src/mobile/CLAUDE.md`
  whole (§ Nothing on disk, § Each platform's guidelines, § Traps).
- **The web is the reference implementation** (R1–R6); its tests are the list the core's tests mirror (R14). Where this CR
  differs from the web it says so and why (D1's renderer, D3's identification, D7's level map, D9's offline map).
- **Where what runs:** core and bridge tests on the JVM and macosArm64 (`./gradlew :core:jvmTest :bridge:jvmTest
  :core:macosArm64Test` from `src/mobile/`); Linux in its container (`/usr/bin/python3 src/mobile/linux/run.py test`),
  Windows on the VM (`/usr/bin/python3 src/mobile/windows/run.py test`); live tests with `VEYQ_LIVE=http://veyq.localhost:8080`
  as `LiveOfflineTest` does. No live test calls a real tile server or Nominatim: the core's map client is played (D2's
  `PlayedMapServices`); only the passes (phase 9) load real tiles, a handful per pass, inside the providers' policies (R12).
- **Out of scope** (inbox rows, § Inbox rows): a Memory's participants, a medium's capture-time correction and the banner
  (#890, which keeps them); the creation dialog's date and place (uc.memory.place A1, #940); a person's profile map — the
  apps have no profile page (#941); a sealed tile cache across launches (#939); the privacy notice's "your browser"
  wording outside the map passage (#942). Nothing in the backend or the web changes; the contracts change in one fixed
  text only (D13).

## Measured at refine (2026-11-16)

Branch `claude/refine-maps` at `e08de2b51` (cut from `claude/windows-cr-f6c73c`), clean. Commands from the repo root
unless they start in `src/mobile/`; `W=src/web/src/features/memories`, `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.
Facts about outside libraries and policies were read from the artifacts or pages named, on the date above.

| # | What | Where / command |
|---|---|---|
| R1 | **The web draws raster tiles with Leaflet 1.9** (`L.tileLayer(tileUrl, {maxZoom})`, max zoom 19), its attribution built from DOM nodes (Trusted Types refuse Leaflet's); tracks are `L.polyline` per segment, weight 4 (6 selected), `dashArray` from the activity; colours by list index (`TRACK_COLORS`, six); pins clustered in pixels and spiderfied (`clusterPins`). | `grep -n '"leaflet"' src/web/package.json`; `grep -n 'tileLayer\|L.polyline\|dashArray' $W/components/MemoryMap.vue $W/components/SpaceMap.vue`; `grep -n '^export' $W/spaceMap.ts $W/mapAttribution.ts $W/track/gpx.ts` |
| R2 | **The services are capabilities, not endpoints.** `memories.map` (a `{z}/{x}/{y}` template) and `memories.places` (a Nominatim-compatible base) reach a client as `Capability.serviceUrl` and `serviceAttribution` (`getSetupStatus`, `getTenantCapabilities`); the seeded defaults are `https://tile.openstreetmap.org/{z}/{x}/{y}.png` with `© OpenStreetMap contributors` and `https://nominatim.openstreetmap.org`. Off (switched off or no address) means not served. No backend proxy, no tile or geocoding route. | `grep -n 'serviceUrl\|serviceAttribution' contracts/api/_common.openapi.yaml`; `grep -n "memories.map'\|memories.places'" src/backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql`; `grep -rlniE 'nominatim\|geocod\|tileUrl' --include='*.kt' src/backend \| grep -v /test/` |
| R3 | **The web's consent** is `localStorage['veyq:maps-consent']` = `{tiles: host\|null, places: host\|null}`; it holds only while those are the served hosts (`consentRenewal`), off by default, one switch for both; asked in context by `MapConsentPrompt.vue` (in `SpaceMap`, `MemoryLocationEditor`, `MemoryTracks`), in Settings by `MapsPanel.vue`, withdrawn by `MapOffButton.vue` on every map. Its words are `memory.place.consent*`, `mapOff`, `mapsNotOffered` and `settings.maps*` (EN+DE); `settings.mapsSub` and `mapsSwitch` say "browser". | `grep -n 'CONSENT_KEY\|export function' src/web/src/shared/state/useMapConfig.ts`; `grep -rln 'MapConsentPrompt\|MapOffButton' $W/components`; `grep -n 'mapsSub\|mapsSwitch:' src/web/src/i18n/index.ts` |
| R4 | **The web's geocoder** is reverse only, browser-direct: `GET {base}/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=&lon=&accept-language=` with `Accept: application/json` alone; levels road … country (`nominatimLevels`, `defaultLevel`); the per-photo resolver caches on the coordinate rounded to 3 decimals plus the language, serialised ≥ 1 s apart, no retry. Its identification is the `Referer` the app shell's `Referrer-Policy` keeps (ADR 0016 § Request discipline). | `grep -n 'reverse?\|export' $W/geocode.ts $W/placeName.ts` |
| R5 | **The web's place and date.** Both live in the node's sealed `wrappedMeta` (`node-meta` context) beside the cover, written read-modify-write at `metaRevision`. `MemoryLocation {name, nameSource, derived?, derivedMode, nameAnchor?}`, `MAX_PLACE_NAME` 200, `WORLD_VIEW`, `computeMedoid`, `mediaGpsPoints`, `isLocationPending`; `MemoryDate {mode auto\|single\|range\|ongoing, from?, to?}`, `computeAutoRange` (trustworthy capture times only), `reconcileMemoryDate`. The place editor pans the map under a fixed centre pin (no drag, no search); a pan flips `auto` to `fixed`. | `grep -n '^export' $W/memoryLocation.ts $W/memoryDate.ts`; `grep -n 'followAgain\|pickLevel\|onNameInput\|remove' $W/components/MemoryLocationEditor.vue` |
| R6 | **The web's tracks.** `track/gpx.ts`: own reader over the browser's XML parser behind `parseInertXml`, DOCTYPE refused, `trk/trkseg/trkpt` else `rte/rtept`, 25 MiB, ≥ 2 points, 50 per node, 2,000 drawn points per segment; name and activity in the listed tier (`trackName`, `trackActivity`, ten activities, `activityDash`); added with its label sealed at birth through the queue's `facts`. | `grep -n '^export' $W/track/*.ts`; `grep -n 'trackName\|trackActivity' $W/media.ts` |
| R7 | **The apps show a medium's place as text and nothing more.** `MediaDetails` adds `PLACE` (`meta.placeName`) and `LOCATION` (`"lat, lng"`, 6 decimals); `MemoryMedia.Meta` carries `gpsLat`, `gpsLng`, `placeName`, `placeLevel`. No Memory-level date or place is read: `NodeRef.wrappedMeta` is held and `NodeMetaWrite` (`openForWrite`, `seal`, `write` with its `Stale` answer) is used by the cover only. | `grep -n 'LOCATION\|PLACE' $C/memories/MediaDetails.kt`; `grep -n 'fun \|class ' $C/session/NodeMetaWrite.kt`; `grep -rn '"date"\|"location"' $C/memories $C/session` → none |
| R8 | **A Map tab exists and draws nothing of its own.** `APPLICATION_SHAPES` gives Memories `memories.all` and `memories.map` (`ShellTest` asserts both); Android and Apple draw the tab and the same list under it; Linux and Windows draw no Memories tabs. Every list is ordered by last use (`arrange`, ADR 0122 decision 5). The Memory screen's view switch is Photos \| Music (`MemoryView`, ADR 0142 decision 6). | `grep -n 'memories.map' $C/navigation/Applications.kt`; `grep -n 'MemoriesList' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/ShellScreen.kt`; `grep -n 'fun arrange' -A3 $C/navigation/Lists.kt`; `grep -n 'enum class MemoryView' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/MusicScreen.kt` |
| R9 | **The core has the pieces a map needs and no map.** `TenantSession.serviceUrl(id)` keeps `serviceUrl` and drops `serviceAttribution`; offline no capabilities are read. A third party is called through `VeyqAccount.outside` (no bearer, no cookie; Expenses' rates use it, keeping a day's table in the `DeviceStore`). No request sets a `User-Agent` (Ktor's default goes out). `GalleryMedium.isTrack` only hides tracks; `TrackLabel` rewrites `trackName` in the raw tier and knows no `trackActivity`; `MediumReport` reports an `ITEM`. No map, tile, geocoder, GPX or consent code exists. | `grep -n 'val services = \|fun serviceUrl' $C/session/Account.kt`; `grep -n 'fun fetchOutside' -A3 $C/session/Account.kt`; `grep -rn -i 'user-agent\|userAgent' src/mobile/core/src src/mobile/bridge/src` → none; `grep -rli gpx src/mobile`; `grep -n 'trackName\|trackActivity' $C/memories/music/TrackLabel.kt` |
| R10 | **Offline keeps the meta, not a track:** the kept listings carry `listNodes`' `wrappedMeta` (date, place) and `listNodeMedia`'s listed tiers (GPS, place names); `MemoryMedia.playsOriginal` is a video's or an audio medium's, so a track's original is neither pinned nor kept. | `sed -n 190,205p $C/memories/MemoryMedia.kt`; `grep -n 'NODES\|NODE_MEDIA' $C/offline/KeptRead.kt` |
| R11 | **Libraries for raster tiles** (read 2026-11-16): **osmdroid** is archived (`archived: true`, last push 2024-11-20); **MapLibre Native Android** 13.7.1 is maintained (BSD-2), an 18.7 MB AAR plus its gestures, GeoJSON and Turf artifacts, a GL vector engine whose `FileSource` keeps a resources cache database on disk; **libshumate** (crate 0.8.1 for gtk4 0.11) is **not in the GNOME 51 runtime** — GNOME Maps' own Flathub manifest builds it as a module — and its `ShumateTileDownloader` always opens a 100 MB `ShumateFileCache`; **Mapsui.WinUI** 5.2 (MIT, maintained) pulls SkiaSharp, HarfBuzzSharp, NetTopologySuite, BruTile, Svg.Skia, RichTextKit and the `Microsoft.WindowsAppSDK` metapackage beside the app's split `Microsoft.WindowsAppSDK.WinUI` 2.6.5; WinUI's own **`MapControl`** (in `Microsoft.UI.Xaml.winmd` 2.6.5) takes a `MapServiceToken` (Azure Maps) and no tile source. | `curl -s https://api.github.com/repos/osmdroid/osmdroid` (`archived`); `curl -sI https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/13.7.1/android-sdk-13.7.1.aar` (`content-length`); `curl -s https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/storage/FileSource.java \| grep -n resourcesCachePath`; `curl -s https://raw.githubusercontent.com/flathub/org.gnome.Maps/master/org.gnome.Maps.json \| grep -n '"name": "libshumate"\|runtime-version'`; `curl -s https://gitlab.gnome.org/GNOME/libshumate/-/raw/main/shumate/shumate-tile-downloader.c \| grep -n shumate_file_cache_new_full`; the NuGet registration of `mapsui.winui` (`dependencyGroups`); `strings ~/.nuget/packages/microsoft.windowsappsdk.winui/2.6.5/metadata/Microsoft.UI.Xaml.winmd \| grep -E '^(MapControl\|MapServiceToken\|MapTileSource)'` |
| R12 | **The providers' policies** (operations.osmfoundation.org, read 2026-11-16). Tiles: an app must send a distinct, stable `User-Agent` naming it ("library defaults will be blocked", okhttp's named); apps need no `Referer`; honour `Cache-Control`/`ETag`, revalidate conditionally, never send `no-cache`; **no pre-emptive fetching** beyond what is viewed and **no offline use** on `tile.openstreetmap.org`; attribution visible, never behind a toggle. Nominatim: a `User-Agent` or `Referer` identifying the application (stock ones "will not do"), ≤ 1 request/s, results cached by the client, no client-side autocomplete; limits apply **per application**. | the two policy pages |
| R13 | **Apple** (Xcode's macOS 27.2 SDK): `CLGeocoder`'s reverse calls are deprecated from iOS/macOS 26 ("Use MKReverseGeocodingRequest"); `MKReverseGeocodingRequest` (iOS/macOS 26) answers `MKMapItem`s whose non-deprecated address is `MKAddress` (`fullAddress`, `shortAddress`) and `MKAddressRepresentations` (`cityName`, `cityWithContext`, `regionName`, `regionCode`) — no road, suburb, county or state level; `MKMapItem.placemark` is deprecated from 26. SwiftUI's MapKit has `Map`, `MapPolyline`, `Annotation`, `MapCameraPosition`, `onMapCameraChange`. The apps target iOS/macOS 26; macOS is not sandboxed; no location key or Maps entitlement is declared. | `grep -n API_DEPRECATED $(xcrun --sdk macosx --show-sdk-path)/System/Library/Frameworks/CoreLocation.framework/Headers/CLGeocoder.h`; `grep -n property $(xcrun --sdk macosx --show-sdk-path)/System/Library/Frameworks/MapKit.framework/Headers/{MKReverseGeocodingRequest,MKAddress,MKAddressRepresentations,MKMapItem}.h`; `grep -n 'public struct \(MapPolyline\|Annotation\|MapCameraPosition\)\|func onMapCameraChange' <_MapKit_SwiftUI's arm64e-apple-macos.swiftinterface>`; `grep -n deploymentTarget -A3 src/mobile/apple/project.yml` |
| R14 | **The web tests a port mirrors:** `geocode.test.ts`, `placeName.test.ts`, `memoryLocation.test.ts`, `memoryDate.test.ts`, `spaceMap.test.ts`, `track/gpx.test.ts`, `track/trackFacts.test.ts`, `track/trackLabel.test.ts`, `track/useMemoryTracks.test.ts`, and `useMapConfig`'s consent cases. | `ls $W/*.test.ts $W/track/*.test.ts src/web/src/shared/state/*.test.ts \| grep -iE 'geocode\|place\|location\|date\|spaceMap\|track\|gpx\|mapConfig'` |
| R15 | **No new permission is needed anywhere:** Android declares `INTERNET`; the MSIX `internetClient`; the Flatpak `--share=network`; iOS needs none for MapKit and the app never asks for the device's location. | `grep -n 'uses-permission' src/mobile/android/src/main/AndroidManifest.xml`; `grep -n Capability src/mobile/windows/Veyq/Package.appxmanifest.in`; `grep -n 'finish-args' -A8 src/mobile/linux/flatpak/manifest.yml`; `grep -n 'INFOPLIST_KEY\|NSLocation' src/mobile/apple/project.yml src/mobile/apple/Veyq/Info.plist` |
| R16 | **The instance's privacy notice names the map services as the browser's:** its `browser-services` passage says "Some features have your browser contact an outside service directly" and lists `{browserServices}` with their addresses; Apple is not named anywhere. | `grep -n '"id": "browser-services"' -A26 contracts/terms/privacy-notice.json` |
| R17 | **Site pages that speak for this:** `memories/set-the-location.mdx`, `memories/add-gpx-tracks.mdx`, `getting-started/install-the-app.mdx` ("Not yet in it: a Memory's date, place and map … its GPX tracks"), `privacy/is-my-data-safe.md`, `security/threat-model.md`, `run/capabilities.mdx` — each EN, the Use pages also DE. | `git grep -l -i 'tile\|nominatim\|geocod\|map services\|kartendienste\|gpx' -- site/src/content/docs` |

## Decisions

The ADR phase (1) writes D1–D13 as **ADR 0143** ("The apps draw a Memory's places and maps: MapKit on Apple, the core's
raster map elsewhere, one consent per device"); each "Rejected" is its Alternatives.

### D1 — Which map each platform draws

- **iOS and macOS: MapKit** (owner, 2026-11-15) — SwiftUI's `Map` (R13): tracks as `MapPolyline`s with the activity's
  dash, the photos' dots and Memories' pins as `Annotation`s, the camera read by `onMapCameraChange`. MapKit's own
  attribution and Legal link stay where MapKit puts them. No `showsUserLocation`, no location key: the app never asks
  where the device is.
- **Android, Linux, Windows: the core's raster map.** The core holds the viewport model and everything that decides
  (`map/` in core, D2): which `{z,x,y}` tiles cover a viewport (Web Mercator, 256 px tiles, fractional zoom drawn from
  the floor level scaled), the projection of every overlay point to normalised Mercator coordinates, clustering, framing
  (fit a set of points), and the gated fetch of each tile. A platform draws what it is handed — tile images at their
  places, polylines, dots, pins — and reports gestures (drag, pinch, wheel, double-tap, the `+`/`−` and arrow keys):
  Android a Compose `Canvas` (`drawImage`, `drawPath` with a `PathEffect` dash); Linux a `gtk::Widget` whose `snapshot`
  appends textures and a `gsk::Path` stroked with a dash (GTK 4.14+, the app's `v4_20`); Windows a `Canvas` of `Image`s
  and `Shapes.Polyline`s (pins are no `Button`, § Traps' floating-button guard). No new dependency on any platform.
- Rejected: **MapLibre Native on Android** (R11) — a GL vector engine of 18.7 MB plus three artifacts for raster tiles
  ADR 0018 chose over vector, its own HTTP stack and on-disk resources cache, and a consent gate each UI would have to
  hold around a library that fetches on its own. Rejected: **osmdroid** — archived (R11), which the dependency rule
  forbids. Rejected: **libshumate on Linux** — not in the GNOME 51 runtime, so a bundled module, and its downloader
  always keeps a 100 MB tile cache on disk (R11), a record of the places a member looked at. Rejected: **Mapsui on
  Windows** — a rendering stack of six native-backed packages and the `WindowsAppSDK` metapackage beside the app's
  split packages (R11). Rejected: **WinUI's `MapControl`** — Azure Maps by token only, a commercial service with no tile
  source (R11). Rejected: **a WebView running the web's Leaflet** — a browser engine inside each app (WebView2 on
  Windows, WebKitGTK on Linux, neither in the app today) for one view, with its own cache to tame, against ADR 0123's
  native UI. Rejected: **MapKit with the operator's tiles on Apple** (`MKTileOverlay`, `canReplaceMapContent`) — the
  owner chose Apple's map; it stays the change to make if owner question 2 is answered so that Apple must not be a
  recipient.
- **Why the core's own map over three libraries:** the map is a tile pyramid, an affine transform and a few overlays
  (ADR 0018: "a readable basemap, not cartographic polish"); every library above brings its own fetch, cache and
  identity, which this CR would otherwise tame three times, while the core's fetch is the one place the consent, the
  hosts, the identification and the no-disk rule are enforced and tested (D2's bites).

### D2 — The core owns the map services: the consent, the gate, the tiles, the geocoder

`core/…/map/` (platform-generic, beside `audio/`; the capability ids are the platform catalogue's, as the web's
`useMapConfig` is core's):

- **`MapServices`** — per signed-in tenant: whether the server offers a map (`memories.map` served with a template) and
  a geocoder (`memories.places`), their hosts and the tile attribution (`TenantSession` keeps `serviceAttribution` now,
  R9); the device's **consent** (`DeviceStore` key `maps-consent`, `{tiles, places}` hosts as the web records them, R3;
  per device, never per account); `available` = offered ∧ consent holds for the served hosts; `renewal` (which new
  host asks again); `turnOn()`, `turnOff()`. **On Apple the tiles recipient is Apple Maps**, recorded as that and named in
  the consent and the settings in place of the tile host (D10; owner question 2); `memories.map` off means no map on
  Apple too (owner question 2). A withdrawal removes the key and every map model observing it drops its tiles, cancels
  what is in flight and clears the geocoder's queue at once.
- **`MapTiles`** — the tile set for a viewport, fetched only while `available`, only for the served template's host,
  only `https`, only tiles in view (no ring ahead: R12's pre-emptive fetching), through `VeyqAccount.outside` with D3's
  identification. A tile's bytes go to the platform as they are (PNG/JPEG); the core decodes nothing.
- **`Geocoder`** — the port of `geocode.ts` and `placeName.ts` (R4): `reverse(lat, lng, lang) → levels`, the 3-decimal
  cache with the language, the ≥ 1 s queue, no retry, a failure resolving to nothing. On every platform it calls the
  operator's geocoder (owner question 1, recommended); `lang` is the app's language, as the web's is the reader's.
- **Played in tests:** `PlayedMapServices` counts every request the map path sends (URL, headers) — the instrument every
  consent bite reads.
- Rejected: **a consent per account** — ADR 0016 makes it the device's; an account switch must not change what the
  device sends. Rejected: **each platform's own gate** — five places to forget one; the core's fetch is the gate, and on
  Apple, where MapKit fetches, the core's `available` is what mounts the `Map` (D11).

### D3 — A map request names the app and the instance, never the person

Every tile and geocoder request carries `User-Agent: veyq/<version> (+https://veyq.app; <instance origin>)` and no
`Referer`, cookie or token (R12: an app identifies by `User-Agent`; a library default is blocked; Ktor's is what goes out
today, R9). The instance's origin is what the web's `Referer` already tells the same providers (ADR 0016 § Request
discipline), so the providers' per-application limits and blocks stay per instance, as on the web. Rejected:
**`veyq/<version>` alone** — every instance's apps would be one application to Nominatim's per-application limit, and
one misbehaving instance would have every veyq app blocked. Rejected: **a synthetic `Referer`** — the policy asks apps
for a `User-Agent`, and a header pretending to be a page is what it calls impersonation.

### D4 — Tiles are kept in memory only, for the session

`MapTiles` keeps a bounded LRU of tiles in memory (≈ 300 tiles), with each one's `ETag`/`Last-Modified` and expiry,
revalidated conditionally after it expires (R12), and drops it all at a withdrawal, a lock, a sign-out and a tenant switch
(`closeTenant()`, where the player's readers close). Nothing the map path fetches is written to disk.
- **Residual:** a tile viewed in an earlier launch is fetched again; the tile policy asks for "a sufficient local cache".
  Our reading: a photo app's map traffic is a person browsing their own Memories, and honouring the headers in memory
  is that cache for a session. A sealed cache across launches, in ADR 0140's automatic tier, is inbox #939.
- Rejected: **a plaintext tile cache on disk** — the tiles a member viewed are a map of where their Memories are, which
  the sealed places exist to hide from whoever holds the device; the app keeps nothing at rest it did not choose
  (`src/mobile/CLAUDE.md`). Rejected: **sealing tiles into the offline store now** — it would serve them offline, which
  `tile.openstreetmap.org` forbids (R12), and the store's automatic tier is per account while the consent is per device;
  #939 decides both.

### D5 — The date and the place are the core's port of the web's

`core/…/memories/place/`: `MemoryDate` (modes, `normalize`, `computeAutoRange` over trustworthy capture times),
`MemoryLocation` (`normalize`, the 200-code-point bound, `WORLD_VIEW` and its "no point" reading, `computeMedoid`,
`mediaGpsPoints`, `isLocationPending`), both sealed into the node's `wrappedMeta` through `NodeMetaWrite` with every
other field verbatim (the cover's path, R7) at `metaRevision`, a `Stale` answer re-applying only its own change
(uc.memory.concurrent-curation): a date editor whose date changed underneath re-opens on the current value, as the web's.
- **The settle points** (uc.memory.date § How auto is derived, uc.memory.place A2): an upload batch settling
  (`MediaUploads`), the Memory opening (the self-heal, `GalleryModel`'s first list), a medium's trash — each reconciles
  the `auto` range and the `auto` position, re-sealed only if changed, and resolves a pending geocoded name where the
  consent holds. Today an app's upload leaves a web-made `auto` range stale until a browser opens the Memory; this ends it.
- **Shown:** under the Memory's header a facts line, its date range and its place name, each a button to its editor where
  `MemoryRights.editMeta` holds; the Memories list's row shows the range as its second line. The list stays ordered by
  last use (ADR 0122 decision 5, R8). Rejected: **sorting by start date as the web's grid does** — ADR 0122 orders every
  list by use; the date is shown, not sorted by.
- **The editors** are the core's models (`DateEditor`, `PlaceEditor`), drawn by each platform: the four modes with the
  platform's date picker (a range rejected with `to` < `from`, E4); the place editor's map with the fixed centre pin, a
  pan flipping `auto` to `fixed`, *Follow the photos*, the level picker from the reverse lookup (700 ms after the map
  settles), the typed name (`manual`), *Remove place*; without a map or without the consent, the name field with the
  consent's offer where the map would stand (A4, A6), no dialog between the member and the name.
- **Offline** both are shown and neither is edited (ADR 0140 decision 7).
- Rejected: **the date and place in the creation dialog** (A1) now — editing them right after creating does the same,
  and the dialog is five platforms' forms; #940.

### D6 — The Memories map: the bar's Map tab

The Memories bar's **Map** tab (R8) draws every Memory of the application's list (the same set as the All tab, ADR 0122)
whose place has a position, unsealed on the device from the `wrappedMeta` the tree already holds — no media load, no
locations endpoint (uc.memory.place A3). Pins carry the Memory's cover where its row has one. The core clusters by a
grid in Mercator pixels at the shown zoom (the port of `clusterPins`, which needs only zoom and coordinates, so Apple
hands its camera's zoom); a cluster's tap zooms to fit its pins, and at the maximum zoom lists them. A pin opens its
Memory. Linux and Windows gain Memories' bar tabs, which they do not draw today (R8). Rejected: **a map view on Home's
levels** as the web's level toggle — Home is core's and lists every application's folders; Memories' map belongs to its
bar (ADR 0122 decision 4). Rejected: **spiderfying** — a Leaflet gesture no platform's map has.

### D7 — The photo panel's map and the photo's place name

A photo with GPS shows, in its details, a small map at zoom 15 centred on it, with the direction needle where the EXIF
gives one (`DIRECTION_TRUE`), and *Turn map services off* beside its attribution; the `LOCATION` row stays. The panel
resolves the photo's name once ever and seals it into its listed tier (`placeName`, `placeLevel`) under the web's gates
(`resolvePlaceForCurrent`: a coordinate and no name, the consent, a geocoder, not while the slideshow runs, the medium's
meta door), through the raw read-modify-write `TrackLabel` already does (every other field verbatim). Rejected: **a name
per open** — ADR 0016's discipline: once per place, sealed.

### D8 — The Map view and its tracks

The Memory screen's view switch becomes **Photos | Music | Map** (Music where ADR 0142 shows it; Map always, as the web's
tab is): the map with the photos' dots and the node's own tracks (ADR 0130 decisions 3–5), the list under it — the
show/hide switch, colour swatch with the activity's dash, the activity above the name, distance, duration, climb,
descent — and per track **Edit**, **Download GPX** (the gallery's save), **Remove** (the gallery's trash with Undo) and
**Report** (`MediumReport`'s `ITEM`). **Add GPX track** where `upload` holds, through the platform's document picker
filtered to `.gpx` (Android `OpenMultipleDocuments`, Apple `fileImporter`, Windows `FileOpenPicker`, Linux
`GtkFileDialog`), each accepted file's name and activity asked before anything is uploaded, sealed at birth through
`MediaUploads`' facts; *Cancel* uploads nothing (A9).
- **`core/…/memories/tracks/`:** the port of `gpx.ts` over a **minimal XML reader of the core's own** — a document type
  declaration refused before anything is read, so only the five predefined entities and character references exist;
  elements, attributes, text, CDATA, comments and processing instructions; `trk/trkseg/trkpt` else `rte/rtept`, with
  `ele` and `time` — plus `trackStats`, `simplifyLine`, `distanceMeters`, `trackName`, the colours and the bounds (25 MiB,
  ≥ 2 points, 50 per node, 2,000 drawn), each refusal its own word naming the file. `TrackLabel` gains `trackActivity`
  (the ten tokens; an unknown one read as none and kept until edited) and its conflict rule of ADR 0130 decision 9.
- **The tracks are read only while the Map view shows**, one at a time, kept parsed while the page stays on its node.
- Rejected: **an XML library** (`xmlutil` or a platform parser) — a dependency on five targets, or a platform deciding a
  rule (ADR 0136), for three elements; the core already reads XMP itself (`media/ExifReader.kt`).

### D9 — Offline: no map, the tracks' list from kept originals

- **No map is drawn offline**, on any platform: the capabilities are not read offline (R9), so the app does not know
  which hosts the server serves now, and a consent holds only for the hosts it names; tiles are never kept (D4), and
  `tile.openstreetmap.org` permits no offline use (R12). The map's place says the map needs a connection (owner
  question 3's word); MapKit is not mounted either.
- **A track's original is kept like a video's** (ADR 0140 decision 8: an original is kept where the app reads one to show
  the item): `playsOriginal` covers a track, so a pin fetches the node's tracks and offline the Map view lists them with
  their numbers, download included. ADR 0140 decision 8's list gains "a GPX track's" (phase 1).
- Rejected: **drawing the tracks over a blank plane offline** — a map without its map, a second look to design on five
  platforms for a state a connection ends.

### D10 — What each map service learns (the security model's rows)

| Service | Learns | Never learns |
|---|---|---|
| The tile host (Android, Linux, Windows; the web) | the tiles in view `{z,x,y}`, the device's IP address, `veyq` and the instance's origin (D3) | a track's points, a pin, a photo's coordinate, a name, any veyq id or token |
| Apple Maps (iOS, macOS) | the region in view (as tiles), the device's IP address, and what MapKit sends of its own (Apple documents rotating random identifiers, not the Apple Account — remembered, not measurable through TLS) | the same as the tile host's column: overlays are drawn by MapKit on the device (remembered from Apple's documentation; phase 7 names it a residual of a closed library) |
| The geocoder (every platform; owner question 1) | a bare coordinate, the language, the device's IP address, `veyq` and the instance's origin | which Memory, whose, or any veyq id |
| The veyq operator | nothing more than today: sealed `wrappedMeta`, a sealed listed tier rewrite, one more medium without tiers | a coordinate, a viewport, a consent, a place name, that a Memory holds a track |

### D11 — The bridge (Linux, Windows)

`Watched.Map(map)` → `MapShown` (the tiles in view as `{key, x, y, size}` in normalised Mercator space, the overlays —
tracks with colour, dash and selection, dots, pins with cover ids, clusters —, the attribution text, the state:
`notOffered` | `consent(hosts, renewal)` | `shown` | `offline`); `Map.View(map, centre, zoom, width, height, scale)` from
the host as it settles and at most every 100 ms during a gesture; `Map.Tile(map, key)` → `Answer.Bytes` (ADR 0137), the
host translating its last frame by the drag meanwhile; `Map.TurnOn|TurnOff`; the editors' and the Map view's calls
(`Place.*`, `Date.*`, `Tracks.Add(paths)|Edit|Remove|Download|Report|Show`). Host-side the transform from normalised
coordinates to pixels is the one affine formula of the viewport; nothing else is computed there. `MapShown` is under
`SecretFreeSchemaTest`, its attribution and hosts in named fields; a map closes with its page and its tenant.

### D12 — No new permission, no new dependency

R15: every platform already reaches the network; MapKit needs no entitlement on iOS or the unsandboxed macOS app, and
nothing asks for the device's location. The packager linter rule (changes/README.md) has nothing to check; phase 7's
first measurement confirms a MapKit map loads on the macOS build without a Maps entitlement, and if it does not, the
entitlement goes through the store's validator before it is added.

### D13 — The privacy notice names the app and Apple Maps

The instance notice's `browser-services` passage (R16) says "your browser **or the veyq app**", and gains a line, shown
while `memories.map` is active, that the veyq app on iPhone, iPad and Mac shows maps from **Apple Maps (Apple Inc.)**
instead, which then sees the area shown and the device's IP address (owner question 3's text; legal duties win —
a recipient the notice does not name is the cheap safe side to close now). `revision` moves. The rest of the notice's
"your browser" wording predates the apps and is #942.

## Phases

- [x] **1 the ADR and the docs it changes** — ADR 0143 (the next free number, `ls docs/decisions | tail -3`) holding
  D1–D13, their rejected alternatives, and the Consequences: the security rows of D10, the tile-cache residual (#939), the
  offline map (D9). ADR 0016 and 0018 point at it (the apps' identification; Apple Maps as a recipient on Apple), ADR
  0130 (the apps' Map view), ADR 0140 decision 8 (a GPX track's original kept); `security-model.md`'s third-party
  boundary gains D10's rows; `memories.md` § Places and the map names the apps as planned; `native-apps.md` § Open
  (Memories) points #890's date and place and #907 here; `src/mobile/CLAUDE.md` names `map/`, `memories/place/`,
  `memories/tracks/`. · Done when: every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py` exits 0.

- [x] **2 the notice (contracts)** — D13 in `contracts/terms/privacy-notice.json` (EN+DE, `revision` + 1) and the
  renderer's switch for the Apple line; `PrivacyNoticeRendererTest` asserts the line shows only while `memories.map` is
  active. · Done when: `./gradlew check` in `src/backend/` green; **bite:** the Apple line rendered regardless of the
  switch → the renderer test red.

- [x] **3 the map services (core `map/`)** — D2, D3, D4: `MapServices`, `MapTiles` with the viewport math and the
  memory LRU, `Geocoder`, clustering and framing, `PlayedMapServices`; `TenantSession` keeps `serviceAttribution`.
  Tests: R14's `geocode`, `placeName`, `spaceMap` and consent cases ported; tile math against known `{z,x,y}` (Berlin at
  z 12 is `2200/1343`); the consent's hosts (a new tile host asks again, a removed geocoder does not, a host-less record
  asks once more). · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green; **bites**, each one edit restored
  from a snapshot, each red at its own assertion: (a) a tile fetched before the consent → `PlayedMapServices`' count 1,
  red; (b) a withdrawal with a tile queued → the request still leaves, red; (c) the consent kept across a changed tile
  host → no renewal, red; (d) a request without D3's `User-Agent` (or with the bearer) → the header case red; (e) a second
  geocode of a point 50 m away inside 1 s → the cache-and-queue case red; (f) the tile cache kept after `closeTenant()`
  → the drop case red; (g) a tile beyond the viewport fetched → the in-view case red.

- [x] **4 the place, the date and the tracks (core `memories/`)** — D5, D6's pins, D7, D8, D9's `playsOriginal`; the
  models `DateEditor`, `PlaceEditor`, `MemoriesMap`, the photo panel's map state, `TrackList` (the Map view). Tests: R14's
  `memoryLocation`, `memoryDate`, `gpx`, `trackFacts`, `trackLabel`, `useMemoryTracks` cases ported; a **cross-surface
  fixture** — a `wrappedMeta` with date, location and cover as the web seals it, and a track's listed tier with
  `trackName`, `trackActivity` and a field the core does not know — after a date write, a place write and a label edit
  every other field byte-equal; GPX files from the web's fixtures. Live (`VEYQ_LIVE`): set a date range and a place on a
  Memory from one session while a second sets the cover → both kept; upload two geotagged photos → the `auto` range and
  position follow; add a GPX with a label, edit it, remove it and Undo; pin the Memory and count the track's frames.
  · Done when: core and `:bridge:jvmTest` green, the live tests green; **bites:** (a) a place write re-sealed from the
  parsed meta → the cover gone, red; (b) a file-sourced capture time counted in `auto` → the range case red; (c) a
  DOCTYPE accepted → the entity case red; (d) a 51st track accepted → red; (e) `playsOriginal` without tracks → the
  pinned frames 0, red; (f) the photo's name resolved without `editMeta` → the gate case red; (g) a `Stale` answer
  overwriting a peer's manual date with `auto` → the concurrent case red.
  *Done 2026-11-16:* core (`:core:jvmTest` 944, `:core:macosArm64Test` 748) and `:bridge:jvmTest` (94) green; bites
  (a)–(g) each red then green on their unit cases ((e) on `SettlePointsTest`); `PlacesLiveTest` green against the dev
  instance (date and place kept beside a peer's cover; settled to 2020-01-02..2021-07-15 at the medoid; a track added,
  relabelled, removed and restored; pinned 1 of 1 frames), and bite (e)'s live half red (`expected:<1> but was:<0>`)
  then green.

- [ ] **5 the bridge** — D11: `Watched.Map` → `MapShown`, the place, date and track calls, `Map.Tile` as bytes, Memories'
  bar tabs and the Map view in the gallery's state; `MapShown` under `SecretFreeSchemaTest`; a map's tiles dropped with
  its page and tenant (`DispatchTest`'s pattern). · Done when: `:bridge:jvmTest` green; **bites:** (a) a tile URL or a
  bearer in `MapShown` → `SecretFreeSchemaTest` red; (b) a map left open after `closeTenant()` → its tile still answered,
  red.

- [ ] **6 Android** — D1's Compose map, the editors, the facts line, the Map tab, the photo panel's map, the Map view with
  its picker and dialogs, Settings → Map services, every word from `words.json` (EN+DE). First, measured on the emulator:
  a pan and a pinch over 30 tiles at 60 Hz without a dropped frame in the GPU profile bars, or the frame time recorded.
  · Done when: `./gradlew :android:assembleDebug` and `scripts/validate_mobile_words.py` green; clicked once: consent in
  context, set a place by panning and picking a level, set a range, the Map tab's pins, add a track and edit it, turn map
  services off on a map.

- [x] **7 Apple (iOS, macOS)** — D1's MapKit views, the rest as phase 6, Settings → Map services naming Apple Maps. First,
  measured on the macOS build: a `Map` loads without a Maps entitlement (D12); and **what MapKit keeps on disk** — the
  app's container (and `~/Library/Caches/` for the unsandboxed Mac) listed before and after a map was shown, named in
  `src/mobile/CLAUDE.md` § Nothing on disk and ADR 0143's Consequences as Apple's residual. · Done when: the Apple
  project's builds (iOS simulator SDK, macOS) green; clicked once on the Mac as phase 6.
  *Done 2026-11-16:* measured first on macOS 26.7 with an unsandboxed, ad-hoc-signed probe without entitlements
  (headless, `MKMapSnapshotter`): MapKit loads without a Maps entitlement (nothing added); the app's own places gained only
  Metal's shader cache, Apple's daemons wrote `~/Library/Caches/GeoServices/` and `geoanalyticsd`'s `APDB.db`, and `geod`'s
  tile cache sits in its privacy-protected container — named as Apple's residual in ADR 0143's Consequences and
  `src/mobile/CLAUDE.md` § Nothing on disk. Built: `MapViews.swift` (the gate mounting MapKit only on `Shown`, the consent
  naming Apple Maps, the facts line, the date and place editors, the bar's Map tab with core's clusters, a photo's map with
  its name resolved once, Settings → Map services), `MemoryMapView.swift` (Photos | Music | Map, the tracks' polylines
  dashed by activity, the list, add with labels, edit, download, report, remove), the words (EN+DE, 86 keys), and
  `appleMain`'s `AppleMemories.saveTrack`/`watchPlace` (what Swift cannot call). `xcodebuild` for macOS and the iOS
  simulator SDK (`CODE_SIGNING_ALLOWED=NO`) green; `validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`
  and every guard but guard_gaps green. **Not run:** the click-through on the Mac — the screen was locked all session
  (`ioreg`: `CGSSessionScreenIsLocked=Yes`), so it is the owner's step (or phase 9's pass); iOS on a simulator (none
  boots on this Mac) and iOS's on-disk listing (a device's, phase 9).

- [ ] **8 Windows and Linux** — D1's canvases over the bridge, Memories' bar tabs, the rest as phase 6. First, measured:
  a dashed `gsk::Path` stroke in the container's GTK and a dashed `Polyline` on the VM (the `StrokeDashArray`), else the
  dash drawn as segments. · Done when: `run.py build`/`test` green on both; clicked once as phase 6.

- [ ] **9 the passes** — per platform against the dev instance with the seeded OSM defaults, a handful of tiles each:
  consent in context naming both hosts (on Apple, Apple Maps and the geocoder), a place set and named, the Map tab's pins
  and a cluster, a photo's map and its name sealed once (a second open sends no geocode — the request log), a track added,
  its line dashed, hidden and selected, *Turn map services off* tearing the map down at once; the request log of one pass
  (Android's OkHttp event listener, or the core's `PlayedMapServices` in a debug build) showing D3's `User-Agent` and no
  `Referer` or token; offline: a pinned Memory's Map view lists its track, no map. · Done when: each pass's commands and
  outputs are in ADR 0143's Consequences, with what could not be run named (iOS on a device is the owner's).

- [ ] **10 harvest** — the site (R17): `set-the-location.mdx` and `add-gpx-tracks.mdx` (EN+DE) tell the apps (Apple
  Maps on Apple, the consent per device, no map offline), `install-the-app.mdx` (EN+DE) no longer sends the date, place,
  map and tracks to the browser, `privacy/is-my-data-safe.md` and `security/threat-model.md` name Apple Maps on Apple,
  `run/capabilities.mdx` says the Apple apps do not use the configured tile host but honour its switch; ADR 0143 and the
  use cases from planned to built; `native-apps.md` § Built; the lessons to `changes/README.md` or the lines they bite;
  #907 deleted, #890 left with participants, capture time and the banner; this CR deleted. · Done when: `pnpm run check`
  in `site/` green and every guard but guard_gaps green.

## Owner questions

Answered 2026-11-15/16 by the owner: **1** — place names come from the **operator-configured geocoder** on Apple too,
while the map itself is drawn by **MapKit** (the recommendation). **2** — (a) yes, the consent on iPhone, iPad and Mac
names Apple Maps as the map service; (b) off means off everywhere: when the operator or the space's owner turned maps
off, MapKit draws no map either. **3** — the new and changed words and the privacy notice's lines as proposed (EN and DE).

1. **On Apple, which geocoder names a place?** Apple's (`MKReverseGeocodingRequest`; `CLGeocoder` is deprecated from 26,
   R13) — Apple then also learns the coordinates being named, which it largely sees already as the map's region — or the
   operator-configured one, as the web and the other apps. *Recommendation: the operator's.* Apple's current API answers
   a city, a region and a full address but no road, suburb, county or state level, so the level picker (ADR 0016) could
   not offer what it offers everywhere else; a Memory named on an iPhone and one named on Android or the web would carry
   names from two gazetteers in one space; the operator's switch for place names would not hold on Apple; and one
   geocoder path in the core keeps one request discipline.
2. **Apple Maps in the consent, and the operator's switch.** (a) Does the consent on iPhone, iPad and Mac name **Apple
   Maps** as the map service (in place of the server's tile host, which those apps do not call)? *Recommendation: yes* — a
   consent is informed only about the recipients it names (ADR 0016), and Apple is the recipient there. (b) Is MapKit
   used when the operator (or the space's owner) turned the map off? *Recommendation: no — off means off everywhere;* the
   switch is the operator's and the owner's say over maps in their instance and space, whoever draws them.
3. **New and changed words** (EN / DE). The web's `memory.date.*`, `memory.place.*`, `memories.tracks.*` and
   `settings.maps*` are reused as they are, except:
   - `settings.mapsSub`: "Whether this device loads maps and looks up the names of places." / "Ob dieses Gerät Karten
     lädt und die Namen von Orten nachschlägt." (web: "browser")
   - `settings.mapsSwitch`: "Map services on this device" / "Kartendienste auf diesem Gerät" (web: "in this browser")
   - `map_needs_connection` (D9): "The map needs a connection." / "Die Karte braucht eine Verbindung."
   - `map_cluster_title` (D6, the list at the maximum zoom): "{count} Memories here" / "{count} Erinnerungen hier"
   - on Apple the existing `consentHostsTiles`/`consentHosts` with `{tiles}` = "Apple Maps" ("The map from Apple Maps,
     place names from {places}.") — no new sentence.
   - The privacy notice (D13): `browser-services`' first line "Some features have your browser or the veyq app contact an
     outside service directly; the server sends them nothing:" / "Manche Funktionen lassen deinen Browser oder die
     veyq-App einen äußeren Dienst direkt anfragen; der Server schickt ihnen nichts:"; and the new line "The veyq app on
     iPhone, iPad and Mac shows maps from Apple Maps (Apple Inc., United States) instead; Apple then sees the area shown
     and your device's IP address." / "Die veyq-App auf iPhone, iPad und Mac zeigt Karten stattdessen von Apple Karten
     (Apple Inc., USA); Apple sieht dann den gezeigten Ausschnitt und die IP-Adresse deines Geräts."

## Inbox rows

#890 (split: its date, place and map are this CR's; participants, a medium's capture time and the banner stay there),
#907 (refined into this CR), #939 (a sealed tile cache across launches), #940 (the date and place in the apps' creation
dialog), #941 (a person's profile map in the apps, with a profile page), #942 (the privacy notice says "your browser"
where the apps do the same).

## Definition of done

Phases 1–10 checked with their Done-when met; the core, bridge, Linux and Windows checks and the backend's green; the
bites above each seen red and green again; the passes and Apple's on-disk measurement recorded in ADR 0143; the Use pages
in EN and DE updated with `pnpm run check` in `site/` green; every `scripts/validate_*.py` but guard_gaps green. No web or
backend change beyond the notice's renderer: `git diff --stat` over `src/web/` is empty and over `contracts/` touches
`contracts/terms/privacy-notice.json` alone. `satisfies:` stays empty as in the music CR: the coverage files have no
platform for the apps; their proof is the core's and bridge's tests listed in `native-apps.md`.

## Retrospective

_(filled at the end of the implementation)_
