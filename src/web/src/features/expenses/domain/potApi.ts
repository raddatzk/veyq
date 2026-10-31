// A Pot's reads and writes against the Expenses API (`contracts/api/expenses/expenses.openapi.yaml`): the member's
// conditional keyset read, the guest's tagged read by id, the batch append on both paths and the member's erasure. What
// a view does with them — the live loop, the editor — is the Pot page's; this is the wire, once.
//
// The member's read is core's revisioned list (`core/items/itemsLive.ts`): `ledgerRevision` is the ETag, the cursor
// (`position,entryId:revision`) is opaque and sent back unchanged, and `revisionIds` comes only when an erasure moved
// `deleteRevision` past the `sinceDelete` echoed. A re-key re-serves every revision at a new position with a new wrap
// and the same body, so a delta replaces what is held by id (`applyPotChange`). The guest's read is paged by id
// (`after=<entryId>,<revision>`), tagged by a digest of everything it serves on its first page only, and has no writer.
//
// Every write states a revision `n + 1` of what the writer read and the heads it read; the server takes all of a batch or
// none (`409 stale_revision`, naming each stale record with its latest). The client never sends an entry whose sums do
// not add up (`ledger.ts`'s `entryProblem`, owner 2026-10-24), and never an erasure through the append.
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { readItemPages, reconcileItems, type ItemsChange, type ItemsOnePage, type ItemsPollPage } from '../../../core/items/itemsLive'
import { withRefresh } from '../../../app/sessionRefresh'
import { HttpStatus, isHttpStatus } from '../../../shared/httpError'
import type { PotGuestWriter, PotRevision, PotRevisionWrite, SharePotRevision } from '../../../api/generated/expenses'
import { expensesApi, publicShareExpensesApi } from '../api'
import { sealRevision } from './codec'
import { isMoney, type RevisionContent } from './format'
import { headsToName } from './heads'
import { buildLedger, entryProblem, isSettled, type BaseCurrency, type EntryProblem } from './ledger'
import { byRecord, openServedRevisions, revisionId, type HeldRevision, type RevisionRef, type ServedRevision } from './revisions'

/** One page asks for the server's cap (`ItemsLimit`'s 1000), as the media list does: a page costs a round trip. */
export const POT_PAGE_LIMIT = 1000

const served = (r: PotRevision | SharePotRevision): ServedRevision => ({
  entryId: r.entryId,
  revision: r.revision,
  createdAt: r.createdAt,
  sealedBody: fromB64std(r.sealedBody),
  wrappedRevisionKey: fromB64std(r.wrappedRevisionKey),
  ...('writer' in r ? { writer: r.writer } : {}),
  ...(r.purgeAfter ? { purgeAfter: r.purgeAfter } : {}),
})

// --- the member's read ----------------------------------------------------------------------------------------------

/** A member's read of a Pot after every page: `304`, or the delta with the first page's counters, the id set (only after
 *  an erasure), the last page's cursor, and the sealed names of the link guests who wrote any revision of it. */
export type MemberPotRead =
  | { notModified: true }
  | (Extract<ItemsPollPage<ServedRevision>, { notModified: false }> & { guestWriters: PotGuestWriter[] })

/**
 * Read a member's Pot in full — a first load (no options) or a poll (`ifNoneMatch` = the held `ledgerRevision`, `since` =
 * the held cursor, `sinceDelete` = the held delete revision). Core's page loop: every later page continues from the page
 * before's cursor without `If-None-Match`, and the answer keeps the first page's counters.
 */
export async function readMemberPot(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number } = {},
): Promise<MemberPotRead> {
  const writers = new Map<string, PotGuestWriter>()
  const page = async (q: { ifNoneMatch?: string; since?: string; sinceDelete?: number }): Promise<ItemsOnePage<ServedRevision>> => {
    try {
      const p = await withRefresh(() =>
        expensesApi().listPotRevisions({ scopeId, nodeId, ifNoneMatch: q.ifNoneMatch, since: q.since, sinceDelete: q.sinceDelete, limit: POT_PAGE_LIMIT }),
      )
      for (const w of p.guestWriters) writers.set(w.identityId, w)
      return {
        notModified: false,
        items: p.revisions.map(served),
        revision: p.ledgerRevision,
        deleteRevision: p.deleteRevision,
        ids: p.revisionIds ? p.revisionIds.map(revisionId) : null,
        more: p.more,
        cursor: p.cursor ?? null,
      }
    } catch (e) {
      if (q.ifNoneMatch !== undefined && isHttpStatus(e, HttpStatus.notModified)) return { notModified: true }
      throw e
    }
  }
  const read = await readItemPages(
    () => page(opts),
    (since, deleteRevision) => page({ since, sinceDelete: deleteRevision }),
    revisionId,
  )
  return read.notModified ? read : { ...read, guestWriters: [...writers.values()] }
}

