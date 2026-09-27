import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  contentApi: vi.fn(),
  tenantsApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  accountApi: vi.fn(),
}))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))
// The TOFU pin store is exercised in identityPins.test.ts; here we drive checkAndPin's outcome directly so the
// rotation re-grant's per-member gate is under our control (default: first-use → proceed).
vi.mock('../account/identityPins', () => ({
  checkAndPin: vi.fn(),
  IdentityChangedError: class IdentityChangedError extends Error {
    constructor(public userId: string) {
      super(`identity key changed for ${userId}`)
    }
  },
}))

import { ensurePersonalScope, openPersonalScope, openScope, openScopeById, openGroupByToken, listMyTenants, createGroup, listMyGroups, rotateGroupForRemoval, commitRotation, renameGroup, deleteGroup, restoreGroup, openMyTenantIcon, openGroupIcon } from './scopes'
import { ScopeKind, type MyTenant, type ScopeMember } from '../../api/generated/scopes'
import { scopesApi, contentApi, tenantsApi, accountApi } from '../../api/client'
import { createFolder } from '../memory/tree'
import { checkAndPin } from '../account/identityPins'
import { identityPrivate } from '../../app/vault'
import { epochLinkContext } from './epochs'
import { deriveGroupNameKey, sealGroupName, openGroupNameWith } from './inviteName'
import { deriveSpaceIconKey, sealIcon } from './spaceIcon'
import { grantSignatureVerifies, signGrant, tagAdmission, admissionVerifies } from './grant'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// A real identity for the test; the mocked vault hands out a FRESH copy each call (the data layer zeroes
// the copy it receives), while we keep this master to verify the grants it produces.
const ID = v1.generateIdentity()
const copyPriv = () => ({ kemX: ID.priv.kemX.slice(), mlkemSeed: ID.priv.mlkemSeed.slice(), sign: ID.priv.sign.slice() })
const ID_PUB = b64std(v1.serializeIdentityPublic(ID.pub))
/** The caller's account id, as its own key material names it. */
const ME = '0e000000-0000-4000-8000-0000000000e0'
const pubOf = (id: v1.Identity) => b64std(v1.serializeIdentityPublic(id.pub))

type Row = { id: string; kind: string; wrappedGrant: string; grantedEpoch?: number; [k: string]: unknown }
/** A row as the server serves the caller's grant: signed by `granter` (the caller itself by default) over exactly
 *  this grant — its scope, kind and epoch, the caller as recipient (ADR 0028 Decision 6). */
type Signed = { granterUserId: string; grantSignature: string; granterIdentityPublicKey: string }
function signed<R extends Row>(row: R, granter: { userId: string; identity: v1.Identity } = { userId: ME, identity: ID }): R & Signed {
  const scope = { scopeId: row.id, kind: row.kind as v1.ScopeKind, epoch: row.grantedEpoch ?? 0 }
  return {
    ...row,
    granterUserId: granter.userId,
    grantSignature: signGrant(granter.identity.priv.sign, scope, { userId: ME, identityPublicKeyB64: ID_PUB }, row.wrappedGrant),
    granterIdentityPublicKey: pubOf(granter.identity),
  }
}

// Canonical scope ids — every grant, group name, icon and chain link is sealed bound to its scope (ADR 0072).
const T = '5c09e000-0000-4000-8000-000000000e01'
const P = '5c09e000-0000-4000-8000-000000000e02'
const TN = '5c09e000-0000-4000-8000-000000000e03'
const G1 = '5c09e000-0000-4000-8000-000000000e04'
const G2 = '5c09e000-0000-4000-8000-000000000e05'
const T1 = '5c09e000-0000-4000-8000-000000000e06'
/** Our own grant on `scopeId` at `epoch` — what the server lists on our row there. */
const ownGrant = (scopeId: string, key: Uint8Array, epoch: number | string = 0) =>
  b64std(v1.sealTo({ purpose: 'scope-grant', scopeId, epoch: Number(epoch) }, v1.hybridPublic(copyPriv()), key))
/** Open a grant as the one on `scopeId`'s row at `epoch`, with `priv` (our own identity by default). */
const openOwn = (scopeId: string, epoch: number, blob: string, priv: v1.Identity['priv'] = copyPriv()) =>
  v1.openAs({ purpose: 'scope-grant', scopeId, epoch }, v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(blob))
/** The chain link stored at `epoch`: `prev` sealed under `key`. */
const linkUnder = (scopeId: string, epoch: number, key: Uint8Array, prev: Uint8Array) =>
  b64std(v1.seal(epochLinkContext(scopeId, epoch), key, prev))
const peel = (scopeId: string, epoch: number, key: Uint8Array, wrappedPrev: string) =>
  v1.open(epochLinkContext(scopeId, epoch), key, fromB64std(wrappedPrev))

function mockScopes(list: ReturnType<typeof vi.fn>, create: ReturnType<typeof vi.fn>, createGroupFn = vi.fn()) {
  vi.mocked(scopesApi).mockReturnValue({
    listMyScopes: list,
    createPersonalScope: create,
    createGroup: createGroupFn,
  } as unknown as ReturnType<typeof scopesApi>)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
  vi.mocked(accountApi).mockReturnValue({ getAccountKeyMaterial: vi.fn().mockResolvedValue({ userId: ME }) } as unknown as ReturnType<typeof accountApi>)
  // Default: every contact is new/unchanged → the rotation re-grants them (TOFU). Overridden per test.
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
})

