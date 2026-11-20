// The shape of an Expenses Pot's content from a download (`pot` in Expenses' own contracts/export/expenses/content.schema.json),
// checked before an import writes anything (ADR 0082 decision 10): the registry's `validateContent` for the Pot kind, which
// core's dry run (`planImport`) calls synchronously - so it lives apart from `exportImport.ts`, whose download, import and copy
// are fetched on first use (`register.ts`). Pure: no network, no key.
import type { ContentProblem } from '../../core/applications/registry'
import type { ManifestContent } from '../../core/export/manifest'
import { namedParticipants } from './domain/ledger'
import { CURRENCY, MAX_RECEIPTS, readRevisionBody, UUID, RevisionFormatInvalid, type RevisionBody } from './domain/format'
import { MAX_CATEGORY_NAME, MAX_OWN_CATEGORIES } from './domain/potMeta'
import { claimsReceipt, RECEIPT_MAX_BYTES } from './domain/receiptRules'

export const EXPENSES_POT_CONTENT = 'expenses.pot'
/** The version of the content schema this build writes and reads; its application is Expenses' registered id (`register.ts`), the
 *  `<application>` part of the content type above. */
export const EXPENSES_CONTENT_VERSION = 1

const MAX_PARTICIPANTS = 1000
const MAX_ENTRIES = 100_000
/** The longest receipt name the content carries (the content schema's `receipt.name` maxLength). */
export const MAX_RECEIPT_NAME = 1024

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const bad = (message: string): ContentProblem => ({ code: 'invalid_manifest', message })
const tooLarge = (message: string): ContentProblem => ({ code: 'too_large', message })

/** The last segment of an archive path: a receipt's name where its content names none. */
export const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

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
  if (content.tenantId !== undefined && (typeof content.tenantId !== 'string' || !UUID.test(content.tenantId))) return bad('a tenant that is no id')
  if (!Array.isArray(content.participants)) return bad('a Pot without its people')
  if (content.participants.length > MAX_PARTICIPANTS) return tooLarge('too many people')
  const ids = new Set<string>()
  const members = new Set<string>()
  for (const p of content.participants) {
    if (!isObj(p) || typeof p.id !== 'string' || !UUID.test(p.id) || ids.has(p.id)) return bad('a person without an id of its own')
    ids.add(p.id)
    if (typeof p.joined !== 'number' || !Number.isInteger(p.joined) || p.joined < 0) return bad('a person without a join time')
    if (p.deleted !== undefined && p.deleted !== true) return bad('a person deleted in another way')
    if (p.deleted !== true && (typeof p.name !== 'string' || p.name.length < 1)) return bad('a person without a name')
    if (typeof p.name === 'string' && [...p.name].length > 100) return tooLarge('a name too long')
    if (p.active !== undefined && typeof p.active !== 'boolean') return bad('a person whose state is no switch')
    if (p.memberId !== undefined) {
      if (typeof p.memberId !== 'string' || !UUID.test(p.memberId)) return bad('a member that is no id')
      if (p.deleted === true) return bad('a deleted person who is a member')
      if (members.has(p.memberId)) return bad('a member who is two people')
      members.add(p.memberId)
    }
  }
  if (members.size > 0 && content.tenantId === undefined) return bad('members without their tenant')
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
