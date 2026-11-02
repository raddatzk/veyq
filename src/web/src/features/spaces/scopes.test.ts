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
// The anchor gate (the pin store and the key-chain walk) is exercised in identityPins.test.ts and, end to end on the
// recipient side, in grantTrust.test.ts; here we drive its answers directly so the rotation re-grant's per-member gate
// and the grant's granter check are under our control (default: first use → proceed).
vi.mock('../account/identityPins', () => ({
  anchorIdentity: vi.fn(),
  anchorChainKey: vi.fn(),
  IdentityUnanchoredError: class IdentityUnanchoredError extends Error {
    constructor(public userId: string) {
      super(`identity key not anchored for ${userId}`)
    }
  },
}))
// Who is anchored in a tenant (the join records) is tenantTrust.test.ts's; here a tenant read is a token per tenant id,
// which the anchor checks above must receive — so a wrap or a grant judged outside its tenant is red.
vi.mock('../account/tenantTrust', () => ({ loadTenantView: vi.fn(), cachedTenantView: vi.fn(), tenantViewOfScope: vi.fn() }))
// The scope-level re-seal a rotation stages before its commit (ADR 0115 decision 2) is scopeRekey.test.ts's; here it is a
// recorder, so what a rotation hands it (the re-key id, the old and the new key) is checked against the
// commit it precedes.
vi.mock('./scopeRekey', () => ({ stageScopeLevelRekey: vi.fn(), isRestageable: vi.fn(), REKEY_RESTAGES: 2 }))
// The node re-key is rekeyScope.test.ts's; here only that a bulk close starts it, for which scope, and when.
vi.mock('../../core/node/rekey/rekeyScope', async (orig) => ({
  ...(await orig<typeof import('../../core/node/rekey/rekeyScope')>()), rekeyScope: vi.fn(async () => ({ done: true, committed: 0 })),
}))

import { ensurePersonalScope, openPersonalScope, openScope, openScopeById, openGroupByToken, listMyTenants, createGroup, listMyGroups, rotateGroupForRemoval, commitRotation, ROTATION_REBUILDS, renameGroup, wrapGroupNameKeyForInvitee, deleteGroup, restoreGroup, openMyTenantIcon, openGroupIcon, revokeScopeShares, rekeyClosedScopes, openPlanGrantKey, isUnreadableGrant } from './scopes'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { ScopeKind, type MyTenant, type OpenInvite, type ScopeSuccession, type ScopeMember, type ScopeSummary, type ScopesApi } from '../../api/generated/scopes'
import type { ContentApi } from '../../api/generated/content'
import { scopesApi, contentApi, accountApi } from '../../api/client'
import { createFolder } from '../../core/node/tree'
import { isRestageable, stageScopeLevelRekey } from './scopeRekey'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { anchorChainKey, anchorIdentity } from '../account/identityPins'
import { cachedTenantView, loadTenantView, tenantViewOfScope, type TenantView } from '../account/tenantTrust'
import { identityPrivate, masterKey } from '../../app/vault'
import { groupNameContext, sealGroupName, openGroupNameWith } from './inviteName'
import { iconContext, sealIcon } from './spaceIcon'
import { openValue, openValueKey, rewrapValueKey, sealValue } from './valueKey'
import { forgetGroupNamesSeen, groupNameSeen } from './groupNamesSeen'
import { grantSignatureVerifies, recipientIdentityDigest, signGrant } from './grant'
import * as invite from '@veyq/crypto/invite'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

// A real identity for the test; the mocked vault hands out a FRESH copy each call (the data layer zeroes
// the copy it receives), while we keep this master to verify the grants it produces.
const ID = v1.generateIdentity()
const copyPriv = () => ({ kemSeed: ID.priv.kemSeed.slice(), sign: ID.priv.sign.slice() })
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

/** A group's name as its writer seals it (ADR 0115): under a fresh value key at `nameEpoch` (the write's epoch, `epoch`
 *  by default), that key wrapped under the scope key [key] of `epoch` — the row's three name fields. */
function groupNameOf(scopeId: string, key: Uint8Array, epoch: number, name: string, nameEpoch = epoch) {
  const sealed = sealValue({ epoch, key }, groupNameContext(scopeId, nameEpoch), (nameKey) =>
    fromB64std(sealGroupName(nameKey, scopeId, nameEpoch, name)))
  return { encryptedName: sealed.sealed, wrappedNameValueKey: sealed.wrapped, nameEpoch }
}
/** A space's icon as its writer seals it (ADR 0115): the row's three icon fields, the wrap under [key] of `epoch`. */
function spaceIconOf(scopeId: string, key: Uint8Array, epoch: number, image: Uint8Array, iconEpoch = epoch) {
  const address = { scopeId, iconEpoch }
  const sealed = sealValue({ epoch, key }, iconContext(address), (iconKey) => fromB64std(sealIcon(iconKey, address, image)))
  return { sealedIcon: sealed.sealed, wrappedIconValueKey: sealed.wrapped, iconEpoch }
}
/** A member's read of a group name a request carries: its value key from the wrap under [key] of `epoch`, then the name. */
const readGroupName = (
  scopeId: string, key: Uint8Array, epoch: number, row: { encryptedName: string; wrappedNameValueKey: string; nameEpoch: number },
) => openValue({ epoch, key }, groupNameContext(scopeId, row.nameEpoch), row.wrappedNameValueKey, (nameKey) =>
  openGroupNameWith(nameKey, scopeId, row.nameEpoch, row.encryptedName))

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
  v1.openAs({ purpose: 'scope-grant', scopeId, epoch }, v1.hybridSecret(priv), fromB64std(blob))
/** The succession statement stored for `epoch` (ADR 0115 decision 5): `key`'s succession public key, signed by `prev`'s
 *  succession key — what a rotation from `prev` to `key` publishes. It opens nothing. */
function linkUnder(scopeId: string, epoch: number, key: Uint8Array, prev: Uint8Array): ScopeSuccession {
  const successionPublicKey = v1.successionKeyPair(key).publicKey
  return {
    epoch, successionPublicKey: b64std(successionPublicKey),
    successionSignature: b64std(v1.signSuccession(prev, { scopeId, epoch, successionPublicKey })),
  }
}
/** Whether a rotation request's succession statement proves [newKey] at [epoch] from [oldKey]. */
const provesSuccession = (scopeId: string, epoch: number, oldKey: Uint8Array, newKey: Uint8Array, r: { successionPublicKey: string; successionSignature: string }) =>
  b64std(v1.successionKeyPair(newKey).publicKey) === r.successionPublicKey &&
  v1.verifySuccession(v1.successionKeyPair(oldKey).publicKey, { scopeId, epoch, successionPublicKey: fromB64std(r.successionPublicKey) }, fromB64std(r.successionSignature))

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

/** The token a read of `tenantId` stands as — one per tenant id, so `toHaveBeenCalledWith` tells the tenants apart. */
const views = new Map<string, TenantView>()
const tenantView = (tenantId: string): TenantView => {
  if (!views.has(tenantId)) views.set(tenantId, { tenantId, members: new Map(), available: true, standing: async () => ({ kind: 'root' }) })
  return views.get(tenantId)!
}

beforeEach(() => {
  vi.mocked(loadTenantView).mockImplementation(async (tenantId) => tenantView(tenantId))
  vi.mocked(cachedTenantView).mockImplementation(async (tenantId) => tenantView(tenantId))
  vi.mocked(tenantViewOfScope).mockImplementation(async () => tenantView('t'))
  vi.clearAllMocks()
  vi.mocked(stageScopeLevelRekey).mockResolvedValue(undefined)
  vi.mocked(isRestageable).mockImplementation(async (e) =>
    (await isCodedError(e, HttpStatus.conflict, 'rekey_parts_stale')) || isCodedError(e, HttpStatus.conflict, 'rekey_parts_missing'))
  vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  globalThis.indexedDB = new IDBFactory() // a device that has held no epoch yet
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ME }) })
  // Default: every contact is new/unchanged → the rotation re-grants them (TOFU), and a granter's key opens. Overridden
  // per test.
  vi.mocked(anchorIdentity).mockResolvedValue('firstUse')
  vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'firstUse', retired: false })
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
  it('posts a self-grant + a name sealed under a value key of its own, wrapped under the birth K_SC (operator-blind — only ciphertext leaves)', async () => {
    const list = listing([signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' })])
    const createGroupFn = vi.fn<ScopesApi['createGroup']>().mockResolvedValue({ scopeId: G1, kind: 'GROUP' })
    mockScopes(list, undefined, createGroupFn)

    const scopeId = await createGroup('Familie')
    expect(scopeId).toBe(G1)
    expect(createGroupFn).toHaveBeenCalledTimes(1)

    // Recover K_SC (= E_0) from the posted grant; the posted encryptedName must decrypt back to the plaintext name
    // under the value key its wrap carries (ADR 0115), NOT under K_SC directly.
    const { scopeId: posted, encryptedName, wrappedNameValueKey, wrappedGrant } = createGroupFn.mock.calls[0][0].createGroupRequest
    expect(posted).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/) // client-minted UUIDv4
    const key = openOwn(posted, 0, wrappedGrant)
    expect(key.length).toBe(32)
    expect(readGroupName(posted, key, 0, { encryptedName, wrappedNameValueKey, nameEpoch: 0 })).toBe('Familie')
    expect(() => openGroupNameWith(key, posted, 0, encryptedName)).toThrow(v1.OpenRefused) // NOT sealed under K_SC directly
    // the wrap is bound to this group's name: at another scope's slot it opens nothing
    expect(() => openValueKey({ epoch: 0, key }, groupNameContext(G2, 0), wrappedNameValueKey)).toThrow(v1.OpenRefused)
  })
})

