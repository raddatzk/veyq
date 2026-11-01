// The ledger check (ADR 0108 decision 5) through the real crypto — every revision written by the real write path
// (`sealPotWrites`: the heads it read, sealed under its own K_rev), served as the wire would, opened by the real reader.
// Each case beside its control: a served list missing one revision marks the Pot (the full list does not); a missing
// revision covered by an erasure of its own record passes, one covered only by another record's does not; a revision
// moved to another entry does not open; a re-key (every K_rev re-wrapped) leaves every digest matching.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { rewrapRevisionKey, sealRevision } from './codec'
import { checkLedger, currentHeads, headsToName } from './heads'
import { buildLedger } from './ledger'
import { sealPotWrites, type PotWrite } from './potApi'
import { openServedRevisions, revisionId, type HeldRevision, type ServedRevision } from './revisions'
import { MAX_HEADS, type RevisionBody } from './format'

const SCOPE = '22222222-2222-4222-8222-222222222222'
const POT = '0e000000-0000-4000-b000-000000000001'
const BASE = { currency: 'EUR', minorUnits: 2 }
const ANNA = '0e000000-0000-4000-8000-000000000001'
const BEN = '0e000000-0000-4000-8000-000000000002'
const CLEO = '0e000000-0000-4000-8000-000000000003'
const DINNER = '0e000000-0000-4000-9000-000000000001'
const TAXI = '0e000000-0000-4000-9000-000000000002'
const BAR = '0e000000-0000-4000-9000-000000000003'

const person = (name: string, joined: number, active = true): PotWrite['content'] => ({ kind: 'participant', name, active, joined })
const expense = (title: string, amount: number, payer: string): PotWrite['content'] => ({
  kind: 'expense', title, date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount,
  payers: [{ participant: payer, amount }], allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, BEN, CLEO] } }, receipts: [],
})

/** A server: what was appended, in order, as rows it serves. */
class Server {
  rows: ServedRevision[] = []
  private clock = 0
  constructor(readonly potKey: Uint8Array) {}
  /** What a reader holds after reading everything served. */
  read(rows = this.rows, key = this.potKey): HeldRevision[] {
    return openServedRevisions(SCOPE, POT, key, rows)
  }
  /** One writer's batch on what it read (the real write path), stored as appended. */
  append(writes: PotWrite[], held = this.read()): void {
    for (const w of sealPotWrites({ scopeId: SCOPE, nodeId: POT }, this.potKey, held, writes, BASE)) {
      this.rows.push({ entryId: w.entryId, revision: w.revision, createdAt: new Date(1_767_225_600_000 + this.clock++), sealedBody: fromB64std(w.sealedBody), wrappedRevisionKey: fromB64std(w.wrappedRevisionKey) })
    }
  }
  /** A member's erasure of a participant: its sealed erasure revision, then none of its earlier revisions. */
  erase(entryId: string): void {
    const held = this.read()
    const latest = Math.max(...held.filter((r) => r.entryId === entryId).map((r) => r.revision))
    const joined = buildLedger(held, BASE).participants.find((p) => p.id === entryId)!.joined
    const s = sealRevision({ scopeId: SCOPE, nodeId: POT, entryId, revision: latest + 1 }, this.potKey, { v: 1, heads: headsToName(held), kind: 'erasure', joined })
    this.rows = this.rows.filter((r) => r.entryId !== entryId)
    this.rows.push({ entryId, revision: latest + 1, createdAt: new Date(1_767_225_600_000 + this.clock++), ...s })
  }
  without(entryId: string, revision: number): ServedRevision[] {
    return this.rows.filter((r) => !(r.entryId === entryId && r.revision === revision))
  }
}

