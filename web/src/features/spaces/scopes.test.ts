import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { httpError } from '../../test/httpError'
import { fakeApi, stubApi } from '../../test/apiMock'
import { accountKeyMaterial, myTenant } from '../../test/models'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  contentApi: vi.fn(),
  tenantsApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  accountApi: vi.fn(),
}))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn(), masterKey: vi.fn() }))
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

import { ensurePersonalScope, openPersonalScope, openScope, openScopeById, openGroupByToken, listMyTenants, createGroup, listMyGroups, rotateGroupForRemoval, commitRotation, renameGroup, deleteGroup, restoreGroup, openMyTenantIcon, openGroupIcon, revokeScopeShares } from './scopes'
import { ScopeKind, type MyTenant, type ScopeEpochLink, type ScopeMember, type ScopeSummary, type ScopesApi } from '../../api/generated/scopes'
import type { ContentApi } from '../../api/generated/content'
import { scopesApi, contentApi, tenantsApi, accountApi } from '../../api/client'
import { createFolder } from '../../core/node/tree'
import { checkAndPin } from '../account/identityPins'
import { identityPrivate, masterKey } from '../../app/vault'
import { epochLinkContext } from './epochs'
import { deriveGroupNameKey, sealGroupName, openGroupNameWith } from './inviteName'
import { deriveSpaceIconKey, sealIcon } from './spaceIcon'
import { forgetGroupNamesSeen, groupNameSeen } from './groupNamesSeen'
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

/** What a test names of a listed scope; the rest comes from {@link scopeRow}. */
type Row = Pick<ScopeSummary, 'id' | 'kind' | 'wrappedGrant'> & Partial<ScopeSummary>
/** A listed scope as the server serves it, unsigned: in tenant `t`, owned by the caller, name and icon at epoch 0,
 *  the caller's grant at the scope's current epoch (epoch 0 unless the row says otherwise). */
const scopeRow = (row: Row): ScopeSummary => ({
  tenantId: 't', role: 'OWNER', nameEpoch: 0, iconEpoch: 0, grantedEpoch: 0, currentEpoch: row.grantedEpoch ?? 0, ...row,
})
/** A row as the server serves the caller's grant: signed by `granter` (the caller itself by default) over exactly
 *  this grant — its scope, kind and epoch, the caller as recipient (ADR 0028 Decision 6). */
type Signed = { granterUserId: string; grantSignature: string; granterIdentityPublicKey: string }
function signed(row: Row, granter: { userId: string; identity: v1.Identity } = { userId: ME, identity: ID }): ScopeSummary & Signed {
  const full = scopeRow(row)
  const scope = { scopeId: full.id, kind: full.kind as v1.ScopeKind, epoch: full.grantedEpoch }
  return {
    ...full,
    granterUserId: granter.userId,
    grantSignature: signGrant(granter.identity.priv.sign, scope, { userId: ME, identityPublicKeyB64: ID_PUB }, full.wrappedGrant),
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

/** The caller's scope listing, as the server serves it. */
const listing = (rows: ScopeSummary[]) => vi.fn<ScopesApi['listMyScopes']>().mockResolvedValue(rows)

function mockScopes(
  list: Mock<ScopesApi['listMyScopes']>,
  create = vi.fn<ScopesApi['createPersonalScope']>(),
  createGroupFn = vi.fn<ScopesApi['createGroup']>(),
) {
  fakeApi(scopesApi, { listMyScopes: list, createPersonalScope: create, createGroup: createGroupFn })
}

// The account's Master Key — the vault hands out a fresh copy per call. It names this device's trust-state marks
// (ADR 0028 Decision 7), of which the per-scope epoch floor is one (ADR 0024 Decision 6).
const MK = v1.randomBytes(32)

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  globalThis.indexedDB = new IDBFactory() // a device that has held no epoch yet
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ME }) })
  // Default: every contact is new/unchanged → the rotation re-grants them (TOFU). Overridden per test.
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
})

describe('ensurePersonalScope', () => {
  it('births a personal scope (operator-blind grant that opens back to a 32-byte key) when none exists', async () => {
    const list = listing([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })])
    const create = vi.fn<ScopesApi['createPersonalScope']>().mockResolvedValue({ scopeId: 's', kind: 'PERSONAL' })
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
    const list = listing([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' })])
    const create = vi.fn<ScopesApi['createPersonalScope']>()
    mockScopes(list, create)

    expect(await ensurePersonalScope()).toBe(false)
    expect(create).not.toHaveBeenCalled()
  })
})

