// @vitest-environment happy-dom
// A map the member never moved sets no point (uc.memory.place E3, inbox #834). The REAL `MemoryMap` over a fake Leaflet that
// keeps the view it was set to: the opening frame (`whenReady`) and every programmatic move report `user: false`, and only
// the member's input on the map's container (a `pointerdown`, a `wheel`, a `keydown`) makes the next frame the member's.
// Before #834 an editor for a Memory without photo GPS stored its opening view, the world's centre, as a `fixed` point.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const leaflet = vi.hoisted(() => {
  const view = { lat: 0, lng: 0, zoom: 0 }
  const handlers: Record<string, () => void> = {}
  const map = {
    setView: (c: [number, number], z: number) => {
      view.lat = c[0]
      view.lng = c[1]
      view.zoom = z
      return map
    },
    fitBounds: () => map,
    panTo: () => map,
    getCenter: () => ({ lat: view.lat, lng: view.lng }),
    getZoom: () => view.zoom,
    removeLayer: () => map,
    remove: () => map,
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
    tileLayer: () => ({ addTo: () => ({}) }),
    layerGroup: layer,
    marker: () => ({ addTo: () => ({}), setLatLng: () => ({}) }),
    circleMarker: () => ({ addTo: () => ({}) }),
    divIcon: () => ({}),
    Control: class {
      addTo() {
        return this
      }
    },
  }
  /** The map's view moves (a pan or a zoom finished) - Leaflet's `moveend`. */
  const moveTo = (lat: number, lng: number, zoom = view.zoom) => {
    view.lat = lat
    view.lng = lng
    view.zoom = zoom
    handlers.moveend()
  }
  return { L, view, moveTo }
})
vi.mock('leaflet', () => ({ default: leaflet.L }))
vi.mock('../geocode', async (orig) => ({ ...(await orig<typeof import('../geocode')>()), reverseGeocode: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import { reverseGeocode } from '../geocode'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../../test/services'
import { normalizeLocation, WORLD_VIEW, type LatLng, type MemoryLocation } from '../memoryLocation'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqDialog: { template: '<div class="dlg"><slot /></div>' },
  VeyqButton: { props: ['type', 'label', 'disabled'], template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>` },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input class="name-input" :value="modelValue" @input="$emit('update:modelValue', $event.target.value); $emit('input', $event)" />`,
  },
  VeyqSelect: { props: ['modelValue', 'options'], template: '<select class="level-select" :value="modelValue"></select>' },
}
const ROME: LatLng = { lat: 41.9, lng: 12.5 }
const MEDOID: LatLng = { lat: 41.89, lng: 12.49 }

const mountEditor = (location: MemoryLocation | null = null, medoid: LatLng | null = null) =>
  mount(MemoryLocationEditor, {
    props: { location, medoid, points: medoid ? [medoid] : [] },
    global: { plugins: [realI18n()], stubs, directives: { tooltip: {} } },
  })
const saved = (w: ReturnType<typeof mountEditor>) => w.emitted('save')?.[0]?.[0] as MemoryLocation | undefined
const submit = (w: ReturnType<typeof mountEditor>) => w.get('form').trigger('submit')
/** The member's input on the map - what MemoryMap reads as "the next frame is the member's". */
const touchMap = (w: ReturnType<typeof mountEditor>, ev: 'pointerdown' | 'wheel' | 'keydown' = 'pointerdown') =>
  w.get('.memory-map').element.dispatchEvent(new Event(ev, { bubbles: true }))

beforeEach(() => {
  vi.mocked(reverseGeocode).mockReset().mockResolvedValue([{ key: 'city', name: 'Roma' }])
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('https://geo.example')
  setMapsConsent(true)
})

describe('MemoryLocationEditor — a map the member never moved sets no point', () => {
  it('saved untouched (no photo GPS): a name only, `auto`, no point, and no lookup of the opening view', async () => {
    vi.mocked(reverseGeocode).mockResolvedValue(null) // were the opening view looked up, the name field would stay a field
    const w = mountEditor()
    await flushPromises()
    expect(leaflet.view, 'the editor opened on the world view').toMatchObject({ ...WORLD_VIEW.center, zoom: WORLD_VIEW.zoom })

    await w.get('.name-input').setValue('Lido di Ostia')
    await submit(w)
    await flushPromises()

    expect(saved(w)).toEqual({ name: 'Lido di Ostia', nameSource: 'manual', derived: undefined, derivedMode: 'auto', nameAnchor: undefined })
    expect(reverseGeocode, 'the opening view is no place to name').not.toHaveBeenCalled()
    expect(w.get('.memory-location-pill').text()).toContain(t('memory.place.nameOnly'))
  })

  it('control: after the member moves the map, the framed point is stored `fixed` (a pan, and a zoom by the wheel)', async () => {
    const panned = mountEditor()
    await flushPromises()
    touchMap(panned)
    leaflet.moveTo(ROME.lat, ROME.lng)
    await flushPromises()
    expect(panned.get('.memory-location-pill').text()).toContain(t('memory.place.customPlace'))
    await panned.get('.name-input').setValue('Roma')
    await submit(panned)
    expect(saved(panned)).toMatchObject({ derivedMode: 'fixed', derived: ROME })

    const zoomed = mountEditor()
    await flushPromises()
    touchMap(zoomed, 'wheel')
    leaflet.moveTo(ROME.lat, ROME.lng, 6)
    await zoomed.get('.name-input').setValue('Roma')
    await submit(zoomed)
    expect(saved(zoomed)).toMatchObject({ derivedMode: 'fixed', derived: ROME })
  })

  it('a move the member did not make (no input on the map first) sets no point', async () => {
    const w = mountEditor()
    await flushPromises()
    leaflet.moveTo(ROME.lat, ROME.lng) // e.g. a programmatic move, or a resize
    await w.get('.name-input').setValue('Lido di Ostia')
    await submit(w)
    expect(saved(w)).toMatchObject({ derivedMode: 'auto', derived: undefined })
  })

  it('a point stored at the opening view reads as none, and re-saving drops it for good', async () => {
    const stored = normalizeLocation({ name: 'Lido di Ostia', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 20, lng: 0 } })
    expect(stored).toMatchObject({ derivedMode: 'auto', derived: undefined })
    const w = mountEditor(stored)
    await flushPromises()
    expect(w.get('.memory-location-pill').text()).toContain(t('memory.place.nameOnly'))

    await submit(w)
    expect(saved(w)).toEqual({ name: 'Lido di Ostia', nameSource: 'manual', derived: undefined, derivedMode: 'auto', nameAnchor: undefined })
  })

  it('with photo GPS the medoid is kept as today (`auto`), the opening frame changes nothing', async () => {
    const w = mountEditor(null, MEDOID)
    await flushPromises()
    await w.get('form').trigger('submit')
    expect(saved(w)).toMatchObject({ name: 'Roma', nameSource: 'geocoded', derived: MEDOID, derivedMode: 'auto' })
  })

  it('a point stored by hand is kept when the editor is saved untouched', async () => {
    const w = mountEditor({ name: 'Roma', nameSource: 'manual', derivedMode: 'fixed', derived: ROME })
    await flushPromises()
    await submit(w)
    expect(saved(w)).toMatchObject({ derivedMode: 'fixed', derived: ROME })
  })
})
