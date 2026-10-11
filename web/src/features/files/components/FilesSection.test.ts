// @vitest-environment happy-dom
// Files' section of the folder level view, mounted with the real catalog, the real core upload queue and the real
// confirm channel (`useConfirm`, answered through `settleConfirm` as the mounted `ConfirmDialog` would). Stubbed by
// path: Files' client (`listFiles`, typed by the contract), the job factory (so the TARGET each pick produces is
// visible — its wire is `uploadFile.wire.test.ts`'s), the download (its `fileFacts` stays real: a row's size and date
// are opened from the listed blob with the real crypto), core's `deleteNode`/`nodeContentKey` (a fixed key per node
// instead of a key chain) and `loadTree`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { FileListPage } from '../../../api/generated/files'

const keys = vi.hoisted(() => new Map<string, Uint8Array>())
vi.mock('../api', () => ({ filesApi: vi.fn() }))
vi.mock('../download', async (importOriginal) => ({ ...(await importOriginal<typeof import('../download')>()), downloadFile: vi.fn() }))
const { toastError, showUndo } = vi.hoisted(() => ({ toastError: vi.fn(), showUndo: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo, dismissUndo: vi.fn() }),
}))
vi.mock('../uploadFile', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../uploadFile')>()),
  fileUploadJob: vi.fn(),
}))
vi.mock('../../../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../core/node/tree')>()),
  deleteNode: vi.fn(async () => {}),
  restoreNode: vi.fn(async () => {}),
  nodeContentKey: (_s: string, _e: unknown, _t: unknown, nodeId: string) => keys.get(nodeId) ?? null,
}))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}), patchNodeMeta: vi.fn() }
})

