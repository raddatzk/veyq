// @vitest-environment happy-dom
// An OWNER's accept holds the tenant key its birth minted (ADR 0111 decision 23, inbox #740): the owner's device must not
// take its first open of the tenant as a first grant, or an operator serves it a higher epoch around a key of its own
// under a granter it fabricated. Held at the birth epoch, under the vault's Master Key - or, for a new account, under the
// Master Key its birth handed back, since no vault holds one before the first sign-in. A MEMBER's checked key is held by
// `acceptCheckedInvite` (tenantInvite.firstOpen.test.ts, TenantJoinView.member.test.ts).
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../features/tenant/tenantInvite', () => ({
  InviteCheckFailed: class InviteCheckFailed extends Error {},
  previewInvite: vi.fn(),
  acceptInvite: vi.fn(),
  acceptOwnership: vi.fn(),
  registerAndAccept: vi.fn(),
  bornTenantScope: vi.fn(),
  tenantError: vi.fn(),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../app/sessionRefresh', () => ({ withRefresh: vi.fn((call: () => unknown) => call()) }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))
vi.mock('../features/account/profile/profile', () => ({ sealName: vi.fn(), sealAvatar: vi.fn(), myUserId: vi.fn() }))
vi.mock('../features/account/emergencyKit/EmergencyKitSave.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    setup: (_: unknown, { emit }: { emit: (e: string, v: boolean) => void }) => {
      void Promise.resolve().then(() => emit('update:modelValue', true))
      return {}
    },
    template: '<div class="stub-kit" />',
  },
}))
vi.mock('../features/account/profile/JoinPicturePicker.vue', () => ({ default: { template: '<div class="stub-picker" />' } }))
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn(async () => 0) }))
vi.mock('../api/client', () => ({ setupApi: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ hash: '#the-bootstrap-secret' }),
}))
vi.mock('../app/vault', async (orig) => ({
  ...(await orig<typeof import('../app/vault')>()),
  identityPrivate: vi.fn(),
  masterKey: vi.fn(),
}))

import TenantJoinView from './TenantJoinView.vue'
import { setupApi } from '../api/client'
import { stubApi } from '../test/apiMock'
import { acceptInvite, bornTenantScope, previewInvite, registerAndAccept, tenantError } from '../features/tenant/tenantInvite'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { myUserId } from '../features/account/profile/profile'
import { identityPrivate, masterKey, vaultState } from '../app/vault'
import { setAccount } from '../app/account'
import { epochKeyCommitment, heldEpochKey } from '../features/account/trustMarks'
import { InviteRole, TenantStatus } from '../api/generated/tenants'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'
import * as v1 from '@veyq/crypto/v1'

