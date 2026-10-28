import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  tenantsApi: vi.fn(),
}))
// The group's key comes from the caller's own grant (scopes.ts, tested there); here the scope is handed over opened.
vi.mock('./scopes', () => ({ openScopeById: vi.fn(), listMyTenants: vi.fn() }))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))
// The pin store is identityPins.test.ts's; here its answer for the target is driven directly.
vi.mock('../account/identityPins', async (o) => ({ ...(await o<typeof import('../account/identityPins')>()), checkAndPin: vi.fn() }))

import {
  addGroupMember,
  listScopeMembers,
  setMemberRole,
  removeGroupMember,
  removeTenantMember,
  SetMemberRoleRequestRoleEnum,
} from './members'
import { scopesApi, tenantsApi } from '../../api/client'
import { TenantsApi, Configuration as TenantsConfiguration } from '../../api/generated/tenants'
import { openScopeById } from './scopes'
import { identityPrivate } from '../../app/vault'
import { checkAndPin, IdentityChangedError } from '../account/identityPins'
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
const G = '9a000000-0000-4000-8000-0000000000a1'
const U = '9a000000-0000-4000-8000-0000000000b1'
/** The group's key per epoch, as this test holds it; the data layer gets a fresh copy (it zeroes what it opened). */
const GROUP_KEYS = new Map([[2, v1.randomBytes(32)], [3, v1.randomBytes(32)]])
let heldEpoch = 2
const openGroup = () => {
  const key = GROUP_KEYS.get(heldEpoch)!.slice()
  return openedScope({ scopeId: G, kind: ScopeKind.Group, key, epochs: { current: heldEpoch, keys: new Map([[heldEpoch, key]]) } })
}

beforeEach(() => {
  vi.clearAllMocks()
  heldEpoch = 2
  vi.mocked(openScopeById).mockImplementation(async () => openGroup())
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: OWNER.priv.kemSeed.slice(), sign: OWNER.priv.sign.slice() }))
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
})

describe('addGroupMember (cr-0086) — the add carries the grant (ADR 0111 decision 4)', () => {
  const row: ScopeMember = { userId: U, username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: TARGET_PUB }

  it('wraps the group\'s current key to the resolved identity, signed by the owner, and posts it with the add', async () => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })

    expect(await addGroupMember(G, 'partner', U, TARGET_PUB, 'bmFtZWtleQ==')).toEqual(row)

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
    expect(checkAndPin).toHaveBeenCalledWith(U, TARGET_PUB)
  })

  it('refuses a target whose identity key CHANGED since it was pinned — nothing is opened or sent', async () => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })
    vi.mocked(checkAndPin).mockResolvedValue('changed')

    await expect(addGroupMember(G, 'partner', U, TARGET_PUB)).rejects.toBeInstanceOf(IdentityChangedError)
    expect(add).not.toHaveBeenCalled()
    expect(openScopeById).not.toHaveBeenCalled()
  })

  it('control: a key that matches its pin is added', async () => {
    const { addGroupMember: add } = stubApi(scopesApi, { addGroupMember: row })
    vi.mocked(checkAndPin).mockResolvedValue('match')

    await addGroupMember(G, 'partner', U, TARGET_PUB)
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

    await addGroupMember(G, 'partner', U, TARGET_PUB)

    expect(add.mock.calls.map((c) => c[0].addGroupMemberRequest.epoch)).toEqual([2, 3])
    const retry = add.mock.calls[1][0].addGroupMemberRequest
    expect(Array.from(v1.openAs(grantContext(G, 3), v1.hybridSecret(TARGET.priv), fromB64std(retry.wrappedGrant)))).toEqual(Array.from(GROUP_KEYS.get(3)!))
  })
})

describe('setMemberRole (cr-0088)', () => {
  it('promotes a member by patching the member endpoint with the role', async () => {
    const { setMemberRole: setRole } = stubApi(scopesApi, {
      setMemberRole: { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=' },
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
      { userId: 'o', username: 'owner', avatarRevision: 0, role: 'OWNER', identityPublicKey: 'cGs=' },
      { userId: 'u1', username: 'partner', avatarRevision: 0, role: 'MEMBER', identityPublicKey: 'cGs=' },
    ]
    const { listScopeMembers: listFn } = stubApi(scopesApi, { listScopeMembers: rows })

    expect(await listScopeMembers('g1')).toEqual(rows)
    expect(listFn).toHaveBeenCalledWith({ scopeId: 'g1' })
  })
})