describe('openPersonalScope', () => {
  it('opens the personal grant back to the scope key', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = ownGrant(P, scopeKey, 0)
    const list = listing([
      signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }),
      signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: grant, grantedEpoch: 0 }),
    ])
    mockScopes(list)

    const opened = await openPersonalScope()
    expect(opened).not.toBeNull()
    expect(Array.from(opened!)).toEqual(Array.from(scopeKey))
  })

  it('returns null when there is no personal scope', async () => {
    mockScopes(listing([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })]))
    expect(await openPersonalScope()).toBeNull()
  })
})

describe('openScope', () => {
  it('opens the TENANT grant back to a 32-byte scope key — open-only, never POSTs (no client birth)', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = ownGrant(TN, scopeKey, 0)
    const list = listing([
      signed({ id: TN, kind: 'TENANT', tenantId: 't', wrappedGrant: grant, grantedEpoch: 0 }),
      signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' }),
    ])
    const create = vi.fn<ScopesApi['createPersonalScope']>()
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
    mockScopes(listing([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' })]))
    expect(await openScope(ScopeKind.Tenant)).toBeNull()
  })
})

describe('createGroup', () => {
  it('posts a self-grant + a name sealed under K_name_0 derived from the birth K_SC (operator-blind — only ciphertext leaves)', async () => {
    const list = listing([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })])
    const createGroupFn = vi.fn<ScopesApi['createGroup']>().mockResolvedValue({ scopeId: G1, kind: 'GROUP' })
    mockScopes(list, undefined, createGroupFn)

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
    const list = listing([
      signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }),
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 }),
    ])
    mockScopes(list)

    expect(await listMyGroups()).toEqual([
      { scopeId: G1, tenantId: 't', name: 'Reise-Crew', icon: null, role: 'OWNER', deletedAt: null, purgeAfter: null, compromiseRotation: null },
    ])
  })

  it('remembers each opened name for this page, and a later read that cannot open the group keeps it (inbox #280)', async () => {
    forgetGroupNamesSeen()
    const key = v1.randomBytes(32)
    const encryptedName = sealGroupName(deriveGroupNameKey(key), G1, 0, 'Reise-Crew')
    const row = (wrappedGrant: string) => signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant, encryptedName, nameEpoch: 0, grantedEpoch: 0 })
    mockScopes(vi.fn().mockResolvedValue([row(ownGrant(G1, key, 0))]), vi.fn())
    await listMyGroups()
    expect(groupNameSeen(G1)).toBe('Reise-Crew')

    // After a compromise rotation the grant is wrapped to the identity this account replaced: the group drops out
    // of the list, and the name this page opened before is still the label the security panel shows.
    mockScopes(vi.fn().mockResolvedValue([row('AQ==')]), vi.fn())
    expect(await listMyGroups()).toEqual([])
    expect(groupNameSeen(G1)).toBe('Reise-Crew')
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
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, encryptedName, nameEpoch: 0, grantedEpoch: 1, iconEpoch: 0, sealedIcon }),
    ])
    const epochs = vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([{ epoch: 1, wrappedPrev }])
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: epochs })

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
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, nameEpoch: 0, grantedEpoch: 1, iconEpoch: 0, sealedIcon }),
      signed({ id: G2, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, nameEpoch: 0, grantedEpoch: 0 }), // no icon
    ])
    const epochs = vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([{ epoch: 1, wrappedPrev }])
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: epochs })

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
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 }),
    ])
    mockScopes(list)

    const opened = await openScopeById(G1)
    expect(opened).not.toBeNull()
    expect(opened!.kind).toBe('GROUP')
    expect(opened!.name).toBe('Reise-Crew')
    expect(Array.from(opened!.key)).toEqual(Array.from(key))
  })

  it('returns null when the caller holds no scope with that id', async () => {
    mockScopes(listing([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })]))
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
    mockScopes(listing([groupRow(G1, 'Familie Süd'), groupRow(G2, 'Reise 2026')]))

    const opened = await openGroupByToken('familie-sued') // ü → ue
    expect(opened?.scopeId).toBe(G1)
    expect(opened?.name).toBe('Familie Süd')
  })

  it('resolves an opaque scopeId token directly (a legacy or collision-fallback link)', async () => {
    const id = '0264addd-1787-40bc-bccf-811d37bf6989'
    mockScopes(listing([groupRow(id, 'Familie Süd')]))
    expect((await openGroupByToken(id))?.scopeId).toBe(id)
  })

  it('resolves nothing for an ambiguous slug (two groups share it — groups have no server-side slug uniqueness)', async () => {
    mockScopes(listing([groupRow(G1, 'Familie Süd'), groupRow(G2, 'Familie Sued')]))
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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })

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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
    const good = admittedMember(B, v1.generateIdentity(), e0)

    await commitRotation(G1, [good])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([B])
    expect(payload.admissions.map((a: { admissionId: string }) => a.admissionId)).toEqual([good.admissionId])
    expect(payload.droppedAdmissions).toEqual([])
  })

  it('a TENANT rotation carries its open member invites forward too — and drops a forged one', async () => {
    const e0 = v1.randomBytes(32)
    const list = listing([signed({ id: T1, kind: 'TENANT', tenantId: T1, wrappedGrant: ownGrant(T1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
    const genuine = tagAdmission(e0, { scopeId: T1, kind: 'TENANT' }, 0, null)
    const forged = { ...tagAdmission(v1.randomBytes(32), { scopeId: T1, kind: 'TENANT' }, 0, null) }
    const expires = new Date('2026-12-01T00:00:00Z')
    stubApi(tenantsApi, {
      listMemberInvites: [
        { inviteId: 'i1', email: 'a@x', inviteStatus: 'ACTIVE', inviteExpiresAt: expires, ...genuine },
        { inviteId: 'i2', email: 'b@x', inviteStatus: 'ACTIVE', inviteExpiresAt: expires, ...forged },
      ],
    })

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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })

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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
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
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[grantedEpoch], grantedEpoch), grantedEpoch, currentEpoch })])
    const links: ScopeEpochLink[] = keys.slice(1).map((k, i) => ({ epoch: i + 1, wrappedPrev: linkUnder(G1, i + 1, k, keys[i]) }))
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links) })
    const createNode = vi.fn<ContentApi['createNode']>(async ({ createNodeRequest }) => ({ nodeId: createNodeRequest.nodeId }))
    fakeApi(contentApi, { createNode })
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