describe('listMyGroups', () => {
  it('decrypts each group’s name through its value key\'s wrap under the scope key on-device (the row carries only ciphertext)', async () => {
    const key = v1.randomBytes(32) // E_0
    const grant = ownGrant(G1, key, 0)
    const list = listing([
      signed({ id: T, kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }),
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, ...groupNameOf(G1, key, 0, 'Reise-Crew'), grantedEpoch: 0 }),
    ])
    mockScopes(list)

    expect(await listMyGroups()).toEqual([
      { scopeId: G1, tenantId: 't', name: 'Reise-Crew', icon: null, role: 'OWNER', deletedAt: null, purgeAfter: null, compromiseRotation: null, awaitingKey: false },
    ])
  })

  it('lists a group whose grant is behind as awaiting the new key — no name or icon opens, only the one this page remembers (ADR 0115 decision 8)', async () => {
    forgetGroupNamesSeen()
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32) // the rotation's new key, which this member is not granted yet
    const name = groupNameOf(G1, e1, 1, 'Reise-Crew')
    const icon = spaceIconOf(G1, e1, 1, new Uint8Array([1, 2, 3]))
    const behind = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), ...name, ...icon, grantedEpoch: 0, currentEpoch: 1 })
    mockScopes(listing([behind]))
    expect(await listMyGroups()).toEqual([
      { scopeId: G1, tenantId: 't', name: '', icon: null, role: 'OWNER', deletedAt: null, purgeAfter: null, compromiseRotation: null, awaitingKey: true },
    ])
    // the remembered label, once this page opened the name under a key it held
    const current = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e1, 1), ...name, grantedEpoch: 1 })
    mockScopes(listing([current]))
    expect((await listMyGroups())[0]).toMatchObject({ name: 'Reise-Crew', awaitingKey: false }) // control: the current grant opens it
    mockScopes(listing([behind]))
    expect((await listMyGroups())[0]).toMatchObject({ name: 'Reise-Crew', icon: null, awaitingKey: true })
  })

  it('remembers each opened name for this page, and a later read that cannot open the group keeps it (inbox #280)', async () => {
    forgetGroupNamesSeen()
    const key = v1.randomBytes(32)
    const name = groupNameOf(G1, key, 0, 'Reise-Crew')
    const row = (wrappedGrant: string) => signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant, ...name, grantedEpoch: 0 })
    mockScopes(vi.fn().mockResolvedValue([row(ownGrant(G1, key, 0))]), vi.fn())
    await listMyGroups()
    expect(groupNameSeen(G1)).toBe('Reise-Crew')

    // After a compromise rotation the grant is wrapped to the identity this account replaced: the group drops out
    // of the list, and the name this page opened before is still the label the security panel shows.
    mockScopes(vi.fn().mockResolvedValue([row('AQ==')]), vi.fn())
    expect(await listMyGroups()).toEqual([])
    expect(groupNameSeen(G1)).toBe('Reise-Crew')
  })

  it('opens a rotated group\'s name and icon under its one key — the rotation re-wrapped their value keys and left them; a wrap left under the older key opens nothing (ADR 0115)', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const grant1 = ownGrant(G1, e1, 1)
    const image = new Uint8Array([9, 8, 7, 6, 5, 4, 3, 2, 1])
    // written at epoch 0, under E_0; the rotation to epoch 1 re-wraps both value keys under E_1 and leaves the values
    const name = groupNameOf(G1, e0, 0, 'Alte Crew')
    const icon = spaceIconOf(G1, e0, 0, image)
    const moved = (value: v1.ValueContext, wrapped: string) => rewrapValueKey({ epoch: 0, key: e0 }, { epoch: 1, key: e1 }, value, wrapped)
    const rotated = {
      ...name, wrappedNameValueKey: moved(groupNameContext(G1, 0), name.wrappedNameValueKey),
      ...icon, wrappedIconValueKey: moved(iconContext({ scopeId: G1, iconEpoch: 0 }), icon.wrappedIconValueKey),
    }
    const row = (fields: typeof rotated) => signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, grantedEpoch: 1, ...fields })
    const epochs = vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([])
    fakeApi(scopesApi, { listMyScopes: listing([row(rotated)]), listScopeEpochs: epochs })
    const [g] = await listMyGroups()
    expect(g.name).toBe('Alte Crew')
    expect(Array.from(g.icon!)).toEqual(Array.from(image))
    expect(rotated.encryptedName).toBe(name.encryptedName) // the rotation left both values byte-identical
    expect(rotated.sealedIcon).toBe(icon.sealedIcon)
    // wraps still under E_0 — a rotation that did not re-wrap them — open under nothing the member holds
    fakeApi(scopesApi, { listMyScopes: listing([row({ ...name, ...icon })]), listScopeEpochs: epochs })
    const [old] = await listMyGroups()
    expect(old?.icon ?? null).toBeNull()
    expect(old?.name ?? '').not.toBe('Alte Crew')
  })

  // ADR 0115 decision 2: a name whose value key's wrap a rotator could not open is staged as one nobody opens — the
  // group is listed unnamed, never dropped from the switcher, while its grant opens.
  it('lists a group whose name\'s wrap opens under no key unnamed, its icon beside it as ever', async () => {
    const key = v1.randomBytes(32)
    const image = new Uint8Array([4, 5, 6])
    const named = groupNameOf(G1, key, 0, 'Reise-Crew')
    const row = (wrappedNameValueKey: string) => signed({
      id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0,
      ...named, wrappedNameValueKey, ...spaceIconOf(G1, key, 0, image),
    })
    // the stand-in a rotation stages for a wrap it cannot open: the same slot, under a key nobody holds
    const standIn = rewrapValueKey({ epoch: 0, key: v1.randomBytes(32) }, { epoch: 0, key }, groupNameContext(G1, 0), named.wrappedNameValueKey)
    mockScopes(listing([row(standIn)]))
    const [g] = await listMyGroups()
    expect(g).toMatchObject({ scopeId: G1, name: '', awaitingKey: false })
    expect(Array.from(g.icon!)).toEqual(Array.from(image))
    mockScopes(listing([row(named.wrappedNameValueKey)]))
    expect((await listMyGroups())[0]).toMatchObject({ name: 'Reise-Crew' }) // control: the name's own wrap opens
  })
})

describe('openGroupIcon (the settings-panel single-scope read)', () => {
  it('opens a rotated group’s icon under its one key, refuses one whose wrap is still under the older key, and returns null for a group with none / an unknown id', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const grant1 = ownGrant(G1, e1, 1)
    const grant0 = ownGrant(G2, e0, 0)
    const image = new Uint8Array([1, 2, 3, 4, 5])
    const stale = spaceIconOf(G1, e0, 0, image) // written at epoch 0, its wrap under E_0
    const rewrapped = { // the rotation re-wrapped its value key under E_1 and left the icon at epoch 0
      ...stale,
      wrappedIconValueKey: rewrapValueKey({ epoch: 0, key: e0 }, { epoch: 1, key: e1 }, iconContext({ scopeId: G1, iconEpoch: 0 }), stale.wrappedIconValueKey),
    }
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, nameEpoch: 1, grantedEpoch: 1, ...rewrapped }),
      signed({ id: G2, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, nameEpoch: 0, grantedEpoch: 0 }), // no icon
    ])
    const epochs = vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([])
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: epochs })

    expect(Array.from((await openGroupIcon(G1))!)).toEqual(Array.from(image)) // under the one key held
    fakeApi(scopesApi, {
      listMyScopes: listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant1, nameEpoch: 1, grantedEpoch: 1, ...stale })]),
      listScopeEpochs: epochs,
    })
    expect(await openGroupIcon(G1)).toBeNull() // no older key is reachable from E_1
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs: epochs })
    expect(await openGroupIcon(G2)).toBeNull() // no sealedIcon → the initial
    expect(await openGroupIcon('nope')).toBeNull() // unknown id
  })
})

