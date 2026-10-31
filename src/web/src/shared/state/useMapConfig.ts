// Map + geocoding consent and provider config (ADR 0016 / 0018, cr-0069). The set-location feature reaches two
// **third-party** boundaries: basemap **tiles** (the viewport `{z,x,y}` + IP leak to the tile server) and
// **reverse geocoding** (a bare coordinate + IP leak to the geocoder). Both go **client-direct** — never through
// the veyq backend, never an operator-run instance — so the veyq operator stays blind.
//
// **One coupled, two-stage opt-in (personal space).** Tiles gate geocoding: there is no tiles-on/geocoding-off
// middle stage. `mapsConsent` **off** ⇒ no map ⇒ no location feature; **on** ⇒ tiles **and** reverse geocoding.
// Off by default — the user actively opts in.
//
// **Withdrawn as easily as given** (GDPR Art. 7(3), uc.memory.place A5): Settings → Map and place names
// (`features/settings/MapsPanel.vue`) and the "Turn map off" control on every map call `setMapsConsent(false)`. The flag
// is removed and `mapsAvailable` turns false at once, so every map unmounts its Leaflet instance (no further tile) and
// every geocoding caller, which checks `geocodingAvailable` at call time, stops; what is already sealed stays. Another
// tab of this browser follows through the `storage` event.
//
// **A consent names its recipients** (GDPR Art. 4(11), 7: informed about whom it lets in). The key stores, as JSON, the
// tile host and the geocoder host it was given for; `mapsConsent` holds only while they are the hosts this server serves
// now. A new tile host, a new or changed geocoder host asks again — the map shows the prompt, naming them, and nothing
// reaches them before the member agrees; a geocoder the Server Owner removed needs no new consent (a recipient fewer).
// The legacy value `'1'`, stored before hosts were recorded, names no host, so such a consent is asked once more.
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

/** The recipients a consent was given for: the tile host and the geocoder host (null: none was served, or none recorded). */
export interface ConsentHosts {
  tiles: string | null
  places: string | null
}

/** The stored consent: null without one; the legacy `'1'` (a consent from before hosts were recorded) names no host. */
function readConsent(): ConsentHosts | null {
  const raw = storage()?.getItem(CONSENT_KEY)
  if (raw == null) return null
  try {
    const v = JSON.parse(raw) as Partial<ConsentHosts> | null
    if (v && typeof v === 'object') {
      return { tiles: typeof v.tiles === 'string' ? v.tiles : null, places: typeof v.places === 'string' ? v.places : null }
    }
  } catch {
    /* not JSON: the legacy value */
  }
  return { tiles: null, places: null }
}

const consentGivenFor = ref<ConsentHosts | null>(readConsent())
// A consent given or withdrawn in another tab of this browser applies here too: its maps tear down without a reload.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === CONSENT_KEY || e.key === null) consentGivenFor.value = readConsent()
  })
}
const { capabilityValues } = useCapabilities()
const tileUrl = computed(() => capabilityValues(MEMORIES_MAP)?.serviceUrl ?? '')
const tileAttribution = computed(() => capabilityValues(MEMORIES_MAP)?.serviceAttribution ?? '')
const geocoderUrl = computed(() => capabilityValues(MEMORIES_PLACES)?.serviceUrl ?? '')

/** The hosts this server serves now (null for a service it does not serve). */
const currentHosts = computed<ConsentHosts>(() => ({
  tiles: tileUrl.value ? serviceHost(tileUrl.value) : null,
  places: geocoderUrl.value ? serviceHost(geocoderUrl.value) : null,
}))

// The consent holds only for the recipients it named: the same tile host, and the same geocoder host — or no geocoder
// now (one the Server Owner removed is a recipient fewer, never a new one). Any other host, or a consent that names
// none, is no consent: the map shows the prompt again and nothing is requested from the new host until the member agrees.
const mapsConsent = computed(() => {
  const given = consentGivenFor.value
  const now = currentHosts.value
  return given !== null && given.tiles === now.tiles && (now.places === null || given.places === now.places)
})

/**
 * Consent + provider config for the map/geocoding surface. `mapsOffered` says the server serves a map at all (the
 * consent is asked only then); `mapsAvailable` gates the whole location feature (a consented map with a tile URL);
 * `geocodingAvailable` additionally requires a geocoder URL — when it's false the user types the name on the map.
 * `consentGivenFor` is the stored consent's hosts; with `mapsConsent` false it names what an earlier consent covered,
 * and `consentRenewal` the hosts a renewed one would add (shown by the prompt and the settings).
 */
export function useMapConfig() {
  return {
    mapsConsent,
    consentGivenFor: readonly(consentGivenFor),
    currentHosts,
    consentRenewal: computed(() => (consentGivenFor.value !== null && !mapsConsent.value ? currentHosts.value : null)),
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

/** The host a service address names (`https://tile.example/{z}/{x}/{y}.png` → `tile.example`), as the settings show
 *  it; the address itself where it names none. */
export function serviceHost(url: string): string {
  return /^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)/i.exec(url)?.[1] ?? url
}

/** Give (`true`) this browser's consent for the hosts this server serves now, or withdraw it (`false`): a withdrawal
 *  removes the stored key. */
export function setMapsConsent(on: boolean): void {
  if (on) {
    const hosts = { ...currentHosts.value }
    consentGivenFor.value = hosts
    storage()?.setItem(CONSENT_KEY, JSON.stringify(hosts))
  } else {
    consentGivenFor.value = null
    storage()?.removeItem(CONSENT_KEY)
  }
}
