// Who's who in a tenant (inbox #562, uc.spaces.enforce-verified-identities main flow 5; ADR 0111 decision 15): for every
// other member, whether this device anchors their key there, whose verification of their current head counts, whether
// this account first saw them under two weeks ago, and whether they secured their account in that time.
//
// It reads what the members list (`tenantBadges`) and a profile (`personInTenant`) already read, through their own
// exports, and nothing else: the tenant's directory with its join records (`loadTenantView`), each member anchored as a
// wrap anchors them (`anchorIdentity`, which moves the pin as every anchor check does - the only write), the pin store,
// the tenant's verification rows (`countVerifications`) and, for the date of a rotation, a key chain. Nothing new reaches
// the server. Display only: nothing here gates anybody's write.
//
// **Load-bearing:** a verification counts only for a member this device anchors, at the head the directory serves. An
// anomaly's entry is built without any count, so it never reads as verified, whatever rows the server serves for it
// (held with the count fed every member, too: the test's bite of both layers is the one that goes red); that only the
// anchored are counted at all is the cost's choice, not the gate.
//
// Cost: one pass (`PinPass`, inbox #710) — the pin store's head is read once for every anchor check and every pin, and each
// join record's signer is judged once (`loadTenantView`); the anchor checks run one after another, as `tenantBadges` runs
// them, and the pins they take are written in one write at the pass's end (inbox #741); the verifications are listed once; a key chain is read for the rotation
// date only when the anchored head is not the chain's first (a pin at index 0 is a genesis: nothing rotated), a few at a
// time.
import type { ScopeMember } from '../../api/generated/scopes'
import { openScopeById } from '../spaces/scopes'
import { zeroEpochKeys } from '../spaces/epochs'
import { anchorIdentity, fetchKeyChain, getPin, PinPass, type Pin } from './identityPins'
import { countVerifications, listAllVerifications, loadTenantView, NEW_FOR_MS, type TenantView } from './tenantTrust'
import { TrustStateRefused } from './trustMarks'

/** The one state a person shows: not anchored here (`anomaly`), someone's verification of their current head counts
 *  (`verified`), anchored and nobody's counts (`unverified`), or their check failed for another reason than the key - a
 *  request - and nothing is said (`unchecked`, never a false anomaly). */
export type OverviewState = 'anomaly' | 'verified' | 'unverified' | 'unchecked'

/** One other member as the overview shows them. */
export interface OverviewPerson {
  userId: string
  username: string
  state: OverviewState
  /** This account first pinned them less than {@link NEW_FOR_MS} ago. Never for an anomaly. */
  isNew: boolean
  /** The members whose verification of the current head counts (this account's own id included). Empty unless `verified`. */
  verifiedBy: string[]
  /** When the newest step of their chain - a rotation - was made, while it is less than {@link NEW_FOR_MS} old; else null. */
  rotatedAt: Date | null
  /** A join record replaced a pin that was not theirs ("the key you saw before wasn't theirs"). */
  replaced: boolean
}

/** A tenant's overview: `available` false when this device could not open or list the tenant (nobody is listed then). */
export interface PeopleOverview {
  available: boolean
  people: OverviewPerson[]
}

/** How many key chains are read at once for the rotation dates. */
const CHAIN_READS_AT_ONCE = 4

/** The counting verifications of `anchored` (subject id → verifier ids), read with the tenant's epoch keys, zeroed after.
 *  Empty when the tenant does not open or a read fails: a *verified* falls away, nothing else does. */
async function countedFor(view: TenantView, anchored: readonly ScopeMember[]): Promise<Map<string, string[]>> {
  if (anchored.length === 0) return new Map()
  const opened = await openScopeById(view.tenantId).catch(() => null)
  if (!opened) return new Map()
  try {
    const rows = await listAllVerifications(view.tenantId)
    return await countVerifications(view, rows, opened.epochs, new Map(anchored.map((m) => [m.userId, m.keyChainHead])))
  } catch {
    return new Map()
  } finally {
    zeroEpochKeys(opened.epochs)
  }
}

/** When `m`'s newest step - a rotation - was made, read from their chain; null for a chain that does not end at the head
 *  the directory serves, a genesis, or a failed read. */