/** A Pot of three people, two expenses and an edit, each written on what its writer read. */
function trip(): Server {
  const server = new Server(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  server.append([{ entryId: DINNER, content: expense('Dinner', 9000, ANNA) }])
  server.append([{ entryId: TAXI, content: expense('Taxi', 3000, BEN) }])
  server.append([{ entryId: DINNER, content: expense('Dinner', 9300, ANNA) }]) // an edit: DINNER:2 names DINNER:1's successor heads
  server.append([{ entryId: BAR, content: expense('Bar', 600, CLEO) }])
  return server
}

describe('the ledger check', () => {
  it('passes the full list (control)', () => {
    const check = checkLedger(trip().read())
    expect(check).toEqual({ complete: true, missing: [], altered: [], unreadable: [], entries: [] })
  })

  it('marks the Pot when a revision somebody wrote after is missing — and it changes a balance unseen otherwise', () => {
    const server = trip()
    const full = buildLedger(server.read(), BASE)
    const thinned = server.read(server.without(TAXI, 1)) // the server drops Ben's taxi
    const check = checkLedger(thinned)
    expect(check.complete).toBe(false)
    expect(check.missing).toEqual([{ entryId: TAXI, revision: 1 }])
    expect(check.entries).toEqual([TAXI])
    expect(buildLedger(thinned, BASE).balances.get(BEN)).not.toBe(full.balances.get(BEN)) // what the notice is for
  })

  it('marks a hole in a record (an edit\'s base dropped) where no held revision names it any more', () => {
    const server = trip()
    // DINNER:1 is named only by TAXI:1 (DINNER:2 names TAXI:1): with both dropped, nothing held names DINNER:1, and
    // only the rule that a record runs 1..latest without a hole sees it.
    const rows = server.without(DINNER, 1).filter((r) => r.entryId !== TAXI)
    const held = server.read(rows)
    expect(held.some((r) => r.body?.heads.some((h) => h.entryId === DINNER && h.revision === 1))).toBe(false)
    expect(checkLedger(held).missing).toEqual([{ entryId: DINNER, revision: 1 }, { entryId: TAXI, revision: 1 }])
  })

  it('cannot see the newest revision held back — the freshness limit, stated: nothing names it yet', () => {
    const server = trip()
    expect(checkLedger(server.read(server.without(BAR, 1))).complete).toBe(true)
  })

  it('a missing revision covered by an erasure of its OWN record passes', () => {
    const server = trip()
    server.append([{ entryId: CLEO, content: person('Cleopatra', 3) }]) // a rename, then settled away and deleted
    server.append([{ entryId: BAR, content: { kind: 'deleted' } }])
    server.append([{ entryId: DINNER, content: { kind: 'deleted' } }])
    server.append([{ entryId: TAXI, content: { kind: 'deleted' } }])
    server.erase(CLEO) // the server now holds CLEO:3 (the erasure) and no CLEO:1, CLEO:2 — both named by later heads
    const held = server.read()
    expect(held.filter((r) => r.entryId === CLEO).map((r) => r.revision)).toEqual([3])
    const check = checkLedger(held)
    expect(check).toMatchObject({ complete: true, missing: [] })
    expect(buildLedger(held, BASE).participants.find((p) => p.id === CLEO)).toMatchObject({ erased: true, name: null })
  })

  it('a missing revision covered only by ANOTHER record\'s erasure does not pass', () => {
    const server = trip()
    server.append([{ entryId: BAR, content: { kind: 'deleted' } }])
    server.append([{ entryId: DINNER, content: { kind: 'deleted' } }])
    server.append([{ entryId: TAXI, content: { kind: 'deleted' } }])
    server.erase(CLEO)
    const check = checkLedger(server.read(server.without(DINNER, 1))) // the server drops a revision of another record too
    expect(check.complete).toBe(false)
    expect(check.missing).toEqual([{ entryId: DINNER, revision: 1 }])
  })

  it('an erasure covers only the revisions below it: once a later write named it, the erasure itself cannot go', () => {
    const server = trip()
    server.erase(CLEO)
    server.append([{ entryId: TAXI, content: expense('Taxi', 3100, BEN) }]) // names CLEO:2, the erasure, among its heads
    const check = checkLedger(server.read(server.without(CLEO, 2)))
    expect(check.complete).toBe(false)
    expect(check.missing).toContainEqual({ entryId: CLEO, revision: 2 })
  })

  it('a server-made erasure covers nothing: the erasure must open as one', () => {
    const server = trip()
    server.erase(CLEO)
    const forged = server.rows.map((r) => (r.entryId === CLEO ? { ...r, sealedBody: v1.randomBytes(r.sealedBody.length) } : r))
    const check = checkLedger(server.read(forged))
    expect(check.complete).toBe(false)
    expect(check.unreadable).toEqual([{ entryId: CLEO, revision: 2 }])
    expect(check.missing).toContainEqual({ entryId: CLEO, revision: 1 })
  })

  it('a revision moved to another entry does not open, and the place it left is missing', () => {
    const server = trip()
    const moved = server.rows.map((r) => (r.entryId === TAXI ? { ...r, entryId: BAR, revision: 2 } : r)) // TAXI:1 served as BAR:2
    const held = server.read(moved)
    expect(held.find((r) => r.entryId === BAR && r.revision === 2)).toMatchObject({ body: null, unreadable: 'key' })
    const check = checkLedger(held)
    expect(check.unreadable).toEqual([{ entryId: BAR, revision: 2 }])
    expect(check.missing).toContainEqual({ entryId: TAXI, revision: 1 })
    expect(buildLedger(held, BASE).entries.find((e) => e.id === BAR)!.status).toBe('unreadable')
  })

  it('a body swapped for another a key holder sealed at the same address is altered', () => {
    const server = trip()
    const forged = sealRevision({ scopeId: SCOPE, nodeId: POT, entryId: TAXI, revision: 1 }, server.potKey, { v: 1, heads: [], ...expense('Taxi', 1, BEN) } as RevisionBody)
    const rows = server.rows.map((r) => (r.entryId === TAXI ? { ...r, ...forged } : r))
    const check = checkLedger(server.read(rows))
    expect(check.altered).toEqual([{ entryId: TAXI, revision: 1 }])
    expect(check.complete).toBe(false)
  })

  it('a re-key — every K_rev re-wrapped under the Pot\'s new key — leaves every body, every digest and the check as they were', () => {
    const server = trip()
    const before = server.read()
    const newKey = v1.randomBytes(32)
    const rekeyed = server.rows.map((r) => ({
      ...r,
      wrappedRevisionKey: rewrapRevisionKey({ scopeId: SCOPE, nodeId: POT, entryId: r.entryId, revision: r.revision }, server.potKey, newKey, r.wrappedRevisionKey),
    }))
    const after = server.read(rekeyed, newKey)
    expect(after.map((r) => r.digest)).toEqual(before.map((r) => r.digest))
    expect(after.every((r) => r.body !== null)).toBe(true)
    expect(checkLedger(after).complete).toBe(true)
    expect(server.read(rekeyed, server.potKey).every((r) => r.body === null)).toBe(true) // the old key opens none of the new wraps
  })
})

describe('the heads a write names', () => {
  it('are every revision no held revision names; an edit names the record\'s former head', () => {
    const server = trip()
    const held = server.read()
    const heads = currentHeads(held).map(revisionId)
    expect(heads).toEqual([`${BAR}:1`]) // a single line of writes: each named the one before it
    const dinner2 = held.find((r) => r.entryId === DINNER && r.revision === 2)!
    expect(dinner2.body!.heads.map(revisionId)).toEqual([`${TAXI}:1`])
  })

  it('two writers who read the same Pot both name its heads; the next write names both', () => {
    const server = trip()
    const read = server.read()
    server.append([{ entryId: '0e000000-0000-4000-9000-0000000000a1', content: expense('Ice', 300, ANNA) }], read)
    server.append([{ entryId: '0e000000-0000-4000-9000-0000000000a2', content: expense('Bus', 400, BEN) }], read)
    expect(currentHeads(server.read()).map(revisionId).sort()).toEqual([`0e000000-0000-4000-9000-0000000000a1:1`, `0e000000-0000-4000-9000-0000000000a2:1`])
    server.append([{ entryId: TAXI, content: expense('Taxi', 3200, BEN) }])
    expect(currentHeads(server.read()).map(revisionId)).toEqual([`${TAXI}:2`])
    expect(checkLedger(server.read()).complete).toBe(true)
  })

  it(`name at most ${MAX_HEADS}, the oldest by the time the server stored them`, () => {
    const server = trip()
    const read = server.read()
    const ids = Array.from({ length: MAX_HEADS + 6 }, (_, i) => `0e000000-0000-4000-9000-1${String(i).padStart(11, '0')}`)
    for (const id of ids) server.append([{ entryId: id, content: expense('Snack', 100, ANNA) }], read)
    const named = headsToName(server.read())
    expect(named.map((h) => h.entryId)).toEqual(ids.slice(0, MAX_HEADS))
  })
})