describe('openScopeById', () => {
  it('opens a scope by id and decrypts a GROUP’s display name from encryptedName (through its value key)', async () => {
    const key = v1.randomBytes(32)
    const grant = ownGrant(G1, key, 0)
    const name = groupNameOf(G1, key, 0, 'Reise-Crew')
    const list = listing([
      signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, ...name, grantedEpoch: 0 }),
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
    return signed({ id, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, ...groupNameOf(id, key, 0, name), grantedEpoch: 0 })
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
  /** A remaining member of G1 as the directory lists it. (`_e0` kept so each call site still names the epoch.) */
  const admittedMember = (userId: string, who: v1.Identity, _e0?: Uint8Array): ScopeMember => ({
    userId, username: userId.slice(0, 1), avatarRevision: 0, role: 'MEMBER', identityPublicKey: pubOf(who), keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=',
    pending: false,
  })

  it('reads the tenant for a GROUP\'s rotation, never for a PERSONAL one — there is no other member to judge', async () => {
    const e0 = v1.randomBytes(32)
    const me: ScopeMember = { userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }
    fakeApi(scopesApi, {
      listMyScopes: listing([signed({ id: P, kind: 'PERSONAL', tenantId: 't', wrappedGrant: ownGrant(P, e0, 0), grantedEpoch: 0 })]),
      rotateScope: vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 }),
    })
    expect(await commitRotation(P, [me])).toBe(1)
    expect(loadTenantView).not.toHaveBeenCalled()

    // control: the same for a group reads its tenant
    fakeApi(scopesApi, {
      listMyScopes: listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })]),
      rotateScope: vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 }),
    })
    expect(await commitRotation(G1, [me])).toBe(1)
    expect(loadTenantView).toHaveBeenCalledWith('t', undefined)
  })

  it('mints a fresh epoch that replaces the old one, proves it by a succession statement, and re-grants it to every remaining member', async () => {
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

    // No chain link (ADR 0115 decision 1): the old key is sealed under nothing the request carries. The succession
    // statement proves E_1 from E_0 — signed by E_0's succession key over E_1's public key — and opens nothing.
    expect(payload).not.toHaveProperty('wrappedPrev')
    expect(provesSuccession(G1, 1, e0, e1a, payload)).toBe(true)
    expect(provesSuccession(G1, 1, v1.randomBytes(32), e1a, payload)).toBe(false) // control: only E_0's key signed it
    const carried = Buffer.from(JSON.stringify(payload))
    expect(carried.includes(Buffer.from(b64std(e0)))).toBe(false)
    // Before the commit every scope-level value key was re-wrapped under the commit's re-key id, from E_0 to E_1
    // (decision 2). A pending invitee's name key is sealed to the invitee, not under the scope key: the run carries no
    // member keys, since a rotation hands no invitee anything (a rename does, ADR 0115).
    expect(stageScopeLevelRekey).toHaveBeenCalledTimes(1)
    const run = vi.mocked(stageScopeLevelRekey).mock.calls[0][0]
    expect(run.rekeyId).toBe(payload.rekeyId)
    expect(run.oldKey.epoch).toBe(0)
    expect(run.newKey.epoch).toBe(1)
    expect(Object.keys(run).sort()).toEqual(['newKey', 'oldKey', 'rekeyId', 'scopeId'])
    expect(vi.mocked(stageScopeLevelRekey).mock.invocationCallOrder[0]).toBeLessThan(rotate.mock.invocationCallOrder[0])

    // Forward-confidentiality: the new epoch differs from the old, so content sealed under E_1 is unreadable with E_0.
    expect(Array.from(e1a)).not.toEqual(Array.from(e0))

    // Every re-grant carries the rotator's signature over it (ADR 0028 Decision 6)…
    const next = { scopeId: G1, kind: 'GROUP' as const, epoch: 1 }
    for (const [g, who] of [[payload.grants[0], memberA], [payload.grants[1], memberB]] as const) {
      expect(grantSignatureVerifies(ID.pub.sign, next, { userId: g.userId, identityPublicKeyB64: pubOf(who) }, g.wrappedGrant, g.grantSignature)).toBe(true)
    }
    // …and names the key it is wrapped to: SHA-256 of that member's bundle (ADR 0111 decision 10).
    expect(payload.grants.map((g) => g.recipientIdentityDigest)).toEqual(
      [memberA, memberB].map((who) => b64std(v1.sha256Digest(v1.serializeIdentityPublic(who.pub)))),
    )
    // Every member anchored is granted; nobody is pending.
    expect(payload.pendingUserIds).toEqual([])
    // Each member was judged against the key AND the head the directory served.
    expect(anchorIdentity).toHaveBeenCalledWith(A, remaining[0], { tenant: tenantView('t') })
    // A group has no invites: the rotation neither reads nor carries any (ADR 0111 decision 4).
    expect(payload.inviteGrants).toEqual([])
    expect(payload.pendingInviteIds).toEqual([])
  })

  it('re-grants a pending consent proposal too — it holds the group\'s key from its proposal on (ADR 0111 decision 4)', async () => {
    const e0 = v1.randomBytes(32)
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
    const proposed = v1.generateIdentity()

    await commitRotation(G1, [{ ...admittedMember(B, proposed), pendingConsent: true }])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([B])
    expect(openOwn(G1, 1, payload.grants[0].wrappedGrant, proposed.priv)).toHaveLength(32)
  })

  /**
   * An open member invite as `listOpenInvites` serves it (ADR 0111 decision 2): its key, and `signer`'s signature over
   * it — served as the caller's (ME, ID) whoever really signed, which is what a forged one looks like to the rotator.
   */
  const openInvite = (inviteId: string, signer: v1.Identity = ID) => {
    const key = invite.inviteKeyFromSeed(invite.generateInviteSeed())
    const invitePublicKey = v1.serializeIdentityPublic(key.pub)
    const expiresAt = new Date('2026-12-01T00:00:00Z')
    const signature = invite.signInviteKey(signer.priv.sign, { tenantId: T1, inviteId, invitePublicKey, expiresAt: expiresAt.getTime() / 1000 })
    const served: OpenInvite = {
      inviteId, invitePublicKey: b64std(invitePublicKey), inviteKeySignature: b64std(signature),
      signerUserId: ME, signerIdentityPublicKey: ID_PUB, signerKeyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', expiresAt,
    }
    return { served, key }
  }
  const I1 = '1a000000-0000-4000-8000-0000000000a1'
  const I2 = '1a000000-0000-4000-8000-0000000000a2'
  const I3 = '1a000000-0000-4000-8000-0000000000a3'
  function rotateTenantWith(invites: OpenInvite[]) {
    const e0 = v1.randomBytes(32)
    const list = listing([signed({ id: T1, kind: 'TENANT', tenantId: T1, wrappedGrant: ownGrant(T1, e0, 0), grantedEpoch: 0 })])
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    const listOpenInvites = vi.fn<ScopesApi['listOpenInvites']>().mockResolvedValue(invites)
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate, listOpenInvites })
    return { rotate, listOpenInvites }
  }
  const selfRow: ScopeMember = { userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }

  it('a TENANT rotation re-grants every open invite its owner signed — wrapped to the invite key, signed by the rotator', async () => {
    const one = openInvite(I1)
    const two = openInvite(I2)
    const { rotate, listOpenInvites } = rotateTenantWith([one.served, two.served])

    await commitRotation(T1, [selfRow])

    expect(listOpenInvites).toHaveBeenCalledWith({ scopeId: T1 })
    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.pendingInviteIds).toEqual([])
    expect(payload.inviteGrants!.map((g) => g.inviteId)).toEqual([I1, I2])
    const e1 = openOwn(T1, 1, payload.grants[0].wrappedGrant) // the rotator's own grant: the new epoch key
    for (const [g, { served, key }] of [[payload.inviteGrants![0], one], [payload.inviteGrants![1], two]] as const) {
      // The invite's holder — whoever opens the link — opens E_1 with the invite key, at the epoch the rotation creates.
      expect(Array.from(openOwn(T1, 1, g.wrappedGrant, key.priv))).toEqual(Array.from(e1))
      // A grant like a member's: the invite id is its recipient, signed by the rotator (ADR 0028 Decision 6)…
      const to = { userId: served.inviteId, identityPublicKeyB64: served.invitePublicKey }
      expect(grantSignatureVerifies(ID.pub.sign, { scopeId: T1, kind: 'TENANT', epoch: 1 }, to, g.wrappedGrant, g.grantSignature)).toBe(true)
      // …naming the key it is wrapped to: SHA-256 of the invite bundle.
      expect(g.recipientIdentityDigest).toBe(b64std(v1.sha256Digest(fromB64std(served.invitePublicKey))))
    }
  })

  it('declares — never wraps to — an invite whose key signature is not its signer\'s (a forged invite)', async () => {
    const genuine = openInvite(I1)
    const forged = openInvite(I2, v1.generateIdentity()) // signed by another key, served as ours
    const { rotate } = rotateTenantWith([genuine.served, forged.served])

    await commitRotation(T1, [selfRow])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.inviteGrants!.map((g) => g.inviteId)).toEqual([I1]) // the control: the genuine one is carried
    expect(payload.pendingInviteIds).toEqual([I2])
    // The forged one's key never received E_1: no grant names it.
    expect(JSON.stringify(payload.inviteGrants)).not.toContain(forged.served.invitePublicKey)
  })

  it('declares an invite whose signer key is an anomaly, and one whose bytes are malformed', async () => {
    const unanchoredSigner = openInvite(I1)
    const malformed = { ...openInvite(I2).served, inviteKeySignature: '!!' }
    const genuine = openInvite(I3)
    const { rotate } = rotateTenantWith([unanchoredSigner.served, malformed, genuine.served])
    // The signer of I1 is not anchored on this device (the same account serves both — so only I1's first judgement
    // counts; an anomaly for every call would declare the control too).
    let calls = 0
    vi.mocked(anchorIdentity).mockImplementation(async () => (++calls === 1 ? 'anomaly' : 'match'))

    await commitRotation(T1, [])

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.pendingInviteIds).toEqual([I1, I2])
    expect(payload.inviteGrants!.map((g) => g.inviteId)).toEqual([I3])
    // The signer was judged against its served key and head.
    expect(anchorIdentity).toHaveBeenCalledWith(
      ME, { identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=' }, { tenant: tenantView(T1) },
    )
  })

  /**
   * A rotateScope with the server's completeness check (ADR 0111 decision 9, `RotationPolicy`): every
   * membership is granted, declared pending or removed — else `409 rotation_incomplete`, nothing written.
   */
  function completeRotate(memberIds: string[]) {
    return vi.fn<ScopesApi['rotateScope']>(async ({ rotateScopeRequest: r }) => {
      const covered = new Set([...r.grants.map((g) => g.userId), ...(r.pendingUserIds ?? []), ...(r.removesUserId ? [r.removesUserId] : [])])
      if (memberIds.some((id) => !covered.has(id))) throw httpError(409, 'rotation_incomplete')
      return { epoch: r.epoch }
    })
  }

  it('declares a member whose key is an anomaly PENDING and grants the rest — the rotation still lands (decision 10)', async () => {
    // At the rotation site the anchor gate decides: a surviving member whose served key the chain does not reach from
    // the pinned head is NOT wrapped to (never to a possibly-substituted key) and is named in `pendingUserIds`, never
    // skipped silently. The rotation proceeds — forward secrecy against the removed member must not hinge on one
    // member's key — and any member who anchors them brings them current through the grant delivery.
    const e0 = v1.randomBytes(32)
    const grant0 = ownGrant(G1, e0, 0)
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 })])
    const rotate = completeRotate([ME, A, B])
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })

    const memberA = v1.generateIdentity()
    const memberB = v1.generateIdentity()
    const remaining: ScopeMember[] = [selfRow, admittedMember(A, memberA, e0), admittedMember(B, memberB, e0)]
    vi.mocked(anchorIdentity).mockImplementation(async (userId: string) => (userId === B ? 'anomaly' : 'advanced'))

    expect(await commitRotation(G1, remaining)).toBe(1)
    expect(rotate).toHaveBeenCalledTimes(1) // the server's completeness check took it the first time
    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([ME, A]) // a (advanced) and the rotator
    expect(payload.pendingUserIds).toEqual([B])
    // B's key received nothing: no grant opens under it.
    for (const g of payload.grants) expect(() => openOwn(G1, 1, g.wrappedGrant, memberB.priv)).toThrow()
    // The rotator itself is never judged — its own re-grant goes to the identity it holds.
    expect(anchorIdentity).not.toHaveBeenCalledWith(ME, expect.anything())
  })

  it('control: the same rotation with every member anchored declares nobody pending', async () => {
    const e0 = v1.randomBytes(32)
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, e0, 0), grantedEpoch: 0 })])
    const rotate = completeRotate([ME, A, B])
    fakeApi(scopesApi, { listMyScopes: list, rotateScope: rotate })
    const remaining = [selfRow, admittedMember(A, v1.generateIdentity()), admittedMember(B, v1.generateIdentity())]

    await commitRotation(G1, remaining)
    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual([ME, A, B])
    expect(payload.pendingUserIds).toEqual([])
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
    expect(payload.pendingUserIds).toEqual([])
    expect(payload.removesUserId).toBe(R)
  })
})

