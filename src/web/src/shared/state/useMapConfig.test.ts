// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { useMapConfig, setMapsConsent, serviceHost } from './useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../test/services'

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

  it('reads the addresses the server carries on its capabilities, and offers no map where it serves none (ADR 0088 decision 7)', () => {
    const c = useMapConfig()
    setTileUrl('https://tiles.other.example/{z}/{x}/{y}.png', '© Other')
    expect(c.tileUrl.value).toBe('https://tiles.other.example/{z}/{x}/{y}.png')
    expect(c.tileAttribution.value).toBe('© Other')
    expect(c.mapsOffered.value).toBe(true)
    setTileUrl('') // `memories.map` not served: unconfigured, or switched off by the server or the space
    expect(c.mapsOffered.value).toBe(false)
  })

  it('a withdrawal removes the stored flag; one in another tab of this browser applies here at once (the storage event)', () => {
    const c = useMapConfig()
    setMapsConsent(true)
    expect(localStorage.getItem('veyq:maps-consent')).not.toBeNull()
    setMapsConsent(false)
    expect(localStorage.getItem('veyq:maps-consent')).toBeNull()

    setMapsConsent(true)
    localStorage.removeItem('veyq:maps-consent') // what the other tab's withdrawal leaves behind
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:maps-consent', newValue: null }))
    expect(c.mapsAvailable.value).toBe(false)

    const given = JSON.stringify({ tiles: 'tiles.example', places: 'nominatim.example' }) // and a consent given there
    localStorage.setItem('veyq:maps-consent', given)
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:maps-consent', newValue: given }))
    expect(c.mapsAvailable.value).toBe(true)
  })

  it('names a service by its host, the address itself where it names none', () => {
    expect(serviceHost('https://tile.openstreetmap.org/{z}/{x}/{y}.png')).toBe('tile.openstreetmap.org')
    expect(serviceHost('https://{s}.tiles.example:8443/{z}/{x}/{y}.png')).toBe('{s}.tiles.example:8443')
    expect(serviceHost('tiles')).toBe('tiles')
  })

  describe('a consent names its hosts (GDPR Art. 4(11), 7) — a new recipient is asked again', () => {
    const KEY = 'veyq:maps-consent'
    const fromAnotherTab = (value: string) => {
      localStorage.setItem(KEY, value)
      window.dispatchEvent(new StorageEvent('storage', { key: KEY, newValue: value }))
    }

    it('stores the tile host and the geocoder host it was given for', () => {
      setMapsConsent(true)
      expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ tiles: 'tiles.example', places: 'nominatim.example' })
    })

    it('holds for the same hosts, whatever the path', () => {
      const c = useMapConfig()
      setMapsConsent(true)
      setTileUrl('https://tiles.example/other/{z}/{x}/{y}.png')
      expect(c.mapsAvailable.value).toBe(true)
      expect(c.consentRenewal.value).toBeNull()
    })

    it('a changed tile host is no consent: no map until the member agrees, and the renewal names the new host', () => {
      const c = useMapConfig()
      setMapsConsent(true)
      setTileUrl('https://tiles.other.example/{z}/{x}/{y}.png')
      expect(c.mapsAvailable.value).toBe(false)
      expect(c.geocodingAvailable.value).toBe(false)
      expect(c.consentRenewal.value).toEqual({ tiles: 'tiles.other.example', places: 'nominatim.example' })
      setMapsConsent(true)
      expect(c.mapsAvailable.value).toBe(true)
    })

    it('a changed or newly added geocoder host asks again; one the Server Owner removed needs no new consent', () => {
      const c = useMapConfig()
      setMapsConsent(true)
      setGeocoderUrl('https://geo.other.example')
      expect(c.geocodingAvailable.value, 'no request to a geocoder the member did not consent to').toBe(false)
      expect(c.mapsAvailable.value).toBe(false)

      setGeocoderUrl('') // removed: a recipient fewer
      expect(c.mapsAvailable.value).toBe(true)
      expect(c.geocodingAvailable.value).toBe(false)

      setMapsConsent(true) // given while no geocoder was served
      setGeocoderUrl('https://nominatim.example') // then one is added
      expect(c.geocodingAvailable.value).toBe(false)
      expect(c.mapsAvailable.value).toBe(false)
    })

    it('the legacy value "1" names no host, so such a consent is asked once more', () => {
      const c = useMapConfig()
      fromAnotherTab('1')
      expect(c.mapsConsent.value).toBe(false)
      expect(c.mapsAvailable.value).toBe(false)
      expect(c.consentGivenFor.value).toEqual({ tiles: null, places: null })
      expect(c.consentRenewal.value).toEqual({ tiles: 'tiles.example', places: 'nominatim.example' })
    })
  })
})