async function rotationOf(m: ScopeMember): Promise<Date | null> {
  try {
    const chain = await fetchKeyChain(m.userId)
    const newest = chain?.steps.at(-1)
    return chain && newest && newest.index >= 1 && chain.head === m.keyChainHead ? newest.createdAt : null
  } catch {
    return null
  }
}

/** `f` over `items`, at most `n` at a time, results in order. */
async function inBatches<T, R>(items: readonly T[], n: number, f: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length)
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      const i = next++
      out[i] = await f(items[i])
    }
  }
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, worker))
  return out
}

/**
 * The overview of `tenantId` for this account (`selfUserId` is not listed; nor is a pending proposal). Requires an
 * unlocked vault. Throws `TrustStateRefused` when this device refuses its own pin store - the caller says so, with the
 * way to start the list over - and nothing else for one member's failed check.
 */
export async function loadPeopleOverview(tenantId: string, selfUserId: string, now: number = Date.now()): Promise<PeopleOverview> {
  const pass = new PinPass()
  try {
    return await overviewOf(await loadTenantView(tenantId, openScopeById, { pins: pass }), selfUserId, now)
  } finally {
    // The pass's pins in one write (inbox #741), also after a failure midway. As a check of the pass: a refused store is
    // the caller's to say, any other failure leaves the pins unwritten - the next pass pins again.
    await pass.flush().catch((e: unknown) => {
      if (e instanceof TrustStateRefused) throw e
    })
  }
}

async function overviewOf(view: TenantView, selfUserId: string, now: number): Promise<PeopleOverview> {
  if (!view.available) return { available: false, people: [] }
  const listed = [...view.members.values()].filter((m) => m.userId !== selfUserId && !m.pendingConsent)

  const state = new Map<string, 'anomaly' | 'anchored' | 'unchecked'>()
  for (const m of listed) {
    try {
      const anchor = await anchorIdentity(m.userId, { identityPublicKey: m.identityPublicKey, keyChainHead: m.keyChainHead }, { tenant: view })
      state.set(m.userId, anchor === 'anomaly' ? 'anomaly' : 'anchored')
    } catch (e) {
      if (e instanceof TrustStateRefused) throw e
      state.set(m.userId, 'unchecked')
    }
  }
  const anchored = listed.filter((m) => state.get(m.userId) === 'anchored')
  const pins: Record<string, Pin> = {}
  for (const m of anchored) {
    const pin = await getPin(m.userId, view.pins)
    if (pin) pins[m.userId] = pin
  }
  const counted = await countedFor(view, anchored)
  // A pin AT the served head with index 0 is the chain's genesis: nothing rotated, nothing to read.
  const rotations = await inBatches(anchored, CHAIN_READS_AT_ONCE, async (m) => {
    const pin = pins[m.userId]
    if (pin && pin.head === m.keyChainHead && pin.index === 0) return null
    return rotationOf(m)
  })
  const rotatedAt = new Map(anchored.map((m, i) => [m.userId, rotations[i]]))

  const people = listed.map((m): OverviewPerson => {
    const s = state.get(m.userId)
    const base = { userId: m.userId, username: m.username }
    if (s !== 'anchored') {
      return { ...base, state: s === 'anomaly' ? 'anomaly' : 'unchecked', isNew: false, verifiedBy: [], rotatedAt: null, replaced: false }
    }
    const pin = pins[m.userId]
    const verifiedBy = counted.get(m.userId) ?? []
    const rotated = rotatedAt.get(m.userId) ?? null
    return {
      ...base,
      state: verifiedBy.length ? 'verified' : 'unverified',
      isNew: !!pin && now - pin.firstSeenAt < NEW_FOR_MS,
      verifiedBy,
      rotatedAt: rotated && now - rotated.getTime() < NEW_FOR_MS ? rotated : null,
      replaced: !!pin?.replaced,
    }
  })
  return { available: true, people }
}

/** The overview's order: anomalies first (the one state that asks for action), then by name as `nameOf` gives it. */
export function orderPeople(people: readonly OverviewPerson[], nameOf: (p: OverviewPerson) => string, locale?: string): OverviewPerson[] {
  return [...people].sort(
    (a, b) => Number(b.state === 'anomaly') - Number(a.state === 'anomaly') || nameOf(a).localeCompare(nameOf(b), locale),
  )
}