describe('no device seals under an epoch below the highest it has held for the scope (ADR 0024 Decision 6, the floor)', () => {
  /** Serve G1 with our grant at `grantedEpoch` and the scope's `currentEpoch`, the chain-links down to E_0 under
   *  `keys`, and a rotation endpoint — the lever is exactly what the server says, fixed per call. */
  function serveG1(keys: Uint8Array[], grantedEpoch: number, currentEpoch: number) {
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[grantedEpoch], grantedEpoch), grantedEpoch, currentEpoch })])
    const links: ScopeEpochLink[] = keys.slice(1, grantedEpoch + 1).map((k, i) => ({ epoch: i + 1, wrappedPrev: linkUnder(G1, i + 1, k, keys[i]) }))
    const rotateScope = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: currentEpoch + 1 }) // a server that takes whatever it is sent
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links), rotateScope })
    const createNode = vi.fn<ContentApi['createNode']>(async ({ createNodeRequest }) => ({ nodeId: createNodeRequest.nodeId }))
    fakeApi(contentApi, { createNode })
    return { createNode, rotateScope }
  }
  async function openAndCreate() {
    const opened = await openScopeById(G1)
    if (opened) await createFolder(G1, opened.epochs, 'Neu')
    return opened
  }
  const self: ScopeMember[] = [{ userId: ME, username: 'me', role: 'OWNER', identityPublicKey: ID_PUB, hasGrant: true }]

  it('held epoch 1, then served its epoch-0 grant with a matching current epoch: awaiting the current key, nothing is written', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    expect((await openScopeById(G1))?.epochs.current).toBe(1) // this device has now held epoch 1
    const { createNode } = serveG1(keys, 0, 0) // the server rolls the scope back to a grant a removed member holds
    expect(await openAndCreate()).toBeNull() // the awaiting-access state: no key to write under
    expect(createNode).not.toHaveBeenCalled()
  })

  it('control: a device that never held epoch 1 opens the very same epoch-0 grant (the null above is the floor, not the grant)', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    const { createNode } = serveG1(keys, 0, 0)
    expect((await openAndCreate())?.epochs.current).toBe(0)
    expect(createNode).toHaveBeenCalledTimes(1)
  })

  it('control: served epoch 1 again, it writes under epoch 1', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    await openScopeById(G1)
    const { createNode } = serveG1(keys, 1, 1)
    expect((await openAndCreate())?.epochs.current).toBe(1)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ epoch: 1 })
  })

  it('a rotation is not built on a grant below the floor either', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    await openScopeById(G1)
    const { rotateScope } = serveG1(keys, 0, 0)
    await expect(commitRotation(G1, self)).rejects.toThrow('cannot open scope to rotate')
    expect(rotateScope).not.toHaveBeenCalled()
  })

  it('a rotation this device made raises the floor to the epoch it created', async () => {
    const keys = [v1.randomBytes(32)]
    serveG1(keys, 0, 0)
    expect(await commitRotation(G1, self)).toBe(1)
    const { createNode } = serveG1(keys, 0, 0) // the server withholds the rotation this device itself committed
    expect(await openAndCreate()).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
  })
})

