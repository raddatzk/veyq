// @vitest-environment happy-dom
// uc.admin.create-tenant Act 2, login branch × uc.account.unlock § One Secret-Key control.
//
// An invited owner who already has an account signs in and unlocks ON THIS SCREEN, so it asks for a Secret
// Key — and it was the one screen asking for one with no way to import the Emergency Kit, which is what
// started this. What this file owns is therefore not the import mechanics (`SecretKeyField.test.ts` has those)
// but the two things only this host decides: that the affordance is REACHABLE here at all, and that the
// account it checks an imported kit against is the invite's owner e-mail rather than anything the person typed.
//
// The real `SecretKeyField` is mounted (not stubbed) for the same reason: a stub would assert that this view
// renders a component, which is exactly the claim that was already true of a bare input.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

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
vi.mock('../api/client', () => ({ setupApi: vi.fn() }))
// Only the file/QR decode is faked; `parseKit`/`kitPayload` stay real, so the pasted token this test hands the
// field is the one the kit generator actually emits.
vi.mock('../features/account/emergencyKit/emergencyKit', async (orig) => ({
  ...(await orig<typeof import('../features/account/emergencyKit/emergencyKit')>()),
  importKitFromFile: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
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
import { kitPayload, type KitData } from '@veyq/crypto/emergencyKit'
import { importKitFromFile } from '../features/account/emergencyKit/emergencyKit'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'

const OWNER_EMAIL = 'owner@acme.example'
// 32 bytes, as a real kit carries — the parser rejects a Secret Key of any other length.
const OWNERS_KIT: KitData = { email: OWNER_EMAIL, secretKey: 'IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI=' }

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: { props: ['label', 'loading', 'disabled', 'type'], template: '<button :type="type ?? \'button\'" :disabled="disabled">{{ label }}</button>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-tone="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqPassword: {
    props: ['modelValue', 'inputId'],
    template: '<input type="password" :id="inputId" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  KitScanner: true,
}

const mounted: VueWrapper[] = []

/** The sign-in branch: `hasAccount: true` and a locked vault → the screen asks for password + Secret Key. */
async function mountSignInBranch(): Promise<VueWrapper> {
  vi.mocked(previewInvite).mockResolvedValue(
    invitePreview({ tenantName: 'Acme', domain: 'acme.example', ownerEmail: OWNER_EMAIL, hasAccount: true }),
  )
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  mounted.push(w)
  await flushPromises()
  return w
}

const secretField = (w: VueWrapper) => w.find('input[name="secret-key"]')
const fileField = (w: VueWrapper) => w.find('input[type="file"]')
const uploadIcon = (w: VueWrapper) =>
  w.findAll('button').find((b) => b.attributes('aria-label') === t('unlock.upload'))
const warnings = (w: VueWrapper) => w.findAll('.msg').filter((m) => m.attributes('data-tone') === 'warn')

async function pickKit(w: VueWrapper, kit: KitData | null) {
  vi.mocked(importKitFromFile).mockResolvedValue(kit)
  const input = fileField(w).element as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [new File(['kit'], 'kit.pdf')], writable: true, configurable: true })
  await fileField(w).trigger('change')
  await flushPromises()
}

async function acceptWith(w: VueWrapper) {
  await w.find('input[name="username"]').setValue('root')
  await w.find('#signin-password').setValue('the-password')
  await w.find('form').trigger('submit')
  await flushPromises()
}

describe('TenantJoinView — importing the Emergency Kit on the invite-accept screen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // The happy path past each file's point of interest, typed by the real functions: these used to live in the
    // mock factory, where nothing checked them against the contract.
    vi.mocked(login).mockResolvedValue(undefined)
    vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
    vi.mocked(acceptInvite).mockResolvedValue({ tenantId: 'tenant-1', status: TenantStatus.Active })
    vi.mocked(registerAndAccept).mockResolvedValue({ tenantId: 'tenant-1', status: TenantStatus.Active, userId: 'user-1' })
    vi.mocked(bornTenantScope).mockReturnValue({
      birth: { grant: 'AQ==', grantSignature: 'AQ==', admissionId: 'admission-1', admissionEpoch: 0, admissionTag: 'AQ==' },
      scopeRootKey: new Uint8Array(32),
    })
    vi.mocked(sealName).mockReturnValue('AQ==')
    vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
    stubApi(setupApi, {
      getSetupStatus: setupStatus(),
    })
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: vi.fn() }, configurable: true })
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
  })

  it('offers the kit import where it asks for the Secret Key, and unlocks with the imported key', async () => {
    const w = await mountSignInBranch()

    expect(secretField(w).exists()).toBe(true)
    expect(uploadIcon(w)).toBeDefined()

    await pickKit(w, OWNERS_KIT)
    // The whole point: the key reaches the unlock without anyone typing 32 bytes of base64. The unlock is
    // where this file stops — what the view does with the opened identity afterwards (cold-birth the scope,
    // seal the grant) needs a real vault and is `TenantJoinView.passwordPolicy.test.ts`'s and the invite
    // module's ground.
    await acceptWith(w)
    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', OWNERS_KIT.secretKey)
  })

  it('checks an imported kit against the INVITED owner, and accepting it stays possible', async () => {
    const w = await mountSignInBranch()

    await pickKit(w, { email: 'someone.else@example.com', secretKey: 'dGhlLXdyb25nLW9uZQ==' })

    const warned = warnings(w)
    expect(warned).toHaveLength(1)
    expect(warned[0].text()).toContain('someone.else@example.com')
    expect(warned[0].text()).toContain(OWNER_EMAIL)
    // Warn, not block — the submit still goes through, because only the wrap can judge a key and the e-mail in
    // a kit is metadata (this owner may have changed address since they saved it).
    await acceptWith(w)
    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', 'dGhlLXdyb25nLW9uZQ==')

    // The control on the same predicate: the invited owner's own kit draws no warning, so the assertion above
    // is about the address and not about "a kit was imported".
    const right = await mountSignInBranch()
    await pickKit(right, OWNERS_KIT)
    expect(warnings(right)).toHaveLength(0)
  })

  it('takes a pasted kit token in place of the key — the paste this screen used to reject', async () => {
    const w = await mountSignInBranch()

    // Before the shared control, only the recovery screen decoded a pasted token; here the whole string went
    // to `fromB64std` and failed as a generic unlock error.
    await secretField(w).setValue(kitPayload(OWNERS_KIT))
    await flushPromises()
    await acceptWith(w)

    expect(unlockMasterKey).toHaveBeenCalledWith('the-password', OWNERS_KIT.secretKey)
  })
})
