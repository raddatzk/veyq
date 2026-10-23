// @vitest-environment happy-dom
// uc.moderation.authority-order (web) — the moderator's order form above the queue:
//   - folded until asked for; opening it reads the recorded orders, each reference opened here;
//   - a removal order asks first, naming that it blocks for everyone; declined, nothing is sent;
//   - accepted, the block time to the second in UTC and the Annex II text with the reference as typed, draft banner first;
//   - a share not on this instance shows the Annex III draft; an order already recorded is said in words.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'

const ask = vi.hoisted(() => vi.fn())
vi.mock('../../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))
vi.mock('./orders', () => ({ enterOrder: vi.fn(), listOrders: vi.fn(), openReferenceOf: vi.fn() }))

import OrderForm from './OrderForm.vue'
import { enterOrder, listOrders, openReferenceOf, type AuthorityOrder } from './orders'
import { ORDER_FORMS } from './orderForms'
import { realI18n, t } from '../../../test/i18n'

const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'

const order = (over: Partial<AuthorityOrder> = {}): AuthorityOrder => ({
  id: '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b', kind: 'REMOVAL', shareId: SHARE, receivedAt: new Date('2026-10-18T07:12:00Z'),
  enteredAt: new Date('2026-10-18T07:20:11Z'), enteredBy: 'mod', found: true, blockedAt: new Date('2026-10-18T07:20:11.734Z'),
  caseId: 'case-of-order', holdUntil: new Date('2027-04-19T07:20:11Z'), statementDeferredUntil: undefined, epoch: 2,
  sealedReference: 'c2VhbGVk', providerName: undefined, contactPoint: 'abuse@example.org', ...over,
})

const stubs = {
  VeyqButton: {
    props: ['label'],
    emits: ['click'],
    template: '<button :class="$attrs.class" :type="$attrs.type ?? \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg" :class="$attrs.class"><slot /></div>' },
}

async function opened() {
  const w = mount(OrderForm, { global: { plugins: [realI18n()], stubs } })
  await w.find('.moderation-orders-toggle').trigger('click')
  await flushPromises()
  return w
}

async function submit(w: Awaited<ReturnType<typeof opened>>) {
  await w.find('.moderation-order-share').setValue(`https://club.example.org/s/${SHARE}#k=secret`)
  await w.find('.moderation-order-reference').setValue('BKA ST-2026-0417')
  await w.find('.moderation-order-form').trigger('submit')
  await flushPromises()
}

describe('OrderForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ask.mockResolvedValue(true)
    vi.mocked(listOrders).mockResolvedValue([order({ id: 'earlier' })])
    vi.mocked(openReferenceOf).mockReturnValue('earlier reference')
  })

  it('is folded until asked for, and lists the recorded orders with their references opened', async () => {
    const folded = mount(OrderForm, { global: { plugins: [realI18n()], stubs } })
    expect(folded.find('.moderation-order-form').exists()).toBe(false)
    expect(listOrders).not.toHaveBeenCalled()
    const w = await opened()
    expect(w.find('.moderation-order-row').text()).toContain('earlier reference')
  })

  it('asks before a removal order, naming that it blocks for everyone; declined, nothing is sent', async () => {
    ask.mockResolvedValue(false)
    const w = await opened()
    await submit(w)
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ message: t('moderation.orders.confirmRemoval') }))
    expect(enterOrder).not.toHaveBeenCalled()
  })

  it('shows the block time to the second and the Annex II text, the reference as typed, draft banner first', async () => {
    vi.mocked(enterOrder).mockResolvedValue(order())
    const w = await opened()
    await submit(w)
    expect(vi.mocked(enterOrder).mock.calls[0][0]).toMatchObject({ kind: 'REMOVAL', reference: 'BKA ST-2026-0417' })
    expect(w.find('.moderation-order-blocked').text()).toContain('2026-10-18 07:20:11 UTC')
    const text = (w.find('.moderation-order-text').element as HTMLTextAreaElement).value
    expect(text.split('\n')[0]).toBe(ORDER_FORMS.en.draftBanner)
    expect(text).toContain('Reference of the removal order: BKA ST-2026-0417')
    await w.find('.moderation-order-case').trigger('click')
    expect(w.emitted('openCase')![0]).toEqual(['case-of-order'])
  })

  it('shows the Annex III draft for a share not on this instance, and says an order already recorded', async () => {
    vi.mocked(enterOrder).mockResolvedValueOnce(order({ found: false, blockedAt: undefined, caseId: undefined, holdUntil: undefined }))
    const w = await opened()
    await submit(w)
    expect(w.find('.moderation-order-not-found').exists()).toBe(true)
    expect((w.find('.moderation-order-text').element as HTMLTextAreaElement).value).toContain('no shared link with this identifier exists')
    vi.mocked(enterOrder).mockRejectedValueOnce(httpError(409, 'order_exists'))
    await submit(w)
    expect(w.find('.moderation-orders-error').text()).toBe(t('moderation.errors.orderExists'))
  })
})
