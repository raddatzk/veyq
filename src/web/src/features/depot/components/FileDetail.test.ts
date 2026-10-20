// @vitest-environment happy-dom
// An opened file as a MEMBER sees it (inbox #378): `FileDetail` mounted as the host does (`NodeDetailProps`), with the
// real catalog, the real core upload queue and the real confirm channel (answered through the real dialog). Stubbed by
// path: Depot's client (`listFiles`), the job factory (so the TARGET each pick produces is visible — its wire is
// `uploadFile.wire.test.ts`'s), the download (`fileFacts` stays real) and core's `nodeContentKey`/`loadTree`. Every control
// is a real DOM click: the header card's share, rename, move and delete reach the host as events, the download starts
// inside the click, the replace asks first and expects the blob the page read.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { FileListPage } from '../../../api/generated/depot'

const keys = vi.hoisted(() => new Map<string, Uint8Array>())
// A PDF is drawn by pdf.js, which `pdf/pdfEngine` loads: no suite of a file page loads it (`FileViewer.pdf.test.ts` has the viewer).
vi.mock('../pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../pdf/pdfEngine')>()), openPdf: vi.fn() }))
vi.mock('../api', () => ({ filesApi: vi.fn() }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
vi.mock('../download', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../download')>()), downloadFile: vi.fn(), fileChunkProvider: vi.fn(),
}))
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
  nodeContentKey: (_s: string, _e: unknown, _t: unknown, nodeId: string) => keys.get(nodeId) ?? null,
}))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})

import FileDetail from './FileDetail.vue'
import { filesApi } from '../api'
import { downloadFile, fileChunkProvider } from '../download'
import { openPdf, PdfOpenError, type PdfDocument } from '../pdf/pdfEngine'
import { PDF_BYTES, PNG_BYTES, providerOf, utf8 } from '../../../test/fileChunks'
import { fileUploadJob, StaleFileError, type FileUploadTarget } from '../uploadFile'
import { planBlob } from '../../../core/blob/transport'
import { FILE_KIND } from '../kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import type { TreeNode } from '../../../core/node/tree'
import { loadTree } from '../../../core/node/treeStore'
import { clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { formatBytes } from '../../../shared/formatBytes'
import { isFavorite, toggleFavorite } from '../../../core/favorites/favoritesStore'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { realI18n, t } from '../../../test/i18n'
import { SaveTooLarge } from '../../../shared/saveStream'
import { stubApi } from '../../../test/apiMock'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'
import { mountConfirmDialog } from '../../../test/confirmDialog'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const D = '5c09e000-0000-4000-8000-0000000000d0'
const R = '5c09e000-0000-4000-8000-0000000000e1'
const keyFor = (id: string) => {
  if (!keys.has(id)) keys.set(id, v1.randomBytes(32))
  return keys.get(id)!
}
const node = (id: string, kind: string, name: string, over: Partial<TreeNode> = {}): TreeNode => treeNode({
  id, kind, name, parentId: D, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0, ...over,
})
const MODIFIED = Date.UTC(2026, 9, 1, 12)
const DOCS = node(D, FOLDER_KIND, 'Docs', { parentId: null })
const REPORT = node(R, FILE_KIND, 'Report.pdf')
const sealedBlob = (size: number) => {
  const plan = planBlob(SCOPE, { ownerId: R, role: 'FILE_CONTENT' }, keyFor(R), new Blob([new Uint8Array(size)]), { type: 'application/pdf', name: 'Report.pdf', modifiedAt: MODIFIED })
  return { blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey, wrappedBlobMeta: plan.wrappedBlobMeta ?? null }
}
const BLOB = sealedBlob(123_456)
let listFiles: ReturnType<typeof stubApi<typeof filesApi, 'listFiles'>>['listFiles']

function mountDetail(o: { pending?: boolean; publicShares?: boolean; identityFolder?: boolean; file?: TreeNode } = {}) {
  const epochs = epochsOf(v1.randomBytes(32))
  const file = o.file ?? REPORT
  const tree = memberTreeOf(() => [DOCS, file], { scopeId: SCOPE, epochs })
  return mount(FileDetail, {
    props: {
      node: file, scopeId: SCOPE, epochs, contentKey: keyFor(file.id), children: { folders: [], byKind: new Map() },
      access: memberAccessOf({ scopeId: SCOPE, epochs, readonly: o.pending ?? false, publicShares: o.publicShares ?? true }),
      tree: { ...tree, inIdentityFolder: () => o.identityFolder ?? false },
    },
    global: { plugins: [realI18n()], directives: { tooltip: {} } },
  })
}
type Wrapper = ReturnType<typeof mountDetail>
const byLabel = (w: Wrapper, label: string) => w.find(`button[aria-label="${label}"]`)
// The viewer's own controls (full screen, a fallback's download) are `FileViewer.test.ts`'s; this counts the page's.
const ariaLabels = (w: Wrapper) => w.findAll('button').filter((b) => !b.element.closest('.file-viewer')).map((b) => b.attributes('aria-label') ?? b.text())
async function pick(w: Wrapper, name: string) {
  const input = w.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [new File(['n'], name)], configurable: true })
  await input.trigger('change')
  await flushPromises()
}
const targets = () => vi.mocked(fileUploadJob).mock.calls.map(([target, file]) => ({ target: target as FileUploadTarget, name: file.name }))

