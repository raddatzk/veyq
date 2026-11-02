// The platform's share mint (uc.sharing.share-public-viewer:create-public-share), driven without any application: a
// READ / READ_WRITE share of a node carries the wrap of its key and the mandatory recovery copy, and a WRITE_ONLY role
// is reachable only with the drop half an application hands in. The Memories suite (`features/memories/share.test.ts`)
// drives the same mint through its `createShare` / `reissueShare`, the drop key included.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ sharesApi: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import {
  mintShare, reactivateShare, readShareNote, recoverShareKey, remintShare, resealShareNames, updateShare, NO_NOTE, type ReissuableShare,
} from './manageShares'
import { SHARE_KEY } from './shareTree'
import { SHARE_MEMORY_NAME, SHARE_NOTE } from './resolvePublicShare'
import { sharesApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { ShareRole, type ShareSummary, type SharingApi } from '../../api/generated/sharing'
import type { ScopeKey } from '../../features/spaces/epochs'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
// A rotated scope at epoch 1: its ONE key (ADR 0115) — every recovery copy is sealed and opened under it at epoch 1.
const EPOCHS: ScopeKey = { epoch: 1, key: v1.randomBytes(32) }

beforeEach(() => vi.clearAllMocks())

describe('mintShare', () => {
  it('wraps the node key under K_share and seals a recovery copy every member can open, for any kind', async () => {
    const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create })
    const nodeKey = v1.randomBytes(32)

    const minted = await mintShare(SCOPE_ID, NODE_ID, { key: nodeKey, epoch: 1 }, EPOCHS, 'Taxes', ShareRole.ReadWrite)

    const req = create.mock.calls[0][0].createShareRequest
    expect(req).toMatchObject({ shareId: minted.shareId, nodeId: NODE_ID, role: ShareRole.ReadWrite, sealedShareKeyEpoch: 1 })
    expect(v1.open(SHARE_KEY, minted.kShare, fromB64std(req.wrap!))).toEqual(nodeKey)
    const address = { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: minted.shareId }
    expect(recoverShareKey(EPOCHS, address, req)).toEqual(minted.kShare)
    // the copy opens under the scope's ONE key at its epoch: not under a key a rotation replaced (ADR 0115)
    expect(() => recoverShareKey({ epoch: 1, key: v1.randomBytes(32) }, address, req)).toThrow(v1.OpenRefused)
    expect(req.sealedDropPublicKey).toBeUndefined()
    expect(JSON.stringify(req)).not.toContain('Taxes')
  })

  it('refuses a WRITE_ONLY role without the drop half, and a drop half on any other role, before posting', async () => {
    const create = vi.fn<SharingApi['createShare']>()
    fakeApi(sharesApi, { createShare: create })
    const drop = { dropFields: async () => ({ sealedDropPublicKey: 'AA==' }) }

    await expect(mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 1 }, EPOCHS, 'x', ShareRole.WriteOnly))
      .rejects.toThrow('share_write_only_mint_mismatch')
    await expect(mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 1 }, EPOCHS, 'x', ShareRole.Read, null, NO_NOTE, '', '', drop))
      .rejects.toThrow('share_write_only_mint_mismatch')
    expect(create).not.toHaveBeenCalled()
  })
})

