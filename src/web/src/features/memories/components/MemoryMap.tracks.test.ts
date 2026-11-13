// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130 decision 4): MemoryMap draws a track as a line per segment in its colour with a start and an
// end marker; every tooltip is a DOM node, never a string Leaflet would write as markup (Trusted Types, ADR 0117); a click
// on a line asks for its track, and the selected one is drawn heavier.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const leaflet = vi.hoisted(() => {
  type Layer = { kind: string; at: unknown; options: Record<string, unknown>; tooltip: unknown; click: (() => void) | null }
  const layers: Layer[] = []
  const layer = (kind: string, at: unknown, options: Record<string, unknown> = {}) => {
    const l: Layer & { bindTooltip: (c: unknown) => unknown; on: (e: string, cb: () => void) => unknown; addTo: () => unknown } = {
      kind, at, options, tooltip: null, click: null,
      bindTooltip: (content: unknown) => {
        l.tooltip = content
        return l
      },
      on: (ev: string, cb: () => void) => {
        if (ev === 'click') l.click = cb
        return l
      },
      addTo: () => {
        layers.push(l)
        return l
      },
    }
    return l
  }
  const map = {
    setView: () => map, fitBounds: () => map, panTo: () => map, remove: () => {}, on: () => map, whenReady: () => map,
    getCenter: () => ({ lat: 0, lng: 0 }), getZoom: () => 2, removeLayer: () => map, addLayer: () => map,
  }
  const L = {
    map: () => map,
    tileLayer: () => ({ addTo: () => ({}) }),
    layerGroup: () => {
      const g = { addTo: () => g, clearLayers: () => layers.splice(0) }
      return g
    },
    polyline: (at: unknown, options: Record<string, unknown>) => layer('line', at, options),
    circleMarker: (at: unknown, options: Record<string, unknown>) => layer('circle', at, options),
    marker: (at: unknown) => layer('marker', at),
    divIcon: () => ({}),
    Control: class {
      addTo() {
        return this
      }
    },
    DomEvent: { stop: () => {}, disableClickPropagation: () => {} },
  }
  return { L, layers }
})
vi.mock('leaflet', () => ({ default: leaflet.L }))

import { mount } from '@vue/test-utils'
import MemoryMap, { type MapTrack } from './MemoryMap.vue'
import { realI18n } from '../../../test/i18n'

const track = (id: string, color: string): MapTrack => ({
  id, color, label: `Track ${id}`, startLabel: `${id} start`, endLabel: `${id} end`,
  lines: [[{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }], [{ lat: 3, lng: 3 }, { lat: 4, lng: 4 }]],
})

function mountMap(tracks: MapTrack[], selectedTrack: string | null = null) {
  return mount(MemoryMap, {
    props: { tileUrl: 'https://t/{z}/{x}/{y}.png', attribution: 'x', maxZoom: 18, center: { lat: 0, lng: 0 }, zoom: 2, tracks, selectedTrack },
    global: { plugins: [realI18n()] },
  })
}

beforeEach(() => {
  leaflet.layers.splice(0)
})

describe('MemoryMap tracks', () => {
  it('draws a line per segment in the track colour, and its start and end', () => {
    mountMap([track('a', '#123456')])
    const lines = leaflet.layers.filter((l) => l.kind === 'line')
    expect(lines.map((l) => l.at)).toEqual([[[1, 1], [2, 2]], [[3, 3], [4, 4]]])
    expect(lines.every((l) => l.options.color === '#123456' && l.options.weight === 4)).toBe(true)
    const ends = leaflet.layers.filter((l) => l.kind === 'circle')
    expect(ends.map((l) => l.at)).toEqual([[1, 1], [4, 4]])
    expect(ends.map((l) => (l.tooltip as HTMLElement).textContent)).toEqual(['a start', 'a end'])
  })

  // ADR 0130 decision 9: the activity's dash rides every segment of its line; a track without one is solid. Lever: drop
  // `dashArray` from the polyline's options.
  it('draws each segment with its track\'s dash, and a track without one solid', () => {
    mountMap([{ ...track('a', '#000'), dash: '2 8' }, track('b', '#111')])
    expect(leaflet.layers.filter((l) => l.kind === 'line').map((l) => l.options.dashArray)).toEqual(['2 8', '2 8', undefined, undefined])
  })

  it('hands Leaflet every tooltip as a node with text, never a string of markup', () => {
    mountMap([{ ...track('a', '#000'), label: '<img src=x onerror=alert(1)>' }])
    const tooltips = leaflet.layers.map((l) => l.tooltip).filter((t) => t !== null)
    expect(tooltips.length).toBeGreaterThan(0)
    expect(tooltips.every((t) => t instanceof HTMLElement)).toBe(true)
    const line = leaflet.layers.find((l) => l.kind === 'line')!
    expect((line.tooltip as HTMLElement).textContent).toBe('<img src=x onerror=alert(1)>')
    expect((line.tooltip as HTMLElement).children).toHaveLength(0)
  })

  it('asks for a track when its line is clicked, and draws the selected one heavier', async () => {
    const w = mountMap([track('a', '#000'), track('b', '#111')], 'b')
    const weights = leaflet.layers.filter((l) => l.kind === 'line').map((l) => l.options.weight)
    expect(weights).toEqual([4, 4, 6, 6])
    leaflet.layers.find((l) => l.kind === 'line')!.click!()
    expect(w.emitted('selectTrack')?.[0]).toEqual(['a'])
    await w.setProps({ tracks: [track('a', '#000')] })
    expect(leaflet.layers.filter((l) => l.kind === 'line')).toHaveLength(2)
  })
})