const UNKNOWN_BYTES = Uint8Array.from({ length: 300 }, (_, i) => (i * 7) % 250)
const fakePdf = (): PdfDocument => ({ numPages: 2, pageSize: vi.fn(async () => ({ width: 200, height: 100 })), render: vi.fn(() => ({ done: new Promise<boolean>(() => {}), cancel: vi.fn() })), destroy: vi.fn(async () => {}) })

beforeEach(() => {
  keyFor(R)
  listFiles = stubApi(filesApi, { listFiles: { items: [{ nodeId: R, fileBlob: BLOB }] } satisfies FileListPage }).listFiles
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
  vi.mocked(fileChunkProvider).mockReset().mockImplementation(() => providerOf(UNKNOWN_BYTES))
  vi.mocked(fileUploadJob).mockReset().mockImplementation((_t, file) => ({ name: file.name, sizeBytes: file.size, run: async () => ({ nodeId: R }), extra: { file } }))
  vi.mocked(loadTree).mockClear()
  toastError.mockClear()
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe('an opened file: the viewer on its page (inbox #412)', () => {
  it('reads the file through the member\'s door when the page opens - the scope, the file\'s key, the listed blob - and shows it ABOVE the facts', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(PNG_BYTES))
    const w = mountDetail()
    await flushPromises()
    const [scopeId, key, nodeId, blob, presign] = vi.mocked(fileChunkProvider).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, presign]).toEqual([SCOPE, R, BLOB.blobId, undefined]) // no presign override: the scope READ gate
    expect(Array.from(key)).toEqual(Array.from(keyFor(R)))
    const img = w.find('.file-viewer img')
    expect(img.attributes('alt')).toBe('Report.pdf')
    const facts = w.find('.file-detail-facts').element
    expect(img.element.compareDocumentPosition(facts) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // the download and the replace are the header's (owner decision 2026-10-14): above the viewer, no button row below it
    expect(w.find('.veyq-node-header').element.compareDocumentPosition(w.find('.file-viewer').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(w.find('.file-detail-actions').exists()).toBe(false)
  })

  it('reads nothing for a list row: only the opened page asks the source for a reader, and not before the blob is known', async () => {
    const w = mountDetail()
    expect(fileChunkProvider).not.toHaveBeenCalled() // the blob listing has not answered yet
    await flushPromises()
    expect(fileChunkProvider).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('shows a text file as text (its markup stays characters), typed by what the claim and the bytes agree on', async () => {
    const html = '<img src=x onerror="alert(1)">'
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(utf8(html)))
    const w = mountDetail({ file: node(R, FILE_KIND, 'notes.txt') })
    await flushPromises()
    expect(w.find('.file-viewer pre').element.textContent).toBe(html)
    expect(w.find('.file-viewer img').exists()).toBe(false)
  })

  it('a PDF is not rendered: the page says so and its fallback download is the page\'s own download', async () => {
    const w = mountDetail()
    await flushPromises()
    expect(w.find('.file-viewer').text()).toContain(t('depot.viewer.unsupported.format'))
    await w.find('.file-viewer button.file-viewer-download').trigger('click')
    expect(downloadFile).toHaveBeenCalledTimes(1)
    expect(vi.mocked(downloadFile).mock.calls[0][2]).toBe(R)
  })

  it('a PDF is shown on the page: pages drawn by the viewer, and a protected one offers the page\'s own download', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(PDF_BYTES))
    vi.mocked(openPdf).mockResolvedValueOnce(fakePdf())
    const w = mountDetail()
    await flushPromises()
    expect(w.findAll('.file-viewer .pdf-page')).toHaveLength(2)
    expect(w.find('.file-viewer iframe, .file-viewer object, .file-viewer embed').exists()).toBe(false)
    w.unmount()
    vi.mocked(openPdf).mockRejectedValueOnce(new PdfOpenError('password'))
    const p = mountDetail()
    await flushPromises()
    expect(p.find('.file-viewer').text()).toContain(t('depot.viewer.unsupported.pdf-password'))
    await p.find('.file-viewer button.file-viewer-download').trigger('click')
    expect(downloadFile).toHaveBeenCalledTimes(1)
  })

  it('an empty file shows no viewer', async () => {
    stubApi(filesApi, { listFiles: { items: [{ nodeId: R, fileBlob: null }] } satisfies FileListPage })
    const w = mountDetail()
    await flushPromises()
    expect(w.find('.file-viewer').exists()).toBe(false)
    expect(fileChunkProvider).not.toHaveBeenCalled()
  })

  it('a blob whose ref is not this file\'s opens nothing (the reader refuses at once) and the page stays standing', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => { throw new Error('open_refused') })
    const w = mountDetail()
    await flushPromises()
    expect(w.find('.file-viewer').exists()).toBe(false)
    expect(w.find('.file-detail-facts').exists()).toBe(true)
  })
})

