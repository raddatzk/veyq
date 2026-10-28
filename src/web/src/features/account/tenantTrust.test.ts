import 'fake-indexeddb/auto'
// Trust in a tenant (ADR 0111 decisions 12-16): who the join records anchor, which verifications count, and what a
// rotation carries. The server is faked — the tenant's directory, its verification rows, the pin store's endpoint and
// the key chains — and the vault is mocked; everything else is REAL: the join records are signed owner → invite →
// newcomer by `@veyq/crypto/invite` and sealed under the tenant key, the chains are built and signed step by step
// (`src/test/keyChain.ts`), the pin store seals and walks as in production, and every verification is signed, sealed and
// opened by `@veyq/crypto/verification`. Each negative fails exactly its own check, beside a positive control on the
// same predicate.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fakePinServer, pinOf, pinsOn, sealPinStore } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, headOf, step, type BuiltChain, type ChainKeys } from '../../test/keyChain'
import { fakeApi } from '../../test/apiMock'

vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn(), tenantsApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))
vi.mock('../spaces/members', () => ({ listScopeMembers: vi.fn() }))
vi.mock('../spaces/scopes', () => ({ openScopeById: vi.fn(), listMyScopes: vi.fn() }))
vi.mock('../spaces/grantTrust', () => ({ me: vi.fn() }))

import {
  collectMyVerifications,
  countVerifications,
  loadTenantView,
  personInTenant,
  resignVerifications,
  tenantBadges,
  verifyPerson,
  NEW_FOR_MS,
  type CarriedVerification,
} from './tenantTrust'
import { anchorIdentity, forgetVerifiedPins, IdentityMovedError, IdentityUnanchoredError } from './identityPins'
import { fingerprintDigestB64 } from './safetyCode'
import { tenantsApi } from '../../api/client'
import { identityPrivate, masterKey } from '../../app/vault'
import { listScopeMembers } from '../spaces/members'
import { openScopeById, type OpenedScope } from '../spaces/scopes'
import { me } from '../spaces/grantTrust'
import { ScopeKind, ScopeMemberRoleEnum, type ScopeMember } from '../../api/generated/scopes'
import type { MemberVerification, TenantsApi } from '../../api/generated/tenants'

const T = '7e000000-0000-4000-8000-0000000000f1'
const ME = '0e000000-0000-4000-8000-000000000001'
const OWNER = '0e000000-0000-4000-8000-000000000002'
const SIGNER = '0e000000-0000-4000-8000-000000000003'
const JOINER = '0e000000-0000-4000-8000-000000000004'
const VERIFIER = '0e000000-0000-4000-8000-000000000005'
const SUBJECT = '0e000000-0000-4000-8000-000000000006'
const STRANGER = '0e000000-0000-4000-8000-000000000007'

let MK: Uint8Array
/** The tenant key at epoch 0 — what the join records and the verifications are sealed under. */
let K0: Uint8Array
let chains: ReturnType<typeof fakeKeyChains>
/** The tenant's directory as the server serves it (`listScopeMembers` of the tenant scope). */
let directory: ScopeMember[]

/** The tenant opened as this device opens it: a fresh copy of the epoch keys each time (the code zeroes what it opened). */
const opened = (): OpenedScope => {
  const key = K0.slice()
  return {
    scopeId: T, tenantId: T, kind: ScopeKind.Tenant, key, epochs: { current: 0, keys: new Map([[0, key]]) },
    nameEpoch: 0, deletedAt: null, purgeAfter: null,
  }
}
const open = async (tenantId: string) => (tenantId === T ? opened() : null)

// ── People ───────────────────────────────────────────────────────────────────────────────────────────────────

type Person = { userId: string; chain: BuiltChain }
const keysOf = (p: Person): ChainKeys => p.chain.keys.at(-1)!
/** `userId` born with `keys`, their chain served. */
const born = (userId: string, keys: ChainKeys = chainKeys()): Person => ({ userId, chain: chains.born(userId, keys) })
/** `p` secured their account: one more step to `next`, served from now on. */
function rotated(p: Person, next: ChainKeys = chainKeys()): Person {
  const chain = step(p.chain, next)
  chains.serve(chain)
  return { userId: p.userId, chain }
}
/** Who endorses a member's join record, with which key — over the record as stored, or (an attack) over another
 *  sealing of it. */
