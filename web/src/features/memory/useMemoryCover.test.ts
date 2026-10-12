// @vitest-environment happy-dom
// uc.admin.tenant-storage-quota (web) — a cover upload refused `quota_exceeded` says there is no space left, as the
// uploads tray does (ADR 0073 §6); every other cover failure stays the silent best-effort it was.
//
// Since inbox #411 the cover's blob step is core's and travels WITH the meta write (`core/node/cover/coverWrite.ts`): this
// slice makes one call per save (`setUpload` for a new image, `set` for the rest) and no longer deletes a blob of its own, so
// what is pinned here is which call a save makes, with which conflict rule, and what the user hears when it fails. What the
// write sends (the blob compare, the removal, the atomicity) is `core/node/cover/coverWrite.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'

const error = vi.fn()
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))
vi.mock('./media', () => ({ openBlob: vi.fn() }))
vi.mock('./memoryCover', () => ({
  unsealMemoryCover: vi.fn(),
  setMemoryCover: vi.fn(),
  setMemoryCoverUpload: vi.fn(),
  FULL_FRAME: { x: 0, y: 0, width: 1, height: 1 },
}))

import { setMemoryCover, setMemoryCoverUpload, unsealMemoryCover, type MemoryCover } from './memoryCover'
import { useMemoryCover } from './useMemoryCover'
import { memberMemorySource } from './source'
import { MetaConflictError, MetaUnreadableError } from '../../core/node/sealedMetaWrite'
import { CoverStaleError } from '../../core/node/cover/coverWrite'
import { i18n } from '../../i18n'
import type { TreeNode } from '../../core/node/tree'

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
            loaders: memberMemorySource({ scopeId: () => 's' }).loaders,
            write: () => memberMemorySource({ scopeId: () => 's' }).cover ?? null,
            thumbs: ref(new Map()),
            onMetaUpdated,
            noSpaceMessage: () => 'No space left.',
            coverStaleMessage: () => 'Cover changed meanwhile.',
          }),
        })
        return () => h('div')
      },
    }),
    { global: { plugins: [i18n] } },
  )
  return (wrapper.vm as unknown as { api: ReturnType<typeof useMemoryCover> }).api
}