describe('a scope whose grant is older than its current epoch writes nothing (ADR 0024 Decision 6)', () => {
  /** Open G1 as the app does, then create a folder in it the way the tree does — under the opened write key. */
  async function openAndCreate(grantedEpoch: number, currentEpoch: number) {
    const keys = Array.from({ length: grantedEpoch + 1 }, () => v1.randomBytes(32))
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[grantedEpoch], grantedEpoch), grantedEpoch, currentEpoch })])
    const links: ScopeSuccession[] = keys.slice(1).map((k, i) => (linkUnder(G1, i + 1, k, keys[i])))
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
    expect(opened?.epochs.epoch).toBe(1)
    expect(createNode).toHaveBeenCalledTimes(1)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ scopeEpoch: 1 })
  })
})

describe('no device seals under an epoch below the highest it has held for the scope (ADR 0024 Decision 6, the floor)', () => {
  /** Serve G1 with our grant at `grantedEpoch` and the scope's `currentEpoch`, the chain-links down to E_0 under
   *  `keys`, and a rotation endpoint — the lever is exactly what the server says, fixed per call. */
  function serveG1(keys: Uint8Array[], grantedEpoch: number, currentEpoch: number) {
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[grantedEpoch], grantedEpoch), grantedEpoch, currentEpoch })])
    const links: ScopeSuccession[] = keys.slice(1, grantedEpoch + 1).map((k, i) => (linkUnder(G1, i + 1, k, keys[i])))
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
  const self: ScopeMember[] = [{ userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }]

  it('held epoch 1, then served its epoch-0 grant with a matching current epoch: awaiting the current key, nothing is written', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    expect((await openScopeById(G1))?.epochs.epoch).toBe(1) // this device has now held epoch 1
    const { createNode } = serveG1(keys, 0, 0) // the server rolls the scope back to a grant a removed member holds
    expect(await openAndCreate()).toBeNull() // the awaiting-access state: no key to write under
    expect(createNode).not.toHaveBeenCalled()
  })

  it('control: a device that never held epoch 1 opens the very same epoch-0 grant (the null above is the floor, not the grant)', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    const { createNode } = serveG1(keys, 0, 0)
    expect((await openAndCreate())?.epochs.epoch).toBe(0)
    expect(createNode).toHaveBeenCalledTimes(1)
  })

  it('control: served epoch 1 again, it writes under epoch 1', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    await openScopeById(G1)
    const { createNode } = serveG1(keys, 1, 1)
    expect((await openAndCreate())?.epochs.epoch).toBe(1)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ scopeEpoch: 1 })
  })

  it('the switcher opens no name or icon from a grant below the floor either: the group reads as awaiting the current key', async () => {
    const keys = [v1.randomBytes(32), v1.randomBytes(32)]
    serveG1(keys, 1, 1)
    await openScopeById(G1) // this device has now held epoch 1
    const rolledBack = signed({
      id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, keys[0], 0), grantedEpoch: 0, currentEpoch: 0,
      ...groupNameOf(G1, keys[0], 0, 'Alt-Crew'), ...spaceIconOf(G1, keys[0], 0, new Uint8Array([1, 2, 3])),
    })
    fakeApi(scopesApi, { listMyScopes: listing([rolledBack]), listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue([]) })
    const [g] = await listMyGroups()
    expect(g).toMatchObject({ scopeId: G1, icon: null, awaitingKey: true })
    expect(g.name).not.toBe('Alt-Crew') // nothing of the rolled-back grant opened
    expect(await openGroupIcon(G1)).toBeNull()
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

  /** The caller's grant at `grantedEpoch`, with the chain-links down to E_0 — what `openScopeById` peels. The epochs
   *  `prior` names stay (a later epoch of the same chain must link to the one this device held, ADR 0111 decision 23). */
  function holdGroupAt(
    grantedEpoch: number, rotate: Mock<ScopesApi['rotateScope']>, list = vi.fn<ScopesApi['listMyScopes']>(),
    directory = vi.fn<ScopesApi['listScopeMembers']>(async () => self), prior: Uint8Array[] = [],
  ): Uint8Array[] {
    const keys = Array.from({ length: grantedEpoch + 1 }, (_, i) => prior[i] ?? v1.randomBytes(32))
    const links: ScopeSuccession[] = keys.slice(1).map((k, i) => (linkUnder(G1, i + 1, k, keys[i])))
    const grant = ownGrant(G1, keys[grantedEpoch], grantedEpoch)
    list.mockResolvedValue([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, grantedEpoch })])
    fakeApi(scopesApi, {
      listMyScopes: list, listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links), rotateScope: rotate,
      listScopeMembers: directory,
    })
    return keys
  }

  /** The caller re-grants itself, so every payload's own grant opens to that attempt's new epoch key. */
  const self: ScopeMember[] = [{ userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }]
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
    const held = holdGroupAt(0, rotate)
    let reopened: Uint8Array[] = []
    rotate
      .mockImplementationOnce(async () => {
        reopened = holdGroupAt(1, rotate, undefined, undefined, held) // the winner's re-grant, visible to our re-open
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
    // And the retry is built on the re-opened grant: its succession statement is signed by E_1 for its own new key, and
    // the scope-level rows are staged again from E_1, under a fresh re-key id.
    const retry = rotate.mock.calls[1][0].rotateScopeRequest
    expect(provesSuccession(G1, retry.epoch, reopened[1], Uint8Array.from(newKeyOf(rotate.mock.calls[1])), retry)).toBe(true)
    expect(provesSuccession(G1, retry.epoch, held[0], Uint8Array.from(newKeyOf(rotate.mock.calls[1])), retry)).toBe(false)
    const runs = vi.mocked(stageScopeLevelRekey).mock.calls.map(([r]) => r)
    expect(runs.map((r) => r.oldKey.epoch)).toEqual([0, 1])
    expect(runs[1].rekeyId).toBe(retry.rekeyId)
    expect(runs[1].rekeyId).not.toBe(runs[0].rekeyId)
  })

  it('throws after exactly two commits when the second answer is still stale (our grant lags)', async () => {
    const second = stale()
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValueOnce(stale()).mockRejectedValueOnce(second)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(second)
    expect(rotate).toHaveBeenCalledTimes(2)
  })

  it('a row written while it staged (409 rekey_parts_stale) is staged again and the same commit sent again — same key, same re-key id (ADR 0115 decision 2)', async () => {
    const rotate = vi.fn<ScopesApi['rotateScope']>()
      .mockRejectedValueOnce(httpError(409, 'rekey_parts_stale'))
      .mockRejectedValueOnce(httpError(409, 'rekey_parts_missing'))
      .mockResolvedValueOnce({ epoch: 1 })
    holdGroupAt(0, rotate)
    expect(await commitRotation(G1, self)).toBe(1)
    expect(rotate).toHaveBeenCalledTimes(3)
    expect(stageScopeLevelRekey).toHaveBeenCalledTimes(3)
    const runs = vi.mocked(stageScopeLevelRekey).mock.calls.map(([r]) => r)
    const requests = rotate.mock.calls.map(([c]) => c.rotateScopeRequest)
    expect(new Set(runs.map((r) => r.rekeyId)).size).toBe(1)
    expect(new Set(requests.map((r) => r.rekeyId))).toEqual(new Set([runs[0].rekeyId]))
    expect(new Set(runs.map((r) => b64std(r.newKey.key))).size).toBe(1)
    // the grants of every attempt carry that one key, to the same members
    expect(newKeyOf(rotate.mock.calls[2])).toEqual(newKeyOf(rotate.mock.calls[0]))
    expect(requests.map((r) => r.grants.map((g) => g.userId))).toEqual([[ME], [ME], [ME]])
  })

  it('re-stages a bounded number of times: a scope whose rows keep moving gets the refusal', async () => {
    const stale = httpError(409, 'rekey_parts_stale')
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValue(stale)
    holdGroupAt(0, rotate)
    await expect(commitRotation(G1, self)).rejects.toBe(stale)
    expect(rotate).toHaveBeenCalledTimes(3) // the first commit and REKEY_RESTAGES (2) more
    expect(stageScopeLevelRekey).toHaveBeenCalledTimes(3)
  })

  it('a stage that cannot run commits nothing', async () => {
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockResolvedValue({ epoch: 1 })
    holdGroupAt(0, rotate)
    vi.mocked(stageScopeLevelRekey).mockRejectedValueOnce(new v1.OpenRefused('content-key'))
    await expect(commitRotation(G1, self)).rejects.toBeInstanceOf(v1.OpenRefused)
    expect(rotate).not.toHaveBeenCalled()
  })

  it('does not retry a non-stale failure (e.g. 403) — it propagates after one commit', async () => {
    const forbidden = httpError(403, 'not_scope_owner')
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValue(forbidden)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(forbidden)
    expect(rotate).toHaveBeenCalledTimes(1)
  })

  it('on a first 409 rotation_incomplete (an invite minted meanwhile) rebuilds once, from a FRESH key (ADR 0111 decision 2)', async () => {
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValueOnce(httpError(409, 'rotation_incomplete')).mockResolvedValueOnce({ epoch: 1 })
    holdGroupAt(0, rotate)

    expect(await commitRotation(G1, self)).toBe(1)
    expect(rotate).toHaveBeenCalledTimes(2)
    expect(newKeyOf(rotate.mock.calls[1])).not.toEqual(newKeyOf(rotate.mock.calls[0]))
  })

  describe('the rebuild reads the members again (inbox #661)', () => {
    const A = 'a1000000-0000-4000-8000-0000000000a1'
    const C = 'c1000000-0000-4000-8000-0000000000c1'
    const R = 'c2000000-0000-4000-8000-0000000000c2'
    const row = (userId: string, who = v1.generateIdentity()): ScopeMember => ({
      userId, username: userId.slice(0, 2), avatarRevision: 0, role: 'MEMBER', identityPublicKey: pubOf(who), keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false,
    })
    /** A rotateScope with the server's completeness check over `members()` as they stand at each commit (ADR 0111
     *  decision 9): a membership neither granted, declared pending nor removed is `409 rotation_incomplete`. */
    const coverChecked = (members: () => string[]) => vi.fn<ScopesApi['rotateScope']>(async ({ rotateScopeRequest: r }) => {
      const covered = new Set([...r.grants.map((g) => g.userId), ...(r.pendingUserIds ?? []), ...(r.removesUserId ? [r.removesUserId] : [])])
      if (members().some((id) => !covered.has(id))) throw httpError(409, 'rotation_incomplete')
      return { epoch: r.epoch }
    })
    const grantedIn = (call: unknown[]) =>
      (call[0] as { rotateScopeRequest: { grants: { userId: string }[] } }).rotateScopeRequest.grants.map((g) => g.userId)

    it('a member who joined after the caller\'s read is granted by the one rebuild', async () => {
      // Bite: build the rebuild from the handed list again — C is never granted and the second commit is refused too.
      const c = row(C)
      const directory = vi.fn<ScopesApi['listScopeMembers']>(async () => [...self, c]) // C has joined by now
      const rotate = coverChecked(() => [ME, C])
      holdGroupAt(0, rotate, undefined, directory)

      expect(await commitRotation(G1, self)).toBe(1)

      expect(rotate).toHaveBeenCalledTimes(2)
      expect(grantedIn(rotate.mock.calls[0])).toEqual([ME])
      expect(grantedIn(rotate.mock.calls[1])).toEqual([ME, C])
      expect(directory).toHaveBeenCalledTimes(1)
      expect(directory).toHaveBeenCalledWith({ scopeId: G1 })
    })

    it('control: a commit taken the first time reads no directory — the caller\'s list is the one it builds from', async () => {
      const directory = vi.fn<ScopesApi['listScopeMembers']>(async () => [...self, row(C)])
      const rotate = coverChecked(() => [ME])
      holdGroupAt(0, rotate, undefined, directory)

      expect(await commitRotation(G1, self)).toBe(1)
      expect(rotate).toHaveBeenCalledTimes(1)
      expect(directory).not.toHaveBeenCalled()
    })

    it('a group removal\'s rebuild never grants the member it removes, however the re-read lists them', async () => {
      // The re-read lists the removed member — twice — beside the newcomer: the remover's exclusion holds for it as well.
      const a = row(A)
      const removed = row(R)
      const directory = vi.fn<ScopesApi['listScopeMembers']>(async () => [...self, a, removed, row(C), { ...removed }])
      const rotate = coverChecked(() => [ME, A, R, C])
      holdGroupAt(0, rotate, undefined, directory)

      expect(await rotateGroupForRemoval(G1, [...self, a, removed], R)).toBe(1)

      expect(rotate).toHaveBeenCalledTimes(2)
      const retry = rotate.mock.calls[1][0].rotateScopeRequest
      expect(retry.grants.map((g) => g.userId)).toEqual([ME, A, C]) // the control on the same predicate: C is granted
      expect(retry.pendingUserIds).toEqual([])
      expect(retry.removesUserId).toBe(R)
    })

    it('`excluding` holds for the re-read too: the member left out is never granted and never named', async () => {
      const removed = row(R)
      const directory = vi.fn<ScopesApi['listScopeMembers']>(async () => [...self, removed, row(C)])
      // A server that still counts R refuses both attempts: nothing commits, and neither attempt wrapped to R.
      const rotate = coverChecked(() => [ME, R, C])
      holdGroupAt(0, rotate, undefined, directory)

      await expect(commitRotation(G1, [...self, removed], { excluding: R })).rejects.toMatchObject({ message: 'http 409 rotation_incomplete' })

      expect(rotate).toHaveBeenCalledTimes(2)
      for (const [req] of rotate.mock.calls) {
        expect(req.rotateScopeRequest.grants.map((g) => g.userId)).not.toContain(R)
        expect(req.rotateScopeRequest.pendingUserIds).not.toContain(R)
        expect(req.rotateScopeRequest.removesUserId).toBeUndefined()
      }
      // the control: the newcomer the re-read found is granted in the rebuild
      expect(grantedIn(rotate.mock.calls[1])).toEqual([ME, C])
    })

    it('is bounded: a server that answers every commit with rotation_incomplete gets two commits and one re-read', async () => {
      const directory = vi.fn<ScopesApi['listScopeMembers']>(async () => self)
      const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValue(httpError(409, 'rotation_incomplete'))
      holdGroupAt(0, rotate, undefined, directory)

      await expect(commitRotation(G1, self)).rejects.toMatchObject({ message: 'http 409 rotation_incomplete' })
      expect(rotate).toHaveBeenCalledTimes(ROTATION_REBUILDS + 1)
      expect(directory).toHaveBeenCalledTimes(ROTATION_REBUILDS)
    })
  })

  it('control: another 409 is no rebuild — it propagates after one commit', async () => {
    const conflict = httpError(409, 'last_owner')
    const rotate = vi.fn<ScopesApi['rotateScope']>().mockRejectedValue(conflict)
    holdGroupAt(0, rotate)

    await expect(commitRotation(G1, self)).rejects.toBe(conflict)
    expect(rotate).toHaveBeenCalledTimes(1)
  })
})

