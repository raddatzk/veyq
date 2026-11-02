// @vitest-environment happy-dom
// A MEMBER invite at the accept page (ADR 0111 decisions 1 and 3): the link's
// fragment is `#<token>.<sealed>`, so the view asks for the code the owner passed on, opens and CHECKS the invite with
// the real `tenantInvite` functions before anything is sent, and the accept carries the newcomer's self-grant, the
// invite key's proof and its join record — opened here with the real crypto. A wrong code is the invitee's to retype; an
// invite that fails a check is "ask for a new one"; in neither case is anything posted.
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
import { sealAvatar, sealName, myUserId } from '../features/account/profile/profile'
import { identityPrivate, masterKey } from '../app/vault'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { epochKeyCommitment, heldEpochKey } from '../features/account/trustMarks'
import { anchorChainKey, anchorServedChain, getPin } from '../features/account/identityPins'
import { InviteRole, TenantStatus, type ServedMemberInvite, type TenantsApi } from '../api/generated/tenants'
import { realI18n, t } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'
import { bundleOf, carriedTo, foreignGranter, memberInviteFixture, ownerSecured, type MemberInviteFixture } from '../test/memberInvite'
import { chainKeys, genesis, headOf } from '../test/keyChain'
import { httpError } from '../test/httpError'
import { grantContext, grantSignatureVerifies, recipientIdentityDigest, sealGrant } from '../features/spaces/grant'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { openValue } from '../features/spaces/valueKey'

/** A join record as every member opens it (ADR 0115): its value key from the wrap under the tenant key at the epoch the
 *  accept states, then the record under that key. */
function recordOf(
  tenantKey: Uint8Array, a: { tenantId: string; userId: string },
  body: { sealedJoinRecord?: string | null; wrappedJoinRecordValueKey?: string | null; epoch?: number | null },
): invite.JoinRecord {
  return openValue({ epoch: body.epoch!, key: tenantKey }, invite.joinRecordContext(a), body.wrappedJoinRecordValueKey, (kv) =>
    invite.openJoinRecord(kv, a, fromB64std(body.sealedJoinRecord!)))
}

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
/** The new account's Master Key as its birth hands it back, and an existing account's as its unlock leaves it in the vault. */
const BORN_MK = v1.randomBytes(32)
const VAULT_MK = v1.randomBytes(32)

let f: MemberInviteFixture
let api: {
  previewTenantInvite: Mock<TenantsApi['previewTenantInvite']>
  acceptTenantInvite: Mock<TenantsApi['acceptTenantInvite']>
  registerAndAcceptTenantInvite: Mock<TenantsApi['registerAndAcceptTenantInvite']>
}

/** The preview of [served] for the invite the fixture's link names. */
const previewOf = (served: ServedMemberInvite, hasAccount: boolean) =>
  invitePreview({ tenantId: f.tenantId, tenantName: 'Acme', role: InviteRole.Member, hasAccount, invite: served })

/** Serve [served] for the invite the fixture's link names, to an invitee who has an account or not. */
function serve(served: ServedMemberInvite, hasAccount = false) {
  api = {
    previewTenantInvite: vi.fn<TenantsApi['previewTenantInvite']>().mockResolvedValue(previewOf(served, hasAccount)),
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
    masterKey: BORN_MK.slice(),
    material: { secretKeyProofPublicKey: b64std(PROOF_PUB) },
    emergencyKit: { secretKey: b64std(SECRET_KEY) },
  } as unknown as Awaited<ReturnType<typeof buildAccount>>)
  vi.mocked(sealName).mockReturnValue({ sealedName: 'SEALED-NAME', wrappedNameValueKey: 'WRAPPED-NAME-KEY' })
  vi.mocked(sealAvatar).mockReturnValue({ sealedAvatar: 'SEALED-AVATAR', wrappedAvatarValueKey: 'WRAPPED-AVATAR-KEY' })
  vi.mocked(anchorServedChain).mockResolvedValue('firstUse')
  vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
  vi.mocked(getPin).mockResolvedValue(null)
  vi.mocked(masterKey).mockReturnValue(null) // nothing unlocked until a sign-in does
  vi.mocked(unlockMasterKey).mockResolvedValue(undefined)
  globalThis.indexedDB = new IDBFactory() // a device that has held nothing yet
})

