// uc.spaces.add-member, the owner's side of a member invite (ADR 0111 decisions 1-2): what `inviteMember` / `reissueMemberInvite` POST, opened the way only the invitee can open it — the link's
// sealed part with the code, the seed inside it to the invite key, that key to the grant. Every claim about the posted
// bytes is checked with the real crypto; each negative has a control that passes on the same bytes.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ tenantsApi: vi.fn(), scopesApi: vi.fn() }))
vi.mock('./scopes', () => ({ openScopeById: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))
vi.mock('./grantTrust', () => ({ me: vi.fn() }))
// The owner's head (decision 1) is `myChainHead`'s — its walk of the served chain is tenantTrust's own suite.
vi.mock('../account/tenantTrust', () => ({ myChainHead: vi.fn(), tenantViewOfScope: vi.fn() }))
// The link's KEK is real Argon2id at a light cost — input-sensitive, which "another code does not open it" depends on
// (a constant stub would open with any code). Production cost is the conformance vectors' job (`#invite_link`).
const LIGHT = { t: 1, m: 64, p: 1, dkLen: 32 }
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  return { inviteLinkKekAsync: async (secret: Uint8Array, salt: Uint8Array) => real.kekInviteLink(secret, salt, LIGHT) }
})

import { inviteMember, reissueMemberInvite } from './members'
import { tenantsApi } from '../../api/client'
import { listMyTenants, openScopeById } from './scopes'
import { identityPrivate } from '../../app/vault'
import { me } from './grantTrust'
import { myChainHead } from '../account/tenantTrust'
import { genesisHeadOf } from '../../test/keyChain'
import { grantContext, grantSignatureVerifies } from './grant'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { myTenant, openedScope } from '../../test/models'
import { ScopeKind } from '../../api/generated/scopes'
import type { MemberInviteRequest, TenantsApi } from '../../api/generated/tenants'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

const OWNER = v1.generateIdentity()
const OWNER_ID = '0a000000-0000-4000-8000-0000000000a0'
const T = '7e000000-0000-4000-8000-0000000000e1'
const TTL = 3600
const EPOCH = 4
const TENANT_KEY = v1.randomBytes(32)
const CODE = invite.generateInviteCode()
/** The owner's key-chain head as their walked chain ends at it. */
const OWNER_HEAD = genesisHeadOf(OWNER_ID, OWNER)
/** The last tenant scope handed to the data layer — to look at after it returns. */
let lastOpened: ReturnType<typeof openedScope> | null = null

const STATE = { email: 'b@x.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' } as const

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: T, memberInviteTtlSeconds: TTL })])
  vi.mocked(openScopeById).mockImplementation(async () => {
    const key = TENANT_KEY.slice()
    lastOpened = openedScope({ scopeId: T, tenantId: T, kind: ScopeKind.Tenant, key, epochs: { current: EPOCH, keys: new Map([[EPOCH, key]]) } })
    return lastOpened
  })
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: OWNER.priv.kemSeed.slice(), sign: OWNER.priv.sign.slice() }))
  vi.mocked(me).mockResolvedValue({ userId: OWNER_ID, pub: OWNER.pub, pubB64: b64std(v1.serializeIdentityPublic(OWNER.pub)) })
  vi.mocked(myChainHead).mockResolvedValue({ userId: OWNER_ID, head: OWNER_HEAD })
})

/** Invite b@x.z with [secret] and return the request the server received. */
async function posted(secret: Parameters<typeof inviteMember>[2] = { mode: 'code', code: CODE }): Promise<MemberInviteRequest> {
  const send = vi.fn<TenantsApi['inviteTenantMember']>().mockResolvedValue(STATE)
  fakeApi(tenantsApi, { inviteTenantMember: send })
  await inviteMember(T, 'b@x.z', secret)
  expect(send).toHaveBeenCalledTimes(1)
  expect(send.mock.calls[0][0].tenantId).toBe(T)
  return send.mock.calls[0][0].memberInviteRequest
}