type Endorsement = { signerUserId: string; by: v1.Identity; overAnotherSealing?: boolean }

/** `p` as the tenant's directory lists them: their newest key and head, a role, the join record they wrote, and the
 *  endorsement of it the record's signer wrote (cr-trust-anchors-survive-rotation decision 9). */
function row(
  p: Person, role: ScopeMemberRoleEnum, record?: invite.JoinRecord, o: { pendingConsent?: boolean; endorsed?: Endorsement } = {},
): ScopeMember {
  const sealed = record ? invite.sealJoinRecord(K0, { tenantId: T, userId: p.userId }, record) : null
  const e = o.endorsed
  const endorsedBytes = e?.overAnotherSealing ? invite.sealJoinRecord(K0, { tenantId: T, userId: p.userId }, record!) : sealed
  return {
    userId: p.userId, username: p.userId.slice(-4), avatarRevision: 0, role, identityPublicKey: bundleB64(keysOf(p)),
    keyChainHead: headOf(p.chain), pending: false, pendingConsent: o.pendingConsent,
    sealedJoinRecord: sealed ? b64std(sealed) : null,
    joinEndorsement: e && endorsedBytes
      ? b64std(invite.signJoinEndorsement(e.by.priv.sign, { tenantId: T, userId: p.userId, signerUserId: e.signerUserId, sealedJoinRecord: endorsedBytes }))
      : null,
  }
}
const servedOf = (p: Person) => ({ identityPublicKey: bundleB64(keysOf(p)), keyChainHead: headOf(p.chain) })

/**
 * The join record `newcomer` writes at an invite's accept (ADR 0111 decision 3): their identity and proof key, the invite
 * key `signer` signed with `signerIdentity` (or, for an attack, a key signed by `inviteKeySigner`), and both signatures.
 */
function joinRecord(
  newcomer: { userId: string; keys: ChainKeys }, signer: { userId: string; identity: v1.Identity },
  o: { inviteKeySigner?: v1.Identity } = {},
): invite.JoinRecord {
  const inviteKey = invite.inviteKeyFromSeed(invite.generateInviteSeed())
  const inviteId = crypto.randomUUID()
  const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
  const expiresAt = Math.floor(Date.now() / 1000) + 3600
  const inviteKeySignature = invite.signInviteKey(
    (o.inviteKeySigner ?? signer.identity).priv.sign, { tenantId: T, inviteId, invitePublicKey, expiresAt },
  )
  return invite.signJoinRecord(
    { tenantId: T, userId: newcomer.userId },
    {
      identityPublicKey: v1.serializeIdentityPublic(newcomer.keys.identity.pub),
      secretKeyProofPublicKey: newcomer.keys.proof.publicKey,
      inviteId, invitePublicKey, inviteKeySignature, inviteExpiresAt: expiresAt,
      signerUserId: signer.userId, signerIdentityPublicKey: v1.serializeIdentityPublic(signer.identity.pub),
    },
    inviteKey.priv.sign, newcomer.keys.identity.priv.sign,
  )
}
/** `p`'s join record through an invite `by` signed with their current identity. */
const joinedThrough = (p: Person, by: Person, o: { inviteKeySigner?: v1.Identity } = {}) =>
  joinRecord({ userId: p.userId, keys: keysOf(p) }, { userId: by.userId, identity: keysOf(by).identity }, o)

/** This account: its chain served, its vault holding the newest identity. */
let self: Person
function signedInAs(p: Person) {
  self = p
  vi.mocked(identityPrivate).mockImplementation(() => {
    const priv = keysOf(self).identity.priv
    return { kemSeed: priv.kemSeed.slice(), sign: priv.sign.slice() }
  })
}

// ── Verifications ────────────────────────────────────────────────────────────────────────────────────────────

