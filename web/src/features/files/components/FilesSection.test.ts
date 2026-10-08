// @vitest-environment happy-dom
// Files' section of the folder level view, mounted with the real catalog, the real core upload queue and the real
// confirm channel (`useConfirm`, answered through `settleConfirm` as the mounted `ConfirmDialog` would). Stubbed by
// path: Files' client (`listFiles`, typed by the contract), the job factory (so the TARGET each pick produces is
// visible — its wire is `uploadFile.wire.test.ts`'s), the download, core's `deleteNode`/`nodeContentKey` (a fixed key
// per node instead of a key chain) and `loadTree`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { FileListPage } from '../../../api/generated/files'

const keys = vi.hoisted(() => new Map<string, Uint8Array>())
vi.mock('../api', () => ({ filesApi: vi.fn() }))
vi.mock('../download', () => ({ downloadFile: vi.fn() }))
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))
vi.mock('../uploadFile', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../uploadFile')>()),
  fileUploadJob: vi.fn(),
}))
vi.mock('../../../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../core/node/tree')>()),
  deleteNode: vi.fn(async () => {}),
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
import { sealFileMeta } from '../fileMeta'
import { FILE_KIND } from '../kinds'
import { MEMORY_KIND } from '../../memory/kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { deleteNode, type TreeNode } from '../../../core/node/tree'
import { loadTree } from '../../../core/node/treeStore'
import { enqueue, clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { formatBytes } from '../../../shared/formatBytes'
import { setViewMode } from '../../../shared/state/useViewMode'
import { activeSpace } from '../../../app/activeSpace'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { realI18n, t } from '../../../test/i18n'
import { SaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import { stubApi } from '../../../test/apiMock'
import { treeNode } from '../../../test/models'
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
const META = { mime: 'application/pdf', size: 123_456, modifiedAt: Date.UTC(2026, 9, 1, 12) }
const DOCS = node(D, FOLDER_KIND, 'Docs', { parentId: null })
const REPORT = node(R, FILE_KIND, 'Report.pdf', { wrappedMeta: sealFileMeta(keyFor(R), { scopeId: SCOPE, nodeId: R }, { v: 1, ...META }), metaVersion: 2 })
const NOTES = node(N, FILE_KIND, 'notes.txt')
const PENDING = node(P, FILE_KIND, 'pending.bin')
const MEMORY = node(M, MEMORY_KIND, 'A Memory')
const blob = (blobId: string, byteSize: number, chunkCount = 1) => ({ blobId, chunkCount, byteSize, wrappedBlobKey: 'eA==' })
const BLOBS: FileListPage = {
  items: [
    { nodeId: R, fileBlob: blob('blob-report', 123_456 + v1.FRAME_OVERHEAD) },
    { nodeId: N, fileBlob: blob('blob-notes', 3000 + 2 * v1.FRAME_OVERHEAD, 2) },
    { nodeId: P, fileBlob: null },
  ],
}
let listFiles: ReturnType<typeof stubApi<typeof filesApi, 'listFiles'>>['listFiles']

function mountSection(props: { nodes: TreeNode[]; readonly?: boolean; folder?: TreeNode | null }) {
  return mount(FilesSection, {
    props: { folder: DOCS, epochs: epochsOf(v1.randomBytes(32)), readonly: false, ...props },
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
    name: file.name, sizeBytes: file.size, run: async () => ({ nodeId: R, metaWritten: true }), extra: { file },
  }))
  vi.mocked(deleteNode).mockClear()
  vi.mocked(loadTree).mockClear()
  vi.mocked(downloadFile).mockClear()
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe('FilesSection', () => {
  it('lists this level\'s files with name, size and modified time, from their sealed meta or their blob', async () => {
    const w = mountSection({ nodes: [REPORT, NOTES, PENDING] })
    await flushPromises()
    expect(listFiles).toHaveBeenCalledWith({ scopeId: SCOPE, parentId: D })
    const rows = w.findAll('li.files-row')
    expect(rows.map((r) => r.find('.veyq-row-title').text())).toEqual(['Report.pdf', 'notes.txt', 'pending.bin'])
    expect(rows.every((r) => r.find('.pi-file').exists())).toBe(true)
    expect(w.find('.veyq-section-header span').text()).toBe('3')
    // from the meta: its size and its date
    expect(rows[0].find('.veyq-row-subtitle').text()).toContain(formatBytes(123_456, 'en'))
    expect(rows[0].find('.veyq-row-subtitle').text()).toContain('Modified')
    // no meta yet: the plaintext size the sealed size gives exactly (2 frames of overhead off), no date
    expect(rows[1].find('.veyq-row-subtitle').text()).toBe(formatBytes(3000, 'en'))
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
    const w = mountSection({ nodes: [NOTES], readonly: true })
    await flushPromises()
    expect(w.find('.files-toolbar').exists()).toBe(false)
    expect(w.findAll('input[type="file"]')).toHaveLength(0)
    expect(byLabel(w, t('files.download', { name: 'notes.txt' })).exists()).toBe(true)
    for (const action of ['files.replace', 'files.rename', 'files.delete']) {
      expect(byLabel(w, t(action, { name: 'notes.txt' })).exists()).toBe(false)
    }
  })

  it('steps aside in map mode', async () => {
    setViewMode('map')
    const w = mountSection({ nodes: [NOTES] })
    expect(w.find('section').exists()).toBe(false)
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
        replace: { nodeId: R, nodeKey: keyFor(R), meta: { wrappedMeta: REPORT.wrappedMeta, metaVersion: 2 }, expectedBlobId: 'blob-report' },
      }),
    }])
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
    expect(targets()).toEqual([{ name: 'other-name.md', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: N, expectedBlobId: 'blob-notes' }) }) }])
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
    await byLabel(w, t('files.delete', { name: 'notes.txt' })).trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, N)
    expect(loadTree).toHaveBeenCalled()
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

    listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: blob('blob-peer', 10) }] })
    await notices[0].findAll('button').find((b) => b.text() === t('files.replaceAnyway'))!.trigger('click')
    await flushPromises()
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toEqual([{ name: 'Report.pdf', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: R, expectedBlobId: 'blob-peer' }) }) }])
    expect(w.findAll('.files-notice').map((n) => n.text()).join()).not.toContain(t('files.errors.stale_file', { name: 'Report.pdf' }))
  })

  it('names a create refusal and a missing meta in their own words', async () => {
    const w = mountSection({ nodes: [] })
    const file = new File(['a'], 'a.txt')
    enqueue([
      { name: 'Taken.txt', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file } },
      { name: 'NoMeta.txt', sizeBytes: 1, run: async () => ({ nodeId: 'm', metaWritten: false }), extra: { file } },
      { name: 'Weird.txt', sizeBytes: 1, run: async () => { throw new Error('something_else') }, extra: { file } },
    ], { label: 'Docs', key: `files:${SCOPE}:${D}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0].finishedAt).not.toBeNull())
    await flushPromises()
    expect(w.findAll('.files-notice').map((n) => n.find('.files-notice-body > span').text())).toEqual([
      t('files.errors.slug_taken', { name: 'Taken.txt' }),
      t('files.metaWarning', { name: 'NoMeta.txt' }),
      t('files.errors.failed', { name: 'Weird.txt' }),
    ])
    expect(w.findAll('.files-notice button').map((b) => b.text())).toEqual([t('files.dismiss'), t('files.dismiss'), t('files.dismiss')])
  })
})
