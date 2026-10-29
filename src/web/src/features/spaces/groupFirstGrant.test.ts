import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'

// A group's first grant on a device is judged by its granter's standing in the TENANT, not in the group (inbox #750,
// ADR 0111 § What stays open). A device that holds no key of a group yet - a newcomer's after a consent accept, which
// opens no grant, or a new or linked device - takes that grant from any member the tenant anchors, and nothing ties
// that member to the group: who is in a group is the server's word. This file measures it end to end on real keys - the
// open path (`openScopeById` -> `verifyOwnGrant` -> `requireLinkToHeld`), the pin store, the key-chain walk and this
// device's held keys are all REAL; only the server and the tenant's standings (which `tenantTrust.test.ts` derives from
// the join records) are driven here. The residual tests are green on purpose: they pin the boundary the docs name, so a
// change that closes it turns them red and must move ADR 0111 and security-model.md with it.
vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(), contentApi: vi.fn(), accountApi: vi.fn(), accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn(),
}))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn(), masterKey: vi.fn() }))
vi.mock('../account/tenantTrust', () => ({ loadTenantView: vi.fn(), cachedTenantView: vi.fn(), tenantViewOfScope: vi.fn() }))

import { openScopeById } from './scopes'
import { acceptGroupInvitation } from './members'
import { keyAt } from './epochs'
import { epochLinkContext } from './epochs'
import { signGrant } from './grant'
import { createFolder } from '../../core/node/tree'
import { heldEpochKey } from '../account/trustMarks'
import { forgetVerifiedPins, type Standing } from '../account/identityPins'
import { cachedTenantView, loadTenantView, tenantViewOfScope, type TenantView } from '../account/tenantTrust'
import { accountApi, contentApi, scopesApi } from '../../api/client'
import { identityPrivate, masterKey } from '../../app/vault'
import { fakeApi, stubApi } from '../../test/apiMock'
import { accountKeyMaterial } from '../../test/models'
import { fakePinServer } from '../../test/pinStore'
import { chainKeys, fakeKeyChains, type ChainKeys } from '../../test/keyChain'
import type { ScopeEpochLink, ScopesApi, ScopeSummary } from '../../api/generated/scopes'
import type { ContentApi } from '../../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

/** The victim: the vault's identity and account id. */
const ID = v1.generateIdentity()
const ID_PUB = b64std(v1.serializeIdentityPublic(ID.pub))
const ME = '75000000-0000-4000-8000-0000000000e0'
const MK = v1.randomBytes(32)

const TENANT = '75000000-0000-4000-8000-0000000000f0'
/** The group the victim belongs to (or was proposed into). */
const G = '75000000-0000-4000-8000-0000000000a0'
/** A group of M's own - the baseline M needs no operator for. */
const G_OWN = '75000000-0000-4000-8000-0000000000a1'

type Actor = { userId: string; keys: ChainKeys }
const actor = (n: string): Actor => ({ userId: `75000000-0000-4000-8000-0000000000${n}`, keys: chainKeys() })
/** G's owner, anchored in the tenant. */
const ALICE = actor('b1')
/** A plain member of G (no owner), anchored in the tenant. */
const BOB = actor('b2')
/** A member the tenant anchors who is in no role in G - never was, or was removed from it. */
const MALLORY = actor('b3')
/** A membership the server made: no join record, no owner - unanchored in the tenant. */
const SERVER_MADE = actor('b4')

/** The tenant's standings as `loadTenantView` derives them from the join records: three joined members at their birth
 *  keys, and the server-made membership unanchored. Nothing in it speaks of any group. */
function tenantView(): TenantView {
  const joined = (a: Actor): Standing => ({
    kind: 'joined', identityPublicKey: v1.serializeIdentityPublic(a.keys.identity.pub), secretKeyProofPublicKey: a.keys.proof.publicKey,
  })
  const standings = new Map<string, Standing>([
    [ALICE.userId, joined(ALICE)], [BOB.userId, joined(BOB)], [MALLORY.userId, joined(MALLORY)],
    [SERVER_MADE.userId, { kind: 'unanchored' }],
  ])
  return { tenantId: TENANT, members: new Map(), available: true, standing: async (u) => standings.get(u) ?? { kind: 'unanchored' } }
}

