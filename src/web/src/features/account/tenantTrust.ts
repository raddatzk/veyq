// Trust in a tenant (ADR 0111 decisions 12–16): who a person is anchored as there, and who verified them.
//
// **The anchor is the join record, per tenant.** Every member who joined through an invite wrote one (decision 3): the
// identity and Secret-Key proof key they joined with, signed by the owner-signed invite key and by that identity. A
// person is anchored in a tenant when their join record opens under the tenant key, verifies owner → invite → newcomer,
// its signer is an owner the server lists for the tenant and this device anchors that signer's key in turn — and then
// their key chain reaches the served head from the step the record names (`identityPins.ts` § `readJoinedChain`). An
// owner the server lists with no join record is a **root** (the tenant's first owner, born through the Server Owner's
// invite) and is trusted on first use, by the pin. **Anyone else without a join record is an anomaly** — a membership
// only the server can have made, since every newcomer writes one and there is no release from before the record: never
// wrapped to, declared pending in a rotation, shown; never a block on anybody's write. Who is an owner stays the server's
// word: roles are not signed (ADR 0111 § What stays open).
//
// **A verification is a signed statement in the tenant**, not a private mark (decisions 12–14, `@veyq/crypto/verification`):
// a matching compare of safety number AND chain code writes the verifier's statement *"in this tenant, I at my head
// confirmed you at yours"* into the verifier's own records, sealed under the tenant key, and every member there reads it.
// It counts while it names the subject's current head and verifies under the verifier's current key at the verifier's
// current head — so a rotation of the subject drops it by itself, and a statement a thief signs with a retired key after
// the rotation never counts. A rotation re-signs the outgoing key's verifications with the new one (decision 13,
// {@link collectMyVerifications} / {@link resignVerifications}). Display only: nothing here gates anybody's write.
//
// The operator reads none of it: join records and verifications are sealed under the tenant key, and the server learns
// only that rows exist.
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { tenantsApi } from '../../api/client'
import type { MemberVerification } from '../../api/generated/tenants'
import { ScopeMemberRoleEnum, type ScopeMember } from '../../api/generated/scopes'
import { withRefresh } from '../../app/sessionRefresh'
import { identityPrivate } from '../../app/vault'
import { openUnderHeldEpochs, writeKey, zeroEpochKeys, type EpochKeys } from '../spaces/epochs'
import { listScopeMembers } from '../spaces/members'
import { me } from '../spaces/grantTrust'
import { listMyScopes, openScopeById, type OpenedScope } from '../spaces/scopes'
import {
  anchorChainKey,
  anchorIdentity,
  fetchKeyChain,
  getPin,
  IdentityMovedError,
  IdentityUnanchoredError,
  linkOf,
  PinPass,
  type Anchor,
  type ChainKeyAnchor,
  type Pin,
  type ServedIdentity,
  type Standing,
  type TenantStandings,
} from './identityPins'
import { fingerprintDigestB64 } from './safetyCode'

/** A person reads as *new* for this long after this account first pinned them (ADR 0111 decision 15). */
export const NEW_FOR_MS = 14 * 24 * 60 * 60 * 1000

/** How a tenant's scope is opened for its key: the active tenant's scope list by default; the account rotation's
 *  finish passes one that reads the plan's row (any tenant). */
export type TenantOpener = (tenantId: string) => Promise<OpenedScope | null>

// ── Standings ────────────────────────────────────────────────────────────────────────────────────────────────

/** A tenant read once: its directory (with the join records opened) and the standings asked of it. */
export interface TenantView extends TenantStandings {
  members: ReadonlyMap<string, ScopeMember>
  /** False when this device could not open the tenant or list it: everyone then stands `unavailable`. */
  available: boolean
}

const UNAVAILABLE: Standing = { kind: 'unavailable' }
const UNANCHORED: Standing = { kind: 'unanchored' }

