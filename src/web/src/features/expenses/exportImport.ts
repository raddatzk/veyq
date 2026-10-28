// Expenses' side of a download, its import and a copy (ADR 0082, docs/apps/expenses.md § Download and import): a Pot's
// content is its settings, its people and the current revision of each live entry, in the plaintext of the entry format
// (`expensesPot` in contracts/export/veyq-subtree.schema.json), each entry with the receipts that revision holds as
// payloads, and a CSV of the entries beside it for a spreadsheet. History, deleted entries and dropped receipts are not
// exported: an import starts each entry at its current revision, under ids it mints, mapping every reference — so the
// imported Pot's balances equal the original's.
//
// A receipt (uc.expenses.attach-receipts) is read through the session's own door (a member's presign, a guest's link), its
// `K_blob` unwrapped under the Pot's key, and its type and name come from its sealed blob meta, opened here on the device:
// its plaintext goes only into the download the person saves, or - in a copy - frame by frame into the copy's upload,
// sealed again under a fresh `K_blob` bound to the new Pot (ADR 0082 decision 11). A receipt the operator blocked cannot be
// read and is left out of its entry's list with it. The import uploads each receipt under a new id BEFORE the batch whose
// revision lists it (a new entry's receipts precede its first revision), and that revision lists the new ids.
//
// A participant whose name was erased (a member's delete) exports as `deleted: true` with no name, and only while an
// exported entry still names it; it imports as a deactivated person under the catalog's "deleted people", which a member
// can delete again — the name it had is gone, and the import does not pretend otherwise.
import type { ExportContentHook, ExportNodeContext, ImportContentHook } from '../../core/export/hooks'
import type { ContentProblem } from '../../core/applications/registry'
import type { ManifestContent, ManifestPayload } from '../../core/export/manifest'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { deleteNode } from '../../core/node/tree'
import { iterateBlob } from '../../core/blob/stream'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { i18n } from '../../i18n'
import { applyPotChange, readGuestPot, readMemberPot, type PotWrite } from './domain/potApi'
import { buildLedger, namedParticipants } from './domain/ledger'
import { CURRENCY, MAX_RECEIPTS, readRevisionBody, UUID, RevisionFormatInvalid, type MoneyContent, type RevisionBody } from './domain/format'
import { potMetaOf, MAX_CATEGORY_NAME, MAX_OWN_CATEGORIES } from './domain/potMeta'
import { claimsReceipt } from './domain/receiptRules'
import type { HeldRevision } from './domain/revisions'
import { appendInBatches, createPotNode } from './createPot'
import {
  RECEIPT_MAX_BYTES, readGuestReceipts, readMemberReceipts, receiptChunks, receiptName, receiptPresign, receiptSize, uploadReceipt, type HeldReceipt,
} from './receipts'
import { decimalOf } from './amounts'

export const EXPENSES_POT_CONTENT = 'expenses.pot'
/** The application id of the content type above, and the content schema this build writes and reads. */
export const EXPENSES_CONTENT_SCHEMA = { application: 'expenses', version: 1 } as const
const SCHEMA = EXPENSES_CONTENT_SCHEMA.version

const MAX_PARTICIPANTS = 1000
const MAX_ENTRIES = 100_000
/** The longest receipt name the content carries (the schema's `expensesReceipt.name` maxLength). */
export const MAX_RECEIPT_NAME = 1024

type ExportedParticipant = { id: string; joined: number; name?: string; active?: boolean; deleted?: true }
/** One receipt as the content carries it (`expensesReceipt`): the id its entry lists, its sealed name, its bytes. */
type ExportedReceipt = { id: string; name?: string; file: ManifestPayload }
type ExportedEntry = { id: string; entry: MoneyContent; receipts?: ExportedReceipt[] }

/** Everything the session holds of the Pot, read in full through the session's own API. */
async function readPot(ctx: ExportNodeContext): Promise<HeldRevision[]> {
  const pot = { scopeId: ctx.scopeId, nodeId: ctx.node.id, potKey: ctx.nodeKey }
  const a = ctx.access
  if (a.mode === 'member') {
    const read = await readMemberPot(ctx.scopeId, ctx.node.id)
    return read.notModified ? [] : applyPotChange([], read, pot)
  }
  const read = await readGuestPot(a.shareId, ctx.node.id, { pwToken: a.pwToken })
  return read ? applyPotChange([], { items: read.revisions, ids: null }, pot) : []
}