/** Open the link's sealed part as the invitee's client does: the mailed fragment is base64url of the posted bytes. */
function openLink(req: MemberInviteRequest, typed: string, address = { tenantId: T, inviteId: req.inviteId }) {
  const sealed = invite.parseSealedInviteLink(b64url(fromB64std(req.sealedLink)))
  const secret = invite.inviteLinkSecret(sealed.mode, typed)
  if (!secret) throw new Error('not a secret')
  return invite.openInviteLink(address, v1.kekInviteLink(secret, sealed.salt, LIGHT), sealed)
}

describe('the link: sealed under the code, holding the seed and the owner', () => {
  it('opens with the code — to the seed of the posted invite key, the owner\'s key-chain head and account', async () => {
    const req = await posted()
    const payload = openLink(req, CODE)
    expect(b64std(v1.serializeIdentityPublic(invite.inviteKeyFromSeed(payload.seed).pub))).toBe(req.invitePublicKey)
    expect(b64std(payload.signerHead)).toBe(OWNER_HEAD)
    expect(payload.signerUserId).toBe(OWNER_ID)
    // As typed by a person: grouped, lower case — the same code.
    expect(() => openLink(req, invite.formatInviteCode(CODE).toLowerCase())).not.toThrow()
  })

  it('does not open with another code (control: the right code opens the same bytes)', async () => {
    const req = await posted()
    let other = invite.generateInviteCode()
    while (other === CODE) other = invite.generateInviteCode()
    expect(() => openLink(req, other)).toThrow()
    expect(() => openLink(req, CODE)).not.toThrow()
  })

  it('is bound to its invite: the right code does not open it as another invite\'s link', async () => {
    const req = await posted()
    expect(() => openLink(req, CODE, { tenantId: T, inviteId: crypto.randomUUID() })).toThrow()
  })

  it('takes a password the owner chose instead — the link says so, and only that password opens it', async () => {
    const req = await posted({ mode: 'password', password: 'correct horse battery' })
    expect(invite.parseSealedInviteLink(b64url(fromB64std(req.sealedLink))).mode).toBe('password')
    expect(() => openLink(req, 'correct horse battery')).not.toThrow()
    expect(() => openLink(req, 'correct horse battery!')).toThrow()
  })

  it('an own chain that does not end at this vault\'s identity mints nothing (myChainHead refuses) — nothing is opened or sent', async () => {
    const send = vi.fn<TenantsApi['inviteTenantMember']>()
    fakeApi(tenantsApi, { inviteTenantMember: send })
    vi.mocked(myChainHead).mockRejectedValue(new Error('the served key chain does not end at this account’s identity'))
    await expect(inviteMember(T, 'b@x.z', { mode: 'code', code: CODE })).rejects.toThrow()
    expect(openScopeById).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
  })

  it('refuses a secret that is no code before anything is opened or sent', async () => {
    const send = vi.fn<TenantsApi['inviteTenantMember']>()
    fakeApi(tenantsApi, { inviteTenantMember: send })
    await expect(inviteMember(T, 'b@x.z', { mode: 'code', code: 'not-a-code' })).rejects.toThrow()
    expect(openScopeById).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
  })
})