/** The tenant key this device holds once the vault holds [mk] (ADR 0111 decision 23). */
async function heldUnder(mk: Uint8Array) {
  vi.mocked(masterKey).mockImplementation(() => mk.slice())
  return heldEpochKey(f.tenantId)
}

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
    // The operator's own chain served for the owner's account: only the head inside the seal refuses it.
    const operator = genesis(f.ownerId, chainKeys())
    serve({ ...f.served, signerIdentityPublicKey: bundleOf(operator.keys[0].identity), signerKeyChain: operator.steps })
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
    // The accept holds the checked key in IndexedDB, whose requests settle on macrotasks: wait for the done step.
    await vi.waitFor(() => expect(w.text()).toContain(t('tenant.join.doneNoteMember')))
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
    // …through its own value key, whose wrap the tenant key holds — the tenant key alone opens no record (ADR 0115).
    expect(() => invite.openJoinRecord(f.tenantKey, { tenantId: f.tenantId, userId }, fromB64std(body.sealedJoinRecord!))).toThrow()
    const record = recordOf(f.tenantKey, { tenantId: f.tenantId, userId }, body)
    expect(invite.joinRecordVerifies({ tenantId: f.tenantId, userId }, record)).toBe(true)
    expect(Array.from(record.secretKeyProofPublicKey)).toEqual(Array.from(PROOF_PUB))
    // The owner key the record names, as the accept states it (ADR 0111 decision 20).
    expect(body.signerIdentityDigest).toBe(recipientIdentityDigest(bundleOf(f.owner)))
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
    // The checked key is held under the new account's Master Key - no vault held one at the accept - so the first open
    // after the sign-in takes no epoch that does not link down to it (inbox #740); the birth's copy is wiped.
    const born = (await vi.mocked(buildAccount).mock.results[0].value) as { masterKey: Uint8Array }
    expect(born.masterKey.every((b) => b === 0)).toBe(true)
    expect(await heldUnder(BORN_MK)).toEqual({ epoch: 1, commitment: epochKeyCommitment(f.tenantKey) })
  })

  it('409 invite_changed: the owner secured their account meanwhile — re-checked and registered once more, without the code', async () => {
    const secured = ownerSecured(f)
    api.registerAndAcceptTenantInvite.mockRejectedValueOnce(httpError(409, 'invite_changed'))
    api.previewTenantInvite.mockResolvedValueOnce(previewOf(f.served, false)).mockResolvedValueOnce(previewOf(secured.served, false))
    const { w } = await registerWith(false)
    expect(api.previewTenantInvite).toHaveBeenCalledTimes(2)
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledTimes(2)
    const [first, second] = api.registerAndAcceptTenantInvite.mock.calls.map((c) => c[0].registerAndAcceptRequest)
    expect(first.signerIdentityDigest).toBe(recipientIdentityDigest(bundleOf(f.owner)))
    expect(second.signerIdentityDigest).toBe(recipientIdentityDigest(bundleOf(secured.owner)))
    // The second record names the new key, under the same account id.
    expect(second.userId).toBe(first.userId)
    const record = recordOf(f.tenantKey, { tenantId: f.tenantId, userId: second.userId }, second)
    expect(bundleOf({ pub: v1.deserializeIdentityPublic(record.signerIdentityPublicKey) } as v1.Identity)).toBe(bundleOf(secured.owner))
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('offers the name and the picture, their value keys wrapped under the invite\'s tenant key at (tenant, the new account)', async () => {
    // The key's bytes as the seal saw them: the view wipes the opened invite once the accept went through.
    const keysSeen: number[][] = []
    vi.mocked(sealName).mockImplementation((k) => (keysSeen.push([k.epoch, ...k.key]), { sealedName: 'SEALED-NAME', wrappedNameValueKey: 'WRAPPED-NAME-KEY' }))
    vi.mocked(sealAvatar).mockImplementation((k) => (keysSeen.push([k.epoch, ...k.key]), { sealedAvatar: 'SEALED-AVATAR', wrappedAvatarValueKey: 'WRAPPED-AVATAR-KEY' }))
    const { pickerOffered } = await registerWith(true)
    expect(pickerOffered).toBe(true)
    const body = api.registerAndAcceptTenantInvite.mock.calls[0][0].registerAndAcceptRequest
    const [nameKey, nameAddress] = vi.mocked(sealName).mock.calls[0]
    const [, avatarAddress, bytes] = vi.mocked(sealAvatar).mock.calls[0]
    // the invite's tenant key at the epoch its grant carries (1): what each fresh value key is wrapped under
    expect(keysSeen).toEqual([[1, ...f.tenantKey], [1, ...f.tenantKey]])
    expect(nameKey.key.every((b) => b === 0)).toBe(true) // …and wiped after the accept (forgetMemberInvite)
    for (const a of [nameAddress, avatarAddress]) expect(a).toEqual({ scopeId: f.tenantId, userId: body.userId })
    expect([...bytes]).toEqual([9, 9, 9])
    expect({ sealedName: body.sealedName, wrappedNameValueKey: body.wrappedNameValueKey, sealedAvatar: body.sealedAvatar, wrappedAvatarValueKey: body.wrappedAvatarValueKey })
      .toEqual({ sealedName: 'SEALED-NAME', wrappedNameValueKey: 'WRAPPED-NAME-KEY', sealedAvatar: 'SEALED-AVATAR', wrappedAvatarValueKey: 'WRAPPED-AVATAR-KEY' })
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
    // The inviting owner is judged by the chain the invite served, walked from the link's head (ADR 0111 decision 19).
    expect(anchorServedChain).toHaveBeenCalledWith(
      f.ownerId,
      { userId: f.ownerId, head: headOf(f.ownerChain), steps: f.served.signerKeyChain },
      { identityPublicKey: bundleOf(f.owner), keyChainHead: headOf(f.ownerChain) },
    )
    const body = api.acceptTenantInvite.mock.calls[0][0].acceptInviteRequest
    const record = recordOf(f.tenantKey, { tenantId: f.tenantId, userId: ME }, body)
    expect(invite.joinRecordVerifies({ tenantId: f.tenantId, userId: ME }, record)).toBe(true)
    expect(Array.from(record.secretKeyProofPublicKey)).toEqual(Array.from(PROOF_PUB))
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('holds the checked tenant key under the vault the sign-in unlocked (inbox #740) - and nothing for a refused accept', async () => {
    vi.mocked(unlockMasterKey).mockImplementation(async () => {
      vi.mocked(masterKey).mockImplementation(() => VAULT_MK.slice())
    })
    await signIn(await mountAt())
    expect(await heldUnder(VAULT_MK)).toEqual({ epoch: 1, commitment: epochKeyCommitment(f.tenantKey) })

    globalThis.indexedDB = new IDBFactory()
    api.acceptTenantInvite.mockRejectedValueOnce(httpError(410, 'invite_expired'))
    await signIn(await mountAt())
    expect(await heldUnder(VAULT_MK)).toBeNull()
  })

  it('409 invite_changed: re-read, re-checked, judged against the pins again, and accepted once more', async () => {
    const secured = ownerSecured(f)
    api.acceptTenantInvite.mockRejectedValueOnce(httpError(409, 'invite_changed'))
    api.previewTenantInvite.mockResolvedValueOnce(previewOf(f.served, true)).mockResolvedValueOnce(previewOf(secured.served, true))
    const w = await mountAt()
    await signIn(w)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(2)
    expect(api.acceptTenantInvite.mock.calls[1][0].acceptInviteRequest.signerIdentityDigest).toBe(recipientIdentityDigest(bundleOf(secured.owner)))
    expect(anchorServedChain).toHaveBeenLastCalledWith(
      f.ownerId,
      { userId: f.ownerId, head: headOf(secured.ownerChain), steps: secured.served.signerKeyChain },
      { identityPublicKey: bundleOf(secured.owner), keyChainHead: headOf(secured.ownerChain) },
    )
    expect(w.text()).toContain(t('tenant.join.doneNoteMember'))
  })

  it('409 invite_changed twice: once is all — "failed", and no third accept', async () => {
    api.acceptTenantInvite.mockRejectedValue(httpError(409, 'invite_changed'))
    api.previewTenantInvite.mockResolvedValue(previewOf(ownerSecured(f).served, true))
    const w = await mountAt()
    await signIn(w)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(2)
    expect(w.text()).toContain(t('tenant.join.failed'))
  })

  it('a re-read invite that no longer checks is "ask for a new one", and is not accepted again', async () => {
    api.acceptTenantInvite.mockRejectedValueOnce(httpError(409, 'invite_changed'))
    // The re-read serves an anchor grant the owner's key did not sign.
    const g = sealGrant(v1.randomBytes(32), { scopeId: f.tenantId, kind: 'TENANT', epoch: 1 }, { userId: f.served.inviteId, identityPublicKeyB64: f.served.invitePublicKey }, v1.generateIdentity().priv.sign)
    api.previewTenantInvite.mockResolvedValueOnce(previewOf(f.served, true)).mockResolvedValueOnce(previewOf({ ...f.served, anchorGrantSignature: g.grantSignature }, true))
    const w = await mountAt()
    await signIn(w)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
  })

  it('refuses when this account knows the inviting owner under a key their chain does not reach — nothing is posted', async () => {
    vi.mocked(anchorServedChain).mockResolvedValue('anomaly')
    const w = await mountAt()
    await signIn(w)
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
    nothingPosted()
  })

  /** The invite as a rotation by ROTATOR carried it to epoch 2 (a foreign granter): its link peels to the anchor. */
  const ROTATOR = '5c09e000-0000-4000-8000-0000000000d4'
  function rewrappedBy(rotator: ReturnType<typeof foreignGranter>) {
    f = carriedTo(f, 2, rotator)
    serve(f.served, true)
  }

  it('a foreign granter this account never met is NOT pinned here — the epoch link vouches for the grant (ADR 0111 decision 19)', async () => {
    rewrappedBy(foreignGranter(ROTATOR))
    const w = await mountAt()
    await signIn(w)
    expect(getPin).toHaveBeenCalledWith(ROTATOR)
    expect(anchorChainKey).not.toHaveBeenCalled()
    expect(anchorServedChain).not.toHaveBeenCalledWith(ROTATOR, expect.anything(), expect.anything())
    const body = api.acceptTenantInvite.mock.calls[0][0].acceptInviteRequest
    expect(body.selfGrant!.epoch).toBe(2)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['an anomaly', { anchor: 'anomaly', retired: false }],
    ['a retired key of their chain', { anchor: 'match', retired: true }],
  ] as const)('a foreign granter this account pinned, whose grant key is %s, is refused — nothing is posted', async (_l, judged) => {
    const rotator = foreignGranter(ROTATOR)
    rewrappedBy(rotator)
    vi.mocked(getPin).mockResolvedValue({ fingerprint: 'AA==', head: 'AA==', index: 0, firstSeenAt: 1, replaced: false })
    vi.mocked(anchorChainKey).mockResolvedValue(judged)
    const w = await mountAt()
    await signIn(w)
    expect(anchorChainKey).toHaveBeenCalledWith(ROTATOR, bundleOf(rotator.identity))
    expect(w.text()).toContain(t('tenant.join.inviteUncheckable'))
    nothingPosted()
  })

  it('control: a foreign granter this account pinned, whose key the chain reaches, is accepted', async () => {
    rewrappedBy(foreignGranter(ROTATOR))
    vi.mocked(getPin).mockResolvedValue({ fingerprint: 'AA==', head: 'AA==', index: 0, firstSeenAt: 1, replaced: false })
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'advanced', retired: false })
    const w = await mountAt()
    await signIn(w)
    expect(api.acceptTenantInvite).toHaveBeenCalledTimes(1)
  })
})