const crop = { x: 0, y: 0, width: 1, height: 1 }
const upload = { kind: 'upload', file: new File([new Uint8Array([1])], 'c.jpg', { type: 'image/jpeg' }), crop } as const
const uploaded = (blobId: string): MemoryCover => ({ source: { kind: 'upload', ref: { blobId, chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', ownerId: 'n', role: 'COVER' } }, crop })
const landed = (extra: object = {}) => ({ wrappedMeta: 'bmV3', metaVersion: 5, replaced: null, ...extra })

beforeEach(() => {
  vi.clearAllMocks()
  treeCover = null
})

describe('useMemoryCover — a cover refused for lack of room', () => {
  it('tells the user there is no space left', async () => {
    vi.mocked(setMemoryCoverUpload).mockRejectedValue(new Error('quota_exceeded'))
    await harness().onCoverSave(upload)
    expect(error).toHaveBeenCalledWith('No space left.')
  })

  it('control: any other failure stays silent', async () => {
    vi.mocked(setMemoryCoverUpload).mockRejectedValue(new Error('chunk_put_failed:500'))
    await harness().onCoverSave(upload)
    expect(setMemoryCoverUpload).toHaveBeenCalledOnce()
    expect(error).not.toHaveBeenCalled()
  })

  it('a cover another writer replaced meanwhile is said so (nothing was written; the old cover is intact)', async () => {
    vi.mocked(setMemoryCoverUpload).mockRejectedValue(new CoverStaleError())
    await harness().onCoverSave(upload)
    expect(error).toHaveBeenCalledWith('Cover changed meanwhile.')
  })
})

/**
 * W10 (ADR 0077, D9): which saves surface a same-field conflict. A new upload re-applies its own cover over a peer's change
 * (core's write passes `surfaceConflict` false for it: this save moves the node's one cover blob); a save that moves no
 * blob - a removal, a photo, a crop-only `keep` - surfaces it.
 */
describe('useMemoryCover — a peer changed the cover meanwhile', () => {
  it('an upload is ONE call with the meta it was built from, and re-applies its own cover on a conflict', async () => {
    vi.mocked(setMemoryCoverUpload).mockResolvedValue({ ...landed(), cover: uploaded('b') })
    const onMetaUpdated = vi.fn()
    await harness(onMetaUpdated).onCoverSave(upload)
    expect(setMemoryCover).not.toHaveBeenCalled()
    const [, nodeId, , source, cropArg, current] = vi.mocked(setMemoryCoverUpload).mock.calls[0] as unknown as [string, string, unknown, { name: string }, typeof crop, unknown]
    expect(nodeId).toBe('n')
    expect(source.name).toBe('c.jpg')
    expect(cropArg).toEqual(crop)
    expect(current).toEqual({ wrappedMeta: null, metaVersion: 4 })
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n', wrappedMeta: 'bmV3', metaVersion: 5 })
  })

  it('a conflict re-opens the editor on the current value and says so', async () => {
    const onMetaUpdated = vi.fn()
    const api = harness(onMetaUpdated)
    vi.mocked(setMemoryCover).mockRejectedValue(new MetaConflictError({ wrappedMeta: 'cGVlcg==', metaVersion: 6 }))
    await api.onCoverSave({ kind: 'remove' })
    expect(vi.mocked(setMemoryCover).mock.calls[0][5]).toBe(true) // no blob moved: the conflict surfaces
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n', wrappedMeta: 'cGVlcg==', metaVersion: 6 })
    expect(api.coverEditorOpen.value).toBe(true)
    expect(error).toHaveBeenCalledWith(i18n.global.t('memory.meta.conflict'))
  })

  // inbox #290: a blob that does not open is refused, never rebuilt (ADR 0077 point 4, no reset) — and the refusal
  // says so in words, rather than leaving an editor that silently does nothing.
  it('a details blob that does not open says it cannot be changed, and sends no upload', async () => {
    const api = harness()
    vi.mocked(setMemoryCover).mockRejectedValue(new MetaUnreadableError())
    await api.onCoverSave({ kind: 'remove' })
    expect(error).toHaveBeenCalledWith(i18n.global.t('memory.meta.unreadable'))
    expect(setMemoryCoverUpload).not.toHaveBeenCalled()
  })
})

/**
 * inbox #411: a switch from an uploaded cover to a photo, or to none, is ONE meta call too - core's write frees the old
 * blob in the same request (`removeNodeCover`), decided from the cover the server accepted the write over (inbox #284), so
 * this slice neither reads the tree copy to decide it nor makes a second call that could be forgotten or fail alone.
 */
describe('useMemoryCover — the old uploaded cover goes with the write that replaces it', () => {
  it('a photo replaces an uploaded cover in ONE call, with the conflict rule of a save that moves no blob here', async () => {
    treeCover = uploaded('mine-old')
    vi.mocked(setMemoryCover).mockResolvedValue(landed({ replaced: uploaded('mine-old') }))
    const onMetaUpdated = vi.fn()
    await harness(onMetaUpdated).onCoverSave({ kind: 'media', mediaId: 'm', crop })
    expect(setMemoryCover).toHaveBeenCalledOnce()
    expect(vi.mocked(setMemoryCover).mock.calls[0][3]).toEqual({ source: { kind: 'media', mediaId: 'm' }, crop })
    expect(setMemoryCoverUpload).not.toHaveBeenCalled()
    expect(onMetaUpdated).toHaveBeenCalledWith({ nodeId: 'n', wrappedMeta: 'bmV3', metaVersion: 5 })
  })

  it('a removal is one call with a null cover; a crop-only keep re-seals the stored source', async () => {
    treeCover = uploaded('mine-old')
    vi.mocked(setMemoryCover).mockResolvedValue(landed())
    const api = harness()
    await api.onCoverSave({ kind: 'remove' })
    expect(vi.mocked(setMemoryCover).mock.calls[0][3]).toBeNull()
    await api.onCoverSave({ kind: 'keep', crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } })
    expect(vi.mocked(setMemoryCover).mock.calls[1][3]).toEqual({ source: uploaded('mine-old').source, crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } })
    expect(setMemoryCoverUpload).not.toHaveBeenCalled()
  })

  it('a keep with no stored cover writes nothing', async () => {
    await harness().onCoverSave({ kind: 'keep', crop })
    expect(setMemoryCover).not.toHaveBeenCalled()
    expect(setMemoryCoverUpload).not.toHaveBeenCalled()
  })
})