describe('commitRotation (ADR 0024 §4 — the rotation states the epoch it creates)', () => {
  const stale = () => httpError(409, 'stale_epoch')

  /** The caller's grant at `grantedEpoch`, with the chain-links down to E_0 — what `openScopeById` peels. */
  function holdGroupAt(grantedEpoch: number, rotate: Mock<ScopesApi['rotateScope']>, list = vi.fn<ScopesApi['listMyScopes']>()): Uint8Array[] {
    const keys = Array.from({ length: grantedEpoch + 1 }, () => v1.randomBytes(32))
    const links: ScopeEpochLink[] = keys.slice(1).map((k, i) => ({ epoch: i + 1, wrappedPrev: linkUnder(G1, i + 1, k, keys[i]) }))
    const grant = ownGrant(G1, keys[grantedEpoch], grantedEpoch)
    list.mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, grantedEpoch })])
    fakeApi(scopesApi, {
      listMyScopes: list, listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links), rotateScope: rotate,
    })
    return keys
  }

  /** The caller re-grants itself, so every payload's own grant opens to that attempt's new epoch key. */
  const self: ScopeMember[] = [{ userId: ME, username: 'me', role: 'OWNER', identityPublicKey: ID_PUB, hasGrant: true }]
  const newKeyOf = (call: unknown[]) => {
    const { grants } = (call[0] as { rotateScopeRequest: { grants: { wrappedGrant: string }[] } }).rotateScopeRequest
    return Array.from(openOwn(G1, (call[0] as { rotateScopeRequest: { epoch: number } }).rotateScopeRequest.epoch, grants[0].wrappedGrant, ID.priv))
  }

  it('states the epoch it creates: the caller\'s own granted epoch + 1', async () => {
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 3 })
    holdGroupAt(2, rotate)

    await commitRotation(G1, self)

    expect(rotate).toHaveBeenCalledTimes(1)
    expect(rotate.mock.calls[0][0].rotateScopeRequest.epoch).toBe(3)
  })

  it('on a first 409 stale_epoch re-opens its grant and commits again, from a FRESH key', async () => {
    // Another rotation committed epoch 1 first and re-granted us: the re-open finds grant 1, so the retry states 2.
    const rotate = vi.fn<ScopesApi['rotateScope']>()
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
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValueOnce(stale()).mockRejectedValueOnce(second)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(second)
    expect(rotate).toHaveBeenCalledTimes(2)
  })

  it('does not retry a non-stale failure (e.g. 403) — it propagates after one commit', async () => {
    const forbidden = httpError(403, 'not_scope_owner')
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValue(forbidden)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(forbidden)
    expect(rotate).toHaveBeenCalledTimes(1)
  })
})

