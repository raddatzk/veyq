// A member's edit of a medium's meta in a Memory not re-keyed since a rotation (ADR 0111 decision 11, inbox #563), through
// the member source's door: the Memory's path is pulled forward on this device, its key re-read, and the edit rebuilt under
// it. The rebuild starts from the tier as listed (its plaintext, opened under the key it was listed with) - the re-key
// re-sealed that tier unchanged and raised its revision - so the send meets `409 stale_revision` and re-bases on the
// re-sealed tier, which opens under the new key (ADR 0077). A WRITE_ONLY guest's medium keeps its own `K_up` across the
// re-key, resolved from the origin as listed. The pull itself is core's (`pullRekeyPath`, `core/node/rekey/rekeyScope.test.ts`).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { ScopeKey } from '../spaces/epochs'

const held = vi.hoisted(() => ({ epochs: { epoch: 0, key: new Uint8Array(32) } as ScopeKey }))
const K_UP = vi.hoisted(() => new Uint8Array(32).fill(9))
const OLD_KEY = vi.hoisted(() => new Uint8Array(32).fill(1))
vi.mock('../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ scopeId: '5c09e000-0000-4000-8000-0000000000cc' }), activeEpochKeys: () => held.epochs }
})
vi.mock('./api', async (orig) => ({ ...(await orig<object>()), mediaApi: vi.fn() }))
// A WRITE_ONLY guest's `K_up` opens only through the origin AS LISTED, i.e. under the Memory's key from before the re-key
// (whose drop key the re-key replaced): under any other key the chain does not open, as the real one would refuse.
vi.mock('./mediaKey', async (orig) => ({ ...(await orig<object>()), mediaKey: vi.fn() }))
vi.mock('../../core/node/rekey/rekeyScope', async (orig) => ({ ...(await orig<object>()), pullRekeyPath: vi.fn() }))
vi.mock('../../core/node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../../core/node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))

import { memberMemorySource } from './source'
import { mediaApi } from './api'
import { mediaKey } from './mediaKey'
import { fakeApi, type ApiMocks } from '../../test/apiMock'
import type { MediaApi } from '../../api/generated/media'
import { mediaMetaContext } from './mediaOpen'
import type { MediaMetaTarget } from './media'
import type { UploaderOrigin } from './mediaKey'
import { pullRekeyPath } from '../../core/node/rekey/rekeyScope'
import { nodeKeyForWrite } from '../../core/node/rekey/floor'
import { treeStore } from '../../core/node/treeStore'
import { treeNode } from '../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const NODE_ID = 'f0000000-0000-4000-8000-000000000002'
const MEDIA_ID = 'a0000000-0000-4000-8000-000000000001'
const NEW_KEY = v1.randomBytes(32)
const AT = new Date('2024-05-01T10:00:00Z').getTime()

const seal = (key: Uint8Array, meta: object) =>
  b64std(v1.sealField(mediaMetaContext(SCOPE, MEDIA_ID), key, new TextEncoder().encode(JSON.stringify(meta))))
const open = (key: Uint8Array, wrapped: string) =>
  JSON.parse(new TextDecoder().decode(v1.openField(mediaMetaContext(SCOPE, MEDIA_ID), key, fromB64std(wrapped))))

/** A thrown generated-client error carrying `409` and a body, the shape `isCodedError` / `staleMetaOf` read. */
const conflict = (body: object) => Object.assign(new Error(String((body as { code: string }).code)), {
  response: new Response(JSON.stringify(body), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

const LISTED = { v: 1, placeName: 'Rom' }
const memberMedium = (): MediaMetaTarget => ({ mediaId: MEDIA_ID, wrappedMeta: seal(OLD_KEY, LISTED), metaRevision: 2 } as MediaMetaTarget)
const guestMedium = (): MediaMetaTarget => ({
  mediaId: MEDIA_ID, wrappedMeta: seal(K_UP, LISTED), metaRevision: 2,
  uploader: { nodeId: NODE_ID, sealedDropPrivateKey: 'old-drop', identityId: 'i-1', wrappedIdentityKey: 'w' } as UploaderOrigin,
} as MediaMetaTarget)
const sent = () => updateMediaMeta.mock.calls.map((c) => c[0].mediaMetaUpdate as { wrappedMeta: string; metaRevision: number; nodeEpoch: number })
let updateMediaMeta: ApiMocks<MediaApi, 'updateMediaMeta'>['updateMediaMeta']

const pull = vi.fn(async () => true)
beforeEach(() => {
  vi.clearAllMocks()
  ;({ updateMediaMeta } = fakeApi(mediaApi, { updateMediaMeta: vi.fn<MediaApi['updateMediaMeta']>() }))
  vi.mocked(mediaKey).mockImplementation((_s, key, row) => {
    if (!row.uploader) return key
    if (key !== OLD_KEY) throw new v1.OpenRefused('upload-identity-key')
    return K_UP
  })
  held.epochs = { epoch: 0, key: new Uint8Array(32) }
  // The Memory as the active tree lists it: its own key at epoch 0, minted under the scope's epoch 0.
  treeStore.nodes = [treeNode({ id: NODE_ID, epoch: 0, scopeEpoch: 0 })]
  vi.mocked(pullRekeyPath).mockReturnValue(pull)
  pull.mockResolvedValue(true)
  // After the pull the node is re-keyed: its own epoch 1, its key minted under the scope's epoch 1.
  vi.mocked(nodeKeyForWrite).mockImplementation(() => ({ key: NEW_KEY, epoch: 1, scopeEpoch: 1 }))
})

describe('a member\'s media-meta edit in a Memory not re-keyed yet (ADR 0111 decision 11)', () => {
  it('refused node_rekey_pending: the path is pulled, the edit rebuilt under the new key and re-based on the tier the re-key re-sealed', async () => {
    const resealed = seal(NEW_KEY, LISTED)
    updateMediaMeta
      .mockRejectedValueOnce(conflict({ code: 'node_rekey_pending' }))
      .mockRejectedValueOnce(conflict({ code: 'stale_revision', wrappedMeta: resealed, metaRevision: 3 }))
      .mockResolvedValueOnce({})
    const door = memberMemorySource({ scopeId: () => SCOPE }).mediaMeta!
    const result = await door.set(memberMedium(), OLD_KEY, { id: NODE_ID, epoch: 0 }, AT)
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [NODE_ID])
    expect(pull).toHaveBeenCalledOnce()
    expect(sent().map((s) => [s.metaRevision, s.nodeEpoch])).toEqual([[2, 0], [2, 1], [3, 1]])
    expect(open(NEW_KEY, sent()[2].wrappedMeta)).toEqual({ ...LISTED, capturedAtOverride: AT })
    expect(result).toMatchObject({ wrote: true, metaRevision: 4 })
  })

  it('the device floor already shows the Memory lags: nothing is sent under the old key, the place backfill lands under the new one', async () => {
    // The scope rotated to epoch 1; the tree still lists the Memory's key as minted under epoch 0 (ADR 0115 decision 6).
    held.epochs = { epoch: 1, key: new Uint8Array(32) }
    updateMediaMeta
      .mockRejectedValueOnce(conflict({ code: 'stale_revision', wrappedMeta: seal(NEW_KEY, { v: 1 }), metaRevision: 3 }))
      .mockResolvedValueOnce({})
    const door = memberMemorySource({ scopeId: () => SCOPE }).mediaMeta!
    await door.backfillPlace({ mediaId: MEDIA_ID, wrappedMeta: seal(OLD_KEY, { v: 1 }), metaRevision: 2 } as MediaMetaTarget, OLD_KEY, { id: NODE_ID, epoch: 0 }, 'Rom')
    expect(pull).toHaveBeenCalledOnce()
    expect(sent().every((s) => s.nodeEpoch === 1)).toBe(true)
    expect(open(NEW_KEY, sent().at(-1)!.wrappedMeta)).toEqual({ v: 1, placeName: 'Rom' })
  })

  it('a WRITE_ONLY guest\'s medium: the rebuild seals under the same K_up (the re-key leaves the medium as it is)', async () => {
    updateMediaMeta.mockRejectedValueOnce(conflict({ code: 'node_rekey_pending' })).mockResolvedValueOnce({})
    const door = memberMemorySource({ scopeId: () => SCOPE }).mediaMeta!
    await door.set(guestMedium(), OLD_KEY, { id: NODE_ID, epoch: 0 }, AT)
    expect(pull).toHaveBeenCalledOnce()
    expect(sent().map((s) => [s.metaRevision, s.nodeEpoch])).toEqual([[2, 0], [2, 1]])
    expect(open(K_UP, sent()[1].wrappedMeta)).toEqual({ ...LISTED, capturedAtOverride: AT })
  })
})
