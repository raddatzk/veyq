// A Pot's reads and writes on the wire — recorded at `fetch`, no mock of the API client (src/web/CLAUDE.md: an absence
// asserted through the generated client proves nothing about the wire). The member's read sends the server's opaque
// cursor back unchanged and asks only its first page conditionally; the guest's pages `after` the cursor and keeps the
// first page's quoted tag; a write sends revision `n + 1` sealed at its address and the heads it read; a `409
// stale_revision` names the stale records; and an entry that does not add up never leaves the client.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { openRevision } from './codec'
import { headsToName } from './heads'
import { appendPotWrites, applyPotChange, eraseParticipant, PotWriteRefused, readGuestPot, readMemberPot, sealPotWrites, StaleRevisionError, type PotTarget, type PotWrite } from './potApi'
import { openServedRevisions, revisionId, type HeldRevision, type ServedRevision } from './revisions'

const SCOPE = '22222222-2222-4222-8222-222222222222'
const POT = '0e000000-0000-4000-b000-000000000001'
const SHARE = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde'
const BASE = { currency: 'EUR', minorUnits: 2 }
const ANNA = '0e000000-0000-4000-8000-000000000001'
const BEN = '0e000000-0000-4000-8000-000000000002'
const DINNER = '0e000000-0000-4000-9000-000000000001'
const potKey = v1.randomBytes(32)
const MEMBER: PotTarget = { kind: 'member', scopeId: SCOPE, nodeId: POT }
const GUEST: PotTarget = { kind: 'guest', scopeId: SCOPE, nodeId: POT, shareId: SHARE, contributorToken: 'Y29udHJpYnV0b3I=', pwToken: 'cHc=' }

afterEach(() => vi.unstubAllGlobals())

type Call = { url: URL; method: string; headers: Headers; json: Record<string, unknown> | null }
function stubFetch(answer: (call: Call) => Response): Call[] {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: new URL(String(input), 'https://veyq.test'), method: init?.method ?? 'GET', headers: new Headers(init?.headers), json: init?.body ? JSON.parse(String(init.body)) : null }
    calls.push(call)
    return answer(call)
  }))
  return calls
}
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

const person = (name: string, joined: number): PotWrite['content'] => ({ kind: 'participant', name, active: true, joined })
const dinner = (amount: number, paid = amount): PotWrite['content'] => ({
  kind: 'expense', title: 'Dinner', date: '2026-07-01', category: 'food', currency: 'EUR', minorUnits: 2, rate: '1', amount,
  payers: [{ participant: ANNA, amount: paid }], allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, BEN] } }, receipts: [],
})

