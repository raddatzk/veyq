// uc.memory.place / uc.memory.gallery — resolving ONE photo's coordinate to a place name, under the request
// discipline the provider's own policy demands ([ADR 0016] § Request discipline).
//
// **Why this is not the medoid path.** uc.memory.place deliberately rejected per-item geocoding: computing a
// Memory's medoid from N per-item fetches on every open is N requests for one answer, every time. This resolves
// **one** name, **once ever**, for the photo the user is looking at, and seals it into the tier the medoid
// already reads. Different frequency, different trigger, same boundary — the rejection there still stands.
//
// Public Nominatim's usage policy (measured 2026-09-20) sets an absolute maximum of 1 request per second, says
// bulk geocoding is not encouraged, requires results to be cached on our side, and warns that repeating the
// same query may get a client blocked. So the cache and the queue below are **policy compliance**, not an
// optimisation — a per-photo trigger is the first caller in this client that can generate volume at all.
import { reverseGeocode, defaultLevel, type GeoLevel } from './geocode'

/** What one resolution yields: the name to seal and the hierarchy level it came from. */
export type ResolvedPlace = { name: string; level: GeoLevel['key'] }

/**
 * Coordinates are rounded to 3 decimals for the cache key — about 110 m, so every photo taken at one spot is
 * ONE lookup rather than one per photo. Without the rounding a burst of frames from the same balcony differs in
 * the fifth decimal and each one is a fresh request, which is precisely the "repeatedly the same query"
 * behaviour the policy warns about.
 */
const CACHE_PRECISION = 3
/** The policy's absolute floor. Requests are serialised, never merely rate-counted. */
const MIN_INTERVAL_MS = 1000

const cache = new Map<string, Promise<ResolvedPlace | null>>()
let chain: Promise<unknown> = Promise.resolve()
let lastStartedAt = 0

const keyOf = (lat: number, lng: number, lang: string) =>
  `${lat.toFixed(CACHE_PRECISION)},${lng.toFixed(CACHE_PRECISION)},${lang}`

/** Run `task` after at least MIN_INTERVAL_MS has passed since the previous one started. Serialised, FIFO. */
function queued<T>(task: () => Promise<T>): Promise<T> {
  const run = chain.then(async () => {
    const wait = MIN_INTERVAL_MS - (Date.now() - lastStartedAt)
    if (wait > 0) await new Promise((r) => setTimeout(r, wait))
    lastStartedAt = Date.now()
    return task()
  })
  chain = run.catch(() => undefined) // a failure must not wedge the queue for everyone behind it
  return run
}

/**
 * Resolve one coordinate to a place name, or null when: geocoding is off (no endpoint — **no request leaves the
 * device**), the provider fails, or the point is unresolvable. Never throws.
 *
 * Identical coordinates share one in-flight promise, so opening twelve photos of the same place makes one
 * request even while it is still outstanding.
 */
export async function resolvePlaceName(
  endpoint: string | null | undefined,
  lat: number,
  lng: number,
  lang: string,
  fetchImpl: typeof fetch = fetch,
): Promise<ResolvedPlace | null> {
  if (!endpoint) return null // the off state, asserted by a bite: no consent, no coordinate on the wire
  const key = keyOf(lat, lng, lang)
  const hit = cache.get(key)
  if (hit) return hit
  const pending = queued(async () => {
    const levels = await reverseGeocode(endpoint, lat, lng, lang, fetchImpl)
    const level = levels ? defaultLevel(levels) : null
    return level ? { name: level.name, level: level.key } : null
  })
  cache.set(key, pending)
  return pending
}

/** Drop the module cache — tests only; production wants one lookup per place per session. */
export function resetPlaceNameCache(): void {
  cache.clear()
  chain = Promise.resolve()
  lastStartedAt = 0
}
