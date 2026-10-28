// A member's banner on a Memory not re-keyed since a rotation (ADR 0111 decision 11, inbox #563): the banner is wrapped under
// the Memory's key, so an epoch refusal runs the WHOLE save again through the member source's door (`banner.under`) - the
// Memory's path pulled forward on this device, its key re-read, the bytes uploaded again and the slots written under the
// new key; a re-frame keeps the banner the re-key re-wrapped (read from the re-read meta), never the one read under the old
// key. The pull itself is core's (`pullRekeyPath`, `core/node/rekey/rekeyScope.test.ts`); the door is the real one.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import type { EpochKeys } from '../../spaces/epochs'

const held = vi.hoisted(() => ({ epochs: { current: 0, keys: new Map() } as EpochKeys }))
vi.mock('../../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ scopeId: '5c09e000-0000-4000-8000-0000000000cc' }), activeEpochKeys: () => held.epochs }
})
vi.mock('./bannerWrite', async (orig) => ({ ...(await orig<object>()), uploadBanner: vi.fn(), writeMemoryBanner: vi.fn() }))
vi.mock('../../../core/node/rekey/rekeyScope', async (orig) => ({ ...(await orig<object>()), pullRekeyPath: vi.fn() }))
vi.mock('../../../core/node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../../../core/node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))

import { useBannerEditor } from './useBannerEditor'
import { uploadBanner, writeMemoryBanner } from './bannerWrite'
import { memberMemorySource } from '../source'
import { pullRekeyPath } from '../../../core/node/rekey/rekeyScope'
import { nodeKeyForWrite } from '../../../core/node/rekey/floor'
import { treeStore } from '../../../core/node/treeStore'
import { sealNodeMeta } from '../../../core/node/nodeMeta'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import { MEMORY_KIND } from '../kinds'
import { treeNode } from '../../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const OLD_KEY = v1.randomBytes(32)
const NEW_KEY = v1.randomBytes(32)
const NODE_ID = 'f0000000-0000-4000-8000-000000000002'
const framing = { frame: 'fill' as const, focal: { x: 0.25, y: 0.5 } }
const bannerWith = (wrap: string) => ({ medium: 'image', blob: { blobId: 'b-1', chunkCount: 1, byteSize: 9, wrappedBlobKey: wrap } })
// The Memory as the device read it (under the old key), and as the re-read tree holds it after the re-key: its meta re-sealed
// under the new key, the banner's wrap moved to it.
const before = treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Rome', epoch: 0, metaRevision: 3,
  wrappedMeta: sealNodeMeta(OLD_KEY, { scopeId: SCOPE, nodeId: NODE_ID }, { v: 1, banner: bannerWith('old-wrap') }) })
const after = { ...before, epoch: 1, metaRevision: 4,
  wrappedMeta: sealNodeMeta(NEW_KEY, { scopeId: SCOPE, nodeId: NODE_ID }, { v: 1, banner: bannerWith('new-wrap') }) }

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const pending = () => Object.assign(new Error('node_rekey_pending'), {
  response: new Response(JSON.stringify({ code: 'node_rekey_pending' }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

const pull = vi.fn(async () => true)
const deriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8]), source: null })),
  deriveVideoPoster: vi.fn(),
} as unknown as MediaDeriver

function editor() {
  return useBannerEditor({
    scopeId: () => SCOPE, node: () => before, memoryKey: () => OLD_KEY, source: memberMemorySource({ scopeId: () => SCOPE }),
    items: () => [], raw: () => bannerWith('old-wrap'), onMetaUpdated: vi.fn(), deriver,
  })
}
const writes = () => vi.mocked(writeMemoryBanner).mock.calls.map((c) => c[0])

beforeEach(() => {
  vi.clearAllMocks()
  held.epochs = { current: 0, keys: new Map() }
  treeStore.nodes = [after]
  vi.mocked(pullRekeyPath).mockReturnValue(pull)
  pull.mockResolvedValue(true)
  vi.mocked(nodeKeyForWrite).mockImplementation(() => ({ key: NEW_KEY, epoch: 1 }))
  vi.mocked(uploadBanner).mockImplementation(async () => ({ medium: 'image', uploadId: `up-${vi.mocked(uploadBanner).mock.calls.length}`, blob: { blobId: 'b-2', chunkCount: 1, byteSize: 9, wrappedBlobKey: 'w' } }))
  vi.mocked(writeMemoryBanner).mockRejectedValueOnce(pending()).mockResolvedValue({ wrappedMeta: 'bmV3', metaRevision: 5 })
})

describe('a member\'s banner on a Memory not re-keyed yet (ADR 0111 decision 11)', () => {
  it('a picked image: the Memory\'s path is pulled, and the banner uploaded and written again under the new key', async () => {
    await editor().save({ kind: 'file', file: new File([new Uint8Array([1, 2])], 'a.jpg'), type: 'image/jpeg', poster: null, framing })
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [NODE_ID])
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(uploadBanner).mock.calls.map((c) => c[2])).toEqual([OLD_KEY, NEW_KEY])
    expect(writes()[1]).toMatchObject({ scopeId: SCOPE, nodeKey: NEW_KEY, current: { nodeEpoch: 1, metaRevision: 4 }, upload: { uploadId: 'up-2' } })
  })

  it('a re-frame keeps the banner the re-key re-wrapped: its wrap read from the re-read meta, the base the re-read one', async () => {
    await editor().save({ kind: 'keep', framing })
    expect(pull).toHaveBeenCalledOnce()
    expect(writes()[0]).toMatchObject({ nodeKey: OLD_KEY, banner: { blob: { wrappedBlobKey: 'old-wrap' } } })
    expect(writes()[1]).toMatchObject({
      nodeKey: NEW_KEY, current: { wrappedMeta: after.wrappedMeta, metaRevision: 4, nodeEpoch: 1 },
      banner: { blob: { wrappedBlobKey: 'new-wrap' }, frame: 'fill', focal: { x: 0.25, y: 0.5 } },
    })
    expect(uploadBanner).not.toHaveBeenCalled()
  })

  it('the device floor already shows the Memory lags: the pull runs before anything is sealed, and the banner is uploaded once', async () => {
    held.epochs = { current: 1, keys: new Map() }
    vi.mocked(writeMemoryBanner).mockReset().mockResolvedValue({ wrappedMeta: 'bmV3', metaRevision: 5 })
    await editor().save({ kind: 'file', file: new File([new Uint8Array([1, 2])], 'a.jpg'), type: 'image/jpeg', poster: null, framing })
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(uploadBanner).mock.calls.map((c) => c[2])).toEqual([NEW_KEY])
    expect(writes()).toHaveLength(1)
    expect(writes()[0]).toMatchObject({ nodeKey: NEW_KEY, current: { nodeEpoch: 1 } })
  })
})
