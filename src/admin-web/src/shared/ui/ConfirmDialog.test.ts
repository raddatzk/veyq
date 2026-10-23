// @vitest-environment happy-dom
// The surface half of the confirmation channel (cr-admin-web-own-confirm-dialog). `useConfirm.test.ts` owns the
// promise semantics; what only this file can pin is that every way OUT of the dialog reaches them — the
// accept button, the cancel button, and the close the dialog itself reports (Escape and the X both arrive as
// the dialog's one `update:visible` event). A dialog that renders the question and answers
// none of them would leave the call site awaiting forever, with no error anywhere.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

import ConfirmDialog from './ConfirmDialog.vue'
import { useConfirm, confirmRequest, settleConfirm, type ConfirmRequest } from '../state/useConfirm'
import { i18n } from '../../i18n'

const REQUEST: ConfirmRequest = {
  header: 'Take this tenant offline',
  message: 'Nobody can reach it until you bring it back.',
  acceptLabel: 'Take offline',
  acceptIcon: 'pause',
}

const stubs = {
  // Passthrough (the `CreateTenantDialog.test.ts` shape), plus the close event: the dialog's own chrome is
  // `VeyqDialog`'s, but that event is the one this component must wire.
  VeyqDialog: {
    props: ['visible', 'header'],
    emits: ['update:visible'],
    template:
      '<div class="dlg"><h2>{{ header }}</h2><slot /><button class="dlg-close" @click="$emit(\'update:visible\', false)">x</button></div>',
  },
  VeyqButton: {
    props: ['label', 'icon', 'variant'],
    // `emits` is load-bearing: without it the parent's @click also arrives as a native fallthrough listener,
    // so one trigger settles twice — and the second settle would silently answer the NEXT request.
    emits: ['click'],
    template: '<button :class="variant" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const mountDialog = () => mount(ConfirmDialog, { global: { plugins: [i18n], stubs } })
const accept = (w: VueWrapper) => w.findAll('button').find((b) => b.text() === REQUEST.acceptLabel)
const cancel = (w: VueWrapper) => w.findAll('button').find((b) => b.text() === i18n.global.t('common.cancel'))

describe('ConfirmDialog', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
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

  it('declines when the dialog closes itself — Escape and the X are both this one event', async () => {
    const wrapper = mountDialog()
    const answer = useConfirm().ask(REQUEST)
    await flushPromises()

    await wrapper.find('.dlg-close').trigger('click')
    await expect(answer).resolves.toBe(false)
    expect(wrapper.find('.dlg').exists()).toBe(false)
  })
})
