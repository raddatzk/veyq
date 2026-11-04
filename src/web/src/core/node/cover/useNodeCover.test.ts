// @vitest-environment happy-dom
// What core's cover composable does with the dialog (inbox #411, follow-up 2026-10-12): the state it opens the dialog with (the
// crop restored from the sealed cover), and what each save intent writes - ONE atomic write through `writeNodeCover` (the
// writer's own wire behaviour is `coverWrite.test.ts`), with the conflict rule of the kind of save, and what the user hears when
// it fails. An upload whose image the kind finds already stored (`KindCover.findStored`) is a shared row, nothing uploaded (ADR
// 0013 § 5). The registry is the real one (Memories' MEMORY carries `findStored`, mocked at its module; a folder none); the
// upload and the write are mocked at their module boundary.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const toastError = vi.hoisted(() => vi.fn())
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('./coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))
vi.mock('../../../features/memories/storedMatch', async (orig) => ({ ...(await orig<object>()), findStoredCover: vi.fn() }))
vi.mock('./coverStore', async (orig) => ({
  ...(await orig<object>()),
  // The decrypted covers are `coverStore.test.ts`'s: here the store answers from a fixed map, the declared set from the sealed meta.
  useCovers: () => ({
    covers: { value: loaded },
    declared: { has: (id: string) => declaredIds.has(id) },
    ensure: vi.fn(),
  }),
}))

import { useNodeCover, type UseNodeCover } from './useNodeCover'
import { uploadedCover, type CoverCrop, type LoadedCover } from './coverModel'
import { uploadCoverImage, writeNodeCover, CoverStaleError } from './coverWrite'
import { findStoredCover } from '../../../features/memories/storedMatch'
import { planBlob, unwrapBlobKey } from '../../blob/transport'
import { MetaConflictError, MetaUnreadableError } from '../sealedMetaWrite'
import { treeStore } from '../treeStore'
import { clearUploads } from '../../blob/queue'
import { FOLDER_KIND } from '../kind'
import { MEMORY_KIND } from '../../../features/memories/kinds'
import { FILE_KIND } from '../../../features/depot/kinds'
import { ShareRole } from '../../../api/generated/sharing'
import type { Access } from '../../access'
import type { TreeNode } from '../tree'
import type { ScopeKey } from '../../../features/spaces/epochs'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const KEY = v1.randomBytes(32)
const EPOCHS = {} as ScopeKey
const crop: CoverCrop = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }
const FULL = { x: 0, y: 0, width: 1, height: 1 }

let loaded = new Map<string, LoadedCover>()
let declaredIds = new Set<string>()

const sealed = (nodeId: string, meta: object) =>
  b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1, ...meta }))))
const upload = (nodeId: string, blobId: string, c: CoverCrop = FULL) =>
  uploadedCover({ blobId, chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: nodeId }, c)
/** An older client's photo reference: read as no cover, carried untouched by every writer. */
const photo = (mediaId: string, c: CoverCrop = FULL) => ({ source: { kind: 'media', mediaId }, crop: c })

const FOLDER = treeNode({ id: 'f0000000-0000-4000-8000-000000000001', kind: FOLDER_KIND, name: 'Trips', parentId: null, metaRevision: 0 })
const MEMORY = treeNode({ id: 'f0000000-0000-4000-8000-000000000002', kind: MEMORY_KIND, name: 'Rome', parentId: null, metaRevision: 0 })
const FILE = treeNode({ id: 'f0000000-0000-4000-8000-000000000003', kind: FILE_KIND, name: 'Plan.pdf', parentId: null, metaRevision: 0 })
const withMeta = (n: TreeNode, meta: object, version = 3): TreeNode => ({ ...n, wrappedMeta: sealed(n.id, meta), metaRevision: version })

