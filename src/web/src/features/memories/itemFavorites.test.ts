import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { ItemFavoritesApi, ItemFavoritesEntry, OwnItemFavoritesApi } from '../../api/generated/item-favorites'
import { fakeApi } from '../../test/apiMock'

// The seal/open path of item marks (ADR 0068 §9) against the REAL crypto: only the wire and the vault are faked.
// Every blob below is produced by this module's own `saveItemFavorites`, run as the member it belongs to (their
// Master Key in the vault) and captured from the PUT — so a fixture is a genuine blob of whatever format the tree
// under test writes, never a hand-built one that could drift from it.
const api = {
  get: vi.fn<ItemFavoritesApi['getItemFavorites']>(),
  put: vi.fn<ItemFavoritesApi['putItemFavorites']>(),
  listOwn: vi.fn<OwnItemFavoritesApi['listOwnItemFavorites']>(),
}
const vault = vi.hoisted(() => ({ mk: null as Uint8Array | null }))
vi.mock('./api', () => ({ itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: () => (vault.mk ? vault.mk.slice() : null) }))
// The rider opens each scope's E_0 through the caller's own grant; here every scope opens to a COPY of K_SC (the
// rider zeroes what it opened), and a scope listed in `unopenable` does not open at all.
const scopes = vi.hoisted(() => ({ unopenable: new Set<string>(), key: null as Uint8Array | null }))
vi.mock('../spaces/scopes', () => ({ openScopeById: vi.fn() }))

import { loadItemFavorites, saveItemFavorites, retagItemMarksUnderNewMasterKey, ItemMarksTooMany, ITEM_MARKS_CIPHERTEXT_MAX } from './itemFavorites'
import { readFileSync } from 'node:fs'
import { itemFavoritesStore, loadItemFavoritesFor, toggleItemFavorite, clearItemFavorites, markCount } from './itemFavoritesStore'
import { itemFavoritesApi, ownItemFavoritesApi } from './api'
import { openScopeById } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'

const SCOPE = 'c0ffee00-0000-4000-8000-0000000000a1'
const NODE = 'c0ffee00-0000-4000-8000-0000000000b1'
const K_SC = v1.randomBytes(32)
const MK_ME = v1.randomBytes(32)
const MK_BOB = v1.randomBytes(32)
const media = (n: number) => `c0ffee00-0000-4000-8000-${n.toString().padStart(12, '0')}`
const MINE = [media(1)]
const BOBS = [media(2), media(3)]
const CLICKED = media(9)

/** Seal `ids` as the member whose Master Key is `mk`, through the module's own write, and return the PUT's blob. */
async function blobAs(mk: Uint8Array, ids: string[], nodeId = NODE): Promise<string> {
  vault.mk = mk
  api.put.mockClear()
  await saveItemFavorites(SCOPE, nodeId, K_SC, new Set(ids))
  return api.put.mock.calls[0][0].putItemFavoritesRequest.ciphertext
}

/** The plaintext inside a written blob. */
const plaintextOf = (ciphertext: string, nodeId = NODE) =>
  v1.open({ purpose: 'item-favorites', scopeId: SCOPE, nodeId }, K_SC, fromB64std(ciphertext))

/** The ids a written blob holds. */
function idsIn(ciphertext: string, nodeId = NODE): string[] {
  return v1.decodeItemMarks(plaintextOf(ciphertext, nodeId)).ids
}

/** Serve `entries` as the node's read. Typed as the generated response, so a schema change reaches this file. */
function serve(entries: ItemFavoritesEntry[]): void {
  api.get.mockResolvedValue(entries)
}

const ownSets = (marks: { own: boolean; ids: Set<string> }[]) => marks.filter((m) => m.own).map((m) => [...m.ids].sort())

beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(itemFavoritesApi, { getItemFavorites: api.get, putItemFavorites: api.put })
  fakeApi(ownItemFavoritesApi, { listOwnItemFavorites: api.listOwn })
  api.put.mockResolvedValue(undefined)
  clearItemFavorites()
  vault.mk = null
  scopes.unopenable.clear()
  scopes.key = K_SC
  vi.mocked(openScopeById).mockImplementation(async (scopeId) => {
    if (scopes.unopenable.has(scopeId)) return null
    const key = scopes.key!.slice()
    return {
      scopeId, tenantId: 'tenant-1', kind: ScopeKind.Group, key, epochs: { current: 0, keys: new Map([[0, key]]) },
      nameEpoch: 0, deletedAt: null, purgeAfter: null,
    }
  })
})

