import { describe, it, expect, vi } from 'vitest'
import { reverseGeocode, defaultLevel, nameAtLevel, type GeoLevel } from './geocode'

const ok = (body: unknown): Response => ({ ok: true, json: async () => body }) as unknown as Response
const ADDRESS = {
  address: { road: 'Musterstraße', house_number: '12', suburb: 'Bilk', city: 'Düsseldorf', state: 'Nordrhein-Westfalen', country: 'Deutschland' },
}
const HIERARCHY: GeoLevel[] = [
  { key: 'road', name: 'Musterstraße 12' },
  { key: 'suburb', name: 'Bilk' },
  { key: 'city', name: 'Düsseldorf' },
  { key: 'state', name: 'Nordrhein-Westfalen' },
  { key: 'country', name: 'Deutschland' },
]

describe('reverseGeocode (ADR 0016 — third-party, returns the address hierarchy)', () => {
  it('makes NO request when no endpoint is configured (off state) → null', async () => {
    const fetchImpl = vi.fn()
    expect(await reverseGeocode(null, 28.27, -16.61, 'de', fetchImpl)).toBeNull()
    expect(await reverseGeocode('', 28.27, -16.61, 'de', fetchImpl)).toBeNull()
    expect(fetchImpl).not.toHaveBeenCalled() // no coordinate ever leaves the device
  })

  it('resolves the finest→coarsest hierarchy from an identity-free coordinate request', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(ok(ADDRESS))
    const levels = await reverseGeocode('https://nominatim.example/', 51.2, 6.77, 'de', fetchImpl)
    expect(levels).toEqual(HIERARCHY)

    const [url, init] = fetchImpl.mock.calls[0]
    const u = new URL(url as string)
    expect(u.pathname).toBe('/reverse')
    expect(u.searchParams.get('addressdetails')).toBe('1')
    expect(u.searchParams.get('lat')).toBe('51.2')
    expect(u.searchParams.get('lon')).toBe('6.77')
    expect(u.searchParams.get('accept-language')).toBe('de')
    expect((init as RequestInit | undefined)?.credentials).toBeUndefined() // identity-free
  })

  it('returns null on a provider error, empty address, non-ok, or a thrown fetch (manual name stands)', async () => {
    expect(await reverseGeocode('https://n.example', 1, 2, 'en', vi.fn().mockResolvedValue(ok({ error: 'Unable to geocode' })))).toBeNull()
    expect(await reverseGeocode('https://n.example', 1, 2, 'en', vi.fn().mockResolvedValue(ok({ address: {} })))).toBeNull()
    expect(await reverseGeocode('https://n.example', 1, 2, 'en', vi.fn().mockResolvedValue({ ok: false } as Response))).toBeNull()
    expect(await reverseGeocode('https://n.example', 1, 2, 'en', vi.fn().mockRejectedValue(new Error('offline')))).toBeNull()
  })
})

describe('defaultLevel / nameAtLevel', () => {
  it('defaults to the city when present', () => {
    expect(defaultLevel(HIERARCHY)).toEqual({ key: 'city', name: 'Düsseldorf' })
  })
  it('falls back to the coarsest level when there is no city', () => {
    const rural: GeoLevel[] = [{ key: 'state', name: 'Kanaren' }, { key: 'country', name: 'Spanien' }]
    expect(defaultLevel(rural)).toEqual({ key: 'country', name: 'Spanien' })
    expect(defaultLevel([])).toBeNull()
  })
  it('nameAtLevel reads a chosen level', () => {
    expect(nameAtLevel(HIERARCHY, 'city')).toBe('Düsseldorf')
    expect(nameAtLevel(HIERARCHY, 'county')).toBeNull()
  })
})

describe('reverseGeocode — island level (e.g. Formentera)', () => {
  it('includes the `island` component (coarser than city, finer than state)', async () => {
    const body = { address: { city: 'Sant Francesc', island: 'Formentera', state: 'Illes Balears', country: 'España' } }
    const levels = await reverseGeocode('https://n.example', 38.68, 1.43, 'es', vi.fn().mockResolvedValue(ok(body)))
    expect(levels).toEqual([
      { key: 'city', name: 'Sant Francesc' },
      { key: 'island', name: 'Formentera' },
      { key: 'state', name: 'Illes Balears' },
      { key: 'country', name: 'España' },
    ])
  })
})