/** Open a join record with the epoch key it was sealed under — the epoch its invite's grant carried, which the record
 *  does not name, so every epoch this device holds is tried, newest first ({@link openUnderHeldEpochs}). Null for one
 *  none opens. */
function openJoinRecord(tenantId: string, userId: string, sealed: string, epochs: EpochKeys): invite.JoinRecord | null {
  let envelope: Uint8Array
  try {
    envelope = fromB64std(sealed)
  } catch {
    return null
  }
  return openUnderHeldEpochs(epochs, (key) => invite.openJoinRecord(key, { tenantId, userId }, envelope))?.value ?? null
}

/**
 * Read a tenant's standings: its directory (`listScopeMembers` of the tenant scope, whose id is the tenant's) and every
 * join record in it, opened with the tenant's epoch keys, which are zeroed before this returns. A tenant this device
 * cannot open or list reads `unavailable` for everyone. A person's standing is computed once per view:
 *  - no member, or a pending proposal → `unanchored`;
 *  - no join record → `root` for an owner the server lists, `unanchored` for anyone else (a membership the server made);
 *  - a join record → `joined` when it opened, verifies (`invite.joinRecordVerifies`: the owner's signature over the invite
 *    key, the invite key's and the newcomer's over the record), names another member the server lists as an OWNER as its
 *    signer, and that signer's key in it is anchored in this tenant in turn ({@link anchorChainKey} with this view);
 *    `unanchored` otherwise. A cycle of signers anchors nobody.
 *  - **a record a retired key signed** (ADR 0111 decision 21): when the key the record names is
 *    not the signer's served current one — a retired key of their chain, at or after their join — it counts only through
 *    the signer's endorsement: the member's `joinEndorsement` must verify under the signer's served current key, over
 *    the sealed record as stored, and that key must be anchored in this tenant ({@link anchorIdentity} with this view).
 *    An owner's securing endorses every record it rooted (`rotateKeys`), so its members stay; a record a thief signs with
 *    the retired key afterwards never carries an endorsement by the key the thief lacks.
 *
 * **Each signer is judged once per view** (inbox #710): the owner who invited thirty members anchors their key once, not
 * thirty times — the same answer, since a view's standings are memoised for its life anyway; a check that failed for a
 * request is not kept, so the next member's asks again. With `pins`, a pass ({@link PinPass}), every check judged with
 * this view reads the pin store through it — one read for the whole member list. A view kept beyond one pass (the grant
 * checks' {@link cachedTenantView}) carries none.
 */