/**
 * Merge a read into what is held, opened: the delta's rows open at their address and replace a held revision of the same
 * id (a re-key's new wrap; the body is the same, so its digest is), and the id set — present only after an erasure —
 * drops every held revision it no longer names. A first load is a change applied to nothing.
 */
export function applyPotChange(
  held: readonly HeldRevision[],
  change: Pick<ItemsChange<ServedRevision>, 'items' | 'ids'>,
  pot: { scopeId: string; nodeId: string; potKey: Uint8Array },
): HeldRevision[] {
  const opened = openServedRevisions(pot.scopeId, pot.nodeId, pot.potKey, change.items)
  return reconcileItems(held.map((r) => ({ ...r })), opened, change.ids, { idOf: revisionId, patch: (h, incoming) => Object.assign(h, incoming) })
}

// --- the guest's read -----------------------------------------------------------------------------------------------

/** A guest's read of a Pot: every revision, the tag of its first page (`null`: the server named none), and the day's
 *  exchange-rate table's address while the Pot's tenant has the rates on (`exchangeRateService`, else null) — a guest
 *  holds no tenant's capability set, so this is the one place its device learns whether it may fetch. */
export type GuestPotRead = { revisions: ServedRevision[]; etag: string | null; exchangeRateService: string | null }

/**
 * Read a Pot under a share link in full. With `ifNoneMatch` (the last first page's `ETag`, quoted, as served) the first
 * page is conditional and an unchanged listing answers `null`. Later pages continue `after` the page before's cursor, are
 * never conditional, and carry no tag. The rows have no writer: the guest's schema has none.
 */
export async function readGuestPot(
  shareId: string,
  nodeId: string,
  opts: { pwToken?: string; ifNoneMatch?: string } = {},
): Promise<GuestPotRead | null> {
  const api = publicShareExpensesApi()
  let res
  try {
    res = await api.listSharePotRevisionsRaw({ shareId, nodeId, xSharePassword: opts.pwToken, limit: POT_PAGE_LIMIT, ifNoneMatch: opts.ifNoneMatch })
  } catch (e) {
    if (opts.ifNoneMatch !== undefined && isHttpStatus(e, HttpStatus.notModified)) return null
    throw e
  }
  const etag = res.raw.headers.get('ETag')
  let page = await res.value()
  const exchangeRateService = page.exchangeRateService ?? null
  const revisions = page.revisions.map(served)
  while (page.more) {
    const after = page.cursor
    if (!after) throw new Error('pot_page_without_cursor') // a page that says more must say where it ends
    page = await api.listSharePotRevisions({ shareId, nodeId, xSharePassword: opts.pwToken, after, limit: POT_PAGE_LIMIT })
    if (page.more && page.cursor === after) throw new Error('pot_page_did_not_advance')
    revisions.push(...page.revisions.map(served))
  }
  return { revisions, etag, exchangeRateService }
}

// --- writes ---------------------------------------------------------------------------------------------------------

/** The Pot's key for a write and the epoch the write states (`nodeEpoch`). A member's comes from core's `nodeKeyForWrite`
 *  (`core/node/rekey/floor.ts`), which refuses a key below the device's floor; a guest's from the opened share. */
export type PotWriteKey = { key: Uint8Array; epoch: number }

/** Where a write goes: a member's scope path, or a guest's share path with the link's password token and the guest's
 *  contributor token (`createShareIdentity`). */
export type PotTarget =
  | { kind: 'member'; scopeId: string; nodeId: string }
  | { kind: 'guest'; scopeId: string; nodeId: string; shareId: string; contributorToken: string; pwToken?: string }

/** One record's next revision: a new record (its id minted by the caller, ADR 0072 decision 3) or the next of one held.
 *  [basedOn] is the revision the writer's change was made on — an open editor's, which the live poll may have moved past
 *  meanwhile: the write is then revision `basedOn + 1`, so the server answers `409 stale_revision` instead of taking it
 *  over the other writer's version unseen. Absent, it is the latest held. */
export type PotWrite = {
  entryId: string
  content: Exclude<RevisionContent, { kind: 'erasure' }>
  basedOn?: number
  /** The record's receipts this revision no longer holds (`droppedReceipts`, `domain/receiptRules.ts`' rule): each is
   *  stamped dropped and erased after the trash retention, and never held again. Absent: none. */
  droppedReceipts?: readonly string[]
}

/** The client refuses to send a write: an entry that does not add up, a person the Pot does not know, two revisions of one
 *  record in a batch, a participant deactivated or erased while not settled, whose join order moved or whose kind would
 *  change (a name made a member, a member made a name or another member), or a member added who already is a
 *  participant. Nothing reached the server. */