/** A verification row as `putMyVerification` stores it: `verifierUserId` confirmed `subjectUserId` at these heads,
 *  signed by `signKey`, sealed under the tenant key at epoch 0. */
function verificationRow(
  verifierUserId: string, subjectUserId: string, heads: { subjectHead: string; verifierHead: string }, signKey: Uint8Array,
): MemberVerification {
  const address = { tenantId: T, verifierUserId, subjectUserId }
  const v = verification.signVerification(
    address, { subjectHead: fromB64std(heads.subjectHead), verifierHead: fromB64std(heads.verifierHead) }, signKey,
  )
  const sealedStatement = b64std(verification.sealVerification(K0, { ...address, epoch: 0 }, v))
  return { verifierUserId, subjectUserId, epoch: 0, sealedStatement, createdAt: new Date() }
}
/** `verifier` (at their current head and key) confirmed `subject` at `subjectHead` (their current head by default). */
const verifiedBy = (verifier: Person, subject: Person, o: { subjectHead?: string; signKey?: Uint8Array; verifierHead?: string } = {}) =>
  verificationRow(
    verifier.userId, subject.userId,
    { subjectHead: o.subjectHead ?? headOf(subject.chain), verifierHead: o.verifierHead ?? headOf(verifier.chain) },
    o.signKey ?? keysOf(verifier).identity.priv.sign,
  )

/** The tenant's verification rows, one per (verifier, subject); a write lands as the caller's own row. */
function fakeVerifications(initial: MemberVerification[] = [], caller: () => string = () => self.userId) {
  const rows = new Map(initial.map((r) => [`${r.verifierUserId}:${r.subjectUserId}`, r]))
  const listVerifications = vi.fn<TenantsApi['listVerifications']>(async ({ subjectUserId, verifierUserId }) => ({
    verifications: [...rows.values()].filter(
      (r) => (!subjectUserId || r.subjectUserId === subjectUserId) && (!verifierUserId || r.verifierUserId === verifierUserId),
    ),
    more: false,
  }))
  const putMyVerification = vi.fn<TenantsApi['putMyVerification']>(async ({ subjectUserId, putVerificationRequest: r }) => {
    const verifierUserId = caller()
    rows.set(`${verifierUserId}:${subjectUserId}`, { verifierUserId, subjectUserId, epoch: r.epoch ?? 0, sealedStatement: r.sealedStatement, createdAt: new Date() })
  })
  fakeApi(tenantsApi, { listVerifications, putMyVerification })
  return { rows, listVerifications, putMyVerification, all: () => [...rows.values()] }
}

/** Who `countVerifications` counts for `subjects` (each at its current head), read in a fresh view of the tenant. */
async function counted(...subjects: Person[]): Promise<Map<string, string[]>> {
  const view = await loadTenantView(T, open)
  const rows = (await tenantsApi().listVerifications({ tenantId: T })).verifications
  return countVerifications(view, rows, opened().epochs, new Map(subjects.map((s) => [s.userId, headOf(s.chain)])))
}

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
  vi.mocked(openScopeById).mockImplementation(open)
  vi.mocked(me).mockImplementation(async (priv) => {
    const pub = v1.identityPublicFromPrivate(priv)
    return { userId: self.userId, pub, pubB64: b64std(v1.serializeIdentityPublic(pub)) }
  })
  signedInAs(born(ME))
  fakeVerifications()
})

// ── The anchor ───────────────────────────────────────────────────────────────────────────────────────────────

