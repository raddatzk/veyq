// @vitest-environment happy-dom
// A member's cover into a node not re-keyed since a rotation (ADR 0111 decision 11, inbox #563): the cover is wrapped under
// the node's key, so an epoch refusal runs the WHOLE cover again - the node's path pulled forward on this device, its key
// re-read, the image uploaded again and the write sent under the new key - instead of waiting for another device's re-key.
// Seen from the device (the floor knows the node lags) the pull runs before anything is sealed; seen from the server (`409
// node_rekey_pending` after the upload) the upload is repeated. A drop box is an owner's to re-key: its pull says no, and
// the cover waits as a guest's write does. The pull itself is core's (`pullRekeyPath`, `rekey/rekeyScope.test.ts`, the
// drop box's refusal included); here only that the cover asks it and what it does with the answer.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { ScopeKey } from '../../../features/spaces/epochs'

const held = vi.hoisted(() => ({ epochs: { epoch: 0, key: new Uint8Array(32) } as ScopeKey }))
vi.mock('../../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ scopeId: '5c09e000-0000-4000-8000-0000000000cc' }), activeEpochKeys: () => held.epochs }
})
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('./coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))
vi.mock('./coverStore', async (orig) => ({
  ...(await orig<object>()),
  useCovers: () => ({ covers: { value: new Map() }, declared: { has: () => false }, ensure: vi.fn() }),
}))
vi.mock('../rekey/rekeyScope', async (orig) => ({ ...(await orig<object>()), pullRekeyPath: vi.fn() }))
vi.mock('../rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))

import { useNodeCover, type UseNodeCover } from './useNodeCover'
import { uploadCoverImage, writeNodeCover } from './coverWrite'
import { pullRekeyPath } from '../rekey/rekeyScope'
import { nodeKeyForWrite } from '../rekey/floor'
import { loadTree, treeStore } from '../treeStore'
import { sealNodeMeta } from '../nodeMeta'
import { clearUploads, useUploadQueue } from '../../blob/queue'
import { FOLDER_KIND } from '../kind'
import { DROPBOX_KIND } from '../../../features/depot/kinds'
import type { TreeNode } from '../tree'
import { realI18n } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const OLD_KEY = v1.randomBytes(32)
const NEW_KEY = v1.randomBytes(32)
const crop = { x: 0, y: 0, width: 1, height: 1 }
const FOLDER = treeNode({ id: 'f0000000-0000-4000-8000-000000000001', kind: FOLDER_KIND, name: 'Trips', parentId: null, metaRevision: 0, epoch: 0 })
const DROPBOX = treeNode({ id: 'f0000000-0000-4000-8000-000000000002', kind: DROPBOX_KIND, name: 'Inbox', parentId: null, metaRevision: 0, epoch: 0 })
const image = () => new File([new Uint8Array([0xff, 0xd8])], 'a.jpg', { type: 'image/jpeg' })

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const pending = () => Object.assign(new Error('node_rekey_pending'), {
  response: new Response(JSON.stringify({ code: 'node_rekey_pending' }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

function harness(nodes: TreeNode[]): UseNodeCover {
  treeStore.nodes = nodes.map((n) => ({ ...n }))
  let api!: UseNodeCover
  mount(defineComponent({
    setup() {
      api = useNodeCover(
        () => memberAccessOf({ scopeId: SCOPE, epochs: held.epochs }),
        () => ({ ...memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }), keyOf: () => OLD_KEY }),
        () => treeStore.nodes,
      )
      return () => h('div')
    },
  }), { global: { plugins: [realI18n()] } })
  return api
}

const pull = vi.fn(async () => true)
const settled = async () => {
  const { batches } = useUploadQueue()
  await vi.waitFor(() => expect(['done', 'failed']).toContain(batches.value.at(-1)?.files[0]?.status), { timeout: 4_000 })
  return batches.value.at(-1)!.files[0]
}

beforeEach(() => {
  vi.clearAllMocks()
  clearUploads()
  held.epochs = { epoch: 0, key: new Uint8Array(32) }
  vi.mocked(pullRekeyPath).mockReturnValue(pull)
  vi.mocked(loadTree).mockImplementation(async () => {})
  pull.mockResolvedValue(true)
  // the re-read after the pull: the node re-keyed under its new key, minted at the scope's current epoch
  vi.mocked(nodeKeyForWrite).mockImplementation(() => ({ key: NEW_KEY, epoch: 1, scopeEpoch: 1 }))
  vi.mocked(uploadCoverImage).mockImplementation(async (_s, nodeId) => ({
    uploadId: `up-${vi.mocked(uploadCoverImage).mock.calls.length}`,
    plan: { blobId: `blob-${vi.mocked(uploadCoverImage).mock.calls.length}`, chunkCount: 1, byteSize: 9, wrappedBlobKey: 'd3JhcA==', ownerId: nodeId },
  }))
  vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaRevision: 1, replaced: null })
})
afterEach(() => vi.useRealTimers())

describe('a member\'s cover into a node not re-keyed yet (ADR 0111 decision 11)', () => {
  it('refused node_rekey_pending after the upload: the node\'s path is pulled, and the cover uploaded and written again under the new key', async () => {
    vi.mocked(writeNodeCover).mockRejectedValueOnce(pending())
    harness([FOLDER]).save(FOLDER, { kind: 'upload', file: image(), crop })
    expect((await settled()).status).toBe('done')
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [FOLDER.id])
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(uploadCoverImage).mock.calls.map((c) => c[2])).toEqual([OLD_KEY, NEW_KEY])
    const last = vi.mocked(writeNodeCover).mock.calls.at(-1)![0]
    expect(last).toMatchObject({ nodeKey: NEW_KEY, current: { nodeEpoch: 1 }, uploadId: 'up-2' })
  })

  it('the device floor already shows the node lags: the pull runs before anything is sealed, and the cover is uploaded once', async () => {
    held.epochs = { epoch: 1, key: new Uint8Array(32) } // the device holds scope epoch 1; the node's key is from 0
    harness([FOLDER]).save(FOLDER, { kind: 'upload', file: image(), crop })
    expect((await settled()).status).toBe('done')
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(uploadCoverImage).mock.calls.map((c) => c[2])).toEqual([NEW_KEY])
    expect(writeNodeCover).toHaveBeenCalledOnce()
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ nodeKey: NEW_KEY, current: { nodeEpoch: 1 } })
  })

  it('a removal (no upload) pulls and writes again under the new key', async () => {
    vi.mocked(writeNodeCover).mockRejectedValueOnce(pending())
    harness([FOLDER]).save(FOLDER, { kind: 'remove' })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledTimes(2))
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(writeNodeCover).mock.calls[1][0]).toMatchObject({ nodeKey: NEW_KEY, current: { nodeEpoch: 1 }, cover: null })
  })

  it('a moved crop keeps the cover the re-key re-wrapped: its wrap read from the re-read meta, never the one under the old key', async () => {
    const cover = (wrap: string) => ({ source: { kind: 'upload', ref: { blobId: 'b-1', chunkCount: 1, byteSize: 9, wrappedBlobKey: wrap } }, crop })
    const before = { ...FOLDER, wrappedMeta: sealNodeMeta(OLD_KEY, { scopeId: SCOPE, nodeId: FOLDER.id }, { v: 1, cover: cover('old-wrap') }), metaRevision: 3 }
    const after = { ...before, epoch: 1, metaRevision: 4, wrappedMeta: sealNodeMeta(NEW_KEY, { scopeId: SCOPE, nodeId: FOLDER.id }, { v: 1, cover: cover('new-wrap') }) }
    const resealed = after.wrappedMeta // the write patches the tree's copy afterwards
    vi.mocked(loadTree).mockImplementation(async () => void (treeStore.nodes = [after])) // the re-read after the pull
    vi.mocked(writeNodeCover).mockRejectedValueOnce(pending())
    const moved = { x: 0.1, y: 0.1, width: 0.5, height: 0.5 }
    harness([before]).save(before, { kind: 'keep', crop: moved })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledTimes(2))
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ nodeKey: OLD_KEY, cover: { source: { ref: { wrappedBlobKey: 'old-wrap' } } } })
    expect(vi.mocked(writeNodeCover).mock.calls[1][0]).toMatchObject({
      nodeKey: NEW_KEY, current: { wrappedMeta: resealed, metaRevision: 4, nodeEpoch: 1 },
      cover: { source: { ref: { wrappedBlobKey: 'new-wrap' } }, crop: moved },
    })
  })

  it('a drop box is an owner\'s to re-key: its pull says no, so the cover waits - and lands once an owner\'s device re-keyed it', async () => {
    vi.useFakeTimers()
    pull.mockResolvedValue(false) // `pullRekeyPath` on a path through a drop box, for a member (`RekeyPathNotMineError`)
    vi.mocked(writeNodeCover).mockRejectedValueOnce(pending())
    harness([DROPBOX]).save(DROPBOX, { kind: 'upload', file: image(), crop })
    await vi.advanceTimersByTimeAsync(1_000)
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [DROPBOX.id])
    expect(pull).toHaveBeenCalledOnce()
    expect(writeNodeCover).toHaveBeenCalledOnce() // still waiting: not written again at once
    await vi.advanceTimersByTimeAsync(2_000) // the first wait
    vi.useRealTimers()
    expect((await settled()).status).toBe('done')
    expect(writeNodeCover).toHaveBeenCalledTimes(2)
    expect(vi.mocked(writeNodeCover).mock.calls[1][0]).toMatchObject({ nodeKey: NEW_KEY, current: { nodeEpoch: 1 } })
  })
})