describe('renameGroup (cr-0097 — re-seal the name under the current epoch)', () => {
  function mockGroupForRename(rename: Mock<ScopesApi['renameGroup']>, currentEpoch = 0, grantedEpoch = 0) {
    const key = v1.randomBytes(32) // E_grantedEpoch = the write key the caller holds
    const grant = ownGrant(G1, key, grantedEpoch)
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName: sealGroupName(deriveGroupNameKey(key), G1, 0, 'Alt'), nameEpoch: 0, grantedEpoch, currentEpoch }),
    ])
    fakeApi(scopesApi, { listMyScopes: list, renameGroup: rename })
    return key
  }

  it('seals the new name under K_name of the write-key epoch and posts { encryptedName, nameEpoch }', async () => {
    const rename = vi.fn<ScopesApi['renameGroup']>().mockResolvedValue(undefined)
    const key = mockGroupForRename(rename)

    await renameGroup(G1, 'Neu')

    expect(rename).toHaveBeenCalledTimes(1)
    const req = rename.mock.calls[0][0].renameGroupRequest
    expect(req.nameEpoch).toBe(0)
    // The posted ciphertext decrypts under K_name derived from the epoch key back to the NEW plaintext.
    expect(openGroupNameWith(deriveGroupNameKey(key), G1, req.nameEpoch!, req.encryptedName)).toBe('Neu')
  })

  it('retries once on 409 stale_epoch (a concurrent rotation), then succeeds', async () => {
    const stale = httpError(409, 'stale_epoch')
    const rename = vi.fn<ScopesApi['renameGroup']>().mockRejectedValueOnce(stale).mockResolvedValueOnce(undefined)
    mockGroupForRename(rename)

    await renameGroup(G1, 'Neu')
    expect(rename).toHaveBeenCalledTimes(2) // refetch + retry once
  })

  it('does not retry on a non-stale failure (e.g. 403) — it propagates', async () => {
    const forbidden = httpError(403, 'not_scope_owner')
    const rename = vi.fn<ScopesApi['renameGroup']>().mockRejectedValue(forbidden)
    mockGroupForRename(rename)

    await expect(renameGroup(G1, 'Neu')).rejects.toBe(forbidden)
    expect(rename).toHaveBeenCalledTimes(1)
  })
})

describe('deleteGroup / restoreGroup (cr-0090 — group lifecycle data layer)', () => {
  it('posts a soft-delete then a restore to the scope lifecycle endpoints', async () => {
    const { deleteGroup: deleteFn, restoreGroup: restoreFn } = stubApi(scopesApi, { deleteGroup: undefined, restoreGroup: undefined })

    await deleteGroup(G1)
    expect(deleteFn).toHaveBeenCalledWith({ scopeId: G1 })

    await restoreGroup(G1)
    expect(restoreFn).toHaveBeenCalledWith({ scopeId: G1 })
  })
})

describe('listMyTenants', () => {
  it('returns the caller’s tenants (membership metadata only) for the switcher', async () => {
    const tenants: MyTenant[] = [myTenant({ tenantId: T1 })]
    stubApi(scopesApi, { listMyTenants: tenants })
    expect(await listMyTenants()).toEqual(tenants)
  })
})

describe('revokeScopeShares (ADR 0075 point 5)', () => {
  it('revokeScopeShares turns off every link of the scope and resolves how many it turned off', async () => {
    const { revokeScopeShares: revoke } = stubApi(scopesApi, { revokeScopeShares: { revoked: 3 } })

    await expect(revokeScopeShares(G1)).resolves.toBe(3)
    expect(revoke).toHaveBeenCalledWith({ scopeId: G1 })
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
    return signedTenant(myTenant({ tenantId: T1, iconEpoch: 0, grantedEpoch: 0, wrappedGrant, sealedIcon }))
  }

  it('opens the caller’s own grant and returns the encoded icon bytes', async () => {
    const encoded = new Uint8Array([1, 2, 3, 4, 5, 250, 0, 99])
    const bytes = await openMyTenantIcon(tenantWithIcon(encoded))
    expect(bytes).not.toBeNull()
    expect([...bytes!]).toEqual([...encoded])
  })

  it('returns null (→ the initial) when the tenant has no icon or the caller holds no grant', async () => {
    expect(await openMyTenantIcon(myTenant({ tenantId: T1, role: 'MEMBER' }))).toBeNull()
    expect(await openMyTenantIcon(myTenant({ tenantId: T1, role: 'MEMBER', sealedIcon: 'c2VhbGVk', wrappedGrant: null }))).toBeNull()
  })

  it('returns null (never throws) when the grant opens but the icon was sealed under a different key', async () => {
    // A foreign/mismatched sealedIcon under our real grant must fall back to the initial, not crash the switcher.
    const scopeKey = v1.randomBytes(32)
    const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: T1, epoch: 0 }, v1.hybridPublic(ID.priv), scopeKey))
    const foreign = sealIcon(deriveSpaceIconKey(v1.randomBytes(32)), { scopeId: T1, iconEpoch: 0 }, new Uint8Array([9, 9, 9]))
    const tenant: MyTenant = signedTenant(myTenant({ tenantId: T1, iconEpoch: 0, grantedEpoch: 0, wrappedGrant, sealedIcon: foreign }))
    expect(await openMyTenantIcon(tenant)).toBeNull()
  })
})