describe('renameGroup (cr-0097, ADR 0115 — a fresh value key per rename, handed to every pending invitee)', () => {
  const INVITEE = 'c0000000-0000-4000-8000-0000000000c1'
  const ODD = 'c0000000-0000-4000-8000-0000000000c2'
  const MEMBER = 'c0000000-0000-4000-8000-0000000000c3'
  const invitee = v1.generateIdentity()
  const odd = v1.generateIdentity()
  const person = (userId: string, who: v1.Identity, pendingConsent: boolean): ScopeMember => ({
    userId, username: userId.slice(0, 2), avatarRevision: 0, role: 'MEMBER', identityPublicKey: pubOf(who),
    keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false, pendingConsent,
  })
  /** The name key the invitee opens from its `sealedNameKey`, bound to (scope, name epoch). */
  const inviteeKey = (sealedNameKey: string, nameEpoch: number, who: v1.Identity = invitee) =>
    v1.openAs({ purpose: 'group-name-key', scopeId: G1, nameEpoch }, v1.hybridSecret(who.priv), fromB64std(sealedNameKey))

  /** G1 held at [grantedEpoch], its current name written at epoch 0 and its wrap under the held key; [members] listed. */
  function mockGroupForRename(
    rename: Mock<ScopesApi['renameGroup']>, opts: { epoch?: number; members?: Mock<ScopesApi['listScopeMembers']> } = {},
  ) {
    const epoch = opts.epoch ?? 0
    const key = v1.randomBytes(32) // E_epoch = the write key the caller holds
    const grant = ownGrant(G1, key, epoch)
    const old = groupNameOf(G1, key, epoch, 'Alt', 0) // written at epoch 0, its wrap (re-wrapped by any rotation) under E_epoch
    const list = listing([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: grant, ...old, grantedEpoch: epoch, currentEpoch: epoch })])
    const members = opts.members ?? vi.fn<ScopesApi['listScopeMembers']>().mockResolvedValue([])
    fakeApi(scopesApi, { listMyScopes: list, renameGroup: rename, listScopeMembers: members })
    return { key, old }
  }

  it('seals the new name under a fresh value key at the write epoch, wraps it under the scope key, and seals it to each pending invitee', async () => {
    const rename = vi.fn<ScopesApi['renameGroup']>().mockResolvedValue(undefined)
    const members = vi.fn<ScopesApi['listScopeMembers']>().mockResolvedValue([
      person(MEMBER, v1.generateIdentity(), false), person(INVITEE, invitee, true), person(ODD, odd, true),
    ])
    vi.mocked(anchorIdentity).mockImplementation(async (userId) => (userId === ODD ? 'anomaly' : 'firstUse'))
    const { key, old } = mockGroupForRename(rename, { members })

    await renameGroup(G1, 'Neu')

    expect(rename).toHaveBeenCalledTimes(1)
    const req = rename.mock.calls[0][0].renameGroupRequest
    expect(req.nameEpoch).toBe(0)
    // A member reads the NEW name through its wrap under the held key …
    expect(readGroupName(G1, key, 0, { ...req, nameEpoch: req.nameEpoch! })).toBe('Neu')
    // … under a key of its own: not the old name's, and not the scope key itself.
    expect(req.wrappedNameValueKey).not.toBe(old.wrappedNameValueKey)
    expect(() => openValue({ epoch: 0, key }, groupNameContext(G1, 0), old.wrappedNameValueKey,
      (oldKey) => openGroupNameWith(oldKey, G1, 0, req.encryptedName))).toThrow(v1.OpenRefused)
    expect(() => openGroupNameWith(key, G1, 0, req.encryptedName)).toThrow(v1.OpenRefused)
    // Each pending invitee is named — never a member — and the anchored one opens the same canonical name with its key.
    expect(req.inviteeNameKeys.map((i) => i.userId).sort()).toEqual([INVITEE, ODD].sort())
    const handed = req.inviteeNameKeys.find((i) => i.userId === INVITEE)!
    expect(handed.recipientIdentityDigest).toBe(recipientIdentityDigest(pubOf(invitee)))
    expect(openGroupNameWith(inviteeKey(handed.sealedNameKey!, 0), G1, 0, req.encryptedName)).toBe('Neu')
    // The one held as an anomaly gets its name key cleared, never sealed to a key this device cannot anchor.
    expect(req.inviteeNameKeys.find((i) => i.userId === ODD)).toEqual({ userId: ODD, sealedNameKey: null, recipientIdentityDigest: null })
  })

  it('a rename after a rotation mints a fresh key at the new epoch and re-wraps it to each invitee — the key it held opens nothing new', async () => {
    const rename = vi.fn<ScopesApi['renameGroup']>().mockResolvedValue(undefined)
    const members = vi.fn<ScopesApi['listScopeMembers']>().mockResolvedValue([person(INVITEE, invitee, true)])
    const { key, old } = mockGroupForRename(rename, { epoch: 1, members })
    // what the invitee held from before: the old name's key, sealed to it at the old name's epoch
    const oldKey = openValueKey({ epoch: 1, key }, groupNameContext(G1, 0), old.wrappedNameValueKey)

    await renameGroup(G1, 'Nach der Rotation')

    const req = rename.mock.calls[0][0].renameGroupRequest
    expect(req.nameEpoch).toBe(1) // the epoch the rename writes at, which both seals bind
    expect(readGroupName(G1, key, 1, { ...req, nameEpoch: 1 })).toBe('Nach der Rotation')
    const fresh = inviteeKey(req.inviteeNameKeys[0].sealedNameKey!, 1)
    expect(b64std(fresh)).not.toBe(b64std(oldKey))
    expect(openGroupNameWith(fresh, G1, 1, req.encryptedName)).toBe('Nach der Rotation') // control
    expect(() => openGroupNameWith(oldKey, G1, 1, req.encryptedName)).toThrow(v1.OpenRefused)
    expect(() => inviteeKey(req.inviteeNameKeys[0].sealedNameKey!, 0)).toThrow(v1.OpenRefused) // bound to the new name epoch
  })

  it('retries once on 409 stale_epoch (a concurrent rotation), then succeeds', async () => {
    const stale = httpError(409, 'stale_epoch')
    const rename = vi.fn<ScopesApi['renameGroup']>().mockRejectedValueOnce(stale).mockResolvedValueOnce(undefined)
    mockGroupForRename(rename)

    await renameGroup(G1, 'Neu')
    expect(rename).toHaveBeenCalledTimes(2) // refetch + retry once
  })

  it('retries once on 409 rename_incomplete with the members read again — the proposal made meanwhile is named', async () => {
    const rename = vi.fn<ScopesApi['renameGroup']>().mockRejectedValueOnce(httpError(409, 'rename_incomplete')).mockResolvedValueOnce(undefined)
    const members = vi.fn<ScopesApi['listScopeMembers']>().mockResolvedValueOnce([]).mockResolvedValueOnce([person(INVITEE, invitee, true)])
    mockGroupForRename(rename, { members })

    await renameGroup(G1, 'Neu')
    expect(rename).toHaveBeenCalledTimes(2)
    expect(rename.mock.calls[0][0].renameGroupRequest.inviteeNameKeys).toEqual([])
    expect(rename.mock.calls[1][0].renameGroupRequest.inviteeNameKeys.map((i) => i.userId)).toEqual([INVITEE])
  })

  it('does not retry on a non-stale failure (e.g. 403) — it propagates', async () => {
    const forbidden = httpError(403, 'not_scope_owner')
    const rename = vi.fn<ScopesApi['renameGroup']>().mockRejectedValue(forbidden)
    mockGroupForRename(rename)

    await expect(renameGroup(G1, 'Neu')).rejects.toBe(forbidden)
    expect(rename).toHaveBeenCalledTimes(1)
  })
})

