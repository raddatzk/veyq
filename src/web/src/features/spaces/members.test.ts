import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  tenantsApi: vi.fn(),
}))
// The group's key comes from the caller's own grant (scopes.ts, tested there); here the scope is handed over opened.
vi.mock('./scopes', () => ({ openScopeById: vi.fn(), listMyTenants: vi.fn(), commitRotation: vi.fn(), rekeyClosedScopes: vi.fn() }))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))
// The pin store is identityPins.test.ts's; here its answer for the target is driven directly.
vi.mock('../account/identityPins', async (o) => ({ ...(await o<typeof import('../account/identityPins')>()), anchorIdentity: vi.fn() }))
// Who is anchored in the group's tenant is tenantTrust.test.ts's; here the view is a token the anchor check must receive.
vi.mock('../account/tenantTrust', () => ({ tenantViewOfScope: vi.fn() }))

import {
  addGroupMember,
  listScopeMembers,
  setMemberRole,
  removeGroupMember,
  removeTenantMember,
  rekeySpace,
  SetMemberRoleRequestRoleEnum,
} from './members'
import { scopesApi, tenantsApi } from '../../api/client'
import { TenantsApi, Configuration as TenantsConfiguration } from '../../api/generated/tenants'
import { commitRotation, openScopeById, rekeyClosedScopes } from './scopes'
import { identityPrivate } from '../../app/vault'
import { anchorIdentity, IdentityUnanchoredError } from '../account/identityPins'
import { tenantViewOfScope, type TenantView } from '../account/tenantTrust'
import { grantContext, grantSignatureVerifies } from './grant'
import { stubApi, fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { openedScope } from '../../test/models'
import { ScopeKind, type ScopeMember, type ScopesApi } from '../../api/generated/scopes'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// The group owner (its identity signs the grant) and the member being added (its identity key is what the resolve served).
const OWNER = v1.generateIdentity()
const TARGET = v1.generateIdentity()
const TARGET_PUB = b64std(v1.serializeIdentityPublic(TARGET.pub))
const TARGET_HEAD = 'ERERERERERERERERERERERERERERERERERERERERERE='
/** The target as the resolve serves them: their key and the head of their key chain. */
const TARGET_SERVED = { identityPublicKey: TARGET_PUB, keyChainHead: TARGET_HEAD }
const G = '9a000000-0000-4000-8000-0000000000a1'
const U = '9a000000-0000-4000-8000-0000000000b1'
/** The group's key per epoch, as this test holds it; the data layer gets a fresh copy (it zeroes what it opened). */
const GROUP_KEYS = new Map([[2, v1.randomBytes(32)], [3, v1.randomBytes(32)]])
let heldEpoch = 2
/** The group's tenant as read for the add — what the anchor check is handed (ADR 0111 decision 15). */
const GROUP_TENANT = { tenantId: 'tenant-of-g', members: new Map(), available: true, standing: vi.fn() } as unknown as TenantView
const openGroup = () => {
  const key = GROUP_KEYS.get(heldEpoch)!.slice()
  return openedScope({ scopeId: G, kind: ScopeKind.Group, key, epochs: { epoch: heldEpoch, key } })
}

beforeEach(() => {
  vi.clearAllMocks()
  heldEpoch = 2
  vi.mocked(openScopeById).mockImplementation(async () => openGroup())
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: OWNER.priv.kemSeed.slice(), sign: OWNER.priv.sign.slice() }))
  vi.mocked(anchorIdentity).mockResolvedValue('firstUse')
  vi.mocked(tenantViewOfScope).mockResolvedValue(GROUP_TENANT)
})