let createNode: ReturnType<typeof vi.fn<ContentApi['createNode']>>

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // a device that holds no key of any scope yet
  forgetVerifiedPins()
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: ID.priv.kemSeed.slice(), sign: ID.priv.sign.slice() }))
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ME }) })
  fakePinServer() // the victim has pinned nobody yet
  const chains = fakeKeyChains()
  for (const a of [ALICE, BOB, MALLORY, SERVER_MADE]) chains.born(a.userId, a.keys)
  const view = tenantView()
  vi.mocked(cachedTenantView).mockResolvedValue(view)
  vi.mocked(loadTenantView).mockResolvedValue(view)
  vi.mocked(tenantViewOfScope).mockResolvedValue(view)
  createNode = vi.fn<ContentApi['createNode']>(async ({ createNodeRequest }) => ({ nodeId: createNodeRequest.nodeId }))
  fakeApi(contentApi, { createNode })
})

/** Serve the victim's grant on `scopeId` at `epoch`, wrapping `key`, signed by `granter`, beside `links`. */
function serve(scopeId: string, key: Uint8Array, epoch: number, granter: Actor, links: ScopeEpochLink[] = [], extra: Partial<ScopesApi> = {}) {
  const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId, epoch }, v1.hybridPublic(ID.priv), key))
  const row: ScopeSummary = {
    id: scopeId, kind: 'GROUP', tenantId: TENANT, role: 'MEMBER', nameEpoch: 0, iconEpoch: 0, grantedEpoch: epoch, currentEpoch: epoch,
    wrappedGrant, granterUserId: granter.userId,
    granterIdentityPublicKey: b64std(v1.serializeIdentityPublic(granter.keys.identity.pub)),
    grantSignature: signGrant(granter.keys.identity.priv.sign, { scopeId, kind: 'GROUP', epoch }, { userId: ME, identityPublicKeyB64: ID_PUB }, wrappedGrant),
  }
  fakeApi(scopesApi, {
    listMyScopes: vi.fn<ScopesApi['listMyScopes']>().mockResolvedValue([row]),
    listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links),
    ...extra,
  })
}

/** The link stored at `epoch`: `prev` sealed under `key`. */
const linkUnder = (scopeId: string, epoch: number, key: Uint8Array, prev: Uint8Array): ScopeEpochLink =>
  ({ epoch, wrappedPrev: b64std(v1.seal(epochLinkContext(scopeId, epoch), key, prev)) })

/** Open the group as the app does and write a folder into it under the write key it opened. */
async function openAndWrite(scopeId: string) {
  const opened = await openScopeById(scopeId)
  if (opened) await createFolder(scopeId, opened.epochs, 'Neu')
  return opened
}

const same = (a: Uint8Array, b: Uint8Array) => b64std(a) === b64std(b)

