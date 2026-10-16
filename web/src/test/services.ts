// The outside services a test's client sees (uc.admin.configure-external-services): the entries `memories.map`,
// `memories.places` and `core.breached-password` with the address the server would carry as their `serviceUrl`. A test
// sets them here the way the manifest would, never through a client-side override (there is none). `setup.ts` puts a
// new instance's defaults in place before every test.
import { capabilityState } from '../shared/state/capabilities'
import { CORE_BREACHED_PASSWORD, MEMORIES_MAP, MEMORIES_PLACES } from '../features/platform/capabilities'

export const DEFAULT_TEST_TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
export const DEFAULT_TEST_GEOCODER = 'https://nominatim.openstreetmap.org'
export const DEFAULT_TEST_BREACH = 'https://api.pwnedpasswords.com/range/'

function put(id: string, serviceUrl: string, serviceAttribution?: string): void {
  const entries = { ...capabilityState.entries }
  if (serviceUrl) entries[id] = { id, serviceUrl, serviceAttribution }
  else delete entries[id]
  capabilityState.entries = entries
}

/** The map tiles' template, or '' for a server without the map service. */
export function setTileUrl(url: string, attribution = '© OpenStreetMap contributors'): void {
  put(MEMORIES_MAP, url, attribution)
}

/** The geocoder's base, or '' for a server without place names. */
export function setGeocoderUrl(url: string): void {
  put(MEMORIES_PLACES, url)
}

/** The breach check's range API, or '' for a server that runs no check. */
export function setBreachUrl(url: string): void {
  put(CORE_BREACHED_PASSWORD, url)
}

/** A new instance's three services, as the backend's migration seeds them. */
export function defaultServices(): void {
  capabilityState.tenantEntries = null
  setTileUrl(DEFAULT_TEST_TILES)
  setGeocoderUrl(DEFAULT_TEST_GEOCODER)
  setBreachUrl(DEFAULT_TEST_BREACH)
}