describe('addGroupMember (cr-0086) — the add carries the grant (ADR 0111 decision 4)', () => {
  const row: ScopeMember = { userId: U, username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: TARGET_PUB, keyChainHead: TARGET_HEAD, pending: false }

  it('wraps the group\'s current key to the resolved identity, signed by the owner, and posts it with the add', async () => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })

    expect(await addGroupMember(G, 'partner', U, TARGET_SERVED, 'bmFtZWtleQ==')).toEqual(row)

    const body = vi.mocked(add).mock.calls[0][0].addGroupMemberRequest
    expect(body).toMatchObject({ username: 'partner', sealedNameKey: 'bmFtZWtleQ==', epoch: 2 })
    // The member opens the group's epoch-2 key with its OWN identity — it holds the key from the add on.
    const opened = v1.openAs(grantContext(G, 2), v1.hybridSecret(TARGET.priv), fromB64std(body.wrappedGrant))
    expect(Array.from(opened)).toEqual(Array.from(GROUP_KEYS.get(2)!))
    // Signed by the owner over exactly this grant: this group, kind GROUP, epoch 2, this recipient…
    const to = { userId: U, identityPublicKeyB64: TARGET_PUB }
    expect(grantSignatureVerifies(OWNER.pub.sign, { scopeId: G, kind: 'GROUP', epoch: 2 }, to, body.wrappedGrant, body.grantSignature)).toBe(true)
    // …control: not as another kind, nor under another key.
    expect(grantSignatureVerifies(OWNER.pub.sign, { scopeId: G, kind: 'TENANT', epoch: 2 }, to, body.wrappedGrant, body.grantSignature)).toBe(false)
    expect(grantSignatureVerifies(TARGET.pub.sign, { scopeId: G, kind: 'GROUP', epoch: 2 }, to, body.wrappedGrant, body.grantSignature)).toBe(false)
    // And it names the key it is wrapped to: SHA-256 of the target's bundle.
    expect(body.recipientIdentityDigest).toBe(b64std(v1.sha256Digest(fromB64std(TARGET_PUB))))
    // judged in the group's own tenant
    expect(tenantViewOfScope).toHaveBeenCalledWith(G)
    expect(anchorIdentity).toHaveBeenCalledWith(U, TARGET_SERVED, { tenant: GROUP_TENANT })
  })

  it('refuses a target whose key the chain does not reach from the pinned head — nothing is opened or sent', async () => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })
    vi.mocked(anchorIdentity).mockResolvedValue('anomaly')

    await expect(addGroupMember(G, 'partner', U, TARGET_SERVED)).rejects.toBeInstanceOf(IdentityUnanchoredError)
    expect(add).not.toHaveBeenCalled()
    expect(openScopeById).not.toHaveBeenCalled()
  })

  it.each(['match', 'advanced'] as const)('control: a key the pin anchors (%s) is added', async (anchor) => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })
    vi.mocked(anchorIdentity).mockResolvedValue(anchor)

    await addGroupMember(G, 'partner', U, TARGET_SERVED)
    expect(add).toHaveBeenCalledTimes(1)
  })

  it('rebuilds once on 409 stale_epoch — against the epoch a rotation left behind it', async () => {
    const add = vi.fn<ScopesApi['addGroupMember']>()
      .mockImplementationOnce(async () => {
        heldEpoch = 3 // the rotation's re-grant, visible to the re-open
        throw httpError(409, 'stale_epoch')
      })
      .mockResolvedValueOnce(row)
    fakeApi(scopesApi, { addGroupMember: add })

    await addGroupMember(G, 'partner', U, TARGET_SERVED)

    expect(add.mock.calls.map((c) => c[0].addGroupMemberRequest.epoch)).toEqual([2, 3])
    const retry = add.mock.calls[1][0].addGroupMemberRequest
    expect(Array.from(v1.openAs(grantContext(G, 3), v1.hybridSecret(TARGET.priv), fromB64std(retry.wrappedGrant)))).toEqual(Array.from(GROUP_KEYS.get(3)!))
  })
})

