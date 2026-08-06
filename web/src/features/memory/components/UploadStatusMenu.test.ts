// @vitest-environment happy-dom
// uc.memory.upload (web, cr-0058) — the TopBar uploads tray renders the live queue: an active batch shows its
// file, status and badge, and the cancel control aborts it.
import { describe, it, expect, vi, beforeEach } from 'vitest'

// addMedia emits one progress tick (→ the file flips preparing → uploading, cr-0105 follow-up) then stays pending
// until aborted, so it sits in 'uploading' for the assertions.
vi.mock('../media', () => ({
  addMedia: vi.fn(
    (
      _s: unknown, _n: unknown, _k: unknown, _src: unknown, _d: unknown,
      opts?: { signal?: AbortSignal; onProgress?: (p: number) => void },
    ) =>
      new Promise((_resolve, reject) => {
        opts?.onProgress?.(0) // first PUT progress → 'uploading' (past the pre-network 'preparing' phase)
        const abort = () => reject(new DOMException('cancelled', 'AbortError'))
        if (opts?.signal?.aborted) return abort()
        opts?.signal?.addEventListener('abort', abort)
      }),
  ),
}))

import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import UploadStatusMenu from './UploadStatusMenu.vue'
import { enqueueUploads, clearUploads } from '../uploadQueue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      media: {
        uploads: 'Uploads',
        uploadActive: '{count} active',
        uploadNone: 'No uploads',
        uploadCancelAll: 'Cancel all',
        uploadCancel: 'Cancel',
        uploadRetry: 'Retry',
        close: 'Close',
        uploadStatus: { queued: 'Queued', preparing: 'Preparing…', uploading: 'Uploading…', done: 'Done', failed: 'Failed', cancelled: 'Cancelled' },
      },
    },
  },
})

const photo = () => new File([new Uint8Array([1, 2, 3])], 'photo.png', { type: 'image/png' })
const mountTray = () => mount(UploadStatusMenu, { global: { plugins: [i18n] } })

beforeEach(() => {
  clearUploads()
  vi.clearAllMocks()
})

describe('UploadStatusMenu', () => {
  it('badges the active count and lists the uploading file when opened', async () => {
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }, [{ file: photo(), mediaType: 'image/png' }], {
      label: '1 photo',
    })
    const w = mountTray()
    await flushPromises()

    expect(w.find('.uploads-badge').text()).toBe('1')
    await w.find('.uploads-trigger').trigger('click')
    expect(w.text()).toContain('photo.png')
    expect(w.text()).toContain('Uploading')
  })

  it('cancels the in-flight file from the tray', async () => {
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }, [{ file: photo(), mediaType: 'image/png' }], {
      label: '1 photo',
    })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    await w.find('.uploads-file-action').trigger('click') // the cancel control
    await flushPromises()

    expect(w.text()).toContain('Cancelled')
    expect(w.find('.uploads-badge').exists()).toBe(false) // no longer active
  })
})
