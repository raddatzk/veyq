import 'fake-indexeddb/auto'
// Who's who in a tenant (inbox #562, ADR 0111 decision 15). The server is faked - the tenant's directory, its verification
// rows, the pin store's endpoint and the key chains - and the vault is mocked; the trust code it reads is REAL: the
// join records are signed owner → invite → newcomer and sealed under the tenant key, the chains are signed step by step
// (`src/test/keyChain.ts`), the pin store seals and walks as in production, and every verification is signed, sealed and
// counted by `tenantTrust.ts`. Each negative sits beside a positive control on the same predicate.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fakePinServer, pinOf, pinsOn, sealPinStore } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, genesis, headOf, step, type BuiltChain, type ChainKeys } from '../../test/keyChain'
import { fakeApi } from '../../test/apiMock'

vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn(), tenantsApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))
vi.mock('../spaces/members', () => ({ listScopeMembers: vi.fn() }))
vi.mock('../spaces/scopes', () => ({ openScopeById: vi.fn(), listMyScopes: vi.fn() }))
vi.mock('../spaces/grantTrust', () => ({ me: vi.fn() }))

import { loadPeopleOverview, orderPeople, type OverviewPerson } from './peopleOverview'
import { forgetVerifiedPins } from './identityPins'
import { NEW_FOR_MS } from './tenantTrust'
import { TrustStateRefused } from './trustMarks'
import { tenantsApi } from '../../api/client'
import { identityPrivate, masterKey } from '../../app/vault'
import { listScopeMembers } from '../spaces/members'
import { openScopeById, type OpenedScope } from '../spaces/scopes'
import { me } from '../spaces/grantTrust'
import { ScopeKind, ScopeMemberRoleEnum, type ScopeMember } from '../../api/generated/scopes'
import type { MemberVerification, TenantsApi } from '../../api/generated/tenants'

const T = '7e000000-0000-4000-8000-0000000000f2'
const ME = '0e000000-0000-4000-8000-000000000011'
const OWNER = '0e000000-0000-4000-8000-000000000012'
const JOINER = '0e000000-0000-4000-8000-000000000013'
const VERIFIER = '0e000000-0000-4000-8000-000000000014'
const SUBJECT = '0e000000-0000-4000-8000-000000000015'
const STRANGER = '0e000000-0000-4000-8000-000000000016'
const PROPOSED = '0e000000-0000-4000-8000-000000000017'

const DAY = 24 * 60 * 60 * 1000

let MK: Uint8Array
let K0: Uint8Array
let chains: ReturnType<typeof fakeKeyChains>
let directory: ScopeMember[]

const opened = (): OpenedScope => {
  const key = K0.slice()
  return {
    scopeId: T, tenantId: T, kind: ScopeKind.Tenant, key, epochs: { epoch: 0, key },
    nameEpoch: 0, deletedAt: null, purgeAfter: null,
  }
}

type Person = { userId: string; chain: BuiltChain }
const keysOf = (p: Person): ChainKeys => p.chain.keys.at(-1)!
const born = (userId: string): Person => ({ userId, chain: chains.born(userId, chainKeys()) })
/** `p` secured their account `daysAgo` days ago: one more step, served from now on. */
function rotated(p: Person, daysAgo: number): Person {
  const chain = step(p.chain, chainKeys(), { createdAt: new Date(Date.now() - daysAgo * DAY) })
  chains.serve(chain)
  return { userId: p.userId, chain }
}
function row(p: Person, role: ScopeMemberRoleEnum, record?: invite.JoinRecord, o: { pendingConsent?: boolean } = {}): ScopeMember {
  return {
    userId: p.userId, username: `u${p.userId.slice(-2)}`, avatarRevision: 0, role, identityPublicKey: bundleB64(keysOf(p)),
    keyChainHead: headOf(p.chain), pending: false, pendingConsent: o.pendingConsent,
    sealedJoinRecord: record ? b64std(invite.sealJoinRecord(K0, { tenantId: T, userId: p.userId }, record)) : null,
  }
}
/** `p`'s join record through an invite the owner `by` signed with their current identity. */
function joinedThrough(p: Person, by: Person): invite.JoinRecord {
  const inviteKey = invite.inviteKeyFromSeed(invite.generateInviteSeed())
  const inviteId = crypto.randomUUID()
  const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
  const expiresAt = Math.floor(Date.now() / 1000) + 3600
  const inviteKeySignature = invite.signInviteKey(keysOf(by).identity.priv.sign, { tenantId: T, inviteId, invitePublicKey, expiresAt })
  return invite.signJoinRecord(
    { tenantId: T, userId: p.userId },
    {
      identityPublicKey: v1.serializeIdentityPublic(keysOf(p).identity.pub), secretKeyProofPublicKey: keysOf(p).proof.publicKey,
      inviteId, invitePublicKey, inviteKeySignature, inviteExpiresAt: expiresAt,
      signerUserId: by.userId, signerIdentityPublicKey: v1.serializeIdentityPublic(keysOf(by).identity.pub),
    },
    inviteKey.priv.sign, keysOf(p).identity.priv.sign,
  )
}

