// @vitest-environment happy-dom
// The thumbnail pipeline (cr-0150; ADR 0105 decision 2, inbox #489): a thumbnail is read only for a tile some owner (a
// grid's window, the cover) wants, nearest first; a tile no owner wants any more leaves the queue while it waits; the
// URLs it keeps are bounded and the evicted ones revoked; one revoke owner per URL, a donated preview included.
// Bites: make `want` ignore the order and the nearest-first case goes red; skip the abort of an unwanted read and the
// queue case goes red; drop `evict`'s revoke and the bound case goes red.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, watch } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { INSTALL_EVERY_MS, MIN_KEPT, useThumbnailPipeline, type ThumbnailPipelineCtx } from './useThumbnailPipeline'

let seq = 0
let revoked: string[] = []
beforeEach(() => {
  seq = 0
  revoked = []
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:real:${seq++}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u) => void revoked.push(u as string))
})

type Item = { id: string; derivable: boolean }
const item = (id: string, derivable = true): Item => ({ id, derivable })
const items = (n: number, from = 0) => Array.from({ length: n }, (_, i) => item(`t${from + i}`))
const ids = (list: Item[]) => list.map((i) => i.id)
const pastInstall = () => new Promise((r) => setTimeout(r, INSTALL_EVERY_MS + 30))

/** Mount the pipeline with controllable seams; expose it + the seam spies. */
function harness(over: Partial<ThumbnailPipelineCtx<Item>> = {}) {
  const decryptTile = over.decryptTile ?? vi.fn(async () => new Uint8Array([1]))
  const donorPreviewFor = over.donorPreviewFor ?? vi.fn(() => null)
  let api: ReturnType<typeof useThumbnailPipeline<Item>>
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useThumbnailPipeline<Item>({ idOf: (i) => i.id, isDerivable: (i) => i.derivable, decryptTile, donorPreviewFor, ...over })
        return () => h('div')
      },
    }),
  )
  return { api: api!, wrapper, decryptTile: vi.mocked(decryptTile), donorPreviewFor }
}

/** A decryptTile whose reads wait until released, one by one, by id. */
function heldReads() {
  const release = new Map<string, (b: Uint8Array | null) => void>()
  const signals = new Map<string, AbortSignal>()
  const decryptTile = vi.fn(
    (i: Item, signal: AbortSignal) =>
      new Promise<Uint8Array | null>((resolve, reject) => {
        signals.set(i.id, signal)
        release.set(i.id, resolve)
        signal.addEventListener('abort', () => reject(new DOMException('dropped', 'AbortError')))
      }),
  )
  return { decryptTile, release, signals }
}

