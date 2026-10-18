// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, watch } from 'vue'
import { mount } from '@vue/test-utils'
import { INSTALL_EVERY_MS, useThumbnailPipeline, type ThumbnailPipelineCtx } from './useThumbnailPipeline'

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

/** Mount the pipeline with controllable seams; expose it + the seam spies. */
function harness(over: Partial<ThumbnailPipelineCtx<Item>> = {}) {
  const decryptTile = over.decryptTile ?? vi.fn(async () => new Uint8Array([1]))
  const donorPreviewFor = over.donorPreviewFor ?? vi.fn(() => null)
  let api: ReturnType<typeof useThumbnailPipeline<Item>>
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useThumbnailPipeline<Item>({
          idOf: (i) => i.id,
          isDerivable: (i) => i.derivable,
          decryptTile,
          donorPreviewFor,
          ...over,
        })
        return () => h('div')
      },
    }),
  )
  return { api: api!, wrapper, decryptTile, donorPreviewFor }
}

describe('useThumbnailPipeline', () => {
  it('decrypt installs a real thumbnail per derivable item and skips non-derivable', async () => {
    const { api, decryptTile } = harness()
    await api.decrypt([item('a'), item('b', false), item('c')])
    expect(api.thumbs.value.get('a')).toBe('blob:real:0')
    expect(api.thumbs.value.get('c')).toBe('blob:real:1')
    expect(api.thumbs.value.has('b')).toBe(false) // non-derivable → no fetch
    expect(decryptTile).toHaveBeenCalledTimes(2)
  })

  it('decrypt skips an item that already has a real thumbnail', async () => {
    const { api, decryptTile } = harness()
    await api.decrypt([item('a')])
    await api.decrypt([item('a')]) // already real → no second decrypt
    expect(decryptTile).toHaveBeenCalledTimes(1)
  })

  it('reconcile donates an optimistic preview, then decrypt supersedes it revoking the donation exactly once', async () => {
    const donorPreviewFor = vi.fn((id: string) => (id === 'a' ? 'blob:donated' : null))
    const { api } = harness({ donorPreviewFor })
    api.reconcile([item('a')])
    expect(api.thumbs.value.get('a')).toBe('blob:donated') // placeholder shown instantly
    await api.decrypt([item('a')]) // real thumbnail arrives
    expect(api.thumbs.value.get('a')).toBe('blob:real:0')
    expect(revoked).toEqual(['blob:donated']) // the donated preview revoked once on supersede
  })

  it('reconcile prunes thumbs of no-longer-listed media, revoking them', async () => {
    const { api } = harness()
    await api.decrypt([item('a'), item('b')])
    api.reconcile([item('a')]) // 'b' dropped from the list
    expect(api.thumbs.value.has('b')).toBe(false)
    expect(revoked).toEqual(['blob:real:1'])
  })

  it('decrypt drops a stale result when isCurrent() is false', async () => {
    const { api } = harness()
    await api.decrypt([item('a')], () => false)
    expect(api.thumbs.value.has('a')).toBe(false) // navigated away → not installed
  })

  // inbox #488: a read that failed for good (after the scheduler's retries) is the tile's error state, never a hole
  it('a tile whose read fails is marked failed; the rest of the grid is unaffected; a later success clears it', async () => {
    let fail = true
    const decryptTile = vi.fn(async (i: Item) => {
      if (i.id === 'b' && fail) throw new Error('chunk_get_failed:404')
      return new Uint8Array([1])
    })
    const { api } = harness({ decryptTile })
    await api.decrypt([item('a'), item('b'), item('c')])
    expect([...api.failed.value]).toEqual(['b'])
    expect([...api.thumbs.value.keys()].sort()).toEqual(['a', 'c'])
    fail = false
    await api.decrypt([item('b')]) // the live poll hands the tile again
    expect(api.failed.value.has('b')).toBe(false)
    expect(api.thumbs.value.has('b')).toBe(true)
  })

  it('revokeAll drops the reads still waiting; a read dropped that way is nobody\'s failure', async () => {
    const signals: AbortSignal[] = []
    const decryptTile = vi.fn((_i: Item, signal: AbortSignal) => {
      signals.push(signal)
      return new Promise<Uint8Array>((_resolve, reject) =>
        signal.addEventListener('abort', () => reject(new DOMException('Read cancelled', 'AbortError'))))
    })
    const { api } = harness({ decryptTile })
    const pending = api.decrypt([item('a'), item('b')])
    api.revokeAll() // the gallery switched Memory
    await pending
    expect(signals.every((s) => s.aborted)).toBe(true)
    expect(api.failed.value.size).toBe(0)
    void api.decrypt([item('a')]) // the next generation reads with a fresh signal
    expect(signals.at(-1)!.aborted).toBe(false)
  })

  it('unmounting the owner aborts the reads still waiting', async () => {
    const signals: AbortSignal[] = []
    const decryptTile = vi.fn((_i: Item, signal: AbortSignal) => (signals.push(signal), new Promise<Uint8Array>(() => {})))
    const { api, wrapper } = harness({ decryptTile })
    void api.decrypt([item('a')])
    wrapper.unmount()
    expect(signals[0].aborted).toBe(true)
  })

  // inbox #488: every install re-renders the whole grid, so tiles arriving one by one must not each churn the map
  it('tiles that arrive one by one, close together, are installed together: one map change, not one per tile', async () => {
    const decryptTile = vi.fn((i: Item) =>
      new Promise<Uint8Array>((resolve) => setTimeout(() => resolve(new Uint8Array([1])), Number(i.id.slice(1)))))
    const { api } = harness({ decryptTile })
    let changes = 0
    watch(api.thumbs, () => void changes++, { flush: 'sync' })
    await api.decrypt(Array.from({ length: 20 }, (_, i) => item(`t${i}`)))
    expect(api.thumbs.value.size).toBe(20)
    expect(changes).toBe(1)
  })

  it('tiles already decrypted are installed after INSTALL_EVERY_MS while slower reads still run', async () => {
    let releaseSlow!: (b: Uint8Array) => void
    const decryptTile = vi.fn((i: Item) =>
      i.id === 'slow' ? new Promise<Uint8Array>((resolve) => (releaseSlow = resolve)) : Promise.resolve(new Uint8Array([1])))
    const { api } = harness({ decryptTile })
    const done = api.decrypt([item('fast'), item('slow')])
    await new Promise((r) => setTimeout(r, INSTALL_EVERY_MS + 50))
    expect(api.thumbs.value.has('fast')).toBe(true)
    expect(api.thumbs.value.has('slow')).toBe(false)
    releaseSlow(new Uint8Array([2]))
    await done
    expect(api.thumbs.value.has('slow')).toBe(true)
  })

  it('a reset drops the thumbnails gathered but not yet installed', async () => {
    let releaseSlow!: (b: Uint8Array) => void
    const decryptTile = vi.fn((i: Item) =>
      i.id === 'slow' ? new Promise<Uint8Array>((resolve) => (releaseSlow = resolve)) : Promise.resolve(new Uint8Array([1])))
    const { api } = harness({ decryptTile })
    const done = api.decrypt([item('fast'), item('slow')])
    await Promise.resolve()
    await Promise.resolve()
    api.revokeAll() // the gallery switched Memory before the timer installed 'fast'
    releaseSlow(new Uint8Array([2]))
    await done
    await new Promise((r) => setTimeout(r, INSTALL_EVERY_MS + 50))
    expect(api.thumbs.value.size).toBe(0)
  })

  it('dropThumb revokes one; revokeAll + unmount revoke the rest exactly once', async () => {
    const { api, wrapper } = harness()
    await api.decrypt([item('a'), item('b')])
    api.dropThumb('a')
    expect(revoked).toEqual(['blob:real:0'])
    api.revokeAll()
    expect(revoked).toEqual(['blob:real:0', 'blob:real:1'])
    wrapper.unmount() // store already empty → no double revoke
    expect(revoked).toEqual(['blob:real:0', 'blob:real:1'])
  })
})