let self: Person
function signedInAs(p: Person) {
  self = p
  vi.mocked(identityPrivate).mockImplementation(() => {
    const priv = keysOf(self).identity.priv
    return { kemSeed: priv.kemSeed.slice(), sign: priv.sign.slice() }
  })
}

/** `verifier` (at their current head and key) confirmed `subject` at `subjectHead` (their current head by default). */
function verifiedBy(verifier: Person, subject: Person, subjectHead = headOf(subject.chain)): MemberVerification {
  const address = { tenantId: T, verifierUserId: verifier.userId, subjectUserId: subject.userId }
  const v = verification.signVerification(
    address, { subjectHead: fromB64std(subjectHead), verifierHead: fromB64std(headOf(verifier.chain)) }, keysOf(verifier).identity.priv.sign,
  )
  const sealedStatement = b64std(verification.sealVerification(K0, { ...address, epoch: 0 }, v))
  return { verifierUserId: verifier.userId, subjectUserId: subject.userId, epoch: 0, sealedStatement, createdAt: new Date() }
}
function serveVerifications(rows: MemberVerification[]) {
  const listVerifications = vi.fn<TenantsApi['listVerifications']>(async () => ({ verifications: rows, more: false }))
  fakeApi(tenantsApi, { listVerifications })
  return listVerifications
}

const byId = (people: OverviewPerson[], userId: string): OverviewPerson | undefined => people.find((p) => p.userId === userId)

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory()
  MK = v1.randomBytes(32)
  K0 = v1.randomBytes(32)
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  forgetVerifiedPins()
  fakePinServer()
  chains = fakeKeyChains()
  directory = []
  vi.mocked(listScopeMembers).mockImplementation(async () => directory)
  vi.mocked(openScopeById).mockImplementation(async (id: string) => (id === T ? opened() : null))
  vi.mocked(me).mockImplementation(async (priv) => {
    const pub = v1.identityPublicFromPrivate(priv)
    return { userId: self.userId, pub, pubB64: b64std(v1.serializeIdentityPublic(pub)) }
  })
  signedInAs(born(ME))
  serveVerifications([])
})

describe('who is listed', () => {
  it('everyone the directory lists but this account and a pending proposal', async () => {
    const owner = born(OWNER)
    const proposed = born(PROPOSED)
    directory = [row(self, 'OWNER'), row(owner, 'OWNER'), row(proposed, 'MEMBER', undefined, { pendingConsent: true })]
    const { available, people } = await loadPeopleOverview(T, ME)
    expect(available).toBe(true)
    expect(people.map((p) => p.userId)).toEqual([OWNER])
  })

  it('a tenant this device cannot open lists nobody and says so', async () => {
    directory = [row(born(OWNER), 'OWNER')]
    vi.mocked(openScopeById).mockResolvedValue(null)
    expect(await loadPeopleOverview(T, ME)).toEqual({ available: false, people: [] })
  })
})

