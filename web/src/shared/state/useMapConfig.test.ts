// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { useMapConfig, setMapsConsent, setTileUrl, setGeocoderUrl } from './useMapConfig'

beforeEach(() => {
  // reset the singleton to a known baseline (consent off, both providers configured)
  setMapsConsent(false)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('https://nominatim.example')
})

describe('useMapConfig — the coupled two-stage map/geocoding opt-in (ADR 0016/0018)', () => {
  it('is off by default → no map, no geocoding (the location feature is unavailable)', () => {
    const c = useMapConfig()
    expect(c.mapsConsent.value).toBe(false)
    expect(c.mapsAvailable.value).toBe(false)
    expect(c.geocodingAvailable.value).toBe(false)
  })

  it('consent turns on BOTH tiles and geocoding (coupled — no middle stage)', () => {
    const c = useMapConfig()
    setMapsConsent(true)
    expect(c.mapsAvailable.value).toBe(true)
    expect(c.geocodingAvailable.value).toBe(true)
  })

  it('geocoding stays off when no geocoder is configured — maps (tiles) can still be on for typed names', () => {
    const c = useMapConfig()
    setMapsConsent(true)
    setGeocoderUrl('')
    expect(c.mapsAvailable.value).toBe(true) // tiles present → the map + a typed name
    expect(c.geocodingAvailable.value).toBe(false)
  })

  it('with no tile URL there is no map at all, even consented', () => {
    const c = useMapConfig()
    setMapsConsent(true)
    setTileUrl('')
    expect(c.mapsAvailable.value).toBe(false)
  })

  it('persists the consent across composable instances (localStorage-backed)', () => {
    setMapsConsent(true)
    expect(useMapConfig().mapsConsent.value).toBe(true)
    setMapsConsent(false)
    expect(useMapConfig().mapsConsent.value).toBe(false)
  })
})