describe('setMemberRole (cr-0088)', () => {
  it('promotes a member by patching the member endpoint with the role', async () => {
    const { setMemberRole: setRole } = stubApi(scopesApi, {
      setMemberRole: { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=', keyChainHead: TARGET_HEAD, pending: false },
    })

    const result = await setMemberRole('g1', 'u1', SetMemberRoleRequestRoleEnum.Owner)

    expect(setRole).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1', setMemberRoleRequest: { role: 'OWNER' } })
    expect(result.role).toBe('OWNER')
  })
})

describe('removeGroupMember — the consent rescind (cr-0094)', () => {
  it('deletes a pending proposal via the member endpoint (a keyed member is removed by the removal-rotation instead)', async () => {
    const { removeMember: removeFn } = stubApi(scopesApi, { removeMember: undefined })

    await removeGroupMember('g1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ scopeId: 'g1', userId: 'u1' })
  })
})

describe('removeTenantMember (cr-0091)', () => {
  it('deletes the member via the tenant members endpoint (the server runs the whole cascade)', async () => {
    // A tenant-level cascade (cut shared, orphan rule, own-scope grace) — distinct from removeGroupMember. No
    // key crosses the wire; the tenant key is not rotated (ADR 0025). The client just fires it by (tenant, user).
    const { removeTenantMember: removeFn } = stubApi(tenantsApi, { removeTenantMember: undefined })

    await removeTenantMember('t1', 'u1')

    expect(removeFn).toHaveBeenCalledWith({ tenantId: 't1', userId: 'u1' })
  })

  // ADR 0114 decisions 1 and 9: the removal switches the member's links off — a close — so this device starts the re-key
  // of what it marked, after the server answered and only then.
  it("starts the re-key of every re-keying scope once the removal landed, and not when it was refused", async () => {
    stubApi(tenantsApi, { removeTenantMember: undefined })
    await removeTenantMember('t1', 'u1')
    expect(rekeyClosedScopes).toHaveBeenCalledTimes(1)
    expect(rekeyClosedScopes).toHaveBeenCalledWith()

    vi.mocked(rekeyClosedScopes).mockClear()
    fakeApi(tenantsApi, { removeTenantMember: vi.fn(async () => { throw httpError(409, 'last_owner') }) })
    await expect(removeTenantMember('t1', 'u2')).rejects.toBeTruthy()
    expect(rekeyClosedScopes).not.toHaveBeenCalled() // control: nothing closed, nothing re-keyed
  })

  // W2 (ADR 0075 point 5), asserted on the request the generated client actually sends — a `toHaveBeenCalledWith`
  // treats an `undefined` key as absent, so only the URL proves "no parameter".
  it('sends revokeLinks=true only when asked, and no parameter at all otherwise', async () => {
    const urls: string[] = []
    const fetchApi = vi.fn(async (url: RequestInfo | URL) => {
      urls.push(String(url))
      return new Response(null, { status: 204 })
    })
    const real = new TenantsApi(new TenantsConfiguration({ basePath: 'http://x', fetchApi }))
    // The real generated method, so the URL it builds is what is asserted.
    fakeApi(tenantsApi, { removeTenantMember: real.removeTenantMember.bind(real) })

    await removeTenantMember('t1', 'u1', true)
    await removeTenantMember('t1', 'u2')

    expect(urls).toEqual(['http://x/api/tenants/t1/members/u1?revokeLinks=true', 'http://x/api/tenants/t1/members/u2'])
  })
})

describe('listScopeMembers', () => {
  it('reads a scope’s member directory by id (operator-blind operational metadata only)', async () => {
    const rows: ScopeMember[] = [
      { userId: 'o', username: 'owner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=', keyChainHead: TARGET_HEAD, pending: false },
      { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGs=', keyChainHead: TARGET_HEAD, pending: true },
    ]
    const { listScopeMembers: listFn } = stubApi(scopesApi, { listScopeMembers: rows })

    expect(await listScopeMembers('g1')).toEqual(rows)
    expect(listFn).toHaveBeenCalledWith({ scopeId: 'g1' })
  })
})

// ADR 0025 § 2: the owner's "new lock" is the plain rotation every rotation is (commitRotation, scopes.test.ts), over the
// directory read now; the member a removal just took out is the owner's own exclusion, never the server's word.
describe('rekeySpace (uc.spaces.remove-tenant-member A8-A9)', () => {
  const row = (userId: string): ScopeMember => ({
    userId, username: userId, avatarRevision: 0, sealedName: null, role: 'MEMBER', identityPublicKey: 'AA==', keyChainHead: 'AA==', pending: false,
  })

  it('rotates the tenant over the directory read now, without the member just removed however the server lists them', async () => {
    stubApi(scopesApi, { listScopeMembers: [row('u-owner'), row('u-removed'), row('u-kept')] })
    vi.mocked(commitRotation).mockResolvedValue(4)

    expect(await rekeySpace('t1', 'u-removed')).toBe(4)

    const [scopeId, members, opts] = vi.mocked(commitRotation).mock.calls[0]
    expect(scopeId).toBe('t1')
    expect(members.map((m) => m.userId)).toEqual(['u-owner', 'u-removed', 'u-kept'])
    // The exclusion rides as the rotation's own option, so it holds for the directory its one rebuild reads again
    // (inbox #661; commitRotation's `excluding`, scopes.test.ts) — a filter here alone would not reach that re-read.
    expect(opts?.excluding).toBe('u-removed')
    // a plain rotation: no removal rides on it (the server refuses one on a TENANT, the cascade ran first)
    expect(opts?.removes).toBeUndefined()
  })

  it('control: with nobody removed, every member the directory lists is handed to the rotation, and nobody is excluded', async () => {
    stubApi(scopesApi, { listScopeMembers: [row('u-owner'), row('u-kept')] })
    vi.mocked(commitRotation).mockResolvedValue(1)

    await rekeySpace('t1')

    expect(vi.mocked(commitRotation).mock.calls[0][1].map((m) => m.userId)).toEqual(['u-owner', 'u-kept'])
    expect(vi.mocked(commitRotation).mock.calls[0][2]?.excluding).toBeUndefined()
  })
})