describe('wrapGroupNameKeyForInvitee (uc.spaces.consent-group-add, ADR 0115 — the invitee is handed the name\'s value key)', () => {
  const INVITEE = 'c0000000-0000-4000-8000-0000000000d1'
  const invitee = v1.generateIdentity()
  const served = { identityPublicKey: pubOf(invitee), keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=' }

  it('seals the name\'s own value key, opened from its wrap, to the invitee — and none when the wrap does not open', async () => {
    const key = v1.randomBytes(32)
    const name = groupNameOf(G1, key, 1, 'Buchclub', 0) // written at epoch 0, its wrap (re-wrapped) under E_1
    const row = (fields: typeof name) => signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 1), ...fields, grantedEpoch: 1 })
    mockScopes(listing([row(name)]))
    const sealed = await wrapGroupNameKeyForInvitee(G1, INVITEE, served)
    const nameKey = v1.openAs({ purpose: 'group-name-key', scopeId: G1, nameEpoch: 0 }, v1.hybridSecret(invitee.priv), fromB64std(sealed!))
    expect(openGroupNameWith(nameKey, G1, 0, name.encryptedName)).toBe('Buchclub') // the members' canonical blob
    // a wrap a rotator could not open (its stand-in) yields no key to hand on: the proposal is not made with one
    const standIn = rewrapValueKey({ epoch: 1, key: v1.randomBytes(32) }, { epoch: 1, key }, groupNameContext(G1, 0), name.wrappedNameValueKey)
    mockScopes(listing([row({ ...name, wrappedNameValueKey: standIn })]))
    expect(await wrapGroupNameKeyForInvitee(G1, INVITEE, served)).toBeNull()
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

  // ADR 0114 decisions 1 and 9: every link the switch-off turned off is a close — this device starts the re-key of what
  // the server marked, after its answer; with nothing turned off, nothing was marked.
  it('starts the scope re-key in the background once links went off — and not when none did, or the switch-off was refused', async () => {
    let finish!: () => void
    vi.mocked(rekeyScope).mockReset().mockReturnValue(new Promise((r) => { finish = () => r({ done: true, committed: 2 }) }))
    stubApi(scopesApi, { revokeScopeShares: { revoked: 2 } })
    await expect(revokeScopeShares(G1)).resolves.toBe(2) // answered while the re-key still runs: the dialog does not wait
    await vi.waitFor(() => expect(rekeyScope).toHaveBeenCalledWith(G1))
    finish()

    vi.mocked(rekeyScope).mockClear()
    stubApi(scopesApi, { revokeScopeShares: { revoked: 0 } })
    await revokeScopeShares(G1)
    fakeApi(scopesApi, { revokeScopeShares: vi.fn(async () => { throw httpError(403, 'not_scope_owner') }) })
    await expect(revokeScopeShares(G1)).rejects.toBeTruthy()
    await Promise.resolve()
    expect(rekeyScope).not.toHaveBeenCalled() // control: nothing closed, nothing re-keyed
  })

  it('a removal re-keys every scope of mine now re-keying, one after another, a failure going on to the next', async () => {
    vi.mocked(rekeyScope).mockReset().mockImplementation(async (id) => {
      if (id === G1) throw new Error('rekey: this device does not hold the scope\'s current epoch')
      return { done: true, committed: 1 }
    })
    fakeApi(scopesApi, {
      listMyScopes: vi.fn(async () => [{ id: G1, rekeying: true }, { id: G2, rekeying: false }, { id: TN, rekeying: true }] as ScopeSummary[]),
    })
    await expect(rekeyClosedScopes()).resolves.toBeUndefined()
    expect(vi.mocked(rekeyScope).mock.calls.map(([id]) => id)).toEqual([G1, TN]) // G2 has no due node
  })
})

