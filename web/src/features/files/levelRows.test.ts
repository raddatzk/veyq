// @vitest-environment happy-dom
// Files' rows of the unified level list (inbox #407), as a MEMBER meets them: the real row factory beside core's real item
// and notice components (`test/levelRows.ts`), the real catalog, the real core upload queue and the real confirm channel
// (`useConfirm`, answered through `settleConfirm` as the mounted `ConfirmDialog` would). It replaces `FilesSection.test.ts`;
// every case of it is ported, its selectors renamed on purpose (a file's row is core's `veyq-level-item`, its buttons are
// found by the action id, the first-class ones on the row and the rest in the real "more" menu).
// Stubbed by path: Files' client (`listFiles`, typed by the contract), the job factory (so the TARGET each pick produces is
// visible - its wire is `uploadFile.wire.test.ts`'s), the download (its `fileFacts` stays real: a row's size and date
// are opened from the listed blob with the real crypto), core's `deleteNode`/`nodeContentKey` (a fixed key per node
// instead of a key chain) and `loadTree`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { FileListPage } from '../../api/generated/files'

const keys = vi.hoisted(() => new Map<string, Uint8Array>())
vi.mock('./api', () => ({ filesApi: vi.fn() }))
vi.mock('./download', async (importOriginal) => ({ ...(await importOriginal<typeof import('./download')>()), downloadFile: vi.fn() }))
const { toastError, showUndo } = vi.hoisted(() => ({ toastError: vi.fn(), showUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo, dismissUndo: vi.fn() }),
}))
vi.mock('./uploadFile', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./uploadFile')>()),
  fileUploadJob: vi.fn(),
}))
vi.mock('../../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../core/node/tree')>()),
  deleteNode: vi.fn(async () => {}),
  restoreNode: vi.fn(async () => {}),
  nodeContentKey: (_s: string, _e: unknown, _t: unknown, nodeId: string) => keys.get(nodeId) ?? null,
}))
vi.mock('../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}), patchNodeMeta: vi.fn() }
})

import { filesRows } from './levelRows'
import { filesApi } from './api'
import { downloadFile } from './download'
import { fileUploadJob, type FileUploadTarget } from './uploadFile'
import { planBlob } from '../../core/blob/transport'
import type { BlobMeta } from '../../core/blob/meta'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { MEMORY_KIND } from '../memory/kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import { deleteNode, restoreNode, type TreeNode } from '../../core/node/tree'
import { loadTree } from '../../core/node/treeStore'
import { enqueue, clearUploads, useUploadQueue } from '../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../shared/state/useConfirm'
import { formatBytes } from '../../shared/formatBytes'
import { activeSpace } from '../../app/activeSpace'
import { epochsOf } from '../spaces/epochsTestUtil'
import { t } from '../../test/i18n'
import { SaveCancelled, SaveTooLarge } from '../../shared/saveStream'
import { stubApi } from '../../test/apiMock'
import { treeNode, memberAccessOf, memberTreeOf } from '../../test/models'
import { mountConfirmDialog } from '../../test/confirmDialog'
import { actionDisabled, actionIdsOf, actionLabel, clickAction, mountRows, rowOf, titlesOf, type RowsHarness } from '../../test/levelRows'

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
 *  K_blob when given - the real seal, so the row opens it as it would a server's answer. */
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

// The pickers the factory builds (a multi-file one, then the replace's single one): captured as they are made.
const inputs: HTMLInputElement[] = []
const pickers = () => inputs.filter((i) => i.type === 'file')
const created = document.createElement.bind(document)

let harness: RowsHarness | null = null
/** `pending`: the scope is pending deletion (the member's `access.readonly`); `identityFolder`: the level is a guest's
 *  identity folder in a drop box (what the member's tree answers for it). `siblings`: the level's children of other
 *  kinds - in the tree (what the any-kind pre-check reads, inbox #400), not in `nodes`, as core's level view hands them. */
