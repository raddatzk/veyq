// Reporting an entry or one receipt (uc.moderation.report, ADR 0108 decisions 6 and 7): each is an `ITEM` — its id the
// entry's or the receipt's (they share the reportable-item id space, `PotReportableItems`).
//
// An entry's report reaches the WHOLE entry — every revision the Pot holds of it and every receipt kept with it — so the
// share carries each revision's `K_rev` (`revisionKeyOf`) under {@link ENTRY_REVISION_ROLE} with a null ref (a revision
// is no uuid; the moderator's review tries each key on each held revision, and a seal binds its address, ADR 0072), and
// each receipt's `K_blob` under {@link RECEIPT_KEY_ROLE} with its blob id as the ref. A receipt reported on its own hands
// over its `K_blob` alone. The backend holds what the item reaches; the server never reads the keys.
//
// One share carries at most `REPORT.maxKeysPerShare` (32) keys (inbox #540 keeps the gap): an entry hands over the version
// on screen first — its latest revision's key, then the receipts that version lists — and then its older revisions newest
// first, then any other receipt of it the Pot still holds, until the cap.
import { REPORT, type ReportKey } from '@veyq/crypto/v1'
import type { Access } from '../../core/access'
import { openReport } from '../../core/report/report'
import { reportScope } from '../../core/report/reportActions'
import { revisionKeyOf } from './domain/codec'
import { isMoney } from './domain/format'
import type { HeldRevision } from './domain/revisions'
import { RECEIPT_ROLE, receiptKeyOf, type HeldReceipt } from './receipts'

/** The role of one revision's `K_rev` in a report share (the moderator's review opens a held revision with it). */
export const ENTRY_REVISION_ROLE = 'ENTRY_REVISION'

/** The role of one receipt's `K_blob` in a report share, its ref the receipt's blob id: the blob's own role. */
export const RECEIPT_KEY_ROLE = RECEIPT_ROLE

/** One receipt's report key, or null when its wrap does not open under the Pot's key. */
function receiptKey(scopeId: string, nodeId: string, potKey: Uint8Array, r: HeldReceipt): ReportKey | null {
  try {
    return { role: RECEIPT_KEY_ROLE, ref: r.blobId, key: receiptKeyOf(scopeId, potKey, nodeId, r) }
  } catch {
    return null
  }
}

/** The keys a report of entry [entryId] hands over (see the header for the order), at most the cap. A wrap that does not
 *  open is left out (the moderator then sees that revision or receipt as unreadable). */
export function entryReportKeys(
  scopeId: string,
  nodeId: string,
  potKey: Uint8Array,
  held: readonly HeldRevision[],
  receipts: readonly HeldReceipt[],
  entryId: string,
): ReportKey[] {
  const revisions = held.filter((r) => r.entryId === entryId).sort((a, b) => b.revision - a.revision)
  const ofEntry = receipts.filter((r) => r.entryId === entryId)
  const shown = revisions.find((r) => r.body && isMoney(r.body))
  const listed = new Set(shown?.body && isMoney(shown.body) ? shown.body.receipts : [])
  const revisionKey = (r: HeldRevision): ReportKey | null => {
    try {
      return { role: ENTRY_REVISION_ROLE, ref: null, key: revisionKeyOf({ scopeId, nodeId, entryId, revision: r.revision }, potKey, r.wrappedRevisionKey) }
    } catch {
      return null
    }
  }
  const candidates: (() => ReportKey | null)[] = [
    ...revisions.slice(0, 1).map((r) => () => revisionKey(r)),
    ...ofEntry.filter((r) => listed.has(r.receiptId)).map((r) => () => receiptKey(scopeId, nodeId, potKey, r)),
    ...revisions.slice(1).map((r) => () => revisionKey(r)),
    ...ofEntry.filter((r) => !listed.has(r.receiptId)).map((r) => () => receiptKey(scopeId, nodeId, potKey, r)),
  ]
  const keys: ReportKey[] = []
  for (const next of candidates) {
    if (keys.length >= REPORT.maxKeysPerShare) break
    const k = next()
    if (k) keys.push(k)
  }
  return keys
}

/** Open the report dialog for one entry of the Pot [nodeId], for whoever sees it (a member or a guest). */
export function reportEntry(
  access: Access,
  nodeId: string,
  potKey: Uint8Array,
  held: readonly HeldRevision[],
  receipts: readonly HeldReceipt[],
  entryId: string,
): void {
  const scopeId = reportScope(access)
  openReport({ access, scopeId, targets: [{ kind: 'ITEM', id: entryId, keys: () => entryReportKeys(scopeId, nodeId, potKey, held, receipts, entryId) }] })
}

/** The key a report of one receipt hands over: its `K_blob` alone (none when its wrap does not open). */
export function receiptReportKeys(scopeId: string, nodeId: string, potKey: Uint8Array, receipt: HeldReceipt): ReportKey[] {
  const k = receiptKey(scopeId, nodeId, potKey, receipt)
  return k ? [k] : []
}

/** Open the report dialog for one receipt on its own, an `ITEM` by its receipt id. */
export function reportReceipt(access: Access, nodeId: string, potKey: Uint8Array, receipt: HeldReceipt): void {
  const scopeId = reportScope(access)
  openReport({ access, scopeId, targets: [{ kind: 'ITEM', id: receipt.receiptId, keys: () => receiptReportKeys(scopeId, nodeId, potKey, receipt) }] })
}