/** A held Pot written through the real write path: Anna and Ben, and Anna's dinner. */
function heldPot(withDinner = true): HeldRevision[] {
  let held: HeldRevision[] = []
  let clock = 0
  const write = (writes: PotWrite[]) => {
    const rows: ServedRevision[] = sealPotWrites(MEMBER, potKey, held, writes, BASE).map((w) => ({
      entryId: w.entryId, revision: w.revision, createdAt: new Date(1_767_225_600_000 + clock++), sealedBody: fromB64std(w.sealedBody), wrappedRevisionKey: fromB64std(w.wrappedRevisionKey),
    }))
    held = [...held, ...openServedRevisions(SCOPE, POT, potKey, rows)]
  }
  write([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  if (withDinner) write([{ entryId: DINNER, content: dinner(1000) }])
  return held
}
const wire = (r: HeldRevision) => ({
  entryId: r.entryId, revision: r.revision, tombstone: false, erasure: false, sealedBody: b64std(r.sealedBody), wrappedRevisionKey: b64std(r.wrappedRevisionKey), createdAt: r.createdAt.toISOString(),
})

describe("a member's read", () => {
  it('pages with the opaque cursor sent back unchanged, conditional on its first page only, and keeps the first page\'s counters', async () => {
    const held = heldPot()
    const calls = stubFetch((c) =>
      c.url.searchParams.get('since') === '3,x:1'
        ? json({ revisions: held.slice(0, 2).map((r) => ({ ...wire(r), writer: 'user:aaaa' })), more: true, cursor: '17,' + revisionId(held[1]!), ledgerRevision: 7, deleteRevision: 2, revisionIds: null,
            guestWriters: [{ identityId: '6f7a8b9c-0d1e-4f3a-8b5c-6d7e8f9a0b1c', shareId: SHARE, sealedName: 'c2VhbGVk' }] })
        : json({ revisions: [{ ...wire(held[2]!), writer: 'share:6f7a8b9c-0d1e-4f3a-8b5c-6d7e8f9a0b1c' }], more: false, cursor: '18,' + revisionId(held[2]!), ledgerRevision: 8, deleteRevision: 2, revisionIds: null, guestWriters: [] }),
    )
    const read = await readMemberPot(SCOPE, POT, { ifNoneMatch: '6', since: '3,x:1', sinceDelete: 2 })
    expect(calls).toHaveLength(2)
    expect(calls[0]!.url.pathname).toBe(`/api/scopes/${SCOPE}/pots/${POT}/revisions`)
    expect(calls[0]!.headers.get('If-None-Match')).toBe('6')
    expect(calls[0]!.url.searchParams.get('since')).toBe('3,x:1')
    expect(calls[1]!.headers.get('If-None-Match')).toBeNull()
    expect(calls[1]!.url.searchParams.get('since')).toBe('17,' + revisionId(held[1]!)) // exactly what the server said
    expect(calls[1]!.url.searchParams.get('sinceDelete')).toBe('2')
    if (read.notModified) throw new Error('expected a change')
    expect(read).toMatchObject({ revision: 7, deleteRevision: 2, ids: null, cursor: '18,' + revisionId(held[2]!) })
    expect(read.items.map((r) => r.writer)).toEqual(['user:aaaa', 'user:aaaa', 'share:6f7a8b9c-0d1e-4f3a-8b5c-6d7e8f9a0b1c'])
    expect(read.guestWriters.map((w) => w.identityId)).toEqual(['6f7a8b9c-0d1e-4f3a-8b5c-6d7e8f9a0b1c'])
    // What it read opens as what was written.
    const opened = applyPotChange([], read, { scopeId: SCOPE, nodeId: POT, potKey })
    expect(opened.map((r) => r.body?.kind)).toEqual(['participant', 'participant', 'expense'])
  })

  it('keeps a tombstone\'s purge time, which says until when it may be restored (uc.expenses.entry-history E2)', async () => {
    const held = heldPot()
    stubFetch(() =>
      json({ revisions: [{ ...wire(held[2]!), tombstone: true, writer: 'user:aaaa', purgeAfter: '2026-11-25T10:00:00Z' }, { ...wire(held[0]!), writer: 'user:aaaa', purgeAfter: null }],
        more: false, cursor: 'c', ledgerRevision: 1, deleteRevision: 0, revisionIds: null, guestWriters: [] }),
    )
    const read = await readMemberPot(SCOPE, POT)
    if (read.notModified) throw new Error('unexpected 304')
    expect(read.items[0]!.purgeAfter).toEqual(new Date('2026-11-25T10:00:00Z'))
    expect(read.items[1]!.purgeAfter).toBeUndefined()
  })

  it('a 304 is "nothing changed"', async () => {
    stubFetch(() => new Response(null, { status: 304 }))
    expect(await readMemberPot(SCOPE, POT, { ifNoneMatch: '7' })).toEqual({ notModified: true })
  })

  it('a re-key re-serves a revision with a new wrap: it replaces the held one, the digest stays; an erasure\'s id set drops the rest', () => {
    const held = heldPot()
    const newKey = v1.randomBytes(32)
    const dinnerRow = held[2]!
    const kRev = v1.open({ purpose: 'expense-entry-key', scopeId: SCOPE, nodeId: POT, entryId: DINNER, revision: 1 }, potKey, dinnerRow.wrappedRevisionKey)
    const rewrapped = { ...dinnerRow, wrappedRevisionKey: v1.seal({ purpose: 'expense-entry-key', scopeId: SCOPE, nodeId: POT, entryId: DINNER, revision: 1 }, newKey, kRev) }
    const after = applyPotChange(openServedRevisions(SCOPE, POT, newKey, held), { items: [rewrapped], ids: null }, { scopeId: SCOPE, nodeId: POT, potKey: newKey })
    expect(after).toHaveLength(3)
    expect(after.find((r) => r.entryId === DINNER)).toMatchObject({ digest: dinnerRow.digest, unreadable: null })
    const dropped = applyPotChange(after, { items: [], ids: [revisionId(held[0]!), revisionId(held[2]!)] }, { scopeId: SCOPE, nodeId: POT, potKey: newKey })
    expect(dropped.map(revisionId)).toEqual([revisionId(held[0]!), revisionId(held[2]!)])
  })
})

describe("a guest's read", () => {
  it('pages after the cursor, keeps the first page\'s quoted tag, and reads no writer', async () => {
    const held = heldPot()
    const calls = stubFetch((c) =>
      c.url.searchParams.get('after') === null
        ? json({ revisions: held.slice(0, 2).map(wire), more: true, cursor: revisionId(held[1]!).replace(':', ',') }, 200, { ETag: '"2a9b"' })
        : json({ revisions: [{ ...wire(held[2]!), writer: 'user:leak' }], more: false, cursor: null }),
    )
    const read = await readGuestPot(SHARE, POT, { pwToken: 'cHc=', ifNoneMatch: '"1111"' })
    expect(calls[0]!.url.pathname).toBe(`/api/shares/${SHARE}/pots/${POT}/revisions`)
    expect(calls[0]!.headers.get('If-None-Match')).toBe('"1111"')
    expect(calls[0]!.headers.get('X-Share-Password')).toBe('cHc=')
    expect(calls[1]!.url.searchParams.get('after')).toBe(`${held[1]!.entryId},${held[1]!.revision}`)
    expect(calls[1]!.headers.get('If-None-Match')).toBeNull()
    expect(read!.etag).toBe('"2a9b"')
    expect(read!.revisions).toHaveLength(3)
    // The guest's schema has no writer: even one a server slips in is dropped by the generated reader, never held.
    expect(read!.revisions.every((r) => r.writer === undefined)).toBe(true)
  })

  it('an unchanged listing (304 on its tag) is null', async () => {
    stubFetch(() => new Response(null, { status: 304 }))
    expect(await readGuestPot(SHARE, POT, { ifNoneMatch: '"2a9b"' })).toBeNull()
  })
})

describe('a write', () => {
  it('appends revision n + 1 sealed at its address, naming the heads it read, with the epoch it states', async () => {
    const held = heldPot()
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    await appendPotWrites(MEMBER, { key: potKey, epoch: 3 }, held, [{ entryId: DINNER, content: dinner(1200) }], BASE)
    const call = calls[0]!
    expect(call.method).toBe('POST')
    expect(call.url.pathname).toBe(`/api/scopes/${SCOPE}/pots/${POT}/revisions`)
    expect(call.json).toMatchObject({ nodeEpoch: 3, revisions: [{ entryId: DINNER, revision: 2, tombstone: false }] })
    const sent = (call.json!.revisions as Record<string, string>[])[0]!
    expect(Object.keys(sent).sort()).toEqual(['entryId', 'revision', 'sealedBody', 'tombstone', 'wrappedRevisionKey'])
    const body = openRevision({ scopeId: SCOPE, nodeId: POT, entryId: DINNER, revision: 2 }, potKey, { sealedBody: fromB64std(sent.sealedBody!), wrappedRevisionKey: fromB64std(sent.wrappedRevisionKey!) })
    expect(body).toMatchObject({ kind: 'expense', amount: 1200, heads: headsToName(held) })
    expect(JSON.stringify(call.json)).not.toContain('Dinner') // nothing in plaintext
  })

  it('a delete is a tombstone revision; a guest\'s goes to the share path with its tokens', async () => {
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    await appendPotWrites(GUEST, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content: { kind: 'deleted' } }], BASE)
    const call = calls[0]!
    expect(call.url.pathname).toBe(`/api/shares/${SHARE}/pots/${POT}/revisions`)
    expect(call.headers.get('X-Share-Password')).toBe('cHc=')
    expect(call.headers.get('Authorization')).toBeNull()
    expect(call.json).toMatchObject({ nodeEpoch: 0, contributorToken: 'Y29udHJpYnV0b3I=', revisions: [{ entryId: DINNER, revision: 2, tombstone: true }] })
  })

  it('a revision lists the receipts it holds, sealed, and names the ones it drops beside it; one it keeps and drops is refused', async () => {
    const KEPT = '0e000000-0000-4000-a000-000000000001'
    const DROPPED = '0e000000-0000-4000-a000-000000000002'
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    const content = { ...dinner(1200), receipts: [KEPT] } as PotWrite['content']
    await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content, droppedReceipts: [DROPPED] }], BASE)
    const sent = (calls[0]!.json!.revisions as Record<string, unknown>[])[0]!
    expect(sent.droppedReceipts).toEqual([DROPPED])
    const body = openRevision({ scopeId: SCOPE, nodeId: POT, entryId: DINNER, revision: 2 }, potKey, { sealedBody: fromB64std(sent.sealedBody as string), wrappedRevisionKey: fromB64std(sent.wrappedRevisionKey as string) })
    expect(body).toMatchObject({ receipts: [KEPT] })
    // a dropped receipt is never held again: a revision that keeps one it drops never leaves the client
    expect(() => sealPotWrites(MEMBER, potKey, heldPot(), [{ entryId: DINNER, content, droppedReceipts: [KEPT] }], BASE)).toThrow(new PotWriteRefused('receipt-dropped-and-kept'))
    // control: no drop, no field
    await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content }], BASE)
    expect(Object.keys((calls[1]!.json!.revisions as Record<string, unknown>[])[0]!)).not.toContain('droppedReceipts')
  })

  it('409 stale_revision names every stale record with its latest', async () => {
    stubFetch(() => json({ code: 'stale_revision', stale: [{ entryId: DINNER, revision: 2 }] }, 409))
    const e = await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content: dinner(1200) }], BASE).catch((x: unknown) => x)
    expect(e).toBeInstanceOf(StaleRevisionError)
    expect((e as StaleRevisionError).stale).toEqual([{ entryId: DINNER, revision: 2 }])
  })

  it('another 409 is not a stale revision', async () => {
    stubFetch(() => json({ code: 'stale_epoch' }, 409))
    const e = await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content: dinner(1200) }], BASE).catch((x: unknown) => x)
    expect(e).not.toBeInstanceOf(StaleRevisionError)
  })

  it('an entry whose sums do not add up never leaves the client (control: the balanced one is sent)', async () => {
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    const refused = await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content: dinner(1200, 1100) }], BASE).catch((x: unknown) => x)
    expect(refused).toBeInstanceOf(PotWriteRefused)
    expect((refused as PotWriteRefused).reason).toBe('payers-unbalanced')
    expect(calls).toHaveLength(0)
    await appendPotWrites(MEMBER, { key: potKey, epoch: 0 }, heldPot(), [{ entryId: DINNER, content: dinner(1200, 1200) }], BASE)
    expect(calls).toHaveLength(1)
  })

  it('refuses before sealing: two revisions of one record, a stranger, a moved join time, an unsettled deactivation', () => {
    const held = heldPot()
    const refuse = (writes: PotWrite[]) => {
      try {
        sealPotWrites(MEMBER, potKey, held, writes, BASE)
        return 'sent'
      } catch (e) {
        return e instanceof PotWriteRefused ? e.reason : 'other'
      }
    }
    expect(refuse([{ entryId: DINNER, content: dinner(1) }, { entryId: DINNER, content: dinner(2) }])).toBe('record-twice')
    expect(refuse([{ entryId: DINNER, content: { ...dinner(10), allocation: { items: [], rest: { mode: 'equal', participants: ['0e000000-0000-4000-8000-0000000000ff'] } } } as PotWrite['content'] }])).toBe('unknown-participant')
    expect(refuse([{ entryId: BEN, content: person('Ben', 99) }])).toBe('joined-moved')
    expect(refuse([{ entryId: BEN, content: { kind: 'participant', name: 'Ben', active: false, joined: 2 } }])).toBe('not-settled') // Ben owes 5.00
    expect(refuse([])).toBe('empty')
    // Controls: a rename, a new person and an entry for them in one batch, a settled deactivation.
    expect(refuse([{ entryId: BEN, content: person('Benjamin', 2) }])).toBe('sent')
    const CY = '0e000000-0000-4000-8000-000000000003'
    expect(refuse([{ entryId: CY, content: person('Cy', 3) }, { entryId: '0e000000-0000-4000-9000-000000000002', content: { ...dinner(10), allocation: { items: [], rest: { mode: 'equal', participants: [CY] } } } as PotWrite['content'] }])).toBe('sent')
    const noMoney = heldPot(false)
    expect(sealPotWrites(MEMBER, potKey, noMoney, [{ entryId: BEN, content: { kind: 'participant', name: 'Ben', active: false, joined: 2 } }], BASE)).toHaveLength(1)
  })
})

