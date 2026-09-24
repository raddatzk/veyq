// Reverse geocoding seam (web) — ADR 0016. Coordinate-first, returns the **address hierarchy** so the user picks
// the granularity (Straße ↔ Viertel ↔ Stadt ↔ Region ↔ Land) rather than fighting the map zoom. The request goes
// **client-direct to a third-party provider** (public Nominatim by default), **never through the veyq backend and
// never an operator-run instance** (either would break operator-blindness), carrying **no veyq identity and no
// Memory context** — a bare coordinate. `lang` = the resolving locale (the user's own, in a personal space).
//
// **Off until configured + consented.** With no endpoint configured (or the personal maps opt-in off, gated in
// the UI), `reverseGeocode` makes **no request** and returns `null` — the manual, typed name stands. Manual names
// never make an external call. The seam is provider-neutral: swapping Nominatim for another OSM-based reverse
// geocoder is an endpoint + a thin parse change here — the hierarchy shape (`GeoLevel[]`, finest→coarsest) is what
// the caller consumes.

/** One level of the reverse-geocoded address hierarchy. `key` is a stable granularity id; `name` is the label. */
export type GeoLevel = { key: 'road' | 'suburb' | 'city' | 'county' | 'island' | 'state' | 'country'; name: string }

/** Build the finest→coarsest hierarchy from a Nominatim `address` object (`addressdetails=1`). */
function nominatimLevels(data: unknown): GeoLevel[] {
  if (!data || typeof data !== 'object') return []
  const d = data as { error?: unknown; address?: Record<string, unknown> }
  if (d.error || !d.address) return []
  const a = d.address
  const str = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() ? v.trim() : undefined)
  const levels: GeoLevel[] = []
  const road = str(a.road) ?? str(a.pedestrian) ?? str(a.footway)
  if (road) levels.push({ key: 'road', name: str(a.house_number) ? `${road} ${a.house_number}` : road })
  const suburb = str(a.neighbourhood) ?? str(a.suburb) ?? str(a.quarter) ?? str(a.city_district)
  if (suburb) levels.push({ key: 'suburb', name: suburb })
  const city = str(a.city) ?? str(a.town) ?? str(a.village) ?? str(a.municipality)
  if (city) levels.push({ key: 'city', name: city })
  const county = str(a.county) ?? str(a.state_district)
  if (county) levels.push({ key: 'county', name: county })
  const island = str(a.island) // e.g. Formentera; a bigger island (Mallorca) is split into comarcas → not here
  if (island) levels.push({ key: 'island', name: island })
  const state = str(a.state) ?? str(a.region)
  if (state) levels.push({ key: 'state', name: state })
  const country = str(a.country)
  if (country) levels.push({ key: 'country', name: country })
  return levels
}

/**
 * Reverse-geocode a coordinate to its **address hierarchy** (finest→coarsest levels) via a third-party provider
 * (Nominatim shape). Returns the levels, or **null** when: no `endpoint` is configured (⇒ **no request** — the
 * off state), the provider errors/times out, or the point is unresolvable. Best-effort: a failure never throws.
 *
 * `endpoint` is the provider's reverse-geocode URL base (e.g. `https://nominatim.openstreetmap.org`). `fetchImpl`
 * is injectable for tests; it defaults to the global `fetch`. A high internal `zoom` asks the provider for the
 * full breakdown — the granularity is chosen client-side from the returned levels, not by the request.
 */
export async function reverseGeocode(
  endpoint: string | null | undefined,
  lat: number,
  lng: number,
  lang: string,
  fetchImpl: typeof fetch = fetch,
): Promise<GeoLevel[] | null> {
  if (!endpoint) return null // off until configured — no coordinate leaves the device
  try {
    const url = new URL(endpoint.replace(/\/+$/, '') + '/reverse')
    url.searchParams.set('format', 'jsonv2')
    url.searchParams.set('addressdetails', '1')
    url.searchParams.set('zoom', '18') // ask for the full breakdown; the client picks the level
    url.searchParams.set('lat', String(lat))
    url.searchParams.set('lon', String(lng))
    url.searchParams.set('accept-language', lang)
    // Identity-free: no veyq token, no cookies, no Memory context — just the bare coordinate.
    //
    // **Trap: the provider's identifying-header requirement is met by the `Referer` alone.** Nominatim's usage
    // policy wants "a valid HTTP Referer or User-Agent identifying the application"; we set neither. The app
    // shell sends `Referrer-Policy: strict-origin-when-cross-origin` (ADR 0069), which keeps our origin in
    // `Referer`. Tightening it to `no-referrer` — an ordinary hardening — would silently put every instance
    // outside that policy, so whoever tightens it owes this call an explicit identifying header instead
    // (ADR 0016 § Request discipline).
    const res = await fetchImpl(url.toString(), { headers: { Accept: 'application/json' } })
    if (!res.ok) return null
    const levels = nominatimLevels(await res.json())
    return levels.length > 0 ? levels : null
  } catch {
    return null // network/parse failure → no levels; the manual name stands
  }
}

/**
 * The default level to pre-select for a fresh place — a **city** if the point has one (the most useful grain for
 * a Memory), else the coarsest available, else the finest. Null on an empty list.
 */
export function defaultLevel(levels: GeoLevel[]): GeoLevel | null {
  if (levels.length === 0) return null
  return levels.find((l) => l.key === 'city') ?? levels[levels.length - 1] ?? levels[0]
}

/** The name at a chosen level `key` in a hierarchy, or null when that level isn't present. */
export function nameAtLevel(levels: GeoLevel[], key: GeoLevel['key']): string | null {
  return levels.find((l) => l.key === key)?.name ?? null
}