describe("the note's acceptance switch (uc.sharing.share-note)", () => {
  const minted = async (note: Parameters<typeof mintShare>[7]) => {
    const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create })
    const m = await mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 1 }, EPOCHS, 'Taxes', ShareRole.Read, null, note)
    const req = create.mock.calls[0][0].createShareRequest
    return { ...m, req, address: { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: m.shareId } }
  }
  const plaintextOf = (kShare: Uint8Array, sealed: string) => new TextDecoder().decode(v1.openField(SHARE_NOTE, kShare, fromB64std(sealed)))

  it('is sealed inside the note and read back on edit; the request carries nothing else of it', async () => {
    const note = { texts: { en: 'Read me' }, mustAccept: true }
    const { kShare, req, address } = await minted(note)

    expect(plaintextOf(kShare, req.sealedNote!)).toBe(JSON.stringify({ texts: { en: 'Read me' }, mustAccept: true }))
    expect(readShareNote(EPOCHS, address, req, req.sealedNote!)).toEqual(note)
    expect(JSON.stringify(req)).not.toMatch(/mustAccept|Read me/)
  })

  it("off, the note is sealed as today's flat map; with no text nothing is sealed, switch or not", async () => {
    const off = await minted({ texts: { en: 'Hello' }, mustAccept: false })
    expect(plaintextOf(off.kShare, off.req.sealedNote!)).toBe(JSON.stringify({ en: 'Hello' }))
    expect(readShareNote(EPOCHS, off.address, off.req, off.req.sealedNote!)).toEqual({ texts: { en: 'Hello' }, mustAccept: false })

    const empty = await minted({ texts: { en: ' ' }, mustAccept: true })
    expect(empty.req.sealedNote).toBeUndefined()
  })

  it('an edit re-seals the switch with the text; a renew carries it', async () => {
    const { req, address } = await minted({ texts: { en: 'v1' }, mustAccept: false })
    const update = vi.fn<SharingApi['updateShare']>().mockResolvedValue(undefined)
    fakeApi(sharesApi, { updateShare: update })
    await updateShare(address, EPOCHS, { ...req, enabled: true, expiresAt: null }, { texts: { en: 'v2' }, mustAccept: true }, '', null)
    const sealedNote = update.mock.calls[0][0].updateShareRequest.sealedNote!
    expect(readShareNote(EPOCHS, address, req, sealedNote)).toEqual({ texts: { en: 'v2' }, mustAccept: true })

    const reissue = vi.fn<SharingApi['reissueShare']>(async ({ reissueShareRequest }) => ({ shareId: reissueShareRequest.shareId }))
    fakeApi(sharesApi, { reissueShare: reissue })
    const old: ReissuableShare = {
      ...address, role: ShareRole.Read, expiresAt: null, sealedNote, sealedShareName: null, passwordRequired: false, sealedPassword: null,
      sealedShareKey: req.sealedShareKey!, sealedShareKeyEpoch: req.sealedShareKeyEpoch!,
    }
    const renewed = await remintShare(old, { key: v1.randomBytes(32), epoch: 1 }, EPOCHS, 'Taxes')
    const r = reissue.mock.calls[0][0].reissueShareRequest
    const renewedAt = { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: renewed.shareId }
    expect(readShareNote(EPOCHS, renewedAt, r, r.sealedNote!)).toEqual({ texts: { en: 'v2' }, mustAccept: true })
  })
})

describe('remintShare', () => {
  it('re-issues a READ share with a fresh K_share and a recovery copy at the current epoch; WRITE_ONLY needs the drop half', async () => {
    const reissue = vi.fn<SharingApi['reissueShare']>(async ({ reissueShareRequest }) => ({ shareId: reissueShareRequest.shareId }))
    fakeApi(sharesApi, { reissueShare: reissue })
    const nodeKey = v1.randomBytes(32)
    const old: ReissuableShare = {
      scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: 'S'.repeat(43), role: ShareRole.Read, expiresAt: null,
      sealedNote: null, sealedShareName: null, passwordRequired: false, sealedPassword: null,
      sealedShareKey: null, sealedShareKeyEpoch: null,
    }
    const settings = { note: NO_NOTE, name: '', password: '', expiresAt: null }

    const minted = await remintShare(old, { key: nodeKey, epoch: 1 }, EPOCHS, 'Taxes', settings)

    const req = reissue.mock.calls[0][0].reissueShareRequest
    expect(req.shareId).toBe(minted.shareId)
    expect(v1.open(SHARE_KEY, minted.kShare, fromB64std(req.wrap!))).toEqual(nodeKey)
    expect(recoverShareKey(EPOCHS, { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: minted.shareId }, req)).toEqual(minted.kShare)
    await expect(remintShare({ ...old, role: ShareRole.WriteOnly }, { key: nodeKey, epoch: 1 }, EPOCHS, 'Taxes', settings))
      .rejects.toThrow('share_write_only_mint_mismatch')
  })
})