export async function loadTenantView(
  tenantId: string, open: TenantOpener = openScopeById, opts: { pins?: PinPass } = {},
): Promise<TenantView> {
  let opened: OpenedScope | null = null
  let rows: ScopeMember[] = []
  try {
    opened = await open(tenantId)
    if (opened) rows = await listScopeMembers(tenantId)
  } catch {
    opened = null
  }
  const members = new Map(rows.map((m) => [m.userId, m]))
  if (!opened) return { tenantId, members, available: false, standing: async () => UNAVAILABLE, pins: opts.pins }
  const records = new Map<string, invite.JoinRecord | null>()
  try {
    for (const m of rows) {
      if (m.sealedJoinRecord) records.set(m.userId, openJoinRecord(tenantId, m.userId, m.sealedJoinRecord, opened.epochs))
    }
  } finally {
    zeroEpochKeys(opened.epochs)
  }
  const memo = new Map<string, Promise<Standing>>()
  const visiting = new Set<string>()
  /** `f`'s answer per key, for the view's life — but a failure is forgotten, so a later ask tries again. */
  const once = <T>(cache: Map<string, Promise<T>>, key: string, f: () => Promise<T>): Promise<T> => {
    let p = cache.get(key)
    if (!p) {
      p = f()
      cache.set(key, p)
      p.catch(() => cache.delete(key))
    }
    return p
  }
  const signerKeys = new Map<string, Promise<ChainKeyAnchor>>()
  const signersServed = new Map<string, Promise<Anchor>>()
  const view: TenantView = {
    tenantId,
    members,
    available: true,
    pins: opts.pins,
    standing(userId) {
      let s = memo.get(userId)
      if (!s) {
        s = judge(userId)
        memo.set(userId, s)
      }
      return s
    },
  }
  async function judge(userId: string): Promise<Standing> {
    const m = members.get(userId)
    if (!m || m.pendingConsent) return UNANCHORED
    if (!m.sealedJoinRecord) return m.role === ScopeMemberRoleEnum.Owner ? { kind: 'root' } : UNANCHORED
    const record = records.get(userId)
    if (!record || !invite.joinRecordVerifies({ tenantId, userId }, record)) return UNANCHORED
    const signer = members.get(record.signerUserId)
    if (!signer || signer.pendingConsent || signer.role !== ScopeMemberRoleEnum.Owner || record.signerUserId === userId) {
      return UNANCHORED
    }
    if (visiting.has(userId)) return UNANCHORED
    visiting.add(userId)
    try {
      const signerKey = b64std(record.signerIdentityPublicKey)
      const judged = await once(signerKeys, `${record.signerUserId} ${signerKey}`, () =>
        anchorChainKey(record.signerUserId, signerKey, { tenant: view }),
      )
      if (judged.anchor === 'anomaly') return UNANCHORED
      if ((judged.retired || signerKey !== signer.identityPublicKey) && !(await endorsedBySigner(m, signer))) return UNANCHORED
    } finally {
      visiting.delete(userId)
    }
    return { kind: 'joined', identityPublicKey: record.identityPublicKey, secretKeyProofPublicKey: record.secretKeyProofPublicKey }
  }
  /** Whether [m]'s join endorsement verifies under [signer]'s served current key, over [m]'s sealed record as stored,
   *  that key anchored in this tenant (ADR 0111 decision 21). False — never a throw — for anything malformed. */
  async function endorsedBySigner(m: ScopeMember, signer: ScopeMember): Promise<boolean> {
    if (!m.joinEndorsement || !m.sealedJoinRecord) return false
    let signKey: Uint8Array
    let verifies: boolean
    try {
      signKey = v1.deserializeIdentityPublic(fromB64std(signer.identityPublicKey)).sign
      verifies = invite.joinEndorsementVerifies(
        signKey,
        { tenantId, userId: m.userId, signerUserId: signer.userId, sealedJoinRecord: fromB64std(m.sealedJoinRecord) },
        fromB64std(m.joinEndorsement),
      )
    } catch {
      return false
    }
    if (!verifies) return false
    const anchor = await once(signersServed, signer.userId, () => anchorIdentity(signer.userId, servedOf(signer), { tenant: view }))
    return anchor !== 'anomaly'
  }
  return view
}

const cachedViews = new Map<string, { at: number; view: Promise<TenantView> }>()
const VIEW_TTL_MS = 60_000

/** {@link loadTenantView} of the active tenant's list, kept for a minute — for the grant checks, which run on every open
 *  of a scope. A view that could not open the tenant is not kept. */
export function cachedTenantView(tenantId: string): Promise<TenantView> {
  const hit = cachedViews.get(tenantId)
  if (hit && Date.now() - hit.at < VIEW_TTL_MS) return hit.view
  const view = loadTenantView(tenantId)
  cachedViews.set(tenantId, { at: Date.now(), view })
  void view.then(
    (v) => {
      if (!v.available) cachedViews.delete(tenantId)
    },
    () => cachedViews.delete(tenantId),
  )
  return view
}

/** Forget every kept view (a sign-out, a test). */
export function forgetTenantViews(): void {
  cachedViews.clear()
}

