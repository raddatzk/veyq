// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../../features/account/login/signOut', () => ({ signOut: vi.fn() }))

import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import ProfileMenu from './ProfileMenu.vue'
import { signOut } from '../../features/account/login/signOut'
import { useThemeState, setThemePreference } from '../../shared/state/useThemeState'
import { localePreference, setLocalePreference } from '../../i18n'
import { realI18n, t } from '../../test/i18n'


// A pass-through stub for the teleporting PrimeVue TieredMenu: renders the model's top rows and, one level
// down, each row's submenu items as buttons that run `command`. `toggle` stands in for the popup so the
// trigger's wiring is observable.
const toggle = vi.fn()
const TieredMenuStub = {
  props: ['model'],
  methods: { toggle },
  template: `<ul>
    <li v-for="(it, i) in model" :key="i">
      <button class="menu-item" @click="it.command && it.command()">{{ it.label }}</button>
      <ul v-if="it.items">
        <li v-for="(sub, j) in it.items" :key="j">
          <button class="sub-item" :class="sub.icon" :data-parent="it.label" @click="sub.command && sub.command()">
            {{ sub.label }}
          </button>
        </li>
      </ul>
    </li>
  </ul>`,
}

const mountMenu = () =>
  mount(ProfileMenu, { global: { plugins: [realI18n(), PrimeVue], stubs: { TieredMenu: TieredMenuStub } } })

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
    const checked = subItemsOf(w, t('shell.theme')).filter((i) => i.classes('pi-check'))
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

// The property this CR exists for, and the one that rots first — the next surface that wants a gear adds a
// second door. Asserted over the source tree rather than a mounted shell, because the predicate is about the
// SET of navigations, which no single component can see. SettingsHubView is excluded by hand: its `back()` is
// navigation INSIDE the hub, not a door into it. That one legitimate exception is why this is a test and not a
// scripts/ guard; a second exception is the signal to promote it.
describe('the shell offers exactly one door into the settings hub', () => {
  const SRC = join(__dirname, '..', '..')
  const DOOR = /push\(['"]\/settings['"]\)|to=["']\/settings["']/
  // The typescript-fetch output of `npm run generate:api`: ~40 directories, and a generator that emits no .vue,
  // so walking it is half the scan's readdirs for nothing.
  const GENERATED_CLIENT = join(SRC, 'api', 'generated')

  const vueFilesUnder = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
      const full = join(dir, e.name)
      if (e.isDirectory()) return full === GENERATED_CLIENT ? [] : vueFilesUnder(full)
      return e.isFile() && e.name.endsWith('.vue') ? [full] : []
    })

  // Why a timeout of its own: the scan is synchronous disk I/O over every .vue in src/ — ~15 ms warm, but on a
  // cold page cache under heavy load a single run measured 3 s of readdir + 8 s of reads, past vitest's 5 s
  // default. Nothing is lost by raising it: vitest can only judge a synchronous body AFTER it returns, so the
  // default never caught a hang here — it only turned a slow correct run red.
  it('only ProfileMenu navigates to /settings', { timeout: 30_000 }, () => {
    const doors = vueFilesUnder(SRC)
      .filter((f) => !f.endsWith('SettingsHubView.vue'))
      .filter((f) => DOOR.test(readFileSync(f, 'utf8')))
      .map((f) => relative(SRC, f))
    expect(doors).toEqual(['components/app/ProfileMenu.vue'])
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
