// @vitest-environment happy-dom
// uc.admin.setup-server — what the first-run SCREEN does with the automatic breached-password check
// (docs/crosscutting/security-model.md § the breached-password check). The check's own rule is pinned by
// features/account/passwordWarning.test.ts; what only this file can see is a dialog that forgets to await the
// guard, which the composable test is blind to.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../features/setup/setupServer', () => ({ registerServerOwner: vi.fn() }))
vi.mock('../features/setup/EmergencyKitSave.vue', () => ({ default: { template: '<div class="stub-kit" />' } }))
// The breach lookup is the only thing stubbed of the check: the view's real guard runs.
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import SetupView from './SetupView.vue'
import { registerServerOwner } from '../features/setup/setupServer'
import { pwnedCount } from '../shared/pwned'

// PrimeVue's real components need the plugin's config injection, which this file has no reason to install.
const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label', 'loading', 'disabled'], template: '<button :disabled="disabled">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Password: {
    props: ['modelValue'],
    template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // The breach warning's "Use it anyway": a native checkbox, so a test ticks it with `setValue(true)`.
  Checkbox: {
    props: ['modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { email: 'Email', password: 'Password' },
      password: { minLength: 'Use at least 10 characters.', strong: 'Strong.', pwnedNote: 'Prefix to HIBP.', pwnedWarning: 'Seen in {count}.', pwnedAnyway: 'Use it anyway', pwnedUnavailable: 'Not checked.' },
      setup: { title: 'Set up', sub: 'sub', submit: 'Set up', submitting: 'Setting up', failed: 'failed', kitTitle: 'Kit', kitSub: 'kit', kitProceed: 'Proceed' },
    },
  },
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(registerServerOwner).mockResolvedValue({ userId: 'u-owner', emergencyKit: { secretKey: 'A3-SECRET' } })
  // The check is automatic, so every submit passes through it: clean unless a case says otherwise.
  vi.mocked(pwnedCount).mockResolvedValue(0)
})

describe('SetupView — the breached-password check', () => {
  const mountView = async () => {
    const w = mount(SetupView, { global: { plugins: [i18n], stubs } })
    await flushPromises()
    await w.find('input[type="email"]').setValue('owner@example.com')
    return w
  }

  it('checks the finished password when its field is left — never while typing, once per password', async () => {
    const w = await mountView()
    expect(w.text()).toContain('Prefix to HIBP.')
    const field = w.find('input[type="password"]')
    await field.setValue('a long')
    await field.setValue('a long enough password')
    await flushPromises()
    expect(pwnedCount).not.toHaveBeenCalled()

    await field.trigger('blur')
    await field.trigger('blur')
    await flushPromises()
    expect(pwnedCount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledWith('a long enough password')
  })

  it('a clean password goes ahead at once', async () => {
    // The control for the hit below: a view that stops every submit would satisfy it just as well.
    const w = await mountView()
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).toHaveBeenCalledWith('owner@example.com', 'a long enough password')
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('a hit blocks the submit until "Use it anyway" — pressing submit again is not the acceptance', async () => {
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = await mountView()
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).not.toHaveBeenCalled()
    expect(w.text()).toContain('Seen in 3.')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).not.toHaveBeenCalled()

    await w.find('input[type="checkbox"]').setValue(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).toHaveBeenCalledWith('owner@example.com', 'a long enough password')
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('a check that could not run goes ahead, and says so in one line', async () => {
    // The admin container may have no route out: an unreachable HIBP must never block setting up the server.
    vi.mocked(pwnedCount).mockResolvedValue(null)
    const w = await mountView()
    const field = w.find('input[type="password"]')
    await field.setValue('a long enough password')
    await field.trigger('blur')
    await flushPromises()
    expect(w.text()).toContain('Not checked.')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })
})