function harness(nodes: TreeNode[], access: Access = memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS })) {
  nodes = nodes.map((n) => ({ ...n })) // a write patches the tree's copy in place: never the shared constants
  treeStore.nodes = nodes
  let api!: UseNodeCover
  mount(
    defineComponent({
      setup() {
        api = useNodeCover(() => access, () => ({ ...memberTreeOf(() => nodes, { scopeId: SCOPE }), keyOf: () => KEY }), () => nodes)
        return () => h('div')
      },
    }),
    { global: { plugins: [realI18n()] } },
  )
  return api
}
const lastWrite = () => vi.mocked(writeNodeCover).mock.calls.at(-1)![0]

beforeEach(() => {
  vi.clearAllMocks()
  clearUploads()
  loaded = new Map()
  declaredIds = new Set()
  vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaRevision: 9, replaced: null })
  vi.mocked(findStoredCover).mockResolvedValue(null)
  vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'up-1', plan: { blobId: 'new-blob', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: FOLDER.id } })
})

describe('what the dialog opens with', () => {
  it('a node with no cover: no cover to re-crop or remove', () => {
    const api = harness([FOLDER])
    api.open(FOLDER)
    expect(api.editor.value).toEqual({ node: expect.objectContaining({ id: FOLDER.id }), cover: null, existingSrc: null })
  })

  it('a node with a cover: its stored crop (from the sealed meta) and its decrypted image are what the dialog re-crops', () => {
    const node = withMeta(FOLDER, { cover: upload(FOLDER.id, 'b-1', crop) })
    declaredIds.add(node.id)
    loaded.set(node.id, { url: 'blob:cover', crop })
    const api = harness([node])
    api.open(node)
    expect(api.editor.value).toMatchObject({ cover: { crop }, existingSrc: 'blob:cover' })
  })

  it('a cover that did not load can still be opened (and removed): the crop is the sealed one, the image absent', () => {
    const node = withMeta(FOLDER, { cover: upload(FOLDER.id, 'b-1', crop) })
    declaredIds.add(node.id)
    const api = harness([node])
    api.open(node)
    expect(api.editor.value).toMatchObject({ cover: { crop }, existingSrc: null })
  })

  it('close() drops the state', () => {
    const api = harness([FOLDER])
    api.open(FOLDER)
    api.close()
    expect(api.editor.value).toBeNull()
  })

  it.each([
    ['a file (no cover kind)', FILE, undefined],
    ['a pending deletion', FOLDER, memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS, readonly: true })],
    ['locked keys', FOLDER, memberAccessOf({ scopeId: SCOPE, epochs: null })],
    ['a share guest', FOLDER, guestAccessOf(ShareRole.Read)],
  ])('%s: the dialog does not open and the action is not offered', (_what, node, access) => {
    const api = harness([node], access)
    api.open(node)
    expect(api.editor.value).toBeNull()
    expect(api.rowActions(node)).toEqual([])
  })
})

describe('the action', () => {
  it('is ONE action, id cover, the image icon, "Set cover" without a cover and "Change cover" with one', () => {
    const api = harness([FOLDER])
    const [set, ...rest] = api.rowActions(FOLDER)
    expect(rest, 'no separate remove').toEqual([])
    expect([set.id, set.icon, set.label]).toEqual(['cover', 'image', t('node.cover.set')])
    declaredIds.add(FOLDER.id)
    expect(api.rowActions(FOLDER)[0].label).toBe(t('node.cover.change'))
    expect(api.rowActions(FOLDER).map((a) => a.id)).toEqual(['cover'])
  })

  it('opens the dialog', () => {
    const api = harness([FOLDER])
    api.rowActions(FOLDER)[0].onClick()
    expect(api.editor.value?.node.id).toBe(FOLDER.id)
  })
})

