// @vitest-environment happy-dom
// The password floor on the invite-accept screen, and — the point of this file — the two places it must NOT
// apply. `password` is ONE ref serving three different acts in this view: register a new account (sets a
// password), sign in as an existing owner (does not), and re-authenticate after a lapsed session (does not).
// A floor applied to the whole ref would lock an existing owner out of accepting their own invite whenever
// their password predates the policy — on the screen whose entire job is letting them in.
//
// That is why the negatives are cases rather than a comment: a later "consistency" edit that gates all three
// looks tidier than what is here, and only a test says why it is wrong.
//
// The floor itself is client-side by construction, not by omission: under Model B (ADR 0003) the password
// never leaves the device and the server stores no credential, so there is no server rule this mirrors and
// none that would catch a bypass.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../features/tenant/tenantInvite', () => ({
  previewInvite: vi.fn(),
  acceptInvite: vi.fn().mockResolvedValue({}),
  registerAndAccept: vi.fn().mockResolvedValue({}),
  bornTenantScope: vi.fn().mockReturnValue({ grant: 'AQ==', scopeRootKey: new Uint8Array(32) }),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn().mockResolvedValue(undefined) }))
vi.mock('../app/sessionRefresh', () => ({ withRefresh: vi.fn((call: () => unknown) => call()) }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn().mockResolvedValue(undefined) }))
vi.mock('../features/account/profile/profile', () => ({ sealName: vi.fn().mockResolvedValue(undefined) }))
vi.mock('../features/account/emergencyKit/EmergencyKitSave.vue', () => ({
  default: { template: '<div class="stub-kit" />' },
}))
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn() }))
vi.mock('../api/client', () => ({
  setupApi: () => ({ getSetupStatus: vi.fn().mockResolvedValue({ instanceSalt: 'AAAA' }) }),
}))
const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ hash: '#the-bootstrap-secret' }),
}))

import TenantJoinView from './TenantJoinView.vue'
import { previewInvite } from '../features/tenant/tenantInvite'
import { login } from '../features/account/login/login'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { vaultState } from '../app/vault'
import { pwnedCount } from '../shared/pwned'

// PrimeVue's real components need the plugin's config injection, which this file has no reason to install:
// what it asserts is a `:disabled` prop and a rendered hint, not PrimeVue's own rendering. Same stub set as
// RecoverView.test.ts, plus the two this view uses.
const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: {
    props: ['label', 'loading', 'disabled'],
    template: '<button :disabled="disabled">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Password: {
    props: ['modelValue'],
    template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // The breach-check box: a native checkbox, so a test ticks it with `setValue(true)`.
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
      common: { password: 'Password' },
      unlock: { secretKey: 'Secret Key' },
      profile: { firstName: 'First', lastName: 'Last' },
      login: { title: 'Sign in' },
      password: { minLength: 'Use at least 10 characters.', strong: 'Strong.', pwnedWarning: 'Seen in {count}.', pwnedUnavailable: 'Not checked.' },
      tenant: {
        join: {
          title: 'Join', loading: 'Loading…', invitedTo: 'Invited', continue: 'Continue', join: 'Join',
          username: 'Username', usernameHint: 'hint', nameHint: 'hint', registerSub: 'sub', signinSub: 'sub',
          signinReadySub: 'sub', reauthSub: 'sub', kitSub: 'sub', kitProceed: 'Proceed', done: 'Done',
          doneNote: 'note', failed: 'failed', expired: 'expired', invalid: 'invalid', emailTaken: 'taken',
          usernameTaken: 'taken', wrongOwner: 'wrong',
        },
      },
    },
  },
})

/** `previewInvite` decides which branch the view renders: `hasAccount` false → register, true → sign in. */
async function mountFor(hasAccount: boolean) {
  vi.mocked(previewInvite).mockResolvedValue({
    tenantName: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', hasAccount, role: 'OWNER',
  } as never)
  const w = mount(TenantJoinView, { global: { plugins: [i18n], stubs } })
  await flushPromises()
  return w
}

/** The form's submit control, read from the DOM rather than as a component: `type="submit"` falls through
 *  the stub to its root `<button>`, and `disabled` is the thing under test. */
const submitDisabled = (w: ReturnType<typeof mount>) =>
  w.find('button[type="submit"]').attributes('disabled') !== undefined

beforeEach(() => {
  vi.clearAllMocks()
  vaultState.unlocked = false
})

describe('TenantJoinView — where the password floor applies', () => {
  it('REGISTER: refuses a password under the floor and names it; accepts exactly at the floor', async () => {
    const w = await mountFor(false)
    await w.find('input[name="username"]').setValue('kevin')

    await w.find('input[type="password"]').setValue('short')  // 5 < 10
    await flushPromises()
    expect(w.text()).toContain('Use at least 10 characters.')
    expect(submitDisabled(w)).toBe(true)

    // The boundary, not merely "long enough": `<` vs `<=` is an off-by-one nobody would notice by hand.
    await w.find('input[type="password"]').setValue('0123456789')
    await flushPromises()
    expect(submitDisabled(w)).toBe(false)

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).toHaveBeenCalledOnce()
  })

  it('REGISTER with the breach check ticked: a hit stops the first submit and the second goes ahead', async () => {
    // The slip the composable's own test cannot see: a dialog that forgets to await the guard.
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = await mountFor(false)
    await w.find('input[name="username"]').setValue('kevin')
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('input[type="checkbox"]').setValue(true)
    await flushPromises()

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).not.toHaveBeenCalled()
    expect(w.text()).toContain('Seen in 3.')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })
})

describe('TenantJoinView — where it must NOT apply', () => {
  it('SIGN IN: an existing owner with a short password can still accept their invite', async () => {
    // The account already exists; its password was set before any floor existed and cannot be changed from
    // here. Gating this branch would refuse the person the screen is for.
    const w = await mountFor(true)
    await w.find('input[name="username"]').setValue('kevin')

    await w.find('input[type="password"]').setValue('short')                  // 5 < 10
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await flushPromises()

    expect(w.text()).not.toContain('Use at least 10 characters.')
    expect(submitDisabled(w)).toBe(false)

    // `login` reached WITH the short password is the whole assertion: it is the first thing past the guard,
    // so nothing between the field and it refused on length. What happens after (unlock, the cold scope
    // birth, accept) needs a real vault and is other tests' subject — asserting it here would make this case
    // fail for reasons that have nothing to do with the floor.
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(login).toHaveBeenCalledWith('owner@acme.example', 'short')
  })
})
