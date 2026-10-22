// @vitest-environment happy-dom
// The layer's popup menu (ADR 0107): its owner opens it with `toggle(event)`, an entry's `command` gets the DOM event,
// an entry's `class` and a disabled entry's `aria-disabled` land on the entry, and a pick is a click on
// `veyq-menu__item-content` — the hooks every test and sheet reaches a menu by.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqMenu from './VeyqMenu.vue'
import type { VeyqMenuItem } from './menu'

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
})
