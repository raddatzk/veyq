// @vitest-environment happy-dom
// en.moderation.guest-write-record (admin-web) — Server → Moderation, the record's retention (ADR 0093 § 10, ADR 0092
// § 2). Each case is one observable the Server Owner depends on:
//   - the value in effect is shown, and whether it is the default;
//   - a value above seven says a reason is owed in the record of processing, before anything is saved; seven does not;
//   - a value outside 1 to 30, or not a whole number, is refused in words and nothing is sent;
//   - a valid value is sent as typed and the panel re-renders from the server's answer.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./guestWriteRecord', async () => {
  const actual = await vi.importActual<typeof import('./guestWriteRecord')>('./guestWriteRecord')
  return { ...actual, getGuestWriteRecord: vi.fn(), setGuestWriteRecord: vi.fn() }
})

import GuestWriteRecordPanel from './GuestWriteRecordPanel.vue'
import { getGuestWriteRecord, parseRetentionDays, setGuestWriteRecord, needsReason } from './guestWriteRecord'
import { i18n } from '../../i18n'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqButton: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(GuestWriteRecordPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('GuestWriteRecordPanel (en.moderation.guest-write-record, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(getGuestWriteRecord).mockResolvedValue({ retentionDays: 7, chosen: false })
  })

  it('shows the value in effect and that it is the default', async () => {
    const w = await mountPanel()
    expect((w.find('input.input').element as HTMLInputElement).value).toBe('7')
    expect(w.text()).toContain('Not set yet: 7 days, the default.')
    // No switch of its own: the panel names the two that decide where a guest may write (ADR 0093 § 10).
    expect(w.text()).toContain('Links to look and add')
    expect(w.text()).toContain('Drop boxes')
    expect(w.text()).not.toContain('Record guests’ writes')
  })

  it('says a reason is owed above seven days, and not at seven', async () => {
    const w = await mountPanel()
    expect(w.find('.msg[data-severity="warn"]').exists()).toBe(false)
    await w.find('input.input').setValue('8')
    expect(w.find('.msg[data-severity="warn"]').text()).toContain('record of processing')
    expect(setGuestWriteRecord).not.toHaveBeenCalled()
  })

  it.each(['0', '31', '7.5', 'seven', ''])('refuses %j in words and sends nothing', async (typed) => {
    const w = await mountPanel()
    await w.find('input.input').setValue(typed)
    await w.find('button').trigger('click')
    await flushPromises()
    expect(setGuestWriteRecord).not.toHaveBeenCalled()
    expect(w.find('.msg[data-severity="error"]').text()).toContain('from 1 to 30')
  })

  it('sends a valid value and re-renders from the answer', async () => {
    vi.mocked(setGuestWriteRecord).mockResolvedValue({ retentionDays: 14, chosen: true })
    const w = await mountPanel()
    await w.find('input.input').setValue(' 14 ')
    await w.find('button').trigger('click')
    await flushPromises()
    expect(setGuestWriteRecord).toHaveBeenCalledWith(14)
    expect(w.text()).toContain('Set by you.')
    expect(w.find('.msg[data-severity="success"]').exists()).toBe(true)
  })
})

describe('the retention rule the console and the CLI share', () => {
  it('takes whole days from 1 to 30 only', () => {
    expect([1, 7, 30].map((d) => parseRetentionDays(String(d)))).toEqual([1, 7, 30])
    for (const bad of ['0', '31', '-1', '1e1', '2.0', ' ', 'x']) expect(parseRetentionDays(bad)).toBeUndefined()
  })
  it('owes a reason above seven', () => {
    expect([7, 8].map(needsReason)).toEqual([false, true])
  })
})
