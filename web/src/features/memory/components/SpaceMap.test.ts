// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub Leaflet: happy-dom can't run a real slippy map. The fake records every marker (with its click handler) so a
// test can assert what got pinned and simulate a pin click.
const leaflet = vi.hoisted(() => {
  const markers: Array<{ latlng: [number, number]; click: ((e?: unknown) => void) | null }> = []
  const map = {
    setView: () => map,
    fitBounds: () => map,
    remove: () => {},
    on: () => map,
    // A trivial projection: layer pixels ∝ lat/lng, so far-apart pins never cluster in these contract tests.
    latLngToLayerPoint: ([lat, lng]: [number, number]) => ({ x: lng * 1000, y: lat * 1000 }),
    layerPointToLatLng: ([x, y]: [number, number]) => ({ lat: y / 1000, lng: x / 1000 }),
  }
  const L = {
    map: () => map,
    tileLayer: () => ({ addTo: () => ({}) }),
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
  return { L, markers }
})
vi.mock('leaflet', () => ({ default: leaflet.L }))

import { mount, flushPromises } from '@vue/test-utils'
import SpaceMap from './SpaceMap.vue'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { MEMORY_KIND } from '../kinds'
import type { LocatedPin } from '../spaceMap'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Button: { props: ['label'], template: `<button class="p-btn" @click="$emit('click', $event)">{{ label }}</button>` },
}

const pin = (id: string, name: string, lat: number, lng: number): LocatedPin => ({
  node: { id, name, kind: MEMORY_KIND, parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0 },
  location: { name: `${name} place`, nameSource: 'manual', derivedMode: 'fixed', derived: { lat, lng } },
  date: null,
})

const mountMap = (pins: LocatedPin[]) => mount(SpaceMap, { props: { pins }, global: { plugins: [realI18n()], stubs } })

beforeEach(() => {
  leaflet.markers.splice(0)
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

    await wrapper.find('.p-btn').trigger('click') // "Enable maps"
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
})
