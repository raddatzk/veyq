// @vitest-environment happy-dom
// The layer's dialog (ADR 0107): it is a modal dialog named by its header, and on opening focus goes to the field
// marked `autofocus`, else to its close button - what the PrimeVue dialog did, which every name dialog relies on.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import VeyqDialog from './VeyqDialog.vue'
import { realI18n } from '../../test/i18n'

function open(body: () => unknown) {
  const Host = defineComponent({
    setup: () => () => h(VeyqDialog, { visible: true, header: 'Rename' }, { default: body }),
  })
  return mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } })
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
})