describe('what each intent writes', () => {
  const file = new File([new Uint8Array([1])], 'c.png', { type: 'image/png' })

  it('upload: the open upload becomes the cover framed by the crop, in a job of the upload queue, replacing without a peer-conflict stop', async () => {
    const api = harness([FOLDER])
    api.save(FOLDER, { kind: 'upload', file, crop })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(uploadCoverImage).mock.calls[0][5]).toMatchObject({ strict: true })
    expect(lastWrite()).toMatchObject({ scopeId: SCOPE, nodeId: FOLDER.id, uploadId: 'up-1', surfaceConflict: false })
    expect(lastWrite().cover).toEqual(upload(FOLDER.id, 'new-blob', crop))
  })

  it('upload: the node\'s freshest meta and version are the base, read AFTER the upload', async () => {
    const api = harness([FOLDER])
    vi.mocked(uploadCoverImage).mockImplementation(async () => {
      treeStore.nodes[0].wrappedMeta = 'cGVlcg=='
      treeStore.nodes[0].metaRevision = 7
      return { uploadId: 'up-1', plan: { blobId: 'new-blob', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: FOLDER.id } }
    })
    api.save(FOLDER, { kind: 'upload', file, crop })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(lastWrite().current).toEqual({ wrappedMeta: 'cGVlcg==', metaRevision: 7, nodeEpoch: FOLDER.epoch })
  })

  it('upload: a file that is no image is refused before any upload (a dialog never emits one; the door is shut anyway)', () => {
    const api = harness([FOLDER])
    api.save(FOLDER, { kind: 'upload', file: new File([new Uint8Array([1])], 'a.pdf', { type: 'application/pdf' }), crop })
    expect(toastError).toHaveBeenCalledWith(t('node.cover.notImage'))
    expect(uploadCoverImage).not.toHaveBeenCalled()
  })

  it('remove: ONE write with no cover, not stopped by a peer\'s change', async () => {
    const node = withMeta(FOLDER, { cover: upload(FOLDER.id, 'b-1') })
    const api = harness([node])
    api.save(node, { kind: 'remove' })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(lastWrite()).toMatchObject({ nodeId: node.id, cover: null, surfaceConflict: false, current: { metaRevision: 3 } })
    expect(uploadCoverImage).not.toHaveBeenCalled()
  })

  it('keep: the stored cover with only its crop moved - an uploaded source stays, no new blob, a peer\'s change stops it', async () => {
    const node = withMeta(FOLDER, { cover: upload(FOLDER.id, 'b-1', crop) })
    const api = harness([node])
    const moved = { x: 0.3, y: 0.3, width: 0.4, height: 0.225 }
    api.save(node, { kind: 'keep', crop: moved })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(lastWrite().cover).toEqual({ ...upload(FOLDER.id, 'b-1'), crop: moved })
    expect(lastWrite().uploadId).toBeUndefined()
    expect(lastWrite().surfaceConflict).toBe(true)
  })

  it('keep: an older photo reference is carried untouched (the source stays as stored)', async () => {
    const node = withMeta(MEMORY, { cover: photo('m-7', crop) })
    const api = harness([node])
    api.save(node, { kind: 'keep', crop: FULL })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(lastWrite().cover).toEqual(photo('m-7', FULL))
  })

  it('keep with no stored cover writes nothing', async () => {
    const api = harness([FOLDER])
    api.save(FOLDER, { kind: 'keep', crop })
    await flushPromises()
    expect(writeNodeCover).not.toHaveBeenCalled()
  })

  it('keep on a meta that does not open has no cover to keep and writes nothing', async () => {
    const node = { ...FOLDER, wrappedMeta: 'bm90IHNlYWxlZA==', metaRevision: 2 }
    const api = harness([node])
    api.save(node, { kind: 'keep', crop })
    await flushPromises()
    expect(writeNodeCover).not.toHaveBeenCalled()
  })

  it('upload: an image the kind finds stored is a new row on that blob - nothing uploaded, the K_blob wrapped for the cover', async () => {
    // The Memory's medium's PREVIEW, under the Memory's key, owned by the medium.
    const preview = planBlob(SCOPE, { ownerId: '33333333-3333-4333-8333-333333333333', role: 'PREVIEW' }, KEY, new Blob([new Uint8Array(40)]))
    vi.mocked(findStoredCover).mockResolvedValueOnce({ key: KEY, ref: preview })
    const api = harness([MEMORY])
    api.save(MEMORY, { kind: 'upload', file, crop })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(findStoredCover).toHaveBeenCalledWith(expect.objectContaining({ scopeId: SCOPE, nodeId: MEMORY.id, nodeKey: KEY, file }))
    expect(uploadCoverImage).not.toHaveBeenCalled()
    const w = lastWrite()
    expect(w.uploadId).toBeUndefined()
    expect(w.shared).toMatchObject({ sourceBlobId: preview.blobId })
    const ref = (w.cover as { source: { ref: { blobId: string; byteSize: number; wrappedBlobKey: string } } }).source.ref
    expect(ref.blobId).toBe(w.shared!.blobId)
    expect(ref.byteSize).toBe(preview.byteSize)
    expect(unwrapBlobKey(SCOPE, KEY, { ...ref, ownerId: MEMORY.id, role: 'COVER' }), 'the same K_blob, bound to the cover').toEqual(preview.blobKey)
  })

  it('upload: no stored match, or a failure to look, uploads as before; a kind without findStored never looks', async () => {
    vi.mocked(findStoredCover).mockRejectedValueOnce(new Error('offline'))
    const api = harness([MEMORY, FOLDER])
    api.save(MEMORY, { kind: 'upload', file, crop })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(lastWrite()).toMatchObject({ uploadId: 'up-1', shared: undefined })
    api.save(FOLDER, { kind: 'upload', file, crop })
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledTimes(2))
    expect(findStoredCover).toHaveBeenCalledOnce()
  })

  it.each([
    ['a file', FILE, undefined],
    ['a pending deletion', FOLDER, memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS, readonly: true })],
    ['a share guest', FOLDER, guestAccessOf(ShareRole.Read)],
  ])('%s: no intent writes anything', async (_what, node, access) => {
    const api = harness([node], access)
    for (const intent of [{ kind: 'remove' }, { kind: 'keep', crop }, { kind: 'upload', file, crop }] as const) api.save(node, intent)
    await flushPromises()
    expect(writeNodeCover).not.toHaveBeenCalled()
    expect(uploadCoverImage).not.toHaveBeenCalled()
  })
})