describe('ensurePersonalScope', () => {
  it('births a personal scope (operator-blind grant that opens back to a 32-byte key) when none exists', async () => {
    const list = vi.fn().mockResolvedValue([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })])
    const create = vi.fn().mockResolvedValue({ scopeId: 's', kind: 'PERSONAL' })
    mockScopes(list, create)

    const created = await ensurePersonalScope()
    expect(created).toBe(true)
    expect(create).toHaveBeenCalledTimes(1)

    // The posted grant is a real seal to our identity, bound to the scope id it posted — it must open back to a 32-byte scope key.
    const grant: string = create.mock.calls[0][0].createPersonalScopeRequest.wrappedGrant
    const opened = openOwn(create.mock.calls[0][0].createPersonalScopeRequest.scopeId, 0, grant)
    expect(opened.length).toBe(32)
  })

  it('is a no-op when a personal scope already exists', async () => {
    const list = vi.fn().mockResolvedValue([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' })])
    const create = vi.fn()
    mockScopes(list, create)

    expect(await ensurePersonalScope()).toBe(false)
    expect(create).not.toHaveBeenCalled()
  })
})

describe('openPersonalScope', () => {
  it('opens the personal grant back to the scope key', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = ownGrant(P, scopeKey, 0)
    const list = vi.fn().mockResolvedValue([
      signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }),
      signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: grant, grantedEpoch: 0 }),
    ])
    mockScopes(list, vi.fn())

    const opened = await openPersonalScope()
    expect(opened).not.toBeNull()
    expect(Array.from(opened!)).toEqual(Array.from(scopeKey))
  })

  it('returns null when there is no personal scope', async () => {
    mockScopes(vi.fn().mockResolvedValue([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })]), vi.fn())
    expect(await openPersonalScope()).toBeNull()
  })
})

describe('openScope', () => {
  it('opens the TENANT grant back to a 32-byte scope key — open-only, never POSTs (no client birth)', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = ownGrant(TN, scopeKey, 0)
    const list = vi.fn().mockResolvedValue([
      signed({ id: TN, kind: 'TENANT', tenantId: 't', wrappedGrant: grant, grantedEpoch: 0 }),
      signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' }),
    ])
    const create = vi.fn()
    mockScopes(list, create)

    const opened = await openScope(ScopeKind.Tenant)
    expect(opened).not.toBeNull()
    expect(opened!.scopeId).toBe(TN)
    expect(opened!.kind).toBe('TENANT')
    expect(opened!.key.length).toBe(32)
    expect(Array.from(opened!.key)).toEqual(Array.from(scopeKey))
    expect(create).not.toHaveBeenCalled() // the tenant scope is born cold at activation — never minted here
  })

  it('returns null when the caller holds no scope of that kind (→ the shell falls back to Personal)', async () => {
    mockScopes(vi.fn().mockResolvedValue([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' })]), vi.fn())
    expect(await openScope(ScopeKind.Tenant)).toBeNull()
  })
})

describe('createGroup', () => {
  it('posts a self-grant + a name sealed under K_name_0 derived from the birth K_SC (operator-blind — only ciphertext leaves)', async () => {
    const list = vi.fn().mockResolvedValue([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })])
    const createGroupFn = vi.fn().mockResolvedValue({ scopeId: G1, kind: 'GROUP' })
    mockScopes(list, vi.fn(), createGroupFn)

    const scopeId = await createGroup('Familie')
    expect(scopeId).toBe(G1)
    expect(createGroupFn).toHaveBeenCalledTimes(1)

    // Recover K_SC (= E_0) from the posted grant; the posted encryptedName must decrypt back to the plaintext name
    // under K_name_0 = HKDF(E_0) (cr-0096), NOT under K_SC directly — proving the derived-name-key seal.
    const { scopeId: posted, encryptedName, wrappedGrant } = createGroupFn.mock.calls[0][0].createGroupRequest
    expect(posted).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/) // client-minted UUIDv4
    const key = openOwn(posted, 0, wrappedGrant)
    expect(key.length).toBe(32)
    expect(openGroupNameWith(deriveGroupNameKey(key), posted, 0, encryptedName)).toBe('Familie')
    expect(() => openGroupNameWith(key, posted, 0, encryptedName)).toThrow() // NOT sealed under K_SC directly
  })
})

