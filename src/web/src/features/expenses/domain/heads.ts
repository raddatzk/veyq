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
import { MAX_HEADS, type Head } from './format'
import { revisionId, byRecord, type HeldRevision, type RevisionRef } from './revisions'

/** Every held revision that no held revision names as a head — what the next write names (up to {@link MAX_HEADS}). */
export function currentHeads(held: readonly HeldRevision[]): HeldRevision[] {
  const named = new Set<string>()
  for (const r of held) for (const h of r.body?.heads ?? []) named.add(revisionId(h))
  return held.filter((r) => !named.has(revisionId(r)))
}

const byStoredTime = (a: HeldRevision, b: HeldRevision): number =>
  a.createdAt.getTime() - b.createdAt.getTime() || (a.entryId < b.entryId ? -1 : a.entryId > b.entryId ? 1 : a.revision - b.revision)

/** The heads a revision written now names: the Pot's current heads, the {@link MAX_HEADS} oldest by the time the server
 *  stored them when there are more (the next write names the rest, the schema's rule). Empty for an empty Pot. */
export function headsToName(held: readonly HeldRevision[]): Head[] {
  return [...currentHeads(held)]
    .sort(byStoredTime)
    .slice(0, MAX_HEADS)
    .map((r) => ({ entryId: r.entryId, revision: r.revision, digest: r.digest }))
}

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
export function checkLedger(held: readonly HeldRevision[]): LedgerCheck {
  const present = new Map(held.map((r) => [revisionId(r), r] as const))
  const records = byRecord(held)
  // The highest sealed erasure of each record: it covers every earlier revision of that record, and nothing else.
  const erasedBelow = new Map<string, number>()
  for (const r of held) {
    if (r.body?.kind !== 'erasure') continue
    erasedBelow.set(r.entryId, Math.max(erasedBelow.get(r.entryId) ?? 0, r.revision))
  }
  const covered = (ref: RevisionRef): boolean => ref.revision < (erasedBelow.get(ref.entryId) ?? 0)

  const missing = new Map<string, RevisionRef>()
  const altered = new Map<string, RevisionRef>()
  for (const r of held) {
    for (const h of r.body?.heads ?? []) {
      const id = revisionId(h)
      const found = present.get(id)
      if (found) {
        if (found.digest !== h.digest) altered.set(id, { entryId: h.entryId, revision: h.revision })
      } else if (!covered(h)) {
        missing.set(id, { entryId: h.entryId, revision: h.revision })
      }
    }
  }
  // A record's revisions are 1..latest without a hole: `n + 1` is only ever taken on `n`, and only an erasure removes one.
  for (const [entryId, revisions] of records) {
    const latest = revisions[revisions.length - 1]!.revision
    for (let revision = 1; revision < latest; revision++) {
      const ref = { entryId, revision }
      if (!present.has(revisionId(ref)) && !covered(ref)) missing.set(revisionId(ref), ref)
    }
  }
  const unreadable = held.filter((r) => r.body === null).map((r) => ({ entryId: r.entryId, revision: r.revision }))

  const order = (a: RevisionRef, b: RevisionRef) => (a.entryId < b.entryId ? -1 : a.entryId > b.entryId ? 1 : a.revision - b.revision)
  const lists = { missing: [...missing.values()].sort(order), altered: [...altered.values()].sort(order), unreadable: unreadable.sort(order) }
  const entries = [...new Set([...lists.missing, ...lists.altered, ...lists.unreadable].map((r) => r.entryId))].sort()
  return { complete: entries.length === 0, ...lists, entries }
}
