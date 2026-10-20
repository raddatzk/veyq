// @vitest-environment happy-dom
// The surface half of the confirmation channel (cr-web-own-confirm-dialog). `useConfirm.test.ts` owns the
// promise semantics; what only this file can pin is that every way OUT of the dialog reaches them — the
// accept button, the cancel button, and the close the dialog itself reports (Escape, the X, a click on the
// mask all arrive as PrimeVue's one `update:visible` event). A dialog that renders the question and answers
// none of them would leave the call site awaiting forever, with no error anywhere.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

import ConfirmDialog from './ConfirmDialog.vue'
import { useConfirm, confirmRequest, settleConfirm } from '../state/useConfirm'
import { realI18n, t } from '../../test/i18n'

const REQUEST = {
  header: 'Sign this device out',
  message: 'It will have to sign in again.',
  acceptLabel: 'Sign this device out',
  acceptIcon: 'pi pi-sign-out',
}

const stubs = {
  // Passthrough: the dialog's own chrome is PrimeVue's, but its close event is the one this component must wire.
  Dialog: {
    props: ['visible', 'header'],
    emits: ['update:visible'],
    template:
      '<div class="dlg"><h2>{{ header }}</h2><slot /><button class="dlg-close" @click="$emit(\'update:visible\', false)">x</button></div>',
  },
  Button: {
    props: ['label', 'icon', 'severity'],
    // `emits` is load-bearing: without it the parent's @click also arrives as a native fallthrough listener,
    // so one trigger settles twice — and the second settle would silently answer the NEXT request.
    emits: ['click'],
    template: '<button :class="severity" @click="$emit(\'click\')">{{ label }}</button>',
  },
  Checkbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="opt" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const mountDialog = () => mount(ConfirmDialog, { global: { plugins: [realI18n()], stubs } })
const accept = (w: VueWrapper) => w.findAll('button').find((b) => b.text() === REQUEST.acceptLabel)
const cancel = (w: VueWrapper) => w.findAll('button').find((b) => b.text() === t('common.cancel'))

describe('ConfirmDialog', () => {
  beforeEach(() => {
    if (confirmRequest.value) settleConfirm(false)
  })

  it('renders nothing until something is asked', () => {
    expect(mountDialog().find('.dlg').exists()).toBe(false)
  })

  it('shows the request it was given — header, consequence and the label of the button that opened it', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe(REQUEST.header)
    expect(wrapper.text()).toContain(REQUEST.message)
    // The primary is the destructive one and it carries an explicit label (styleguide § Buttons & dialog
    // actions) — never an icon-only or a generic "OK".
    expect(accept(wrapper)!.classes()).toContain('danger')

    settleConfirm(false)
    await answer
  })

  it('accepts through the primary', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()

    await accept(wrapper)!.trigger('click')
    await expect(answer).resolves.toBe(true)
    expect(wrapper.find('.dlg').exists()).toBe(false)
  })

  it('declines through cancel', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()

    await cancel(wrapper)!.trigger('click')
    await expect(answer).resolves.toBe(false)
    expect(wrapper.find('.dlg').exists()).toBe(false)
  })

  it('declines when the dialog closes itself — Escape, the X and the mask are all this one event', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()

    await wrapper.find('.dlg-close').trigger('click')
    await expect(answer).resolves.toBe(false)
    expect(wrapper.find('.dlg').exists()).toBe(false)
  })

  // The opt-in (useConfirm's askWithOption): rendered only for a request that carries one, unchecked each time it
  // opens, and its ticked state is what the accept answers with.
  const WITH_OPTION = { ...REQUEST, option: { label: 'Also turn off every link', hint: 'They may have copied any of them.' } }

  it('renders no opt-in for a plain request', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()
    expect(wrapper.find('input.opt').exists()).toBe(false)
    settleConfirm(false)
    await answer
  })

  it('answers the ticked opt-in on accept, unchecked by default', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().askWithOption(WITH_OPTION)
    await flushPromises()
    expect(wrapper.text()).toContain(WITH_OPTION.option.label)
    expect(wrapper.text()).toContain(WITH_OPTION.option.hint)
    expect((wrapper.find('input.opt').element as HTMLInputElement).checked).toBe(false)

    await wrapper.find('input.opt').setValue(true)
    await accept(wrapper)!.trigger('click')
    await expect(answer).resolves.toEqual({ option: true })
  })

  it('control: accepted unticked answers false; a ticked option then cancelled answers nothing', async () => {
    const wrapper = mountDialog()
    const plain = useConfirm().askWithOption(WITH_OPTION)
    await flushPromises()
    await accept(wrapper)!.trigger('click')
    await expect(plain).resolves.toEqual({ option: false })

    const cancelled = useConfirm().askWithOption(WITH_OPTION)
    await flushPromises()
    await wrapper.find('input.opt').setValue(true)
    await cancel(wrapper)!.trigger('click')
    await expect(cancelled).resolves.toBeNull()

    // and the tick does not carry into the next request
    const next = useConfirm().askWithOption(WITH_OPTION)
    await flushPromises()
    expect((wrapper.find('input.opt').element as HTMLInputElement).checked).toBe(false)
    settleConfirm(false)
    await next
  })
})