describe('the join record anchors a person in the tenant (ADR 0111 decisions 12, 15)', () => {
  it('a record whose invite key a NON-owner signed anchors nobody; the same record, its signer an owner, anchors', async () => {
    const owner = born(OWNER)
    const signer = born(SIGNER)
    const joiner = born(JOINER)
    const signerRecord = joinedThrough(signer, owner)
    const joinerRecord = joinedThrough(joiner, signer)

    // The signer is a member anchored by their own record — but no owner.
    directory = [row(owner, 'OWNER'), row(signer, 'MEMBER', signerRecord), row(joiner, 'MEMBER', joinerRecord)]
    const asMember = await loadTenantView(T, open)
    expect((await asMember.standing(SIGNER)).kind).toBe('joined') // the signer itself is anchored: only the role differs
    expect(await asMember.standing(JOINER)).toEqual({ kind: 'unanchored' })
    expect(await anchorIdentity(JOINER, servedOf(joiner), { tenant: asMember })).toBe('anomaly')

    // control: the same records, the signer listed as an owner
    directory = [row(owner, 'OWNER'), row(signer, 'OWNER', signerRecord), row(joiner, 'MEMBER', joinerRecord)]
    const asOwner = await loadTenantView(T, open)
    expect((await asOwner.standing(JOINER)).kind).toBe('joined')
    expect(await anchorIdentity(JOINER, servedOf(joiner), { tenant: asOwner })).toBe('firstUse')
  })

  it.each([
    ['its newcomer signature broken', (r: invite.JoinRecord) => ({ ...r, identitySignature: r.identitySignature.map((b, i) => (i === 0 ? b ^ 1 : b)) })],
    ['its invite signature broken', (r: invite.JoinRecord) => ({ ...r, inviteSignature: r.inviteSignature.map((b, i) => (i === 0 ? b ^ 1 : b)) })],
    ['naming a key it was not made for', (r: invite.JoinRecord) => ({ ...r, identityPublicKey: v1.serializeIdentityPublic(v1.generateIdentity().pub) })],
  ])('a record that does not verify (%s) anchors nobody; the untouched record anchors', async (_label, tamper) => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    const record = joinedThrough(joiner, owner)
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', tamper(record))]
    expect(await (await loadTenantView(T, open)).standing(JOINER)).toEqual({ kind: 'unanchored' })
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record)]
    expect((await (await loadTenantView(T, open)).standing(JOINER)).kind).toBe('joined')
  })

  it('an invite key the named owner did not sign anchors nobody (control: the owner signed it)', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner, { inviteKeySigner: v1.generateIdentity() }))]
    expect(await (await loadTenantView(T, open)).standing(JOINER)).toEqual({ kind: 'unanchored' })
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner))]
    expect((await (await loadTenantView(T, open)).standing(JOINER)).kind).toBe('joined')
  })

  it('the signer\'s key in the record must be anchored in turn: an owner\'s name beside a key not on their chain anchors nobody', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    const impostor = v1.generateIdentity() // a key the operator made, presented as the owner's
    const forged = joinRecord({ userId: JOINER, keys: keysOf(joiner) }, { userId: OWNER, identity: impostor })
    expect(invite.joinRecordVerifies({ tenantId: T, userId: JOINER }, forged)).toBe(true) // it holds on its own
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', forged)]
    expect(await (await loadTenantView(T, open)).standing(JOINER)).toEqual({ kind: 'unanchored' })
    // control: the owner's own key
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner))]
    expect((await (await loadTenantView(T, open)).standing(JOINER)).kind).toBe('joined')
  })

  // #571 (cr-trust-anchors-survive-rotation decision 10): a record its owner signed with a key they have since retired.
  /** JOINER's record through OWNER, signed while OWNER held their first key; OWNER has since secured their account. */
  function retiredKeyRecord() {
    const before = born(OWNER)
    const joiner = born(JOINER)
    const record = joinedThrough(joiner, before)
    const owner = rotated(before)
    return { joiner, record, owner, retired: keysOf(before).identity, current: keysOf(owner).identity }
  }
  const standingOf = async (userId: string) => (await (await loadTenantView(T, open)).standing(userId)).kind

  it('#571: a record a retired key signed, without an endorsement, anchors nobody; endorsed by the signer\'s CURRENT key it is joined', async () => {
    const { joiner, record, owner, current } = retiredKeyRecord()
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record)]
    expect(await standingOf(JOINER)).toBe('unanchored')
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current } })]
    expect(await standingOf(JOINER)).toBe('joined')
  })

  it('#571: an endorsement by the RETIRED key does not count — the thief holds that key (control: the current key\'s)', async () => {
    const { joiner, record, owner, retired, current } = retiredKeyRecord()
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: retired } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current } })]
    expect(await standingOf(JOINER)).toBe('joined')
  })

  it('#571: the current key\'s endorsement of ANOTHER sealed record does not count, nor one naming another signer', async () => {
    const { joiner, record, owner, current } = retiredKeyRecord()
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current, overAnotherSealing: true } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: STRANGER, by: current } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
  })

  it('#571: an endorsement by a key the server serves for the owner but their chain does not end at does not count', async () => {
    const { joiner, record, owner } = retiredKeyRecord()
    const operator = v1.generateIdentity()
    const servedOwner = { ...row(owner, 'OWNER'), identityPublicKey: b64std(v1.serializeIdentityPublic(operator.pub)) }
    directory = [servedOwner, row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: operator } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
  })

  it('control: a record naming the signer\'s current key needs no endorsement', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner))]
    expect(await standingOf(JOINER)).toBe('joined')
  })

  it('a non-owner without a join record is an anomaly — no chain read, no pin; an owner without one is a root, trusted on first use', async () => {
    const owner = born(OWNER)
    const stranger = born(STRANGER)
    directory = [row(owner, 'OWNER'), row(stranger, 'MEMBER')]
    const view = await loadTenantView(T, open)
    expect(await view.standing(STRANGER)).toEqual({ kind: 'unanchored' })
    expect(await anchorIdentity(STRANGER, servedOf(stranger), { tenant: view })).toBe('anomaly')
    expect(chains.getKeyChain).not.toHaveBeenCalled()

    expect(await view.standing(OWNER)).toEqual({ kind: 'root' })
    expect(await anchorIdentity(OWNER, servedOf(owner), { tenant: view })).toBe('firstUse')
  })

  it('a pending proposal, someone the directory does not list, and a cycle of signers anchor nobody', async () => {
    const owner = born(OWNER)
    const a = born(SIGNER)
    const b = born(JOINER)
    // a and b, both listed as owners, each joined through the other — no root under either
    directory = [row(owner, 'OWNER'), row(a, 'OWNER', joinedThrough(a, b)), row(b, 'OWNER', joinedThrough(b, a)), row(born(VERIFIER), 'MEMBER', joinedThrough(born(VERIFIER), owner), { pendingConsent: true })]
    const view = await loadTenantView(T, open)
    expect(await view.standing(SIGNER)).toEqual({ kind: 'unanchored' })
    expect(await view.standing(JOINER)).toEqual({ kind: 'unanchored' })
    expect(await view.standing(VERIFIER)).toEqual({ kind: 'unanchored' })
    expect(await view.standing(STRANGER)).toEqual({ kind: 'unanchored' })
  })

  it('a tenant this device cannot open reads unavailable for everyone — and a wrap there is an anomaly', async () => {
    const owner = born(OWNER)
    directory = [row(owner, 'OWNER')]
    const view = await loadTenantView(T, async () => null)
    expect(view.available).toBe(false)
    expect(await view.standing(OWNER)).toEqual({ kind: 'unavailable' })
    expect(await anchorIdentity(OWNER, servedOf(owner), { tenant: view })).toBe('anomaly')
  })
})

