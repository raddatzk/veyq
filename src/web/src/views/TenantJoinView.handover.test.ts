// @vitest-environment happy-dom
// The handover (ADR 0101 decision 9): an owner invite into an ACTIVE tenant whose member the invitee already is. The
// preview says so (`handover`); accepting makes the member an owner through `acceptOwnership` — no scope birth, no
// username, no name — and the done screen opens the space. Every other invite keeps the birth accept (the control).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/tenant/tenantInvite', () => ({
  // The view tells a failed invite check from an HTTP refusal by its class (mapError).
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
vi.mock('../features/account/profile/JoinPicturePicker.vue', () => ({ default: { template: '<div class="stub-picker" />' } }))
vi.mock('../api/client', () => ({ setupApi: vi.fn() }))
const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ hash: '#the-bootstrap-secret' }),
}))
const priv = vi.hoisted(() => ({ value: null as null | { kemSeed: Uint8Array; sign: Uint8Array } }))
vi.mock('../app/vault', async (orig) => ({
  ...(await orig<typeof import('../app/vault')>()),
  identityPrivate: () => priv.value,
}))

import TenantJoinView from './TenantJoinView.vue'
import { acceptInvite, acceptOwnership, bornTenantScope, previewInvite, tenantError } from '../features/tenant/tenantInvite'
import { login } from '../features/account/login/login'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { myUserId } from '../features/account/profile/profile'
import { vaultState } from '../app/vault'
import { setAccount } from '../app/account'
import { InviteRole } from '../api/generated/tenants'
import { realI18n, t } from '../test/i18n'
import { invitePreview } from '../test/models'
import * as v1 from '@veyq/crypto/v1'

const EMAIL = 'member@acme.example'
const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: {
    props: ['label', 'loading', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqPassword: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  SecretKeyField: { props: ['modelValue'], template: '<input class="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

async function mountView(handover: boolean) {
  vi.mocked(previewInvite).mockResolvedValue(
    invitePreview({ tenantName: 'Familie Muster', ownerEmail: EMAIL, hasAccount: true, role: InviteRole.Owner, handover }),
  )
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}
const buttonLabelled = (w: Awaited<ReturnType<typeof mountView>>, label: string) => w.findAll('button').find((b) => b.text() === label)!

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(acceptOwnership).mockResolvedValue(undefined)
  vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
  // Signed in and unlocked as the invited address: the one-click path.
  setAccount(EMAIL)
  vaultState.unlocked = true
  priv.value = null
})

describe('TenantJoinView — handover (ADR 0101 decision 9)', () => {
  it('signed in as the invited member: one click makes them an owner — no username, no birth — and opens the space', async () => {
    const w = await mountView(true)
    expect(w.find('input[name="username"]').exists()).toBe(false)
    expect(w.find('.stub-picker').exists()).toBe(false)
    expect(w.text()).toContain(t('tenant.join.invitedToHandover', { tenant: 'Familie Muster', domain: 'veyq.localhost' }))
    await buttonLabelled(w, t('tenant.join.joinHandover')).trigger('click')
    await flushPromises()
    expect(acceptOwnership).toHaveBeenCalledWith('the-bootstrap-secret')
    expect(acceptInvite).not.toHaveBeenCalled()
    expect(bornTenantScope).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('tenant.join.doneHandover', { tenant: 'Familie Muster' }))
    await buttonLabelled(w, t('tenant.join.openSpace')).trigger('click')
    expect(push).toHaveBeenCalledWith('/')
  })

  it('signed out: signs in and unlocks, then takes ownership — still no birth', async () => {
    vaultState.unlocked = false
    const w = await mountView(true)
    await w.find('input[type="password"]').setValue('the password')
    await w.find('input.secret-key').setValue('A3-SECRET')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(login).toHaveBeenCalledWith(EMAIL, 'the password')
    expect(unlockMasterKey).toHaveBeenCalled()
    expect(acceptOwnership).toHaveBeenCalledWith('the-bootstrap-secret')
    expect(acceptInvite).not.toHaveBeenCalled()
  })

  it('a member no longer in the space is told so', async () => {
    vi.mocked(acceptOwnership).mockRejectedValue(new Error('409'))
    vi.mocked(tenantError).mockResolvedValue({ status: 409, code: 'not_tenant_member' })
    const w = await mountView(true)
    await buttonLabelled(w, t('tenant.join.joinHandover')).trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('tenant.join.notMemberHandover'))
  })

  it('control: an owner invite that is no handover keeps the username and the birth accept', async () => {
    priv.value = { kemSeed: v1.randomBytes(32), sign: v1.randomBytes(32) }
    vi.mocked(myUserId).mockResolvedValue('5c09e000-0000-4000-8000-0000000000e2')
    vi.mocked(bornTenantScope).mockReturnValue({
      birth: { grant: 'Zw==', grantSignature: 'Uw==', recipientIdentityDigest: 'RA==' },
      scopeRootKey: new Uint8Array(32),
    })
    const w = await mountView(false)
    expect(w.find('input[name="username"]').exists()).toBe(true)
    await w.find('input[name="username"]').setValue('kevin')
    await buttonLabelled(w, t('tenant.join.join')).trigger('click')
    await flushPromises()
    expect(acceptOwnership).not.toHaveBeenCalled()
    expect(bornTenantScope).toHaveBeenCalled()
    expect(acceptInvite).toHaveBeenCalledWith('the-bootstrap-secret', 'kevin', expect.objectContaining({ grant: 'Zw==' }), undefined, undefined)
  })
})
