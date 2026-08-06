// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useThumbnailPipeline, type ThumbnailPipelineCtx } from './useThumbnailPipeline'

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
