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
  unsealMemoryCover: vi.fn(),
  setMemoryCover: vi.fn(),
  FULL_FRAME: { x: 0, y: 0, width: 1, height: 1 },
}))

import { deleteCoverBlob, uploadCoverBlob, type BlobRef } from './media'
import { setMemoryCover, unsealMemoryCover, type MemoryCover } from './memoryCover'
import { useMemoryCover } from './useMemoryCover'
import { MetaConflictError, MetaUnreadableError } from './sealedMetaWrite'
import { i18n } from '../../i18n'
import type { TreeNode } from './tree'

/** The cover the harness's tree copy holds — what the editor opened with, possibly stale. */
let treeCover: MemoryCover | null = null

function harness(onMetaUpdated = vi.fn()) {
  vi.mocked(unsealMemoryCover).mockImplementation(() => treeCover)
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
  beforeEach(() => {
    vi.clearAllMocks()
    treeCover = null
  })

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
  beforeEach(() => {
    vi.clearAllMocks()
    treeCover = null
  })

  it('an upload re-applies its own cover on a conflict', async () => {
    vi.mocked(uploadCoverBlob).mockResolvedValue({ blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', ownerId: 'n', role: 'COVER' })
    vi.mocked(setMemoryCover).mockResolvedValue({ wrappedMeta: 'c2Vh', metaVersion: 5, replaced: null })
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

  // inbox #290: a blob that does not open is refused, never rebuilt (ADR 0077 point 4, no reset) — and the refusal
  // says so in words, rather than leaving an editor that silently does nothing.
  it('a details blob that does not open says it cannot be changed, and moves no blob', async () => {
    const api = harness()
    vi.mocked(setMemoryCover).mockRejectedValue(new MetaUnreadableError())
    await api.onCoverSave({ kind: 'remove' })
    expect(error).toHaveBeenCalledWith(i18n.global.t('memory.meta.unreadable'))
    expect(deleteCoverBlob).not.toHaveBeenCalled()
  })
})

/**
 * inbox #284: whether the node's uploaded cover blob is reclaimed is decided from the cover the landed write REPLACED
 * (`setMemoryCover`'s `replaced`, read from the blob the server accepted the write over — the tree copy, or the
 * 409's current blob on a re-run), never from the tree copy the editor opened with. The node has ONE cover-blob slot,
 * so deciding from a stale copy deleted a peer's newer upload, or left a replaced upload behind.
 */
describe('useMemoryCover — the old uploaded cover is reclaimed from the current state', () => {
  const crop = { x: 0, y: 0, width: 1, height: 1 }
  const ref = (blobId: string): BlobRef => ({ blobId, chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', ownerId: 'n', role: 'COVER' })
  const uploaded = (blobId: string): MemoryCover => ({ source: { kind: 'upload', ref: ref(blobId) }, crop })
  const media = (mediaId: string): MemoryCover => ({ source: { kind: 'media', mediaId }, crop })
  const landed = (replaced: MemoryCover | null) => ({ wrappedMeta: 'bmV3', metaVersion: 5, replaced })

  beforeEach(() => {
    vi.clearAllMocks()
    treeCover = null
  })

  it('a stale copy naming an upload deletes nothing when the write replaced a media cover', async () => {
    treeCover = uploaded('mine-old') // a peer has since switched the cover to a photo (and reclaimed this blob)
    vi.mocked(setMemoryCover).mockResolvedValue(landed(media('peer')))
    await harness().onCoverSave({ kind: 'media', mediaId: 'm', crop })
    expect(setMemoryCover).toHaveBeenCalledOnce()
    expect(deleteCoverBlob).not.toHaveBeenCalled()
  })

  it('a stale copy naming an upload surfaces a peer\'s change instead of deleting the blob a peer put there', async () => {
    treeCover = uploaded('mine-old') // a peer has since uploaded its own cover into the node's one slot
    vi.mocked(setMemoryCover).mockRejectedValue(new MetaConflictError({ wrappedMeta: 'cGVlcg==', metaVersion: 6 }))
    const api = harness()
    await api.onCoverSave({ kind: 'remove' })
    expect(vi.mocked(setMemoryCover).mock.calls[0][5]).toBe(true) // nothing moved yet: the conflict surfaces
    expect(deleteCoverBlob).not.toHaveBeenCalled()
    expect(api.coverEditorOpen.value).toBe(true)
  })

  it('a copy naming no upload still reclaims the upload the landed write replaced, after the write', async () => {
    treeCover = media('m-old')
    const order: string[] = []
    vi.mocked(setMemoryCover).mockImplementation(async () => (order.push('write'), landed(uploaded('replaced'))))
    vi.mocked(deleteCoverBlob).mockImplementation(async () => void order.push('delete'))
    await harness().onCoverSave({ kind: 'remove' })
    expect(order).toEqual(['write', 'delete'])
    // inbox #308: the delete names the replaced upload, so a peer's upload landing in the slot meanwhile is refused
    // by the server instead of deleted
    expect(deleteCoverBlob).toHaveBeenCalledWith('s', 'n', 'replaced')
  })

  it('a new upload deletes nothing itself — its commit already reclaimed the slot\'s prior blob', async () => {
    treeCover = uploaded('mine-old')
    vi.mocked(uploadCoverBlob).mockResolvedValue(ref('new'))
    vi.mocked(setMemoryCover).mockResolvedValue(landed(uploaded('mine-old')))
    await harness().onCoverSave(upload)
    expect(deleteCoverBlob).not.toHaveBeenCalled()
  })

  it('a failed reclaim after a landed write keeps the tree current and stays quiet', async () => {
    const onMetaUpdated = vi.fn()
    vi.mocked(setMemoryCover).mockResolvedValue(landed(uploaded('replaced')))
    vi.mocked(deleteCoverBlob).mockRejectedValue(new Error('offline'))
    await harness(onMetaUpdated).onCoverSave({ kind: 'media', mediaId: 'm', crop })
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n', wrappedMeta: 'bmV3', metaVersion: 5 })
    expect(error).not.toHaveBeenCalled()
  })
})
