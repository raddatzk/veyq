// @vitest-environment happy-dom
// The layer's popup menu (ADR 0107): its owner opens it with `toggle(event)`, an entry's `command` gets the DOM event,
// an entry's `class` and a disabled entry's `aria-disabled` land on the entry, and a pick is a click on
// `veyq-menu__item-content` — the hooks every test and sheet reaches a menu by.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqMenu from './VeyqMenu.vue'
import VeyqDialog from './VeyqDialog.vue'
import type { VeyqMenuItem } from './menu'
import { realI18n } from '../../test/i18n'

/** Vue's flushes, then the timer Reka's focus scopes give focus back on, then the flushes it caused. */
async function settle() {
  await flushPromises()
  await new Promise((r) => setTimeout(r, 0))
  await flushPromises()
}

function host(items: VeyqMenuItem[]) {
  return defineComponent({
    setup() {
      const menu = ref<InstanceType<typeof VeyqMenu> | null>(null)
      return () => [
        h('button', { class: 'opener', onClick: (e: Event) => menu.value?.toggle(e) }, 'More'),
        h(VeyqMenu, { ref: menu, items }),
      ]
    },
  })
}

describe('VeyqMenu', () => {
  it('opens at its opener, runs a pick with the DOM event, and marks a disabled entry', async () => {
    const rename = vi.fn()
    const w = mount(host([
      { label: 'Rename', icon: 'pencil', class: 'row-rename', command: rename },
      { label: 'Delete', icon: 'trash', disabled: true, command: vi.fn() },
    ]), { attachTo: document.body })
    expect(document.querySelector('.veyq-menu__item')).toBeNull()
    await w.get('.opener').trigger('click')
    await flushPromises()
    const entries = [...document.querySelectorAll<HTMLElement>('.veyq-menu__item')]
    expect(entries.map((e) => e.textContent?.trim())).toEqual(['Rename', 'Delete'])
    expect(entries[0]!.classList.contains('row-rename')).toBe(true)
    expect(entries[1]!.getAttribute('aria-disabled')).toBe('true')
    entries[0]!.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    expect(rename).toHaveBeenCalledTimes(1)
    expect(rename.mock.calls[0]![0]).toBeInstanceOf(Event)
    w.unmount()
  })

  it('opens a submenu for an entry with items', async () => {
    const pick = vi.fn()
    const w = mount(host([{ label: 'Theme', icon: 'palette', items: [{ label: 'Dark', command: pick }] }]), { attachTo: document.body })
    await w.get('.opener').trigger('click')
    await flushPromises()
    expect(document.querySelector('.veyq-menu__item')?.textContent).toContain('Theme')
    w.unmount()
  })

  it('gives focus back to its opener on Escape', async () => {
    const w = mount(host([{ label: 'Rename', command: vi.fn() }]), { attachTo: document.body })
    const opener = w.get('.opener').element as HTMLElement
    opener.focus()
    opener.click()
    await settle()
    const entry = document.querySelector<HTMLElement>('.veyq-menu__item')!
    entry.focus()
    entry.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await settle()
    expect(document.querySelector('.veyq-menu')).toBeNull()
    expect(document.activeElement).toBe(opener)
    w.unmount()
  })

  // A pick that opens a dialog (the "New" menu's "New folder", a row's "Rename"): the entry is gone once the dialog is up,
  // so the dialog must record the menu's OPENER as where its Escape gives focus back. Run before the menu gave focus back,
  // the pick mounted the dialog while focus was still on the entry; the dialog recorded the entry, and its Escape left
  // focus on the page's body (stack lane, depot-clicks).
  it('gives focus back to its opener before a pick runs, so a dialog the pick opens returns focus there', async () => {
    const Host = defineComponent({
      setup() {
        const shown = ref(false)
        const menu = ref<InstanceType<typeof VeyqMenu> | null>(null)
        return () => [
          h('button', { class: 'opener', onClick: (e: Event) => menu.value?.toggle(e) }, 'New'),
          h(VeyqMenu, { ref: menu, items: [{ label: 'New folder', command: () => (shown.value = true) }] }),
          shown.value
            ? h(VeyqDialog, { visible: true, header: 'Create a folder', 'onUpdate:visible': () => (shown.value = false) }, { default: () => h('input', { class: 'name', autofocus: true }) })
            : null,
        ]
      },
    })
    const w = mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } })
    const opener = w.get('.opener').element as HTMLElement
    opener.focus()
    opener.click()
    await settle()
    const entry = document.querySelector<HTMLElement>('.veyq-menu__item')!
    entry.focus()
    entry.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
    await settle()
    expect(document.querySelector('.veyq-menu'), 'the pick closed the menu').toBeNull()
    expect(document.activeElement, 'the dialog has focus on its field').toBe(document.querySelector('.name'))

    document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await settle()
    expect(document.querySelector('.veyq-dialog'), 'Escape closed the dialog').toBeNull()
    expect(document.activeElement, "focus is back on the menu's opener").toBe(opener)
    w.unmount()
  })
})
