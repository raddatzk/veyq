// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

// One shared `push`, so a test can see where the menu navigated.
const { push } = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../features/account/login/signOut', () => ({ signOut: vi.fn() }))

import { mount } from '@vue/test-utils'
import ProfileMenu from './ProfileMenu.vue'
import { signOut } from '../../features/account/login/signOut'
import { useThemeState, setThemePreference } from '../../shared/state/useThemeState'
import { localePreference, setLocalePreference } from '../../i18n'
import { realI18n, t } from '../../test/i18n'
import { shortcutsOpen } from '../../core/shortcuts/shortcuts'


// A pass-through stub for the teleporting VeyqMenu: renders the items' top rows and, one level
// down, each row's submenu items as buttons that run `command`. `toggle` stands in for the popup so the
// trigger's wiring is observable.
const toggle = vi.fn()
const MenuStub = {
  props: ['items'],
  methods: { toggle },
  template: `<ul>
    <li v-for="(it, i) in items" :key="i">
      <button class="menu-item" @click="it.command && it.command()">{{ it.label }}</button>
      <ul v-if="it.items">
        <li v-for="(sub, j) in it.items" :key="j">
          <button class="sub-item" :data-icon="sub.icon" :data-parent="it.label" @click="sub.command && sub.command()">
            {{ sub.label }}
          </button>
        </li>
      </ul>
    </li>
  </ul>`,
}

const mountMenu = () =>
  mount(ProfileMenu, { global: { plugins: [realI18n()], stubs: { VeyqMenu: MenuStub } } })

const subItemsOf = (w: ReturnType<typeof mountMenu>, parent: string) =>
  w.findAll(`.sub-item[data-parent="${parent}"]`)

describe('ProfileMenu appearance quick-picker', () => {
  it('offers a labelled System/Light/Dark submenu and applies the picked one', async () => {
    setThemePreference('system')
    const { themePreference } = useThemeState()
    const w = mountMenu()

    const items = subItemsOf(w, t('shell.theme'))
    expect(items.map((i) => i.text())).toEqual([t('shell.themeSystem'), t('shell.themeLight'), t('shell.themeDark')])

    await items[2].trigger('click') // Dark
    expect(themePreference.value).toBe('dark')
    await items[0].trigger('click') // System
    expect(themePreference.value).toBe('system')
  })

  it('marks the active entry with a check, so the submenu says which mode is on', async () => {
    setThemePreference('light')
    const w = mountMenu()
    const checked = subItemsOf(w, t('shell.theme')).filter((i) => i.attributes('data-icon') === 'check')
    expect(checked.map((i) => i.text())).toEqual([t('shell.themeLight')])
    setThemePreference('system')
  })

  it('the profile button is the menu trigger', async () => {
    toggle.mockClear()
    const w = mountMenu()
    await w.find('.profile-button').trigger('click')
    expect(toggle).toHaveBeenCalledOnce()
  })
})

describe('ProfileMenu language quick-picker', () => {
  it('still applies a locale, and its "system" row no longer borrows the theme key', async () => {
    setLocalePreference('en')
    const w = mountMenu()

    const items = subItemsOf(w, t('shell.language'))
    expect(items.map((i) => i.text())).toEqual([t('shell.systemDefault'), 'English', 'Deutsch'])

    await items[2].trigger('click') // Deutsch
    expect(localePreference().value).toBe('de')
    await items[0].trigger('click') // System
    expect(localePreference().value).toBe('system')
    setLocalePreference('en')
  })
})

// The ONE door into the settings hub. That no other file opens one is the lint rule `veyq/one-settings-door`
// (eslint.config.js); a lint rule can only assert absence elsewhere, so this asserts the door itself exists.
describe('the shell offers exactly one door into the settings hub', () => {
  it('the settings item navigates to the hub', async () => {
    push.mockClear()
    const w = mountMenu()
    const item = w.findAll('.menu-item').find((b) => b.text() === t('shell.settings'))!

    await item.trigger('click')

    expect(push).toHaveBeenCalledExactlyOnceWith('/settings')
  })
})

// uc.platform.keyboard-shortcuts: besides `?`, the account menu opens the overview (`ShortcutsDialog.test.ts` drives the
// dialog itself).
describe('ProfileMenu keyboard shortcuts', () => {
  it('opens the shortcut overview', async () => {
    shortcutsOpen.value = false
    const w = mountMenu()
    const item = w.findAll('.menu-item').find((b) => b.text() === t('shortcuts.title'))!

    await item.trigger('click')

    expect(shortcutsOpen.value).toBe(true)
    shortcutsOpen.value = false
  })
})

describe('ProfileMenu sign-out (uc.account.sign-out)', () => {
  it('runs the one sign-out sequence — the menu is one of its five entry points, not a copy of it', async () => {
    const w = mountMenu()
    const item = w.findAll('.menu-item').find((b) => b.text() === t('shell.logout'))!

    await item.trigger('click')

    expect(signOut).toHaveBeenCalledExactlyOnceWith()
  })
})