describe('the state each person shows (ADR 0111 decision 15)', () => {
  it('an anomaly is never shown as verified - not even with a well-signed verification of the key the server serves for it', async () => {
    const verifier = born(VERIFIER)
    const stranger = born(STRANGER) // a non-owner without a join record: a membership only the server can have made
    const subject = born(SUBJECT)
    directory = [row(self, 'OWNER'), row(verifier, 'OWNER'), row(stranger, 'MEMBER'), row(subject, 'OWNER')]
    serveVerifications([verifiedBy(verifier, stranger), verifiedBy(verifier, subject)])
    const { people } = await loadPeopleOverview(T, ME)
    expect(byId(people, STRANGER)).toMatchObject({ state: 'anomaly', verifiedBy: [], isNew: false, rotatedAt: null })
    // control: the same verifier's row for an anchored member counts
    expect(byId(people, SUBJECT)).toMatchObject({ state: 'verified', verifiedBy: [VERIFIER] })
  })

  it('a joined member is anchored through the owner\'s invite; the same member without a join record is the anomaly', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    directory = [row(self, 'OWNER'), row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner))]
    expect(byId((await loadPeopleOverview(T, ME)).people, JOINER)?.state).toBe('unverified')
    globalThis.indexedDB = new IDBFactory() // a fresh device: no marks, no pins
    forgetVerifiedPins()
    fakePinServer()
    directory = [row(self, 'OWNER'), row(owner, 'OWNER'), row(joiner, 'MEMBER')]
    expect(byId((await loadPeopleOverview(T, ME)).people, JOINER)?.state).toBe('anomaly')
  })

  it('a verification of a head the subject has since rotated away from does not count (control: of the current head)', async () => {
    const verifier = born(VERIFIER)
    const before = born(SUBJECT)
    const subject = rotated(before, 2)
    directory = [row(self, 'OWNER'), row(verifier, 'OWNER'), row(subject, 'OWNER')]
    serveVerifications([verifiedBy(verifier, subject, headOf(before.chain))])
    expect(byId((await loadPeopleOverview(T, ME)).people, SUBJECT)).toMatchObject({ state: 'unverified', verifiedBy: [] })
    serveVerifications([verifiedBy(verifier, subject)])
    expect(byId((await loadPeopleOverview(T, ME)).people, SUBJECT)).toMatchObject({ state: 'verified', verifiedBy: [VERIFIER] })
  })

  it('this account\'s own verification is named like anyone\'s', async () => {
    const subject = born(SUBJECT)
    directory = [row(self, 'OWNER'), row(subject, 'OWNER')]
    serveVerifications([verifiedBy(self, subject)])
    expect(byId((await loadPeopleOverview(T, ME)).people, SUBJECT)?.verifiedBy).toEqual([ME])
  })

  it('new while this account first saw them under 14 days ago (control: two weeks and more)', async () => {
    const subject = born(SUBJECT)
    const known = born(OWNER)
    directory = [row(self, 'OWNER'), row(subject, 'OWNER'), row(known, 'OWNER')]
    fakePinServer(sealPinStore(MK, { [OWNER]: pinOf(bundleB64(keysOf(known)), Date.now() - NEW_FOR_MS - 1, headOf(known.chain)) }, 1))
    const { people } = await loadPeopleOverview(T, ME)
    expect(byId(people, SUBJECT)?.isNew).toBe(true) // first seen by this very read
    expect(byId(people, OWNER)?.isNew).toBe(false)
  })

  it('a member whose chain could not be read (a failed request) shows no state - never a false anomaly', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    const other = born(SUBJECT)
    directory = [row(self, 'OWNER'), row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner)), row(other, 'OWNER')]
    chains.getKeyChain.mockImplementation(async ({ userId }) => {
      if (userId === JOINER) throw new Error('offline')
      const p = userId === OWNER ? owner : other
      return { userId, head: headOf(p.chain), steps: p.chain.steps }
    })
    const { people } = await loadPeopleOverview(T, ME)
    expect(byId(people, JOINER)?.state).toBe('unchecked')
    expect(byId(people, SUBJECT)?.state).toBe('unverified') // control
  })

  it('a pin store this device refuses is said, not read as nobody', async () => {
    directory = [row(self, 'OWNER'), row(born(OWNER), 'OWNER')]
    fakePinServer({ blob: 'garbage', revision: 3, entries: new Map() })
    await expect(loadPeopleOverview(T, ME)).rejects.toBeInstanceOf(TrustStateRefused)
  })
})