describe('residual (ADR 0111 § What stays open): a device that holds no key of a group takes its first grant from any member the tenant anchors (inbox #750)', () => {
  it('measured on main: M, anchored in the tenant and in no role in G, signs a grant around a key of its choosing - the fresh device opens it and writes under it', async () => {
    const planted = v1.randomBytes(32)
    serve(G, planted, 3, MALLORY)
    const opened = await openAndWrite(G)
    expect(opened && same(opened.key, planted)).toBe(true)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ epoch: 3 })
  })

  it('a member removed from G links the planted epoch down to the E_0 it held: the fresh device also reads the group\'s history, and writes under the planted key', async () => {
    const e0 = v1.randomBytes(32) // what M held before its removal
    const planted = v1.randomBytes(32)
    serve(G, planted, 1, MALLORY, [linkUnder(G, 1, planted, e0)])
    const opened = await openAndWrite(G)
    expect(opened && same(keyAt(opened.epochs, 0), e0)).toBe(true)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ epoch: 1 })
  })

  it('a consent accept holds nothing, so the first open after it takes the plant - unlike a tenant accept (inbox #740)', async () => {
    const accept = vi.fn<ScopesApi['acceptGroupInvitation']>().mockResolvedValue(undefined)
    const planted = v1.randomBytes(32)
    serve(G, planted, 0, MALLORY, [], { acceptGroupInvitation: accept })
    await acceptGroupInvitation(G)
    expect(accept).toHaveBeenCalledWith({ scopeId: G })
    expect(await heldEpochKey(G)).toBeNull()
    const opened = await openAndWrite(G)
    expect(opened && same(opened.key, planted)).toBe(true)
  })

  it('the gain over a group of M\'s own is none: the client judges the plant in G exactly as it judges G_OWN, which M makes and adds the victim to without the operator', async () => {
    // Everything that tells the two apart on the victim's device is the server's word: the scope id it lists, the
    // member list, the `addedBy` of a proposal - and the name, which in both M seals under a key it chose.
    const k = v1.randomBytes(32)
    serve(G, k, 0, MALLORY)
    const plant = await openAndWrite(G)
    serve(G_OWN, k, 0, MALLORY)
    const own = await openAndWrite(G_OWN)
    expect(plant && own && same(plant.key, own.key)).toBe(true)
    expect([plant?.kind, plant?.epochs.current]).toEqual([own?.kind, own?.epochs.current])
    expect(createNode).toHaveBeenCalledTimes(2)
  })
})

describe('what holds (ADR 0111 decisions 15 and 23)', () => {
  it('a granter the tenant does not anchor - a membership the server made - is refused even on a fresh device; nothing is written', async () => {
    serve(G, v1.randomBytes(32), 0, SERVER_MADE)
    expect(await openAndWrite(G)).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
  })

  it('a device that held G\'s key refuses M\'s plant, the removed member\'s link to the older E_0 included (epoch_unlinked)', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    serve(G, e1, 1, ALICE, [linkUnder(G, 1, e1, e0)])
    await openAndWrite(G) // holds E_1 now
    createNode.mockClear()
    const planted = v1.randomBytes(32)
    serve(G, planted, 2, MALLORY, [linkUnder(G, 1, e1, e0), linkUnder(G, 2, planted, e0)])
    expect(await openAndWrite(G)).toBeNull()
    expect(createNode).not.toHaveBeenCalled()
  })
})

describe('honest controls: every legitimate first and later grant of a group opens', () => {
  it('the group owner\'s add, opened first on a fresh device', async () => {
    const e0 = v1.randomBytes(32)
    serve(G, e0, 0, ALICE)
    const opened = await openAndWrite(G)
    expect(opened && same(opened.key, e0)).toBe(true)
  })

  it('a plain member\'s re-key, opened first on a fresh device (a new or linked device of an existing member)', async () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    serve(G, e1, 1, BOB, [linkUnder(G, 1, e1, e0)])
    const opened = await openAndWrite(G)
    expect(opened && same(keyAt(opened.epochs, 0), e0)).toBe(true)
    expect(createNode.mock.calls[0][0].createNodeRequest).toMatchObject({ epoch: 1 })
  })

  it('a consent add by the owner, opened first after the accept', async () => {
    const e0 = v1.randomBytes(32)
    serve(G, e0, 0, ALICE, [], { acceptGroupInvitation: vi.fn<ScopesApi['acceptGroupInvitation']>().mockResolvedValue(undefined) })
    await acceptGroupInvitation(G)
    const opened = await openAndWrite(G)
    expect(opened && same(opened.key, e0)).toBe(true)
  })

  it('a re-key by any member after this device held the group: linked down to the held key, it opens', async () => {
    const e0 = v1.randomBytes(32)
    serve(G, e0, 0, ALICE)
    await openAndWrite(G)
    const e1 = v1.randomBytes(32)
    serve(G, e1, 1, BOB, [linkUnder(G, 1, e1, e0)])
    const opened = await openAndWrite(G)
    expect(opened?.epochs.current).toBe(1)
  })
})
