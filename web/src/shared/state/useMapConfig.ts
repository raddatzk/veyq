// Map + geocoding consent and provider config (ADR 0016 / 0018, cr-0069). The set-location feature reaches two
// **third-party** boundaries: basemap **tiles** (the viewport `{z,x,y}` + IP leak to the tile server) and
// **reverse geocoding** (a bare coordinate + IP leak to the geocoder). Both go **client-direct** — never through
// the veyq backend, never an operator-run instance — so the veyq operator stays blind.
//
// **One coupled, two-stage opt-in (personal space).** Tiles gate geocoding: there is no tiles-on/geocoding-off
// middle stage. `mapsConsent` **off** ⇒ no map ⇒ no location feature; **on** ⇒ tiles **and** reverse geocoding.
// Off by default — the user actively opts in. (The group consent state-machine — owner soft-enable, per-member
// notice, unanimity-binds — is a later CR; here it's simply the user's own toggle.)
//
// **Off until configured.** The reference provider is public OSM tiles + Nominatim (ADR 0016/0018), shipped as
// the built-in default and overridable via env (`VITE_VEYQ_MAP_TILE_URL`, `VITE_VEYQ_MAP_GEOCODER_URL`) or a
// per-device localStorage override. With no tile URL there is no map; with no geocoder URL, names are typed only.
import { computed, readonly, ref } from 'vue'
import {
  DEFAULT_GEOCODER_URL,
  DEFAULT_TILE_ATTRIBUTION,
  DEFAULT_TILE_MAX_ZOOM,
  DEFAULT_TILE_URL,
} from './mapDefaults'

const CONSENT_KEY = 'veyq:maps-consent'
const TILE_URL_KEY = 'veyq:map-tile-url'
const GEOCODER_URL_KEY = 'veyq:map-geocoder-url'

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked) → in-memory default only
  }
}

/** Env override (build/deploy config) for an operator-pointed provider, else the built-in reference default. */
function envDefault(key: 'VITE_VEYQ_MAP_TILE_URL' | 'VITE_VEYQ_MAP_GEOCODER_URL', fallback: string): string {
  const v = (import.meta.env as Record<string, string | undefined>)[key]
  return typeof v === 'string' && v.trim() ? v.trim() : fallback
}

const mapsConsent = ref<boolean>(storage()?.getItem(CONSENT_KEY) === '1')
const tileUrl = ref<string>(storage()?.getItem(TILE_URL_KEY) || envDefault('VITE_VEYQ_MAP_TILE_URL', DEFAULT_TILE_URL))
const geocoderUrl = ref<string>(
  storage()?.getItem(GEOCODER_URL_KEY) || envDefault('VITE_VEYQ_MAP_GEOCODER_URL', DEFAULT_GEOCODER_URL),
)

/**
 * Consent + provider config for the map/geocoding surface. `mapsAvailable` gates the whole location feature (a
 * consented map with a tile URL); `geocodingAvailable` additionally requires a geocoder URL — when it's false the
 * user types the name on the map instead. `reset` is for tests.
 */
export function useMapConfig() {
  return {
    mapsConsent: readonly(mapsConsent),
    setMapsConsent,
    tileUrl: readonly(tileUrl),
    tileAttribution: DEFAULT_TILE_ATTRIBUTION,
    tileMaxZoom: DEFAULT_TILE_MAX_ZOOM,
    geocoderUrl: readonly(geocoderUrl),
    setTileUrl,
    setGeocoderUrl,
    mapsAvailable: computed(() => mapsConsent.value && Boolean(tileUrl.value)),
    geocodingAvailable: computed(() => mapsConsent.value && Boolean(geocoderUrl.value)),
  }
}

export function setMapsConsent(on: boolean): void {
  mapsConsent.value = on
  if (on) storage()?.setItem(CONSENT_KEY, '1')
  else storage()?.removeItem(CONSENT_KEY)
}

export function setTileUrl(url: string): void {
  tileUrl.value = url
  storage()?.setItem(TILE_URL_KEY, url)
}

export function setGeocoderUrl(url: string): void {
  geocoderUrl.value = url
  storage()?.setItem(GEOCODER_URL_KEY, url)
}