describe('an opened file: its page for a member', () => {
  it('shows the name, the size, the type and the date the blob\'s own seal gives, read from the file\'s folder', async () => {
    const w = mountDetail()
    await flushPromises()
    expect(w.find('.veyq-node-header-title').text()).toBe('Report.pdf')
    const facts = w.find('.file-detail-facts').text()
    expect(facts).toContain(formatBytes(123_456, 'en'))
    expect(facts).toContain('application/pdf')
    expect(facts).toContain(new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(MODIFIED))
    expect(listFiles).toHaveBeenCalledWith({ scopeId: SCOPE, parentId: D })
  })

  it('offers everything the matrix grants a member, all in the header: download, replace, share, rename, move and delete', async () => {
    const w = mountDetail()
    await flushPromises()
    expect(ariaLabels(w)).toEqual(expect.arrayContaining([
      t('share.share'), t('node.rename'), t('node.move.action'), t('node.delete'),
      t('depot.download', { name: 'Report.pdf' }), t('depot.replace', { name: 'Report.pdf' }),
    ]))
    // owner decision 2026-10-14: the download and the replace are header actions, before the standard four; core's
    // "Copy to…" beside the move (uc.platform.copy-node)
    const ids = w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('data-action'))
    expect(ids.filter((id) => id !== 'favorite' && id !== 'export')).toEqual(['download', 'replace', 'share', 'rename', 'move', 'copy', 'delete'])
    expect(w.findAll('button').filter((b) => !b.element.closest('.veyq-node-header') && !b.element.closest('.file-viewer')).map((b) => b.attributes('aria-label')), 'no action button below the header')
      .not.toEqual(expect.arrayContaining([t('depot.download', { name: 'Report.pdf' }), t('depot.replace', { name: 'Report.pdf' })]))
  })

  it('core\'s heart is in the header, first: a real click toggles THIS file in the access\'s scope, and a pinned file shows it pressed', async () => {
    const w = mountDetail()
    await flushPromises()
    const heart = () => w.find('.veyq-node-header-actions button[data-action="favorite"]')
    expect(w.findAll('.veyq-node-header-actions button')[0].attributes('data-action')).toBe('favorite')
    expect(heart().attributes('aria-label')).toBe(t('node.favorite'))
    await heart().trigger('click')
    expect(toggleFavorite).toHaveBeenCalledWith(R, SCOPE)
    vi.mocked(isFavorite).mockImplementation((id) => id === R)
    const pinned = mountDetail()
    await flushPromises()
    const pressed = pinned.find('.veyq-node-header-actions button[data-action="favorite"]')
    expect(pressed.attributes('aria-pressed')).toBe('true')
    expect(pressed.attributes('aria-label')).toBe(t('node.unfavorite'))
    vi.mocked(isFavorite).mockReturnValue(false)
  })

  it('the card\'s buttons reach the host: share, rename, delete and move as real clicks', async () => {
    const w = mountDetail()
    await flushPromises()
    await w.find(`.veyq-node-header-actions button[aria-label="${t('share.share')}"]`).trigger('click')
    await w.find(`.veyq-node-header-actions button[aria-label="${t('node.rename')}"]`).trigger('click')
    await w.find(`.veyq-node-header-actions button[aria-label="${t('node.delete')}"]`).trigger('click')
    expect(w.emitted('share')).toHaveLength(1)
    expect(w.emitted('rename')).toHaveLength(1)
    expect(w.emitted('delete')).toHaveLength(1)
    const move = w.find('.veyq-node-header-actions button .pi-file-export')
    await move.element.closest('button')!.click()
    expect((w.emitted('move') as TreeNode[][]).map(([n]) => n.id)).toEqual([R])
  })

  it('no share without the instance\'s public links; during a pending deletion it downloads and lists links, writes nothing', async () => {
    const off = mountDetail({ publicShares: false })
    await flushPromises()
    expect(byLabel(off, t('share.share')).exists()).toBe(false)
    const pending = mountDetail({ pending: true })
    await flushPromises()
    expect(byLabel(pending, t('share.share')).exists(), 'a link list is a read').toBe(true)
    for (const label of [t('node.rename'), t('node.delete'), t('depot.replace', { name: 'Report.pdf' })]) {
      expect(byLabel(pending, label).exists(), label).toBe(false)
    }
    expect(byLabel(pending, t('depot.download', { name: 'Report.pdf' })).exists()).toBe(true)
  })

  it('a guest\'s file in an identity folder: downloaded, deleted or moved out by the member — not shared, renamed or replaced', async () => {
    const w = mountDetail({ identityFolder: true })
    await flushPromises()
    for (const label of [t('share.share'), t('node.rename'), t('depot.replace', { name: 'Report.pdf' })]) {
      expect(byLabel(w, label).exists(), label).toBe(false)
    }
    for (const label of [t('node.delete'), t('depot.download', { name: 'Report.pdf' })]) expect(byLabel(w, label).exists(), label).toBe(true)
  })

  it('downloads with a real click, inside the click: under the file\'s key as the node name; failures are said', async () => {
    const w = mountDetail()
    await flushPromises()
    void w.find('button.file-detail-download').trigger('click')
    expect(downloadFile).toHaveBeenCalledOnce() // synchronously: the save picker needs the click's activation
    const [scopeId, key, nodeId, blob, name] = vi.mocked(downloadFile).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, name]).toEqual([SCOPE, R, BLOB.blobId, 'Report.pdf'])
    expect(Array.from(key)).toEqual(Array.from(keyFor(R)))
    await flushPromises()
    vi.mocked(downloadFile).mockRejectedValueOnce(new SaveTooLarge(2 ** 33))
    await w.find('button.file-detail-download').trigger('click')
    await flushPromises()
    expect(toastError.mock.calls).toEqual([[t('depot.downloadTooLarge')]])
  })

  it('the replace asks first, naming both files; the answer yes targets this file with the blob it read', async () => {
    const dialog = mountConfirmDialog()
    const w = mountDetail()
    await flushPromises()
    await w.find('button.file-detail-replace').trigger('click')
    await pick(w, 'new.pdf')
    expect(dialog.text()).toContain(t('depot.replaceRowConfirm', { name: 'Report.pdf', file: 'new.pdf' }))
    expect(fileUploadJob, 'nothing before the answer').not.toHaveBeenCalled()
    dialog.click(t('depot.replaceAccept'))
    await flushPromises()
    const [{ target, name }] = targets()
    expect(name).toBe('new.pdf')
    expect(target.scopeId).toBe(SCOPE)
    expect(target.replace?.nodeId).toBe(R)
    expect(target.replace?.expectedBlobId).toBe(BLOB.blobId)
    expect(Array.from(target.replace!.nodeKey)).toEqual(Array.from(keyFor(R)))
    await vi.waitFor(() => expect(loadTree).toHaveBeenCalled()) // the finished replace re-reads the tree and the blob
    await vi.waitFor(() => expect(listFiles.mock.calls.length).toBeGreaterThan(1))
  })

  it('a declined question replaces nothing', async () => {
    const dialog = mountConfirmDialog()
    const w = mountDetail()
    await flushPromises()
    await pick(w, 'new.pdf')
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(fileUploadJob).not.toHaveBeenCalled()
  })

  it('a file that holds no content offers an upload into it, and asks nothing', async () => {
    listFiles = stubApi(filesApi, { listFiles: { items: [{ nodeId: R, fileBlob: null }] } satisfies FileListPage }).listFiles
    const w = mountDetail()
    await flushPromises()
    expect(w.text()).toContain(t('depot.notUploaded'))
    expect(w.find('button.file-detail-download').attributes('disabled')).toBeDefined()
    expect(byLabel(w, t('depot.uploadInto', { name: 'Report.pdf' })).exists()).toBe(true)
    await pick(w, 'first.pdf')
    expect(confirmRequest.value).toBeNull()
    expect(targets()[0].target.replace?.expectedBlobId).toBeNull()
  })

  it('after a peer\'s replace (stale_file) it says so and offers "replace anyway" on the blob the slot holds now', async () => {
    vi.mocked(fileUploadJob).mockImplementation((_t, file) => ({
      name: file.name, sizeBytes: file.size, run: async () => { throw new StaleFileError('blob-now') }, isFinal: () => true, extra: { file },
    }))
    mountConfirmDialog()
    const w = mountDetail()
    await flushPromises()
    await pick(w, 'new.pdf')
    settleConfirm(true)
    await flushPromises()
    await vi.waitFor(() => expect(w.text()).toContain(t('depot.errors.stale_file', { name: 'new.pdf' })))
    const queue = useUploadQueue().batches.value
    expect(queue[0].files[0].attempts, 'a final refusal is not retried').toBe(1)
    expect(w.text()).not.toContain(t('upload.retry'))
    // the next read of the folder names the peer's blob; "replace anyway" re-reads first, then goes without asking
    listFiles = stubApi(filesApi, { listFiles: { items: [{ nodeId: R, fileBlob: { ...BLOB, blobId: 'blob-now' } }] } satisfies FileListPage }).listFiles
    vi.mocked(fileUploadJob).mockClear()
    await w.findAll('button').find((b) => b.text() === t('depot.replaceAnyway'))!.trigger('click')
    await flushPromises()
    expect(confirmRequest.value, 'the explicit answer asks nothing').toBeNull()
    expect(targets()[0].target.replace?.expectedBlobId).toBe('blob-now')
  })
})
