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

vi.mock('../features/tenant/tenantInvite', () => ({
  previewInvite: vi.fn(),
  acceptInvite: vi.fn(),
  registerAndAccept: vi.fn(),
  bornTenantScope: vi.fn(),
  tenantError: vi.fn(),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../app/sessionRefresh', () => ({ withRefresh: vi.fn((call: () => unknown) => call()) }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))
vi.mock('../features/account/profile/profile', () => ({ sealName: vi.fn() }))
vi.mock('../features/account/emergencyKit/EmergencyKitSave.vue', () => ({
  default: { template: '<div class="stub-kit" />' },
}))
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn() }))
vi.mock('../api/client', () => ({ setupApi: vi.fn() }))
const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ hash: '#the-bootstrap-secret' }),
}))

import TenantJoinView from './TenantJoinView.vue'
import { setupApi } from '../api/client'
import { stubApi } from '../test/apiMock'
import { previewInvite, acceptInvite, registerAndAccept, bornTenantScope, tenantError } from '../features/tenant/tenantInvite'
import { login } from '../features/account/login/login'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { sealName } from '../features/account/profile/profile'
import { TenantStatus } from '../api/generated/tenants'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { vaultState } from '../app/vault'
import { pwnedCount } from '../shared/pwned'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'

// The layer's real components are stubbed: what this file asserts is a `:disabled` prop and a rendered hint, not
// the layer's own rendering. Same stub set as
// RecoverView.test.ts, plus the two this view uses.
const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: {
    props: ['label', 'loading', 'disabled'],
    template: '<button :disabled="disabled">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqPassword: {
    props: ['modelValue'],
    template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // The breach warning's "Use it anyway": a native checkbox, so a test ticks it with `setValue(true)`.
  VeyqCheckbox: {
    props: ['modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

/** `previewInvite` decides which branch the view renders: `hasAccount` false → register, true → sign in. */
async function mountFor(hasAccount: boolean) {
  vi.mocked(previewInvite).mockResolvedValue(
    invitePreview({ tenantName: 'Acme', domain: 'acme.example', ownerEmail: 'owner@acme.example', hasAccount }),
  )
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

/** The form's submit control, read from the DOM rather than as a component: `type="submit"` falls through
 *  the stub to its root `<button>`, and `disabled` is the thing under test. */
const submitDisabled = (w: ReturnType<typeof mount>) =>
  w.find('button[type="submit"]').attributes('disabled') !== undefined

beforeEach(() => {
  vi.clearAllMocks()
  // The happy path past each file's point of interest, typed by the real functions: these used to live in the
  // mock factory, where nothing checked them against the contract.
  vi.mocked(login).mockResolvedValue(undefined)
  vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
  vi.mocked(acceptInvite).mockResolvedValue({ tenantId: 'tenant-1', status: TenantStatus.Active })
  vi.mocked(registerAndAccept).mockResolvedValue({ tenantId: 'tenant-1', status: TenantStatus.Active, userId: 'user-1' })
  vi.mocked(bornTenantScope).mockReturnValue({
    birth: { grant: 'AQ==', grantSignature: 'AQ==' },
    scopeRootKey: new Uint8Array(32),
  })
  vi.mocked(sealName).mockReturnValue('AQ==')
  vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
  stubApi(setupApi, {
    getSetupStatus: setupStatus(),
  })
  // The breach check is automatic, so every register submit passes through it: clean unless a case says otherwise.
  vi.mocked(pwnedCount).mockResolvedValue(0)
  vaultState.unlocked = false
})

describe('TenantJoinView — where the password floor applies', () => {
  it('REGISTER: refuses a password under the floor and names it; accepts exactly at the floor', async () => {
    const w = await mountFor(false)
    await w.find('input[name="username"]').setValue('kevin')

    await w.find('input[type="password"]').setValue('short')  // 5 < 10
    await flushPromises()
    expect(w.text()).toContain(t('password.minLength'))
    expect(submitDisabled(w)).toBe(true)

    // The boundary, not merely "long enough": `<` vs `<=` is an off-by-one nobody would notice by hand.
    await w.find('input[type="password"]').setValue('0123456789')
    await flushPromises()
    expect(submitDisabled(w)).toBe(false)

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).toHaveBeenCalledOnce()
  })

  it('REGISTER: the finished password is checked when its field is left — never while typing, once per password', async () => {
    const w = await mountFor(false)
    const field = w.find('input[type="password"]')
    await field.setValue('a long')
    await field.setValue('a long enough password')
    await flushPromises()
    expect(pwnedCount).not.toHaveBeenCalled()

    await field.trigger('blur')
    await field.trigger('blur')
    await flushPromises()
    expect(pwnedCount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledWith('a long enough password', 'https://api.pwnedpasswords.com/range/')
  })

  it('REGISTER: a hit blocks the submit until "Use it anyway" — pressing submit again is not the acceptance', async () => {
    // The slip the composable's own test cannot see: a dialog that forgets to await the guard.
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = await mountFor(false)
    await w.find('input[name="username"]').setValue('kevin')
    await w.find('input[type="password"]').setValue('a long enough password')
    await flushPromises()

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('password.pwnedWarning', { count: 3 }))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).not.toHaveBeenCalled()

    await w.find('input[type="checkbox"]').setValue(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('REGISTER: a check that could not run goes ahead, and says so in one line', async () => {
    vi.mocked(pwnedCount).mockResolvedValue(null)
    const w = await mountFor(false)
    await w.find('input[name="username"]').setValue('kevin')
    const field = w.find('input[type="password"]')
    await field.setValue('a long enough password')
    await field.trigger('blur')
    await flushPromises()
    expect(w.text()).toContain(t('password.pwnedUnavailable'))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(buildAccount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('SIGN IN: the password that proves an existing account is never sent to the breach check', async () => {
    // The same `password` ref serves sign-in, which chooses nothing. Its field carries no blur check and its submit
    // no guard; the register case above is the control on the same lookup.
    const w = await mountFor(true)
    await w.find('input[name="username"]').setValue('kevin')
    const field = w.find('input[type="password"]')
    await field.setValue('a long enough password')
    await field.trigger('blur')
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(login).toHaveBeenCalledWith('owner@acme.example', 'a long enough password')
    expect(pwnedCount).not.toHaveBeenCalled()
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

    expect(w.text()).not.toContain(t('password.minLength'))
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

// Beside the floor, because this file already mounts both branches: GDPR Art. 13 applies where an account is born (and
// where an existing one joins a space with a new username), so both branches link the privacy notice's account passage,
// in a tab of its own — the invite's secret rides this page's fragment (ADR 0035 decision 8, uc.admin.privacy-notice).
describe('TenantJoinView — the privacy notice where an account is born', () => {
  it.each([
    ['REGISTER', false],
    ['SIGN IN', true],
  ])('%s: links the notice at its account passage, in a tab of its own', async (_branch, hasAccount) => {
    const w = await mountFor(hasAccount)
    const link = w.find('.join-privacy a')
    expect(link.attributes('href')).toBe('/privacy#account')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.text()).toBe(t('privacyNotice.atInvite'))
  })
})