// ── Verifications ────────────────────────────────────────────────────────────────────────────────────────────

describe('a verification counts for every member of the tenant (ADR 0111 decisions 12-14)', () => {
  it('a verification of the subject\'s CURRENT head counts; one of a head they have since rotated away from does not', async () => {
    const verifier = born(VERIFIER)
    let subject = born(SUBJECT)
    const old = headOf(subject.chain)
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    fakeVerifications([verifiedBy(verifier, subject)])
    expect((await counted(subject)).get(SUBJECT)).toEqual([VERIFIER])

    subject = rotated(subject)
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    fakeVerifications([verifiedBy(verifier, subject, { subjectHead: old })])
    expect((await counted(subject)).get(SUBJECT)).toBeUndefined()
  })

  it('the thief case: a statement the verifier\'s RETIRED key signs after their rotation does not count; re-signed by the new key it does', async () => {
    const before = born(VERIFIER)
    const subject = born(SUBJECT)
    const verifier = rotated(before) // the verifier secured their account; a thief kept the old identity
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    // The thief signs with the retired key, naming the verifier's NEW head — the only head that could count.
    fakeVerifications([verifiedBy(verifier, subject, { signKey: keysOf(before).identity.priv.sign })])
    expect((await counted(subject)).get(SUBJECT)).toBeUndefined()

    // control: the same statement as the rotation's re-sign writes it, under the new identity
    const srv = fakeVerifications([], () => VERIFIER)
    const carried: CarriedVerification[] = [{ tenantId: T, subjectUserId: SUBJECT, subjectHead: subject.chain.head, epoch: 0, key: K0.slice() }]
    await resignVerifications(carried, VERIFIER, keysOf(verifier).identity.priv.sign, verifier.chain.head)
    expect(srv.all()).toHaveLength(1)
    expect((await counted(subject)).get(SUBJECT)).toEqual([VERIFIER])
  })

  it('a statement naming another head of the verifier than their current one does not count (control: their current head)', async () => {
    const before = born(VERIFIER)
    const subject = born(SUBJECT)
    const verifier = rotated(before)
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    fakeVerifications([verifiedBy(verifier, subject, { verifierHead: headOf(before.chain) })])
    expect((await counted(subject)).get(SUBJECT)).toBeUndefined()
    fakeVerifications([verifiedBy(verifier, subject)])
    expect((await counted(subject)).get(SUBJECT)).toEqual([VERIFIER])
  })

  it('a verification by a verifier the tenant does not anchor does not count (control: the same verifier as an owner)', async () => {
    const verifier = born(VERIFIER)
    const subject = born(SUBJECT)
    fakeVerifications([verifiedBy(verifier, subject)])
    directory = [row(verifier, 'MEMBER'), row(subject, 'OWNER')] // a member without a join record
    expect((await counted(subject)).get(SUBJECT)).toBeUndefined()
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    expect((await counted(subject)).get(SUBJECT)).toEqual([VERIFIER])
  })

  it('a row moved to another subject\'s slot neither opens nor counts', async () => {
    const verifier = born(VERIFIER)
    const subject = born(SUBJECT)
    const other = born(STRANGER)
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER'), row(other, 'OWNER')]
    const genuine = verifiedBy(verifier, other, { subjectHead: headOf(subject.chain) })
    fakeVerifications([{ ...genuine, subjectUserId: SUBJECT }])
    expect((await counted(subject)).get(SUBJECT)).toBeUndefined()
  })
})

