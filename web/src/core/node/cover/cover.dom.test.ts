// @vitest-environment happy-dom
// A node's cover in a level, by real clicks and a real DOM (inbox #411): core's real `LevelView` with the real registry as
// `main.ts` fills it (Memories' and Files' real row factories), a folder, a Memory, a file and a drop box in one level.
//
// Pinned here: the action - `Set cover` / `Change cover` / `Remove cover`, core's, in the row's menu and in the header - is
// offered exactly where the capability function says (a writer, on the kinds that opted in; never a file, never a guest, never
// a pending deletion, never while the keys are locked), and a click runs core's flow (the picker, the upload, ONE write with the
// meta); the cover is the leading visual of the row, the tile and the header, the kind's icon the fallback when it is missing
// or fails to open; and a share guest sees the cover through the link's own reads but gets no action. The picker, the derive
// and the write are mocked at their module boundary (`pickImage`, `coverWrite` - their own tests are `coverWrite.test.ts`
// and the wire characterisation); the queue, the store, the registry and the components are real.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const toastError = vi.hoisted(() => vi.fn())
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../../favorites/favorites', () => ({ loadFavorites: vi.fn(), saveFavorites: vi.fn(), resealFavoritesUnderNewMasterKey: vi.fn() }))
vi.mock('../../../app/vault', () => ({ masterKey: vi.fn() }))
vi.mock('../../../features/files/api', () => ({ filesApi: vi.fn() }))
vi.mock('../../blob/transport', async (orig) => ({ ...(await orig<object>()), openBlob: vi.fn(), openBlobFromUrls: vi.fn() }))
vi.mock('../../sharing/guestBlob', async (orig) => ({ ...(await orig<object>()), shareChunkUrls: vi.fn(async () => ['https://store.test/c0']) }))
vi.mock('./pickImage', async (orig) => ({ ...(await orig<object>()), pickImageFile: vi.fn() }))
vi.mock('./coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))

import LevelView from '../LevelView.vue'
import { childrenOf } from '../browse'
import { FOLDER_KIND } from '../kind'
import type { TreeNode } from '../tree'
import { clearUploads } from '../../blob/queue'
import { openBlob, openBlobFromUrls } from '../../blob/transport'
import { shareChunkUrls } from '../../sharing/guestBlob'
import { pickImageFile } from './pickImage'
import { uploadCoverImage, writeNodeCover } from './coverWrite'
import { uploadedCover } from './coverModel'
import { resetCovers } from './coverStore'
import { filesApi } from '../../../features/files/api'
import { MEMORY_KIND } from '../../../features/memory/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../../features/files/kinds'
import { ShareRole } from '../../../api/generated/sharing'
import type { FileListPage } from '../../../api/generated/files'
import type { EpochKeys } from '../../../features/spaces/epochs'
import type { PublicShareHead } from '../../sharing/resolvePublicShare'
import { stubApi } from '../../../test/apiMock'
import { authenticatedCalls } from '../../../test/guestMode'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../../test/models'
import { actionIdsOf, actionLabel, clickAction, rowOf } from '../../../test/levelRows'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const KEY = v1.randomBytes(32)
const EPOCHS = {} as EpochKeys // only its presence matters: the capability asks "are the keys loaded", the tree source supplies the key

const id = (n: number) => `d0000000-0000-4000-8000-00000000000${n}`
const sealCover = (nodeId: string, blobId: string) =>
  b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId }, KEY, new TextEncoder().encode(JSON.stringify({
    v: 1, cover: uploadedCover({ blobId, chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: nodeId }),
  }))))
const node = (n: number, kind: string, name: string, parentId: string | null, wrappedMeta: string | null = null): TreeNode =>
  treeNode({ id: id(n), kind, name, parentId, wrappedMeta, metaVersion: wrappedMeta ? 4 : 0 })

const TRIPS = node(1, FOLDER_KIND, 'Trips', null)
const ROME = node(2, MEMORY_KIND, 'Rome', TRIPS.id)
const PLAN = node(3, FILE_KIND, 'Plan.pdf', TRIPS.id)
const BOX = node(4, DROPBOX_KIND, 'Inbox', TRIPS.id)
const SUB = node(5, FOLDER_KIND, 'Sub', TRIPS.id)
let TREE: TreeNode[] = []

