// Map + geocoding consent and provider config (ADR 0016 / 0018, cr-0069). The set-location feature reaches two
// **third-party** boundaries: basemap **tiles** (the viewport `{z,x,y}` + IP leak to the tile server) and
// **reverse geocoding** (a bare coordinate + IP leak to the geocoder). Both go **client-direct** — never through
// the veyq backend, never an operator-run instance — so the veyq operator stays blind.
//
// **One coupled, two-stage opt-in (personal space).** Tiles gate geocoding: there is no tiles-on/geocoding-off
// middle stage. `mapsConsent` **off** ⇒ no map ⇒ no location feature; **on** ⇒ tiles **and** reverse geocoding.
// Off by default — the user actively opts in.
//
// **The addresses are the server's** (ADR 0088 decision 7, uc.admin.configure-external-services): the Server Owner
// configures the tile service and the geocoder, and the client reads them from the capabilities it holds — the
// entries `memories.map` and `memories.places`, present only while configured and switched on, their `serviceUrl`
// (and the tiles' `serviceAttribution`). The served Content Security Policy allows exactly those origins. Without the
// map entry there is no map and nothing to consent to (`mapsOffered`); without the geocoder, names are typed only.
import { computed, readonly, ref } from 'vue'
import { DEFAULT_TILE_MAX_ZOOM } from './mapDefaults'
import { useCapabilities } from './useCapabilities'
import { MEMORIES_MAP, MEMORIES_PLACES } from '../../features/platform/capabilities'

const CONSENT_KEY = 'veyq:maps-consent'

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked) → in-memory default only
  }
}

const mapsConsent = ref<boolean>(storage()?.getItem(CONSENT_KEY) === '1')
const { capabilityValues } = useCapabilities()
const tileUrl = computed(() => capabilityValues(MEMORIES_MAP)?.serviceUrl ?? '')
const tileAttribution = computed(() => capabilityValues(MEMORIES_MAP)?.serviceAttribution ?? '')
const geocoderUrl = computed(() => capabilityValues(MEMORIES_PLACES)?.serviceUrl ?? '')

/**
 * Consent + provider config for the map/geocoding surface. `mapsOffered` says the server serves a map at all (the
 * consent is asked only then); `mapsAvailable` gates the whole location feature (a consented map with a tile URL);
 * `geocodingAvailable` additionally requires a geocoder URL — when it's false the user types the name on the map.
 */
export function useMapConfig() {
  return {
    mapsConsent: readonly(mapsConsent),
    setMapsConsent,
    tileUrl,
    tileAttribution,
    tileMaxZoom: DEFAULT_TILE_MAX_ZOOM,
    geocoderUrl,
    mapsOffered: computed(() => Boolean(tileUrl.value)),
    mapsAvailable: computed(() => mapsConsent.value && Boolean(tileUrl.value)),
    geocodingAvailable: computed(() => mapsConsent.value && Boolean(geocoderUrl.value)),
  }
}

export function setMapsConsent(on: boolean): void {
  mapsConsent.value = on
  if (on) storage()?.setItem(CONSENT_KEY, '1')
  else storage()?.removeItem(CONSENT_KEY)
}