describe('a grant opens only if its granter\'s signature verifies (ADR 0028 Decision 6)', () => {
  const foreigner = v1.generateIdentity()
  const FOREIGNER = 'f0000000-0000-4000-8000-0000000000f0'
  const serve = (rows: ScopeSummary[]) => mockScopes(listing(rows))

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
    serve([scopeRow({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, v1.randomBytes(32), 0), grantedEpoch: 0 })])
    expect(await openScopeById(G1)).toBeNull()
  })

  it('the birth producers sign: a new personal scope and a new group carry our signature over their own grant', async () => {
    const create = vi.fn<ScopesApi['createPersonalScope']>().mockResolvedValue({ scopeId: 's', kind: 'PERSONAL' })
    const createGroupFn = vi.fn<ScopesApi['createGroup']>().mockResolvedValue({ scopeId: 'g', kind: 'GROUP' })
    mockScopes(listing([]), create, createGroupFn)
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

describe('a grant verifies under the key its granter signed it with, not the granter\'s current one (inbox #233)', () => {
  // The rotator R signed our grant with its OLD identity, then rotated its account keys (ADR 0060). The server serves
  // the key as of the grant (ADR 0028 § What is still open); this pins what the client does with each served key,
  // with real signatures. The client is unchanged by #233 — the backend's
  // `RotationSliceTest.aGrantTheRotatorSignedIsStillServedWithTheKeyThatSignedIt` is the half that was red.
  const R = 'a0000000-0000-4000-8000-0000000000a1'
  const rOld = v1.generateIdentity()
  const rNew = v1.generateIdentity()
  const self: ScopeMember[] = [{ userId: ME, username: 'me', role: 'OWNER', identityPublicKey: ID_PUB, hasGrant: true }]

  /** Our G1 grant at epoch 0, signed by R's old identity, served beside `servedKey`; plus a rotation endpoint. */
  function serveRGrant(key: Uint8Array, servedKey: string) {
    const row = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: R, identity: rOld })
    const rotateScope = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, {
      listMyScopes: listing([{ ...row, granterIdentityPublicKey: servedKey }]),
      listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([]),
      rotateScope,
    })
    return { rotateScope }
  }

  it('served the key that signed it, with our pin still on that key: the grant opens', async () => {
    const key = v1.randomBytes(32)
    serveRGrant(key, pubOf(rOld))
    vi.mocked(checkAndPin).mockResolvedValue('match')
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
    expect(checkAndPin).toHaveBeenCalledWith(R, pubOf(rOld))
  })

  it('and the owner holding it can therefore complete the covering rotation', async () => {
    const { rotateScope } = serveRGrant(v1.randomBytes(32), pubOf(rOld))
    vi.mocked(checkAndPin).mockResolvedValue('match')
    expect(await commitRotation(G1, self)).toBe(1)
    expect(rotateScope).toHaveBeenCalledTimes(1)
  })

  it('the same row, once our pin moved to R\'s new key before the covering rotation reached us: refused', async () => {
    // We accepted R's new key first. The retired key is never trusted again, so this grant stays refused
    // (`granter_key_changed`) until a rotation replaces it — which is why accepting drains the rotation worker first.
    serveRGrant(v1.randomBytes(32), pubOf(rOld))
    vi.mocked(checkAndPin).mockResolvedValue('changed')
    expect(await openScopeById(G1)).toBeNull()
    expect(checkAndPin).toHaveBeenCalledWith(R, pubOf(rOld))
  })

  it('served R\'s CURRENT key, the signature fails before our pin is ever read — even a pin that accepted it', async () => {
    // The lock #233 removed: the served key is not the one that signed, so `bad_signature` precedes the TOFU gate.
    serveRGrant(v1.randomBytes(32), pubOf(rNew))
    vi.mocked(checkAndPin).mockResolvedValue('match')
    expect(await openScopeById(G1)).toBeNull()
    expect(checkAndPin).not.toHaveBeenCalled()
  })

  it('and served R\'s current key, the owner holding it cannot rotate the scope at all', async () => {
    const { rotateScope } = serveRGrant(v1.randomBytes(32), pubOf(rNew))
    vi.mocked(checkAndPin).mockResolvedValue('match')
    await expect(commitRotation(G1, self)).rejects.toThrow('cannot open scope to rotate')
    expect(rotateScope).not.toHaveBeenCalled()
  })
})