describe('resealShareNames (ADR 0080 decision 14)', () => {
  /** A share as the manage list serves it, minted through the real `mintShare` so its recovery copy is the real one.
   *  Each is a second older than the one before, so the list (newest first) keeps the order they were minted in. */
  let age = 0
  async function mintedRow(mine: boolean, oldName = 'Taxes'): Promise<{ row: ShareSummary; kShare: Uint8Array }> {
    const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create })
    const minted = await mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 1 }, EPOCHS, oldName, ShareRole.Read)
    const req = create.mock.calls[0][0].createShareRequest
    const row: ShareSummary = {
      shareId: minted.shareId, nodeId: NODE_ID, scopeId: SCOPE_ID, role: ShareRole.Read, sealedName: req.sealedName,
      sealedShareKey: req.sealedShareKey, sealedShareKeyEpoch: req.sealedShareKeyEpoch, enabled: true, mine,
      reissueRequired: false, deletable: mine, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
      createdAt: new Date(Date.UTC(2026, 9, 1) - 1000 * age++),
    }
    return { row, kShare: minted.kShare }
  }
  const opened = (kShare: Uint8Array, sealed: string) => new TextDecoder().decode(v1.openField(SHARE_MEMORY_NAME, kShare, fromB64std(sealed)))

  it('re-seals the new name under EVERY share\'s own K_share — the caller\'s and another member\'s', async () => {
    const own = await mintedRow(true)
    const theirs = await mintedRow(false)
    const set = vi.fn<SharingApi['setShareSealedName']>(async () => undefined)
    fakeApi(sharesApi, { listShares: async () => [own.row, theirs.row], setShareSealedName: set })

    const report = await resealShareNames(NODE_ID, 'Steuern 2026', EPOCHS)

    expect(report).toEqual({ resealed: [own.row.shareId, theirs.row.shareId], closed: [], skipped: [], failed: [] })
    expect(set).toHaveBeenCalledTimes(2)
    const sealedFor = (shareId: string) => set.mock.calls.find(([r]) => r.shareId === shareId)![0].setShareSealedNameRequest.sealedName
    for (const { row, kShare } of [own, theirs]) {
      expect(opened(kShare, sealedFor(row.shareId))).toBe('Steuern 2026')
    }
    expect(JSON.stringify(set.mock.calls)).not.toContain('Steuern')
    // each name opens under its own share's key only
    expect(() => opened(own.kShare, sealedFor(theirs.row.shareId))).toThrow(v1.OpenRefused)
  })

  it('skips and reports a share without a recovery copy (a tombstone or a legacy row), and still re-seals the rest', async () => {
    const legacy = await mintedRow(false)
    const ok = await mintedRow(true)
    const set = vi.fn<SharingApi['setShareSealedName']>(async () => undefined)
    fakeApi(sharesApi, {
      listShares: async () => [{ ...legacy.row, sealedShareKey: null, sealedShareKeyEpoch: null }, ok.row],
      setShareSealedName: set,
    })

    const report = await resealShareNames(NODE_ID, 'New', EPOCHS)

    expect(report).toEqual({ resealed: [ok.row.shareId], closed: [], skipped: [legacy.row.shareId], failed: [] })
    expect(set.mock.calls.map(([r]) => r.shareId)).toEqual([ok.row.shareId])
  })

  it('collects a failing share and goes on with the next — nothing is rolled back', async () => {
    const refused = await mintedRow(true)
    const broken = await mintedRow(false)
    const ok = await mintedRow(false)
    const set = vi.fn<SharingApi['setShareSealedName']>(async ({ shareId }) => {
      if (shareId === refused.row.shareId) throw new Error('404')
    })
    fakeApi(sharesApi, {
      // `broken`'s copy is relabelled to another epoch: it opens under no key
      listShares: async () => [refused.row, { ...broken.row, sealedShareKeyEpoch: 0 }, ok.row],
      setShareSealedName: set,
    })

    const report = await resealShareNames(NODE_ID, 'New', EPOCHS)

    expect(report).toEqual({ resealed: [ok.row.shareId], closed: [], skipped: [], failed: [refused.row.shareId, broken.row.shareId] })
    expect(set.mock.calls.map(([r]) => r.shareId)).toEqual([refused.row.shareId, ok.row.shareId])
  })

  // A link that closed between the list and its write: the server refuses the re-seal (`422 link_closed`, ADR 0114
  // decision 5) — a closed link, not a failure, so the rename shows no error for it.
  it('counts a link the server answers `link_closed` as closed, not failed — the sibling beside it still lands', async () => {
    const closing = await mintedRow(false)
    const ok = await mintedRow(false)
    const set = vi.fn<SharingApi['setShareSealedName']>(async ({ shareId }) => {
      if (shareId === closing.row.shareId) throw httpError(422, 'link_closed')
    })
    fakeApi(sharesApi, { listShares: async () => [closing.row, ok.row], setShareSealedName: set })

    const report = await resealShareNames(NODE_ID, 'New', EPOCHS)

    expect(report).toEqual({ resealed: [ok.row.shareId], closed: [closing.row.shareId], skipped: [], failed: [] })
    // control: another 422 stays a failure
    set.mockImplementation(async () => { throw httpError(422, 'capability_disabled') })
    expect((await resealShareNames(NODE_ID, 'New', EPOCHS)).failed).toEqual([closing.row.shareId, ok.row.shareId])
  })

  it('writes nothing for a node without shares', async () => {
    const set = vi.fn<SharingApi['setShareSealedName']>()
    fakeApi(sharesApi, { listShares: async () => [], setShareSealedName: set })
    expect(await resealShareNames(NODE_ID, 'New', EPOCHS)).toEqual({ resealed: [], closed: [], skipped: [], failed: [] })
    expect(set).not.toHaveBeenCalled()
  })

  // ADR 0114 decision 5: a re-key seals nothing new under a closed link, and neither does a rename — the reopen carries
  // the root's current name. Closed is switched off or past its end date; an open sibling beside them is the control.
  it('skips a closed link — switched off or past its end date — and re-seals the open sibling beside them', async () => {
    const off = await mintedRow(true)
    const expired = await mintedRow(false)
    const open = await mintedRow(false)
    const later = await mintedRow(true)
    const set = vi.fn<SharingApi['setShareSealedName']>(async () => undefined)
    fakeApi(sharesApi, {
      listShares: async () => [
        { ...off.row, enabled: false },
        { ...expired.row, expiresAt: new Date(Date.now() - 60_000) },
        open.row,
        { ...later.row, expiresAt: new Date(Date.now() + 86_400_000) },
      ],
      setShareSealedName: set,
    })

    const report = await resealShareNames(NODE_ID, 'Steuern 2026', EPOCHS)

    expect(report).toEqual({ resealed: [open.row.shareId, later.row.shareId], closed: [off.row.shareId, expired.row.shareId], skipped: [], failed: [] })
    expect(set.mock.calls.map(([r]) => r.shareId)).toEqual([open.row.shareId, later.row.shareId])
    const sealed = set.mock.calls[0][0].setShareSealedNameRequest.sealedName
    expect(opened(open.kShare, sealed)).toBe('Steuern 2026')
  })
})