describe('openMyTenantIcon (the cross-tenant switcher read)', () => {
  const signedTenant = (t: MyTenant): MyTenant => {
    const r = signed({ id: t.tenantId, kind: 'TENANT', wrappedGrant: t.wrappedGrant!, grantedEpoch: t.grantedEpoch ?? 0 })
    return { ...t, granterUserId: r.granterUserId, grantSignature: r.grantSignature, granterIdentityPublicKey: r.granterIdentityPublicKey }
  }
  // Seal an icon under a value key whose wrap a tenant scope key holds (ADR 0115), wrap that scope key as the caller's
  // OWN grant, and hand all to openMyTenantIcon as a MyTenant row — it must open our grant transiently and return the
  // encoded bytes. This is the per-tenant grant-open the switcher runs once per tenant (en.spaces.tenant-scope),
  // exercised without a live backend; the end-to-end (a SECOND tenant rendering the first's icon) is the live smoke.
  function tenantWithIcon(encoded: Uint8Array, scopeKey = v1.randomBytes(32)): MyTenant {
    const icon = spaceIconOf(T1, scopeKey, 0, encoded)
    const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: T1, epoch: 0 }, v1.hybridPublic(ID.priv), scopeKey))
    return signedTenant(myTenant({ tenantId: T1, grantedEpoch: 0, wrappedGrant, ...icon }))
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

  it('returns null (never throws) when the grant opens but the icon\'s wrap is under a different key, or substituted', async () => {
    // A foreign/mismatched icon under our real grant must fall back to the initial, not crash the switcher.
    const scopeKey = v1.randomBytes(32)
    const ours = tenantWithIcon(new Uint8Array([9, 9, 9]), scopeKey)
    expect([...(await openMyTenantIcon(ours))!]).toEqual([9, 9, 9]) // control
    const foreign = spaceIconOf(T1, v1.randomBytes(32), 0, new Uint8Array([9, 9, 9]))
    expect(await openMyTenantIcon({ ...ours, wrappedIconValueKey: foreign.wrappedIconValueKey })).toBeNull()
    // a wrap of another K_v under OUR key at this very slot — what a rotator could stage — opens, the icon does not
    const other = spaceIconOf(T1, scopeKey, 0, new Uint8Array([7, 7, 7]))
    expect(await openMyTenantIcon({ ...ours, wrappedIconValueKey: other.wrappedIconValueKey })).toBeNull()
    expect(await openMyTenantIcon({ ...ours, wrappedIconValueKey: null })).toBeNull()
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

  it('refuses a shared grant whose granter key the chain does not reach from the pinned head', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'anomaly', retired: false })
    expect(await openScopeById(G1)).toBeNull()
    // a GROUP's grant is judged in the group's tenant
    expect(anchorChainKey).toHaveBeenCalledWith(FOREIGNER, pubOf(foreigner), { tenant: tenantView('t') })
  })

  it('the TENANT scope\'s own grant from another member is judged by the pin alone — its standings need the key it carries', async () => {
    const key = v1.randomBytes(32)
    serve([signed({ id: T, kind: 'TENANT', tenantId: T, wrappedGrant: ownGrant(T, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
    expect(Array.from((await openScope(ScopeKind.Tenant))!.key)).toEqual(Array.from(key))
    expect(anchorChainKey).toHaveBeenCalledWith(FOREIGNER, pubOf(foreigner), { tenant: undefined })
  })

  it.each(['match', 'advanced'] as const)('control: a shared grant whose granter key is anchored (%s) opens', async (anchor) => {
    const key = v1.randomBytes(32)
    serve([signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })])
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor, retired: false })
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
  })

  it('refuses a shared grant whose signature does not verify under the key it names — and pins nothing', async () => {
    const key = v1.randomBytes(32)
    const row = signed({ id: G1, kind: 'GROUP', tenantId: 't', wrappedGrant: ownGrant(G1, key, 0), grantedEpoch: 0 }, { userId: FOREIGNER, identity: foreigner })
    serve([{ ...row, granterIdentityPublicKey: pubOf(v1.generateIdentity()) }])
    expect(await openScopeById(G1)).toBeNull()
    expect(anchorChainKey).not.toHaveBeenCalled() // a forged grant must not get to pin the key it names
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
    // Both name the key they are wrapped to — our own bundle (ADR 0111 decision 10).
    const myDigest = b64std(v1.sha256Digest(fromB64std(ID_PUB)))
    expect(p.recipientIdentityDigest).toBe(myDigest)
    expect(g.recipientIdentityDigest).toBe(myDigest)
    // No admission tag rides a group's birth any more (ADR 0111 decision 5): the body is the grant, its key, the name and its value key's wrap (ADR 0115).
    expect(Object.keys(g).sort()).toEqual(['encryptedName', 'grantSignature', 'recipientIdentityDigest', 'scopeId', 'wrappedGrant', 'wrappedNameValueKey'])
  })
})

describe('a grant verifies under the key its granter signed it with, not the granter\'s current one (inbox #233)', () => {
  // The rotator R signed our grant with its OLD identity, then rotated its account keys (ADR 0060). The server serves
  // the key as of the grant (ADR 0028 Decision 6); this pins what the client does with each served key,
  // with real signatures. The client is unchanged by #233 — the backend's
  // `RotationSliceTest.aGrantTheRotatorSignedIsStillServedWithTheKeyThatSignedIt` is the half that was red.
  const R = 'a0000000-0000-4000-8000-0000000000a1'
  const rOld = v1.generateIdentity()
  const rNew = v1.generateIdentity()
  const self: ScopeMember[] = [{ userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }]

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
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
    expect(anchorChainKey).toHaveBeenCalledWith(R, pubOf(rOld), { tenant: tenantView('t') })
  })

  it('and the owner holding it can therefore complete the covering rotation', async () => {
    const { rotateScope } = serveRGrant(v1.randomBytes(32), pubOf(rOld))
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
    expect(await commitRotation(G1, self)).toBe(1)
    expect(rotateScope).toHaveBeenCalledTimes(1)
  })

  it('once R\'s chain moved past that key, it is a retired key: the grant (epoch 0, at this device\'s floor) still opens', async () => {
    // R secured their account after granting us; the grant's key is a retired key of R's chain. A grant from before the
    // rotation opens at an epoch this device already held (decision 9) — grantTrust.test.ts walks a real chain for it.
    const key = v1.randomBytes(32)
    serveRGrant(key, pubOf(rOld))
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'advanced', retired: true })
    expect(Array.from((await openScopeById(G1))!.key)).toEqual(Array.from(key))
  })

  it('served R\'s CURRENT key, the signature fails before the key is ever judged', async () => {
    // The lock #233 removed: the served key is not the one that signed, so `bad_signature` precedes the anchor gate.
    serveRGrant(v1.randomBytes(32), pubOf(rNew))
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
    expect(await openScopeById(G1)).toBeNull()
    expect(anchorChainKey).not.toHaveBeenCalled()
  })

  it('and served R\'s current key, the owner holding it cannot rotate the scope at all', async () => {
    const { rotateScope } = serveRGrant(v1.randomBytes(32), pubOf(rNew))
    vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'match', retired: false })
    await expect(commitRotation(G1, self)).rejects.toThrow('cannot open scope to rotate')
    expect(rotateScope).not.toHaveBeenCalled()
  })
})

