// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../../features/memories/media', () => ({ addMedia: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'
import { realI18n } from '../../test/i18n'
import { addMedia } from '../../features/memories/media'
import { enqueueUploads, clearUploads } from '../../features/memories/uploadQueue'

const mountTopbar = () => mount(AppTopbar, { global: { plugins: [realI18n(), PrimeVue] } })

describe('AppTopbar breadcrumb', () => {
  it('renders the client-side breadcrumb trail labels', () => {
    setBreadcrumb([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'f1', name: 'Reisen 2026', hash: '#~me/reisen-2026' },
      { id: 'm1', name: 'Rom', hash: '#~me/reisen-2026/rom' },
    ])
    const w = mountTopbar()
    const text = w.text()
    expect(text).toContain('Personal')
    expect(text).toContain('Reisen 2026')
    expect(text).toContain('Rom')
  })
})

// The upload status is the platform's (cr-files-application B3): the bar mounts core's menu itself, over the REAL
// registry (src/test/setup.ts registers the applications as main.ts does), and a batch Memories enqueues through its
// own adapter shows in it. If the bar stopped mounting it, this is the test that goes red.
describe('AppTopbar upload status (core\'s menu)', () => {
  it('shows a Memories batch in the platform\'s upload menu with a live queue', async () => {
    vi.mocked(addMedia).mockImplementation(() => new Promise(() => {})) // stays in flight
    clearUploads()
    const w = mountTopbar()
    expect(w.find('.uploads-trigger').exists()).toBe(false) // an empty queue draws no trigger
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) },
      [{ file: new File([new Uint8Array([1])], 'photo.png', { type: 'image/png' }), mediaType: 'image/png' }], { label: '1 photo' })
    await flushPromises()
    expect(w.find('.uploads-trigger').exists()).toBe(true)
    clearUploads()
  })
})
