// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: { shell: { invitations: 'Invitations', uploads: 'Uploads' } } },
})

const mountTopbar = () => mount(AppTopbar, { global: { plugins: [i18n, PrimeVue] } })

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