/** Every receipt the session reads of the Pot, through its own door (a member's listing, or the link's). */
async function readReceipts(ctx: ExportNodeContext): Promise<HeldReceipt[]> {
  const a = ctx.access
  const read = a.mode === 'member'
    ? await readMemberReceipts(ctx.scopeId, ctx.node.id)
    : await readGuestReceipts(a.shareId, ctx.node.id, { pwToken: a.pwToken })
  return read?.receipts ?? []
}

/** A revision's body without what an import writes anew (`v`, `heads`). */
function contentOnly(body: MoneyContent): MoneyContent {
  const { v: _v, heads: _heads, ...rest } = body as MoneyContent & Partial<Pick<RevisionBody, 'v' | 'heads'>>
  return rest as MoneyContent
}

/**
 * The entry [entryId] with the receipts its revision [entry] holds, each declared as a payload (see the header), and its
 * list naming exactly those: one the listing does not hold for this entry, or holds dropped or blocked, is left out of
 * both. Its `K_blob` is unwrapped now, so a row the server moved here from another Pot refuses the walk (`OpenRefused`)
 * instead of exporting a stranger's bytes; its frames are fetched only when the archive or the copy reads them.
 */
function withReceipts(ctx: ExportNodeContext, entryId: string, entry: MoneyContent, listed: ReadonlyMap<string, HeldReceipt>): ExportedEntry {
  const presign = receiptPresign(ctx.access, ctx.scopeId)
  const receipts: ExportedReceipt[] = []
  for (const id of entry.receipts) {
    const r = listed.get(id)
    if (!r || r.entryId !== entryId || r.droppedAt !== null || r.blockedByOperator) continue
    const provider = receiptChunks(ctx.scopeId, ctx.nodeKey, ctx.node.id, r, presign)
    const meta = provider.meta()
    const file = ctx.addPayload(receiptName(meta), {
      size: receiptSize(r),
      mediaType: typeof meta?.type === 'string' ? meta.type : '',
      modifiedAt: typeof meta?.modifiedAt === 'number' ? meta.modifiedAt : undefined,
      chunks: () => iterateBlob(provider, { signal: ctx.signal }),
      frames: { frameSize: CHUNK_SIZE, chunkCount: provider.chunkCount, getPlaintextChunk: (i) => provider.getPlaintextChunk(i) },
    })
    const name = typeof meta?.name === 'string' && meta.name.length > 0 && [...meta.name].length <= MAX_RECEIPT_NAME ? meta.name : undefined
    receipts.push(name ? { id, name, file } : { id, file })
  }
  const carried = { ...entry, receipts: receipts.map((r) => r.id) } as MoneyContent
  return receipts.length ? { id: entryId, entry: carried, receipts } : { id: entryId, entry: carried }
}

