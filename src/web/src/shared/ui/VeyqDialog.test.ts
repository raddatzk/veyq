// @vitest-environment happy-dom
// The layer's dialog (ADR 0107): it is a modal dialog named by its header, and on opening focus goes to the field
// marked `autofocus`, else to its close button - what the dialog before the layer did, which every name dialog relies on.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqDialog from './VeyqDialog.vue'
import { realI18n } from '../../test/i18n'

function open(body: () => unknown, props: Record<string, unknown> = {}) {
  const visible = ref(true)
  const Host = defineComponent({
    setup: () => () =>
      h(VeyqDialog, { visible: visible.value, 'onUpdate:visible': (v: boolean) => (visible.value = v), header: 'Rename', ...props }, { default: body }),
  })
  return Object.assign(mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } }), { visible })
}

/** A press outside the box, as Reka's dismissable layer listens for it (`pointerdown` on the document, then `click`). */
async function pressOutside() {
  const outside = document.querySelector<HTMLElement>('.veyq-dialog__frame')!
  outside.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'touch' }))
  outside.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

describe('VeyqDialog', () => {
  it('is a modal dialog named by its header and focuses the field marked autofocus', async () => {
    const w = open(() => [h('button', { class: 'first', type: 'button' }, 'Other'), h('input', { class: 'name', autofocus: true })])
    await flushPromises()
    const dialog = document.querySelector<HTMLElement>('.veyq-dialog')!
    expect(dialog.getAttribute('role')).toBe('dialog')
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toBe('Rename')
    expect(document.activeElement).toBe(document.querySelector('.name'))
    w.unmount()
  })

  it('focuses its close button when nothing is marked', async () => {
    const w = open(() => h('input', { class: 'name' }))
    await flushPromises()
    expect(document.activeElement).toBe(document.querySelector('.veyq-dialog__close'))
    w.unmount()
  })

  it('stays open on a press outside - a stray tap must not lose what a dialog holds', async () => {
    const w = open(() => h('input', { class: 'name' }))
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    expect(document.querySelector('.veyq-dialog')).not.toBeNull()
    w.unmount()
  })

  it('closes on a press outside with closeOnOutside, as the mobile navigation sheet does', async () => {
    const w = open(() => h('a', { href: '#' }, 'Home'), { closeOnOutside: true, position: 'bottom' })
    await flushPromises()
    expect(document.querySelector('.veyq-dialog')).not.toBeNull()
    await pressOutside()
    expect(w.visible.value).toBe(false)
    w.unmount()
  })
})