describe('listMyGroups', () => {
  it('decrypts each group’s name via K_name (from the name-epoch key) on-device (the row carries only ciphertext)', async () => {
    const key = v1.randomBytes(32) // E_0
    const grant = ownGrant(G1, key, 0)
    const encryptedName = sealGroupName(deriveGroupNameKey(key), G1, 0, 'Reise-Crew') // sealed under K_name_0
    const list = vi.fn().mockResolvedValue([
      signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }),
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 }),
    ])
    mockScopes(list, vi.fn())

    expect(await listMyGroups()).toEqual([{ scopeId: G1, tenantId: 't', name: 'Reise-Crew', icon: null, deletedAt: null, purgeAfter: null }])
  })

  it('opens a group icon sealed under an OLDER epoch by peeling the chain (a rotated group: iconEpoch < currentEpoch)', async () => {
    // The group-only path a tenant never hits — openMyTenantIcon shortcuts to epoch 0, so listMyGroups must peel.
    // Seal the icon under K_icon of E_0 while the caller holds E_1 (grantedEpoch 1); the chain link lets it peel down.
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const grant1 = ownGrant(G1, e1, 1)
    const wrappedPrev = linkUnder(G1, 1, e1, e0) // E_0 sealed under E_1
    const image = new Uint8Array([9, 8, 7, 6, 5, 4, 3, 2, 1])
    const sealedIcon = sealIcon(deriveSpaceIconKey(e0), { scopeId: G1, iconEpoch: 0 }, image) // K_icon of E_0, iconEpoch 0
    const encryptedName = sealGroupName(deriveGroupNameKey(e0), G1, 0, 'Alte Crew') // name also at epoch 0
    const list = vi.fn().mockResolvedValue([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, encryptedName, nameEpoch: 0, grantedEpoch: 1, iconEpoch: 0, sealedIcon }),
    ])
    const epochs = vi.fn().mockResolvedValue([{ epoch: 1, wrappedPrev }])
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, listScopeEpochs: epochs } as unknown as ReturnType<typeof scopesApi>)

    const [g] = await listMyGroups()
    expect(g.name).toBe('Alte Crew')
    expect(g.icon).not.toBeNull()
    expect(Array.from(g.icon!)).toEqual(Array.from(image)) // peeled E_1 → E_0, derived K_icon, opened + unpadded
  })
})

describe('openGroupIcon (the settings-panel single-scope read)', () => {
  it('peels the chain to open a rotated group’s icon, and returns null for a group with none / an unknown id', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const grant1 = ownGrant(G1, e1, 1)
    const grant0 = ownGrant(G2, e0, 0)
    const wrappedPrev = linkUnder(G1, 1, e1, e0)
    const image = new Uint8Array([1, 2, 3, 4, 5])
    const sealedIcon = sealIcon(deriveSpaceIconKey(e0), { scopeId: G1, iconEpoch: 0 }, image)
    const list = vi.fn().mockResolvedValue([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, nameEpoch: 0, grantedEpoch: 1, iconEpoch: 0, sealedIcon }),
      signed({ id: G2, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, nameEpoch: 0, grantedEpoch: 0 }), // no icon
    ])
    const epochs = vi.fn().mockResolvedValue([{ epoch: 1, wrappedPrev }])
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, listScopeEpochs: epochs } as unknown as ReturnType<typeof scopesApi>)

    expect(Array.from((await openGroupIcon(G1))!)).toEqual(Array.from(image)) // peels then opens
    expect(await openGroupIcon(G2)).toBeNull() // no sealedIcon → the initial
    expect(await openGroupIcon('nope')).toBeNull() // unknown id
  })
})

describe('openScopeById', () => {
  it('opens a scope by id and decrypts a GROUP’s display name from encryptedName (via K_name)', async () => {
    const key = v1.randomBytes(32)
    const grant = ownGrant(G1, key, 0)
    const encryptedName = sealGroupName(deriveGroupNameKey(key), G1, 0, 'Reise-Crew')
    const list = vi.fn().mockResolvedValue([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 }),
    ])
    mockScopes(list, vi.fn())

    const opened = await openScopeById(G1)
    expect(opened).not.toBeNull()
    expect(opened!.kind).toBe('GROUP')
    expect(opened!.name).toBe('Reise-Crew')
    expect(Array.from(opened!.key)).toEqual(Array.from(key))
  })

  it('returns null when the caller holds no scope with that id', async () => {
    mockScopes(vi.fn().mockResolvedValue([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })]), vi.fn())
    expect(await openScopeById('missing')).toBeNull()
  })
})

describe('openGroupByToken (cr-0120 — resolve a group from its `#@<slug|id>` fragment)', () => {
  const groupRow = (id: string, name: string) => {
    const key = v1.randomBytes(32)
    const grant = ownGrant(id, key, 0)
    return signed({ id, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName: sealGroupName(deriveGroupNameKey(key), id, 0, name), nameEpoch: 0, grantedEpoch: 0 })
  }

  it('resolves a readable slug to its group by decrypting + slugifying each name on-device', async () => {
    mockScopes(vi.fn().mockResolvedValue([groupRow(G1, 'Familie Süd'), groupRow(G2, 'Reise 2026')]), vi.fn())

    const opened = await openGroupByToken('familie-sued') // ü → ue
    expect(opened?.scopeId).toBe(G1)
    expect(opened?.name).toBe('Familie Süd')
  })

  it('resolves an opaque scopeId token directly (a legacy or collision-fallback link)', async () => {
    const id = '0264addd-1787-40bc-bccf-811d37bf6989'
    mockScopes(vi.fn().mockResolvedValue([groupRow(id, 'Familie Süd')]), vi.fn())
    expect((await openGroupByToken(id))?.scopeId).toBe(id)
  })

  it('resolves nothing for an ambiguous slug (two groups share it — groups have no server-side slug uniqueness)', async () => {
    mockScopes(vi.fn().mockResolvedValue([groupRow(G1, 'Familie Süd'), groupRow(G2, 'Familie Sued')]), vi.fn())
    expect(await openGroupByToken('familie-sued')).toBeNull()
  })
})

