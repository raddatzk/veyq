// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub Leaflet: happy-dom can't run a real slippy map. The fake records every marker (with its click handler) so a
// test can assert what got pinned and simulate a pin click.
const leaflet = vi.hoisted(() => {
  const markers: Array<{ latlng: [number, number]; click: ((e?: unknown) => void) | null }> = []
  // How often a map was created with its tile layer, and removed: a withdrawn consent must remove it and add no other.
  const calls = { tileLayers: 0, removed: 0, attribution: undefined as string | undefined }
  const map = {
    setView: () => map,
    fitBounds: () => map,
    remove: () => {
      calls.removed++
    },
    on: () => map,
    // A trivial projection: layer pixels ∝ lat/lng, so far-apart pins never cluster in these contract tests.
    latLngToLayerPoint: ([lat, lng]: [number, number]) => ({ x: lng * 1000, y: lat * 1000 }),
    layerPointToLatLng: ([x, y]: [number, number]) => ({ lat: y / 1000, lng: x / 1000 }),
  }
  const L = {
    map: () => map,
    tileLayer: (_url: string, options?: { attribution?: string }) => {
      calls.tileLayers++
      calls.attribution = options?.attribution
      return { addTo: () => ({}) }
    },
    layerGroup: () => {
      const g = { addTo: () => g, clearLayers: () => markers.splice(0), remove: () => {} }
      return g
    },
    marker: (latlng: [number, number]) => {
      const mk = {
        latlng,
        click: null as ((e?: unknown) => void) | null,
        bindTooltip: () => mk,
        setIcon: () => mk,
        on: (ev: string, cb: (e?: unknown) => void) => {
          if (ev === 'click') mk.click = cb
          return mk
        },
        addTo: () => mk,
      }
      markers.push(mk)
      return mk
    },
    polyline: () => ({ on: () => ({}), addTo: () => ({}) }),
    divIcon: () => ({}),
    DomEvent: { stop: () => {} },
  }
  return { L, markers, calls }
})
vi.mock('leaflet', () => ({ default: leaflet.L }))

import { mount, flushPromises } from '@vue/test-utils'
import SpaceMap from './SpaceMap.vue'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { DEFAULT_TEST_TILES, setTileUrl } from '../../../test/services'
import { MEMORY_KIND } from '../kinds'
import type { LocatedPin } from '../spaceMap'
import { realI18n, t } from '../../../test/i18n'
import { treeNode } from '../../../test/models'

const stubs = {
  VeyqButton: { props: ['label'], template: `<button class="btn-stub" @click="$emit('click', $event)">{{ label }}</button>` },
}

const pin = (id: string, name: string, lat: number, lng: number): LocatedPin => ({
  node: treeNode({ id, name, kind: MEMORY_KIND, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 }),
  location: { name: `${name} place`, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } },
  date: null,
})

const mountMap = (pins: LocatedPin[]) => mount(SpaceMap, { props: { pins }, global: { plugins: [realI18n()], stubs } })

beforeEach(() => {
  leaflet.markers.splice(0)
  leaflet.calls.tileLayers = 0
  leaflet.calls.removed = 0
  setMapsConsent(true) // consented by default; the opt-in test turns it off
})