describe('a new identity, signed by the previous key', () => {
  it('shows while the rotation is under 14 days old (control: an older one shows nothing)', async () => {
    const recent = rotated(born(SUBJECT), 3)
    const old = rotated(born(OWNER), 20)
    directory = [row(self, 'OWNER'), row(recent, 'OWNER'), row(old, 'OWNER')]
    const { people } = await loadPeopleOverview(T, ME)
    expect(byId(people, SUBJECT)?.rotatedAt).toEqual(recent.chain.steps[1].createdAt)
    expect(byId(people, OWNER)?.rotatedAt).toBeNull()
  })

  it('a genesis head reads no chain for the date; a pin at a later step does', async () => {
    const plain = born(OWNER)
    const moved = rotated(born(SUBJECT), 1)
    directory = [row(self, 'OWNER'), row(plain, 'OWNER'), row(moved, 'OWNER')]
    // Both already pinned at their served heads: the anchor check reads no chain for a root owner it pins at the head.
    fakePinServer(sealPinStore(MK, {
      [OWNER]: pinOf(bundleB64(keysOf(plain)), 1, headOf(plain.chain), { index: 0 }),
      [SUBJECT]: pinOf(bundleB64(keysOf(moved)), 1, headOf(moved.chain), { index: 1 }),
    }, 1))
    const { people } = await loadPeopleOverview(T, ME)
    const read = chains.getKeyChain.mock.calls.map(([r]) => r.userId)
    expect(read).not.toContain(OWNER)
    expect(read).toContain(SUBJECT)
    expect(byId(people, SUBJECT)?.rotatedAt).toEqual(moved.chain.steps[1].createdAt)
  })

  it('a chain that is only its genesis shows none, even when it is read (control: one rotation step shows)', async () => {
    // Both born yesterday, so a genesis read as a rotation would fall inside the 14 days. The pins say a later step than
    // the genesis (index 1), so the overview reads both chains for the date: the chain decides, not the pin's index.
    const plain = { userId: OWNER, chain: genesis(OWNER, chainKeys(), new Date(Date.now() - DAY)) }
    chains.serve(plain.chain)
    const moved = rotated(born(SUBJECT), 1)
    directory = [row(self, 'OWNER'), row(plain, 'OWNER'), row(moved, 'OWNER')]
    fakePinServer(sealPinStore(MK, {
      [OWNER]: pinOf(bundleB64(keysOf(plain)), 1, headOf(plain.chain), { index: 1 }),
      [SUBJECT]: pinOf(bundleB64(keysOf(moved)), 1, headOf(moved.chain), { index: 1 }),
    }, 1))
    const { people } = await loadPeopleOverview(T, ME)
    expect(chains.getKeyChain.mock.calls.map(([r]) => r.userId)).toContain(OWNER) // the genesis chain WAS read
    expect(byId(people, OWNER)?.rotatedAt).toBeNull()
    expect(byId(people, SUBJECT)?.rotatedAt).toEqual(moved.chain.steps[1].createdAt)
  })

  it('never for an anomaly', async () => {
    const stranger = rotated(born(STRANGER), 1)
    directory = [row(self, 'OWNER'), row(stranger, 'MEMBER')]
    expect(byId((await loadPeopleOverview(T, ME)).people, STRANGER)).toMatchObject({ state: 'anomaly', rotatedAt: null })
  })
})

describe('the order', () => {
  it('anomalies first, then by name', () => {
    const p = (userId: string, state: OverviewPerson['state']): OverviewPerson =>
      ({ userId, username: userId, state, isNew: false, verifiedBy: [], rotatedAt: null, replaced: false })
    const ordered = orderPeople([p('cleo', 'verified'), p('zed', 'anomaly'), p('anna', 'unverified'), p('bob', 'anomaly')], (x) => x.username)
    expect(ordered.map((x) => x.userId)).toEqual(['bob', 'zed', 'anna', 'cleo'])
  })
})

// Inbox #710: one overview reads the pin store's head once and judges each join record's signer once, however many
// members the tenant has; a second overview reads afresh.
describe('what one overview reads (inbox #710)', () => {
  it('the pin head once, the signer\'s chain once as a signer (and once as a member), each member\'s once', async () => {
    const root = born(OWNER)
    const signer = born(VERIFIER)
    const members = Array.from({ length: 8 }, (_, i) => born(`0e000000-0000-4000-8000-0000000002${String(i).padStart(2, '0')}`))
    directory = [
      row(self, 'OWNER'), row(root, 'OWNER'), row(signer, 'OWNER', joinedThrough(signer, root)),
      ...members.map((m) => row(m, 'MEMBER', joinedThrough(m, signer))),
    ]
    const pins = fakePinServer()
    const reads = (userId: string) => chains.getKeyChain.mock.calls.filter(([r]) => r.userId === userId).length
    const { people } = await loadPeopleOverview(T, ME)
    expect(people.map((p) => p.state)).toEqual(people.map(() => 'unverified')) // everyone anchored
    expect(people.every((p) => p.isNew)).toBe(true)
    expect(pins.getIdentityPins).toHaveBeenCalledTimes(1)
    expect(reads(signer.userId)).toBe(2)
    expect(reads(root.userId)).toBe(1)
    expect(members.map((m) => reads(m.userId))).toEqual(members.map(() => 1))
    await loadPeopleOverview(T, ME)
    expect(pins.getIdentityPins).toHaveBeenCalledTimes(2) // nothing kept across overviews
  })
})