describe('commitRotation / rotateGroupForRemoval (cr-0089 — the client-side forward rotation)', () => {
  const A = 'a0000000-0000-4000-8000-0000000000a0'
  const B = 'b0000000-0000-4000-8000-0000000000b0'
  /** A remaining member admitted to G1 by a genuine key-holder of `e0`, at epoch 0 (a group add binds the member). */
  const admittedMember = (userId: string, who: v1.Identity, e0: Uint8Array): ScopeMember => ({
    userId, username: userId.slice(0, 1), role: 'MEMBER', identityPublicKey: pubOf(who), hasGrant: true,
    ...tagAdmission(e0, { scopeId: G1, kind: 'GROUP' }, 0, userId),
  })

  it('mints a fresh epoch, chain-links the old one under it, and re-grants it to every remaining member', async () => {
    // The caller holds the group at epoch 0 (E_0). commitRotation opens that grant to derive E_0.
    const e0 = v1.randomBytes(32)
    const grant0 = ownGrant(G1, e0, 0)
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)

    // Two surviving members with real identities (the removed one is simply absent from this list).
    const memberA = v1.generateIdentity()
    const memberB = v1.generateIdentity()
    const remaining: ScopeMember[] = [admittedMember(A, memberA, e0), admittedMember(B, memberB, e0)]

    const newEpoch = await commitRotation(G1, remaining)
    expect(newEpoch).toBe(1)
    expect(rotate).toHaveBeenCalledTimes(1)

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([A, B])

    // Each member's grant opens (with their OWN identity) to the same fresh epoch key E_1 — operator-blind: only
    // ciphertext was posted, and every remaining member can read forward.
    const e1a = openOwn(G1, 1, payload.grants[0].wrappedGrant, memberA.priv)
    const e1b = openOwn(G1, 1, payload.grants[1].wrappedGrant, memberB.priv)
    expect(e1a.length).toBe(32)
    expect(Array.from(e1a)).toEqual(Array.from(e1b))

    // The chain-link wraps the OLD epoch key under the new one: E_0 opens from wrappedPrev under E_1, as the link stored at epoch 1,
    // so a member can still peel down to read pre-rotation content.
    const peeledE0 = peel(G1, 1, e1a, payload.wrappedPrev)
    expect(Array.from(peeledE0)).toEqual(Array.from(e0))

    // Forward-confidentiality: the new epoch differs from the old, so content sealed under E_1 is unreadable with E_0.
    expect(Array.from(e1a)).not.toEqual(Array.from(e0))

    // Every re-grant carries the rotator's signature over it (ADR 0028 Decision 6)…
    const next = { scopeId: G1, kind: 'GROUP' as const, epoch: 1 }
    for (const [g, who] of [[payload.grants[0], memberA], [payload.grants[1], memberB]] as const) {
      expect(grantSignatureVerifies(ID.pub.sign, next, { userId: g.userId, identityPublicKeyB64: pubOf(who) }, g.wrappedGrant, g.grantSignature)).toBe(true)
    }
    // …and every admission is carried forward under the NEW epoch, same id (ADR 0020 move 6): verifiable at 1 with E_1.
    expect(payload.droppedAdmissions).toEqual([])
    for (const m of remaining) {
      const carried = payload.admissions.find((a: { admissionId: string }) => a.admissionId === m.admissionId)
      expect(admissionVerifies(e1a, 1, { scopeId: G1, kind: 'GROUP' }, m.userId, { ...carried, admissionEpoch: 1 })).toBe(true)
    }
  })

  it('does not re-grant a member whose old admission tag fails, and drops that admission (ADR 0020 move 6)', async () => {
    // A membership the server invented, or one an owner's session made without the vault, carries a tag that does
    // not verify under E_n. The rotator must not carry it into the new epoch — nor wrap E_{n+1} to that member.
    const e0 = v1.randomBytes(32)
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)
    const memberA = v1.generateIdentity()
    const forged = v1.generateIdentity()
    const bad = { ...admittedMember(B, forged, e0), admissionTag: b64std(v1.randomBytes(32)) }

    await commitRotation(G1, [admittedMember(A, memberA, e0), bad])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([A]) // the forged one is not re-granted
    expect(payload.droppedAdmissions).toEqual([bad.admissionId]) // …and its admission is dropped on purpose
    expect(payload.admissions.map((a: { admissionId: string }) => a.admissionId)).not.toContain(bad.admissionId)
  })

  it('control: a member whose tag verifies is re-granted and re-tagged', async () => {
    const e0 = v1.randomBytes(32)
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)
    const good = admittedMember(B, v1.generateIdentity(), e0)

    await commitRotation(G1, [good])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([B])
    expect(payload.admissions.map((a: { admissionId: string }) => a.admissionId)).toEqual([good.admissionId])
    expect(payload.droppedAdmissions).toEqual([])
  })

  it('a TENANT rotation carries its open member invites forward too — and drops a forged one', async () => {
    const e0 = v1.randomBytes(32)
    const list = vi.fn().mockResolvedValue([signed({ id: T1, kind: 'TENANT', tenantId: T1, wrappedGrant: ownGrant(T1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)
    const genuine = tagAdmission(e0, { scopeId: T1, kind: 'TENANT' }, 0, null)
    const forged = { ...tagAdmission(v1.randomBytes(32), { scopeId: T1, kind: 'TENANT' }, 0, null) }
    vi.mocked(tenantsApi).mockReturnValue({
      listMemberInvites: vi.fn().mockResolvedValue([{ inviteId: 'i1', email: 'a@x', ...genuine }, { inviteId: 'i2', email: 'b@x', ...forged }]),
    } as unknown as ReturnType<typeof tenantsApi>)

    await commitRotation(T1, [])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.admissions.map((a: { admissionId: string }) => a.admissionId)).toEqual([genuine.admissionId])
    expect(payload.droppedAdmissions).toEqual([forged.admissionId])
  })

  it('excludes a member whose identity key CHANGED from the re-grant, but still rotates for the others (cr-0098)', async () => {
    // TOFU at the rotation site: a surviving member whose directory key differs from the pin is NOT re-granted the
    // fresh epoch (never silently wrap to a possibly-substituted key). The rotation still proceeds — forward secrecy
    // against the removed member must not hinge on one contact's changed key. The excluded member stays awaiting-access.
    const e0 = v1.randomBytes(32)
    const grant0 = ownGrant(G1, e0, 0)
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)

    const memberA = v1.generateIdentity()
    const memberB = v1.generateIdentity()
    const remaining: ScopeMember[] = [admittedMember(A, memberA, e0), admittedMember(B, memberB, e0)]
    // b's key changed since it was pinned → exclude b, keep a.
    vi.mocked(checkAndPin).mockImplementation(async (userId: string) => (userId === B ? 'changed' : 'match'))

    const newEpoch = await commitRotation(G1, remaining)
    expect(newEpoch).toBe(1)
    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([A]) // b excluded, a still re-granted
  })

  it('never wraps the new epoch to the member it removes, whatever list it is handed (ADR 0024 Decision 4)', async () => {
    // The server re-lists the removed member — and twice, as a duplicate row. The removal names them, and the
    // exclusion is the remover's own choice: no row with their id reaches the grants.
    const e0 = v1.randomBytes(32)
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)
    const R = 'c0000000-0000-4000-8000-0000000000c0'
    const removed = admittedMember(R, v1.generateIdentity(), e0)
    const pickedFrom: ScopeMember[] = [admittedMember(A, v1.generateIdentity(), e0), removed, admittedMember(B, v1.generateIdentity(), e0), { ...removed }]

    await rotateGroupForRemoval(G1, pickedFrom, R)

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    // Every remaining member is named (the control on the same predicate); the removed one is not.
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([A, B])
    expect(payload.admissions.map((a: { admissionId: string }) => a.admissionId)).not.toContain(removed.admissionId)
    expect(payload.removesUserId).toBe(R)
  })
})

