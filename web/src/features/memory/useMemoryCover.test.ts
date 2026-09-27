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
import { useMemoryCover } from './useMemoryCover'
import type { TreeNode } from './tree'

function harness() {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        expose({
          api: useMemoryCover({
            scopeId: () => 's',
            node: () => ({ id: 'n' }) as TreeNode,
            memoryKey: () => new Uint8Array(32),
            thumbs: ref(new Map()),
            onMetaUpdated: vi.fn(),
            noSpaceMessage: () => 'No space left.',
          }),
        })
        return () => h('div')
      },
    }),
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
