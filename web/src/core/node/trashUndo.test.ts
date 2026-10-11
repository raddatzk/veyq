// @vitest-environment happy-dom
// Inbox #385 (c): the undo after a delete into the trash, through the app's REAL undo toast — App.vue's undo group
// (AppShell stubbed), PrimeVue's real Toast and ToastService — clicked in the DOM: the Undo button runs the caller's
// restore; a failed restore says so and points at the trash.
import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('../../components/app/AppShell.vue', () => ({ default: { render: () => null } }))

import App from '../../App.vue'
import { useTrashUndo } from './trashUndo'
import { realI18n, t } from '../../test/i18n'

function mountWithApp() {
  let offer: ReturnType<typeof useTrashUndo> = () => {}
  const Harness = defineComponent({
    setup() {
      offer = useTrashUndo()
      return () => h('div')
    },
  })
  mount(defineComponent({ render: () => [h(App), h(Harness)] }), {
    attachTo: document.body,
    global: { plugins: [realI18n(), PrimeVue] },
  })
  return (name: string, restore: () => Promise<void>) => offer(name, restore)
}
const undoButton = () => [...document.body.querySelectorAll('button.veyq-undo-toast__action')].at(-1) as HTMLButtonElement | undefined
const bodyText = () => document.body.textContent ?? ''

describe('useTrashUndo', () => {
  it('says the node moved to the trash, and its Undo restores it', async () => {
    const offer = mountWithApp()
    const restore = vi.fn(async () => {})
    offer('Trips', restore)
    await flushPromises()
    expect(bodyText()).toContain(t('node.trash.moved', { name: 'Trips' }))
    expect(restore).not.toHaveBeenCalled()
    undoButton()!.click()
    await flushPromises()
    expect(restore).toHaveBeenCalledOnce()
    expect(bodyText()).not.toContain(t('node.trash.undoFailed', { name: 'Trips' }))
  })

  it('a restore that fails says so and points at the trash', async () => {
    const offer = mountWithApp()
    offer('Report.pdf', async () => { throw new Error('slug_taken') })
    await flushPromises()
    undoButton()!.click()
    await flushPromises()
    expect(bodyText()).toContain(t('node.trash.undoFailed', { name: 'Report.pdf' }))
  })
})
