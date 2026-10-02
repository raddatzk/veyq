// @vitest-environment happy-dom
// uc.admin.tenant-storage-quota (web) — a cover upload refused `quota_exceeded` says there is no space left, as the
// uploads tray does (ADR 0073 §6); every other cover failure stays the silent best-effort it was.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'

const error = vi.fn()
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))
vi.mock('./media', () => ({ openBlob: vi.fn(), uploadCoverBlob: vi.fn(), deleteCoverBlob: vi.fn() }))
vi.mock('./memoryCover', () => ({
  unsealMemoryCover: () => null,
  setMemoryCover: vi.fn(),
  FULL_FRAME: { x: 0, y: 0, width: 1, height: 1 },
}))

import { uploadCoverBlob } from './media'
import { setMemoryCover } from './memoryCover'
import { useMemoryCover } from './useMemoryCover'
import { MetaConflictError } from './sealedMetaWrite'
import { i18n } from '../../i18n'
import type { TreeNode } from './tree'

function harness(onMetaUpdated = vi.fn()) {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        expose({
          api: useMemoryCover({
            scopeId: () => 's',
            node: () => ({ id: 'n', wrappedMeta: null, metaVersion: 4 }) as TreeNode,
            memoryKey: () => new Uint8Array(32),
            thumbs: ref(new Map()),
            onMetaUpdated,
            noSpaceMessage: () => 'No space left.',
          }),
        })
        return () => h('div')
      },
    }),
    { global: { plugins: [i18n] } },
  )
  return (wrapper.vm as unknown as { api: ReturnType<typeof useMemoryCover> }).api
}

const upload = { kind: 'upload', file: new File([new Uint8Array([1])], 'c.jpg', { type: 'image/jpeg' }), crop: { x: 0, y: 0, width: 1, height: 1 } } as const

describe('useMemoryCover — a cover refused for lack of room', () => {
  beforeEach(() => vi.clearAllMocks())

  it('tells the user there is no space left', async () => {
    vi.mocked(uploadCoverBlob).mockRejectedValue(new Error('quota_exceeded'))
    await harness().onCoverSave(upload)
    expect(error).toHaveBeenCalledWith('No space left.')
  })

  it('control: any other failure stays silent', async () => {
    vi.mocked(uploadCoverBlob).mockRejectedValue(new Error('chunk_put_failed:500'))
    await harness().onCoverSave(upload)
    expect(uploadCoverBlob).toHaveBeenCalledOnce()
    expect(error).not.toHaveBeenCalled()
  })
})

/**
 * W10 (ADR 0077, D9): which saves surface a same-field conflict. An upload has already replaced the node's one cover
 * blob before the meta write, so its own cover is re-applied (`surfaceConflict` false); a save that moved no blob
 * (a removal of a media cover, a crop-only `keep`) surfaces it. The lever is the `!blobMoved` argument — passing `true` always reddens the upload case.
 */
describe('useMemoryCover — a peer changed the cover meanwhile', () => {
  beforeEach(() => vi.clearAllMocks())

  it('an upload re-applies its own cover on a conflict', async () => {
    vi.mocked(uploadCoverBlob).mockResolvedValue({ blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', ownerId: 'n', role: 'COVER' })
    vi.mocked(setMemoryCover).mockResolvedValue({ wrappedMeta: 'c2Vh', metaVersion: 5 })
    await harness().onCoverSave(upload)
    expect(vi.mocked(setMemoryCover).mock.calls[0][5]).toBe(false)
    expect(vi.mocked(setMemoryCover).mock.calls[0][4]).toEqual({ wrappedMeta: null, metaVersion: 4 })
  })

  it('a conflict re-opens the editor on the current value and says so', async () => {
    const onMetaUpdated = vi.fn()
    const api = harness(onMetaUpdated)
    vi.mocked(setMemoryCover).mockRejectedValue(new MetaConflictError({ wrappedMeta: 'cGVlcg==', metaVersion: 6 }))
    // the stored cover is null in this harness, so a keep has no source; drive the conflict through a removal
    await api.onCoverSave({ kind: 'remove' })
    expect(vi.mocked(setMemoryCover).mock.calls[0][5]).toBe(true) // no blob moved: the conflict surfaces
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n', wrappedMeta: 'cGVlcg==', metaVersion: 6 })
    expect(api.coverEditorOpen.value).toBe(true)
    expect(error).toHaveBeenCalledWith(i18n.global.t('memory.meta.conflict'))
  })
})
