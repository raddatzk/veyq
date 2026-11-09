// @vitest-environment happy-dom
// The keyboard shortcut overview (uc.platform.keyboard-shortcuts), over the REAL registry (`src/test/setup.ts` registers
// the applications as `main.ts` does): `?` opens it - never in a text field, never over another dialog -, Escape closes it
// and gives the focus back, and it lists core's groups and each switched-on application's (Memories', registered through
// its registrar), none of a switched-off one. The modifier's name follows the platform.
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ShortcutsDialog from './ShortcutsDialog.vue'
import ShortcutKeys from './ShortcutKeys.vue'
import { CORE_SHORTCUTS, shortcutsOpen } from './shortcuts'
import { forgetPlatform, isApplePlatform } from './modifier'
import { MEMORIES_SHORTCUTS } from '../../features/memories/shortcuts'
import { MEMORIES } from '../../features/platform/capabilities'
import { registry } from '../applications/registry'
import { capabilityState } from '../../shared/state/capabilities'
import { realI18n, t } from '../../test/i18n'

const mountDialog = () => mount(ShortcutsDialog, { attachTo: document.body, global: { plugins: [realI18n()] } })
const dialog = () => document.querySelector<HTMLElement>('[data-testid="shortcuts-dialog"]')
const press = (target: EventTarget, key = '?') => {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
  target.dispatchEvent(event)
  return event
}
const headings = () => [...document.querySelectorAll('.shortcuts-group__title')].map((h) => h.textContent)

afterEach(() => {
  shortcutsOpen.value = false
  capabilityState.ids = null
  capabilityState.tenantIds = null
  document.body.innerHTML = ''
  forgetPlatform()
})

describe('ShortcutsDialog: ? opens it', () => {
  it('opens on ? pressed on the page, and Escape closes it and gives the focus back', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const w = mountDialog()
    expect(dialog()).toBeNull()
    const event = press(opener)
    await flushPromises()
    expect(event.defaultPrevented).toBe(true)
    expect(dialog()).not.toBeNull()
    expect(dialog()!.getAttribute('role')).toBe('dialog')
    press(document.activeElement!, 'Escape')
    await flushPromises()
    expect(shortcutsOpen.value).toBe(false)
    expect(dialog()).toBeNull()
    expect(document.activeElement).toBe(opener)
    w.unmount()
  })

  it('leaves ? to a text field, where it is a character', async () => {
    const w = mountDialog()
    for (const html of ['<input type="text">', '<textarea></textarea>', '<div contenteditable="true"><span>x</span></div>']) {
      const host = document.createElement('div')
      host.innerHTML = html
      document.body.append(host)
      const field = host.querySelector('input, textarea, span')!
      const event = press(field)
      await flushPromises()
      expect(event.defaultPrevented, html).toBe(false)
      expect(dialog(), html).toBeNull()
      host.remove()
    }
    // control: the same key outside a field opens it
    press(document.body)
    await flushPromises()
    expect(dialog()).not.toBeNull()
    w.unmount()
  })

  it('does nothing while another dialog is open: that dialog keeps the keyboard', async () => {
    const w = mountDialog()
    for (const attrs of [{ role: 'dialog' }, { role: 'alertdialog' }, { 'aria-modal': 'true' }]) {
      const other = document.createElement('div')
      for (const [k, v] of Object.entries(attrs)) other.setAttribute(k, v)
      document.body.append(other)
      press(document.body)
      await flushPromises()
      expect(dialog(), JSON.stringify(attrs)).toBeNull()
      other.remove()
    }
    press(document.body) // control: with the other one gone it opens
    await flushPromises()
    expect(dialog()).not.toBeNull()
    w.unmount()
  })

  // owner 2026-11-09: the large view's keys are what the overview is most read for
  it('opens over the large view of a photo (a modal marked data-shortcuts-over); control: another dialog beside it still keeps the keyboard', async () => {
    const w = mountDialog()
    const large = document.createElement('div')
    large.setAttribute('role', 'dialog')
    large.setAttribute('aria-modal', 'true')
    large.setAttribute('data-shortcuts-over', '')
    document.body.append(large)
    const other = document.createElement('div')
    other.setAttribute('role', 'alertdialog')
    document.body.append(other)
    press(document.body)
    await flushPromises()
    expect(dialog()).toBeNull() // control: a second, unmarked dialog keeps the keyboard
    other.remove()
    press(document.body)
    await flushPromises()
    expect(dialog()).not.toBeNull()
    w.unmount()
  })

  // owner 2026-11-09: a member who pressed ? by chance should learn how the overview opened
  it('says first that ? opens it, the key drawn in the sentence', async () => {
    const w = mountDialog()
    press(document.body)
    await flushPromises()
    const intro = document.querySelector('[data-testid="shortcuts-intro"]')
    expect(intro).not.toBeNull()
    expect(intro!.querySelector('kbd')?.textContent).toBe('?')
    expect(intro!.textContent?.replace(/\s+/g, ' ').trim()).toBe(t('shortcuts.intro', { key: '?' }).replace(/\s+/g, ' ').trim())
    expect(intro!.textContent).not.toContain('\u0000')
    w.unmount()
  })

  it('is no shortcut with Ctrl or ⌘ held', async () => {
    const w = mountDialog()
    for (const mod of [{ ctrlKey: true }, { metaKey: true }]) {
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true, ...mod }))
      await flushPromises()
      expect(dialog(), JSON.stringify(mod)).toBeNull()
    }
    w.unmount()
  })
})