// Inbox #741: one overview stores the pins of its first contacts in one write at its end - also when it ends in a throw
// midway, so what it pinned is kept.
describe('what one overview writes (inbox #741)', () => {
  /** A root owner, an owner S who joined through the root's invite, and `n` members who joined through S's. */
  function tenantOf(n: number) {
    const root = born(OWNER)
    const signer = born(VERIFIER)
    const members = Array.from({ length: n }, (_, i) => born(`0e000000-0000-4000-8000-0000000004${String(i).padStart(2, '0')}`))
    directory = [
      row(self, 'OWNER'), row(root, 'OWNER'), row(signer, 'OWNER', joinedThrough(signer, root)),
      ...members.map((m) => row(m, 'MEMBER', joinedThrough(m, signer))),
    ]
    return { root, signer, members }
  }

  it('cold, at 50 members: every first contact pinned by one write', async () => {
    const { root, signer, members } = tenantOf(50)
    const pins = fakePinServer()
    const listVerifications = serveVerifications([])
    const { people } = await loadPeopleOverview(T, ME)
    expect(people.every((p) => p.state === 'unverified' && p.isNew)).toBe(true) // the same answers
    expect(Object.keys(pinsOn(MK, pins.state)).sort()).toEqual([root, signer, ...members].map((p) => p.userId).sort())
    const counts = {
      pinHead: pins.getIdentityPins.mock.calls.length,
      pinList: pins.listIdentityPinEntries.mock.calls.length,
      pinWrite: pins.writeIdentityPins.mock.calls.length,
      chains: chains.getKeyChain.mock.calls.length,
      tenant: vi.mocked(listScopeMembers).mock.calls.length + listVerifications.mock.calls.length,
    }
    // 52 first contacts: one write where each wrote its own - 109 requests before (52 writes), 58 now
    expect(counts).toEqual({ pinHead: 1, pinList: 1, pinWrite: 1, chains: 53, tenant: 2 })
  })

  it('a pass that ends in a throw midway still writes what it pinned before it', async () => {
    const { root, signer, members } = tenantOf(4)
    const pins = fakePinServer()
    const real = chains.getKeyChain.getMockImplementation()!
    chains.getKeyChain.mockImplementation(async (r) => {
      if (r.userId === members[2].userId) throw new TrustStateRefused('trust_state_unreadable', 'pins')
      return real(r)
    })
    await expect(loadPeopleOverview(T, ME)).rejects.toBeInstanceOf(TrustStateRefused)
    expect(pins.writeIdentityPins).toHaveBeenCalledTimes(1)
    expect(Object.keys(pinsOn(MK, pins.state)).sort()).toEqual([root, signer, members[0], members[1]].map((p) => p.userId).sort())
  })

  it('a store the pass\'s write re-reads after a 409 and this device refuses is said, as at any read (control: one it opens)', async () => {
    tenantOf(2)
    const pins = fakePinServer()
    pins.racedBy(sealPinStore(v1.randomBytes(32), {}, 1)) // another key's store: it does not open here
    await expect(loadPeopleOverview(T, ME)).rejects.toBeInstanceOf(TrustStateRefused)
    const ok = fakePinServer()
    ok.racedBy(sealPinStore(MK, {}, 1))
    expect((await loadPeopleOverview(T, ME)).people).toHaveLength(4)
  })

  it('...the same pass\'s write failing for a request leaves the pins unwritten, and the overview stands', async () => {
    tenantOf(2)
    const pins = fakePinServer()
    pins.writeIdentityPins.mockRejectedValueOnce(new Error('offline'))
    expect((await loadPeopleOverview(T, ME)).people.map((p) => p.state)).toEqual(['unverified', 'unverified', 'unverified', 'unverified'])
    expect(pins.state.entries.size).toBe(0)
  })
})