describe('verifyPerson writes this account\'s own signed statement (ADR 0111 decisions 12, 14)', () => {
  it('after a matching compare it writes one row every member opens and counts: signed by my identity, naming their head and mine', async () => {
    const subject = born(SUBJECT)
    directory = [row(self, 'OWNER'), row(subject, 'OWNER')]
    const srv = fakeVerifications()
    await verifyPerson(T, SUBJECT, { head: headOf(subject.chain), fingerprint: fingerprintDigestB64(bundleB64(keysOf(subject))) })
    expect(srv.putMyVerification).toHaveBeenCalledTimes(1)
    const put = srv.putMyVerification.mock.calls[0][0]
    expect(put).toMatchObject({ tenantId: T, subjectUserId: SUBJECT, putVerificationRequest: { epoch: 0 } })
    const v = verification.openVerification(K0, { tenantId: T, verifierUserId: ME, subjectUserId: SUBJECT, epoch: 0 }, fromB64std(put.putVerificationRequest.sealedStatement))
    expect(b64std(v.subjectHead)).toBe(headOf(subject.chain))
    expect(b64std(v.verifierHead)).toBe(headOf(self.chain))
    expect(verification.verificationVerifies({ tenantId: T, verifierUserId: ME, subjectUserId: SUBJECT }, v, keysOf(self).identity.pub.sign)).toBe(true)
    // …and another member of the tenant counts it
    expect((await counted(subject)).get(SUBJECT)).toEqual([ME])
  })

  it('a head that moved under the compare writes nothing (IdentityMovedError); so does a key the tenant does not anchor (IdentityUnanchoredError)', async () => {
    const subject = born(SUBJECT)
    const compared = { head: headOf(subject.chain), fingerprint: fingerprintDigestB64(bundleB64(keysOf(subject))) }
    const srv = fakeVerifications()
    const moved = rotated(subject)
    directory = [row(self, 'OWNER'), row(moved, 'OWNER')]
    await expect(verifyPerson(T, SUBJECT, compared)).rejects.toBeInstanceOf(IdentityMovedError)

    directory = [row(self, 'OWNER'), row(subject, 'MEMBER')] // the compared head again — but no join record, no owner
    chains.serve(subject.chain)
    await expect(verifyPerson(T, SUBJECT, compared)).rejects.toBeInstanceOf(IdentityUnanchoredError)
    expect(srv.putMyVerification).not.toHaveBeenCalled()

    // control: the same compare against what is anchored writes
    directory = [row(self, 'OWNER'), row(subject, 'OWNER')]
    await verifyPerson(T, SUBJECT, compared)
    expect(srv.putMyVerification).toHaveBeenCalledTimes(1)
  })
})

