// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../geocode', async (orig) => ({ ...(await orig<typeof import('../geocode')>()), reverseGeocode: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import { reverseGeocode, type GeoLevel } from '../geocode'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../../test/services'
import type { LatLng, MemoryLocation } from '../memoryLocation'
import { realI18n, t } from '../../../test/i18n'

const HIERARCHY: GeoLevel[] = [
  { key: 'road', name: 'Musterstraße 12' },
  { key: 'suburb', name: 'Bilk' },
  { key: 'city', name: 'Düsseldorf' },
  { key: 'state', name: 'Nordrhein-Westfalen' },
]

const fitPointsSpy = vi.fn()
const setViewSpy = vi.fn()
const MapStub = {
  props: ['tileUrl', 'attribution', 'maxZoom', 'center', 'zoom', 'marker', 'crosshair', 'dots'],
  emits: ['frame'],
  methods: { fitPoints: fitPointsSpy, setView: setViewSpy },
  template: `<div class="map-stub"><button type="button" class="ev-pan" @click="$emit('frame', { lat: 40, lng: 10, zoom: 12, user: true })"></button></div>`,
}
const stubs = {
  MemoryMap: MapStub,
  Dialog: { template: '<div class="dlg"><slot /></div>' },
  Button: { props: ['type', 'label', 'disabled'], template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>` },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input class="name-input" :value="modelValue" @input="$emit('update:modelValue', $event.target.value); $emit('input', $event)" />`,
  },
  Select: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: `<select class="level-select" :value="modelValue" @change="$emit('update:modelValue', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>`,
  },
}

const MEDOID: LatLng = { lat: 28.27, lng: -16.61 }
const POINTS: LatLng[] = [MEDOID, { lat: 28.47, lng: -16.25 }]

function mountEditor(location: MemoryLocation | null, medoid: LatLng | null = MEDOID, points: LatLng[] = POINTS) {
  return mount(MemoryLocationEditor, { props: { location, medoid, points }, global: { plugins: [realI18n()], stubs, directives: { tooltip: {} } } })
}
const saved = (w: ReturnType<typeof mountEditor>) => w.emitted('save')?.[0]?.[0] as MemoryLocation | null | undefined
const pill = (w: ReturnType<typeof mountEditor>) => w.find('.memory-location-pill').text()

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(reverseGeocode).mockResolvedValue(HIERARCHY)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('https://nominatim.example')
  setMapsConsent(true)
})

describe('MemoryLocationEditor (intent-first)', () => {
  it('auto: frames the photos, names the medoid at the default (city) level, saves auto geocoded', async () => {
    const w = mountEditor(null)
    await flushPromises()

    expect(fitPointsSpy).toHaveBeenCalledWith(POINTS) // framed to the photo spread
    expect(pill(w)).toContain(t('memory.place.followsPhotos'))
    expect((w.find('.level-select').element as HTMLSelectElement).value).toBe('city') // default level = city
    await w.find('form').trigger('submit')

    expect(saved(w)).toEqual({
      name: 'Düsseldorf', nameSource: 'geocoded', derived: MEDOID, derivedMode: 'auto',
      nameAnchor: { lat: 28.27, lng: -16.61, level: 'city' },
    })
  })

  it('moving the map by hand takes over → fixed at the framed centre', async () => {
    const w = mountEditor(null)
    await flushPromises()
    await w.find('.ev-pan').trigger('click') // user pans → {40,10}
    await flushPromises()

    expect(pill(w)).toContain(t('memory.place.customPlace'))
    await w.find('form').trigger('submit')
    expect(saved(w)).toMatchObject({ derivedMode: 'fixed', derived: { lat: 40, lng: 10 } })
  })

  it('“follow photos” returns to auto and re-centres on the medoid', async () => {
    const w = mountEditor(null)
    await flushPromises()
    await w.find('.ev-pan').trigger('click') // → fixed
    await flushPromises()
    await w.find('.memory-location-follow').trigger('click') // → auto again
    await flushPromises()

    expect(setViewSpy).toHaveBeenCalledWith(MEDOID, expect.any(Number))
    expect(pill(w)).toContain(t('memory.place.followsPhotos'))
    await w.find('form').trigger('submit')
    expect(saved(w)).toMatchObject({ derivedMode: 'auto', derived: MEDOID })
  })

  it('picking a coarser/finer level changes the name and the stored level', async () => {
    const w = mountEditor(null)
    await flushPromises()
    await w.find('.level-select').setValue('state') // the name field IS the level picker when geocoded
    await w.find('form').trigger('submit')
    expect(saved(w)).toMatchObject({ name: 'Nordrhein-Westfalen', nameSource: 'geocoded', nameAnchor: { level: 'state' } })
  })

  it('“enter your own name” switches to a typed, manual name (no anchor level)', async () => {
    const w = mountEditor(null)
    await flushPromises()
    await w.find('.memory-location-nametoggle-btn').trigger('click') // switch from the level picker to a typed name
    await w.find('.name-input').setValue('Grandmas garden')
    await w.find('form').trigger('submit')
    expect(saved(w)).toMatchObject({ name: 'Grandmas garden', nameSource: 'manual', derivedMode: 'auto', nameAnchor: undefined })
  })

  it('removing emits save(null) — the parent preserves the date', async () => {
    const existing: MemoryLocation = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed', derived: MEDOID }
    const w = mountEditor(existing)
    await flushPromises()
    await w.findAll('button').find((b) => b.text() === t('memory.place.remove'))!.trigger('click')
    expect(w.emitted('save')?.[0]?.[0]).toBeNull()
  })

  it('with geocoding off: no external call, no level picker, the name is typed', async () => {
    setGeocoderUrl('')
    const w = mountEditor(null)
    await flushPromises()

    expect(reverseGeocode).not.toHaveBeenCalled()
    expect(w.find('.level-select').exists()).toBe(false)
    await w.find('.name-input').setValue('Grandmas garden')
    await w.find('form').trigger('submit')
    expect(saved(w)).toMatchObject({ name: 'Grandmas garden', nameSource: 'manual', derived: MEDOID, derivedMode: 'auto' })
  })
})