describe('a new epoch is accepted only if its chain links down to the key this device held (inbox #650, ADR 0111 decision 23)', () => {
  // The operator's lever: it bumps a scope's epoch and serves this member a grant at the new epoch, sealed to the
  // member's PUBLIC key around a key of its own choosing and signed by a granter it fabricated. For the TENANT scope's
  // own grant the granter is judged by the pin alone, and a first-seen granter is pinned (TOFU), so the signature check
  // passes; before #650 the only check against what the device held was the epoch floor - a number - and the member's
  // next write landed under the operator's key. The link `E_n sealed under E_n+1` is what the operator cannot make: it
  // never held E_n.
  const OP = 'f0000000-0000-4000-8000-0000000000f9' // a granter the operator fabricated, met for the first time
  const operator = { userId: OP, identity: v1.generateIdentity() }
  const A = 'a0000000-0000-4000-8000-0000000000a9' // an honest co-member who granted the epochs this device held
  const member = { userId: A, identity: v1.generateIdentity() }
  type Granter = { userId: string; identity: v1.Identity }

  /** `keys[i]` sealed under `keys[i+1]`: the honest chain links of `scopeId` from epoch 1 up to `keys.length - 1`. */
  const linksOf = (scopeId: string, keys: Uint8Array[]): ScopeSuccession[] =>
    keys.slice(1).map((k, i) => (linkUnder(scopeId, i + 1, k, keys[i])))

  /** Serve `scopeId` (of `kind`) with our grant at `epoch` wrapping `key`, signed by `granter`, beside `links`; a
   *  content API that records what is written. */
  function serve(scopeId: string, kind: 'TENANT' | 'GROUP', key: Uint8Array, epoch: number, links: ScopeSuccession[], granter: Granter = member) {
    const tenantId = kind === 'TENANT' ? scopeId : T
    const list = listing([signed({ id: scopeId, kind, tenantId, wrappedGrant: ownGrant(scopeId, key, epoch), grantedEpoch: epoch }, granter)])
    const rotateScope = vi.fn<ScopesApi['rotateScope']>(async ({ rotateScopeRequest }) => ({ epoch: rotateScopeRequest.epoch }))
    const listScopeEpochs = vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links)
    fakeApi(scopesApi, { listMyScopes: list, listScopeEpochs, rotateScope, listScopeMembers: vi.fn<ScopesApi['listScopeMembers']>(async () => []) })
    const createNode = vi.fn<ContentApi['createNode']>(async ({ createNodeRequest }) => ({ nodeId: createNodeRequest.nodeId }))
    fakeApi(contentApi, { createNode })
    return { createNode, rotateScope, listScopeEpochs }
  }
  /** Open the scope as the app does, then write into it under the write key it opened (a folder at its root). */
  async function openAndWrite(scopeId: string) {
    const opened = await openScopeById(scopeId)
    if (opened) await createFolder(scopeId, opened.epochs, 'Neu')
    return opened
  }

  it('measured on main: a grant for a later TENANT epoch whose chain does not peel down to the held key is refused - nothing is written', async () => {
    const E = [v1.randomBytes(32)]
    serve(T, 'TENANT', E[0], 0, [])
    expect((await openAndWrite(T))?.epochs.epoch).toBe(0) // this device now holds E_0 of the tenant

    const planted = v1.randomBytes(32) // the operator's key, with no link to E_0 (it never held E_0)
    const { createNode } = serve(T, 'TENANT', planted, 1, [], operator)
    expect(await openAndWrite(T)).toBeNull() // the awaiting-access state, as for any refused grant
    expect(createNode).not.toHaveBeenCalled()
  })

  it('a link the operator made does not help it: it peels to a key that is not the one held', async () => {
    serve(T, 'TENANT', v1.randomBytes(32), 0, [])
    await openAndWrite(T)
    const planted = v1.randomBytes(32)
    const { createNode } = serve(T, 'TENANT', planted, 1, [linkUnder(T, 1, planted, v1.randomBytes(32))], operator)
    expect(await openAndWrite(T)).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
  })

  it('control: the honest next epoch, its link sealing the held E_0, opens - from the same unpinned granter - and writes under it', async () => {
    const E = [v1.randomBytes(32), v1.randomBytes(32)]
    serve(T, 'TENANT', E[0], 0, [])
    await openAndWrite(T)
    const { createNode } = serve(T, 'TENANT', E[1], 1, linksOf(T, E), operator)
    expect((await openAndWrite(T))?.epochs.epoch).toBe(1)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ scopeEpoch: 1 })
  })

  it('at the held epoch itself, another key than the one held is refused (control: the held key opens again)', async () => {
    const E = [v1.randomBytes(32), v1.randomBytes(32)]
    serve(T, 'TENANT', E[1], 1, linksOf(T, E))
    await openAndWrite(T) // holds E_1
    const { createNode } = serve(T, 'TENANT', v1.randomBytes(32), 1, [], operator) // the operator withholds the links
    expect(await openAndWrite(T)).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
    serve(T, 'TENANT', E[1], 1, linksOf(T, E), operator)
    expect((await openAndWrite(T))?.epochs.epoch).toBe(1)
  })

  it('a member pending across several epochs is brought current: the grant three epochs up peels down to the held key', async () => {
    const E = Array.from({ length: 4 }, () => v1.randomBytes(32))
    serve(T, 'TENANT', E[0], 0, [])
    await openAndWrite(T)
    const { createNode } = serve(T, 'TENANT', E[3], 3, linksOf(T, E))
    expect((await openAndWrite(T))?.epochs.epoch).toBe(3)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ scopeEpoch: 3 })
  })

  it('...and one link in between that does not open breaks the chain: refused, not thrown (main threw OpenRefused from the peel)', async () => {
    const E = Array.from({ length: 4 }, () => v1.randomBytes(32))
    serve(T, 'TENANT', E[0], 0, [])
    await openAndWrite(T)
    const links = linksOf(T, E)
    links[1] = linkUnder(T, 2, E[2], v1.randomBytes(32))
    serve(T, 'TENANT', E[3], 3, links)
    expect(await openAndWrite(T)).toBeNull()
  })

  it('a device that holds nothing yet takes its first grant as it is (the invite\'s anchor, ADR 0111 decision 19, vouched for it)', async () => {
    const { createNode } = serve(T, 'TENANT', v1.randomBytes(32), 1, [], operator)
    expect((await openAndWrite(T))?.epochs.epoch).toBe(1)
    expect(createNode).toHaveBeenCalledTimes(1)
  })

  it('the self-grant of an account\'s securing carries the very key the device held: it opens', async () => {
    const E = [v1.randomBytes(32), v1.randomBytes(32)]
    serve(T, 'TENANT', E[1], 1, linksOf(T, E))
    await openAndWrite(T)
    // the stage's self-grant: the same epoch's key, granted to this account by itself (ADR 0111 decision 6)
    serve(T, 'TENANT', E[1], 1, linksOf(T, E), { userId: ME, identity: ID })
    expect((await openAndWrite(T))?.epochs.epoch).toBe(1)
  })

  it('after a removal rotation this device committed, the removed member (who held E_0) cannot plant epoch 1 under a key of its own', async () => {
    // The removed member R held E_0, so - with the operator - it CAN sign a succession from E_0 to a key it chose.
    // What refuses it is that the device holds the E_1 it minted, not merely the floor 1 (control: that E_1 opens).
    const E0 = v1.randomBytes(32)
    const { rotateScope } = serve(G1, 'GROUP', E0, 0, [])
    const self: ScopeMember[] = [{ userId: ME, username: 'me', avatarRevision: 0, role: 'OWNER', identityPublicKey: ID_PUB, keyChainHead: 'ERERERERERERERERERERERERERERERERERERERERERE=', pending: false }]
    expect(await commitRotation(G1, self)).toBe(1)
    const request = rotateScope.mock.calls[0][0].rotateScopeRequest
    const { grants, epoch } = request
    const E1 = openOwn(G1, epoch, grants[0].wrappedGrant, ID.priv) // the key this device minted and holds
    const planted = v1.randomBytes(32)
    const { createNode } = serve(G1, 'GROUP', planted, 1, [linkUnder(G1, 1, planted, E0)], operator)
    expect(await openAndWrite(G1)).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
    serve(G1, 'GROUP', E1, 1, [{ epoch: 1, successionPublicKey: request.successionPublicKey, successionSignature: request.successionSignature }], member)
    expect((await openAndWrite(G1))?.epochs.epoch).toBe(1)
  })

  it('the account securing\'s plan opens no unlinked grant either: it is declared unreadable, never re-granted', async () => {
    serve(T, 'TENANT', v1.randomBytes(32), 0, [])
    await openAndWrite(T)
    const planted = v1.randomBytes(32)
    serve(T, 'TENANT', planted, 1, [], operator)
    const row = signed({ id: T, kind: 'TENANT', tenantId: T, wrappedGrant: ownGrant(T, planted, 1), grantedEpoch: 1 }, operator)
    const plan = {
      scopeId: T, kind: row.kind, tenantId: T, currentEpoch: 1, grantedEpoch: 1, wrappedGrant: row.wrappedGrant,
      granterUserId: row.granterUserId, grantSignature: row.grantSignature, granterIdentityPublicKey: row.granterIdentityPublicKey,
    }
    const refused = await openPlanGrantKey(plan).then(() => null, (e: unknown) => e)
    expect(isUnreadableGrant(refused)).toBe(true)
    // ...also where the server serves no links at all: a group invitation not yet answered is no member's (404)
    fakeApi(scopesApi, { listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockRejectedValue(httpError(404, 'scope_not_found')) })
    expect(isUnreadableGrant(await openPlanGrantKey(plan).then(() => null, (e: unknown) => e))).toBe(true)
  })
})