function mountSection({ pending = false, identityFolder = false, siblings = [], nodes, folder = DOCS }: {
  nodes: TreeNode[]; pending?: boolean; identityFolder?: boolean; folder?: TreeNode | null; siblings?: TreeNode[]
}) {
  const epochs = epochsOf(v1.randomBytes(32))
  const tree = memberTreeOf(() => [DOCS, ...nodes, ...siblings], { scopeId: SCOPE, epochs })
  harness = mountRows(filesRows, {
    folder, nodes, epochs, viewMode: 'grid', liveTick: undefined,
    access: memberAccessOf({ scopeId: SCOPE, epochs, readonly: pending }),
    tree: { ...tree, inIdentityFolder: () => identityFolder },
  })
  return harness
}
async function pick(index: number, files: File[]) {
  const input = pickers()[index]
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
}
const MULTI = 0
const SINGLE = 1
const dateOf = (ms: number) => new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(ms)
const targets = () => vi.mocked(fileUploadJob).mock.calls.map(([target, file]) => ({ target: target as FileUploadTarget, name: file.name }))
const metaOf = (h: RowsHarness, title: string) => rowOf(h.w, title).find('.veyq-level-item-meta').text()
const noticeTexts = (h: RowsHarness) => h.w.findAll('.veyq-level-notice-text').map((n) => n.text())
const dismiss = (h: RowsHarness, index: number) =>
  h.w.findAll('.veyq-level-notice')[index].findAll('button').find((b) => b.text() === t('node.dismiss'))!.trigger('click')
const fileMeta = (rest: string) => `${t('files.kindFile')} · ${rest}`

beforeEach(() => {
  inputs.length = 0
  vi.spyOn(document, 'createElement').mockImplementation(((tag: string, o?: ElementCreationOptions) => {
    const el = created(tag, o)
    if (tag === 'input') inputs.push(el as HTMLInputElement)
    return el
  }) as typeof document.createElement)
  activeSpace.scopeId = SCOPE
  keyFor(D)
  keyFor(N)
  keyFor(P)
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
  vi.mocked(document.createElement).mockRestore()
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
  harness?.w.unmount()
  harness = null
})