describe('useThumbnailPipeline', () => {
  it('reads only the wanted tiles, in the order wanted (nearest first), and skips a non-derivable one', async () => {
    const { api, decryptTile } = harness()
    api.reconcile([item('a'), item('b'), item('c'), item('d', false), item('e')])
    api.want('grid', ['c', 'a', 'd'])
    await flushPromises()
    expect(decryptTile.mock.calls.map(([i]) => i.id)).toEqual(['c', 'a'])
    expect([...api.thumbs.value.keys()].sort()).toEqual(['a', 'c'])
    expect(api.thumbs.value.has('e')).toBe(false) // listed, never wanted: never read
  })

  it('a tile with its real thumbnail in place, or on its way, is not read again', async () => {
    const { api, decryptTile } = harness()
    api.reconcile([item('a'), item('b')])
    api.want('grid', ['a', 'b'])
    api.want('grid', ['a', 'b'])
    await flushPromises()
    api.want('grid', ['b', 'a'])
    api.want('cover', ['a'])
    await flushPromises()
    expect(decryptTile).toHaveBeenCalledTimes(2)
  })

  it('a tile that leaves every window is dropped from the queue while it waits, and is nobody\'s failure', async () => {
    const reads = heldReads()
    const { api } = harness({ decryptTile: reads.decryptTile })
    api.reconcile([item('a'), item('b'), item('c')])
    api.want('grid', ['a', 'b'])
    api.want('cover', ['b'])
    api.want('grid', ['c']) // scrolled: a left the window; b is still the cover's
    await flushPromises()
    expect(reads.signals.get('a')?.aborted).toBe(true)
    expect(reads.signals.get('b')?.aborted).toBe(false)
    expect(api.failed.value.has('a')).toBe(false)
    reads.release.get('b')!(new Uint8Array([1]))
    reads.release.get('c')!(new Uint8Array([1]))
    await flushPromises()
    expect([...api.thumbs.value.keys()].sort()).toEqual(['b', 'c'])
  })

  it('keeps at most twice the largest window (at least MIN_KEPT) and revokes the ones wanted longest ago; one that comes back is read again', async () => {
    const { api, decryptTile } = harness()
    const all = items(MIN_KEPT + 100)
    api.reconcile(all)
    for (let from = 0; from < all.length; from += 50) {
      api.want('grid', ids(all.slice(from, from + 50))) // a scroll through, a window of 50 at a time
      await flushPromises()
    }
    expect(api.thumbs.value.size).toBe(MIN_KEPT)
    expect(revoked).toHaveLength(100) // the first 100 tiles, wanted longest ago, each revoked once
    expect(api.thumbs.value.has('t0')).toBe(false)
    expect(api.thumbs.value.has(`t${all.length - 1}`)).toBe(true)
    const before = decryptTile.mock.calls.length
    api.want('grid', ['t0']) // scrolled back to the top
    await flushPromises()
    expect(decryptTile.mock.calls.length).toBe(before + 1)
    expect(api.thumbs.value.has('t0')).toBe(true)
  })

  it('a tile some owner wants now is never evicted, however long ago it was first read', async () => {
    const { api } = harness()
    const all = items(MIN_KEPT + 50)
    api.reconcile(all)
    api.want('cover', ['t0'])
    for (let from = 1; from < all.length; from += 50) {
      api.want('grid', ids(all.slice(from, from + 50)))
      await flushPromises()
    }
    expect(api.thumbs.value.has('t0')).toBe(true)
  })

  it('reconcile donates an optimistic preview, then a want supersedes it, revoking the donation exactly once', async () => {
    const { api } = harness({ donorPreviewFor: (id) => (id === 'a' ? 'blob:donated' : null) })
    api.reconcile([item('a')])
    expect(api.thumbs.value.get('a')).toBe('blob:donated')
    api.want('grid', ['a'])
    await flushPromises()
    expect(api.thumbs.value.get('a')).toBe('blob:real:0')
    expect(revoked).toEqual(['blob:donated'])
  })

  it('reconcile prunes thumbs of no-longer-listed media, revoking them', async () => {
    const { api } = harness()
    api.reconcile([item('a'), item('b')])
    api.want('grid', ['a', 'b'])
    await flushPromises()
    api.reconcile([item('b')])
    expect(revoked).toEqual(['blob:real:0'])
    expect([...api.thumbs.value.keys()]).toEqual(['b'])
  })

  it('a tile whose read fails is marked failed; the rest of the grid is unaffected; dropThumb clears it', async () => {
    const decryptTile = vi.fn(async (i: Item) => {
      if (i.id === 'bad') throw new Error('chunk_get_failed:500')
      return new Uint8Array([1])
    })
    const { api } = harness({ decryptTile })
    api.reconcile([item('ok'), item('bad')])
    api.want('grid', ['ok', 'bad'])
    await flushPromises()
    expect(api.thumbs.value.has('ok')).toBe(true)
    expect(api.failed.value.has('bad')).toBe(true)
    api.want('grid', ['bad']) // failed for good: not asked again on every scroll past it
    await flushPromises()
    expect(decryptTile).toHaveBeenCalledTimes(2)
    api.dropThumb('bad')
    expect(api.failed.value.has('bad')).toBe(false)
  })

  it('revokeAll drops the reads still waiting; a read dropped that way is nobody\'s failure', async () => {
    const reads = heldReads()
    const { api } = harness({ decryptTile: reads.decryptTile })
    api.reconcile([item('a')])
    api.want('grid', ['a'])
    api.revokeAll()
    await flushPromises()
    expect(reads.signals.get('a')?.aborted).toBe(true)
    expect(api.failed.value.size).toBe(0)
  })

  it('unmounting the owner aborts the reads still waiting', async () => {
    const reads = heldReads()
    const { api, wrapper } = harness({ decryptTile: reads.decryptTile })
    api.reconcile([item('a')])
    api.want('grid', ['a'])
    wrapper.unmount()
    expect(reads.signals.get('a')?.aborted).toBe(true)
  })

  // inbox #488: every install re-renders the grid, so tiles arriving one by one must not each re-render it
  it('tiles that arrive one by one, close together, are installed together: one re-render, not one per tile', async () => {
    const decryptTile = vi.fn((i: Item) =>
      new Promise<Uint8Array>((resolve) => setTimeout(() => resolve(new Uint8Array([1])), Number(i.id.slice(1)))))
    const { api } = harness({ decryptTile })
    let renders = 0
    watch(() => api.thumbs.value.size, () => void renders++) // a reader as a template is one: flushed once per tick
    const twenty = items(20)
    api.reconcile(twenty)
    api.want('grid', ids(twenty))
    await pastInstall()
    await flushPromises()
    expect(api.thumbs.value.size).toBe(20)
    expect(renders).toBe(1)
  })

  it('tiles already decrypted are installed after INSTALL_EVERY_MS while slower reads still run', async () => {
    const reads = heldReads()
    const decryptTile = vi.fn((i: Item, signal: AbortSignal) => (i.id === 'fast' ? Promise.resolve(new Uint8Array([1])) : reads.decryptTile(i, signal)))
    const { api } = harness({ decryptTile })
    api.reconcile([item('fast'), item('slow')])
    api.want('grid', ['fast', 'slow'])
    await pastInstall()
    expect(api.thumbs.value.has('fast')).toBe(true)
    expect(api.thumbs.value.has('slow')).toBe(false)
    reads.release.get('slow')!(new Uint8Array([2]))
    await flushPromises()
    expect(api.thumbs.value.has('slow')).toBe(true)
  })

  it('a reset drops the thumbnails gathered but not yet installed', async () => {
    const reads = heldReads()
    const decryptTile = vi.fn((i: Item, signal: AbortSignal) => (i.id === 'fast' ? Promise.resolve(new Uint8Array([1])) : reads.decryptTile(i, signal)))
    const { api } = harness({ decryptTile })
    api.reconcile([item('fast'), item('slow')])
    api.want('grid', ['fast', 'slow'])
    await flushPromises() // 'fast' gathered, not installed: 'slow' still runs
    api.revokeAll() // the gallery switched Memory before the timer installed 'fast'
    await pastInstall()
    expect(api.thumbs.value.size).toBe(0)
  })

  it('dropThumb revokes one; revokeAll + unmount revoke the rest exactly once', async () => {
    const { api, wrapper } = harness()
    api.reconcile([item('a'), item('b')])
    api.want('grid', ['a', 'b'])
    await flushPromises()
    api.dropThumb('a')
    expect(revoked).toEqual(['blob:real:0'])
    api.revokeAll()
    expect(revoked).toEqual(['blob:real:0', 'blob:real:1'])
    wrapper.unmount() // store already empty → no double revoke
    expect(revoked).toEqual(['blob:real:0', 'blob:real:1'])
  })
})
