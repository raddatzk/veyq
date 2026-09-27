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
import UploadStatusMenu from './UploadStatusMenu.vue'
import { enqueueUploads, clearUploads } from '../uploadQueue'
import { addMedia } from '../media'
import { realI18n, t } from '../../../test/i18n'

const photo = () => new File([new Uint8Array([1, 2, 3])], 'photo.png', { type: 'image/png' })
const mountTray = () => mount(UploadStatusMenu, { global: { plugins: [realI18n()] } })

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
    expect(w.text()).toContain(t('media.uploadStatus.uploading'))
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

    expect(w.text()).toContain(t('media.uploadStatus.cancelled'))
    expect(w.find('.uploads-badge').exists()).toBe(false) // no longer active
  })

  // uc.admin.tenant-storage-quota / ADR 0073 §6: a file refused for lack of room says so, and offers no retry.
  it('shows the no-space reason and no retry on a quota_exceeded failure', async () => {
    vi.mocked(addMedia).mockRejectedValueOnce(new Error('quota_exceeded'))
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }, [{ file: photo(), mediaType: 'image/png' }], {
      label: '1 photo',
    })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    expect(w.text()).toContain(t('media.uploadStatus.failed'))
    expect(w.find('.uploads-file-reason').text()).toBe(t('media.uploadNoSpace'))
    expect(w.find('.uploads-file-action').exists()).toBe(false)
  })

  it('control: any other failure offers the retry and gives no no-space reason', async () => {
    vi.mocked(addMedia).mockRejectedValueOnce(new Error('chunk_put_failed:500')).mockRejectedValueOnce(new Error('chunk_put_failed:500'))
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }, [{ file: photo(), mediaType: 'image/png' }], {
      label: '1 photo',
    })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    expect(w.text()).toContain(t('media.uploadStatus.failed'))
    expect(w.find('.uploads-file-reason').exists()).toBe(false)
    expect(w.find('.uploads-file-action').attributes('aria-label')).toBe(t('media.uploadRetry'))
  })
})
