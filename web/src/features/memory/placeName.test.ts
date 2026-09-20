// uc.memory.place — the request discipline a per-photo trigger owes the provider (ADR 0016 § Request
// discipline). Public Nominatim's policy is explicit: at most 1 request/second, results MUST be cached on our
// side, and repeating the same query may get a client blocked. This CR adds the first caller in the client that
// could generate volume at all, so these are compliance assertions, not performance ones.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { resolvePlaceName, resetPlaceNameCache } from './placeName'

const ENDPOINT = 'https://geo.example/'
const OK = (city: string) =>
  ({ ok: true, status: 200, json: async () => ({ address: { city, country: 'Deutschland' } }) }) as unknown as Response

beforeEach(() => resetPlaceNameCache())

describe('resolvePlaceName', () => {
  it('makes NO request when geocoding is not configured — the off state', async () => {
    const fetchImpl = vi.fn()
    await expect(resolvePlaceName(null, 48.137, 11.575, 'de', fetchImpl as unknown as typeof fetch)).resolves.toBeNull()
    await expect(resolvePlaceName(undefined, 48.137, 11.575, 'de', fetchImpl as unknown as typeof fetch)).resolves.toBeNull()
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  // B2.2 — the provider's caching obligation. Twelve photos from one balcony differ in the fifth decimal;
  // without the rounding each is a fresh query, which is exactly the "sending repeatedly the same query"
  // behaviour the policy warns about. Lever: drop `toFixed(CACHE_PRECISION)` from the cache key → 12 fetches.
  it('asks once per PLACE, not once per photo — coordinates are rounded into the cache key', async () => {
    const fetchImpl = vi.fn(async () => OK('München'))
    const results = []
    for (let i = 0; i < 12; i++) {
      results.push(await resolvePlaceName(ENDPOINT, 48.1371 + i * 0.00001, 11.5751 + i * 0.00001, 'de', fetchImpl as unknown as typeof fetch))
    }
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(results.every((r) => r?.name === 'München')).toBe(true)
  })

  it('shares one in-flight request between callers that arrive together', async () => {
    const fetchImpl = vi.fn(async () => OK('München'))
    const all = await Promise.all(
      Array.from({ length: 5 }, () => resolvePlaceName(ENDPOINT, 48.137, 11.575, 'de', fetchImpl as unknown as typeof fetch)),
    )
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(all.every((r) => r?.name === 'München')).toBe(true)
  })

  it('keys the cache on the language too — the same spot in another locale is another name', async () => {
    const fetchImpl = vi.fn(async () => OK('München'))
    await resolvePlaceName(ENDPOINT, 48.137, 11.575, 'de', fetchImpl as unknown as typeof fetch)
    await resolvePlaceName(ENDPOINT, 48.137, 11.575, 'en', fetchImpl as unknown as typeof fetch)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('a provider failure resolves to null and does not wedge the queue behind it', async () => {
    const fetchImpl = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(OK('Berlin'))
    await expect(resolvePlaceName(ENDPOINT, 52.52, 13.405, 'de', fetchImpl as unknown as typeof fetch)).resolves.toBeNull()
    await expect(resolvePlaceName(ENDPOINT, 50.11, 8.68, 'de', fetchImpl as unknown as typeof fetch)).resolves.toMatchObject({ name: 'Berlin' })
  })
})