describe('which item-mark entry is mine (ADR 0068 §9)', () => {
  /**
   * **B1 — the relabel (live defect on the untouched tree).** The server flags bob's genuinely sealed set `self` and
   * the caller's own `false`. Neither may read as mine: bob's because it is not, the caller's because the server
   * did not claim it (Decision 1 — both halves are required). The count is untouched either way.
   * The honest labelling of the very same blobs is the positive control: there, the caller's set IS mine.
   */
  it('B1: a relabelled entry is not mine, and the caller’s unflagged entry is not either', async () => {
    const bobs = await blobAs(MK_BOB, BOBS)
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME

    serve([{ self: false, ciphertext: bobs }, { self: true, ciphertext: mine }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([MINE])

    serve([{ self: true, ciphertext: bobs }, { self: false, ciphertext: mine }])
    const marks = await loadItemFavorites(SCOPE, NODE, K_SC)
    expect(ownSets(marks)).toEqual([])
    expect(marks.map((m) => [...m.ids].sort())).toEqual([BOBS, MINE]) // still both members' sets — the count holds
  })

  /**
   * **B2 — the laundering (live defect on the untouched tree).** After that relabelled load, one click must write
   * only the clicked id into the caller's row — never bob's set plus it, which would erase the caller's real marks
   * and add bob's to every count, persistently, through the caller's own client.
   */
  it('B2: a toggle after a relabelled load writes only the clicked id, never the other member’s set', async () => {
    const bobs = await blobAs(MK_BOB, BOBS)
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME
    serve([{ self: true, ciphertext: bobs }, { self: false, ciphertext: mine }])
    await loadItemFavoritesFor(SCOPE, NODE, K_SC)
    api.put.mockClear()

    await toggleItemFavorite(CLICKED, SCOPE, NODE, K_SC)

    expect(api.put).toHaveBeenCalledTimes(1)
    expect(idsIn(api.put.mock.calls[0][0].putItemFavoritesRequest.ciphertext)).toEqual([CLICKED])
    expect(markCount(BOBS[0])).toBe(1)
  })

  /**
   * **B3 — the tag binds the content.** Every co-member opens the caller's blob, so they can copy its tag. Pasted
   * onto a set of their choosing and flagged `self` by a colluding server, it must not verify: a copied tag answers
   * for the set it was made over and no other. The caller's own honest entry is the positive control.
   */
  it('B3: a co-member’s set carrying a copy of the caller’s tag is not mine; the caller’s honest one is', async () => {
    const mine = await blobAs(MK_ME, MINE)
    const copiedTag = tagIn(mine)
    const forged = sealAsCoMember(v1.encodeItemMarks(copiedTag, BOBS))
    vault.mk = MK_ME

    serve([{ self: true, ciphertext: mine }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([MINE])

    serve([{ self: true, ciphertext: forged }])
    const marks = await loadItemFavorites(SCOPE, NODE, K_SC)
    expect(ownSets(marks)).toEqual([])
    expect(marks.map((m) => [...m.ids].sort())).toEqual([BOBS]) // it still counts — as someone else's
  })

  /**
   * **B4 — nobody without the caller's Master Key computes the caller's tag.** A co-member holds the scope key, the
   * scope and node ids, the ids themselves and — by elimination in a small scope — every member's user id. Every key
   * those allow, used both through the self-tag derivation and as a raw HMAC key, must miss the caller's real tag.
   */
  it('B4: no key a co-member holds reproduces the caller’s tag', async () => {
    const mine = await blobAs(MK_ME, MINE)
    const real = Buffer.from(tagIn(mine)).toString('hex')
    const mark = { scopeId: SCOPE, nodeId: NODE, ids: MINE }
    const known = [K_SC, v1.utf8(SCOPE), v1.utf8(NODE), v1.utf8(MINE[0]), ...MEMBER_IDS.map((id) => v1.utf8(id))]
    const candidates = known.flatMap((k) => [
      v1.itemMarkSelfTag(v1.itemMarkSelfKey(k), mark),
      v1.itemMarkSelfTag(k, mark),
      v1.itemMarkSelfTag(v1.hkdfSha256(K_SC, new Uint8Array(0), k, 32), mark),
    ])
    expect(candidates.length).toBe(known.length * 3)
    for (const c of candidates) expect(Buffer.from(c).toString('hex')).not.toBe(real)
    // ...while the holder of the Master Key does reproduce it (the derivation the test claims is the only way in).
    expect(Buffer.from(v1.itemMarkSelfTag(v1.itemMarkSelfKey(MK_ME), mark)).toString('hex')).toBe(real)
  })
})

describe('everything not provably mine counts as someone else’s (ADR 0068 §9) — a degradation, never a refusal', () => {
  // inbox #500 dropped the JSON plaintext with no read path: a blob in the old `{tag, ids}` form, or one whose ids are
  // cut short, opens but does not decode — an empty set nobody owns, while the entries beside it still load.
  it('an own-flagged entry that is not tag ‖ ids — the old JSON form, an id cut short — is an empty set nobody owns', async () => {
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME
    const tag = tagIn(mine)
    const oldForm = sealAsCoMember(new TextEncoder().encode(JSON.stringify({ tag: Buffer.from(tag).toString('base64'), ids: MINE })))
    const cut = sealAsCoMember(v1.encodeItemMarks(tag, MINE).slice(0, -1))
    for (const ciphertext of [oldForm, cut]) {
      serve([{ self: true, ciphertext }, { self: false, ciphertext: await blobAs(MK_BOB, BOBS) }])
      vault.mk = MK_ME
      const marks = await loadItemFavorites(SCOPE, NODE, K_SC)
      expect(marks.map((m) => [m.own, [...m.ids].sort()])).toEqual([[false, []], [false, BOBS]])
    }
    serve([{ self: true, ciphertext: mine }]) // the control: the same tag and ids in the current form are mine
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([MINE])
  })

  it('a verifying entry the server did NOT flag is not mine — the flag is the other half', async () => {
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME
    serve([{ self: false, ciphertext: mine }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([])
  })

  it('of several entries that are flagged and verify, only the first is mine; the rest count as others', async () => {
    const older = await blobAs(MK_ME, [media(5)])
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME
    serve([{ self: true, ciphertext: mine }, { self: true, ciphertext: older }])
    const marks = await loadItemFavorites(SCOPE, NODE, K_SC)
    expect(marks.map((m) => m.own)).toEqual([true, false])
    expect(ownSets(marks)).toEqual([MINE])
  })

  it('an entry that will not open is an empty set, and the rest still load', async () => {
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_ME
    serve([{ self: false, ciphertext: 'AAAA' }, { self: true, ciphertext: mine }])
    const marks = await loadItemFavorites(SCOPE, NODE, K_SC)
    expect(marks.map((m) => [m.own, m.ids.size])).toEqual([[false, 0], [true, 1]])
  })

  it('with the vault locked nothing is mine, and a toggle writes nothing', async () => {
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = null
    serve([{ self: true, ciphertext: mine }])
    await loadItemFavoritesFor(SCOPE, NODE, K_SC)
    expect(ownSets(itemFavoritesStore.marks)).toEqual([])
    api.put.mockClear()

    await expect(toggleItemFavorite(CLICKED, SCOPE, NODE, K_SC)).rejects.toThrow(/vault is locked/)

    expect(api.put).not.toHaveBeenCalled()
    expect(ownSets(itemFavoritesStore.marks)).toEqual([]) // the optimistic own set was reverted
  })

  it('the written plaintext is tag ‖ ids and its tag verifies for the caller only', async () => {
    const mine = await blobAs(MK_ME, MINE)
    const tag = tagIn(mine)
    const mark = { scopeId: SCOPE, nodeId: NODE, ids: MINE }
    expect(v1.itemMarkSelfTagVerifies(v1.itemMarkSelfKey(MK_ME), mark, tag)).toBe(true)
    expect(v1.itemMarkSelfTagVerifies(v1.itemMarkSelfKey(MK_BOB), mark, tag)).toBe(false)
  })
})

// inbox #500: one member's marks in one Memory are one blob, so its size is the limit. 16 bytes a mark lift it from 1,257
// (a JSON uuid) to 12,284 — every photo of a 10,000-photo Memory — under the contract's 262,144 characters.
describe('how many marks one Memory holds (inbox #500)', () => {
  /** `n` distinct canonical media ids, in no particular order. */
  const manyIds = (n: number) => Array.from({ length: n }, () => crypto.randomUUID())

  it('the cap the client enforces is the contract\'s, on every item-mark ciphertext', () => {
    const spec = readFileSync(new URL('../../../../../contracts/api/memories/item-favorites.openapi.yaml', import.meta.url), 'utf8')
    const caps = [...spec.matchAll(/maxLength: (\d+)/g)].map((m) => Number(m[1]))
    expect(caps).toEqual([ITEM_MARKS_CIPHERTEXT_MAX, ITEM_MARKS_CIPHERTEXT_MAX, ITEM_MARKS_CIPHERTEXT_MAX])
    expect(spec).toContain(`Size(max = ${ITEM_MARKS_CIPHERTEXT_MAX})`)
  })

  it.each([1_258, 12_000])('%i marks round-trip: written, served, and read back as mine', async (n) => {
    const ids = manyIds(n)
    const mine = await blobAs(MK_ME, ids)
    expect(mine.length).toBeLessThanOrEqual(ITEM_MARKS_CIPHERTEXT_MAX)
    vault.mk = MK_ME
    serve([{ self: true, ciphertext: mine }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([[...ids].sort()])
  })

  // The last set that fits and the first that does not: the refusal is the client's, before any write, so the server
  // never sees the attempt and the gallery can say the set is full (MemoryDetail's message).
  it('12,284 marks fit; the 12,285th is refused before any write', async () => {
    const ids = manyIds(12_284)
    vault.mk = MK_ME
    await saveItemFavorites(SCOPE, NODE, K_SC, new Set(ids))
    expect(api.put).toHaveBeenCalledTimes(1)
    // 29 + 32 + 16 × 12,284 = 196,605 bytes, 262,140 base64 characters; one more mark is 262,164, past the cap
    expect(api.put.mock.calls[0][0].putItemFavoritesRequest.ciphertext).toHaveLength(262_140)
    api.put.mockClear()
    await expect(saveItemFavorites(SCOPE, NODE, K_SC, new Set([...ids, crypto.randomUUID()]))).rejects.toBeInstanceOf(ItemMarksTooMany)
    expect(api.put).not.toHaveBeenCalled()
  })

  it('a toggle past the cap reverts the heart and hands the refusal to the gallery', async () => {
    const ids = manyIds(12_284)
    vault.mk = MK_ME
    serve([{ self: true, ciphertext: await blobAs(MK_ME, ids) }])
    await loadItemFavoritesFor(SCOPE, NODE, K_SC)
    api.put.mockClear()
    await expect(toggleItemFavorite(CLICKED, SCOPE, NODE, K_SC)).rejects.toBeInstanceOf(ItemMarksTooMany)
    expect(api.put).not.toHaveBeenCalled()
    expect(ownSets(itemFavoritesStore.marks)).toEqual([[...ids].sort()]) // the set as it was
  })

  // The ids travel sealed and binary: the blob is 29 bytes of seal around 32 + 16 bytes a mark, and neither an id's
  // 16 bytes nor its text appear anywhere in what the server stores.
  it('each mark is 16 bytes inside the seal, and no id appears in the blob in any form', async () => {
    const ids = manyIds(50)
    const blob = fromB64std(await blobAs(MK_ME, ids))
    expect(blob.length).toBe(29 + 32 + 16 * ids.length)
    expect(plaintextOf(Buffer.from(blob).toString('base64')).length).toBe(32 + 16 * ids.length)
    const stored = Buffer.from(blob)
    for (const id of ids) {
      expect(stored.indexOf(Buffer.from(id.replaceAll('-', ''), 'hex'))).toBe(-1)
      expect(stored.indexOf(Buffer.from(id, 'utf8'))).toBe(-1)
    }
  })
})

describe('a Master-Key rotation re-tags the caller’s own marks (ADR 0068 §9)', () => {
  const MK_NEW = v1.randomBytes(32)
  const NODE2 = 'c0ffee00-0000-4000-8000-0000000000b2'
  const putsByNode = () => new Map(api.put.mock.calls.map((c) => [c[0].nodeId, c[0].putItemFavoritesRequest.ciphertext]))

  /**
   * **B6.** After the rider, a set tagged under the OLD Master Key reads as mine under the NEW one — and before it,
   * under the new key, it does not (the control that says it is the rider, not the load, that made it mine).
   */
  it('B6: an entry tagged under the old Master Key verifies as mine under the new one after the re-tag', async () => {
    const mine = await blobAs(MK_ME, MINE)
    vault.mk = MK_NEW
    serve([{ self: true, ciphertext: mine }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([])

    api.listOwn.mockResolvedValue([{ scopeId: SCOPE, nodeId: NODE, ciphertext: mine }])
    api.put.mockClear()
    await retagItemMarksUnderNewMasterKey(MK_ME, MK_NEW)

    expect(api.put).toHaveBeenCalledTimes(1)
    const retagged = putsByNode().get(NODE)!
    expect(idsIn(retagged)).toEqual(MINE)
    serve([{ self: true, ciphertext: retagged }])
    expect(ownSets(await loadItemFavorites(SCOPE, NODE, K_SC))).toEqual([MINE])
  })

  /**
   * **B7.** The listing is the server's, a hint and not a proof: a co-member's blob slipped into it must NOT be
   * re-tagged as the caller's — only a row whose tag verifies under the OLD key is. The caller's genuine row in the
   * same listing is the positive control.
   */
  it('B7: a listed row whose tag fails the old key is not re-tagged; the caller’s genuine one is', async () => {
    const bobs = await blobAs(MK_BOB, BOBS)
    const mine = await blobAs(MK_ME, MINE, NODE2)
    api.listOwn.mockResolvedValue([
      { scopeId: SCOPE, nodeId: NODE, ciphertext: bobs },
      { scopeId: SCOPE, nodeId: NODE2, ciphertext: mine },
    ])
    api.put.mockClear()

    await retagItemMarksUnderNewMasterKey(MK_ME, MK_NEW)

    expect([...putsByNode().keys()]).toEqual([NODE2])
  })

  it('is best-effort per Memory: an unopenable scope and a failed write leave the rest re-tagged', async () => {
    const OTHER_SCOPE = 'c0ffee00-0000-4000-8000-0000000000a2'
    const first = await blobAs(MK_ME, MINE)
    const second = await blobAs(MK_ME, BOBS, NODE2)
    api.listOwn.mockResolvedValue([
      { scopeId: OTHER_SCOPE, nodeId: NODE, ciphertext: first },
      { scopeId: SCOPE, nodeId: NODE, ciphertext: first },
      { scopeId: SCOPE, nodeId: NODE2, ciphertext: second },
    ])
    scopes.unopenable.add(OTHER_SCOPE)
    api.put.mockClear()
    api.put.mockRejectedValueOnce(new Error('offline'))

    await expect(retagItemMarksUnderNewMasterKey(MK_ME, MK_NEW)).resolves.toBeUndefined()

    expect(api.put.mock.calls.map((c) => [c[0].scopeId, c[0].nodeId])).toEqual([[SCOPE, NODE], [SCOPE, NODE2]])
    expect(idsIn(putsByNode().get(NODE2)!, NODE2)).toEqual([...BOBS].sort())
  })
})

/** Every member's account id — what a co-member of a small scope can know by elimination. */
const MEMBER_IDS = ['b0b00000-0000-4000-8000-000000000001', 'a11ce000-0000-4000-8000-000000000002']

/** The self-tag inside a written blob. */
function tagIn(ciphertext: string): Uint8Array {
  return v1.decodeItemMarks(plaintextOf(ciphertext)).tag
}

/** A plaintext of the co-member's choosing, sealed as any member can: under `K_SC`, bound to this node. */
function sealAsCoMember(plaintext: Uint8Array): string {
  return Buffer.from(v1.seal({ purpose: 'item-favorites', scopeId: SCOPE, nodeId: NODE }, K_SC, plaintext)).toString('base64')
}