describe('reactivateShare (ADR 0114 decision 7)', () => {
  /** A READ link as the manage list serves it after its close: minted through the real `mintShare`, so its recovery
   *  copy is the real one, and the root's key the close made the server forget. */
  async function closedReadLink() {
    const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create })
    const minted = await mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 0 }, EPOCHS, 'Taxes', ShareRole.Read)
    const req = create.mock.calls[0][0].createShareRequest
    const share = {
      scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: minted.shareId, role: ShareRole.Read,
      sealedShareKey: req.sealedShareKey ?? null, sealedShareKeyEpoch: req.sealedShareKeyEpoch ?? null,
    }
    return { share, kShare: minted.kShare }
  }

  it("reopens a READ link with the root's CURRENT key and name under K_share, stating the root's own epoch", async () => {
    const { share, kShare } = await closedReadLink()
    const reactivate = vi.fn<SharingApi['reactivateShare']>(async () => undefined)
    fakeApi(sharesApi, { reactivateShare: reactivate })
    // the close re-keyed the subtree: the root now holds its next key, at its own epoch 1
    const rootKey = v1.randomBytes(32)

    await reactivateShare(share, { key: rootKey, epoch: 1 }, EPOCHS, 'Steuern 2026')

    expect(reactivate).toHaveBeenCalledTimes(1)
    const [{ shareId, reactivateShareRequest: body }] = reactivate.mock.calls[0]
    expect(shareId).toBe(share.shareId)
    expect(body.nodeEpoch).toBe(1)
    expect(v1.open(SHARE_KEY, kShare, fromB64std(body.wrap!))).toEqual(rootKey)
    expect(new TextDecoder().decode(v1.openField(SHARE_MEMORY_NAME, kShare, fromB64std(body.sealedName!)))).toBe('Steuern 2026')
    expect(body.sealedDropPublicKey).toBeUndefined()
    expect(JSON.stringify(body)).not.toContain('Steuern')
  })

  it('refuses a link without a recovery copy before any request — one with a copy is sent', async () => {
    const { share } = await closedReadLink()
    const reactivate = vi.fn<SharingApi['reactivateShare']>(async () => undefined)
    fakeApi(sharesApi, { reactivateShare: reactivate })
    const root = { key: v1.randomBytes(32), epoch: 1 }

    await expect(reactivateShare({ ...share, sealedShareKey: null, sealedShareKeyEpoch: null }, root, EPOCHS, 'Taxes'))
      .rejects.toThrow('share_key_not_recoverable')
    // a copy left under a key a rotation replaced opens under no key: refused as well, nothing sent
    await expect(reactivateShare(share, root, { epoch: 1, key: v1.randomBytes(32) }, 'Taxes')).rejects.toThrow(v1.OpenRefused)
    expect(reactivate).not.toHaveBeenCalled()

    await reactivateShare(share, root, EPOCHS, 'Taxes')
    expect(reactivate).toHaveBeenCalledTimes(1)
  })
})
