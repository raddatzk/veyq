// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130 decisions 2, 4, 6): the Map tab — the map's three states under the one consent, the tracks
// listed with their numbers, the add that checks each file on the device, and each row's actions by the rights handed in.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
  UNDO_GROUP: 'undo',
}))

import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import MemoryTracks from './MemoryTracks.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { DEFAULT_TEST_TILES, setTileUrl } from '../../../test/services'
import { realI18n } from '../../../test/i18n'
import { mediaItem } from '../../../test/models'
import type { MediaItem } from '../media'
import type { MediaLoaders } from '../source'
import { GPX_MEDIA_TYPE, TRACK_COLORS, TRACKS_PER_MEMORY } from '../track/gpx'
import type { MapTrack } from './MemoryMap.vue'

const KEY = new Uint8Array(32)
const gpxText = (name: string) =>
  `<gpx><trk><name>${name}</name><trkseg><trkpt lat="48" lon="11"><ele>500</ele><time>2026-06-01T08:00:00Z</time></trkpt>` +
  `<trkpt lat="48.01" lon="11"><ele>520</ele><time>2026-06-01T09:00:00Z</time></trkpt></trkseg></trk></gpx>`

// The map itself is MemoryMap's (its own test draws the lines); here it is a stand-in that records what it is handed.
const mapProps: { tracks?: MapTrack[]; dots?: unknown[] }[] = []
const MapStub = defineComponent({
  props: ['tileUrl', 'attribution', 'maxZoom', 'center', 'zoom', 'dots', 'tracks', 'selectedTrack'],
  emits: ['selectTrack'],
  setup(props, { expose }) {
    expose({ fitPoints: vi.fn() })
    return () => {
      mapProps.push({ tracks: props.tracks as MapTrack[], dots: props.dots as unknown[] })
      return h('div', { class: 'map-stub' })
    }
  },
})

function loaders(bytes: Record<string, string>): MediaLoaders {
  return {
    original: vi.fn(async (it: MediaItem) => new TextEncoder().encode(bytes[it.mediaId])),
    blobMeta: (it: MediaItem) => ({ type: GPX_MEDIA_TYPE, name: `${it.mediaId}.gpx` }),
    meta: () => null,
    chunk: vi.fn(),
  } as unknown as MediaLoaders
}

