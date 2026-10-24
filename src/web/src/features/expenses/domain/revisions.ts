// A Pot's revisions as the client holds them once read: each served row opened at the address it was read by, with the
// digest of its body as served. Everything downstream — the ledger check (`heads.ts`) and the balances (`ledger.ts`) —
// reads these, never the wire rows, so a row that does not open is carried as unreadable rather than dropped: dropping it
// would hide exactly what the ledger check must see.
import { openRevision, revisionDigest, RevisionUnreadable, type SealedRevision } from './codec'
import type { RevisionBody } from './format'

/** One revision by its address inside a Pot. */
export type RevisionRef = { entryId: string; revision: number }

/** The one id of a revision in a Pot's list (the list's record id, and every map key here). */
export const revisionId = (r: RevisionRef): string => `${r.entryId}:${r.revision}`

/** One served row, decoded from the wire's base64 but not opened. `writer` is a member read's (`user:{id}` or
 *  `share:{identityId}`); a guest's read has none, and nothing here asks for one. */
export type ServedRevision = RevisionRef & SealedRevision & { createdAt: Date; writer?: string }

/** One revision as held: the row, its body's digest, and what it opened to — or why it did not. */
export type HeldRevision = ServedRevision & {
  digest: string
  body: RevisionBody | null
  unreadable: RevisionUnreadable['reason'] | null
}

/** Open every served row of the Pot `nodeId` in `scopeId` with the Pot's key. A row that does not open is held as
 *  unreadable with its reason; nothing throws for one row. */
export function openServedRevisions(scopeId: string, nodeId: string, potKey: Uint8Array, served: readonly ServedRevision[]): HeldRevision[] {
  return served.map((row) => {
    const digest = revisionDigest(row.sealedBody)
    try {
      const body = openRevision({ scopeId, nodeId, entryId: row.entryId, revision: row.revision }, potKey, row)
      return { ...row, digest, body, unreadable: null }
    } catch (e) {
      if (!(e instanceof RevisionUnreadable)) throw e
      return { ...row, digest, body: null, unreadable: e.reason }
    }
  })
}

/** The held revisions grouped by record, each record's revisions ascending. */
export function byRecord(held: readonly HeldRevision[]): Map<string, HeldRevision[]> {
  const records = new Map<string, HeldRevision[]>()
  for (const r of held) {
    const list = records.get(r.entryId)
    if (list) list.push(r)
    else records.set(r.entryId, [r])
  }
  for (const list of records.values()) list.sort((a, b) => a.revision - b.revision)
  return records
}