function mountLevel(folder: TreeNode | null, o: { access?: ReturnType<typeof memberAccessOf> | ReturnType<typeof guestAccessOf> } = {}): VueWrapper {
  const tree = { ...memberTreeOf(() => TREE, { scopeId: SCOPE }), keyOf: () => KEY }
  return mount(LevelView, {
    props: { folder, level: childrenOf(TREE, folder?.id ?? null), access: o.access ?? memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), tree },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}
const headerAction = (w: VueWrapper, action: string) => w.find(`.veyq-node-header-actions [data-action="${action}"]`)
const png = () => new File([new Uint8Array([1, 2, 3])], 'cover.png', { type: 'image/png' })

let n = 0
beforeEach(() => {
  vi.clearAllMocks()
  authenticatedCalls.length = 0
  clearUploads()
  resetCovers()
  TREE = [TRIPS, ROME, PLAN, BOX, SUB]
  Object.assign(URL, { createObjectURL: vi.fn(() => `blob:cover-${++n}`), revokeObjectURL: vi.fn() })
  vi.mocked(openBlob).mockResolvedValue(new Uint8Array([1, 2, 3]))
  vi.mocked(openBlobFromUrls).mockResolvedValue(new Uint8Array([4, 5, 6]))
  vi.mocked(pickImageFile).mockResolvedValue(png())
  vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'up-1', plan: { blobId: 'new-blob', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: SUB.id } })
  vi.mocked(writeNodeCover).mockResolvedValue({ wrappedMeta: 'bmV3', metaVersion: 5, replaced: null })
  stubApi(filesApi, { listFiles: { items: [] } satisfies FileListPage })
})
afterEach(() => vi.restoreAllMocks())

describe('who is offered the cover action, and on which kinds (capability matrix, fail closed)', () => {
  it('a writer gets it on a folder, a Memory and a drop box - in the row menu - and never on a file', async () => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    for (const title of ['Sub', 'Rome', 'Inbox']) expect(await actionIdsOf(w, title), title).toContain('cover')
    expect(await actionIdsOf(w, 'Plan.pdf')).not.toContain('cover')
    expect(await actionLabel(w, 'Sub', 'cover')).toBe(t('node.cover.set'))
    expect(await actionIdsOf(w, 'Sub'), 'no cover yet, so nothing to remove').not.toContain('cover-remove')
    expect(headerAction(w, 'cover').attributes('aria-label')).toBe(t('node.cover.set')) // the folder on screen: in its header too
  })

  it('a node that has a cover says Change, and offers Remove beside it', async () => {
    TREE = [TRIPS, ROME, PLAN, BOX, node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))]
    const w = mountLevel(TRIPS)
    await flushPromises()
    expect(await actionLabel(w, 'Sub', 'cover')).toBe(t('node.cover.change'))
    expect(await actionLabel(w, 'Sub', 'cover-remove')).toBe(t('node.cover.remove'))
    expect(await actionLabel(w, 'Inbox', 'cover-remove'), 'a node without one has no Remove').toBeNull()
  })

  it.each([
    ['a pending deletion', { readonly: true }],
    ['locked keys', { epochs: null }],
  ])('%s: no cover action anywhere - a row, the header (the heart stays, it is no write)', async (_what, over) => {
    const w = mountLevel(TRIPS, { access: memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS, ...over }) })
    await flushPromises()
    for (const title of ['Sub', 'Rome', 'Inbox']) expect(await actionIdsOf(w, title), title).not.toContain('cover')
    expect(headerAction(w, 'cover').exists()).toBe(false)
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s share guest sees the level with no cover action at all', async (role) => {
    const access = guestAccessOf(role, { head: { scopeId: SCOPE } as PublicShareHead })
    const w = mountLevel(TRIPS, { access })
    await flushPromises()
    for (const title of ['Sub', 'Rome', 'Inbox']) {
      const ids = await actionIdsOf(w, title)
      expect(ids.filter((a) => a.startsWith('cover')), title).toEqual([])
    }
    expect(headerAction(w, 'cover').exists()).toBe(false)
    expect(w.find('[data-action^="cover"]').exists()).toBe(false)
    expect(pickImageFile).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })
})