/** The view of the tenant a scope of the active tenant lives in (a group's, or the tenant scope itself), for a wrap there. */
export async function tenantViewOfScope(scopeId: string): Promise<TenantView> {
  const row = (await listMyScopes()).find((s) => s.id === scopeId)
  return loadTenantView(row?.tenantId ?? scopeId)
}

const servedOf = (m: ScopeMember): ServedIdentity => ({ identityPublicKey: m.identityPublicKey, keyChainHead: m.keyChainHead })

// ── My own head ──────────────────────────────────────────────────────────────────────────────────────────────

/** This account's own key-chain head (base64), as its chain is served — walked, and required to end at the identity this
 *  vault holds, so a served chain that withholds this account's newest step is refused rather than shown (a compare of
 *  it would then match a peer who was served the same withheld chain). Throws when it does not. */
export async function myChainHead(): Promise<{ userId: string; head: string }> {
  const priv = identityPrivate()
  if (!priv) throw new Error('my chain head requires an unlocked vault')
  try {
    const self = await me(priv)
    const chain = await fetchKeyChain(self.userId)
    if (!chain) throw new Error('the server serves no key chain for this account')
    const walked = v1.verifyKeyChain({ userId: self.userId, links: chain.steps.map(linkOf) }, null)
    if (!walked.ok || b64std(walked.identityPublicKey) !== self.pubB64 || b64std(walked.head) !== chain.head) {
      throw new Error('the served key chain does not end at this account’s identity')
    }
    return { userId: self.userId, head: chain.head }
  } finally {
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}

// ── Verifications ────────────────────────────────────────────────────────────────────────────────────────────

const PAGE = 1000

/** Every verification row of a tenant (optionally of one subject or by one verifier), page by page. */
export async function listAllVerifications(
  tenantId: string, filter: { subjectUserId?: string; verifierUserId?: string } = {},
): Promise<MemberVerification[]> {
  const out: MemberVerification[] = []
  let after: string | undefined
  for (;;) {
    const page = await withRefresh(() => tenantsApi().listVerifications({ tenantId, ...filter, after, limit: PAGE }))
    out.push(...page.verifications)
    const last = page.verifications.at(-1)
    if (!page.more || !last) return out
    after = `${last.verifierUserId}:${last.subjectUserId}`
  }
}

/** One row opened with the epoch key it names, or null when this device holds no key for that epoch or the row does not
 *  open at its slot. */
function openRow(tenantId: string, row: MemberVerification, epochs: EpochKeys): verification.Verification | null {
  const key = epochs.keys.get(row.epoch)
  if (!key) return null
  try {
    const slot = { tenantId, verifierUserId: row.verifierUserId, subjectUserId: row.subjectUserId, epoch: row.epoch }
    return verification.openVerification(key, slot, fromB64std(row.sealedStatement))
  } catch {
    return null
  }
}

/**
 * Which verifications count, by subject (subject id → verifier ids). A row counts when it opens at its slot, names the
 * subject's CURRENT head (the one the directory serves, which `anchoredHeads` holds only for a subject this device
 * anchors), and verifies under the verifier's CURRENT key at the verifier's current head — the verifier anchored in this
 * tenant ({@link anchorIdentity}), or this account itself at the head its own chain ends at. Nothing else: a statement
 * by a retired key, the thief's case of decision 13, is not the current key's.
 */
export async function countVerifications(
  view: TenantView, rows: readonly MemberVerification[], epochs: EpochKeys, anchoredHeads: ReadonlyMap<string, string>,
): Promise<Map<string, string[]>> {
  const out = new Map<string, string[]>()
  const verifierOk = new Map<string, Promise<boolean>>()
  let mine: { userId: string; head: string; pubB64: string } | null | undefined
  const self = async () => {
    if (mine !== undefined) return mine
    try {
      const { userId, head } = await myChainHead()
      const priv = identityPrivate()
      if (!priv) return (mine = null)
      try {
        mine = { userId, head, pubB64: (await me(priv)).pubB64 }
      } finally {
        priv.kemSeed.fill(0)
        priv.sign.fill(0)
      }
    } catch {
      mine = null
    }
    return mine
  }
  for (const row of rows) {
    const subjectHead = anchoredHeads.get(row.subjectUserId)
    if (!subjectHead) continue
    const verifier = view.members.get(row.verifierUserId)
    if (!verifier) continue
    const v = openRow(view.tenantId, row, epochs)
    if (!v || b64std(v.subjectHead) !== subjectHead) continue
    const me_ = await self()
    let verifierKey: string
    let verifierHead: string
    if (me_ && row.verifierUserId === me_.userId) {
      verifierKey = me_.pubB64
      verifierHead = me_.head
    } else {
      verifierKey = verifier.identityPublicKey
      verifierHead = verifier.keyChainHead
      let ok = verifierOk.get(row.verifierUserId)
      if (!ok) {
        ok = anchorIdentity(row.verifierUserId, servedOf(verifier), { tenant: view })
          .then((a) => a !== 'anomaly')
          .catch(() => false)
        verifierOk.set(row.verifierUserId, ok)
      }
      if (!(await ok)) continue
    }
    if (b64std(v.verifierHead) !== verifierHead) continue
    let signKey: Uint8Array
    try {
      signKey = v1.deserializeIdentityPublic(fromB64std(verifierKey)).sign
    } catch {
      continue
    }
    const address = { tenantId: view.tenantId, verifierUserId: row.verifierUserId, subjectUserId: row.subjectUserId }
    if (!verification.verificationVerifies(address, v, signKey)) continue
    out.set(row.subjectUserId, [...(out.get(row.subjectUserId) ?? []), row.verifierUserId])
  }
  return out
}

/**
 * Write this account's verification of `subjectUserId` in `tenantId` after a matching compare of safety number and chain
 * code: the compared head and key must still be what the directory serves and what this device anchors there (else
 * {@link IdentityMovedError}, or {@link IdentityUnanchoredError} for an anomaly — nothing is signed), and the statement
 * names this account's own head as its chain ends at the vault's identity. Sealed under the tenant's current epoch.
 */
export async function verifyPerson(
  tenantId: string, subjectUserId: string, compared: { head: string; fingerprint: string },
): Promise<void> {
  const view = await loadTenantView(tenantId)
  const subject = view.members.get(subjectUserId)
  if (!subject || subject.keyChainHead !== compared.head || fingerprintDigestB64(subject.identityPublicKey) !== compared.fingerprint) {
    throw new IdentityMovedError(subjectUserId)
  }
  if ((await anchorIdentity(subjectUserId, servedOf(subject), { tenant: view })) === 'anomaly') {
    throw new IdentityUnanchoredError(subjectUserId)
  }
  const mine = await myChainHead()
  const opened = await openScopeById(tenantId)
  if (!opened) throw new Error('cannot open the tenant to verify — the vault is locked or no grant is held')
  const priv = identityPrivate()
  try {
    if (!priv) throw new Error('verify requires an unlocked vault')
    const address = { tenantId, verifierUserId: mine.userId, subjectUserId }
    const signed = verification.signVerification(
      address, { subjectHead: fromB64std(compared.head), verifierHead: fromB64std(mine.head) }, priv.sign,
    )
    const epoch = opened.epochs.current
    const sealed = verification.sealVerification(writeKey(opened.epochs), { ...address, epoch }, signed)
    await withRefresh(() =>
      tenantsApi().putMyVerification({ tenantId, subjectUserId, putVerificationRequest: { epoch, sealedStatement: b64std(sealed) } }),
    )
  } finally {
    priv?.kemSeed.fill(0)
    priv?.sign.fill(0)
    zeroEpochKeys(opened.epochs)
  }
}

// ── What a member sees ───────────────────────────────────────────────────────────────────────────────────────

/** A person in one tenant as their profile shows them (decision 15). Display only. */
export interface PersonInTenant {
  /** `anomaly` — not anchored here (no join record and no owner, a record that does not verify, a key the chain does
   *  not reach); else how the key was reached. */
  anchor: Anchor
  /** The pin after the read — first-seen, the `replaced` note. */
  pin: Pin | null
  /** The head and key the compare is about — the served ones, anchored; null for an anomaly. */
  head: string | null
  fingerprint: string | null
  /** When the newest step of their chain was made, when it is a rotation ("new identity, signed by the previous key"). */
  rotatedAt: Date | null
  /** First seen by this account less than {@link NEW_FOR_MS} ago. */
  isNew: boolean
  /** The user ids whose verification of the current head counts. */
  verifiedBy: string[]
}

/** The profile's read of one member of `tenantId`: anchored in that tenant, and verified by whom. Moves the pin the way
 *  every anchor check does. Requires an unlocked vault. */
export async function personInTenant(tenantId: string, userId: string): Promise<PersonInTenant> {
  const view = await loadTenantView(tenantId)
  const m = view.members.get(userId)
  const none: PersonInTenant = { anchor: 'anomaly', pin: null, head: null, fingerprint: null, rotatedAt: null, isNew: false, verifiedBy: [] }
  if (!m) return none
  const anchor = await anchorIdentity(userId, servedOf(m), { tenant: view }).catch((): Anchor => 'anomaly')
  const pin = await getPin(userId)
  if (anchor === 'anomaly') return { ...none, pin }
  const chain = await fetchKeyChain(userId).catch(() => null)
  const newest = chain?.steps.at(-1)
  const rotatedAt = chain && newest && newest.index >= 1 && chain.head === m.keyChainHead ? newest.createdAt : null
  const verifiedBy = (await verifiedByIn(view, [m])).get(userId) ?? []
  return {
    anchor,
    pin,
    head: m.keyChainHead,
    fingerprint: fingerprintDigestB64(m.identityPublicKey),
    rotatedAt,
    isNew: !!pin && Date.now() - pin.firstSeenAt < NEW_FOR_MS,
    verifiedBy,
  }
}

/** The counting verifications of `subjects` (each anchored by the caller already), read with the tenant's epoch keys. */
async function verifiedByIn(view: TenantView, subjects: readonly ScopeMember[]): Promise<Map<string, string[]>> {
  const opened = await openScopeById(view.tenantId).catch(() => null)
  if (!opened) return new Map()
  try {
    const rows =
      subjects.length === 1
        ? await listAllVerifications(view.tenantId, { subjectUserId: subjects[0].userId })
        : await listAllVerifications(view.tenantId)
    return await countVerifications(view, rows, opened.epochs, new Map(subjects.map((s) => [s.userId, s.keyChainHead])))
  } catch {
    return new Map()
  } finally {
    zeroEpochKeys(opened.epochs)
  }
}

/** A member list's badge (decision 15): `anomaly`, `verified` (someone's verification of the current head counts), `new`
 *  (first seen less than two weeks ago) or `unverified`. */
export type MemberBadge = 'anomaly' | 'verified' | 'new' | 'unverified'

/**
 * The badges of a tenant's member list, in one read of the tenant and one listing of its verifications, as one pass
 * ({@link PinPass}: one read of the pin store). Each member is anchored as a wrap would anchor them (sequentially: the pin
 * store's writes must not race); a member whose check failed for another reason than the key (a request) gets no badge.
 * `selfUserId` gets none.
 */
export async function tenantBadges(tenantId: string, selfUserId?: string): Promise<Record<string, MemberBadge>> {
  const view = await loadTenantView(tenantId, openScopeById, { pins: new PinPass() })
  const out: Record<string, MemberBadge> = {}
  const anchored: ScopeMember[] = []
  const pins = new Map<string, Pin | null>()
  for (const m of view.members.values()) {
    if (m.userId === selfUserId || m.pendingConsent) continue
    try {
      if ((await anchorIdentity(m.userId, servedOf(m), { tenant: view })) === 'anomaly') {
        out[m.userId] = 'anomaly'
        continue
      }
    } catch {
      continue
    }
    anchored.push(m)
    pins.set(m.userId, await getPin(m.userId, view.pins).catch(() => null))
  }
  const verified = await verifiedByIn(view, anchored)
  for (const m of anchored) {
    const pin = pins.get(m.userId)
    out[m.userId] = verified.get(m.userId)?.length
      ? 'verified'
      : pin && Date.now() - pin.firstSeenAt < NEW_FOR_MS
        ? 'new'
        : 'unverified'
  }
  return out
}

// ── A rotation re-signs (decision 13) ────────────────────────────────────────────────────────────────────────

/** One of this account's verifications that counts now, held across the publish to be re-signed by the new identity:
 *  where it goes, the head it confirms, and the tenant key (a copy, zeroed by {@link resignVerifications}) and epoch it
 *  is sealed under. */
export type CarriedVerification = { tenantId: string; subjectUserId: string; subjectHead: Uint8Array; epoch: number; key: Uint8Array }

/**
 * Before the publish: in each tenant, this account's own verifications that verify under its CURRENT identity at its
 * current head — the outgoing key's. Read now, while that key is still the account's, so a statement a thief signs with
 * it after the publish is never among them (ADR 0111 decision 13). A tenant this device cannot open carries nothing: its
 * verifications stop counting with the rotation, a display that falls away. Never a throw.
 */
export async function collectMyVerifications(
  self: { userId: string; pub: v1.Identity['pub'] }, myHead: string, tenants: ReadonlyArray<{ tenantId: string; open: TenantOpener }>,
): Promise<CarriedVerification[]> {
  const out: CarriedVerification[] = []
  for (const { tenantId, open } of tenants) {
    let opened: OpenedScope | null = null
    try {
      opened = await open(tenantId)
      if (!opened) continue
      const rows = await listAllVerifications(tenantId, { verifierUserId: self.userId })
      for (const row of rows) {
        const v = openRow(tenantId, row, opened.epochs)
        if (!v || b64std(v.verifierHead) !== myHead) continue
        const address = { tenantId, verifierUserId: self.userId, subjectUserId: row.subjectUserId }
        if (!verification.verificationVerifies(address, v, self.pub.sign)) continue
        out.push({ tenantId, subjectUserId: row.subjectUserId, subjectHead: v.subjectHead, epoch: opened.epochs.current, key: writeKey(opened.epochs).slice() })
      }
    } catch {
      /* this tenant carries nothing */
    } finally {
      if (opened) zeroEpochKeys(opened.epochs)
    }
  }
  return out
}

/** After the publish: re-sign each carried verification with the NEW identity, naming the new head, and write it over
 *  the old row. Best-effort — a write that fails leaves a row signed by the retired key, which no longer counts. Zeroes
 *  every carried key. */
export async function resignVerifications(
  carried: readonly CarriedVerification[], verifierUserId: string, newSignKey: Uint8Array, newHead: Uint8Array,
): Promise<void> {
  for (const c of carried) {
    try {
      const address = { tenantId: c.tenantId, verifierUserId, subjectUserId: c.subjectUserId }
      const signed = verification.signVerification(address, { subjectHead: c.subjectHead, verifierHead: newHead }, newSignKey)
      const sealed = verification.sealVerification(c.key, { ...address, epoch: c.epoch }, signed)
      await withRefresh(() =>
        tenantsApi().putMyVerification({
          tenantId: c.tenantId,
          subjectUserId: c.subjectUserId,
          putVerificationRequest: { epoch: c.epoch, sealedStatement: b64std(sealed) },
        }),
      )
    } catch {
      /* stays signed by the retired key: no longer counts */
    } finally {
      c.key.fill(0)
    }
  }
}