describe('ShortcutsDialog: one list from the registry', () => {
  it('lists core\'s groups, then Memories\' as its registrar registered them', async () => {
    expect(registry.shortcuts().map((s) => s.application)).toContain(MEMORIES)
    const w = mountDialog()
    shortcutsOpen.value = true
    await flushPromises()
    expect(headings()).toEqual([...CORE_SHORTCUTS, ...MEMORIES_SHORTCUTS].map((g) => t(g.title)))
    const labels = [...document.querySelectorAll('.shortcuts-entry__label')].map((d) => d.textContent)
    expect(labels).toContain(t('shortcuts.toSearch'))
    expect(labels).toContain(t('memory.shortcuts.trash'))
    w.unmount()
  })

  it('leaves out a switched-off application\'s groups, and lists them while it is on', async () => {
    const w = mountDialog()
    shortcutsOpen.value = true
    capabilityState.ids = [MEMORIES, 'depot']
    capabilityState.tenantIds = ['depot'] // Memories switched off for this tenant
    await flushPromises()
    const memories = MEMORIES_SHORTCUTS.map((g) => t(g.title))
    expect(headings()).toEqual(CORE_SHORTCUTS.map((g) => t(g.title)))
    for (const title of memories) expect(headings()).not.toContain(title)
    capabilityState.tenantIds = [MEMORIES, 'depot'] // control: switched on again
    await flushPromises()
    for (const title of memories) expect(headings()).toContain(title)
    w.unmount()
  })
})

describe('the modifier is named for the platform', () => {
  it('reads an Apple platform from userAgentData, platform or the user agent', () => {
    expect(isApplePlatform({ userAgentData: { platform: 'macOS' } })).toBe(true)
    expect(isApplePlatform({ platform: 'MacIntel' })).toBe(true)
    expect(isApplePlatform({ platform: 'iPhone' })).toBe(true)
    expect(isApplePlatform({ userAgent: 'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)' })).toBe(true)
    expect(isApplePlatform({ userAgentData: { platform: 'Windows' }, platform: 'Win32' })).toBe(false)
    expect(isApplePlatform({ platform: 'Linux x86_64' })).toBe(false)
    expect(isApplePlatform({ userAgentData: { platform: 'Android' } })).toBe(false)
  })

  const render = (platform: string, chords: readonly (readonly string[])[] = [['/'], ['mod', 'K']]) => {
    const spy = Object.getOwnPropertyDescriptor(Navigator.prototype, 'platform')
    Object.defineProperty(navigator, 'platform', { configurable: true, get: () => platform })
    forgetPlatform()
    const w = mount(ShortcutKeys, { props: { keys: chords }, global: { plugins: [realI18n()] } })
    const keys = w.findAll('kbd').map((k) => k.text())
    const text = w.text()
    w.unmount()
    delete (navigator as unknown as Record<string, unknown>).platform
    if (spy) Object.defineProperty(Navigator.prototype, 'platform', spy)
    return { keys, text }
  }

  it('renders ⌘ on a Mac and Ctrl elsewhere, each key a kbd and the alternatives joined by "or"', () => {
    const mac = render('MacIntel')
    expect(mac.keys).toEqual(['/', '⌘', 'K'])
    expect(mac.text).toContain(t('shortcuts.or'))
    expect(render('Win32').keys).toEqual(['/', t('shortcuts.key.ctrl'), 'K'])
    expect(t('shortcuts.key.ctrl')).toBe('Ctrl')
  })

  // owner 2026-11-09: a Mac has no Home, PageUp or Delete key - name the keys it has
  it('names a Mac\'s keys on a Mac: fn+arrows for Home/End/PageUp/PageDown, one ⌫ for Delete and Backspace; control: PC names elsewhere', () => {
    expect(render('MacIntel', [['Home'], ['End']]).keys).toEqual(['fn', '←', 'fn', '→'])
    expect(render('MacIntel', [['PageUp'], ['PageDown']]).keys).toEqual(['fn', '↑', 'fn', '↓'])
    expect(render('MacIntel', [['Delete'], ['Backspace']]).keys).toEqual(['⌫']) // the same key there: listed once
    expect(render('MacIntel', [['Enter']]).keys).toEqual(['↩'])
    expect(render('MacIntel', [['Escape']]).keys).toEqual(['esc'])
    expect(render('Win32', [['Home'], ['End']]).keys).toEqual([t('shortcuts.key.home'), t('shortcuts.key.end')])
    expect(render('Win32', [['Delete'], ['Backspace']]).keys).toEqual([t('shortcuts.key.delete'), t('shortcuts.key.backspace')])
  })
})