describe('Set cover, by a real click: the picker, the derive and upload, ONE write', () => {
  it.each([['row', 'Sub', SUB], ['row', 'Inbox', BOX], ['row', 'Rome', ROME]])('%s %s: the flow runs for the node clicked', async (_where, title, target) => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    await clickAction(w, title, 'cover')
    if (target === ROME) {
      // a Memory in a ROW takes core's plain picker too (its own dialog is the detail header's, below)
      expect(pickImageFile).toHaveBeenCalledOnce()
    }
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())

    expect(pickImageFile).toHaveBeenCalledOnce()
    const [scopeId, nodeId, nodeKey, source, deriver, opts] = vi.mocked(uploadCoverImage).mock.calls[0]
    expect([scopeId, nodeId]).toEqual([SCOPE, target.id])
    expect(Array.from(nodeKey)).toEqual(Array.from(KEY))
    expect(source).toMatchObject({ mediaType: 'image/png', name: 'cover.png' })
    expect(deriver, 'the existing derive pipeline, its default').toBeUndefined()
    expect(opts).toMatchObject({ strict: true }) // an unreadable image is refused, never sealed as a cover
    const write = vi.mocked(writeNodeCover).mock.calls[0][0]
    expect(write).toMatchObject({ scopeId: SCOPE, nodeId: target.id, uploadId: 'up-1', surfaceConflict: false, current: { wrappedMeta: target.wrappedMeta, metaVersion: target.metaVersion } })
    expect(write.cover).toEqual(uploadedCover({ blobId: 'new-blob', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: SUB.id }))
  })

  it('the folder on screen sets its cover from its header', async () => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    await headerAction(w, 'cover').trigger('click')
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ nodeId: TRIPS.id })
  })

  it('a cancelled dialog does nothing', async () => {
    vi.mocked(pickImageFile).mockResolvedValue(null)
    const w = mountLevel(TRIPS)
    await flushPromises()
    await clickAction(w, 'Sub', 'cover')
    await flushPromises()
    expect(uploadCoverImage).not.toHaveBeenCalled()
    expect(writeNodeCover).not.toHaveBeenCalled()
  })

  it('a file that is no image, or too large, is refused with a clear message, before anything is read or uploaded', async () => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    vi.mocked(pickImageFile).mockResolvedValue(new File([new Uint8Array([1])], 'plan.pdf', { type: 'application/pdf' }))
    await clickAction(w, 'Sub', 'cover')
    await flushPromises()
    expect(toastError).toHaveBeenLastCalledWith(t('node.cover.notImage'))
    const huge = new File([new Uint8Array([1])], 'huge.png', { type: 'image/png' })
    Object.defineProperty(huge, 'size', { value: 41 * 1024 * 1024 })
    vi.mocked(pickImageFile).mockResolvedValue(huge)
    await clickAction(w, 'Sub', 'cover')
    await flushPromises()
    expect(toastError).toHaveBeenLastCalledWith(t('node.cover.tooLarge', { max: '40 MB' }))
    expect(uploadCoverImage).not.toHaveBeenCalled()
    expect(writeNodeCover).not.toHaveBeenCalled()
  })

  it('Remove cover is ONE write with no cover, for the node clicked', async () => {
    TREE = [TRIPS, ROME, PLAN, BOX, node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))]
    const w = mountLevel(TRIPS)
    await flushPromises()
    await clickAction(w, 'Sub', 'cover-remove')
    await vi.waitFor(() => expect(writeNodeCover).toHaveBeenCalledOnce())
    expect(vi.mocked(writeNodeCover).mock.calls[0][0]).toMatchObject({ nodeId: id(5), cover: null, surfaceConflict: false })
    expect(uploadCoverImage).not.toHaveBeenCalled()
  })

  it.each([
    ['chunk_put_failed:500', 'node.cover.failed'],
    ['quota_exceeded', 'node.cover.noSpace'],
    ['cover_stale', 'node.cover.stale'],
    ['cover_unreadable', 'node.cover.unreadable'],
  ])('a failure (%s) is said in words, and no write follows an upload that failed - the old cover stays', async (code, message) => {
    vi.mocked(uploadCoverImage).mockRejectedValue(new Error(code))
    const w = mountLevel(TRIPS)
    await flushPromises()
    await clickAction(w, 'Sub', 'cover')
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t(message)))
    expect(writeNodeCover).not.toHaveBeenCalled()
  })

  it('a write that fails after the upload is said too, and claims no cover', async () => {
    vi.mocked(writeNodeCover).mockRejectedValue(new Error('cover_stale'))
    const w = mountLevel(TRIPS)
    await flushPromises()
    await clickAction(w, 'Sub', 'cover')
    await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith(t('node.cover.stale')))
    expect(w.find('li.veyq-level-item .veyq-level-item-visual img').exists()).toBe(false)
  })
})