export class PotWriteRefused extends Error {
  constructor(
    readonly reason:
      | EntryProblem
      | 'record-twice'
      | 'erasure-through-append'
      | 'not-a-participant'
      | 'not-settled'
      | 'joined-moved'
      | 'kind-changed'
      | 'member-twice'
      | 'empty'
      | 'receipt-dropped-and-kept',
  ) {
    super(`pot_write_refused: ${reason}`)
    this.name = 'PotWriteRefused'
  }
}

/** `409 stale_revision`: someone wrote a record since it was read. `stale` names each one with its latest revision (0: it
 *  does not exist). Nothing of the write was stored; re-read, show their version, and let the writer save on top. */
export class StaleRevisionError extends Error {
  constructor(readonly stale: RevisionRef[]) {
    super('stale_revision')
    this.name = 'StaleRevisionError'
  }
}

async function staleOf(e: unknown): Promise<StaleRevisionError | null> {
  if (!isHttpStatus(e, HttpStatus.conflict)) return null
  try {
    const body = (await (e as { response: Response }).response.clone().json()) as { code?: unknown; stale?: unknown }
    if (body.code !== 'stale_revision' || !Array.isArray(body.stale)) return null
    return new StaleRevisionError(
      body.stale.filter((s): s is RevisionRef => typeof s?.entryId === 'string' && Number.isInteger(s?.revision)).map((s) => ({ entryId: s.entryId, revision: s.revision })),
    )
  } catch {
    return null
  }
}

const latestOf = (held: readonly HeldRevision[]): Map<string, number> => {
  const latest = new Map<string, number>()
  for (const [id, revs] of byRecord(held)) latest.set(id, revs[revs.length - 1]!.revision)
  return latest
}

/**
 * Seal a batch of writes on what is held: each record's revision `n + 1` of its latest held (`1` for a new one) or of the
 * revision the write says it was made on ([PotWrite.basedOn]), every
 * revision naming the heads the writer read. Refuses ([PotWriteRefused]) before sealing anything: two writes of one record,
 * an erasure, a revision that keeps a receipt it drops, a money entry whose sums do not add up or that names someone who is neither a participant of the Pot nor
 * one this batch adds. Pure but for the randomness of the seals.
 */
export function sealPotWrites(
  target: Pick<PotTarget, 'scopeId' | 'nodeId'>,
  potKey: Uint8Array,
  held: readonly HeldRevision[],
  writes: readonly PotWrite[],
  base: BaseCurrency,
): PotRevisionWrite[] {
  if (writes.length === 0) throw new PotWriteRefused('empty')
  if (new Set(writes.map((w) => w.entryId)).size !== writes.length) throw new PotWriteRefused('record-twice')
  if (writes.some((w) => (w.content as RevisionContent).kind === 'erasure')) throw new PotWriteRefused('erasure-through-append')
  // A receipt a revision drops is never held again (ADR 0108 decision 7): a revision that keeps one it drops is a bug.
  for (const w of writes) {
    const kept = isMoney(w.content) ? new Set(w.content.receipts) : new Set<string>()
    if (w.droppedReceipts?.some((id) => kept.has(id))) throw new PotWriteRefused('receipt-dropped-and-kept')
  }
  const ledger = buildLedger(held, base)
  const known = new Map(ledger.participants.map((p) => [p.id, p] as const))
  const people = new Set(known.keys())
  for (const w of writes) if (w.content.kind === 'participant') people.add(w.entryId)
  for (const w of writes) {
    const c = w.content
    if (isMoney(c)) {
      const problem = entryProblem(c, (id) => people.has(id))
      if (problem) throw new PotWriteRefused(problem)
    } else if (c.kind === 'participant') {
      // A participant keeps its join order for life (the rounding's tie-break), and is deactivated only when settled.
      const before = known.get(w.entryId)
      if (before?.erased) throw new PotWriteRefused('not-a-participant')
      if (before && before.joined !== c.joined) throw new PotWriteRefused('joined-moved')
      // A participant never changes kind (ADR 0108 decision 2): every later revision carries its first one's member, or none.
      if (before && before.member !== (c.member?.userId ?? null)) throw new PotWriteRefused('kind-changed')
      if (before?.active && !c.active && !isSettled(ledger, w.entryId)) throw new PotWriteRefused('not-settled')
    }
  }
  // A member is one participant of the Pot at most: a new record naming one who already is (not erased), or two new ones
  // naming the same, is refused. An erased participant's record no longer names anyone, so the member may be added again.
  const members = new Set(ledger.participants.filter((p) => !p.erased && p.member !== null).map((p) => p.member!))
  for (const w of writes) {
    const c = w.content
    if (c.kind !== 'participant' || !c.member || known.has(w.entryId)) continue
    if (members.has(c.member.userId)) throw new PotWriteRefused('member-twice')
    members.add(c.member.userId)
  }
  const latest = latestOf(held)
  const heads = headsToName(held)
  return writes.map((w) => {
    const revision = (w.basedOn ?? latest.get(w.entryId) ?? 0) + 1
    // A content handed back from an opened body (a restore) still carries the `v` and the heads ITS writer read; spread
    // last, those would overwrite this write's own heads, and the restore would not name the tombstone it lifts.
    const { v: _v, heads: _theirs, ...content } = w.content as typeof w.content & { v?: unknown; heads?: unknown }
    const sealed = sealRevision({ scopeId: target.scopeId, nodeId: target.nodeId, entryId: w.entryId, revision }, potKey, { v: 1, heads, ...content })
    return {
      entryId: w.entryId,
      revision,
      tombstone: w.content.kind === 'deleted',
      sealedBody: b64std(sealed.sealedBody),
      wrappedRevisionKey: b64std(sealed.wrappedRevisionKey),
      ...(w.droppedReceipts?.length ? { droppedReceipts: [...w.droppedReceipts] } : {}),
    }
  })
}

