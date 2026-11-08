// @vitest-environment happy-dom
// The layer's dialog (ADR 0107): it is a modal dialog named by its header, and on opening focus goes to the field
// marked `autofocus`, else to its close button - what the dialog before the layer did, which every name dialog relies on.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqDialog from './VeyqDialog.vue'
import { i18n } from '../../i18n'

function open(body: () => unknown, props: Record<string, unknown> = {}) {
  const visible = ref(true)
  const Host = defineComponent({
    setup: () => () =>
      h(VeyqDialog, { visible: visible.value, 'onUpdate:visible': (v: boolean) => (visible.value = v), header: 'Rename', ...props }, { default: body }),
  })
  return Object.assign(mount(Host, { attachTo: document.body, global: { plugins: [i18n] } }), { visible })
}

/** A press outside the box, as Reka's dismissable layer listens for it (`pointerdown` on the document, then `click`).
 *  The layer adds that listener a macrotask after it opened (so the press that opened it cannot close it), which
 *  `flushPromises` does not wait for: without the timer a press lands before the listener and proves nothing. */
async function pressOutside() {
  await new Promise((resolve) => setTimeout(resolve, 0))
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

  // owner 2026-11-08: a tap beside a dialog closes it only while it holds no field
  it('stays open on a press outside while it holds a field - a stray tap must not lose what a form holds', async () => {
    const w = open(() => h('input', { class: 'name' }))
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    expect(document.querySelector('.veyq-dialog')).not.toBeNull()
    w.unmount()
  })

  it('counts a layer field by its role, not only a native one', async () => {
    const w = open(() => h('button', { type: 'button', role: 'combobox' }, 'Pick'))
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    w.unmount()
  })

  it('closes on a press outside when it holds no field, as Cancel would', async () => {
    const w = open(() => [h('p', 'Delete it?'), h('a', { href: '#' }, 'Home')], { position: 'bottom' })
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(false)
    w.unmount()
  })

  it('stays open on a press outside while it may not be closed', async () => {
    const w = open(() => h('p', 'Uploading…'), { closable: false })
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    w.unmount()
  })

  it('stays open on a press outside with keepOnOutside, though it holds no field - a result shown once', async () => {
    const w = open(() => h('code', 'K7Q2-P9XM'), { keepOnOutside: true })
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    w.unmount()
  })

  it('counts a toggle (aria-pressed) as a field', async () => {
    const w = open(() => h('button', { type: 'button', 'aria-pressed': 'false' }, 'Auto'))
    await flushPromises()
    await pressOutside()
    expect(w.visible.value).toBe(true)
    w.unmount()
  })
})