describe("a member's erasure", () => {
  it('is refused while the participant is not settled, and nothing is sent', async () => {
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    const e = await eraseParticipant(MEMBER as Extract<PotTarget, { kind: 'member' }>, { key: potKey, epoch: 0 }, heldPot(), BEN, BASE).catch((x: unknown) => x)
    expect((e as PotWriteRefused).reason).toBe('not-settled')
    expect(calls).toHaveLength(0)
  })

  it('posts the sealed erasure revision n + 1, keeping the join order and no name', async () => {
    const calls = stubFetch(() => new Response(null, { status: 204 }))
    const held = heldPot(false)
    await eraseParticipant(MEMBER as Extract<PotTarget, { kind: 'member' }>, { key: potKey, epoch: 1 }, held, BEN, BASE)
    const call = calls[0]!
    expect(call.url.pathname).toBe(`/api/scopes/${SCOPE}/pots/${POT}/erasures`)
    expect(call.json).toMatchObject({ nodeEpoch: 1, entryId: BEN, revision: 2 })
    const body = openRevision({ scopeId: SCOPE, nodeId: POT, entryId: BEN, revision: 2 }, potKey, {
      sealedBody: fromB64std(call.json!.sealedBody as string), wrappedRevisionKey: fromB64std(call.json!.wrappedRevisionKey as string),
    })
    expect(body).toEqual({ v: 1, heads: headsToName(held), kind: 'erasure', joined: 2 })
  })

  it('an erasure never goes through the append', () => {
    expect(() => sealPotWrites(MEMBER, potKey, heldPot(), [{ entryId: BEN, content: { kind: 'erasure', joined: 2 } as unknown as PotWrite['content'] }], BASE)).toThrow(PotWriteRefused)
  })
})