const csvCell = (s: string) => (/[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)

/** The entries as a spreadsheet reads them: one line per entry, its amount and its base amount as decimals. */
export function entriesCsv(entries: readonly ExportedEntry[], people: ReadonlyMap<string, string>, baseMinorUnits: number, base: (e: MoneyContent) => bigint): string {
  const name = (id: string) => people.get(id) ?? ''
  const lines = [['date', 'kind', 'title', 'category', 'amount', 'currency', 'rate', 'base amount', 'paid by', 'from', 'to'].join(',')]
  for (const { entry: c } of entries) {
    const who = c.kind === 'expense' ? c.payers : c.kind === 'refund' ? c.receivers : []
    lines.push(
      [
        c.date,
        c.kind,
        c.title ?? '',
        c.kind === 'transfer' ? '' : c.category,
        decimalOf(BigInt(c.amount), c.minorUnits),
        c.currency,
        c.rate,
        decimalOf(base(c), baseMinorUnits),
        who.map((p) => name(p.participant)).join('; '),
        c.kind === 'transfer' ? name(c.from) : '',
        c.kind === 'transfer' ? name(c.to) : '',
      ].map(csvCell).join(','),
    )
  }
  return `${lines.join('\r\n')}\r\n`
}

export const exportPot: ExportContentHook = async (ctx) => {
  const meta = potMetaOf(unsealNodeMeta(ctx.nodeKey, { scopeId: ctx.scopeId, nodeId: ctx.node.id }, ctx.node.wrappedMeta))
  if (!meta) return null // a Pot no client set up has no base currency to export in
  const ledger = buildLedger(await readPot(ctx), meta.base)
  const live = ledger.entries.filter((e) => e.status === 'live' && e.content)
  // the listing is read only where a live entry holds a receipt
  const listed = live.some((e) => e.content!.receipts.length > 0)
    ? new Map((await readReceipts(ctx)).map((r) => [r.receiptId, r] as const))
    : new Map<string, HeldReceipt>()
  const entries: ExportedEntry[] = live.map((e) => withReceipts(ctx, e.id, contentOnly(e.content!), listed))
  const named = new Set(entries.flatMap((e) => namedParticipants(e.entry)))
  const participants: ExportedParticipant[] = ledger.participants
    .filter((p) => !p.erased || named.has(p.id))
    .map((p) => (p.erased ? { id: p.id, joined: p.joined, deleted: true } : { id: p.id, joined: p.joined, name: p.name!, active: p.active }))
  const effects = new Map(ledger.entries.map((e) => [e.id, e.effect?.base ?? 0n] as const))
  const baseOf = new Map(entries.map((e) => [e.entry, effects.get(e.id) ?? 0n] as const))
  let table = null
  if (ctx.purpose === 'download') {
    const people = new Map(ledger.participants.map((p) => [p.id, p.name ?? i18n.global.t('expenses.people.deletedPeople')] as const))
    const bytes = new TextEncoder().encode(entriesCsv(entries, people, meta.base.minorUnits, (c) => baseOf.get(c) ?? 0n))
    table = ctx.addPayload(`${ctx.node.name}.csv`, {
      size: bytes.length,
      mediaType: 'text/csv',
      chunks: async function* () {
        yield bytes
      },
      frames: { frameSize: Math.max(1, bytes.length), chunkCount: 1, getPlaintextChunk: async () => bytes },
    })
  }
  return {
    type: EXPENSES_POT_CONTENT,
    schema: SCHEMA,
    baseCurrency: meta.base.currency,
    baseMinorUnits: meta.base.minorUnits,
    ...(meta.categories.length ? { categories: meta.categories } : {}),
    participants,
    entries,
    table,
  }
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const bad = (message: string): ContentProblem => ({ code: 'invalid_manifest', message })
const tooLarge = (message: string): ContentProblem => ({ code: 'too_large', message })

/** The last segment of an archive path: a receipt's name where its content names none. */
const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

/** An `expenses.pot` content from a download held to its contract (decision 10: the archive is untrusted input). */
export function validatePotContent(content: ManifestContent): ContentProblem | null {
  if (content.type !== EXPENSES_POT_CONTENT) return bad(`not an Expenses Pot: ${content.type}`)
  if (typeof content.baseCurrency !== 'string' || !CURRENCY.test(content.baseCurrency)) return bad('a Pot without a base currency')
  const mu = content.baseMinorUnits
  if (typeof mu !== 'number' || !Number.isInteger(mu) || mu < 0 || mu > 4) return bad('a Pot without its minor units')
  if (content.categories !== undefined) {
    if (!Array.isArray(content.categories)) return bad('categories that are no list')
    if (content.categories.length > MAX_OWN_CATEGORIES) return tooLarge('too many categories')
    for (const c of content.categories) {
      if (!isObj(c) || typeof c.id !== 'string' || !UUID.test(c.id)) return bad('a category without an id')
      if (typeof c.name !== 'string' || c.name.length < 1) return bad('a category without a name')
      if ([...c.name].length > MAX_CATEGORY_NAME) return tooLarge('a category name too long')
    }
  }
  if (!Array.isArray(content.participants)) return bad('a Pot without its people')
  if (content.participants.length > MAX_PARTICIPANTS) return tooLarge('too many people')
  const ids = new Set<string>()
  for (const p of content.participants) {
    if (!isObj(p) || typeof p.id !== 'string' || !UUID.test(p.id) || ids.has(p.id)) return bad('a person without an id of its own')
    ids.add(p.id)
    if (typeof p.joined !== 'number' || !Number.isInteger(p.joined) || p.joined < 0) return bad('a person without a join time')
    if (p.deleted !== undefined && p.deleted !== true) return bad('a person deleted in another way')
    if (p.deleted !== true && (typeof p.name !== 'string' || p.name.length < 1)) return bad('a person without a name')
    if (typeof p.name === 'string' && [...p.name].length > 100) return tooLarge('a name too long')
    if (p.active !== undefined && typeof p.active !== 'boolean') return bad('a person whose state is no switch')
  }
  if (!Array.isArray(content.entries)) return bad('a Pot without its entries')
  if (content.entries.length > MAX_ENTRIES) return tooLarge('too many entries')
  const receiptIds = new Set<string>()
  for (const e of content.entries) {
    if (!isObj(e) || typeof e.id !== 'string' || !UUID.test(e.id)) return bad('an entry without an id')
    let body: RevisionBody
    try {
      body = readRevisionBody({ ...(isObj(e.entry) ? e.entry : {}), v: 1, heads: [] })
    } catch (x) {
      if (x instanceof RevisionFormatInvalid) return bad(`an entry not in the format: ${x.path}`)
      throw x
    }
    if (body.kind !== 'expense' && body.kind !== 'refund' && body.kind !== 'transfer') return bad('an entry that is no money entry')
    if (namedParticipants(body).some((id) => !ids.has(id))) return bad('an entry naming someone the Pot does not have')
    const problem = receiptsProblem(e.receipts, body.receipts, receiptIds)
    if (problem) return problem
  }
  return null
}

/** What is wrong with an entry's carried receipts [carried] against the ids its revision lists [listed], or null: each a
 *  payload of at most 10 MiB that claims an image or a PDF (the picker's rule, `claimsReceipt`), exactly the listed ids,
 *  none carried twice in the Pot ([seen]). */
function receiptsProblem(carried: unknown, listed: readonly string[], seen: Set<string>): ContentProblem | null {
  if (carried !== undefined && !Array.isArray(carried)) return bad('receipts that are no list')
  const items = (carried ?? []) as unknown[]
  if (items.length > MAX_RECEIPTS) return tooLarge('too many receipts on one entry')
  const own = new Set<string>()
  for (const r of items) {
    if (!isObj(r) || typeof r.id !== 'string' || !UUID.test(r.id)) return bad('a receipt without an id')
    if (seen.has(r.id)) return bad('a receipt carried twice')
    own.add(r.id)
    seen.add(r.id)
    const f = r.file
    if (!isObj(f) || typeof f.path !== 'string' || typeof f.mediaType !== 'string' || typeof f.size !== 'number' || !Number.isInteger(f.size) || f.size < 0) {
      return bad('a receipt without its file')
    }
    if (r.name !== undefined && (typeof r.name !== 'string' || r.name.length < 1)) return bad('a receipt whose name is no name')
    if (typeof r.name === 'string' && [...r.name].length > MAX_RECEIPT_NAME) return tooLarge('a receipt name too long')
    if (f.size > RECEIPT_MAX_BYTES) return tooLarge('a receipt above 10 MB')
    if (!claimsReceipt({ type: f.mediaType, name: typeof r.name === 'string' ? r.name : baseName(f.path) })) return bad('a receipt that is no image or PDF')
  }
  if (own.size !== listed.length || listed.some((id) => !own.has(id))) return bad('an entry whose receipts are not the ones it lists')
  return null
}

/** The entry with every reference mapped through [id] (a person, the expense a refund refunds) and each receipt it lists
 *  through [receipt] (the new id its upload is completed under). */
export function remapEntry(c: MoneyContent, id: (old: string) => string, receipt: (old: string) => string): MoneyContent {
  const base = { ...c, receipts: c.receipts.map(receipt) }
  if (c.kind === 'transfer') return { ...base, kind: 'transfer', from: id(c.from), to: id(c.to) } as MoneyContent
  const a = c.allocation
  const allocation =
    a.mode === 'equal'
      ? { mode: 'equal' as const, participants: a.participants.map(id) }
      : a.mode === 'shares'
        ? { mode: 'shares' as const, shares: a.shares.map((s) => ({ ...s, participant: id(s.participant) })) }
        : a.mode === 'exact'
          ? { mode: 'exact' as const, amounts: a.amounts.map((s) => ({ ...s, participant: id(s.participant) })) }
          : { mode: 'items' as const, rest: a.rest, items: a.items.map((i) => ({ ...i, participants: i.participants.map(id) })) }
  if (c.kind === 'expense') return { ...base, kind: 'expense', payers: c.payers.map((p) => ({ ...p, participant: id(p.participant) })), allocation } as MoneyContent
  const refund = { ...base, kind: 'refund', receivers: c.receivers.map((p) => ({ ...p, participant: id(p.participant) })), allocation } as MoneyContent & { refunds?: string }
  if (c.refunds) refund.refunds = id(c.refunds)
  return refund
}

/** A fresh id for each old one, the same for the same: what an import maps an archive's ids through. */
function minter(): (old: string) => string {
  const fresh = new Map<string, string>()
  return (old) => {
    let next = fresh.get(old)
    if (!next) fresh.set(old, (next = crypto.randomUUID()))
    return next
  }
}

export const importPot: ImportContentHook = async (ctx) => {
  const content = ctx.node.content
  if (!content || content.type !== EXPENSES_POT_CONTENT) throw new Error('import_invalid_content')
  if (typeof content.schema === 'number' && content.schema > SCHEMA) throw new Error('import_newer_content')
  const problem = validatePotContent(content)
  if (problem) throw new Error(`import_invalid_content: ${problem.message}`)
  const id = minter()
  // a receipt's ids in a map of their own: an archive's receipt id equal to one of its record ids still gets one of its own
  const receiptId = minter()
  const participants = content.participants as ExportedParticipant[]
  const entries = content.entries as { id: string; entry: Record<string, unknown>; receipts?: ExportedReceipt[] }[]
  const categories = ((content.categories ?? []) as { id: string; name: string }[]).map((c) => ({ id: id(c.id), name: c.name }))
  const deletedName = i18n.global.t('expenses.people.deletedPeople')
  const people: PotWrite[] = participants.map((p) => ({
    entryId: id(p.id),
    content: { kind: 'participant', name: p.deleted ? deletedName : p.name!, active: p.deleted ? false : p.active !== false, joined: p.joined },
  }))
  // Entry ids are minted first, so a refund naming an expense maps to that expense's new id.
  for (const e of entries) id(e.id)
  /** Each new entry's receipts, by the entry's new id: uploaded before the batch holding its first revision. */
  const uploads = new Map<string, { receiptId: string; receipt: ExportedReceipt }[]>()
  const money: PotWrite[] = entries.map((e) => {
    const body = readRevisionBody({ ...e.entry, v: 1, heads: [] })
    const { v: _v, heads: _heads, ...plain } = body
    const mapped = remapEntry(plain as MoneyContent, id, receiptId)
    // A category is a default id or one of the Pot's own, which is minted anew like every other id.
    if (mapped.kind !== 'transfer' && UUID.test(mapped.category)) mapped.category = id(mapped.category)
    if (e.receipts?.length) uploads.set(id(e.id), e.receipts.map((r) => ({ receiptId: receiptId(r.id), receipt: r })))
    return { entryId: id(e.id), content: mapped }
  })
  const base = { currency: content.baseCurrency as string, minorUnits: content.baseMinorUnits as number }
  const total = [...uploads.values()].flat().reduce((s, u) => s + u.receipt.file.size, 0)
  let done = 0
  const created = await createPotNode(ctx.scopeId, ctx.epochs, ctx.parent, ctx.name, base, ctx.createOptions, categories)
  const target = { kind: 'member' as const, scopeId: ctx.scopeId, nodeId: created.nodeId }
  const key = { key: created.nodeKey, epoch: created.epoch }
  /** The receipts of [batch]'s entries, uploaded into the new Pot and completed against them. */
  const receiptsOf = async (batch: readonly PotWrite[]) => {
    for (const w of batch) {
      for (const { receiptId: rid, receipt } of uploads.get(w.entryId) ?? []) {
        const file = await ctx.payload(receipt.file, receipt.name ?? baseName(receipt.file.path))
        const before = done
        await uploadReceipt(target, key, w.entryId, rid, file, {
          signal: ctx.signal,
          onProgress: (f) => void (total > 0 && ctx.onProgress?.((before + f * receipt.file.size) / total)),
        })
        done += receipt.file.size
      }
    }
  }
  try {
    await appendInBatches(ctx.scopeId, created.nodeId, key, base, [...people, ...money], receiptsOf)
  } catch (e) {
    // the Pot holds only part of what it imports: none is left behind (best effort; core trashes what holds it)
    await deleteNode(ctx.scopeId, created.nodeId).catch(() => {})
    throw e
  }
  return { nodeId: created.nodeId, nodeKey: created.nodeKey }
}
