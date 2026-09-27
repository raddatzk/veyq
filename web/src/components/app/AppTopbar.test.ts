// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'
import { realI18n } from '../../test/i18n'

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