function mountTab(over: Partial<{
  tracks: MediaItem[]; canAdd: boolean; canRemove: (id: string) => boolean; canDownload: boolean; canReport: boolean
  pickReady: () => boolean; photoPoints: { lat: number; lng: number }[]; bytes: Record<string, string>
}> = {}) {
  const tracks = over.tracks ?? [mediaItem({ mediaId: 'a' }), mediaItem({ mediaId: 'b' })]
  return mount(MemoryTracks, {
    props: {
      scopeId: 's1', nodeId: 'n1', memoryName: 'Alps', memoryKey: KEY, tracks,
      photoPoints: over.photoPoints ?? [{ lat: 47, lng: 10 }],
      active: true,
      loaders: loaders(over.bytes ?? { a: gpxText('Morning'), b: gpxText('Evening') }),
      canAdd: over.canAdd ?? true,
      canRemove: over.canRemove ?? (() => true),
      canDownload: over.canDownload ?? true,
      canReport: over.canReport ?? true,
      pickReady: over.pickReady,
    },
    global: { plugins: [realI18n()], stubs: { MemoryMap: MapStub } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mapProps.length = 0
  setTileUrl(DEFAULT_TEST_TILES)
  setMapsConsent(true)
})

describe('MemoryTracks', () => {
  it('lists each track with its colour, its name and its numbers, and draws the shown ones beside the photos', async () => {
    const w = mountTab()
    await flushPromises()
    const rows = w.findAll('[data-testid="track-row"]')
    expect(rows.map((r) => r.find('.memory-track-name').text())).toEqual(['Morning', 'Evening'])
    expect(rows[0].find('.memory-track-facts').text()).toBe('1.1 km · 1 h 0 min · 20 m up · 0 m down')
    const drawn = mapProps[mapProps.length - 1]
    expect(drawn.tracks?.map((t) => [t.id, t.color, t.label, t.startLabel])).toEqual([
      ['a', TRACK_COLORS[0], 'Morning', 'Morning: start'],
      ['b', TRACK_COLORS[1], 'Evening', 'Evening: start'],
    ])
    expect(drawn.dots).toEqual([{ lat: 47, lng: 10 }])
  })

  it('hides a track from the map on this view only', async () => {
    const w = mountTab()
    await flushPromises()
    await w.findAll('[data-testid="track-row"]')[1].find('button[role="checkbox"]').trigger('click')
    await flushPromises()
    expect(mapProps[mapProps.length - 1].tracks?.map((t) => t.id)).toEqual(['a'])
  })

  it('lists a track that does not read as unreadable, and draws the rest', async () => {
    const w = mountTab({ bytes: { a: '<html/>', b: gpxText('Evening') } })
    await flushPromises()
    expect(w.findAll('[data-testid="track-row"]')[0].text()).toContain('This track could not be read.')
    expect(mapProps[mapProps.length - 1].tracks?.map((t) => t.id)).toEqual(['b'])
  })

  // decision 6: every map's three states, and without a map the list stays
  it('says the server shows no map, or offers the map services, and keeps the list either way', async () => {
    setTileUrl('')
    const off = mountTab()
    await flushPromises()
    expect(off.text()).toContain('This server shows no maps.')
    expect(off.find('.map-stub').exists()).toBe(false)
    expect(off.findAll('[data-testid="track-row"]')).toHaveLength(2)

    setTileUrl(DEFAULT_TEST_TILES)
    setMapsConsent(false)
    const noConsent = mountTab()
    await flushPromises()
    expect(noConsent.text()).toContain('Map services are off')
    expect(noConsent.find('.map-stub').exists()).toBe(false)
    expect(noConsent.findAll('[data-testid="track-row"]')).toHaveLength(2)
  })

  it('says there is nothing on the map where no photo has a place and there is no track', async () => {
    const w = mountTab({ tracks: [], photoPoints: [] })
    await flushPromises()
    expect(w.text()).toContain('Nothing to show on the map yet')
    expect(w.find('.map-stub').exists()).toBe(false)
    expect(w.text()).toContain('Add a GPX file')
  })

  it('checks each picked file on the device: a refused one is named, the good ones go on as GPX files', async () => {
    const w = mountTab()
    const input = w.find('[data-testid="track-file-input"]').element as HTMLInputElement
    const good = new File([gpxText('New')], 'new.gpx', { type: '' })
    const notGpx = new File(['<gpx/>'], 'photo.jpg', { type: 'image/jpeg' })
    const short = new File(['<gpx><trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk></gpx>'], 'short.gpx')
    Object.defineProperty(input, 'files', { value: [good, notGpx, short], configurable: true })
    await w.find('[data-testid="track-file-input"]').trigger('change')
    await flushPromises()
    const added = w.emitted('add')?.[0]?.[0] as File[]
    expect(added.map((f) => [f.name, f.type])).toEqual([['new.gpx', GPX_MEDIA_TYPE]])
    expect(toastError.mock.calls.map(([line]) => line)).toEqual(['photo.jpg is no GPX track.', 'short.gpx has fewer than two points.'])
  })

  it('refuses a file past the node\'s track limit', async () => {
    const many = Array.from({ length: TRACKS_PER_MEMORY }, (_, i) => mediaItem({ mediaId: `t${i}` }))
    const w = mountTab({ tracks: many, bytes: {} })
    const input = w.find('[data-testid="track-file-input"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [new File([gpxText('X')], 'x.gpx')], configurable: true })
    await w.find('[data-testid="track-file-input"]').trigger('change')
    await flushPromises()
    expect(w.emitted('add')).toBeUndefined()
    expect(toastError).toHaveBeenCalledWith('x.gpx was not added: a Memory holds at most 50 tracks.')
  })

  it('opens the picker only once the session is ready (a guest names itself first)', async () => {
    const pickReady = vi.fn(() => false)
    const w = mountTab({ pickReady })
    const click = vi.spyOn(w.find('[data-testid="track-file-input"]').element as HTMLInputElement, 'click')
    await w.find('[data-testid="track-add"]').trigger('click')
    expect(pickReady).toHaveBeenCalled()
    expect(click).not.toHaveBeenCalled()
    pickReady.mockReturnValue(true)
    await w.find('[data-testid="track-add"]').trigger('click')
    expect(click).toHaveBeenCalledTimes(1)
  })

  it('offers no add where the rights grant none', async () => {
    const w = mountTab({ canAdd: false, tracks: [] })
    await flushPromises()
    expect(w.find('[data-testid="track-add"]').exists()).toBe(false)
    expect(w.text()).toContain('No tracks yet.')
    expect(w.text()).not.toContain('Add a GPX file')
  })

  it("offers each row's download, report and removal as the rights say, and hands removal and report up", async () => {
    const w = mountTab({ canRemove: (id) => id === 'b', canReport: false })
    await flushPromises()
    const menuOf = async (row: number) => {
      await w.findAll('[data-testid="track-more"]')[row].trigger('click')
      return (w.findComponent(VeyqMenu).props('items') as VeyqMenuItem[]).filter((i) => i.visible !== false)
    }
    expect((await menuOf(0)).map((i) => i.key)).toEqual(['download'])
    const second = await menuOf(1)
    expect(second.map((i) => i.key)).toEqual(['download', 'remove'])
    second.find((i) => i.key === 'remove')!.command!(new Event('click'))
    expect((w.emitted('remove')?.[0]?.[0] as MediaItem).mediaId).toBe('b')

    const reporting = mountTab({ canRemove: () => false, canDownload: false })
    await flushPromises()
    await reporting.findAll('[data-testid="track-more"]')[0].trigger('click')
    const items = (reporting.findComponent(VeyqMenu).props('items') as VeyqMenuItem[]).filter((i) => i.visible !== false)
    expect(items.map((i) => i.key)).toEqual(['report'])
    items[0].command!(new Event('click'))
    expect((reporting.emitted('report')?.[0]?.[0] as MediaItem).mediaId).toBe('a')

    const none = mountTab({ canRemove: () => false, canDownload: false, canReport: false })
    await flushPromises()
    expect(none.find('[data-testid="track-more"]').exists()).toBe(false)
  })
})