const TENANT_ID = '5c09e000-0000-4000-8000-0000000000e3'
const EMAIL = 'owner@acme.example'
const BORN_KEY = v1.randomBytes(32)
const BORN_MK = v1.randomBytes(32)
const VAULT_MK = v1.randomBytes(32)

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: {
    props: ['label', 'loading', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue', 'name'], template: '<input :name="name" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqPassword: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqCheckbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
  SecretKeyField: { props: ['modelValue'], template: '<input class="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

async function mountView(hasAccount: boolean): Promise<VueWrapper> {
  vi.mocked(previewInvite).mockResolvedValue(invitePreview({ tenantId: TENANT_ID, ownerEmail: EMAIL, hasAccount, role: InviteRole.Owner }))
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

/** The tenant key this device holds once the vault holds [mk]. */
async function heldUnder(mk: Uint8Array) {
  vi.mocked(masterKey).mockImplementation(() => mk.slice())
  return heldEpochKey(TENANT_ID)
}

/** Wait until the accept settled: the done step, or an error message. IndexedDB settles on macrotasks. */
const settled = (w: VueWrapper, text: string) => vi.waitFor(() => expect(w.text()).toContain(text))

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // a device that has held nothing yet
  vi.mocked(masterKey).mockReturnValue(null)
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: v1.randomBytes(32), sign: v1.randomBytes(32) }))
  vi.mocked(myUserId).mockResolvedValue('5c09e000-0000-4000-8000-0000000000e4')
  vi.mocked(bornTenantScope).mockReturnValue({
    birth: { grant: 'AQ==', grantSignature: 'AQ==', recipientIdentityDigest: 'AQ==' },
    scopeRootKey: BORN_KEY,
  })
  vi.mocked(buildAccount).mockResolvedValue({
    identity: v1.generateIdentity(), masterKey: BORN_MK.slice(), material: {}, emergencyKit: { secretKey: 'SK' },
  } as unknown as Awaited<ReturnType<typeof buildAccount>>)
  vi.mocked(acceptInvite).mockResolvedValue({ tenantId: TENANT_ID, status: TenantStatus.Active })
  vi.mocked(registerAndAccept).mockResolvedValue({ tenantId: TENANT_ID, status: TenantStatus.Active, userId: 'u' })
  vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
  vi.mocked(unlockMasterKey).mockImplementation(async () => {
    vi.mocked(masterKey).mockImplementation(() => VAULT_MK.slice())
  })
  stubApi(setupApi, { getSetupStatus: setupStatus() })
  setAccount('someone-else@acme.example')
  vaultState.unlocked = false
})

const BORN = { epoch: 0, commitment: epochKeyCommitment(BORN_KEY) }

describe('TenantJoinView - an OWNER\'s accept holds the tenant key its birth minted (inbox #740)', () => {
  it('a new account: held under the Master Key its birth handed back, which is wiped afterwards', async () => {
    const w = await mountView(false)
    await w.find('input[name="username"]').setValue('kevin')
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('form').trigger('submit')
    await flushPromises()
    await w.findAll('button').find((b) => b.text() === t('tenant.join.join'))!.trigger('click')
    await settled(w, t('tenant.join.doneNote'))
    const born = (await vi.mocked(buildAccount).mock.results[0].value) as { masterKey: Uint8Array }
    expect(born.masterKey.every((b) => b === 0)).toBe(true)
    expect(await heldUnder(BORN_MK)).toEqual(BORN)
  })

  it('an existing account signing in: held under the vault its unlock opened', async () => {
    const w = await mountView(true)
    await w.find('input[name="username"]').setValue('kevin')
    await w.find('input[type="password"]').setValue('the password')
    await w.find('input.secret-key').setValue('A3-SECRET')
    await w.find('form').trigger('submit')
    await settled(w, t('tenant.join.doneNote'))
    expect(await heldUnder(VAULT_MK)).toEqual(BORN)
  })

  it('the one-click accept of an unlocked vault: held under that vault', async () => {
    setAccount(EMAIL)
    vaultState.unlocked = true
    vi.mocked(masterKey).mockImplementation(() => VAULT_MK.slice())
    const w = await mountView(true)
    await w.find('input[name="username"]').setValue('kevin')
    await w.findAll('button').find((b) => b.text() === t('tenant.join.join'))!.trigger('click')
    await settled(w, t('tenant.join.doneNote'))
    expect(await heldUnder(VAULT_MK)).toEqual(BORN)
  })

  it('an accept the server refuses holds nothing', async () => {
    setAccount(EMAIL)
    vaultState.unlocked = true
    vi.mocked(masterKey).mockImplementation(() => VAULT_MK.slice())
    vi.mocked(acceptInvite).mockRejectedValue(new Error('410'))
    vi.mocked(tenantError).mockResolvedValue({ status: 410, code: null })
    const w = await mountView(true)
    await w.find('input[name="username"]').setValue('kevin')
    await w.findAll('button').find((b) => b.text() === t('tenant.join.join'))!.trigger('click')
    await flushPromises()
    expect(acceptInvite).toHaveBeenCalledTimes(1)
    expect(await heldUnder(VAULT_MK)).toBeNull()
  })
})
