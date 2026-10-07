// The platform's share mint (cr-node-key-hierarchy 5b.2), driven without any application: a READ / READ_WRITE share of
// a node carries the wrap of its key and the mandatory recovery copy, and a WRITE_ONLY role is reachable only with the
// drop half an application hands in. The Memories suite (`features/memory/share.test.ts`) drives the same mint through
// its `createShare` / `reissueShare`, the drop key included.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ sharesApi: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { mintShare, recoverShareKey, remintShare, type ReissuableShare } from './manageShares'
import { SHARE_KEY } from './shareTree'
import { sharesApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import { ShareRole, type SharingApi } from '../../api/generated/sharing'
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