describe('a scope whose grant is older than its current epoch writes nothing (ADR 0024 Decision 6)', () => {
  /** Open G1 as the app does, then create a folder in it the way the tree does — under the opened write key. */
  async function openAndCreate(grantedEpoch: number, currentEpoch: number) {
    const keys = Array.from({ length: grantedEpoch + 1 }, () => v1.randomBytes(32))
    const list = vi.fn().mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[grantedEpoch], grantedEpoch), grantedEpoch, currentEpoch })])
    const links = keys.slice(1).map((k, i) => ({ epoch: i + 1, wrappedPrev: linkUnder(G1, i + 1, k, keys[i]) }))
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, listScopeEpochs: vi.fn().mockResolvedValue(links) } as unknown as ReturnType<typeof scopesApi>)
    const createNode = vi.fn(async ({ createNodeRequest }: { createNodeRequest: { nodeId: string } }) => ({ nodeId: createNodeRequest.nodeId }))
    vi.mocked(contentApi).mockReturnValue({ createNode } as unknown as ReturnType<typeof contentApi>)
    const opened = await openScopeById(G1)
    if (opened) await createFolder(G1, opened.epochs, 'Neu')
    return { opened, createNode }
  }

  it('a member granted epoch 0 of a scope now at epoch 1 reads as awaiting the current key, and creates nothing', async () => {
    const { opened, createNode } = await openAndCreate(0, 1)
    expect(opened).toBeNull() // the awaiting-access state, as for a refused or absent grant
    expect(createNode).not.toHaveBeenCalled()
  })

  it('control: a member holding the current epoch creates, tagged with it', async () => {
    const { opened, createNode } = await openAndCreate(1, 1)
    expect(opened?.epochs.current).toBe(1)
    expect(createNode).toHaveBeenCalledTimes(1)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ epoch: 1 })
  })
})