import FilesSection from './FilesSection.vue'
import { filesApi } from '../api'
import { downloadFile } from '../download'
import { fileUploadJob, type FileUploadTarget } from '../uploadFile'
import { planBlob } from '../../../core/blob/transport'
import type { BlobMeta } from '../../../core/blob/meta'
import { FILE_KIND } from '../kinds'
import { MEMORY_KIND } from '../../memory/kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { deleteNode, restoreNode, type TreeNode } from '../../../core/node/tree'
import { loadTree } from '../../../core/node/treeStore'
import { enqueue, clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { formatBytes } from '../../../shared/formatBytes'
import { setViewMode, type ViewMode } from '../../../shared/state/useViewMode'
import { activeSpace } from '../../../app/activeSpace'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { realI18n, t } from '../../../test/i18n'
import { SaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import { stubApi } from '../../../test/apiMock'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'
import { mountConfirmDialog } from '../../../test/confirmDialog'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const D = '5c09e000-0000-4000-8000-0000000000d0'
const R = '5c09e000-0000-4000-8000-0000000000e1'
const N = '5c09e000-0000-4000-8000-0000000000e2'
const P = '5c09e000-0000-4000-8000-0000000000e3'
const M = '5c09e000-0000-4000-8000-0000000000e4'
const node = (id: string, kind: string, name: string, over: Partial<TreeNode> = {}): TreeNode => treeNode({
  id, kind, name, parentId: D, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over,
})
const keyFor = (id: string) => {
  if (!keys.has(id)) keys.set(id, v1.randomBytes(32))
  return keys.get(id)!
}
const MODIFIED = Date.UTC(2026, 9, 1, 12)
const META: BlobMeta = { type: 'application/pdf', name: 'Report.pdf', modifiedAt: MODIFIED }
const DOCS = node(D, FOLDER_KIND, 'Docs', { parentId: null })
// The node's own payload is EMPTY: since inbox #397 a file's facts are its blob's, and the list reads nothing else.
const REPORT = node(R, FILE_KIND, 'Report.pdf')
const NOTES = node(N, FILE_KIND, 'notes.txt')
const PENDING = node(P, FILE_KIND, 'pending.bin')
const MEMORY = node(M, MEMORY_KIND, 'A Memory')
/** A file's blob as `listFiles` serves it: planned for the node `ownerId` under its key, with `meta` sealed under its
 *  K_blob when given — the real seal, so the row opens it as it would a server's answer. */
const sealedBlob = (ownerId: string, size: number, meta?: BlobMeta) => {
  const plan = planBlob(SCOPE, { ownerId, role: 'FILE_CONTENT' }, keyFor(ownerId), new Blob([new Uint8Array(size)]), meta)
  return { blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey, wrappedBlobMeta: plan.wrappedBlobMeta ?? null }
}
const REPORT_BLOB = sealedBlob(R, 123_456, META)
const NOTES_BLOB = sealedBlob(N, 3000 + 256 * 1024) // two frames, no meta
const BLOBS: FileListPage = {
  items: [
    { nodeId: R, fileBlob: REPORT_BLOB },
    { nodeId: N, fileBlob: NOTES_BLOB },
    { nodeId: P, fileBlob: null },
  ],
}
let listFiles: ReturnType<typeof stubApi<typeof filesApi, 'listFiles'>>['listFiles']

/** `pending`: the scope is pending deletion (the member's `access.readonly`); `identityFolder`: the level is a guest's
 *  identity folder in a drop box (what the member's tree answers for it). */
/** `siblings`: the level's children of other kinds — in the tree (what the any-kind pre-check reads, inbox #400), not in
 *  the section's `nodes`, as core's level view hands them. */
function mountSection({ pending = false, identityFolder = false, siblings = [], ...props }: { nodes: TreeNode[]; pending?: boolean; identityFolder?: boolean; folder?: TreeNode | null; siblings?: TreeNode[]; viewMode?: ViewMode }) {
  const epochs = epochsOf(v1.randomBytes(32))
  const tree = memberTreeOf(() => [DOCS, ...props.nodes, ...siblings], { scopeId: SCOPE, epochs })
  return mount(FilesSection, {
    props: {
      folder: DOCS, epochs, viewMode: 'grid' as ViewMode, access: memberAccessOf({ scopeId: SCOPE, epochs, readonly: pending }),
      tree: { ...tree, inIdentityFolder: () => identityFolder }, ...props,
    },
    global: { plugins: [realI18n()], directives: { tooltip: {} } },
  })
}
type Wrapper = ReturnType<typeof mountSection>
const byLabel = (w: Wrapper, label: string) => w.find(`button[aria-label="${label}"]`)
async function pick(w: Wrapper, index: number, files: File[]) {
  const input = w.findAll('input[type="file"]')[index]
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  await flushPromises()
}
const dateOf = (ms: number) => new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(ms)
const targets = () => vi.mocked(fileUploadJob).mock.calls.map(([target, file]) => ({ target: target as FileUploadTarget, name: file.name }))

beforeEach(() => {
  activeSpace.scopeId = SCOPE
  keyFor(D)
  keyFor(N)
  keyFor(P)
  setViewMode('grid')
  listFiles = stubApi(filesApi, { listFiles: BLOBS }).listFiles
  vi.mocked(downloadFile).mockResolvedValue(undefined)
  vi.mocked(fileUploadJob).mockClear().mockImplementation((_target, file) => ({
    name: file.name, sizeBytes: file.size, run: async () => ({ nodeId: R }), extra: { file },
  }))
  vi.mocked(deleteNode).mockClear()
  vi.mocked(restoreNode).mockClear()
  showUndo.mockClear()
  toastError.mockClear()
  vi.mocked(loadTree).mockClear()
  vi.mocked(downloadFile).mockClear()
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe('FilesSection', () => {
  // inbox #378: a row's info button opens the file's own page (the host navigates on the section's `openNode`); the row's
  // main button stays the download, so opening is a separate, real click.
  it('a row\'s info button asks the host to open that file; the row\'s name still downloads', async () => {
    const w = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await byLabel(w, t('files.detail.open', { name: 'notes.txt' })).trigger('click')
    expect((w.emitted('openNode') as TreeNode[][]).map(([n]) => n.id)).toEqual([N])
    expect(downloadFile).not.toHaveBeenCalled()
  })

  it('lists this level\'s files with name, size and modified time — from their BLOB, the node\'s own meta empty', async () => {
    const w = mountSection({ nodes: [REPORT, NOTES, PENDING] })
    await flushPromises()
    expect(listFiles).toHaveBeenCalledWith({ scopeId: SCOPE, parentId: D })
    const rows = w.findAll('li.files-row')
    expect(rows.map((r) => r.find('.veyq-row-title').text())).toEqual(['Report.pdf', 'notes.txt', 'pending.bin'])
    expect(rows.every((r) => r.find('.pi-file').exists())).toBe(true)
    expect(w.find('.veyq-section-header span').text()).toBe('3')
    // the size from the blob's sealed size, the date from the blob's sealed meta
    expect(rows[0].find('.veyq-row-subtitle').text()).toBe(`${formatBytes(123_456, 'en')} · ${t('files.modified', { date: dateOf(MODIFIED) })}`)
    // a blob without meta: the plaintext size the sealed size gives exactly (2 frames of overhead off), no date
    expect(rows[1].find('.veyq-row-subtitle').text()).toBe(formatBytes(3000 + 256 * 1024, 'en'))
    // no blob yet: said so, and nothing to download
    expect(rows[2].find('.veyq-row-subtitle').text()).toBe(t('files.notUploaded'))
    expect(byLabel(w, t('files.download', { name: 'pending.bin' })).attributes('disabled')).toBeDefined()
    // A row's icon buttons are an icon group: one row on a phone, not the text buttons' one-column grid (inbox #382).
    expect(rows.every((r) => r.find('.veyq-action-group').classes('veyq-action-group-icons'))).toBe(true)
  })

  it('shows only FILE nodes, whatever else the level hands it', async () => {
    const w = mountSection({ nodes: [MEMORY, NOTES] })
    await flushPromises()
    expect(w.findAll('li.files-row').map((r) => r.find('.veyq-row-title').text())).toEqual(['notes.txt'])
    expect(w.text()).not.toContain('A Memory')
    expect(w.find('.veyq-section-header span').text()).toBe('1')
  })

  it('an empty folder: the empty state and the upload action, and no request at all', async () => {
    const w = mountSection({ nodes: [] })
    await flushPromises()
    expect(w.find('.veyq-empty').text()).toBe(t('files.empty'))
    expect(w.find('.files-toolbar button').text()).toBe(t('files.upload'))
    expect(listFiles).not.toHaveBeenCalled()
  })

  it('read-only (pending deletion): download stays, no upload, replace, rename or delete', async () => {
    const w = mountSection({ nodes: [NOTES], pending: true })
    await flushPromises()
    expect(w.find('.files-toolbar').exists()).toBe(false)
    expect(w.findAll('input[type="file"]')).toHaveLength(0)
    expect(byLabel(w, t('files.download', { name: 'notes.txt' })).exists()).toBe(true)
    for (const action of ['files.replace', 'files.rename', 'files.delete', 'files.move']) {
      expect(byLabel(w, t(action, { name: 'notes.txt' })).exists()).toBe(false)
    }
  })

  // Inbox #391: a guest's identity folder takes no write, but a member removes what a guest sent — file by file, a
  // content-less one too (a guest's upload that never finished: 'not uploaded yet', nothing to download). Through the
  // section's own delete confirmation (trash semantics); still no upload, replace or rename there.
  it('a guest\'s identity folder: delete per file, a content-less one too, and nothing else', async () => {
    const w = mountSection({ nodes: [NOTES, PENDING], identityFolder: true })
    await flushPromises()
    expect(w.find('.files-toolbar').exists()).toBe(false)
    expect(w.findAll('input[type="file"]')).toHaveLength(0)
    const pending = w.findAll('li.files-row')[1]
    expect(pending.find('.veyq-row-subtitle').text()).toBe(t('files.notUploaded'))
    for (const action of ['files.replace', 'files.uploadInto', 'files.rename']) {
      expect(byLabel(w, t(action, { name: 'pending.bin' })).exists()).toBe(false)
    }
    await byLabel(w, t('files.delete', { name: 'pending.bin' })).trigger('click')
    expect(confirmRequest.value).toMatchObject({ header: t('files.deleteTitle'), message: t('files.deleteConfirm', { name: 'pending.bin' }) })
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, P)
    expect(byLabel(w, t('files.delete', { name: 'notes.txt' })).exists()).toBe(true)
    // …and moves it out (a move out of a drop box is a member's, inbox #358, #377 (a))
    expect(byLabel(w, t('files.move', { name: 'notes.txt' })).exists()).toBe(true)
  })

  it('steps aside when the level hands it the map', async () => {
    const w = mountSection({ nodes: [NOTES], viewMode: 'map' })
    expect(w.find('section').exists()).toBe(false)
  })

  // inbox #404: a stored map is not the section's to read — the level hands in the mode it shows (the grid where no
  // shown section draws a map, e.g. a guest's identity folder), and the rows are there.
  it('shows its rows in the mode handed in, whatever is stored', async () => {
    setViewMode('map')
    const w = mountSection({ nodes: [NOTES], viewMode: 'grid', identityFolder: true })
    await flushPromises()
    expect(w.find('section.files-section').exists()).toBe(true)
    expect(w.findAll('.veyq-row-title').map((r) => r.text())).toEqual(['notes.txt'])
  })

  it('uploading a new name enqueues one job per file into this folder, under the folder\'s key, with no question', async () => {
    const w = mountSection({ nodes: [REPORT] })
    await flushPromises()
    await pick(w, 0, [new File(['a'], 'a.txt'), new File(['b'], 'Report pdf')]) // `Report pdf` ≠ `Report.pdf` as a FILE name
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toHaveLength(2)
    for (const { target } of targets()) {
      expect(target).toMatchObject({ scopeId: SCOPE, parent: { id: D, key: keyFor(D) } })
      expect(target.replace).toBeUndefined()
    }
    const { batches } = useUploadQueue()
    expect(batches.value.map((b) => [b.label, b.key, b.files.map((f) => f.name)])).toEqual([['Docs', `files:${SCOPE}:${D}`, ['a.txt', 'Report pdf']]])
  })

  it('a name a file already holds (folded as a file name) asks first, then replaces exactly the blob the member saw', async () => {
    const w = mountSection({ nodes: [REPORT] })
    await flushPromises()
    const picking = pick(w, 0, [new File(['new'], 'report.PDF', { type: 'application/pdf' })])
    await flushPromises()
    expect(confirmRequest.value).toMatchObject({ header: t('files.replaceTitle'), message: t('files.replaceConfirm', { name: 'Report.pdf' }) })
    // the question says what is lost (inbox #383): no version history
    expect(t('files.replaceConfirm', { name: 'x' })).toContain('cannot be restored')
    expect(fileUploadJob).not.toHaveBeenCalled() // nothing before the answer
    settleConfirm(true)
    await picking
    expect(targets()).toEqual([{
      name: 'report.PDF',
      target: expect.objectContaining({
        replace: { nodeId: R, nodeKey: keyFor(R), expectedBlobId: REPORT_BLOB.blobId },
      }),
    }])
  })

  // Inbox #400: a picked name a sibling of ANOTHER kind holds — each name folded by its own kind — is not sent and not
  // asked about: the section names that sibling. A file of a name that only looks alike is sent.
  it('a name a folder or a Memory here holds is not sent: the notice names the sibling by its kind; the rest is uploaded', async () => {
    const AB = node('5c09e000-0000-4000-8000-0000000000e5', FOLDER_KIND, 'a.b')
    const w = mountSection({ nodes: [REPORT], siblings: [AB, MEMORY] })
    await flushPromises()
    // a folder `a.b` folds to `a-b` (a folder's slug), so a file `a-b` meets it, a file `a.b` does not (a file keeps its dot)
    await pick(w, 0, [new File(['1'], 'a-b'), new File(['2'], 'a.b'), new File(['3'], 'a-memory'), new File(['4'], 'a memory')])
    expect(confirmRequest.value).toBeNull()
    // `a memory` keeps its space as a file name, so it does not meet the Memory `A Memory` (`a-memory`); `a-memory` does
    expect(targets().map((x) => x.name)).toEqual(['a.b', 'a memory'])
    const notices = w.findAll('.files-notice').map((n) => n.text())
    expect(notices).toEqual([
      expect.stringContaining(t('files.nameTakenBy', { name: 'a-b', reason: t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'a.b' }) })),
      expect.stringContaining(t('files.nameTakenBy', { name: 'a-memory', reason: t('node.nameTakenBy', { kind: t('memory.aMemory'), name: 'A Memory' }) })),
    ])
    // dismissed by its own button; the other stays
    await w.findAll('.files-notice')[0].findAll('button').find((b) => b.text() === t('files.dismiss'))!.trigger('click')
    expect(w.findAll('.files-notice')).toHaveLength(1)
    // a FILE sibling is still the replace question, not a refusal (the replace semantics are unchanged)
    const picking = pick(w, 0, [new File(['n'], 'report.pdf')])
    await flushPromises()
    expect(confirmRequest.value).toMatchObject({ message: t('files.replaceConfirm', { name: 'Report.pdf' }) })
    settleConfirm(false)
    await picking
  })

  it('a refusal the server answered for a name another kind holds by now names that sibling (the race backstop)', async () => {
    const SUB = node('5c09e000-0000-4000-8000-0000000000e6', FOLDER_KIND, 'Later')
    const w = mountSection({ nodes: [], siblings: [SUB] })
    await flushPromises()
    enqueue([{ name: 'later', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file: new File([''], 'later') } }], { label: 'Docs', key: `files:${SCOPE}:${D}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files[0]?.status).toBe('failed'))
    await flushPromises()
    expect(w.text()).toContain(t('files.nameTakenBy', { name: 'later', reason: t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Later' }) }))
  })

  it('a declined replace uploads nothing for that file', async () => {
    const w = mountSection({ nodes: [REPORT] })
    await flushPromises()
    const picking = pick(w, 0, [new File(['new'], 'Report.pdf')])
    await flushPromises()
    settleConfirm(false)
    await picking
    expect(fileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #383 (owner decision 2026-10-08): every replace of content asks — the row's too, naming the file it replaces
  // and the one picked, and saying the old content cannot be restored. Answered through the real dialog's buttons.
  it('a row\'s replace asks first, then takes the picked file into THAT node, whatever its name', async () => {
    const dialog = mountConfirmDialog()
    const w = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await byLabel(w, t('files.replace', { name: 'notes.txt' })).trigger('click')
    await pick(w, 1, [new File(['x'], 'other-name.md')])
    expect(dialog.text()).toContain(t('files.replaceTitle'))
    expect(dialog.text()).toContain(t('files.replaceRowConfirm', { name: 'notes.txt', file: 'other-name.md' }))
    expect(t('files.replaceRowConfirm', { name: 'a', file: 'b' })).toContain('cannot be restored')
    expect(fileUploadJob, 'nothing before the answer').not.toHaveBeenCalled()
    dialog.click(t('files.replaceAccept'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    expect(targets()).toEqual([{ name: 'other-name.md', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: N, expectedBlobId: NOTES_BLOB.blobId }) }) }])
  })

  it('a row\'s replace cancelled in the dialog uploads nothing', async () => {
    const dialog = mountConfirmDialog()
    const w = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await byLabel(w, t('files.replace', { name: 'notes.txt' })).trigger('click')
    await pick(w, 1, [new File(['x'], 'other-name.md')])
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    expect(fileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #384: a file that holds no content has nothing to replace — its row uploads into it, and a picked file of its
  // name fills it without the replace question (which says the old content is deleted).
  it('a file known to hold no content offers an upload into it, and a picked file of its name fills it without a question', async () => {
    const w = mountSection({ nodes: [PENDING, NOTES] })
    await flushPromises()
    expect(byLabel(w, t('files.replace', { name: 'pending.bin' })).exists()).toBe(false)
    const uploadInto = byLabel(w, t('files.uploadInto', { name: 'pending.bin' }))
    expect(uploadInto.find('.pi-upload').exists()).toBe(true)
    expect(byLabel(w, t('files.replace', { name: 'notes.txt' })).exists(), 'control: a file with content is replaced').toBe(true)

    await uploadInto.trigger('click')
    await pick(w, 1, [new File(['x'], 'anything.bin')])
    await pick(w, 0, [new File(['y'], 'Pending.BIN')])
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toEqual([
      { name: 'anything.bin', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: P, expectedBlobId: null }) }) },
      { name: 'Pending.BIN', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: P, expectedBlobId: null }) }) },
    ])
  })

  it('a file whose content could not be read is not taken for empty: a picked file of its name still asks', async () => {
    listFiles.mockRejectedValue(new TypeError('Failed to fetch'))
    const w = mountSection({ nodes: [PENDING] })
    await flushPromises()
    expect(byLabel(w, t('files.replace', { name: 'pending.bin' })).exists()).toBe(true)
    const picking = pick(w, 0, [new File(['y'], 'pending.bin')])
    await flushPromises()
    expect(confirmRequest.value).toMatchObject({ message: t('files.replaceConfirm', { name: 'pending.bin' }) })
    settleConfirm(false)
    await picking
  })

  it('rename asks the host for core\'s name dialog with the node; download decrypts the row\'s blob under its key', async () => {
    const w = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await byLabel(w, t('files.rename', { name: 'notes.txt' })).trigger('click')
    expect(w.emitted('rename')).toEqual([[NOTES]])
    // the move, likewise the host's: core's move dialog (inbox #358)
    await byLabel(w, t('files.move', { name: 'notes.txt' })).trigger('click')
    expect(w.emitted('move')).toEqual([[NOTES]])
    await w.find('button.files-row-open').trigger('click')
    await flushPromises()
    expect(downloadFile).toHaveBeenCalledWith(SCOPE, keyFor(N), N, BLOBS.items[1].fileBlob, 'notes.txt')
  })

  it('download starts inside the click — nothing awaited first, the save picker needs its activation', async () => {
    const w = mountSection({ nodes: [NOTES] })
    await flushPromises()
    void w.find('button.files-row-open').trigger('click')
    expect(downloadFile).toHaveBeenCalledOnce() // before any microtask: the click handler called it synchronously
    await flushPromises()
  })

  it('download outcomes: too large says so, a cancelled save says nothing, any other failure says it failed', async () => {
    const w = mountSection({ nodes: [NOTES] })
    await flushPromises()
    const outcome = async (error: unknown) => {
      toastError.mockClear()
      vi.mocked(downloadFile).mockRejectedValueOnce(error)
      await w.find('button.files-row-open').trigger('click')
      await flushPromises()
      return toastError.mock.calls.map(([summary]) => summary)
    }
    expect(await outcome(new SaveTooLarge(2 ** 33))).toEqual([t('files.downloadTooLarge')])
    expect(await outcome(new SaveCancelled())).toEqual([])
    expect(await outcome(new DOMException('cancelled', 'AbortError'))).toEqual([])
    expect(await outcome(new Error('chunk_get_failed:500'))).toEqual([t('files.downloadFailed')])
  })

  it('delete asks, then moves the node to the trash through core and reloads the tree; declined, nothing happens', async () => {
    const w = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await byLabel(w, t('files.delete', { name: 'notes.txt' })).trigger('click')
    expect(confirmRequest.value).toMatchObject({ header: t('files.deleteTitle'), acceptLabel: t('files.deleteAccept') })
    settleConfirm(false)
    await flushPromises()
    expect(deleteNode).not.toHaveBeenCalled()
    expect(showUndo).not.toHaveBeenCalled()
    await byLabel(w, t('files.delete', { name: 'notes.txt' })).trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, N)
    expect(loadTree).toHaveBeenCalled()
    // inbox #385 (c): said, with an undo (core's `trashUndo`, its toast clicked in `trashUndo.test.ts`) that restores the
    // node through core's trash restore and reloads the tree
    expect(showUndo).toHaveBeenCalledOnce()
    const { summary, onUndo } = showUndo.mock.calls[0][0]
    expect(summary).toBe(t('node.trash.moved', { name: 'notes.txt' }))
    vi.mocked(loadTree).mockClear()
    onUndo()
    await flushPromises()
    expect(vi.mocked(restoreNode).mock.calls.map((c) => [c[0], c[3]])).toEqual([[SCOPE, NOTES]])
    expect(loadTree).toHaveBeenCalled()
  })

  it('a failed delete says so and offers no undo', async () => {
    vi.mocked(deleteNode).mockRejectedValueOnce(new Error('boom'))
    const w = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await byLabel(w, t('files.delete', { name: 'notes.txt' })).trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith(t('files.deleteFailed'))
    expect(showUndo).not.toHaveBeenCalled()
  })

  it('says why a file of this folder was not uploaded; after a peer\'s replace it offers replace anyway, on the CURRENT blob', async () => {
    const w = mountSection({ nodes: [REPORT] })
    await flushPromises()
    const file = new File(['mine'], 'Report.pdf')
    enqueue([{ name: 'Report.pdf', sizeBytes: 4, run: async () => { throw new Error('stale_file') }, extra: { file, replaceNodeId: R } }], {
      label: 'Docs', key: `files:${SCOPE}:${D}`,
    })
    // another folder's failure is not this section's to explain
    enqueue([{ name: 'x', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file } }], { label: 'Other', key: `files:${SCOPE}:other` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    const notices = w.findAll('.files-notice')
    expect(notices).toHaveLength(1)
    expect(notices[0].text()).toContain(t('files.errors.stale_file', { name: 'Report.pdf' }))

    listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: { ...sealedBlob(R, 10), blobId: 'blob-peer' } }] })
    await notices[0].findAll('button').find((b) => b.text() === t('files.replaceAnyway'))!.trigger('click')
    await flushPromises()
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toEqual([{ name: 'Report.pdf', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: R, expectedBlobId: 'blob-peer' }) }) }])
    expect(w.findAll('.files-notice').map((n) => n.text()).join()).not.toContain(t('files.errors.stale_file', { name: 'Report.pdf' }))
  })

  // Inbox #395 (F9): a failure the member made good — the same file uploaded since — is no longer explained; another
  // file's success leaves it.
  it('a failure is no longer shown once the same file has been uploaded since', async () => {
    const w = mountSection({ nodes: [] })
    const file = new File(['a'], 'x.txt')
    const key = `files:${SCOPE}:${D}`
    enqueue([{ name: 'x.txt', sizeBytes: 1, run: async () => { throw new Error('upload_failed') }, extra: { file } }], { label: 'Docs', key })
    enqueue([{ name: 'other.txt', sizeBytes: 1, run: async () => ({ nodeId: 'o' }), extra: { file } }], { label: 'Docs', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    expect(w.findAll('.files-notice').map((n) => n.text()).join()).toContain(t('files.errors.failed', { name: 'x.txt' }))

    enqueue([{ name: 'x.txt', sizeBytes: 1, run: async () => ({ nodeId: 'x' }), extra: { file } }], { label: 'Docs', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    expect(w.findAll('.files-notice')).toHaveLength(0)
  })

  it('names a create refusal in its own words, and a finished upload explains nothing (no meta can be missing)', async () => {
    const w = mountSection({ nodes: [] })
    const file = new File(['a'], 'a.txt')
    enqueue([
      { name: 'Taken.txt', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file } },
      { name: 'Done.txt', sizeBytes: 1, run: async () => ({ nodeId: 'm' }), extra: { file } },
      { name: 'Weird.txt', sizeBytes: 1, run: async () => { throw new Error('something_else') }, extra: { file } },
    ], { label: 'Docs', key: `files:${SCOPE}:${D}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0].finishedAt).not.toBeNull())
    await flushPromises()
    expect(w.findAll('.files-notice').map((n) => n.find('.files-notice-body > span').text())).toEqual([
      t('files.errors.slug_taken', { name: 'Taken.txt' }),
      t('files.errors.failed', { name: 'Weird.txt' }),
    ])
    expect(w.findAll('.files-notice button').map((b) => b.text())).toEqual([t('files.dismiss'), t('files.dismiss')])
  })

  // Inbox #397: a file's facts are its blob's and nothing else's.
  describe('a row\'s facts come from its blob', () => {
    it('a stale node meta is not read: the blob\'s date shows, not what the node payload says', async () => {
      const legacy = node(R, FILE_KIND, 'Report.pdf', { wrappedMeta: 'bm90IGEgbWV0YQ==', metaVersion: 7 })
      const w = mountSection({ nodes: [legacy] })
      await flushPromises()
      expect(w.find('li.files-row .veyq-row-subtitle').text()).toBe(`${formatBytes(123_456, 'en')} · ${t('files.modified', { date: dateOf(MODIFIED) })}`)
    })

    it('a replace shows the new blob\'s facts at once — with the listing that names the new blob, never the old meta beside it', async () => {
      const w = mountSection({ nodes: [REPORT] })
      await flushPromises()
      const later = Date.UTC(2026, 9, 5, 9)
      listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: sealedBlob(R, 10, { type: 'image/png', name: 'x.png', modifiedAt: later }) }] })
      await pick(w, 0, [new File(['new'], 'Report.pdf')])
      settleConfirm(true)
      await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.finishedAt).not.toBeNull())
      await flushPromises()
      expect(w.find('li.files-row .veyq-row-subtitle').text()).toBe(`${formatBytes(10, 'en')} · ${t('files.modified', { date: dateOf(later) })}`)
      expect(w.find('li.files-row .veyq-row-title').text(), 'the node keeps its name').toBe('Report.pdf')
    })

    it('a blob ref listed for another node shows its size but no date — the meta does not open there', async () => {
      listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: sealedBlob(N, 50, META) }] })
      const w = mountSection({ nodes: [REPORT] })
      await flushPromises()
      expect(w.find('li.files-row .veyq-row-subtitle').text()).toBe(formatBytes(50, 'en'))
    })
  })
})
