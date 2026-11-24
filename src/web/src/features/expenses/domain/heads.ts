// The heads of a Pot and the ledger check (ADR 0108 decision 5). A seal binds a revision to its own address, not to its
// neighbours (ADR 0072), so the server could serve the list with a revision left out and every balance would come out
// wrong unseen. Each revision therefore names the Pot's heads as its writer read them — every revision no other revision
// names, with the SHA-256 of its sealed body — and every read checks that each named revision is there and unchanged.
//
// What the check catches: a revision dropped that somebody wrote after (it is named), a body swapped under its address
// (its digest moves), a record with a hole in its revisions (a revision `n + 1` is taken only on `n`, so a gap is a
// drop), and a row that does not open where it is served. What it cannot catch: the newest revisions held back, which
// looks exactly like a stale read — the platform's freshness limit (ADR 0072 decision 5, inbox #161), stated, not closed.
//
// The one legitimate absence is an erasure (ADR 0108 decision 3): a member's sealed erasure revision of a participant's
// record, after which the server holds none of that record's earlier revisions. It covers ONLY its own record's earlier
// revisions, and only as a revision that opened to `kind: "erasure"` — the server's `erasure` flag is not read, because
// the server cannot write a sealed erasure and so cannot use one to hide anything.
//
// The rules are the Kotlin core's (`expenses/LedgerCheck.kt`, `Revisions`, through `@veyq/core-js/expenses`, ADR 0155
// decision 7.3), pinned by `contracts/expenses/v1-vectors.json` (`check`); this file keeps the types. It holds no rule.
import * as core from '@veyq/core-js/expenses'
import type { Head } from './format'
import { revisionsOf, type HeldRevision, type RevisionRef } from './revisions'

/** Every held revision that no held revision names as a head — what the next write names (up to the format's `MAX_HEADS`). */
export const currentHeads = (held: readonly HeldRevision[]): HeldRevision[] =>
  [...core.currentHeads(revisionsOf(held))] as unknown as HeldRevision[]

/** The heads a revision written now names: the Pot's current heads, the `MAX_HEADS` oldest by the time the server stored
 *  them when there are more (the next write names the rest, the schema's rule). Empty for an empty Pot. */
export const headsToName = (held: readonly HeldRevision[]): Head[] => [...core.headsToName(revisionsOf(held))]

/** What a read of the Pot found wrong with its ledger. `complete` is true only when all three lists are empty; `entries`
 *  are the records any of them touch — what the ledger notice lists. */
export type LedgerCheck = {
  complete: boolean
  /** Named by a held revision, or a hole below a record's latest, and not held — and no erasure of its own record covers it. */
  missing: RevisionRef[]
  /** Named by a held revision and held, but its body is not the one that was named (the digest differs). */
  altered: RevisionRef[]
  /** Held, but it does not open where it is served (moved, swapped or forged), or not to a v1 body. */
  unreadable: RevisionRef[]
  entries: string[]
}

/** Run the ledger check over everything held for one Pot. Pure: the revisions were opened by `openServedRevisions`. */
export const checkLedger = (held: readonly HeldRevision[]): LedgerCheck => core.checkLedger(revisionsOf(held)) as unknown as LedgerCheck