describe('commitRotation (ADR 0024 §4 — the rotation states the epoch it creates)', () => {
  const stale = () => ({ response: new Response(JSON.stringify({ code: 'stale_epoch' }), { status: 409 }) })

  /** The caller's grant at `grantedEpoch`, with the chain-links down to E_0 — what `openScopeById` peels. */
  function holdGroupAt(grantedEpoch: number, rotate: ReturnType<typeof vi.fn>, list = vi.fn()): Uint8Array[] {
    const keys = Array.from({ length: grantedEpoch + 1 }, () => v1.randomBytes(32))
    const links = keys.slice(1).map((k, i) => ({ epoch: i + 1, wrappedPrev: linkUnder(G1, i + 1, k, keys[i]) }))
    const grant = ownGrant(G1, keys[grantedEpoch], grantedEpoch)
    list.mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, grantedEpoch })])
    vi.mocked(scopesApi).mockReturnValue({
      listMyScopes: list, listScopeEpochs: vi.fn().mockResolvedValue(links), rotateScope: rotate,
    } as unknown as ReturnType<typeof scopesApi>)
    return keys
  }

  /** The caller re-grants itself, so every payload's own grant opens to that attempt's new epoch key. */
  const self: ScopeMember[] = [{ userId: ME, username: 'me', role: 'OWNER', identityPublicKey: ID_PUB, hasGrant: true }]
  const newKeyOf = (call: unknown[]) => {
    const { grants } = (call[0] as { rotateScopeRequest: { grants: { wrappedGrant: string }[] } }).rotateScopeRequest
    return Array.from(openOwn(G1, (call[0] as { rotateScopeRequest: { epoch: number } }).rotateScopeRequest.epoch, grants[0].wrappedGrant, ID.priv))
  }

  it('states the epoch it creates: the caller\'s own granted epoch + 1', async () => {
    const rotate = vi.fn().mockResolvedValue({ epoch: 3 })
    holdGroupAt(2, rotate)

    await commitRotation(G1, self)

    expect(rotate).toHaveBeenCalledTimes(1)
    expect(rotate.mock.calls[0][0].rotateScopeRequest.epoch).toBe(3)
  })

  it('on a first 409 stale_epoch re-opens its grant and commits again, from a FRESH key', async () => {
    // Another rotation committed epoch 1 first and re-granted us: the re-open finds grant 1, so the retry states 2.
    const rotate = vi.fn()
    holdGroupAt(0, rotate)
    let reopened: Uint8Array[] = []
    rotate
      .mockImplementationOnce(async () => {
        reopened = holdGroupAt(1, rotate) // the winner's re-grant, visible to our re-open
        throw stale()
      })
      .mockResolvedValueOnce({ epoch: 2 })

    expect(await commitRotation(G1, self)).toBe(2)

    expect(rotate).toHaveBeenCalledTimes(2)
    expect(rotate.mock.calls.map((c) => c[0].rotateScopeRequest.epoch)).toEqual([1, 2])
    // The refused attempt's wraps already left the device: the retry must not reuse its key.
    expect(newKeyOf(rotate.mock.calls[1])).not.toEqual(newKeyOf(rotate.mock.calls[0]))
    // Nor its buffer: a mint hoisted out of the attempt is wiped by the first attempt's `finally`, so the retry would
    // wrap an all-zero key — different from the first, and still self-consistent, so only this line catches it.
    expect(newKeyOf(rotate.mock.calls[1]).some((b) => b !== 0)).toBe(true)
    // And the retry is built on the re-opened grant: its link peels, under its own new key, to E_1.
    const retry = rotate.mock.calls[1][0].rotateScopeRequest
    const peeled = peel(G1, retry.epoch, Uint8Array.from(newKeyOf(rotate.mock.calls[1])), retry.wrappedPrev)
    expect(Array.from(peeled)).toEqual(Array.from(reopened[1]))
  })

  it('throws after exactly two commits when the second answer is still stale (our grant lags)', async () => {
    const second = stale()
    const rotate = vi.fn().mockRejectedValueOnce(stale()).mockRejectedValueOnce(second)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(second)
    expect(rotate).toHaveBeenCalledTimes(2)
  })

  it('does not retry a non-stale failure (e.g. 403) — it propagates after one commit', async () => {
    const forbidden = { response: new Response(JSON.stringify({ code: 'not_scope_owner' }), { status: 403 }) }
    const rotate = vi.fn().mockRejectedValue(forbidden)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(forbidden)
    expect(rotate).toHaveBeenCalledTimes(1)
  })
})