/** Append a batch to the Pot ([sealPotWrites]), atomically, and answer the revisions the server took (a writer that
 *  appends a second batch on the first — an import — holds them, `heldOfSent`). Throws [StaleRevisionError] on
 *  `409 stale_revision`, and the server's other refusals as the generated client throws them. */
export async function appendPotWrites(
  target: PotTarget,
  key: PotWriteKey,
  held: readonly HeldRevision[],
  writes: readonly PotWrite[],
  base: BaseCurrency,
): Promise<PotRevisionWrite[]> {
  const revisions = sealPotWrites(target, key.key, held, writes, base)
  try {
    if (target.kind === 'member') {
      await withRefresh(() =>
        expensesApi().appendPotRevisions({ scopeId: target.scopeId, nodeId: target.nodeId, potAppendRequest: { nodeEpoch: key.epoch, revisions } }),
      )
    } else {
      await publicShareExpensesApi().appendSharePotRevisions({
        shareId: target.shareId,
        nodeId: target.nodeId,
        xSharePassword: target.pwToken,
        sharePotAppendRequest: { nodeEpoch: key.epoch, contributorToken: target.contributorToken, revisions },
      })
    }
  } catch (e) {
    throw (await staleOf(e)) ?? e
  }
  return revisions
}

/** The revisions an append took, opened as held (their server time is the writer's clock: only a batch written on them
 *  reads it, as the order of their heads). */
export function heldOfSent(scopeId: string, nodeId: string, potKey: Uint8Array, sent: readonly PotRevisionWrite[], at = new Date()): HeldRevision[] {
  return openServedRevisions(
    scopeId,
    nodeId,
    potKey,
    sent.map((r) => ({ entryId: r.entryId, revision: r.revision, createdAt: at, sealedBody: fromB64std(r.sealedBody), wrappedRevisionKey: fromB64std(r.wrappedRevisionKey) })),
  )
}

/**
 * A member deletes or removes a participant (ADR 0108 decisions 3 and 6): the record's sealed erasure revision, after
 * which the server holds none of its earlier revisions. The client's rules first — the server cannot see them: the record
 * is a participant (not already erased), and its balance is settled. Throws [PotWriteRefused] or [StaleRevisionError].
 */
export async function eraseParticipant(
  target: Extract<PotTarget, { kind: 'member' }>,
  key: PotWriteKey,
  held: readonly HeldRevision[],
  participantId: string,
  base: BaseCurrency,
): Promise<void> {
  const ledger = buildLedger(held, base)
  const person = ledger.participants.find((p) => p.id === participantId)
  if (!person || person.erased) throw new PotWriteRefused('not-a-participant')
  if (!isSettled(ledger, participantId)) throw new PotWriteRefused('not-settled')
  const revision = (latestOf(held).get(participantId) ?? 0) + 1
  const sealed = sealRevision(
    { scopeId: target.scopeId, nodeId: target.nodeId, entryId: participantId, revision },
    key.key,
    { v: 1, heads: headsToName(held), kind: 'erasure', joined: person.joined },
  )
  try {
    await withRefresh(() =>
      expensesApi().erasePotRecord({
        scopeId: target.scopeId,
        nodeId: target.nodeId,
        potErasureRequest: {
          nodeEpoch: key.epoch,
          entryId: participantId,
          revision,
          sealedBody: b64std(sealed.sealedBody),
          wrappedRevisionKey: b64std(sealed.wrappedRevisionKey),
        },
      }),
    )
  } catch (e) {
    throw (await staleOf(e)) ?? e
  }
}
