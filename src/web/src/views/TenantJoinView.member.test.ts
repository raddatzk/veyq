// @vitest-environment happy-dom
// A MEMBER invite at the accept page (ADR 0111 decisions 1 and 3): the link's
// fragment is `#<token>.<sealed>`, so the view asks for the code the owner passed on, opens and CHECKS the invite with
// the real `tenantInvite` functions before anything is sent, and the accept carries the newcomer's self-grant, the
// invite key's proof and its join record — opened here with the real crypto. A wrong code is the invitee's to retype; an
// invite that fails a check is "ask for a new one"; in neither case is anything posted.
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
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
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))
vi.mock('../app/vault', async (o) => ({ ...(await o<typeof import('../app/vault')>()), identityPrivate: vi.fn() }))
vi.mock('../features/account/identityPins', () => ({ anchorIdentity: vi.fn(), anchorChainKey: vi.fn(), getPin: vi.fn() }))
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
import { sealAvatar, sealName, myUserId } from '../features/account/profile/profile'
import { identityPrivate } from '../app/vault'
import { anchorChainKey, anchorIdentity, getPin } from '../features/account/identityPins'
import { InviteRole, TenantStatus, type ServedMemberInvite, type TenantsApi } from '../api/generated/tenants'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'
import { bundleOf, inviteGrant, memberInviteFixture, signedInviteKey, type MemberInviteFixture } from '../test/memberInvite'
import { grantContext, grantSignatureVerifies } from '../features/spaces/grant'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: { props: ['label', 'loading', 'disabled', 'type'], template: '<button :type="type ?? \'button\'" :disabled="disabled">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqInput: { props: ['modelValue', 'name'], template: '<input :name="name" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqPassword: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqCheckbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
  SecretKeyField: { props: ['modelValue'], template: '<input class="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

const NEWCOMER = v1.generateIdentity()
const SECRET_KEY = v1.randomBytes(32)
const PROOF_PUB = v1.deriveSecretKeyProofKeypair(SECRET_KEY).publicKey

let f: MemberInviteFixture
let api: {
  previewTenantInvite: Mock<TenantsApi['previewTenantInvite']>
  acceptTenantInvite: Mock<TenantsApi['acceptTenantInvite']>
  registerAndAcceptTenantInvite: Mock<TenantsApi['registerAndAcceptTenantInvite']>
}

/** Serve [served] for the invite the fixture's link names, to an invitee who has an account or not. */
function serve(served: ServedMemberInvite, hasAccount = false) {
  api = {
    previewTenantInvite: vi.fn<TenantsApi['previewTenantInvite']>().mockResolvedValue(
      invitePreview({ tenantId: f.tenantId, tenantName: 'Acme', role: InviteRole.Member, hasAccount, invite: served }),
    ),
    acceptTenantInvite: vi.fn<TenantsApi['acceptTenantInvite']>().mockResolvedValue({ tenantId: f.tenantId, status: TenantStatus.Active }),
    registerAndAcceptTenantInvite: vi.fn<TenantsApi['registerAndAcceptTenantInvite']>().mockResolvedValue({ tenantId: f.tenantId, status: TenantStatus.Active, userId: 'u' }),
  }
  fakeApi(tenantsApi, api)
}

async function mountAt(hash = `#tok.${f.sealedPart}`): Promise<VueWrapper> {
  route.hash = hash
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

async function typeCode(w: VueWrapper, code: string) {
  await w.find('input[name="invite-code"]').setValue(code)
  await w.find('form').trigger('submit')
  await flushPromises()
}

const nothingPosted = () => {
  expect(api.acceptTenantInvite).not.toHaveBeenCalled()
  expect(api.registerAndAcceptTenantInvite).not.toHaveBeenCalled()
}

beforeEach(() => {
  vi.clearAllMocks()
  f = memberInviteFixture({ epoch: 1 })
  serve(f.served)
  stubApi(setupApi, { getSetupStatus: setupStatus() })
  vi.mocked(buildAccount).mockResolvedValue({
    identity: NEWCOMER,
    material: { secretKeyProofPublicKey: b64std(PROOF_PUB) },
    emergencyKit: { secretKey: b64std(SECRET_KEY) },
  } as unknown as Awaited<ReturnType<typeof buildAccount>>)
  vi.mocked(sealName).mockReturnValue('SEALED-NAME')
  vi.mocked(sealAvatar).mockReturnValue('SEALED-AVATAR')
  vi.mocked(anchorIdentity).mockResolvedValue('firstUse')
  vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
  vi.mocked(getPin).mockResolvedValue(null)
})

describe('TenantJoinView — a member invite asks for the code first', () => {
  it('shows the code step for a member invite, with the token alone sent to the preview', async () => {
    const w = await mountAt()
    expect(api.previewTenantInvite).toHaveBeenCalledWith({ acceptPreviewRequest: { bootstrapSecret: 'tok' } })
    expect(w.find('input[name="invite-code"]').exists()).toBe(true)
    expect(w.text()).toContain(t('tenant.join.codeSub'))
    expect(w.find('input[name="username"]').exists()).toBe(false) // nothing else until the invite is checked
  })

  it('a member link without its sealed part cannot be checked: invalid, and no code is asked for', async () => {
    const w = await mountAt('#tok')
    expect(w.text()).toContain(t('tenant.join.invalid'))
    expect(w.find('input[name="invite-code"]').exists()).toBe(false)
  })

  it('a wrong code says so and stays on the code step — nothing is posted', async () => {
    const w = await mountAt()
    let other = invite.generateInviteCode()
    while (other === f.code) other = invite.generateInviteCode()
    await typeCode(w, other)
    expect(w.text()).toContain(t('tenant.join.codeWrong'))
    expect(w.text()).not.toContain(t('tenant.join.inviteUncheckable'))
    expect(w.find('input[name="invite-code"]').exists()).toBe(true)
    nothingPosted()
  })

  it('an invite that fails a check is "ask for a new one" — nothing is posted', async () => {
    // The operator's own key served as the owner's, signing everything: only the fingerprint in the seal refuses it.
    const operator = v1.generateIdentity()
    serve({
      ...f.served,
      signerIdentityPublicKey: bundleOf(operator),
      inviteKeySignature: signedInviteKey(operator, { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, f.inviteKey),
      ...inviteGrant(f.tenantId, 1, f.tenantKey, f.served.inviteId, f.inviteKey, operator),
      granterIdentityPublicKey: bundleOf(operator),
    })
    const w = await mountAt()
    await typeCode(w, f.code)
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
    expect(w.text()).not.toContain(t('tenant.join.codeWrong'))
    expect(w.find('input[name="username"]').exists()).toBe(false)
    nothingPosted()
  })

  it('control: the right code opens the genuine invite and moves on to the register form', async () => {
    const w = await mountAt()
    await typeCode(w, invite.formatInviteCode(f.code))
    expect(w.find('input[name="username"]').exists()).toBe(true)
    expect(w.text()).not.toContain(t('tenant.join.inviteUncheckable'))
  })
})

describe('TenantJoinView — the newcomer joins holding the tenant key', () => {
  async function registerWith(picture: boolean) {
    const w = await mountAt()
    await typeCode(w, f.code)
    await w.find('input[name="username"]').setValue('bea')
    await w.find('input[name="given-name"]').setValue('Bea')
    await w.find('input[type="password"]').setValue('a long enough password')
    const pickerOffered = w.find('.stub-picker').exists()
    if (picture) await w.find('.stub-picker').trigger('click')
    await w.find('form').trigger('submit')
    await flushPromises()
    await w.findAll('button').find((b) => b.text() !== 'picker')!.trigger('click')
    await flushPromises()
    return { w, pickerOffered }
  }

  it('registers with a self-grant, an invite proof and a join record that all verify — and the done step', async () => {
    const { w } = await registerWith(false)
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledTimes(1)
    const body = api.registerAndAcceptTenantInvite.mock.calls[0][0].registerAndAcceptRequest
    const userId: string = body.userId
    // No cold birth for a member: the tenant scope exists.
    expect(body.grant).toBeUndefined()
    // The self-grant opens under the NEW identity to the tenant key the invite carried, signed by that identity.
    const key = v1.openAs(grantContext(f.tenantId, 1), v1.hybridSecret(NEWCOMER.priv), fromB64std(body.selfGrant!.wrappedGrant))
    expect(Array.from(key)).toEqual(Array.from(f.tenantKey))
    const to = { userId, identityPublicKeyB64: bundleOf(NEWCOMER) }
    expect(grantSignatureVerifies(NEWCOMER.pub.sign, { scopeId: f.tenantId, kind: 'TENANT', epoch: 1 }, to, body.selfGrant!.wrappedGrant, body.selfGrant!.grantSignature)).toBe(true)
    // The invite key's proof over (invite, account, identity).
    const stmt = invite.inviteAcceptStatement({ inviteId: f.served.inviteId, userId, identityPublicKey: v1.serializeIdentityPublic(NEWCOMER.pub) })
    expect(v1.identityVerify(f.inviteKey.pub.sign, stmt, fromB64std(body.inviteProof!))).toBe(true)
    // The join record: under the tenant key, bound to this account, both signatures verify, naming the registered proof key.
    const record = invite.openJoinRecord(f.tenantKey, { tenantId: f.tenantId, userId }, fromB64std(body.sealedJoinRecord!))
    expect(invite.joinRecordVerifies({ tenantId: f.tenantId, userId }, record)).toBe(true)
    expect(Array.from(record.secretKeyProofPublicKey)).toEqual(Array.from(PROOF_PUB))
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('offers the name and the picture, sealed under the invite\'s tenant key at (tenant, the new account)', async () => {
    // The key's bytes as the seal saw them: the view wipes the opened invite once the accept went through.
    const keysSeen: number[][] = []
    vi.mocked(sealName).mockImplementation((k) => (keysSeen.push(Array.from(k)), 'SEALED-NAME'))
    vi.mocked(sealAvatar).mockImplementation((k) => (keysSeen.push(Array.from(k)), 'SEALED-AVATAR'))
    const { pickerOffered } = await registerWith(true)
    expect(pickerOffered).toBe(true)
    const body = api.registerAndAcceptTenantInvite.mock.calls[0][0].registerAndAcceptRequest
    const [nameKey, nameAddress] = vi.mocked(sealName).mock.calls[0]
    const [, avatarAddress, bytes] = vi.mocked(sealAvatar).mock.calls[0]
    expect(keysSeen).toEqual([Array.from(f.tenantKey), Array.from(f.tenantKey)])
    expect(nameKey.every((b) => b === 0)).toBe(true) // …and wiped after the accept (forgetMemberInvite)
    for (const a of [nameAddress, avatarAddress]) expect(a).toEqual({ scopeId: f.tenantId, userId: body.userId })
    expect([...bytes]).toEqual([9, 9, 9])
    expect({ sealedName: body.sealedName, sealedAvatar: body.sealedAvatar }).toEqual({ sealedName: 'SEALED-NAME', sealedAvatar: 'SEALED-AVATAR' })
  })
})

describe('TenantJoinView — a member invite to an existing account', () => {
  const ME = '5c09e000-0000-4000-8000-0000000000c3'
  beforeEach(() => {
    serve(f.served, true)
    vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: NEWCOMER.priv.kemSeed.slice(), sign: NEWCOMER.priv.sign.slice() }))
    vi.mocked(myUserId).mockResolvedValue(ME)
  })

  async function signIn(w: VueWrapper) {
    await typeCode(w, f.code)
    await w.find('input[name="username"]').setValue('bea')
    await w.find('input[type="password"]').setValue('pw')
    await w.find('input.secret-key').setValue(b64std(SECRET_KEY))
    await w.find('form').trigger('submit')
    await flushPromises()
  }

  it('signs in and accepts with a join whose record names the proof key the typed Secret Key derives', async () => {
    const w = await mountAt()
    await signIn(w)
    // The inviting owner is pinned at the head the invite serves (the link vouched for the key; no chain is served yet).
    expect(anchorIdentity).toHaveBeenCalledWith(
      f.ownerId, { identityPublicKey: f.served.signerIdentityPublicKey, keyChainHead: f.served.signerKeyChainHead }, { firstUse: 'served' },
    )
    const body = api.acceptTenantInvite.mock.calls[0][0].acceptInviteRequest
    const record = invite.openJoinRecord(f.tenantKey, { tenantId: f.tenantId, userId: ME }, fromB64std(body.sealedJoinRecord!))
    expect(invite.joinRecordVerifies({ tenantId: f.tenantId, userId: ME }, record)).toBe(true)
    expect(Array.from(record.secretKeyProofPublicKey)).toEqual(Array.from(PROOF_PUB))
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('refuses when this account knows the inviting owner under a key their chain does not reach — nothing is posted', async () => {
    vi.mocked(anchorIdentity).mockResolvedValue('anomaly')
    const w = await mountAt()
    await signIn(w)
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
    nothingPosted()
  })

  /** The invite's grant as a rotation by ROTATOR re-wrapped it (a foreign granter, inbox #565). */
  const ROTATOR = '5c09e000-0000-4000-8000-0000000000d4'
  function rewrappedBy(rotator: v1.Identity) {
    serve({
      ...f.served,
      ...inviteGrant(f.tenantId, 1, f.tenantKey, f.served.inviteId, f.inviteKey, rotator),
      granterUserId: ROTATOR, granterIdentityPublicKey: bundleOf(rotator), granterKeyChainHead: f.served.signerKeyChainHead,
    }, true)
  }

  it('a foreign granter this account never met is trusted on first use and NOT pinned here (#565)', async () => {
    rewrappedBy(v1.generateIdentity())
    const w = await mountAt()
    await signIn(w)
    expect(getPin).toHaveBeenCalledWith(ROTATOR)
    expect(anchorChainKey).not.toHaveBeenCalled()
    expect(anchorIdentity).not.toHaveBeenCalledWith(ROTATOR, expect.anything(), expect.anything())
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['an anomaly', { anchor: 'anomaly', retired: false }],
    ['a retired key of their chain', { anchor: 'match', retired: true }],
  ] as const)('a foreign granter this account pinned, whose grant key is %s, is refused — nothing is posted', async (_l, judged) => {
    const rotator = v1.generateIdentity()
    rewrappedBy(rotator)
    vi.mocked(getPin).mockResolvedValue({ fingerprint: 'AA==', head: 'AA==', index: 0, firstSeenAt: 1, replaced: false })
    vi.mocked(anchorChainKey).mockResolvedValue(judged)
    const w = await mountAt()
    await signIn(w)
    expect(anchorChainKey).toHaveBeenCalledWith(ROTATOR, bundleOf(rotator))
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
    nothingPosted()
  })

  it('control: a foreign granter this account pinned, whose key the chain reaches, is accepted', async () => {
    rewrappedBy(v1.generateIdentity())
    vi.mocked(getPin).mockResolvedValue({ fingerprint: 'AA==', head: 'AA==', index: 0, firstSeenAt: 1, replaced: false })
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'advanced', retired: false })
    const w = await mountAt()
    await signIn(w)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(1)
  })
})