describe('renameGroup (cr-0097 — re-seal the name under the current epoch)', () => {
  function mockGroupForRename(rename: ReturnType<typeof vi.fn>, currentEpoch = 0, grantedEpoch = 0) {
    const key = v1.randomBytes(32) // E_grantedEpoch = the write key the caller holds
    const grant = ownGrant(G1, key, grantedEpoch)
    const list = vi.fn().mockResolvedValue([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName: sealGroupName(deriveGroupNameKey(key), G1, 0, 'Alt'), nameEpoch: 0, grantedEpoch, currentEpoch }),
    ])
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, renameGroup: rename } as unknown as ReturnType<typeof scopesApi>)
    return key
  }

  it('seals the new name under K_name of the write-key epoch and posts { encryptedName, nameEpoch }', async () => {
    const rename = vi.fn().mockResolvedValue(undefined)
    const key = mockGroupForRename(rename)

    await renameGroup(G1, 'Neu')

    expect(rename).toHaveBeenCalledTimes(1)
    const req = rename.mock.calls[0][0].renameGroupRequest
    expect(req.nameEpoch).toBe(0)
    // The posted ciphertext decrypts under K_name derived from the epoch key back to the NEW plaintext.
    expect(openGroupNameWith(deriveGroupNameKey(key), G1, req.nameEpoch, req.encryptedName)).toBe('Neu')
  })

  it('retries once on 409 stale_epoch (a concurrent rotation), then succeeds', async () => {
    const stale = { response: new Response(JSON.stringify({ code: 'stale_epoch' }), { status: 409 }) }
    const rename = vi.fn().mockRejectedValueOnce(stale).mockResolvedValueOnce(undefined)
    mockGroupForRename(rename)

    await renameGroup(G1, 'Neu')
    expect(rename).toHaveBeenCalledTimes(2) // refetch + retry once
  })

  it('does not retry on a non-stale failure (e.g. 403) — it propagates', async () => {
    const forbidden = { response: new Response(JSON.stringify({ code: 'not_scope_owner' }), { status: 403 }) }
    const rename = vi.fn().mockRejectedValue(forbidden)
    mockGroupForRename(rename)

    await expect(renameGroup(G1, 'Neu')).rejects.toBe(forbidden)
    expect(rename).toHaveBeenCalledTimes(1)
  })
})

describe('deleteGroup / restoreGroup (cr-0090 — group lifecycle data layer)', () => {
  it('posts a soft-delete then a restore to the scope lifecycle endpoints', async () => {
    const deleteFn = vi.fn().mockResolvedValue(undefined)
    const restoreFn = vi.fn().mockResolvedValue(undefined)
    vi.mocked(scopesApi).mockReturnValue({ deleteGroup: deleteFn, restoreGroup: restoreFn } as unknown as ReturnType<typeof scopesApi>)

    await deleteGroup(G1)
    expect(deleteFn).toHaveBeenCalledWith({ scopeId: G1 })

    await restoreGroup(G1)
    expect(restoreFn).toHaveBeenCalledWith({ scopeId: G1 })
  })
})

describe('listMyTenants', () => {
  it('returns the caller’s tenants (membership metadata only) for the switcher', async () => {
    const tenants = [{ tenantId: T1, name: 'Fam', domain: '127.0.0.1', role: 'OWNER' }]
    vi.mocked(scopesApi).mockReturnValue({
      listMyTenants: vi.fn().mockResolvedValue(tenants),
    } as unknown as ReturnType<typeof scopesApi>)
    expect(await listMyTenants()).toEqual(tenants)
  })
})

describe('openMyTenantIcon (the cross-tenant switcher read)', () => {
  const signedTenant = (t: MyTenant): MyTenant => {
    const r = signed({ id: t.tenantId, kind: 'TENANT', wrappedGrant: t.wrappedGrant!, grantedEpoch: t.grantedEpoch ?? 0 })
    return { ...t, granterUserId: r.granterUserId, grantSignature: r.grantSignature, granterIdentityPublicKey: r.granterIdentityPublicKey }
  }
  // Seal an icon under K_icon derived from a tenant scope key, wrap that key as the caller's OWN grant, and hand
  // both to openMyTenantIcon as a MyTenant row — it must open our grant transiently and return the encoded bytes.
  // This is the per-tenant grant-open the switcher runs once per tenant (en.spaces.tenant-scope), exercised without
  // a live backend; the end-to-end (a SECOND tenant rendering the first's icon) is the live smoke.
  function tenantWithIcon(encoded: Uint8Array): MyTenant {
    const scopeKey = v1.randomBytes(32)
    const iconKey = deriveSpaceIconKey(scopeKey)
    const sealedIcon = sealIcon(iconKey, { scopeId: T1, iconEpoch: 0 }, encoded)
    const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: T1, epoch: 0 }, v1.hybridPublic(ID.priv), scopeKey))
    return signedTenant({ tenantId: T1, name: 'Fam', domain: 'd', role: 'OWNER', status: 'ACTIVE', iconEpoch: 0, grantedEpoch: 0, wrappedGrant, sealedIcon })
  }

  it('opens the caller’s own grant and returns the encoded icon bytes', async () => {
    const encoded = new Uint8Array([1, 2, 3, 4, 5, 250, 0, 99])
    const bytes = await openMyTenantIcon(tenantWithIcon(encoded))
    expect(bytes).not.toBeNull()
    expect([...bytes!]).toEqual([...encoded])
  })

  it('returns null (→ the initial) when the tenant has no icon or the caller holds no grant', async () => {
    expect(await openMyTenantIcon({ tenantId: T1, name: 'Fam', domain: 'd', role: 'MEMBER', status: 'ACTIVE', iconEpoch: 0 })).toBeNull()
    expect(await openMyTenantIcon({ tenantId: T1, name: 'Fam', domain: 'd', role: 'MEMBER', status: 'ACTIVE', iconEpoch: 0, sealedIcon: 'c2VhbGVk', wrappedGrant: null })).toBeNull()
  })

  it('returns null (never throws) when the grant opens but the icon was sealed under a different key', async () => {
    // A foreign/mismatched sealedIcon under our real grant must fall back to the initial, not crash the switcher.
    const scopeKey = v1.randomBytes(32)
    const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: T1, epoch: 0 }, v1.hybridPublic(ID.priv), scopeKey))
    const foreign = sealIcon(deriveSpaceIconKey(v1.randomBytes(32)), { scopeId: T1, iconEpoch: 0 }, new Uint8Array([9, 9, 9]))
    const tenant: MyTenant = signedTenant({ tenantId: T1, name: 'Fam', domain: 'd', role: 'OWNER', status: 'ACTIVE', iconEpoch: 0, grantedEpoch: 0, wrappedGrant, sealedIcon: foreign })
    expect(await openMyTenantIcon(tenant)).toBeNull()
  })
})

