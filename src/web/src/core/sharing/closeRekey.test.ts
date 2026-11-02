// ADR 0114 on the curator's device: every close of a viewable link starts its root's subtree re-key in the background
// (decision 9) — a revoke, a delete of an open link, a re-issue, an end date set into the past — and a WRITE_ONLY close
// starts nothing (decision 2); and an edit that moves a passed end date forward reopens the link carrying what a
// re-activation carries (decision 7), at the root's own epoch, its path pulled forward on `node_rekey_pending`. Every
// "starts" beside the control on the same call that starts nothing.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { pull } = vi.hoisted(() => ({ pull: vi.fn(async () => true) }))
vi.mock('../../api/client', () => ({ sharesApi: vi.fn() }))
// The re-key itself is rekeyScope.test.ts's; here only that a close starts it, on which subtree, and when.
vi.mock('../node/rekey/rekeyScope', () => ({
  rekeyPath: vi.fn(async () => ({ done: true, committed: 1 })),
  pullRekeyPath: vi.fn(() => pull),
}))
// The re-read after a refusal: the scope opened again, the tree read again, the root's key as the floor hands it.
vi.mock('../../features/spaces/scopes', () => ({ openScopeById: vi.fn() }))
vi.mock('../node/tree', () => ({ listTree: vi.fn(async () => []), fetchTreeRows: vi.fn(async () => []) }))
vi.mock('../node/rekey/floor', () => ({ nodeKeyForWrite: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import {
  deleteShare, mintShare, rekeyAfterClose, rekeyingClosedLinks, remintShare, revokeShare, updateShare, NO_NOTE,
  type ReissuableShare,
} from './manageShares'
import { SHARE_KEY } from './shareTree'
import { SHARE_MEMORY_NAME } from './resolvePublicShare'
import { sharesApi } from '../../api/client'
import { openScopeById } from '../../features/spaces/scopes'
import { nodeKeyForWrite } from '../node/rekey/floor'
import { pullRekeyPath, rekeyPath } from '../node/rekey/rekeyScope'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { ShareRole, type SharingApi } from '../../api/generated/sharing'
import type { ScopeKey } from '../../features/spaces/epochs'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const EPOCHS: ScopeKey = { epoch: 1, key: v1.randomBytes(32) }
const at = (shareId: string) => ({ scopeId: SCOPE_ID, nodeId: NODE_ID, shareId })
const HOUR = 3_600_000

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(rekeyPath).mockReset().mockResolvedValue({ done: true, committed: 1 })
  rekeyingClosedLinks.clear()
})

/** The subtree re-key a close starts, as `rekeyAfterClose` calls it. */
const startedOn = () => vi.mocked(rekeyPath).mock.calls.map(([scopeId, nodeIds, opts]) => ({ scopeId, nodeIds, opts }))
const SUBTREE = { scopeId: SCOPE_ID, nodeIds: [NODE_ID], opts: { subtree: true } }

describe('a close starts its subtree re-key (ADR 0114 decision 9)', () => {
  it('a revoke of a viewable link starts it after the server answered; a WRITE_ONLY one starts nothing', async () => {
    const order: string[] = []
    fakeApi(sharesApi, { revokeShare: vi.fn(async () => { order.push('revoke') }) })
    vi.mocked(rekeyPath).mockImplementation(async () => { order.push('rekey'); return { done: true, committed: 1 } })

    await revokeShare({ ...at('read'), role: ShareRole.Read })
    await revokeShare({ ...at('drop'), role: ShareRole.WriteOnly })

    expect(startedOn()).toEqual([SUBTREE]) // the control: the WRITE_ONLY revoke beside it started none
    expect(order).toEqual(['revoke', 'rekey', 'revoke'])
  })

  it('the dialog does not wait for it, and the link is marked re-keying until it ends', async () => {
    fakeApi(sharesApi, { revokeShare: vi.fn(async () => undefined) })
    let finish!: () => void
    vi.mocked(rekeyPath).mockReturnValue(new Promise((r) => { finish = () => r({ done: true, committed: 3 }) }))

    await revokeShare({ ...at('read'), role: ShareRole.ReadWrite }) // resolves while the re-key still runs
    expect(rekeyingClosedLinks.has('read')).toBe(true)
    finish()
    await vi.waitFor(() => expect(rekeyingClosedLinks.has('read')).toBe(false))
  })

  it('a re-key that fails is left to the members\' workers: nothing throws, and the mark goes', async () => {
    vi.mocked(rekeyPath).mockRejectedValue(new Error('rekey_path_not_mine: drop box'))
    await expect(rekeyAfterClose({ ...at('read'), role: ShareRole.Read })).resolves.toBeUndefined()
    expect(rekeyingClosedLinks.has('read')).toBe(false)
  })

  it('a refused revoke closed nothing and starts nothing', async () => {
    fakeApi(sharesApi, { revokeShare: vi.fn(async () => { throw httpError(404, 'share_not_found') }) })
    await expect(revokeShare({ ...at('read'), role: ShareRole.Read })).rejects.toBeTruthy()
    expect(rekeyPath).not.toHaveBeenCalled()
  })

  it('a delete of an OPEN link starts it; one of a switched-off or expired link — closed at its close — does not', async () => {
    fakeApi(sharesApi, { deleteShare: vi.fn(async () => undefined) })

    await deleteShare({ ...at('off'), role: ShareRole.Read, enabled: false, expiresAt: null })
    await deleteShare({ ...at('expired'), role: ShareRole.Read, enabled: true, expiresAt: new Date(Date.now() - HOUR) })
    expect(rekeyPath).not.toHaveBeenCalled()
    await deleteShare({ ...at('open'), role: ShareRole.Read, enabled: true, expiresAt: new Date(Date.now() + HOUR) })
    expect(startedOn()).toEqual([SUBTREE])
  })

  it('a re-issue starts it for the old link of a viewable role, none for a WRITE_ONLY one', async () => {
    const reissue = vi.fn<SharingApi['reissueShare']>(async ({ reissueShareRequest }) => ({ shareId: reissueShareRequest.shareId }))
    fakeApi(sharesApi, { reissueShare: reissue })
    const old = (role: ShareRole): ReissuableShare => ({
      ...at('old'), role, expiresAt: null, sealedNote: null, sealedShareName: null, passwordRequired: false, sealedPassword: null,
      sealedShareKey: null, sealedShareKeyEpoch: null,
    })
    const settings = { note: NO_NOTE, name: '', password: '', expiresAt: null }

    await remintShare(old(ShareRole.WriteOnly), { key: v1.randomBytes(32), epoch: 0 }, EPOCHS, 'x', settings, {
      dropFields: async () => ({ sealedDropPublicKey: 'AA==' }),
    })
    expect(rekeyPath).not.toHaveBeenCalled()
    await remintShare(old(ShareRole.Read), { key: v1.randomBytes(32), epoch: 0 }, EPOCHS, 'x', settings)
    expect(reissue).toHaveBeenCalledTimes(2)
    expect(startedOn()).toEqual([SUBTREE])
  })

  it('an end date set into the past closes an open link and starts it; an edit keeping a future one does not', async () => {
    const { share, copy } = await minted()
    const update = vi.fn<SharingApi['updateShare']>(async () => undefined)
    fakeApi(sharesApi, { updateShare: update })
    const open = { ...copy, enabled: true, expiresAt: new Date(Date.now() + HOUR) }

    await updateShare(share, EPOCHS, open, NO_NOTE, '', new Date(Date.now() + 2 * HOUR))
    expect(rekeyPath).not.toHaveBeenCalled()
    await updateShare(share, EPOCHS, open, NO_NOTE, '', new Date(Date.now() - HOUR))
    expect(startedOn()).toEqual([SUBTREE])
    expect(update.mock.calls[1][0].updateShareRequest.wrap).toBeUndefined() // a close carries no key
  })
})

/** A READ link minted through the real `mintShare`, so its recovery copy is the real one. */
async function minted() {
  const create = vi.fn<SharingApi['createShare']>(async ({ createShareRequest }) => ({ shareId: createShareRequest.shareId }))
  fakeApi(sharesApi, { createShare: create })
  const m = await mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 0 }, EPOCHS, 'Taxes', ShareRole.Read)
  const req = create.mock.calls[0][0].createShareRequest
  return {
    kShare: m.kShare,
    share: at(m.shareId),
    copy: { role: ShareRole.Read, sealedShareKey: req.sealedShareKey ?? null, sealedShareKeyEpoch: req.sealedShareKeyEpoch ?? null },
  }
}

