// The platform's share mint (cr-node-key-hierarchy 5b.2), driven without any application: a READ / READ_WRITE share of
// a node carries the wrap of its key and the mandatory recovery copy, and a WRITE_ONLY role is reachable only with the
// drop half an application hands in. The Memories suite (`features/memory/share.test.ts`) drives the same mint through
// its `createShare` / `reissueShare`, the drop key included.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ sharesApi: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { mintShare, recoverShareKey, remintShare, resealShareNames, type ReissuableShare } from './manageShares'
import { SHARE_KEY } from './shareTree'
import { SHARE_MEMORY_NAME } from './resolvePublicShare'
import { sharesApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import { ShareRole, type ShareSummary, type SharingApi } from '../../api/generated/sharing'
import type { EpochKeys } from '../../features/spaces/epochs'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const EPOCHS: EpochKeys = { current: 1, keys: new Map([[0, v1.randomBytes(32)], [1, v1.randomBytes(32)]]) }

beforeEach(() => vi.clearAllMocks())

describe('mintShare', () => {
  it('wraps the node key under K_share and seals a recovery copy every member can open, for any kind', async () => {
    const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
    fakeApi(sharesApi, { createShare: create })
    const nodeKey = v1.randomBytes(32)

    const minted = await mintShare(SCOPE_ID, NODE_ID, nodeKey, EPOCHS, 'Taxes', ShareRole.ReadWrite)

    const req = create.mock.calls[0][0].createShareRequest
    expect(req).toMatchObject({ shareId: minted.shareId, nodeId: NODE_ID, role: ShareRole.ReadWrite, sealedShareKeyEpoch: 1 })
    expect(v1.open(SHARE_KEY, minted.kShare, fromB64std(req.wrap!))).toEqual(nodeKey)
    const address = { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: minted.shareId }
    expect(recoverShareKey(EPOCHS, address, req)).toEqual(minted.kShare)
    expect(req.sealedDropPublicKey).toBeUndefined()
    expect(JSON.stringify(req)).not.toContain('Taxes')
  })

  it('refuses a WRITE_ONLY role without the drop half, and a drop half on any other role, before posting', async () => {
    const create = vi.fn<SharingApi['createShare']>()
    fakeApi(sharesApi, { createShare: create })
    const drop = { dropFields: async () => ({ sealedDropPublicKey: 'AA==' }) }

    await expect(mintShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'x', ShareRole.WriteOnly))
      .rejects.toThrow('share_write_only_mint_mismatch')
    await expect(mintShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, 'x', ShareRole.Read, null, {}, '', '', drop))
      .rejects.toThrow('share_write_only_mint_mismatch')
    expect(create).not.toHaveBeenCalled()
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
    const settings = { note: {}, name: '', password: '', expiresAt: null }

    const minted = await remintShare(old, nodeKey, EPOCHS, 'Taxes', settings)

    const req = reissue.mock.calls[0][0].reissueShareRequest
    expect(req.shareId).toBe(minted.shareId)
    expect(v1.open(SHARE_KEY, minted.kShare, fromB64std(req.wrap!))).toEqual(nodeKey)
    expect(recoverShareKey(EPOCHS, { scopeId: SCOPE_ID, nodeId: NODE_ID, shareId: minted.shareId }, req)).toEqual(minted.kShare)
    await expect(remintShare({ ...old, role: ShareRole.WriteOnly }, nodeKey, EPOCHS, 'Taxes', settings))
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
    const minted = await mintShare(SCOPE_ID, NODE_ID, v1.randomBytes(32), EPOCHS, oldName, ShareRole.Read)
    const req = create.mock.calls[0][0].createShareRequest
    const row: ShareSummary = {
      shareId: minted.shareId, nodeId: NODE_ID, scopeId: SCOPE_ID, role: ShareRole.Read, sealedName: req.sealedName,
      sealedShareKey: req.sealedShareKey, sealedShareKeyEpoch: req.sealedShareKeyEpoch, enabled: true, mine,
      reissueRequired: false, deletable: mine, newGuestsRefused: false, writeVerifierMissing: false,
      createdAt: new Date(Date.UTC(2026, 9, 1) - 1000 * age++),
    }
    return { row, kShare: minted.kShare }
  }
  const opened = (kShare: Uint8Array, sealed: string) => new TextDecoder().decode(v1.open(SHARE_MEMORY_NAME, kShare, fromB64std(sealed)))

  it('re-seals the new name under EVERY share\'s own K_share — the caller\'s and another member\'s', async () => {
    const own = await mintedRow(true)
    const theirs = await mintedRow(false)
    const set = vi.fn<SharingApi['setShareSealedName']>(async () => undefined)
    fakeApi(sharesApi, { listShares: async () => [own.row, theirs.row], setShareSealedName: set })

    const report = await resealShareNames(NODE_ID, 'Steuern 2026', EPOCHS)

    expect(report).toEqual({ resealed: [own.row.shareId, theirs.row.shareId], skipped: [], failed: [] })
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

    expect(report).toEqual({ resealed: [ok.row.shareId], skipped: [legacy.row.shareId], failed: [] })
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

    expect(report).toEqual({ resealed: [ok.row.shareId], skipped: [], failed: [refused.row.shareId, broken.row.shareId] })
    expect(set.mock.calls.map(([r]) => r.shareId)).toEqual([refused.row.shareId, ok.row.shareId])
  })

  it('writes nothing for a node without shares', async () => {
    const set = vi.fn<SharingApi['setShareSealedName']>()
    fakeApi(sharesApi, { listShares: async () => [], setShareSealedName: set })
    expect(await resealShareNames(NODE_ID, 'New', EPOCHS)).toEqual({ resealed: [], skipped: [], failed: [] })
    expect(set).not.toHaveBeenCalled()
  })
})