describe('a grant opens only if its granter\'s signature verifies (ADR 0028 Decision 6)', () => {
  const foreigner = v1.generateIdentity()
  const FOREIGNER = 'f0000000-0000-4000-8000-0000000000f0'
  const serve = (rows: Row[]) => mockScopes(vi.fn().mockResolvedValue(rows), vi.fn())

  it('refuses a PERSONAL grant sealed and signed by a foreign identity — the key inside is never used', async () => {
    // The server plants a personal scope key of its own choosing: sealed to our key (which needs only our PUBLIC
    // key) and signed by an identity it controls. Opening it would put our next write under the server's key.
    const planted = v1.randomBytes(32)
    serve([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: ownGrant(P, planted, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    expect(await openScope(ScopeKind.Personal)).toBeNull()
  })

  it('control: our own self-signed personal grant opens', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: ownGrant(P, key, 0), grantedEpoch: 0 })])
    expect(Array.from((await openScope(ScopeKind.Personal))!.key)).toEqual(Array.from(key))
  })

  it('refuses a group\'s genuine grant served as the caller\'s personal scope — on the signed kind', async () => {
    // A group we created ourselves: its grant is genuine, self-signed, and sealed bound to the group's id. Served in
    // a row that says PERSONAL (same id, so the seal still opens), it must not become our personal scope.
    const key = v1.randomBytes(32)
    const genuine = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 })
    serve([{ ...genuine, kind: 'PERSONAL' }])
    expect(await openScope(ScopeKind.Personal)).toBeNull()
  })

  it('control: the same grant in its own group row opens', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 })])
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
  })

  it('refuses a shared grant whose granter key differs from that granter\'s pin', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    vi.mocked(checkAndPin).mockResolvedValue('changed')
    expect(await openScopeById(G1)).toBeNull()
    expect(checkAndPin).toHaveBeenCalledWith(FOREIGNER, pubOf(foreigner))
  })

  it('control: a shared grant whose granter key matches the pin opens', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    vi.mocked(checkAndPin).mockResolvedValue('match')
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
  })

  it('refuses a shared grant whose signature does not verify under the key it names — and pins nothing', async () => {
    const key = v1.randomBytes(32)
    const row = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })
    serve([{ ...row, granterIdentityPublicKey: pubOf(v1.generateIdentity()) }])
    expect(await openScopeById(G1)).toBeNull()
    expect(checkAndPin).not.toHaveBeenCalled() // a forged grant must not get to pin the key it names
  })

  it('refuses an unsigned grant (written before grants were signed)', async () => {
    serve([{ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, v1.randomBytes(32), 0), grantedEpoch: 0 }])
    expect(await openScopeById(G1)).toBeNull()
  })

  it('the birth producers sign: a new personal scope and a new group carry our signature over their own grant', async () => {
    const create = vi.fn().mockResolvedValue({ scopeId: 's', kind: 'PERSONAL' })
    const createGroupFn = vi.fn().mockResolvedValue({ scopeId: 'g', kind: 'GROUP' })
    mockScopes(vi.fn().mockResolvedValue([]), create, createGroupFn)
    await ensurePersonalScope()
    await createGroup('Alt')
    const p = create.mock.calls[0][0].createPersonalScopeRequest
    const g = createGroupFn.mock.calls[0][0].createGroupRequest
    const me = { userId: ME, identityPublicKeyB64: ID_PUB }
    expect(grantSignatureVerifies(ID.pub.sign, { scopeId: p.scopeId, kind: 'PERSONAL', epoch: 0 }, me, p.wrappedGrant, p.grantSignature)).toBe(true)
    expect(grantSignatureVerifies(ID.pub.sign, { scopeId: g.scopeId, kind: 'GROUP', epoch: 0 }, me, g.wrappedGrant, g.grantSignature)).toBe(true)
    // The group's creator self-tags its own membership under E_0, bound to itself (ADR 0020 move 6).
    const e0 = openOwn(g.scopeId, 0, g.wrappedGrant)
    expect(admissionVerifies(e0, 0, { scopeId: g.scopeId, kind: 'GROUP' }, ME, g)).toBe(true)
  })
})
