// @vitest-environment happy-dom
// The confirm dialog is the one door to starting a trust document over (ADR 0028 Decision 7's way back). The ESLint
// block `veyq/trust-reset-one-door` pins that nothing else IMPORTS the reset; what it cannot pin — that this file
// calls it only on a confirmed click — is this test's job. Every negative is anchored on the reset's recorded
// calls, and has its control on the same mount.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('./trustReset', () => ({ resetAccountDocument: vi.fn(async () => 'reset') }))

import TrustResetDialog from './TrustResetDialog.vue'
import { resetAccountDocument } from './trustReset'
import { realI18n, t } from '../../../test/i18n'
import { i18n } from '../../../i18n'

const stubs = {
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible"><h3>{{ header }}</h3><slot /></div>' },
  Checkbox: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template:
      '<input type="checkbox" data-test="trust-reset-ack" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  Button: {
    props: ['type', 'label', 'disabled', 'loading'],
    emits: ['click'],
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const mountDialog = (document: 'pins' | 'register' = 'pins') =>
  mount(TrustResetDialog, { props: { document, visible: true }, global: { plugins: [realI18n()], stubs } })

const flush = async (w: ReturnType<typeof mountDialog>) => {
  await w.vm.$nextTick()
  await w.vm.$nextTick()
}
const confirmButton = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('trustReset.confirm'))!
const cancelButton = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('common.cancel'))!

describe('TrustResetDialog — the one door to the reset', () => {
  beforeEach(() => vi.clearAllMocks())

  it('confirm is disabled until the acknowledgement is ticked, and an unticked click resets nothing', async () => {
    const w = mountDialog()
    expect(confirmButton(w).attributes('disabled')).toBeDefined()
    await confirmButton(w).trigger('click')
    await flush(w)
    expect(resetAccountDocument).not.toHaveBeenCalled()

    // Control on the same mount: ticked, the same button resets — exactly the document it was opened for.
    await w.find('[data-test="trust-reset-ack"]').setValue(true)
    expect(confirmButton(w).attributes('disabled')).toBeUndefined()
    await confirmButton(w).trigger('click')
    await flush(w)
    expect(resetAccountDocument).toHaveBeenCalledTimes(1)
    expect(resetAccountDocument).toHaveBeenCalledWith('pins')
    expect(w.emitted('done')).toEqual([['reset']])
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('the handler holds on its own: with the disabled state bypassed, an unticked confirm still resets nothing', async () => {
    // The second layer, tested with the first held off: a button stub that ignores `disabled` delivers the click,
    // and `confirm()` must still refuse without the acknowledgement. Control: ticked, the same click resets.
    const w = mount(TrustResetDialog, {
      props: { document: 'pins', visible: true },
      global: {
        plugins: [realI18n()],
        // `inheritAttrs: false` is load-bearing: otherwise `disabled` falls through onto the <button> as an
        // attribute and the click never fires — the bypass would be a no-op and this test green for nothing.
        stubs: {
          ...stubs,
          Button: {
            props: ['label'],
            emits: ['click'],
            inheritAttrs: false,
            template: '<button @click="$emit(\'click\')">{{ label }}</button>',
          },
        },
      },
    })
    await confirmButton(w).trigger('click')
    await flush(w)
    expect(resetAccountDocument).not.toHaveBeenCalled()

    await w.find('[data-test="trust-reset-ack"]').setValue(true)
    await confirmButton(w).trigger('click')
    await flush(w)
    expect(resetAccountDocument).toHaveBeenCalledTimes(1)
  })

  it('mounting and opening write nothing; cancel writes nothing and closes', async () => {
    const w = mountDialog('register')
    await flush(w)
    expect(resetAccountDocument).not.toHaveBeenCalled()

    await w.find('[data-test="trust-reset-ack"]').setValue(true) // even ticked
    await cancelButton(w).trigger('click')
    await flush(w)
    expect(resetAccountDocument).not.toHaveBeenCalled()
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
    expect(w.emitted('done')).toBeUndefined()
  })

  it('a new opening starts unticked — one consent is not the next one', async () => {
    const w = mountDialog()
    await w.find('[data-test="trust-reset-ack"]').setValue(true)
    await w.setProps({ visible: false })
    await w.setProps({ visible: true })
    await flush(w)
    expect(confirmButton(w).attributes('disabled')).toBeDefined()
  })

  it('a failed reset stays open, says so, and does not report done', async () => {
    vi.mocked(resetAccountDocument).mockRejectedValueOnce(new Error('offline'))
    const w = mountDialog()
    await w.find('[data-test="trust-reset-ack"]').setValue(true)
    await confirmButton(w).trigger('click')
    await flush(w)
    expect(w.text()).toContain(t('trustReset.failed'))
    expect(w.emitted('done')).toBeUndefined()
  })

  it.each(['pins', 'register'] as const)('names the cost of starting the %s over — and not the other list’s', (doc) => {
    const other = doc === 'pins' ? 'register' : 'pins'
    const w = mountDialog(doc)
    expect(w.text()).toContain(t(`trustReset.confirmTitle.${doc}`))
    expect(w.find('[data-test="trust-reset-cost"]').text()).toBe(t(`trustReset.cost.${doc}`))
    expect(w.text()).not.toContain(t(`trustReset.cost.${other}`))
    expect(w.text()).toContain(t('trustReset.serverCanWait'))
  })

  it('says it in German too — the DE catalog carries its own copy of each cost', async () => {
    const w = mountDialog('pins')
    i18n.global.locale.value = 'de'
    await flush(w)
    const de = i18n.global.t('trustReset.cost.pins')
    expect(w.find('[data-test="trust-reset-cost"]').text()).toBe(de)
    expect(de).not.toBe(i18n.global.t('trustReset.cost.pins', 1, { locale: 'en' }))
    expect(w.text()).toContain(i18n.global.t('trustReset.acknowledge'))
    i18n.global.locale.value = 'en'
  })
})