describe('Files rows', () => {
  // inbox #378 / #407: a row's primary click OPENS the file's own page (the host navigates); its download is a first-class
  // action of its own, so opening is a separate, real click.
  it('a row\'s primary click asks the host to open that file; it downloads nothing', async () => {
    const h = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await rowOf(h.w, 'notes.txt').find('button.veyq-level-item-main').trigger('click')
    expect(h.events.open.map((n) => n.id)).toEqual([N])
    expect(downloadFile).not.toHaveBeenCalled()
    expect(rowOf(h.w, 'notes.txt').find('button.veyq-level-item-main').attributes('aria-label')).toBe(t('files.detail.open', { name: 'notes.txt' }))
  })

  it('lists this level\'s files with name, size and modified time - from their BLOB, the node\'s own meta empty', async () => {
    const h = mountSection({ nodes: [REPORT, NOTES, PENDING] })
    await flushPromises()
    expect(listFiles).toHaveBeenCalledWith({ scopeId: SCOPE, parentId: D })
    expect(titlesOf(h.w)).toEqual(['notes.txt', 'pending.bin', 'Report.pdf']) // core's order: by name
    expect(h.w.findAll('li.veyq-level-item').every((r) => r.find('.pi-file').exists())).toBe(true)
    // the size from the blob's sealed size, the date from the blob's sealed meta
    expect(metaOf(h, 'Report.pdf')).toBe(fileMeta(`${formatBytes(123_456, 'en')} · ${t('files.modified', { date: dateOf(MODIFIED) })}`))
    // a blob without meta: the plaintext size the sealed size gives exactly (2 frames of overhead off), no date
    expect(metaOf(h, 'notes.txt')).toBe(fileMeta(formatBytes(3000 + 256 * 1024, 'en')))
    // no blob yet: said so, and nothing to download
    expect(metaOf(h, 'pending.bin')).toBe(fileMeta(t('files.notUploaded')))
    expect(await actionDisabled(h.w, 'pending.bin', 'download')).toBe(true)
    expect(await actionDisabled(h.w, 'notes.txt', 'download')).toBe(false)
  })

  // inbox #408: a peer's upload COMPLETES (its blob appears) without the tree changing; the level's live tick says so.
  it("re-reads the blob listing when the level's live tick moves, and keeps what it shows if that read fails", async () => {
    const h = mountSection({ nodes: [REPORT, PENDING] })
    await flushPromises()
    const metas = () => h.w.findAll('li.veyq-level-item').map((r) => r.find('.veyq-level-item-meta').text())
    expect(metas()[0]).toBe(fileMeta(t('files.notUploaded')))
    const calls = listFiles.mock.calls.length
    await h.setCtx({ liveTick: 1 })
    expect(listFiles.mock.calls.length).toBe(calls + 1)
    const before = metas()
    listFiles.mockRejectedValue(new TypeError('Failed to fetch'))
    await h.setCtx({ liveTick: 2 })
    expect(metas(), 'a blip in the background read does not blank the sizes').toEqual(before)
  })

  it('an empty level does not read the listing on a live tick', async () => {
    const h = mountSection({ nodes: [] })
    await flushPromises()
    await h.setCtx({ liveTick: 1 })
    expect(listFiles).not.toHaveBeenCalled()
  })

  it('draws only FILE nodes (and drop boxes), whatever else the level hands it - a Memory is not its to draw', async () => {
    const h = mountSection({ nodes: [MEMORY, NOTES] })
    await flushPromises()
    expect(titlesOf(h.w)).toEqual(['notes.txt'])
    expect(h.w.text()).not.toContain('A Memory')
    expect(h.source().row(MEMORY), 'a kind it does not serve is null: core shows the unsupported row').toBeNull()
  })

  it('an empty folder asks the server nothing at all', async () => {
    mountSection({ nodes: [] })
    await flushPromises()
    expect(listFiles).not.toHaveBeenCalled()
  })

  // inbox #406 / #407: "Upload files" is a level action registered while the level is mounted - whichever tab is shown - and
  // takes dropped files too; a drop is only ever as enabled as the action.
  describe('the level action "files.upload" (core\'s "New" menu and the level\'s drop)', () => {
    it('is registered, enabled for a member who may upload; running it opens the multi-file picker', async () => {
      const h = mountSection({ nodes: [] })
      await flushPromises()
      const handler = h.actions.get('files.upload')!
      expect(handler.enabled()).toBe(true)
      const click = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function (this: HTMLInputElement) { this.dataset.clicked = 'y' })
      handler.run()
      expect(click).toHaveBeenCalledOnce()
      expect(pickers()[MULTI].dataset.clicked).toBe('y')
      expect(pickers()[MULTI].multiple).toBe(true)
      click.mockRestore()
    })

    it('is not enabled where the matrix grants no upload (a pending deletion)', async () => {
      const h = mountSection({ nodes: [], pending: true })
      await flushPromises()
      expect(h.actions.get('files.upload')!.enabled()).toBe(false)
    })

    it('takes dropped files as an upload into this level, exactly like a picked pick', async () => {
      const h = mountSection({ nodes: [REPORT] })
      await flushPromises()
      h.actions.get('files.upload')!.drop!([new File(['a'], 'dropped.txt')])
      await flushPromises()
      expect(targets().map((x) => x.name)).toEqual(['dropped.txt'])
      expect(targets()[0].target).toMatchObject({ scopeId: SCOPE, parent: { id: D, key: keyFor(D) } })
    })

    it('a drop where the matrix grants no upload sends nothing (the handler is the second line behind core\'s enabled())', async () => {
      const h = mountSection({ nodes: [REPORT], pending: true })
      await flushPromises()
      h.actions.get('files.upload')!.drop!([new File(['a'], 'dropped.txt')])
      await flushPromises()
      expect(fileUploadJob).not.toHaveBeenCalled()
    })
  })

  it('read-only (pending deletion): download stays, no replace, rename, move or delete', async () => {
    const h = mountSection({ nodes: [NOTES], pending: true })
    await flushPromises()
    expect(await actionIdsOf(h.w, 'notes.txt')).toEqual(['download'])
  })

  // Inbox #391: a guest's identity folder takes no write, but a member removes what a guest sent - file by file, a
  // content-less one too (a guest's upload that never finished: 'not uploaded yet', nothing to download). Through the
  // delete confirmation (trash semantics); still no upload, replace or rename there.
  it('a guest\'s identity folder: delete per file, a content-less one too, move out, and nothing else', async () => {
    const h = mountSection({ nodes: [NOTES, PENDING], identityFolder: true })
    await flushPromises()
    expect(metaOf(h, 'pending.bin')).toBe(fileMeta(t('files.notUploaded')))
    expect(await actionIdsOf(h.w, 'pending.bin')).toEqual(['download', 'move', 'delete'])
    await clickAction(h.w, 'pending.bin', 'delete')
    expect(confirmRequest.value).toMatchObject({ header: t('files.deleteTitle'), message: t('files.deleteConfirm', { name: 'pending.bin' }) })
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, P)
    // …and moves it out (a move out of a drop box is a member's, inbox #358, #377 (a))
    expect(await actionIdsOf(h.w, 'notes.txt')).toContain('move')
  })

  // inbox #404: the mode the level hands in is the layout; a row exists in either.
  it('draws its rows in the grid as well as the list', async () => {
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    expect(rowOf(h.w, 'notes.txt').classes()).toContain('is-list')
  })

  it('uploading a new name enqueues one job per file into this folder, under the folder\'s key, with no question', async () => {
    mountSection({ nodes: [REPORT] })
    await flushPromises()
    await pick(MULTI, [new File(['a'], 'a.txt'), new File(['b'], 'Report pdf')]) // `Report pdf` != `Report.pdf` as a FILE name
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
    mountSection({ nodes: [REPORT] })
    await flushPromises()
    const picking = pick(MULTI, [new File(['new'], 'report.PDF', { type: 'application/pdf' })])
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

  // Inbox #400: a picked name a sibling of ANOTHER kind holds - each name folded by its own kind - is not sent and not
  // asked about: a notice names that sibling. A file of a name that only looks alike is sent.
  it('a name a folder or a Memory here holds is not sent: the notice names the sibling by its kind; the rest is uploaded', async () => {
    const AB = node('5c09e000-0000-4000-8000-0000000000e5', FOLDER_KIND, 'a.b')
    const h = mountSection({ nodes: [REPORT], siblings: [AB, MEMORY] })
    await flushPromises()
    // a folder `a.b` folds to `a-b` (a folder's slug), so a file `a-b` meets it, a file `a.b` does not (a file keeps its dot)
    await pick(MULTI, [new File(['1'], 'a-b'), new File(['2'], 'a.b'), new File(['3'], 'a-memory'), new File(['4'], 'a memory')])
    expect(confirmRequest.value).toBeNull()
    // `a memory` keeps its space as a file name, so it does not meet the Memory `A Memory` (`a-memory`); `a-memory` does
    expect(targets().map((x) => x.name)).toEqual(['a.b', 'a memory'])
    expect(noticeTexts(h)).toEqual([
      t('files.nameTakenBy', { name: 'a-b', reason: t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'a.b' }) }),
      t('files.nameTakenBy', { name: 'a-memory', reason: t('node.nameTakenBy', { kind: t('memory.aMemory'), name: 'A Memory' }) }),
    ])
    // dismissed by its own button; the other stays
    await dismiss(h, 0)
    expect(h.w.findAll('.veyq-level-notice')).toHaveLength(1)
    // a FILE sibling is still the replace question, not a refusal (the replace semantics are unchanged)
    const picking = pick(MULTI, [new File(['n'], 'report.pdf')])
    await flushPromises()
    expect(confirmRequest.value).toMatchObject({ message: t('files.replaceConfirm', { name: 'Report.pdf' }) })
    settleConfirm(false)
    await picking
  })

  it('a refusal the server answered for a name another kind holds by now names that sibling (the race backstop)', async () => {
    const SUB = node('5c09e000-0000-4000-8000-0000000000e6', FOLDER_KIND, 'Later')
    const h = mountSection({ nodes: [], siblings: [SUB] })
    await flushPromises()
    enqueue([{ name: 'later', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file: new File([''], 'later') } }], { label: 'Docs', key: `files:${SCOPE}:${D}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files[0]?.status).toBe('failed'))
    await flushPromises()
    expect(h.w.text()).toContain(t('files.nameTakenBy', { name: 'later', reason: t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Later' }) }))
  })

  it('a declined replace uploads nothing for that file', async () => {
    mountSection({ nodes: [REPORT] })
    await flushPromises()
    const picking = pick(MULTI, [new File(['new'], 'Report.pdf')])
    await flushPromises()
    settleConfirm(false)
    await picking
    expect(fileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #383 (owner decision 2026-10-08): every replace of content asks - the row's too, naming the file it replaces
  // and the one picked, and saying the old content cannot be restored. Answered through the real dialog's buttons.
  it('a row\'s replace asks first, then takes the picked file into THAT node, whatever its name', async () => {
    const dialog = mountConfirmDialog()
    const h = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await clickAction(h.w, 'notes.txt', 'replace')
    await pick(SINGLE, [new File(['x'], 'other-name.md')])
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
    const h = mountSection({ nodes: [REPORT, NOTES] })
    await flushPromises()
    await clickAction(h.w, 'notes.txt', 'replace')
    await pick(SINGLE, [new File(['x'], 'other-name.md')])
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    expect(fileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #384: a file that holds no content has nothing to replace - its row uploads into it, and a picked file of its
  // name fills it without the replace question (which says the old content is deleted).
  it('a file known to hold no content offers an upload into it, and a picked file of its name fills it without a question', async () => {
    const h = mountSection({ nodes: [PENDING, NOTES] })
    await flushPromises()
    expect(await actionLabel(h.w, 'pending.bin', 'replace')).toBe(t('files.uploadInto', { name: 'pending.bin' }))
    expect(await actionLabel(h.w, 'notes.txt', 'replace'), 'control: a file with content is replaced').toBe(t('files.replace', { name: 'notes.txt' }))

    await clickAction(h.w, 'pending.bin', 'replace')
    await pick(SINGLE, [new File(['x'], 'anything.bin')])
    await pick(MULTI, [new File(['y'], 'Pending.BIN')])
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toEqual([
      { name: 'anything.bin', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: P, expectedBlobId: null }) }) },
      { name: 'Pending.BIN', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: P, expectedBlobId: null }) }) },
    ])
  })

  it('a file whose content could not be read is not taken for empty: a picked file of its name still asks', async () => {
    listFiles.mockRejectedValue(new TypeError('Failed to fetch'))
    const h = mountSection({ nodes: [PENDING] })
    await flushPromises()
    expect(await actionLabel(h.w, 'pending.bin', 'replace')).toBe(t('files.replace', { name: 'pending.bin' }))
    const picking = pick(MULTI, [new File(['y'], 'pending.bin')])
    await flushPromises()
    expect(confirmRequest.value).toMatchObject({ message: t('files.replaceConfirm', { name: 'pending.bin' }) })
    settleConfirm(false)
    await picking
  })

  it('rename and move ask the host for core\'s dialogs with the node; download decrypts the row\'s blob under its key', async () => {
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await clickAction(h.w, 'notes.txt', 'rename')
    expect(h.events.rename).toEqual([NOTES])
    // the move, likewise the host's: core's move dialog (inbox #358)
    await clickAction(h.w, 'notes.txt', 'move')
    expect(h.events.move).toEqual([NOTES])
    await clickAction(h.w, 'notes.txt', 'download')
    expect(downloadFile).toHaveBeenCalledWith(SCOPE, keyFor(N), N, BLOBS.items[1].fileBlob, 'notes.txt')
  })

  it('download starts inside the click - nothing awaited first, the save picker needs its activation', async () => {
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    void rowOf(h.w, 'notes.txt').find('[data-action="download"]').trigger('click')
    expect(downloadFile).toHaveBeenCalledOnce() // before any microtask: the click handler called it synchronously
    await flushPromises()
  })

  it('download outcomes: too large says so, a cancelled save says nothing, any other failure says it failed', async () => {
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    const outcome = async (error: unknown) => {
      toastError.mockClear()
      vi.mocked(downloadFile).mockRejectedValueOnce(error)
      await clickAction(h.w, 'notes.txt', 'download')
      return toastError.mock.calls.map(([summary]) => summary)
    }
    expect(await outcome(new SaveTooLarge(2 ** 33))).toEqual([t('files.downloadTooLarge')])
    expect(await outcome(new SaveCancelled())).toEqual([])
    expect(await outcome(new DOMException('cancelled', 'AbortError'))).toEqual([])
    expect(await outcome(new Error('chunk_get_failed:500'))).toEqual([t('files.downloadFailed')])
  })

  it('delete asks, then moves the node to the trash through core and reloads the tree; declined, nothing happens', async () => {
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await clickAction(h.w, 'notes.txt', 'delete')
    expect(confirmRequest.value).toMatchObject({ header: t('files.deleteTitle'), acceptLabel: t('files.deleteAccept') })
    settleConfirm(false)
    await flushPromises()
    expect(deleteNode).not.toHaveBeenCalled()
    expect(showUndo).not.toHaveBeenCalled()
    await clickAction(h.w, 'notes.txt', 'delete')
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
    const h = mountSection({ nodes: [NOTES] })
    await flushPromises()
    await clickAction(h.w, 'notes.txt', 'delete')
    settleConfirm(true)
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith(t('files.deleteFailed'))
    expect(showUndo).not.toHaveBeenCalled()
  })

  it('says why a file of this folder was not uploaded; after a peer\'s replace it offers replace anyway, on the CURRENT blob', async () => {
    const h = mountSection({ nodes: [REPORT] })
    await flushPromises()
    const file = new File(['mine'], 'Report.pdf')
    enqueue([{ name: 'Report.pdf', sizeBytes: 4, run: async () => { throw new Error('stale_file') }, extra: { file, replaceNodeId: R } }], {
      label: 'Docs', key: `files:${SCOPE}:${D}`,
    })
    // another folder's failure is not this level's to explain
    enqueue([{ name: 'x', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file } }], { label: 'Other', key: `files:${SCOPE}:other` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    expect(noticeTexts(h)).toEqual([t('files.errors.stale_file', { name: 'Report.pdf' })])

    listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: { ...sealedBlob(R, 10), blobId: 'blob-peer' } }] })
    await h.w.find('.veyq-level-notice').findAll('button').find((b) => b.text() === t('files.replaceAnyway'))!.trigger('click')
    await flushPromises()
    expect(confirmRequest.value).toBeNull()
    expect(targets()).toEqual([{ name: 'Report.pdf', target: expect.objectContaining({ replace: expect.objectContaining({ nodeId: R, expectedBlobId: 'blob-peer' }) }) }])
    expect(noticeTexts(h).join()).not.toContain(t('files.errors.stale_file', { name: 'Report.pdf' }))
  })

  // Inbox #395 (F9): a failure the member made good - the same file uploaded since - is no longer explained; another
  // file's success leaves it.
  it('a failure is no longer shown once the same file has been uploaded since', async () => {
    const h = mountSection({ nodes: [] })
    const file = new File(['a'], 'x.txt')
    const key = `files:${SCOPE}:${D}`
    enqueue([{ name: 'x.txt', sizeBytes: 1, run: async () => { throw new Error('upload_failed') }, extra: { file } }], { label: 'Docs', key })
    enqueue([{ name: 'other.txt', sizeBytes: 1, run: async () => ({ nodeId: 'o' }), extra: { file } }], { label: 'Docs', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    expect(noticeTexts(h).join()).toContain(t('files.errors.failed', { name: 'x.txt' }))

    enqueue([{ name: 'x.txt', sizeBytes: 1, run: async () => ({ nodeId: 'x' }), extra: { file } }], { label: 'Docs', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.every((b) => b.finishedAt !== null)).toBe(true))
    await flushPromises()
    expect(h.w.findAll('.veyq-level-notice')).toHaveLength(0)
  })

  it('names a create refusal in its own words, and a finished upload explains nothing (no meta can be missing)', async () => {
    const h = mountSection({ nodes: [] })
    const file = new File(['a'], 'a.txt')
    enqueue([
      { name: 'Taken.txt', sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file } },
      { name: 'Done.txt', sizeBytes: 1, run: async () => ({ nodeId: 'm' }), extra: { file } },
      { name: 'Weird.txt', sizeBytes: 1, run: async () => { throw new Error('something_else') }, extra: { file } },
    ], { label: 'Docs', key: `files:${SCOPE}:${D}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0].finishedAt).not.toBeNull())
    await flushPromises()
    expect(noticeTexts(h)).toEqual([
      t('files.errors.slug_taken', { name: 'Taken.txt' }),
      t('files.errors.failed', { name: 'Weird.txt' }),
    ])
    expect(h.w.findAll('.veyq-level-notice button').map((b) => b.text())).toEqual([t('node.dismiss'), t('node.dismiss')])
  })

  // Inbox #397: a file's facts are its blob's and nothing else's.
  describe('a row\'s facts come from its blob', () => {
    it('a stale node meta is not read: the blob\'s date shows, not what the node payload says', async () => {
      const legacy = node(R, FILE_KIND, 'Report.pdf', { wrappedMeta: 'bm90IGEgbWV0YQ==', metaVersion: 7 })
      const h = mountSection({ nodes: [legacy] })
      await flushPromises()
      expect(metaOf(h, 'Report.pdf')).toBe(fileMeta(`${formatBytes(123_456, 'en')} · ${t('files.modified', { date: dateOf(MODIFIED) })}`))
    })

    it('a replace shows the new blob\'s facts at once - with the listing that names the new blob, never the old meta beside it', async () => {
      const h = mountSection({ nodes: [REPORT] })
      await flushPromises()
      const later = Date.UTC(2026, 9, 5, 9)
      listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: sealedBlob(R, 10, { type: 'image/png', name: 'x.png', modifiedAt: later }) }] })
      await pick(MULTI, [new File(['new'], 'Report.pdf')])
      settleConfirm(true)
      await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.finishedAt).not.toBeNull())
      await flushPromises()
      expect(metaOf(h, 'Report.pdf')).toBe(fileMeta(`${formatBytes(10, 'en')} · ${t('files.modified', { date: dateOf(later) })}`))
      expect(titlesOf(h.w), 'the node keeps its name').toEqual(['Report.pdf'])
    })

    it('a blob ref listed for another node shows its size but no date - the meta does not open there', async () => {
      listFiles.mockResolvedValue({ items: [{ nodeId: R, fileBlob: sealedBlob(N, 50, META) }] })
      const h = mountSection({ nodes: [REPORT] })
      await flushPromises()
      expect(metaOf(h, 'Report.pdf')).toBe(fileMeta(formatBytes(50, 'en')))
    })
  })
})