describe('the cover is the leading visual; the icon is the fallback', () => {
  const visual = (w: VueWrapper, title: string) => rowOf(w, title).find('.veyq-level-item-visual')

  it('a folder, a drop box and a Memory with a cover show its image in the row/tile; the file keeps its icon', async () => {
    TREE = [TRIPS, node(2, MEMORY_KIND, 'Rome', TRIPS.id, sealCover(id(2), 'b-2')), PLAN, node(4, DROPBOX_KIND, 'Inbox', TRIPS.id, sealCover(id(4), 'b-4')), node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))]
    const w = mountLevel(TRIPS)
    await flushPromises()
    await vi.waitFor(() => expect(visual(w, 'Sub').find('img').exists()).toBe(true))
    for (const title of ['Sub', 'Inbox', 'Rome']) {
      await vi.waitFor(() => expect(visual(w, title).find('img').attributes('src'), title).toMatch(/^blob:cover-/))
    }
    expect(visual(w, 'Plan.pdf').find('img').exists()).toBe(false)
    expect(visual(w, 'Plan.pdf').find('.pi-file').exists()).toBe(true)
  })

  it('a cover that does not open, or a node without one, is the kind\'s icon - silently', async () => {
    vi.mocked(openBlob).mockRejectedValue(new Error('offline'))
    TREE = [TRIPS, ROME, PLAN, BOX, node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))]
    const w = mountLevel(TRIPS)
    await flushPromises()
    await vi.waitFor(() => expect(openBlob).toHaveBeenCalled())
    await flushPromises()
    expect(visual(w, 'Sub').find('img').exists()).toBe(false)
    expect(visual(w, 'Sub').find('.pi-folder').exists()).toBe(true)
    expect(visual(w, 'Inbox').find('.pi-inbox').exists()).toBe(true)
  })

  it('the folder on screen shows its cover in the header, the folder icon without one', async () => {
    const withCover = node(1, FOLDER_KIND, 'Trips', null, sealCover(id(1), 'b-1'))
    TREE = [withCover, ROME, PLAN, BOX, SUB]
    const w = mountLevel(withCover)
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.veyq-node-header-cover img').exists()).toBe(true))
    const plain = mountLevel(TRIPS)
    await flushPromises()
    expect(plain.find('.veyq-node-header-cover').exists()).toBe(false)
    expect(plain.find('.veyq-node-header-icon.pi-folder').exists()).toBe(true)
  })

  it('a removed cover drops its image at once: the meta is patched, the icon returns', async () => {
    const withCover = node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))
    TREE = [TRIPS, ROME, PLAN, BOX, withCover]
    const w = mountLevel(TRIPS)
    await flushPromises()
    await vi.waitFor(() => expect(visual(w, 'Sub').find('img').exists()).toBe(true))
    const shown = visual(w, 'Sub').find('img').attributes('src')

    // the write landed: the tree now holds the node's new (cover-less) meta, as `patchNodeMeta` leaves it
    withCover.wrappedMeta = b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId: id(5) }, KEY, new TextEncoder().encode(JSON.stringify({ v: 1 }))))
    withCover.metaVersion = 5
    await w.setProps({ level: childrenOf([...TREE], TRIPS.id) })
    await vi.waitFor(() => expect(visual(w, 'Sub').find('img').exists()).toBe(false))
    expect(visual(w, 'Sub').find('.pi-folder').exists()).toBe(true)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(shown)
  })
})

describe('a share guest sees the cover through the link\'s own reads, and nothing of the member\'s world', () => {
  it('draws a folder\'s cover from the link\'s gated presign; no member presign, no authenticated client', async () => {
    TREE = [TRIPS, ROME, PLAN, BOX, node(5, FOLDER_KIND, 'Sub', TRIPS.id, sealCover(id(5), 'b-5'))]
    const access = guestAccessOf(ShareRole.Read, { head: { scopeId: SCOPE } as PublicShareHead, pwToken: 'pw-1' })
    const w = mountLevel(TRIPS, { access })
    await flushPromises()
    await vi.waitFor(() => expect(rowOf(w, 'Sub').find('.veyq-level-item-visual img').exists()).toBe(true))
    expect(shareChunkUrls).toHaveBeenCalledWith(access.shareId, 'b-5', 'pw-1')
    expect(openBlob).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })
})
