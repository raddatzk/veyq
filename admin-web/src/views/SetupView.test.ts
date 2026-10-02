// @vitest-environment happy-dom
// uc.admin.setup-server — what the first-run SCREEN does with the opt-in breached-password check
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
      password: { minLength: 'Use at least 10 characters.', strong: 'Strong.', pwnedWarning: 'Seen in {count}.', pwnedUnavailable: 'Not checked.' },
      setup: { title: 'Set up', sub: 'sub', submit: 'Set up', submitting: 'Setting up', failed: 'failed', kitTitle: 'Kit', kitSub: 'kit', kitProceed: 'Proceed' },
    },
  },
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(registerServerOwner).mockResolvedValue({ userId: 'u-owner', emergencyKit: { secretKey: 'A3-SECRET' } })
})

describe('SetupView — the breached-password check', () => {
  it('with the box ticked, a hit stops the first submit and the second goes ahead', async () => {
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = mount(SetupView, { global: { plugins: [i18n], stubs } })
    await flushPromises()

    await w.find('input[type="email"]').setValue('owner@example.com')
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('input[type="checkbox"]').setValue(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).not.toHaveBeenCalled()
    expect(w.text()).toContain('Seen in 3.')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).toHaveBeenCalledWith('owner@example.com', 'a long enough password')
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('with the box left unticked, nothing is looked up and setup goes ahead at once', async () => {
    // The control for the case above: a view that stops every submit would satisfy it just as well.
    const w = mount(SetupView, { global: { plugins: [i18n], stubs } })
    await flushPromises()

    await w.find('input[type="email"]').setValue('owner@example.com')
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(registerServerOwner).toHaveBeenCalledOnce()
    expect(pwnedCount).not.toHaveBeenCalled()
  })
})
