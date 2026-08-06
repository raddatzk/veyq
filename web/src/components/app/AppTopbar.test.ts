// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'
import { useThemeState, setThemePreference } from '../../shared/state/useThemeState'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      shell: { search: 'Search…', theme: 'Theme', themeSystem: 'System', themeLight: 'Light', themeDark: 'Dark' },
      settings: { title: 'Settings' },
    },
  },
})

const mountTopbar = () => mount(AppTopbar, { global: { plugins: [i18n, PrimeVue] } })

// A pass-through stub for the teleporting PrimeVue Menu: renders its model items as buttons that run `command`.
const MenuStub = {
  props: ['model'],
  template: '<ul><li v-for="(it, i) in model" :key="i"><button class="menu-item" @click="it.command && it.command()">{{ it.label }}</button></li></ul>',
}

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

describe('AppTopbar theme dropdown (cr-0085)', () => {
  it('offers a labelled System/Light/Dark menu and applies the picked one', async () => {
    setThemePreference('system')
    const { themePreference } = useThemeState()
    const w = mount(AppTopbar, { global: { plugins: [i18n, PrimeVue], stubs: { Menu: MenuStub } } })

    const items = w.findAll('.menu-item')
    expect(items.map((i) => i.text())).toEqual(['System', 'Light', 'Dark'])

    await items[2].trigger('click') // Dark
    expect(themePreference.value).toBe('dark')
    await items[0].trigger('click') // System
    expect(themePreference.value).toBe('system')
  })

  it('the trigger is a menu button (aria-haspopup), not a mystery cycle', () => {
    const w = mount(AppTopbar, { global: { plugins: [i18n, PrimeVue], stubs: { Menu: true } } })
    expect(w.find('.topbar-theme-button').attributes('aria-haspopup')).toBe('menu')
  })
})
