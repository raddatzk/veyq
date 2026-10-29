// @vitest-environment happy-dom
// uc.moderation.authority-order (admin-web) — Server → Moderation, the Server Owner's order form. Each case is one
// observable the Server Owner depends on within the hour:
//   - a removal order asks first, naming that it blocks for everyone; declined, nothing is sent;
//   - accepted, the panel shows the block time to the second in UTC and the Annex II text to copy, the reference as typed
//     in it, led by the form's title;
//   - a share the server does not know shows the Annex III draft instead;
//   - a refusal is said in words (an order already recorded, an unreadable link).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

const ask = vi.fn()
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))
vi.mock('./orders', async () => {
  const actual = await vi.importActual<typeof import('./orders')>('./orders')
  return { ...actual, enterOrder: vi.fn() }
})

import OrderPanel from './OrderPanel.vue'
import { enterOrder, OrderInvalid, type AuthorityOrder } from './orders'
import { ORDER_FORMS } from './orderForms'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'

function order(over: Partial<AuthorityOrder> = {}): AuthorityOrder {
  return {
    id: '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b', kind: 'REMOVAL', shareId: SHARE, receivedAt: new Date('2026-10-18T07:12:00Z'),
    enteredAt: new Date('2026-10-18T07:20:11Z'), enteredBy: 'owner', found: true, blockedAt: new Date('2026-10-18T07:20:11.734Z'),
    caseId: 'case', holdUntil: new Date('2027-04-19T07:20:11Z'), statementDeferredUntil: undefined, epoch: 1, sealedReference: 'c2VhbGVk',
    providerName: 'Example e.V.', contactPoint: 'abuse@example.org', ...over,
  }
}

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqSelect: { props: ['modelValue', 'options'], template: '<select class="select" />' },
  VeyqTextarea: { props: ['modelValue'], template: '<textarea class="textarea" :value="modelValue" />' },
  VeyqButton: {
    props: ['label', 'type'],
    emits: ['click'],
    template: '<button :type="type || \'button\'" :class="$attrs.class" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const w = mount(OrderPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return w
}

async function fillAndSubmit(w: Awaited<ReturnType<typeof mountPanel>>) {
  await w.find('.order-share').setValue(`https://club.example.org/s/${SHARE}#k=secret`)
  await w.find('.order-reference').setValue('BKA ST-2026-0417')
  await w.find('form.order-form').trigger('submit')
  await flushPromises()
}

describe('OrderPanel (uc.moderation.authority-order, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('asks before a removal order, and sends nothing when declined', async () => {
    ask.mockResolvedValue(false)
    const w = await mountPanel()
    await fillAndSubmit(w)
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('blocked for everyone') }))
    expect(enterOrder).not.toHaveBeenCalled()
  })

  it('shows the block time to the second and the Annex II text, the reference as typed, led by the form\'s title', async () => {
    ask.mockResolvedValue(true)
    vi.mocked(enterOrder).mockResolvedValue(order())
    const w = await mountPanel()
    await fillAndSubmit(w)
    expect(vi.mocked(enterOrder).mock.calls[0][0]).toMatchObject({ kind: 'REMOVAL', reference: 'BKA ST-2026-0417' })
    expect(w.find('.order-blocked').text()).toContain('2026-10-18 07:20:11 UTC')
    const text = (w.find('.textarea').element as HTMLTextAreaElement).value
    expect(text.split('\n')[0]).toBe(ORDER_FORMS.en.annexII[0])
    expect(text).toContain('Reference of the removal order: BKA ST-2026-0417')
    expect(text).toContain('Access disabled at: 2026-10-18 07:20:11 UTC')
  })

  it('shows the Annex III draft when the share is not on this instance', async () => {
    ask.mockResolvedValue(true)
    vi.mocked(enterOrder).mockResolvedValue(order({ found: false, blockedAt: undefined, caseId: undefined, holdUntil: undefined }))
    const w = await mountPanel()
    await fillAndSubmit(w)
    expect(w.find('.order-not-found').exists()).toBe(true)
    expect((w.find('.textarea').element as HTMLTextAreaElement).value).toContain('no shared link with this identifier exists')
  })

  it('says a refusal in words', async () => {
    ask.mockResolvedValue(true)
    vi.mocked(enterOrder).mockRejectedValueOnce(httpError(409, 'order_exists'))
    const w = await mountPanel()
    await fillAndSubmit(w)
    expect(w.find('.order-error').text()).toContain('already recorded')
    vi.mocked(enterOrder).mockRejectedValueOnce(new OrderInvalid('share'))
    await fillAndSubmit(w)
    expect(w.find('.order-error').text()).toContain('neither a link of this instance nor a link id')
  })
})
