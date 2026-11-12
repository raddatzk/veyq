// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130 decisions 2-4): the Map tab's tracks — read only while the tab is on screen, one at a time,
// each parsed on the device; a file that does not read is listed as unreadable; the list keeps its order and colours.
import { describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { MediaItem } from '../media'
import { GPX_MEDIA_TYPE, TRACK_COLORS } from './gpx'
import { useMemoryTracks } from './useMemoryTracks'

const enc = (s: string) => new TextEncoder().encode(s)
const file = (name: string | null, pts: [number, number][]) =>
  enc(`<gpx><trk>${name ? `<name>${name}</name>` : ''}<trkseg>${pts.map(([a, b]) => `<trkpt lat="${a}" lon="${b}"/>`).join('')}</trkseg></trk></gpx>`)
const item = (mediaId: string) => ({ mediaId }) as MediaItem
const flush = async () => {
  for (let i = 0; i < 10; i++) await nextTick()
}

function setup(opts: { bytes: Record<string, Uint8Array | Error>; at?: Record<string, number>; names?: Record<string, string> }) {
  const active = ref(false)
  const key = ref<Uint8Array | null>(new Uint8Array([1]))
  const nodeId = ref('n1')
  const tracks = ref<MediaItem[]>(Object.keys(opts.bytes).map(item))
  let inFlight = 0
  let maxInFlight = 0
  const original = vi.fn(async (it: MediaItem) => {
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await Promise.resolve()
    inFlight--
    const b = opts.bytes[it.mediaId]
    if (b instanceof Error) throw b
    return b
  })
  const model = useMemoryTracks({
    scopeId: () => 's1',
    nodeId: () => nodeId.value,
    memoryKey: () => key.value,
    tracks: () => tracks.value,
    active: () => active.value,
    original,
    blobMeta: (it) => ({ v: 1, type: GPX_MEDIA_TYPE, name: opts.names?.[it.mediaId] }),
    capturedAt: (it) => opts.at?.[it.mediaId],
  })
  return { model, active, key, nodeId, tracks, original, maxInFlight: () => maxInFlight }
}

describe('useMemoryTracks', () => {
  it('reads nothing before the tab is on screen, then every track, one at a time', async () => {
    const s = setup({ bytes: { a: file('A', [[1, 2], [3, 4]]), b: file('B', [[5, 6], [7, 8]]) } })
    await flush()
    expect(s.original).not.toHaveBeenCalled()
    expect(s.model.entries.value.map((e) => e.status)).toEqual(['loading', 'loading'])

    s.active.value = true
    await flush()
    expect(s.original).toHaveBeenCalledTimes(2)
    expect(s.maxInFlight()).toBe(1)
    const [a, b] = s.model.entries.value
    expect(a.status).toBe('ready')
    expect(a.name).toBe('A')
    expect(a.lines).toEqual([[{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }]])
    expect(a.stats?.distanceMeters).toBeGreaterThan(0)
    expect(b.name).toBe('B')
  })

  it('lists a track that does not read as failed, by its file name, and reads the others', async () => {
    const s = setup({
      bytes: { a: new Error('gone'), b: enc('<html/>'), c: new Uint8Array([0xff, 0xfe, 0x00]), d: file(null, [[1, 1], [2, 2]]) },
      names: { a: 'lost.gpx', d: 'Named by file.gpx' },
    })
    s.active.value = true
    await flush()
    const byId = Object.fromEntries(s.model.entries.value.map((e) => [e.mediaId, e]))
    expect(byId.a.status).toBe('failed')
    expect(byId.a.name).toBe('lost')
    expect(byId.b.status).toBe('failed')
    expect(byId.c.status).toBe('failed') // no UTF-8 text
    expect(byId.d.status).toBe('ready')
    expect(byId.d.name).toBe('Named by file')
  })

  it('orders by the listed capture time and colours by that order, stable while tracks are read', async () => {
    const s = setup({ bytes: { late: file('L', [[1, 1], [2, 2]]), early: file('E', [[1, 1], [2, 2]]) }, at: { late: 2000, early: 1000 } })
    const before = s.model.entries.value.map((e) => [e.mediaId, e.color])
    expect(before).toEqual([['early', TRACK_COLORS[0]], ['late', TRACK_COLORS[1]]])
    s.active.value = true
    await flush()
    expect(s.model.entries.value.map((e) => [e.mediaId, e.color])).toEqual(before)
  })

  it('hides, shows and selects on this view only; selecting a hidden track shows it', async () => {
    const s = setup({ bytes: { a: file('A', [[1, 1], [2, 2]]) } })
    s.model.setShown('a', false)
    expect(s.model.hidden.value.has('a')).toBe(true)
    s.model.select('a')
    expect(s.model.selected.value).toBe('a')
    expect(s.model.hidden.value.has('a')).toBe(false)
    s.model.setShown('a', false)
    expect(s.model.selected.value).toBeNull()
  })

  it('drops every read and choice when the node or its key changes, and lists nothing without a key', async () => {
    const s = setup({ bytes: { a: file('A', [[1, 1], [2, 2]]) } })
    s.active.value = true
    await flush()
    s.model.setShown('a', false)
    expect(s.model.entries.value[0].status).toBe('ready')

    s.active.value = false
    s.nodeId.value = 'n2'
    await flush()
    expect(s.model.entries.value[0].status).toBe('loading')
    expect(s.model.hidden.value.size).toBe(0)

    s.key.value = null
    await flush()
    expect(s.model.entries.value).toEqual([])
  })
})