describe('when a write does not land', () => {
  it('a conflict on the cover re-opens the dialog on the server\'s current value and says so', async () => {
    const node = withMeta(FOLDER, { cover: upload(FOLDER.id, 'b-1') })
    declaredIds.add(node.id)
    const api = harness([node])
    vi.mocked(writeNodeCover).mockRejectedValue(new MetaConflictError({ wrappedMeta: 'cGVlcg==', metaRevision: 6 }))
    api.save(node, { kind: 'keep', crop })
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t('node.cover.stale')))
    expect(treeStore.nodes[0]).toMatchObject({ wrappedMeta: 'cGVlcg==', metaRevision: 6 })
    expect(api.editor.value?.node.id).toBe(node.id)
  })

  it('a cover another writer replaced meanwhile is said so; nothing is claimed', async () => {
    const api = harness([FOLDER])
    vi.mocked(writeNodeCover).mockRejectedValue(new CoverStaleError())
    api.save(FOLDER, { kind: 'remove' })
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t('node.cover.stale')))
    expect(treeStore.nodes[0].metaRevision).toBe(0)
  })

  it('a details blob that does not open says it cannot be changed', async () => {
    const api = harness([FOLDER])
    vi.mocked(writeNodeCover).mockRejectedValue(new MetaUnreadableError())
    api.save(FOLDER, { kind: 'remove' })
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t('node.cover.metaUnreadable')))
  })

  it('any other failure is said in general words', async () => {
    const api = harness([FOLDER])
    vi.mocked(writeNodeCover).mockRejectedValue(new Error('boom'))
    api.save(FOLDER, { kind: 'remove' })
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t('node.cover.failed')))
  })

  it.each([
    ['quota_exceeded', 'node.cover.noSpace'],
    ['chunk_put_failed:500', 'node.cover.failed'],
  ])('an upload failing with %s says %s and writes no cover', async (code, message) => {
    const api = harness([FOLDER])
    vi.mocked(uploadCoverImage).mockRejectedValue(new Error(code))
    api.save(FOLDER, { kind: 'upload', file: new File([new Uint8Array([1])], 'c.png', { type: 'image/png' }), crop })
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t(message)))
    expect(writeNodeCover).not.toHaveBeenCalled()
  })
})
