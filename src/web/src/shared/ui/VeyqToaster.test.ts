// @vitest-environment happy-dom
// Inbox #864: Reka's toast closes on an Escape anywhere in the window, so the Escape that closed the lightbox took the
// Undo bar along and the shift could no longer be undone. A toast closes on Escape only while the focus is in it - and
// the Escape is never cancelled: a dialog that mounted after the toast must still close on it (lane L1 caught a
// `preventDefault` keeping the share dialog open after "Link copied"). A toast's own timer still ends it.
import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import VeyqToaster from './VeyqToaster.vue'
import VeyqDialog from './VeyqDialog.vue'
import { toastQueue, useToastChannel } from './toast'
import { realI18n } from '../../test/i18n'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  toastQueue.splice(0)
})

async function withToasts() {
  wrapper = mount(VeyqToaster, { attachTo: document.body, global: { plugins: [realI18n()] } })
  const channel = useToastChannel()
  channel.add({ severity: 'info', summary: 'Capture time shifted for 3 items', life: 8000, onUndo: () => {} })
  channel.add({ severity: 'error', summary: 'One could not be written' })
  await flushPromises()
}
const undoBar = () => document.querySelector<HTMLButtonElement>('button.veyq-undo-toast__action')
const errorToast = () => document.querySelector('.veyq-toast--error')
function escapeFrom(target: Element) {
  target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
}

describe('VeyqToaster', () => {
  it('keeps every toast when an Escape meant for another layer is pressed', async () => {
    await withToasts()
    const lightbox = document.body.appendChild(document.createElement('button'))
    lightbox.focus()
    escapeFrom(lightbox)
    await flushPromises()
    expect(undoBar()).not.toBeNull()
    expect(errorToast()).not.toBeNull()
    lightbox.remove()
  })

  it('leaves the Escape to a dialog opened after the toast: the dialog closes, the toast stays', async () => {
    await withToasts()
    const open = ref(true)
    const host = mount(defineComponent({
      setup: () => () => h(VeyqDialog, { visible: open.value, 'onUpdate:visible': (v: boolean) => (open.value = v), header: 'Share' }, () => h('button', { class: 'inside' }, 'x')),
    }), { attachTo: document.body, global: { plugins: [realI18n()] } })
    await flushPromises()
    const inside = document.querySelector<HTMLElement>('.inside')!
    inside.focus()
    escapeFrom(inside)
    await flushPromises()
    expect(open.value, 'the dialog closed').toBe(false)
    expect(undoBar(), 'the Undo bar stayed').not.toBeNull()
    host.unmount()
  })

  it('still ends a toast when its time is up', async () => {
    wrapper = mount(VeyqToaster, { attachTo: document.body, global: { plugins: [realI18n()] } })
    useToastChannel().add({ severity: 'success', summary: 'Saved', life: 30 })
    await flushPromises()
    expect(toastQueue).toHaveLength(1)
    await new Promise((r) => setTimeout(r, 120))
    await flushPromises()
    expect(toastQueue).toHaveLength(0)
  })

  it('closes a toast on Escape while the focus is in it', async () => {
    await withToasts()
    const action = undoBar()!
    action.focus()
    escapeFrom(action)
    await flushPromises()
    expect(toastQueue.some((q) => q.onUndo)).toBe(false)
  })
})