// ── What a member sees ───────────────────────────────────────────────────────────────────────────────────────

describe('what a member sees (ADR 0111 decision 15)', () => {
  it('the profile: verified by whom, new, the rotation, and the anomaly with nothing to compare', async () => {
    const verifier = born(VERIFIER)
    const subject = rotated(born(SUBJECT))
    const stranger = born(STRANGER)
    directory = [row(self, 'OWNER'), row(verifier, 'OWNER'), row(subject, 'OWNER'), row(stranger, 'MEMBER')]
    fakeVerifications([verifiedBy(verifier, subject)])

    const read = await personInTenant(T, SUBJECT)
    expect(read).toMatchObject({
      anchor: 'firstUse', head: headOf(subject.chain), fingerprint: fingerprintDigestB64(bundleB64(keysOf(subject))),
      rotatedAt: subject.chain.steps[1].createdAt, isNew: true, verifiedBy: [VERIFIER],
    })
    expect(await personInTenant(T, STRANGER)).toMatchObject({ anchor: 'anomaly', head: null, fingerprint: null, verifiedBy: [] })
  })

  it('the badges of a member list: anomaly, verified, new, unverified — and none for this account', async () => {
    const verifier = born(VERIFIER)
    const subject = born(SUBJECT)
    const known = born(OWNER)
    const stranger = born(STRANGER)
    directory = [row(self, 'OWNER'), row(verifier, 'OWNER'), row(subject, 'OWNER'), row(known, 'OWNER'), row(stranger, 'MEMBER')]
    const longAgo = Date.now() - NEW_FOR_MS - 1
    // Every member this account met two weeks ago or longer, but the verifier: first seen now.
    fakePinServer(sealPinStore(MK, {
      [SUBJECT]: pinOf(bundleB64(keysOf(subject)), longAgo, headOf(subject.chain)),
      [OWNER]: pinOf(bundleB64(keysOf(known)), longAgo, headOf(known.chain)),
    }, 1))
    fakeVerifications([verifiedBy(verifier, subject)])
    expect(await tenantBadges(T, ME)).toEqual({ [VERIFIER]: 'new', [SUBJECT]: 'verified', [OWNER]: 'unverified', [STRANGER]: 'anomaly' })
  })

  it('a member whose chain could not be read (a failed request) gets no badge — never a false anomaly', async () => {
    const before = born(OWNER)
    const owner = rotated(before)
    const other = born(SUBJECT)
    directory = [row(owner, 'OWNER'), row(other, 'OWNER')]
    fakePinServer(sealPinStore(MK, { [OWNER]: pinOf(bundleB64(keysOf(before)), 1, headOf(before.chain)) }, 1))
    chains.getKeyChain.mockImplementation(async ({ userId }) => {
      if (userId === OWNER) throw new Error('offline')
      return { userId, head: headOf(other.chain), steps: other.chain.steps }
    })
    const badges = await tenantBadges(T, ME)
    expect(badges[OWNER]).toBeUndefined()
    expect(badges[SUBJECT]).toBe('new') // control: the member whose chain was read
  })
})