describe('an edit that moves a passed end date forward reopens the link (ADR 0114 decision 7)', () => {
  const expired = (copy: Awaited<ReturnType<typeof minted>>['copy']) => ({ ...copy, enabled: true, expiresAt: new Date(Date.now() - HOUR) })
  const opened = (kShare: Uint8Array, sealed: string) => new TextDecoder().decode(v1.openField(SHARE_MEMORY_NAME, kShare, fromB64std(sealed)))

  it("carries the root's current key and name under K_share at its own epoch — into the future, or cleared", async () => {
    const { share, copy, kShare } = await minted()
    const update = vi.fn<SharingApi['updateShare']>(async () => undefined)
    fakeApi(sharesApi, { updateShare: update })
    const rootKey = v1.randomBytes(32)
    const root = { root: { key: rootKey, epoch: 2 }, rootName: 'Steuern 2026' }

    await updateShare(share, EPOCHS, expired(copy), NO_NOTE, '', new Date(Date.now() + HOUR), undefined, undefined, root)
    await updateShare(share, EPOCHS, expired(copy), NO_NOTE, '', null, undefined, undefined, root)

    for (const [{ updateShareRequest: body }] of update.mock.calls) {
      expect(body.nodeEpoch).toBe(2)
      expect(v1.open(SHARE_KEY, kShare, fromB64std(body.wrap!))).toEqual(rootKey)
      expect(opened(kShare, body.sealedName!)).toBe('Steuern 2026')
      expect(body.sealedDropPublicKey).toBeUndefined()
      expect(JSON.stringify(body)).not.toContain('Steuern')
    }
    expect(rekeyPath).not.toHaveBeenCalled() // a reopen is no close
  })

  it('control: an edit that reopens nothing — an open link, or an expired one kept expired — carries none of it', async () => {
    const { share, copy } = await minted()
    const update = vi.fn<SharingApi['updateShare']>(async () => undefined)
    fakeApi(sharesApi, { updateShare: update })
    const root = { root: { key: v1.randomBytes(32), epoch: 2 }, rootName: 'Taxes' }

    await updateShare(share, EPOCHS, { ...copy, enabled: true, expiresAt: null }, NO_NOTE, 'label', null, undefined, undefined, root)
    await updateShare(share, EPOCHS, expired(copy), NO_NOTE, 'label', new Date(Date.now() - 2 * HOUR), undefined, undefined, root)
    // switched off: an end date moved forward does not switch it on (a re-activation does)
    await updateShare(share, EPOCHS, { ...expired(copy), enabled: false }, NO_NOTE, 'label', null, undefined, undefined, root)

    for (const [{ updateShareRequest: body }] of update.mock.calls) {
      expect(body).not.toHaveProperty('nodeEpoch')
      expect(body).not.toHaveProperty('wrap')
      expect(body).not.toHaveProperty('sealedName')
    }
  })

  it('refuses a reopen without the root to seal it from, before any request', async () => {
    const { share, copy } = await minted()
    const update = vi.fn<SharingApi['updateShare']>(async () => undefined)
    fakeApi(sharesApi, { updateShare: update })
    await expect(updateShare(share, EPOCHS, expired(copy), NO_NOTE, '', null)).rejects.toThrow('share_reopen_root_missing')
    expect(update).not.toHaveBeenCalled()
  })

  it("a clock behind the server's is answered link_key_required and sent once more as the reopen", async () => {
    const { share, copy, kShare } = await minted()
    const update = vi.fn<SharingApi['updateShare']>()
      .mockRejectedValueOnce(httpError(422, 'link_key_required'))
      .mockResolvedValue(undefined)
    fakeApi(sharesApi, { updateShare: update })
    const rootKey = v1.randomBytes(32)
    // as this device's clock reads it the end date is a minute ahead; the server's says it has passed
    const ahead = { ...copy, enabled: true, expiresAt: new Date(Date.now() + 60_000) }

    await updateShare(share, EPOCHS, ahead, NO_NOTE, '', null, undefined, undefined, { root: { key: rootKey, epoch: 1 }, rootName: 'Taxes' })

    expect(update).toHaveBeenCalledTimes(2)
    expect(update.mock.calls[0][0].updateShareRequest).not.toHaveProperty('wrap')
    expect(v1.open(SHARE_KEY, kShare, fromB64std(update.mock.calls[1][0].updateShareRequest.wrap!))).toEqual(rootKey)
  })

  it("on node_rekey_pending the member's write pulls the root's path forward and reopens with the re-read key", async () => {
    const { share, copy, kShare } = await minted()
    const update = vi.fn<SharingApi['updateShare']>()
      .mockRejectedValueOnce(httpError(409, 'node_rekey_pending'))
      .mockResolvedValue(undefined)
    fakeApi(sharesApi, {
      updateShare: update,
      // the re-read lists the link again, its recovery copy as it is now
      listShares: vi.fn<SharingApi['listShares']>(async () => [{
        shareId: share.shareId, scopeId: SCOPE_ID, nodeId: NODE_ID, role: ShareRole.Read, sealedName: 'c2VhbGVk', enabled: true, mine: true,
        createdAt: new Date(), sealedShareKey: copy.sealedShareKey, sealedShareKeyEpoch: copy.sealedShareKeyEpoch,
        reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
      }]),
    })
    vi.mocked(openScopeById).mockResolvedValue({ epochs: { ...EPOCHS, key: EPOCHS.key.slice() } } as Awaited<ReturnType<typeof openScopeById>>)
    const reKeyed = v1.randomBytes(32)
    vi.mocked(nodeKeyForWrite).mockReturnValue({ key: reKeyed, epoch: 3, scopeEpoch: 1 })

    await updateShare(share, EPOCHS, expired(copy), NO_NOTE, '', null, undefined, undefined, { root: { key: v1.randomBytes(32), epoch: 2 }, rootName: 'Taxes' })

    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE_ID, [NODE_ID])
    expect(pull).toHaveBeenCalledTimes(1)
    expect(update).toHaveBeenCalledTimes(2)
    const body = update.mock.calls[1][0].updateShareRequest
    expect(body.nodeEpoch).toBe(3)
    expect(v1.open(SHARE_KEY, kShare, fromB64std(body.wrap!))).toEqual(reKeyed)
  })
})
