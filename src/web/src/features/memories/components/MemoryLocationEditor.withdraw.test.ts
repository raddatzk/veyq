// @vitest-environment happy-dom
// The place editor's "Turn map off" (uc.memory.place A5), with the REAL `MemoryMap` over a fake Leaflet: the withdrawal
// removes the map, makes no further tile layer, lets the pending (debounced) lookup ask no geocoder, and puts the consent prompt where
// the map was; turning it back on there brings the map back.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const leaflet = vi.hoisted(() => {
  const calls = { tileLayers: 0, removed: 0 }
  const handlers: Record<string, () => void> = {}
  const map = {
    setView: () => map,
    fitBounds: () => map,
    panTo: () => map,
    getCenter: () => ({ lat: 40, lng: 10 }),
    getZoom: () => 12,
    removeLayer: () => map,
    remove: () => {
      calls.removed++
    },
    on: (ev: string, cb: () => void) => {
      handlers[ev] = cb
      return map
    },
    whenReady: (cb: () => void) => {
      cb()
      return map
    },
  }
  const layer = () => {
    const g = { addTo: () => g, clearLayers: () => g }
    return g
  }
  const L = {
    map: () => map,
    tileLayer: () => {
      calls.tileLayers++
      return { addTo: () => ({}) }
    },
    layerGroup: layer,
    marker: () => ({ addTo: () => ({}), setLatLng: () => ({}) }),
    circleMarker: () => ({ addTo: () => ({}) }),
    divIcon: () => ({}),
    // The attribution control the maps add (`mapAttribution.ts`); what it draws is not this test's subject.
    Control: class {
      addTo() {
        return this
      }
    },
  }
  return { L, calls, handlers }
})
vi.mock('leaflet', () => ({ default: leaflet.L }))
vi.mock('../geocode', async (orig) => ({ ...(await orig<typeof import('../geocode')>()), reverseGeocode: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import { reverseGeocode } from '../geocode'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../../test/services'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqDialog: { template: '<div class="dlg"><slot /></div>' },
  VeyqButton: { props: ['type', 'label', 'disabled'], template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>` },
}
// No location and no photo GPS: the editor opens `fixed`, so the map's first frame schedules a lookup of its centre.
const mountEditor = () =>
  mount(MemoryLocationEditor, { props: { location: null, medoid: null, points: [] }, global: { plugins: [realI18n()], stubs, directives: { tooltip: {} } } })

beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(reverseGeocode).mockReset().mockResolvedValue([{ key: 'city', name: 'Rom' }])
  leaflet.calls.tileLayers = 0
  leaflet.calls.removed = 0
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('https://geo.example')
  setMapsConsent(true)
})
afterEach(() => {
  vi.useRealTimers()
})

describe('MemoryLocationEditor — turning the map off', () => {
  it('removes the map, requests no further tile and no name, and shows the consent prompt where the map was', async () => {
    const w = mountEditor() // its map's first frame schedules the lookup synchronously; no timer has run yet
    expect(leaflet.calls.tileLayers).toBe(1)
    expect(w.find('.memory-map').exists()).toBe(true)
    expect(vi.getTimerCount(), 'a lookup is pending').toBeGreaterThan(0)
    const asked = vi.mocked(reverseGeocode).mock.calls.length // the editor's own opening lookup of the framed point

    await w.get('.veyq-map-off').trigger('click') // a lookup of the framed centre is still pending
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    expect(localStorage.getItem('veyq:maps-consent')).toBeNull()
    expect(leaflet.calls.removed).toBe(1)
    expect(w.find('.memory-map').exists()).toBe(false)
    expect(vi.mocked(reverseGeocode).mock.calls.length, 'no name is looked up after the withdrawal').toBe(asked)
    expect(w.find('.map-consent-prompt').exists()).toBe(true)
    expect(w.text()).toContain(t('memory.place.consentBody'))
    expect(w.find('.memory-location-name input, .memory-location-name-field').exists()).toBe(true) // still a name field
    expect(leaflet.calls.tileLayers).toBe(1)

    await w.get('.map-consent-prompt button').trigger('click') // "Enable maps" there
    await flushPromises()
    expect(localStorage.getItem('veyq:maps-consent')).not.toBeNull()
    expect(w.find('.memory-map').exists()).toBe(true)
    expect(leaflet.calls.tileLayers).toBe(2)
  })

  it('a changed geocoder host asks again: no geocoding request and no tile layer until the member agrees', async () => {
    setGeocoderUrl('https://geo.new.example') // the Server Owner changed the place-name service after the consent
    const w = mountEditor()
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()

    expect(reverseGeocode, 'nothing asked of a geocoder the member did not consent to').not.toHaveBeenCalled()
    expect(leaflet.calls.tileLayers).toBe(0)
    expect(w.get('.map-consent-renewal').text()).toContain('geo.new.example')
  })
})
