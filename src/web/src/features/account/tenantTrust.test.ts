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
vi.mock('../spaces/scopes', () => ({ openScopeById: vi.fn(), listMyScopes: vi.fn(), isStaleEpoch: vi.fn() }))
vi.mock('../spaces/grantTrust', () => ({ me: vi.fn() }))

import {
  cachedTenantView,
  collectMyVerifications,
  forgetTenantViews,
  countVerifications,
  loadTenantView,
  openJoinRecord,
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
import { isStaleEpoch, openScopeById, type OpenedScope } from '../spaces/scopes'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { me } from '../spaces/grantTrust'
import { ScopeKind, ScopeMemberRoleEnum, type ScopeMember } from '../../api/generated/scopes'
import type { MemberVerification, TenantsApi } from '../../api/generated/tenants'
import type { ScopeKey } from '../spaces/epochs'
import { httpError } from '../../test/httpError'

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
/** The one tenant key this device's grant opens to (ADR 0115 decision 1): K0 at epoch 0 unless a test rotates it. */
let tenantKey: ScopeKey
let chains: ReturnType<typeof fakeKeyChains>
/** The tenant's directory as the server serves it (`listScopeMembers` of the tenant scope). */
let directory: ScopeMember[]

/** The tenant opened as this device opens it: a fresh copy of its one key each time (the code zeroes what it opened). */
const opened = (): OpenedScope => {
  const key = tenantKey.key.slice()
  return {
    scopeId: T, tenantId: T, kind: ScopeKind.Tenant, key, epochs: { epoch: tenantKey.epoch, key },
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
/** Who endorses a member's join record, with which key — over the opened record (ADR 0115 decision 8), or (an attack, a
 *  control) over other bytes: another record, or the envelope the record is stored in. */
type Endorsement = { signerUserId: string; by: v1.Identity; over?: (sealedEnvelope: Uint8Array) => Uint8Array }

/** `p` as the tenant's directory lists them: their newest key and head, a role, the join record they wrote — sealed
 *  under the tenant key (`sealedUnder`, the current one by default) — and the endorsement of it the record's signer wrote
 *  (ADR 0111 decision 21). */
function row(
  p: Person, role: ScopeMemberRoleEnum, record?: invite.JoinRecord,
  o: { pendingConsent?: boolean; endorsed?: Endorsement; sealedUnder?: Uint8Array } = {},
): ScopeMember {
  const sealed = record ? invite.sealJoinRecord(o.sealedUnder ?? tenantKey.key, { tenantId: T, userId: p.userId }, record) : null
  const e = o.endorsed
  const endorsedBytes = sealed && record ? (e?.over ? e.over(sealed) : invite.encodeJoinRecord(record)) : null
  return {
    userId: p.userId, username: p.userId.slice(-4), avatarRevision: 0, role, identityPublicKey: bundleB64(keysOf(p)),
    keyChainHead: headOf(p.chain), pending: false, pendingConsent: o.pendingConsent,
    sealedJoinRecord: sealed ? b64std(sealed) : null,
    joinEndorsement: e && endorsedBytes
      ? b64std(invite.signJoinEndorsement(e.by.priv.sign, { tenantId: T, userId: p.userId, signerUserId: e.signerUserId, joinRecord: endorsedBytes }))
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
 *  signed by `signKey`, sealed under the tenant key `at` (the current one by default) at its epoch. */
function verificationRow(
  verifierUserId: string, subjectUserId: string, heads: { subjectHead: string; verifierHead: string }, signKey: Uint8Array,
  at: ScopeKey = tenantKey,
): MemberVerification {
  const address = { tenantId: T, verifierUserId, subjectUserId }
  const v = verification.signVerification(
    address, { subjectHead: fromB64std(heads.subjectHead), verifierHead: fromB64std(heads.verifierHead) }, signKey,
  )
  const sealedStatement = b64std(verification.sealVerification(at.key, { ...address, epoch: at.epoch }, v))
  return { verifierUserId, subjectUserId, epoch: at.epoch, sealedStatement, createdAt: new Date() }
}
/** `verifier` (at their current head and key) confirmed `subject` at `subjectHead` (their current head by default). */
const verifiedBy = (verifier: Person, subject: Person, o: { subjectHead?: string; signKey?: Uint8Array; verifierHead?: string; at?: ScopeKey } = {}) =>
  verificationRow(
    verifier.userId, subject.userId,
    { subjectHead: o.subjectHead ?? headOf(subject.chain), verifierHead: o.verifierHead ?? headOf(verifier.chain) },
    o.signKey ?? keysOf(verifier).identity.priv.sign, o.at,
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
  tenantKey = { epoch: 0, key: K0 }
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  forgetVerifiedPins()
  fakePinServer()
  chains = fakeKeyChains()
  directory = []
  vi.mocked(listScopeMembers).mockImplementation(async () => directory)
  vi.mocked(openScopeById).mockImplementation(open)
  // the real predicate: a `409 stale_epoch` is what a write sealed under a replaced key is answered with
  vi.mocked(isStaleEpoch).mockImplementation((e) => isCodedError(e, HttpStatus.conflict, 'stale_epoch'))
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

  // #571 (ADR 0111 decision 21): a record its owner signed with a key they have since retired.
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

  it('#571: the current key\'s endorsement of ANOTHER record does not count, nor one naming another signer', async () => {
    const { joiner, record, owner, current } = retiredKeyRecord()
    const another = invite.encodeJoinRecord(joinedThrough(joiner, owner)) // the same joiner's record through another invite
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current, over: () => another } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { endorsed: { signerUserId: STRANGER, by: current } })]
    expect(await standingOf(JOINER)).toBe('unanchored')
  })

  it('#571 + ADR 0115 decision 8: an endorsement over the opened record still verifies after the rotation re-sealed the record in a fresh envelope; one over the envelope\'s bytes does not', async () => {
    const { joiner, record, owner, current } = retiredKeyRecord()
    const address = { tenantId: T, userId: JOINER }
    const endorsed = row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current } })
    const overEnvelope = row(joiner, 'MEMBER', record, { endorsed: { signerUserId: OWNER, by: current, over: (envelope) => envelope } })
    directory = [row(owner, 'OWNER'), endorsed]
    expect(await standingOf(JOINER)).toBe('joined')
    directory = [row(owner, 'OWNER'), overEnvelope]
    expect(await standingOf(JOINER), 'an envelope is no record: its signature does not verify').toBe('unanchored')

    // The tenant rotates: E1 replaces E0, and the rotation re-seals each record under it — a fresh envelope, the
    // endorsement carried as it was.
    const K1 = v1.randomBytes(32)
    const reseal = (m: ScopeMember): ScopeMember => ({
      ...m,
      sealedJoinRecord: b64std(invite.sealJoinRecord(K1, address, invite.openJoinRecord(K0, address, fromB64std(m.sealedJoinRecord!)))),
    })
    const resealed = reseal(endorsed)
    expect(resealed.sealedJoinRecord).not.toBe(endorsed.sealedJoinRecord)
    expect(resealed.joinEndorsement).toBe(endorsed.joinEndorsement)
    tenantKey = { epoch: 1, key: K1 }
    directory = [row(owner, 'OWNER'), resealed]
    expect(await standingOf(JOINER)).toBe('joined')
    // control: the one signed over the old envelope's bytes, re-sealed the same way, still does not
    directory = [row(owner, 'OWNER'), reseal(overEnvelope)]
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

  it('a join record opens under the tenant\'s one key: one left under a replaced key anchors nobody (control: re-sealed under the current one)', async () => {
    const owner = born(OWNER)
    const joiner = born(JOINER)
    const record = joinedThrough(joiner, owner)
    const sealedE0 = b64std(invite.sealJoinRecord(K0, { tenantId: T, userId: JOINER }, record))
    expect(openJoinRecord(T, JOINER, sealedE0, { epoch: 0, key: K0 })).toEqual(record)
    // A rotation replaced K0 with K1: nothing older is tried.
    const K1 = v1.randomBytes(32)
    tenantKey = { epoch: 1, key: K1 }
    expect(openJoinRecord(T, JOINER, sealedE0, { epoch: 1, key: K1 })).toBeNull()
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record, { sealedUnder: K0 })]
    expect(await standingOf(JOINER)).toBe('unanchored')
    directory = [row(owner, 'OWNER'), row(joiner, 'MEMBER', record)] // sealed under K1, as the rotation re-sealed it
    expect(await standingOf(JOINER)).toBe('joined')
    // …and an envelope that is no base64 at all is one that does not open, never a throw
    expect(openJoinRecord(T, JOINER, '%%%', tenantKey)).toBeNull()
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

  it('a row opens only at the epoch of the tenant\'s one key (ADR 0115 decisions 1-2): one the rotation re-sealed counts, one left at the old epoch does not', async () => {
    const verifier = born(VERIFIER)
    const subject = born(SUBJECT)
    directory = [row(verifier, 'OWNER'), row(subject, 'OWNER')]
    const K1 = v1.randomBytes(32)
    const E1: ScopeKey = { epoch: 1, key: K1 }
    tenantKey = E1 // the device now holds K1 at epoch 1
    fakeVerifications([verifiedBy(verifier, subject, { at: { epoch: 0, key: K0 } })])
    expect((await counted(subject)).get(SUBJECT), 'under the replaced key: does not open').toBeUndefined()
    fakeVerifications([verifiedBy(verifier, subject, { at: E1 })])
    expect((await counted(subject)).get(SUBJECT), 'control: re-sealed at epoch 1').toEqual([VERIFIER])
    // The epoch the row states decides, before any key is tried: a row sealed with K1 at the slot of epoch 0 (and saying
    // so) would open under K1, yet it is not at the key's epoch and is not read.
    fakeVerifications([verifiedBy(verifier, subject, { at: { epoch: 0, key: K1 } })])
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

  describe('the write states the tenant\'s current epoch and seals once more after a `409 stale_epoch` (ADR 0115 decision 3)', () => {
    const K1 = () => v1.randomBytes(32)
    const opens = (put: Parameters<TenantsApi['putMyVerification']>[0], at: ScopeKey) => {
      try {
        return verification.openVerification(
          at.key, { tenantId: T, verifierUserId: ME, subjectUserId: SUBJECT, epoch: put.putVerificationRequest.epoch ?? -1 },
          fromB64std(put.putVerificationRequest.sealedStatement),
        )
      } catch {
        return null
      }
    }
    async function compare() {
      const subject = born(SUBJECT)
      directory = [row(self, 'OWNER'), row(subject, 'OWNER')]
      return { subject, compared: { head: headOf(subject.chain), fingerprint: fingerprintDigestB64(bundleB64(keysOf(subject))) } }
    }

    it('at epoch 1 it sends epoch 1 and seals under that epoch\'s key', async () => {
      const at: ScopeKey = { epoch: 1, key: K1() }
      tenantKey = at
      const { compared } = await compare()
      const srv = fakeVerifications()
      await verifyPerson(T, SUBJECT, compared)
      const put = srv.putMyVerification.mock.calls[0][0]
      expect(put.putVerificationRequest.epoch).toBe(1)
      expect(opens(put, at)).not.toBeNull()
      expect(opens(put, { epoch: 1, key: K0 }), 'control: not under the replaced key').toBeNull()
    })

    it('a rotation between the open and the write: the tenant is opened again and the statement sealed once more at the new epoch', async () => {
      const { compared } = await compare()
      const srv = fakeVerifications()
      const E2: ScopeKey = { epoch: 2, key: K1() }
      const write = srv.putMyVerification.getMockImplementation()!
      srv.putMyVerification.mockImplementationOnce(async () => {
        tenantKey = E2 // the rotation committed meanwhile: the server now holds the tenant at epoch 2
        throw httpError(409, 'stale_epoch')
      }).mockImplementation(write)
      await verifyPerson(T, SUBJECT, compared)
      expect(srv.putMyVerification).toHaveBeenCalledTimes(2)
      expect(openScopeById).toHaveBeenCalledTimes(3) // the view's read, then one open per attempt
      const [first, second] = srv.putMyVerification.mock.calls.map(([p]) => p)
      expect(first.putVerificationRequest.epoch).toBe(0)
      expect(second.putVerificationRequest.epoch).toBe(2)
      expect(opens(second, E2)).not.toBeNull()
      expect(srv.all().map((r) => r.epoch)).toEqual([2])
    })

    it('bounded: a second `stale_epoch` is thrown, after exactly two writes; any other refusal is never retried', async () => {
      const { compared } = await compare()
      const srv = fakeVerifications()
      srv.putMyVerification.mockRejectedValue(httpError(409, 'stale_epoch'))
      await expect(verifyPerson(T, SUBJECT, compared)).rejects.toMatchObject({ response: { status: 409 } })
      expect(srv.putMyVerification).toHaveBeenCalledTimes(2)

      srv.putMyVerification.mockReset()
      srv.putMyVerification.mockRejectedValue(httpError(409, 'conflict'))
      await expect(verifyPerson(T, SUBJECT, compared)).rejects.toMatchObject({ response: { status: 409 } })
      expect(srv.putMyVerification).toHaveBeenCalledTimes(1)
    })
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
    // a chain that is only its genesis is no rotation (control: the subject's one step above)
    expect((await personInTenant(T, VERIFIER)).rotatedAt).toBeNull()
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

// Inbox #710: one pass over a member list reads the pin store's head once and judges each join record's signer once,
// whatever the number of members; nothing is kept across passes.
describe('what one pass over a member list reads (inbox #710)', () => {
  /** A root owner R, an owner S who joined through R's invite, and `n` members who joined through S's. */
  function tenantOf(n: number) {
    const root = born(OWNER)
    const signer = born(SIGNER)
    const members = Array.from({ length: n }, (_, i) => born(`0e000000-0000-4000-8000-0000000001${String(i).padStart(2, '0')}`))
    directory = [
      row(self, 'OWNER'), row(root, 'OWNER'), row(signer, 'OWNER', joinedThrough(signer, root)),
      ...members.map((m) => row(m, 'MEMBER', joinedThrough(m, signer))),
    ]
    return { root, signer, members }
  }
  const chainReads = (userId: string) => chains.getKeyChain.mock.calls.filter(([r]) => r.userId === userId).length

  it('the members list: the pin head once, the signer\'s chain once as a signer (and once as a member), each member\'s once', async () => {
    const { root, signer, members } = tenantOf(8)
    const pins = fakePinServer()
    const badges = await tenantBadges(T, ME)
    // the semantics first: everyone anchored, first seen by this very pass
    expect(badges).toEqual(Object.fromEntries([root, signer, ...members].map((p) => [p.userId, 'new'])))
    expect(pins.getIdentityPins).toHaveBeenCalledTimes(1)
    expect(chainReads(signer.userId)).toBe(2)
    expect(chainReads(root.userId)).toBe(1)
    expect(members.map((m) => chainReads(m.userId))).toEqual(members.map(() => 1))
  })

  it('an owner who secured the account after inviting: their retired key and their endorsing key are each checked once', async () => {
    const root = born(OWNER)
    const before = born(SIGNER)
    const members = Array.from({ length: 4 }, (_, i) => born(`0e000000-0000-4000-8000-0000000001${String(i).padStart(2, '0')}`))
    const records = members.map((m) => joinedThrough(m, before)) // signed with the key the owner retired since
    const signer = rotated(before)
    directory = [
      row(self, 'OWNER'), row(root, 'OWNER'), row(signer, 'OWNER', joinedThrough(before, root)),
      ...members.map((m, i) => row(m, 'MEMBER', records[i], { endorsed: { signerUserId: SIGNER, by: keysOf(signer).identity } })),
    ]
    fakePinServer()
    const badges = await tenantBadges(T, ME)
    expect(members.map((m) => badges[m.userId])).toEqual(members.map(() => 'new')) // anchored through the endorsement
    expect(chainReads(SIGNER)).toBe(3) // as a member, as the records' signer, as their endorser - once each
  })

  it('a second pass reads the head again — nothing is kept across passes', async () => {
    tenantOf(3)
    const pins = fakePinServer()
    await tenantBadges(T, ME)
    await tenantBadges(T, ME)
    expect(pins.getIdentityPins).toHaveBeenCalledTimes(2)
  })

  it('a signer check that failed for a request is asked again for the next member - never kept as the pass\'s answer', async () => {
    const { signer, members } = tenantOf(2)
    fakePinServer()
    let signerReads = 0
    const real = chains.getKeyChain.getMockImplementation()!
    chains.getKeyChain.mockImplementation(async (r) => {
      // the 2nd read of the signer's chain is the first member's signer check: it fails, as a request may
      if (r.userId === signer.userId && ++signerReads === 2) throw new Error('offline')
      return real(r)
    })
    const badges = await tenantBadges(T, ME)
    expect(badges[members[0].userId]).toBeUndefined() // its check failed: no badge, never a false anomaly
    expect(badges[members[1].userId]).toBe('new') // the next member's signer check asked again, and anchored
  })

  it('the grant checks\' view, kept for a minute, is no pass: each check through it reads the pin store itself', async () => {
    const { root } = tenantOf(0)
    const pins = fakePinServer()
    forgetTenantViews()
    const view = await cachedTenantView(T)
    await anchorIdentity(root.userId, servedOf(root), { tenant: view })
    await anchorIdentity(root.userId, servedOf(root), { tenant: view })
    expect(pins.getIdentityPins).toHaveBeenCalledTimes(2)
  })

  it('a pin another device moved between two passes is what the second pass judges against', async () => {
    const { members } = tenantOf(1)
    const pins = fakePinServer()
    expect((await tenantBadges(T, ME))[members[0].userId]).toBe('new')
    // another device of this account pinned the member long ago at a head the chain does not hold: an anomaly now
    pins.become(sealPinStore(MK, { ...pinsOn(MK, pins.state), [members[0].userId]: pinOf(bundleB64(keysOf(born(STRANGER))), 1, headOf(born(STRANGER).chain), { index: 3 }) }, pins.state.revision + 1))
    expect((await tenantBadges(T, ME))[members[0].userId]).toBe('anomaly')
  })
})

// Inbox #741: a pass over a member list stores the pins of its first contacts in ONE write at its end, not one write per
// first contact - with the same answers, the same revision guard, nothing pinned for an anomaly, and a concurrent
// device's pin standing.
describe('what one pass over a member list writes (inbox #741)', () => {
  /** A root owner R, an owner S who joined through R's invite, and `n` members who joined through S's. */
  function tenantOf(n: number) {
    const root = born(OWNER)
    const signer = born(SIGNER)
    const members = Array.from({ length: n }, (_, i) => born(`0e000000-0000-4000-8000-0000000003${String(i).padStart(2, '0')}`))
    directory = [
      row(self, 'OWNER'), row(root, 'OWNER'), row(signer, 'OWNER', joinedThrough(signer, root)),
      ...members.map((m) => row(m, 'MEMBER', joinedThrough(m, signer))),
    ]
    return { root, signer, members, everyone: [root, signer, ...members] }
  }
  /** Every request the faked server answered: the pin store's, the key chains', the tenant's directory and verifications. */
  function requestsOf(pins: ReturnType<typeof fakePinServer>, verifications: ReturnType<typeof fakeVerifications>) {
    const counts = () => ({
      pinHead: pins.getIdentityPins.mock.calls.length,
      pinList: pins.listIdentityPinEntries.mock.calls.length,
      pinWrite: pins.writeIdentityPins.mock.calls.length,
      chains: chains.getKeyChain.mock.calls.length,
      tenant: vi.mocked(listScopeMembers).mock.calls.length + verifications.listVerifications.mock.calls.length,
    })
    const total = () => Object.values(counts()).reduce((a, b) => a + b, 0)
    return { counts, total }
  }

  it('cold, at 50 members: every first contact pinned by one write', async () => {
    const { everyone } = tenantOf(50)
    const pins = fakePinServer()
    const req = requestsOf(pins, fakeVerifications())
    const badges = await tenantBadges(T, ME)
    expect(badges).toEqual(Object.fromEntries(everyone.map((p) => [p.userId, 'new']))) // the same answers
    expect(Object.keys(pinsOn(MK, pins.state)).sort()).toEqual(everyone.map((p) => p.userId).sort()) // all stored
    // 52 first contacts (50 members, the signer, the root owner): one write where each wrote its own - 109 requests
    // before (52 writes), 58 now; a key chain per person and the signer's once more as the records' signer
    expect(req.counts()).toEqual({ pinHead: 1, pinList: 1, pinWrite: 1, chains: 53, tenant: 2 })
    expect(req.total()).toBe(58)
  })

  it('control: warm, the same 50 - nothing to pin, nothing written', async () => {
    tenantOf(50)
    const pins = fakePinServer()
    await tenantBadges(T, ME)
    pins.writeIdentityPins.mockClear()
    pins.getIdentityPins.mockClear()
    pins.listIdentityPinEntries.mockClear()
    chains.getKeyChain.mockClear()
    vi.mocked(listScopeMembers).mockClear()
    const req = requestsOf(pins, fakeVerifications())
    await tenantBadges(T, ME)
    // the session verified the store at its head: no listing; nothing changed: no write - 55, as before
    expect(req.counts()).toEqual({ pinHead: 1, pinList: 0, pinWrite: 0, chains: 52, tenant: 2 })
    expect(req.total()).toBe(55)
  })

  it('the write is guarded by the revision the pass read: a 409 re-reads, re-applies, and a pin another device wrote meanwhile stands', async () => {
    const { members, everyone } = tenantOf(3)
    const pins = fakePinServer()
    const stranger = born(STRANGER)
    const theirs = pinOf(bundleB64(keysOf(stranger)), 1, headOf(stranger.chain))
    // another device of this account pinned the first member (at a key that is not theirs) while this pass ran
    pins.racedBy(sealPinStore(MK, { [members[0].userId]: theirs }, pins.state.revision + 1))
    await tenantBadges(T, ME)
    expect(pins.writeIdentityPins).toHaveBeenCalledTimes(2) // the 409, then the re-applied write
    const stored = pinsOn(MK, pins.state)
    expect(stored[members[0].userId]).toEqual(theirs) // first-online-wins: a first sight claims only an empty slot
    for (const p of everyone.filter((q) => q !== members[0])) expect(stored[p.userId]).toMatchObject({ head: headOf(p.chain) })
  })

  it('an anomaly is never pinned - in the one write, beside the anchored', async () => {
    const { members } = tenantOf(2)
    const outsider = born(STRANGER)
    directory.push(row(outsider, 'MEMBER')) // a membership the server made: no join record
    const pins = fakePinServer()
    const badges = await tenantBadges(T, ME)
    expect(badges[outsider.userId]).toBe('anomaly')
    expect(pins.writeIdentityPins).toHaveBeenCalledTimes(1)
    const stored = pinsOn(MK, pins.state)
    expect(stored[outsider.userId]).toBeUndefined()
    expect(stored[members[1].userId]).toBeDefined()
  })

  it('a pin the pass recorded is what its later checks judge against: the signer, judged three times, is pinned once', async () => {
    const { signer } = tenantOf(2)
    const pins = fakePinServer()
    const badges = await tenantBadges(T, ME)
    expect(badges[signer.userId]).toBe('new')
    expect(pins.writeIdentityPins).toHaveBeenCalledTimes(1)
    expect(pinsOn(MK, pins.state)[signer.userId]).toMatchObject({ head: headOf(signer.chain), index: 0 })
  })

  it('a pass whose write fails still answers; the next pass pins again', async () => {
    const { everyone } = tenantOf(2)
    const pins = fakePinServer()
    pins.writeIdentityPins.mockRejectedValueOnce(new Error('offline'))
    expect(await tenantBadges(T, ME)).toEqual(Object.fromEntries(everyone.map((p) => [p.userId, 'new'])))
    expect(pins.state.entries.size).toBe(0)
    await tenantBadges(T, ME)
    expect(Object.keys(pinsOn(MK, pins.state))).toHaveLength(everyone.length)
  })
})