// ── A rotation re-signs (decision 13) ────────────────────────────────────────────────────────────────────────

describe('a rotation carries this account\'s verifications to its new key (ADR 0111 decision 13)', () => {
  const selfOf = (p: Person) => ({ userId: p.userId, pub: keysOf(p).identity.pub })

  it('collects only the rows that verify under the CURRENT key at the current head', async () => {
    const a = born(SUBJECT)
    const b = born(OWNER)
    const c = born(STRANGER)
    fakeVerifications([
      verifiedBy(self, a), // mine, now: carried
      verifiedBy(self, b, { signKey: v1.generateIdentity().priv.sign }), // in my slot, signed by another key
      verifiedBy(self, c, { verifierHead: b64std(v1.randomBytes(32)) }), // naming another head of mine
    ])
    const carried = await collectMyVerifications(selfOf(self), headOf(self.chain), [{ tenantId: T, open }])
    expect(carried.map((c) => c.subjectUserId)).toEqual([SUBJECT])
    expect(b64std(carried[0].subjectHead)).toBe(headOf(a.chain))
    expect(carried[0]).toMatchObject({ tenantId: T, epoch: 0 })
    expect(Array.from(carried[0].key)).toEqual(Array.from(K0)) // a copy of the tenant key…
    expect(carried[0].key).not.toBe(K0)
  })

  it('a tenant this device cannot open carries nothing — never a throw', async () => {
    fakeVerifications([verifiedBy(self, born(SUBJECT))])
    expect(await collectMyVerifications(selfOf(self), headOf(self.chain), [{ tenantId: T, open: async () => null }])).toEqual([])
    expect(await collectMyVerifications(selfOf(self), headOf(self.chain), [{ tenantId: T, open: async () => { throw new Error('offline') } }])).toEqual([])
  })

  it('re-signs each under the NEW identity, naming the new head, over the old row — and zeroes every carried key', async () => {
    const a = born(SUBJECT)
    const b = born(OWNER)
    const srv = fakeVerifications([verifiedBy(self, a), verifiedBy(self, b)])
    const carried = await collectMyVerifications(selfOf(self), headOf(self.chain), [{ tenantId: T, open }])
    const after = rotated(self)
    srv.putMyVerification.mockRejectedValueOnce(new Error('offline')) // the first write is lost: the second still lands
    await resignVerifications(carried, ME, keysOf(after).identity.priv.sign, after.chain.head)

    expect(srv.putMyVerification).toHaveBeenCalledTimes(2)
    const landed = srv.putMyVerification.mock.calls[1][0]
    const v = verification.openVerification(K0, { tenantId: T, verifierUserId: ME, subjectUserId: landed.subjectUserId, epoch: 0 }, fromB64std(landed.putVerificationRequest.sealedStatement))
    expect(b64std(v.verifierHead)).toBe(headOf(after.chain))
    const address = { tenantId: T, verifierUserId: ME, subjectUserId: landed.subjectUserId }
    expect(verification.verificationVerifies(address, v, keysOf(after).identity.pub.sign)).toBe(true)
    expect(verification.verificationVerifies(address, v, keysOf(self).identity.pub.sign)).toBe(false)
    expect(carried.every((c) => c.key.every((x) => x === 0))).toBe(true)
  })
})

// The pins the anchor reads are the device's counter (ADR 0111 decision 16): a read through the tenant moves them as
// any anchor check does. One check here that the profile's read writes what it concluded.
describe('the profile\'s read moves the pin as every anchor check does', () => {
  it('a first contact through the tenant is pinned at the anchored head', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    directory = [row(self, 'MEMBER', joinedThrough(self, owner)), row(owner, 'OWNER'), row(joiner, 'MEMBER', joinedThrough(joiner, owner))]
    const srv = fakePinServer()
    await personInTenant(T, JOINER)
    expect(pinsOn(MK, srv.state)[JOINER]).toMatchObject({ head: headOf(joiner.chain), index: 0, replaced: false })
  })
})