describe('SpaceMap', () => {
  it('renders a marker per pin and emits open with the node id on a pin click', async () => {
    const wrapper = mountMap([pin('m1', 'Rome', 41.9, 12.5), pin('m2', 'Paris', 48.85, 2.35)])
    await flushPromises()

    expect(wrapper.find('.space-map-canvas').exists()).toBe(true)
    expect(leaflet.markers.map((m) => m.latlng)).toEqual([
      [41.9, 12.5],
      [48.85, 2.35],
    ])

    leaflet.markers[1].click?.() // click the Paris pin
    expect(wrapper.emitted('open')?.[0]).toEqual(['m2'])
  })

  it('shows the opt-in prompt when maps are off, and reveals the map once enabled', async () => {
    setMapsConsent(false)
    const wrapper = mountMap([pin('m1', 'Rome', 41.9, 12.5)])
    await flushPromises()

    expect(wrapper.text()).toContain(t('memory.place.consentTitle'))
    expect(wrapper.find('.space-map-canvas').exists()).toBe(false)

    await wrapper.find('.btn-stub').trigger('click') // "Enable maps"
    await flushPromises()

    expect(wrapper.find('.space-map-canvas').exists()).toBe(true)
    expect(leaflet.markers.map((m) => m.latlng)).toEqual([[41.9, 12.5]])
  })

  it('shows the empty state when consented with zero pins', async () => {
    const wrapper = mountMap([])
    await flushPromises()

    expect(wrapper.text()).toContain(t('memory.map.empty'))
    expect(wrapper.find('.space-map-canvas').exists()).toBe(false)
  })

  it('heads its panel one level below its host: h2 under a folder\'s h1, h3 under a person\'s Memories', async () => {
    setMapsConsent(false)
    const level = mountMap([])
    const person = mount(SpaceMap, { props: { pins: [], headingLevel: 3 }, global: { plugins: [realI18n()], stubs } })
    await flushPromises()

    expect(level.get('h2.map-consent-prompt-title').text()).toBe(t('memory.place.consentTitle'))
    expect(level.find('h3').exists()).toBe(false)
    expect(person.get('h3.map-consent-prompt-title').text()).toBe(t('memory.place.consentTitle'))
    expect(person.find('h2').exists()).toBe(false)
  })

  it('hands Leaflet the attribution escaped: it writes it as HTML, and the Server Owner\'s text must stay text', async () => {
    setTileUrl(DEFAULT_TEST_TILES, '© <a href="https://evil.example">Tiles</a> & co') // the consented host, new text
    setMapsConsent(true)
    mountMap([pin('m1', 'Rome', 41.9, 12.5)])
    await flushPromises()
    expect(leaflet.calls.attribution).toBe('© &lt;a href=&quot;https://evil.example&quot;&gt;Tiles&lt;/a&gt; &amp; co')
  })

  it('"Turn map off" withdraws at once: the map is removed, no further tile layer is made, the consent prompt returns', async () => {
    const wrapper = mountMap([pin('m1', 'Rome', 41.9, 12.5)])
    await flushPromises()
    expect(leaflet.calls.tileLayers).toBe(1)

    await wrapper.get('.veyq-map-off').trigger('click')
    await flushPromises()

    expect(localStorage.getItem('veyq:maps-consent')).toBeNull()
    expect(leaflet.calls.removed).toBe(1)
    expect(wrapper.find('.space-map-canvas').exists()).toBe(false)
    expect(wrapper.text()).toContain(t('memory.place.consentTitle'))

    await wrapper.setProps({ pins: [pin('m1', 'Rome', 41.9, 12.5), pin('m2', 'Paris', 48.85, 2.35)] }) // the level moves on
    await flushPromises()
    expect(leaflet.calls.tileLayers, 'no tile layer after the withdrawal').toBe(1)
  })

  it('a new tile host asks again: no tile layer for it, the prompt names it, the map comes once the member agrees', async () => {
    const wrapper = mountMap([pin('m1', 'Rome', 41.9, 12.5)]) // consented for the default tile host
    await flushPromises()
    expect(leaflet.calls.tileLayers).toBe(1)

    setTileUrl('https://tiles.new.example/{z}/{x}/{y}.png') // the Server Owner changed the tile service
    await flushPromises()
    expect(leaflet.calls.removed).toBe(1)
    expect(wrapper.find('.space-map-canvas').exists()).toBe(false)
    expect(wrapper.get('.map-consent-renewal').text()).toContain('tiles.new.example')
    expect(leaflet.calls.tileLayers, 'nothing from the new host before the consent').toBe(1)

    const fresh = mountMap([pin('m1', 'Rome', 41.9, 12.5)]) // a map opened now
    await flushPromises()
    expect(fresh.find('.space-map-canvas').exists()).toBe(false)
    expect(leaflet.calls.tileLayers).toBe(1)

    await wrapper.get('.btn-stub').trigger('click') // "Enable maps" for the new host
    await flushPromises()
    expect(wrapper.find('.space-map-canvas').exists()).toBe(true)
    expect(leaflet.calls.tileLayers).toBeGreaterThan(1)
  })

  it('the legacy consent "1" named no host: the prompt shows and no tile layer is made', async () => {
    localStorage.setItem('veyq:maps-consent', '1')
    window.dispatchEvent(new StorageEvent('storage', { key: 'veyq:maps-consent', newValue: '1' }))
    const wrapper = mountMap([pin('m1', 'Rome', 41.9, 12.5)])
    await flushPromises()
    expect(wrapper.find('.space-map-canvas').exists()).toBe(false)
    expect(wrapper.text()).toContain(t('memory.place.consentTitle'))
    expect(wrapper.find('.map-consent-renewal').exists()).toBe(true)
    expect(leaflet.calls.tileLayers).toBe(0)
  })
})