describe('the invite key: signed by the owner, granted the tenant\'s current key', () => {
  it('the grant opens with the key the seed derives — the tenant key at its current epoch', async () => {
    const req = await posted()
    const inviteKey = invite.inviteKeyFromSeed(openLink(req, CODE).seed)
    expect(req.epoch).toBe(EPOCH)
    const opened = v1.openAs(grantContext(T, EPOCH), v1.hybridSecret(inviteKey.priv), fromB64std(req.wrappedGrant))
    expect(Array.from(opened)).toEqual(Array.from(TENANT_KEY))
    // Control: another key does not open it.
    expect(() => v1.openAs(grantContext(T, EPOCH), v1.hybridSecret(v1.generateIdentity().priv), fromB64std(req.wrappedGrant))).toThrow()
  })

  it('the grant is signed by the owner as a TENANT grant to the invite (its id the recipient)', async () => {
    const req = await posted()
    const to = { userId: req.inviteId, identityPublicKeyB64: req.invitePublicKey }
    const scope = { scopeId: T, kind: 'TENANT', epoch: EPOCH } as const
    expect(grantSignatureVerifies(OWNER.pub.sign, scope, to, req.wrappedGrant, req.grantSignature)).toBe(true)
    expect(grantSignatureVerifies(v1.generateIdentity().pub.sign, scope, to, req.wrappedGrant, req.grantSignature)).toBe(false)
  })

  it('inviteKeySignature is the owner\'s over (tenant, invite, key, expiry) — the expiry the instance\'s lifetime from now', async () => {
    const before = Math.floor(Date.now() / 1000)
    const req = await posted()
    const expiresAt = req.expiresAt.getTime() / 1000
    expect(Number.isInteger(expiresAt)).toBe(true)
    expect(expiresAt - before).toBeGreaterThanOrEqual(TTL)
    expect(expiresAt - before).toBeLessThanOrEqual(TTL + 5)
    const signed = { tenantId: T, inviteId: req.inviteId, invitePublicKey: fromB64std(req.invitePublicKey), expiresAt }
    const sig = fromB64std(req.inviteKeySignature)
    expect(invite.inviteKeySignatureVerifies(OWNER.pub.sign, signed, sig)).toBe(true)
    // Controls: another signer, another expiry, another invite — none verifies.
    expect(invite.inviteKeySignatureVerifies(v1.generateIdentity().pub.sign, signed, sig)).toBe(false)
    expect(invite.inviteKeySignatureVerifies(OWNER.pub.sign, { ...signed, expiresAt: expiresAt + 1 }, sig)).toBe(false)
    expect(invite.inviteKeySignatureVerifies(OWNER.pub.sign, { ...signed, inviteId: crypto.randomUUID() }, sig)).toBe(false)
  })

  it('recipientIdentityDigest is SHA-256 of the invite bundle', async () => {
    const req = await posted()
    expect(req.recipientIdentityDigest).toBe(b64std(v1.sha256Digest(fromB64std(req.invitePublicKey))))
  })

  it('every invite is a new key under a new id', async () => {
    const one = await posted()
    const two = await posted()
    expect(two.inviteId).not.toBe(one.inviteId)
    expect(two.invitePublicKey).not.toBe(one.invitePublicKey)
  })

  it('wipes the tenant key and the identity copy it was handed', async () => {
    const priv = { kemSeed: OWNER.priv.kemSeed.slice(), sign: OWNER.priv.sign.slice() }
    vi.mocked(identityPrivate).mockReturnValueOnce(priv)
    await posted()
    expect(lastOpened!.key.every((b) => b === 0)).toBe(true)
    expect(priv.sign.every((b) => b === 0)).toBe(true)
  })
})

describe('reissue and a stale epoch', () => {
  it('a re-issue posts a whole new invite for the same address, addressed to the old invite', async () => {
    const send = vi.fn<TenantsApi['reissueMemberInvite']>().mockResolvedValue(STATE)
    fakeApi(tenantsApi, { reissueMemberInvite: send })
    const old = crypto.randomUUID()
    await reissueMemberInvite(T, old, 'b@x.z', { mode: 'code', code: CODE })
    const { tenantId, inviteId, memberInviteRequest: req } = send.mock.calls[0][0]
    expect({ tenantId, inviteId, email: req.email }).toEqual({ tenantId: T, inviteId: old, email: 'b@x.z' })
    expect(req.inviteId).not.toBe(old)
    expect(() => openLink(req, CODE)).not.toThrow()
  })

  it('a 409 stale_epoch is rebuilt once — a new invite under the re-opened key', async () => {
    const send = vi.fn<TenantsApi['inviteTenantMember']>().mockRejectedValueOnce(httpError(409, 'stale_epoch')).mockResolvedValueOnce(STATE)
    fakeApi(tenantsApi, { inviteTenantMember: send })
    await inviteMember(T, 'b@x.z', { mode: 'code', code: CODE })
    expect(send).toHaveBeenCalledTimes(2)
    expect(openScopeById).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1][0].memberInviteRequest.inviteId).not.toBe(send.mock.calls[0][0].memberInviteRequest.inviteId)
  })

  it('control: another refusal is not retried', async () => {
    const send = vi.fn<TenantsApi['inviteTenantMember']>().mockRejectedValue(httpError(409, 'already_member'))
    fakeApi(tenantsApi, { inviteTenantMember: send })
    await expect(inviteMember(T, 'b@x.z', { mode: 'code', code: CODE })).rejects.toBeTruthy()
    expect(send).toHaveBeenCalledTimes(1)
  })
})
