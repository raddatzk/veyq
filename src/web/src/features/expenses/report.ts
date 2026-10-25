// Reporting an entry (uc.moderation.report, ADR 0108 decision 6, CR decision 7): an entry is an `ITEM` — its id the
// entry's — and the report reaches the WHOLE entry, every revision the Pot holds of it, so the share carries each
// revision's `K_rev` (`revisionKeyOf`) under the role {@link ENTRY_REVISION_ROLE}. A report key's `ref` is a uuid or null
// (`@veyq/crypto`'s report codec), and a revision is no uuid, so every key goes with a null ref and the moderator's review
// tries each key on each held revision: a seal binds its address (ADR 0072), so only the right one opens. The backend
// holds every revision of the entry (`PotReportableItems`); the server never reads the keys.
//
// One share carries at most `REPORT.maxKeysPerShare` (32) keys, so an entry of more revisions hands over its NEWEST 32 —
// the version on screen and its history back from it; a receipt's `K_blob` joins with `cr-expenses-receipts`.
import { REPORT, type ReportKey } from '@veyq/crypto/v1'
import type { Access } from '../../core/access'
import { openReport } from '../../core/report/report'
import { reportScope } from '../../core/report/reportActions'
import { revisionKeyOf } from './domain/codec'
import type { HeldRevision } from './domain/revisions'

/** The role of one revision's `K_rev` in a report share (the moderator's review opens a held revision with it). */
export const ENTRY_REVISION_ROLE = 'ENTRY_REVISION'

/** The keys a report of entry [entryId] hands over: each held revision's `K_rev`, newest first, at most the cap. A wrap
 *  that does not open is left out (the moderator then sees that revision as unreadable). */
export function entryReportKeys(scopeId: string, nodeId: string, potKey: Uint8Array, held: readonly HeldRevision[], entryId: string): ReportKey[] {
  const keys: ReportKey[] = []
  const revisions = held.filter((r) => r.entryId === entryId).sort((a, b) => b.revision - a.revision)
  for (const r of revisions) {
    if (keys.length >= REPORT.maxKeysPerShare) break
    try {
      keys.push({ role: ENTRY_REVISION_ROLE, ref: null, key: revisionKeyOf({ scopeId, nodeId, entryId, revision: r.revision }, potKey, r.wrappedRevisionKey) })
    } catch {
      // not this one
    }
  }
  return keys
}

/** Open the report dialog for one entry of the Pot [nodeId], for whoever sees it (a member or a guest). */
export function reportEntry(access: Access, nodeId: string, potKey: Uint8Array, held: readonly HeldRevision[], entryId: string): void {
  const scopeId = reportScope(access)
  openReport({ access, scopeId, targets: [{ kind: 'ITEM', id: entryId, keys: () => entryReportKeys(scopeId, nodeId, potKey, held, entryId) }] })
}