// inbox #407 (step 3): the drop boxes are rows of the same list.
describe('Drop box rows', () => {
  const BOX = node('5c09e000-0000-4000-8000-0000000000f1', DROPBOX_KIND, 'Inbox')
  const GUEST_A = node('5c09e000-0000-4000-8000-0000000000f2', FOLDER_KIND, 'Anna', { parentId: BOX.id })
  const GUEST_B = node('5c09e000-0000-4000-8000-0000000000f3', FOLDER_KIND, 'Ben', { parentId: BOX.id })

  function mountBoxes(over: { pending?: boolean; siblings?: TreeNode[] } = {}) {
    const epochs = epochsOf(v1.randomBytes(32))
    const tree = memberTreeOf(() => [DOCS, BOX, ...(over.siblings ?? [])], { scopeId: SCOPE, epochs })
    harness = mountRows(filesRows, {
      folder: DOCS, nodes: [BOX], epochs, viewMode: 'grid', liveTick: undefined,
      access: memberAccessOf({ scopeId: SCOPE, epochs, readonly: over.pending ?? false }),
      tree: { ...tree, inIdentityFolder: () => false },
    })
    return harness
  }

  it('draws the box with its icon, a meta line counting the people who have a folder in it, and opens it on a click', async () => {
    const h = mountBoxes({ siblings: [GUEST_A, GUEST_B] })
    await flushPromises()
    expect(titlesOf(h.w)).toEqual(['Inbox'])
    expect(rowOf(h.w, 'Inbox').find('.pi-inbox').exists()).toBe(true)
    expect(metaOf(h, 'Inbox')).toBe(`${t('files.dropBox.kind')} · 2 people`)
    await rowOf(h.w, 'Inbox').find('button.veyq-level-item-main').trigger('click')
    expect(h.events.open.map((n) => n.id)).toEqual([BOX.id])
    expect(listFiles, 'a level of drop boxes only reads no blob listing').not.toHaveBeenCalled()
  })

  it('an empty box says nobody yet', async () => {
    const h = mountBoxes()
    await flushPromises()
    expect(metaOf(h, 'Inbox')).toBe(`${t('files.dropBox.kind')} · Nobody yet`)
  })

  it('offers move and delete to a member, and neither during a pending deletion (fail closed)', async () => {
    const h = mountBoxes()
    await flushPromises()
    expect(await actionIdsOf(h.w, 'Inbox')).toEqual(['move', 'delete'])
    await clickAction(h.w, 'Inbox', 'move')
    expect(h.events.move).toEqual([BOX])
    await clickAction(h.w, 'Inbox', 'delete')
    expect(confirmRequest.value).toMatchObject({ header: t('files.dropBox.delete'), message: t('files.dropBox.deleteConfirm', { name: 'Inbox' }) })
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, BOX.id)
    harness?.w.unmount()
    const readOnly = mountBoxes({ pending: true })
    await flushPromises()
    expect(await actionIdsOf(readOnly.w, 'Inbox')).toEqual([])
  })
})
