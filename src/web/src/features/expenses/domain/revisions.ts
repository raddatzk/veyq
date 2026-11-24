// A Pot's revisions as the client holds them once read: each served row opened at the address it was read by, with the
// digest of its body as served. Everything downstream — the ledger check (`heads.ts`) and the balances (`ledger.ts`) —
// reads these, never the wire rows, so a row that does not open is carried as unreadable rather than dropped: dropping it
// would hide exactly what the ledger check must see. The opening and the grouping are the Kotlin core's
// (`expenses/Codec.kt`, `Revisions`, through `@veyq/core-js/expenses`, ADR 0155 decision 7.3); this file keeps the types and
// the one handle a list of held revisions is read into. It holds no rule.
import { markRaw } from 'vue'
import * as core from '@veyq/core-js/expenses'
import type { RevisionUnreadable, SealedRevision } from './codec'
import type { RevisionBody } from './format'

/** One revision by its address inside a Pot. */
export type RevisionRef = { entryId: string; revision: number }

/** The one id of a revision in a Pot's list (the list's record id, and every map key here). */
export const revisionId = (r: RevisionRef): string => core.revisionId(r)

/** One served row, decoded from the wire's base64 but not opened. `writer` is a member read's (`user:{id}` or
 *  `share:{identityId}`); a guest's read has none, and nothing here asks for one. */
export type ServedRevision = RevisionRef & SealedRevision & {
  createdAt: Date
  writer?: string
  /** On a tombstone: when the purge erases its record's receipts if it is still the latest (ADR 0108 decision 6). */
  purgeAfter?: Date | null
}

/** One revision as held: the row, its body's digest, and what it opened to — or why it did not. */
export type HeldRevision = ServedRevision & {
  digest: string
  body: RevisionBody | null
  unreadable: RevisionUnreadable['reason'] | null
}

/**
 * The core's handle of one list of held revisions: each body read into the core once per list, not on every rule's call
 * (cr-web-expenses-on-the-core decision 5's fallback, measured). Kept beside the list it was made from — a list is replaced,
 * never changed in place (`potSession.ts`) —, `markRaw`, read only by the core.
 */
const handles = new WeakMap<readonly HeldRevision[], core.PotRevisions>()
export function revisionsOf(held: readonly HeldRevision[]): core.PotRevisions {
  let h = handles.get(held)
  if (!h) {
    h = markRaw(core.potRevisions(held as unknown as readonly core.HeldShape[]))
    handles.set(held, h)
  }
  return h
}

/** Open every served row of the Pot `nodeId` in `scopeId` with the Pot's key. A row that does not open is held as
 *  unreadable with its reason; nothing throws for one row. */
export const openServedRevisions = (scopeId: string, nodeId: string, potKey: Uint8Array, served: readonly ServedRevision[]): HeldRevision[] =>
  [...core.openServedRevisions(scopeId, nodeId, potKey, served as unknown as readonly core.ServedShape[])] as unknown as HeldRevision[]

/** The held revisions grouped by record, each record's revisions ascending (the objects given). */
export const byRecord = (held: readonly HeldRevision[]): Map<string, HeldRevision[]> =>
  core.byRecord(revisionsOf(held)) as unknown as Map<string, HeldRevision[]>
