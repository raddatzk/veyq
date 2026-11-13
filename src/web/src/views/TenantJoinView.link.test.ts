// @vitest-environment happy-dom
// A LINK invite at the accept page (ADR 0129 decisions 1, 2, 4 and 6): the fragment is `#<token>.<sealed>.<code>`, so the
// page opens and checks the invite with the code the link carries and asks for none; bound to nobody, the preview names
// no address, so the newcomer types one — sent with the register, never for a mailed invite — or signs in with any
// account; a new account asks for its confirmation mail once, in a session of its own, and a failure there leaves the
// join done. A consumed invite's preview (`410 invite_redeemed`) has its own message. The invite opens with the real
// crypto (`memberInviteFixture`).
import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const route = vi.hoisted(() => ({ hash: '' }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => route }))
vi.mock('../api/client', () => ({ tenantsApi: vi.fn(), setupApi: vi.fn() }))
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  const { TEST_ARGON } = await vi.importActual<typeof import('@veyq/crypto/testArgon')>('@veyq/crypto/testArgon')
  return { inviteLinkKekAsync: async (secret: Uint8Array, salt: Uint8Array) => real.kekInviteLink(secret, salt, TEST_ARGON) }
})
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../features/account/profile/profile', () => ({ sealName: vi.fn(), sealAvatar: vi.fn(), myUserId: vi.fn() }))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../features/account/email/emailConfirmation', () => ({ requestEmailConfirmation: vi.fn() }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))
vi.mock('../app/vault', async (o) => ({ ...(await o<typeof import('../app/vault')>()), identityPrivate: vi.fn(), masterKey: vi.fn() }))
vi.mock('../features/account/identityPins', async (o) => ({
  linkOf: (await o<typeof import('../features/account/identityPins')>()).linkOf,
  anchorServedChain: vi.fn(), anchorChainKey: vi.fn(), getPin: vi.fn(),
}))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn(async () => 0) }))
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
vi.mock('../features/account/profile/JoinPicturePicker.vue', () => ({
  default: {
    props: ['modelValue', 'name'],
    emits: ['update:modelValue'],
    setup: (_: unknown, { emit }: { emit: (e: string, v: Uint8Array) => void }) => ({ pick: () => emit('update:modelValue', new Uint8Array([9, 9, 9])) }),
    template: '<button type="button" class="stub-picker" @click="pick">picker</button>',
  },
}))

