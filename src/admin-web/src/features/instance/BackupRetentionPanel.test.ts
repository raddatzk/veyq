// @vitest-environment happy-dom
// uc.admin.back-up-and-restore-instance A12 (admin-web) — Server → Moderation, B, the backups' retention (ADR 0056
// decision 8). Each case is one observable the Server Owner depends on:
//   - the value in effect is shown, and whether it is the default;
//   - a value above thirty says a reason is owed in the record of processing, before anything is saved; thirty does not;
//   - a value outside 7 to 365, or not a whole number, is refused in words and nothing is sent;
//   - a valid value is sent as typed and the panel re-renders from the server's answer.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./backupRetention', async () => {
  const actual = await vi.importActual<typeof import('./backupRetention')>('./backupRetention')
  return { ...actual, getBackupRetention: vi.fn(), setBackupRetention: vi.fn() }
})

import BackupRetentionPanel from './BackupRetentionPanel.vue'
import { getBackupRetention, needsReason, parseRetentionDays, setBackupRetention } from './backupRetention'
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
  const wrapper = mount(BackupRetentionPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('BackupRetentionPanel (uc.admin.back-up-and-restore-instance A12, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(getBackupRetention).mockResolvedValue({ retentionDays: 30, chosen: false })
  })

  it('shows the value in effect and that it is the default', async () => {
    const w = await mountPanel()
    expect((w.find('input.input').element as HTMLInputElement).value).toBe('30')
    expect(w.text()).toContain('Not set yet: 30 days, the default.')
    expect(w.text()).toContain('the newest one too')
  })

  it('says a reason is owed above thirty days, and not at thirty', async () => {
    const w = await mountPanel()
    expect(w.find('.msg[data-severity="warn"]').exists()).toBe(false)
    await w.find('input.input').setValue('31')
    expect(w.find('.msg[data-severity="warn"]').text()).toContain('record of processing')
    expect(setBackupRetention).not.toHaveBeenCalled()
  })

  it.each(['6', '366', '30.5', 'month', ''])('refuses %j in words and sends nothing', async (typed) => {
    const w = await mountPanel()
    await w.find('input.input').setValue(typed)
    await w.find('button').trigger('click')
    await flushPromises()
    expect(setBackupRetention).not.toHaveBeenCalled()
    expect(w.find('.msg[data-severity="error"]').text()).toContain('from 7 to 365')
  })

  it('sends a valid value and re-renders from the answer', async () => {
    vi.mocked(setBackupRetention).mockResolvedValue({ retentionDays: 14, chosen: true })
    const w = await mountPanel()
    await w.find('input.input').setValue(' 14 ')
    await w.find('button').trigger('click')
    await flushPromises()
    expect(setBackupRetention).toHaveBeenCalledWith(14)
    expect(w.text()).toContain('Set by you.')
    expect(w.find('.msg[data-severity="success"]').exists()).toBe(true)
  })
})

describe('the retention rule the console and the CLI share', () => {
  it('takes whole days from 7 to 365 only', () => {
    expect([7, 30, 365].map((d) => parseRetentionDays(String(d)))).toEqual([7, 30, 365])
    for (const bad of ['6', '366', '-1', '1e1', '30.0', ' ', 'x']) expect(parseRetentionDays(bad)).toBeUndefined()
  })
  it('owes a reason above thirty', () => {
    expect([30, 31].map(needsReason)).toEqual([false, true])
  })
})
