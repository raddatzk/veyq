// @vitest-environment happy-dom
// The place editor with the member's map services off, on a server that offers a map (uc.memory.place A6, ADR 0016): the
// name field, and where the map would stand the offer to turn them on. The geocoder is the REAL one (`../geocode` is not
// mocked here) over a fetch spy, so "nothing is asked" is measured where a request would leave; the map is a stub whose
// presence is the tile layer's (the tile layer itself, over a fake Leaflet, is `MemoryLocationEditor.withdraw.test.ts`'s).
// Each negative has its control: the same editor with the map services on draws the map and asks the geocoder.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../../test/services'
import type { LatLng, MemoryLocation } from '../memoryLocation'
import { realI18n, t } from '../../../test/i18n'
import { i18n } from '../../../i18n'
import MapOffButton from './MapOffButton.vue'

const GEOCODER_HOST = 'geocoder.a6.example'
const MapStub = {
  props: ['tileUrl', 'attribution', 'maxZoom', 'center', 'zoom', 'marker', 'crosshair', 'dots'],
  methods: { fitPoints() {}, setView() {} },
  template: '<div class="map-stub"></div>',
}
const stubs = {
  MemoryMap: MapStub,
  VeyqDialog: { template: '<div class="dlg"><slot /></div>' },
  VeyqButton: { props: ['type', 'label', 'disabled'], template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>` },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<input class="name-input" :value="modelValue" @input="$emit('update:modelValue', $event.target.value); $emit('input', $event)" />`,
  },
  VeyqSelect: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: `<select class="level-select" :value="modelValue" @change="$emit('update:modelValue', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>`,
  },
}
const MEDOID: LatLng = { lat: 48.85, lng: 2.35 }

let fetchSpy: ReturnType<typeof vi.fn>
const geocoderAsked = () => fetchSpy.mock.calls.map((c) => String(c[0])).filter((u) => u.includes(GEOCODER_HOST))
const mountEditor = (location: MemoryLocation | null = null) =>
  mount(MemoryLocationEditor, { props: { location, medoid: MEDOID, points: [MEDOID] }, global: { plugins: [realI18n()], stubs, directives: { tooltip: {} } } })
const saved = (w: ReturnType<typeof mountEditor>) => w.emitted('save')?.[0]?.[0] as MemoryLocation | undefined
const turnOn = async (w: ReturnType<typeof mountEditor>) => {
  await w.get('.map-consent-prompt button').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  setTileUrl('https://tiles.a6.example/{z}/{x}/{y}.png')
  setGeocoderUrl(`https://${GEOCODER_HOST}`)
  setMapsConsent(false)
  fetchSpy = vi.fn(async () => new Response(JSON.stringify({ address: { city: 'Paris', country: 'France' } }), { status: 200 }))
  vi.stubGlobal('fetch', fetchSpy)
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MemoryLocationEditor with the map services off, on a server with a map', () => {
  it('is the name field with the offer naming the hosts: no map, no geocoder; control: with them on, the same editor asks both', async () => {
    const off = mountEditor()
    await flushPromises()
    expect(off.find('.name-input').exists()).toBe(true)
    expect(off.get('.map-consent-prompt').text()).toContain(t('memory.place.consentHosts', { tiles: 'tiles.a6.example', places: GEOCODER_HOST }))
    expect(off.find('.map-stub').exists(), 'no map, so no tile').toBe(false)
    expect(fetchSpy, 'nothing leaves the device').not.toHaveBeenCalled()

    off.unmount() // an open editor follows the switch (the watch); the control is a fresh one
    setMapsConsent(true) // control: the services on, the same editor
    const on = mountEditor()
    await flushPromises()
    expect(on.find('.map-stub').exists()).toBe(true)
    expect(on.find('.map-consent-prompt').exists()).toBe(false)
    expect(geocoderAsked()).toHaveLength(1)
    expect(geocoderAsked()[0]).toContain('lat=48.85')
  })

  it('saves a typed name as manual, at the photos’ medoid, and turns nothing on', async () => {
    const w = mountEditor()
    await flushPromises()
    await w.get('.name-input').setValue('At grandma’s')
    await w.get('form').trigger('submit')
    expect(saved(w)).toEqual({ name: 'At grandma’s', nameSource: 'manual', derived: MEDOID, derivedMode: 'auto', nameAnchor: undefined })
    expect(localStorage.getItem('veyq:maps-consent')).toBeNull()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('turned on in the editor, the map is drawn there and the typed name is kept; the map’s names are offered beside it', async () => {
    const w = mountEditor()
    await flushPromises()
    await w.get('.name-input').setValue('At grandma’s')
    await turnOn(w)

    expect(w.find('.map-stub').exists()).toBe(true)
    expect(w.find('.map-consent-prompt').exists()).toBe(false)
    expect((w.get('.name-input').element as HTMLInputElement).value).toBe('At grandma’s')
    expect(geocoderAsked(), 'the map asks for names now').toHaveLength(1)
    expect(w.get('.memory-location-nametoggle-btn').text()).toBe(t('memory.place.nameFromMap'))
    await w.get('form').trigger('submit')
    expect(saved(w)).toEqual({ name: 'At grandma’s', nameSource: 'manual', derived: MEDOID, derivedMode: 'auto', nameAnchor: undefined })
  })

  it('turned on with no name typed yet, the map names the place as it would on an opening with the map', async () => {
    const w = mountEditor()
    await flushPromises()
    await turnOn(w)

    expect((w.get('.level-select').element as HTMLSelectElement).value).toBe('city')
    await w.get('form').trigger('submit')
    expect(saved(w)).toEqual({ name: 'Paris', nameSource: 'geocoded', derived: MEDOID, derivedMode: 'auto', nameAnchor: { ...MEDOID, level: 'city' } })
  })

  // ADR 0016 (owner 2026-11-09): the offer and the map's control call the choice "Map services", on or off. Asserted as
  // written, in both languages; the offer keeps saying what the services receive and to whom.
  it('words the offer and the map’s control as Map services, in English and in German', () => {
    const en = mountEditor()
    expect(en.get('.map-consent-prompt button').text()).toBe('Turn map services on')
    expect(en.get('.map-consent-prompt').text()).toContain('The map from tiles.a6.example, place names from geocoder.a6.example.')
    expect(en.get('.map-consent-prompt').text()).toContain('IP address')
    expect(mount(MapOffButton, { global: { plugins: [realI18n()] } }).text()).toBe('Turn map services off')

    const plugin = realI18n()
    i18n.global.locale.value = 'de'
    const de = mount(MemoryLocationEditor, { props: { location: null, medoid: MEDOID, points: [MEDOID] }, global: { plugins: [plugin], stubs, directives: { tooltip: {} } } })
    expect(de.get('.map-consent-prompt button').text()).toBe('Kartendienste einschalten')
    expect(de.get('.map-consent-prompt').text()).toContain('Die Karte von tiles.a6.example, Ortsnamen von geocoder.a6.example.')
    expect(de.get('.map-consent-prompt').text()).toContain('IP-Adresse')
    expect(mount(MapOffButton, { global: { plugins: [plugin] } }).text()).toBe('Kartendienste ausschalten')
  })
})