import TenantJoinView from './TenantJoinView.vue'
import { tenantsApi, setupApi } from '../api/client'
import { fakeApi, stubApi } from '../test/apiMock'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { myUserId } from '../features/account/profile/profile'
import { identityPrivate, masterKey } from '../app/vault'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { anchorChainKey, anchorServedChain, getPin } from '../features/account/identityPins'
import { login } from '../features/account/login/login'
import { requestEmailConfirmation } from '../features/account/email/emailConfirmation'
import { InviteRole, TenantStatus, type TenantsApi } from '../api/generated/tenants'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'
import { memberInviteFixture, type MemberInviteFixture } from '../test/memberInvite'
import { httpError } from '../test/httpError'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std } from '@veyq/crypto/base64'
import { formatSecretKey } from '@veyq/crypto/secretKey'

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: {
    props: ['label', 'loading', 'disabled', 'type'], emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue', 'name'], template: '<input :name="name" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqPassword: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqCheckbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
  SecretKeyField: { props: ['modelValue'], template: '<input class="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

const NEWCOMER = v1.generateIdentity()
const SECRET_KEY = v1.randomBytes(32)
const PROOF_PUB = v1.deriveSecretKeyProofKeypair(SECRET_KEY).publicKey
const BORN_MK = v1.randomBytes(32)

let f: MemberInviteFixture
let api: {
  previewTenantInvite: Mock<TenantsApi['previewTenantInvite']>
  acceptTenantInvite: Mock<TenantsApi['acceptTenantInvite']>
  registerAndAcceptTenantInvite: Mock<TenantsApi['registerAndAcceptTenantInvite']>
}

/** Serve the fixture's invite as a link invite — no address, no `hasAccount` — or, with [ownerEmail], as a mailed one. */
function serve(ownerEmail?: string) {
  const preview = invitePreview({
    tenantId: f.tenantId, tenantName: 'Acme', role: InviteRole.Member, invite: f.served,
    ownerEmail, hasAccount: ownerEmail === undefined ? undefined : false,
  })
  api = {
    previewTenantInvite: vi.fn<TenantsApi['previewTenantInvite']>().mockResolvedValue(preview),
    acceptTenantInvite: vi.fn<TenantsApi['acceptTenantInvite']>().mockResolvedValue({ tenantId: f.tenantId, status: TenantStatus.Active }),
    registerAndAcceptTenantInvite: vi.fn<TenantsApi['registerAndAcceptTenantInvite']>().mockResolvedValue({ tenantId: f.tenantId, status: TenantStatus.Active, userId: 'u' }),
  }
  fakeApi(tenantsApi, api)
}

/** The link the owner handed over: token, sealed part and the code. */
const linkHash = () => `#tok.${f.sealedPart}.${f.code}`

async function mountAt(hash = linkHash()): Promise<VueWrapper> {
  route.hash = hash
  window.history.replaceState(null, '', `/tenant/accept${hash}`)
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

const nothingPosted = () => {
  expect(api.acceptTenantInvite).not.toHaveBeenCalled()
  expect(api.registerAndAcceptTenantInvite).not.toHaveBeenCalled()
}

beforeEach(() => {
  vi.clearAllMocks()
  f = memberInviteFixture({ epoch: 1 })
  serve()
  stubApi(setupApi, { getSetupStatus: setupStatus() })
  vi.mocked(buildAccount).mockImplementation(async () => ({
    identity: NEWCOMER,
    masterKey: BORN_MK.slice(),
    material: { secretKeyProofPublicKey: b64std(PROOF_PUB) },
    emergencyKit: { secretKey: formatSecretKey(SECRET_KEY) },
  }) as unknown as Awaited<ReturnType<typeof buildAccount>>)
  vi.mocked(anchorServedChain).mockResolvedValue('firstUse')
  vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
  vi.mocked(getPin).mockResolvedValue(null)
  vi.mocked(masterKey).mockReturnValue(null)
  vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
  vi.mocked(login).mockResolvedValue(undefined)
  vi.mocked(requestEmailConfirmation).mockResolvedValue(undefined)
  globalThis.indexedDB = new IDBFactory()
})

/** Fill the register form (the address typed as [email]) and go through the kit to the accept. */
async function register(w: VueWrapper, email = 'Bea@Example.org') {
  await w.find('input[name="username"]').setValue('bea')
  await w.find('input[name="email"]').setValue(email)
  await w.find('input[type="password"]').setValue('a long enough password')
  await w.find('form').trigger('submit')
  await flushPromises()
}
async function acceptFromKit(w: VueWrapper) {
  await w.findAll('button').find((b) => b.text() === t('tenant.join.joinMember'))!.trigger('click')
  await flushPromises()
}

describe('TenantJoinView — a link carries its code', () => {
  it('asks for no code: the invite opens with the link\'s own, the token alone goes to the preview, and the fragment is stripped', async () => {
    const w = await mountAt()
    expect(api.previewTenantInvite).toHaveBeenCalledWith({ acceptPreviewRequest: { bootstrapSecret: 'tok' } })
    expect(w.find('input[name="invite-code"]').exists()).toBe(false)
    expect(w.find('input[name="username"]').exists()).toBe(true) // straight to the account step
    expect(window.location.hash).toBe('')
    nothingPosted()
  })

  it('control: a mailed member link (no code) still asks for it', async () => {
    serve('bea@example.org')
    const w = await mountAt(`#tok.${f.sealedPart}`)
    expect(w.find('input[name="invite-code"]').exists()).toBe(true)
    expect(w.find('input[name="username"]').exists()).toBe(false)
  })

  it('a code in the link that does not open the invite falls back to the code step, which says so', async () => {
    let other = invite.generateInviteCode()
    while (other === f.code) other = invite.generateInviteCode()
    const w = await mountAt(`#tok.${f.sealedPart}.${other}`)
    expect(w.find('input[name="invite-code"]').exists()).toBe(true)
    expect(w.text()).toContain(t('tenant.join.codeWrong'))
    nothingPosted()
  })
})

describe('TenantJoinView — a link invite registers with the address its holder types', () => {
  it('asks for the address, registers with it, then signs in and asks for the confirmation mail once', async () => {
    const w = await mountAt()
    expect(w.text()).toContain(t('tenant.join.registerSubLink'))
    await register(w, ' bea@example.org ')
    expect(buildAccount).toHaveBeenCalledWith('bea@example.org', 'a long enough password', expect.anything())
    await acceptFromKit(w)
    await vi.waitFor(() => expect(w.text()).toContain(t('tenant.join.doneNoteMember')))
    const body = api.registerAndAcceptTenantInvite.mock.calls[0][0].registerAndAcceptRequest
    expect(body.email).toBe('bea@example.org')
    expect(login).toHaveBeenCalledWith('bea@example.org', 'a long enough password')
    expect(requestEmailConfirmation).toHaveBeenCalledOnce()
    expect(vi.mocked(login).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(requestEmailConfirmation).mock.invocationCallOrder[0])
    expect(w.text()).toContain(t('tenant.join.confirmationSent', { email: 'bea@example.org' }))
  })

  it('a confirmation request that fails is not an error of the join: done, without the "sent" line', async () => {
    vi.mocked(requestEmailConfirmation).mockRejectedValue(new Error('503'))
    const w = await mountAt()
    await register(w, 'bea@example.org')
    await acceptFromKit(w)
    await vi.waitFor(() => expect(w.text()).toContain(t('tenant.join.doneNoteMember')))
    expect(w.text()).not.toContain(t('tenant.join.confirmationSent', { email: 'bea@example.org' }))
    expect(w.text()).not.toContain(t('tenant.join.failed'))
  })

  it('refuses an address that is none before anything is built or sent', async () => {
    const w = await mountAt()
    await register(w, 'not an address')
    expect(w.text()).toContain(t('tenant.join.emailInvalid'))
    expect(buildAccount).not.toHaveBeenCalled()
    nothingPosted()
  })

  it('an address that has an account points at signing in, which the page offers beside it', async () => {
    api.registerAndAcceptTenantInvite.mockRejectedValue(httpError(409, 'email_taken'))
    const w = await mountAt()
    await register(w, 'bea@example.org')
    await acceptFromKit(w)
    expect(w.text()).toContain(t('tenant.join.emailTaken'))
    expect(requestEmailConfirmation).not.toHaveBeenCalled()
  })

  it('control: a mailed invite registers with its own address and sends none, and asks for no confirmation', async () => {
    serve('bea@example.org')
    const w = await mountAt(`#tok.${f.sealedPart}.${f.code}`)
    expect(w.find('input[name="email"]').exists()).toBe(false)
    await w.find('input[name="username"]').setValue('bea')
    await w.find('input[type="password"]').setValue('a long enough password')
    await w.find('form').trigger('submit')
    await flushPromises()
    await acceptFromKit(w)
    await vi.waitFor(() => expect(w.text()).toContain(t('tenant.join.doneNoteMember')))
    const body = api.registerAndAcceptTenantInvite.mock.calls[0][0].registerAndAcceptRequest
    expect('email' in body).toBe(false)
    expect(buildAccount).toHaveBeenCalledWith('bea@example.org', expect.anything(), expect.anything())
    expect(requestEmailConfirmation).not.toHaveBeenCalled()
  })
})

describe('TenantJoinView — a link invite signs in with any account', () => {
  const ME = '5c09e000-0000-4000-8000-0000000000c3'
  beforeEach(() => {
    vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: NEWCOMER.priv.kemSeed.slice(), sign: NEWCOMER.priv.sign.slice() }))
    vi.mocked(myUserId).mockResolvedValue(ME)
  })

  async function signIn(w: VueWrapper) {
    await w.get('[data-testid="join-have-account"]').trigger('click')
    expect(w.text()).toContain(t('tenant.join.signinSubLink'))
    await w.find('input[name="username"]').setValue('bea')
    await w.find('input[name="email"]').setValue('old@example.org')
    await w.find('input[type="password"]').setValue('pw')
    await w.find('input.secret-key').setValue(formatSecretKey(SECRET_KEY))
    await w.find('form').trigger('submit')
    await flushPromises()
  }

  it('signs in as the address typed and accepts — no confirmation asked, the account keeps what it had', async () => {
    const w = await mountAt()
    await signIn(w)
    expect(login).toHaveBeenCalledWith('old@example.org', 'pw')
    expect(api.acceptTenantInvite).toHaveBeenCalledOnce()
    expect(requestEmailConfirmation).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('an account already in the space is told so', async () => {
    api.acceptTenantInvite.mockRejectedValue(httpError(409, 'already_member'))
    const w = await mountAt()
    await signIn(w)
    expect(w.text()).toContain(t('tenant.join.alreadyMember'))
  })

  it('switches back to a new account', async () => {
    const w = await mountAt()
    await w.get('[data-testid="join-have-account"]').trigger('click')
    await w.get('[data-testid="join-new-account"]').trigger('click')
    expect(w.text()).toContain(t('tenant.join.registerSubLink'))
  })
})

describe('TenantJoinView — a link someone already used (ADR 0129 decision 6)', () => {
  it('says so — tell whoever invited you — distinct from an expired invite', async () => {
    api.previewTenantInvite.mockRejectedValue(httpError(410, 'invite_redeemed'))
    const w = await mountAt()
    expect(w.text()).toContain(t('tenant.join.redeemed'))
    expect(w.text()).not.toContain(t('tenant.join.expired'))
  })

  it('control: a revoked or expired one is "no longer valid"', async () => {
    api.previewTenantInvite.mockRejectedValue(httpError(410, 'invite_no_longer_valid'))
    const w = await mountAt()
    expect(w.text()).toContain(t('tenant.join.expired'))
    expect(w.text()).not.toContain(t('tenant.join.redeemed'))
  })

  it('an accept the link was taken from under says the same', async () => {
    api.registerAndAcceptTenantInvite.mockRejectedValue(httpError(410, 'invite_redeemed'))
    const w = await mountAt()
    await register(w, 'bea@example.org')
    await acceptFromKit(w)
    expect(w.text()).toContain(t('tenant.join.redeemed'))
  })
})
