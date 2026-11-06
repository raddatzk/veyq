// @vitest-environment happy-dom
// A SINGLE-FILE share, as its guest sees it (inbox #378, #401): core's page of a READ or READ_WRITE link
// (`ShareTreePage`, the real one — real router, real registry with Depot registered, real UI layer) rooted at a `FILE`,
// which renders FILE's registered `detail`, the member's `FileDetail`, handed the guest's access and the tree the link
// opened (`guestTreeSource` over real crypto). No guest-only page exists: the file's facts, its download and — for a
// contributor — its replace are the member view with the functions the matrix removes (`depotRights`).
//
// Nothing of the member's world is touched: every authenticated client throws (`test/guestMode.ts`), the member's
// `activeSpace` records any read, and core's member node operations and `treeStore` are stubs that must stay uncalled.
// Stubbed by path besides: the guest job factory (the TARGET a replace produces; its wire is `shareUpload.wire.test.ts`'s),
// the download (`fileFacts` stays real) and the link's chunk presign. Every control is a real DOM click on the rendered
// element, and the confirmation is answered through the real dialog.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
// A PDF is drawn by pdf.js, which `shared/pdf/pdfEngine` loads: no suite of a file page loads it (`FileViewer.pdf.test.ts` has the viewer).
vi.mock('../../../shared/pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../../../shared/pdf/pdfEngine')>()), openPdf: vi.fn() }))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
const memberReads = vi.hoisted(() => [] as string[])
vi.mock('../../../app/activeSpace', () => ({
  activeSpace: new Proxy({}, { get: (_t, name) => { memberReads.push(`activeSpace.${String(name)}`); return null } }),
}))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../../../core/node/tree', async (orig) => ({ ...(await orig<typeof import('../../../core/node/tree')>()), deleteNode: vi.fn() }))
vi.mock('../download', async (orig) => ({ ...(await orig<typeof import('../download')>()), downloadFile: vi.fn(), fileChunkProvider: vi.fn() }))
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))
vi.mock('../shareUpload', () => ({ guestFileUploadJob: vi.fn() }))
vi.mock('../../../core/sharing/guestBlob', async (orig) => ({ ...(await orig<object>()), shareChunkUrls: vi.fn(async () => ['https://store.test/0']) }))
vi.mock('../../../core/sharing/guestTree', async (orig) => ({
  ...(await orig<typeof import('../../../core/sharing/guestTree')>()),
  guestTreeContext: vi.fn((head: unknown, o: object) => ({ head, ...o })),
}))

import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import { publicShareFilesApi } from '../api'
import { downloadFile, fileChunkProvider } from '../download'
import { openPdf, PdfOpenError, type PdfDocument } from '../../../shared/pdf/pdfEngine'
import { PDF_BYTES, PNG_BYTES, providerOf } from '../../../test/fileChunks'
import { guestFileUploadJob, type GuestFileTarget } from '../shareUpload'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { planBlob } from '../../../core/blob/transport'
import { deleteNode } from '../../../core/node/tree'
import { loadTree } from '../../../core/node/treeStore'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { identityNameContext, type GuestIdentity } from '../../../core/sharing/guestIdentity'
import { clearUploads } from '../../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { formatBytes } from '../../../shared/formatBytes'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import type { ShareFileListing } from '../../../api/generated/depot'
import { FILE_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { guestAccessFor } from '../../../test/models'
import { authenticatedCalls } from '../../../test/guestMode'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_6'
const FILE = '5c09e000-0000-4000-8000-0000000000f1'
const PW = 'cHc='
const K_SHARE = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`
const fileKey = v1.randomBytes(32)
const MODIFIED = Date.UTC(2026, 9, 1, 12)
const META = { type: 'application/pdf', name: 'Report.pdf', modifiedAt: MODIFIED }
const PLAN = planBlob(SCOPE, { ownerId: FILE, role: 'FILE_CONTENT' }, fileKey, new Blob([new Uint8Array(123_456)]), META)
const BLOB = { blobId: PLAN.blobId, chunkCount: PLAN.chunkCount, byteSize: PLAN.byteSize, wrappedBlobKey: PLAN.wrappedBlobKey, wrappedBlobMeta: PLAN.wrappedBlobMeta }
const GUEST: GuestIdentity = { identityId: 'id-1', contributorToken: 'tok-1' }

/** A link whose root is the file itself: the served subtree is that one node, its key wrapped under `K_share`. The
 *  root's name is the link's own sealed name (`sealedName`), as a folder link's root is. */
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name: 'Report.pdf', noteByLocale: {}, role, rootKind: FILE_KIND,
    share: {
      role, scopeId: SCOPE, rootKind: FILE_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, fileKey)),
      nodes: [{ id: FILE, parentId: null, kind: FILE_KIND, epoch: 0, encryptedName: undefined, wrappedContentKey: undefined }],
    } as unknown as PublicShare,
  }
}

let router: Router
let listShareFiles: ReturnType<typeof stubApi<typeof publicShareFilesApi, 'listShareFiles'>>['listShareFiles']
async function mountPage(role: ShareRole, o: { requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void> } = {}) {
  const h0 = head(role)
  await router.push(`/s/${SHARE}${HASH}`)
  const w = mount(ShareTreePage, {
    props: {
      shareId: SHARE, fragmentKey: b64url(K_SHARE), pwToken: PW, head: h0, reload: o.reload ?? (async () => {}),
      access: guestAccessFor(h0, { pwToken: PW, requestGuest: o.requestGuest ?? (async () => GUEST), reload: o.reload }),
    },
    global: { plugins: [router, realI18n()] },
  })
  await flushPromises()
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>
// The viewer's own controls are `FileViewer.test.ts`'s; this counts the page's.
const labels = (w: Page) => w.findAll('button').filter((b) => !b.element.closest('.file-viewer')).map((b) => b.attributes('aria-label') ?? b.text())
const downloadButton = (w: Page) => w.find('button.file-detail-download')
/** Every icon a button of the page draws (the layer's icon names). */
const buttonIcons = (w: Page) => w.findAllComponents(VeyqButton).map((b) => b.props('icon'))
/** Pick `name` into the page's one replace input (the real change event). */
async function pick(w: Page, name: string) {
  await w.find('button.file-detail-replace').trigger('click')
  const input = w.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [new File(['n'], name)], configurable: true })
  await input.trigger('change')
  await flushPromises()
}
/** Nothing of the member's world was touched (see the header). */
function expectNoMemberWorld() {
  expect(authenticatedCalls, 'no authenticated client').toEqual([])
  expect(memberReads, "the member's activeSpace is not read").toEqual([])
  expect(loadTree, "the member's tree is not reloaded").not.toHaveBeenCalled()
  expect(deleteNode, 'no member node operation').not.toHaveBeenCalled()
}

const UNKNOWN_BYTES = Uint8Array.from({ length: 300 }, (_, i) => (i * 7) % 250)
const fakePdf = (): PdfDocument => ({ numPages: 2, pageSize: vi.fn(async () => ({ width: 200, height: 100 })), render: vi.fn(() => ({ done: new Promise<boolean>(() => {}), cancel: vi.fn() })), destroy: vi.fn(async () => {}) })

beforeEach(() => {
  authenticatedCalls.length = 0
  memberReads.length = 0
  vi.mocked(loadTree).mockClear()
  vi.mocked(deleteNode).mockClear()
  listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: { items: [{ nodeId: FILE, fileBlob: BLOB }] } satisfies ShareFileListing }).listShareFiles
  vi.mocked(guestFileUploadJob).mockReset().mockImplementation((_t, file) => ({ name: file.name, sizeBytes: 1, run: vi.fn(async () => ({ nodeId: FILE })), extra: { file } }))
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
  vi.mocked(fileChunkProvider).mockReset().mockImplementation(() => providerOf(UNKNOWN_BYTES))
  toastError.mockClear()
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe.each([ShareRole.Read, ShareRole.ReadWrite])('the viewer for a %s guest of a single-file link (inbox #412)', (role) => {
  it('opens the file through the LINK (the share\'s presign with the password token) and shows it, with no authenticated call', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(PNG_BYTES))
    const w = await mountPage(role)
    const [scopeId, key, nodeId, blob, presign] = vi.mocked(fileChunkProvider).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId]).toEqual([SCOPE, FILE, BLOB.blobId])
    expect(Array.from(key)).toEqual(Array.from(fileKey))
    await presign!(BLOB.blobId)
    expect(shareChunkUrls).toHaveBeenCalledWith(expect.objectContaining({ shareId: SHARE, apiBase: '' }), BLOB.blobId, PW) // the link where it lives (ADR 0121)
    const img = w.find('.file-viewer img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('alt')).toBe('Report.pdf')
    expect(w.find('.file-viewer-fullscreen').exists()).toBe(true) // the same controls as the member's
    expectNoMemberWorld()
  })

  it('a PDF is shown on the page, drawn by the same viewer as the member\'s (no frame, no link)', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(PDF_BYTES))
    vi.mocked(openPdf).mockResolvedValue(fakePdf())
    const w = await mountPage(role)
    expect(w.find('.file-viewer .pdf-viewer').exists()).toBe(true)
    expect(w.findAll('.file-viewer .pdf-page')).toHaveLength(2)
    expect(w.find('.file-viewer iframe, .file-viewer object, .file-viewer embed').exists()).toBe(false)
    expect(w.find('.file-viewer-fullscreen').exists()).toBe(true)
    expectNoMemberWorld()
  })

  it('a password-protected PDF offers the guest\'s download instead', async () => {
    vi.mocked(fileChunkProvider).mockImplementation(() => providerOf(PDF_BYTES))
    vi.mocked(openPdf).mockRejectedValue(new PdfOpenError('password'))
    const w = await mountPage(role)
    expect(w.find('.file-viewer').text()).toContain(t('depot.viewer.unsupported.pdf-password'))
    await w.find('.file-viewer button.file-viewer-download').trigger('click')
    await flushPromises()
    expect(downloadFile).toHaveBeenCalledTimes(1)
    expectNoMemberWorld()
  })

  it('a file the viewer cannot show offers the guest\'s download (the matrix grants it to both roles)', async () => {
    const w = await mountPage(role)
    expect(w.find('.file-viewer').text()).toContain(t('depot.viewer.unsupported.format'))
    await w.find('.file-viewer button.file-viewer-download').trigger('click')
    await flushPromises()
    expect(downloadFile).toHaveBeenCalledTimes(1)
    expectNoMemberWorld()
  })
})

// ADR 0083 Decision 8, inbox #190: the uploader is a member's to see. The guest's source has no uploader door and the link's
// listing serves none, so the page names nobody - and asks no member directory for a name (every authenticated client
// would be recorded). The control is the rest of the header: the page is there, with its title and facts.
describe.each([ShareRole.Read, ShareRole.ReadWrite])('a %s guest of a single-file link is shown no uploader', (role) => {
  it('the header names nobody and the member directory is never asked', async () => {
    const w = await mountPage(role)
    expect(w.find('.veyq-node-header h1').text()).toBe('Report.pdf') // control: the header is drawn
    expect(w.find('.file-detail-facts').exists()).toBe(true)
    expect(w.find('.veyq-node-header [data-fact="people"]').exists(), 'no people in core\'s header').toBe(false)
    expect(w.text()).not.toContain(t('depot.detail.uploadedBy'))
    expectNoMemberWorld()
  })

  // inbox #431: a guest's name rides the MEMBER's listing only. Were the link's listing ever to carry one - sealed under the
  // file's key, which this guest holds, so it would open - the guest's page still reads none: its source has no such door.
  it('a link listing that carried an uploader and a sealed name still names nobody', async () => {
    const identityId = 'cccccccc-0000-4000-8000-000000000004'
    const name = b64std(v1.sealField(identityNameContext(SCOPE, SHARE, identityId), fileKey, v1.utf8('Ben Gast')))
    listShareFiles = stubApi(publicShareFilesApi, {
      listShareFiles: {
        items: [{ nodeId: FILE, fileBlob: BLOB, uploaderKey: `share:${identityId}`, uploaderName: { sealedName: name, shareId: SHARE } }],
      } as unknown as ShareFileListing,
    }).listShareFiles
    const w = await mountPage(role)
    expect(w.find('.file-detail-facts').exists()).toBe(true) // control: the listing was read and the page drawn
    expect(w.find('.veyq-node-header [data-fact="people"]').exists(), 'no people in core\'s header').toBe(false)
    expect(w.text()).not.toContain('Ben Gast')
    expectNoMemberWorld()
  })
})

describe('the guest of a single-file link sees the member\'s file view', () => {
  it('renders FILE\'s detail at the link\'s root: the file\'s name, size, type and date opened under its key, read through the link only', async () => {
    const w = await mountPage(ShareRole.Read)
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.veyq-node-header-title').text()).toBe('Report.pdf')
    const facts = w.find('.file-detail-facts').text()
    expect(facts).toContain(formatBytes(123_456, 'en')) // the blob's sealed size, less every frame's overhead
    expect(facts).toContain('application/pdf') // the blob's sealed meta
    expect(facts).toContain(new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(MODIFIED))
    expect(listShareFiles).toHaveBeenCalledWith({ shareId: SHARE, xSharePassword: PW })
    expectNoMemberWorld()
  })

  it('a READ guest gets the download only: no replace, rename, share, move or delete, and no contributor hint', async () => {
    const w = await mountPage(ShareRole.Read)
    expect(labels(w)).toEqual([t('depot.download', { name: 'Report.pdf' })])
    expect(w.find('input[type="file"]').exists()).toBe(false)
    for (const icon of ['pencil', 'trash', 'share-alt', 'file-export', 'sync']) expect(buttonIcons(w), icon).not.toContain(icon)
    // The name is plain text in the h1 for a guest: no rename in place (ADR 0110 decision 11).
    expect(w.find('h1.veyq-node-header-title').text()).toBe('Report.pdf')
    expect(w.find('h1.veyq-node-header-title button').exists()).toBe(false)
    expectNoMemberWorld()
  })

  it('downloads through the link with a real click: the presign is the share\'s, with the password token', async () => {
    const w = await mountPage(ShareRole.Read)
    await downloadButton(w).trigger('click')
    await flushPromises()
    const [scopeId, key, nodeId, blob, name, presign] = vi.mocked(downloadFile).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, name]).toEqual([SCOPE, FILE, BLOB.blobId, 'Report.pdf'])
    expect(Array.from(key)).toEqual(Array.from(fileKey))
    await presign!(BLOB.blobId)
    expect(shareChunkUrls).toHaveBeenCalledWith(expect.objectContaining({ shareId: SHARE, apiBase: '' }), BLOB.blobId, PW) // the link where it lives (ADR 0121)
    expectNoMemberWorld()
  })

  it('a download too large says so; a failed one says it failed', async () => {
    const { SaveTooLarge } = await import('../../../shared/saveStream')
    const w = await mountPage(ShareRole.Read)
    vi.mocked(downloadFile).mockRejectedValueOnce(new SaveTooLarge(2 ** 33))
    await downloadButton(w).trigger('click')
    await flushPromises()
    expect(toastError.mock.calls).toEqual([[t('depot.downloadTooLarge')]])
    toastError.mockClear()
    vi.mocked(downloadFile).mockRejectedValueOnce(new Error('boom'))
    await downloadButton(w).trigger('click')
    await flushPromises()
    expect(toastError.mock.calls).toEqual([[t('depot.downloadFailed')]])
  })

  // Owner decision 2026-10-14: the replace stays on the opened file (the one way a single-file READ_WRITE link writes), as a
  // header action beside the download; no standing sentence says what it does - the question does, when it happens.
  it('a READ_WRITE guest may also replace the content from the header — no standing sentence; asked on the pick, then named', async () => {
    const dialog = mountConfirmDialog()
    const requestGuest = vi.fn(async () => GUEST)
    const reload = vi.fn(async () => {})
    const w = await mountPage(ShareRole.ReadWrite, { requestGuest, reload })
    expect(w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('data-action'))).toEqual(['download', 'replace'])
    expect(w.find('h1.veyq-node-header-title').text(), 'a writing guest renames nothing either').toBe('Report.pdf')
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(false)
    expect(w.find('.file-detail .veyq-form-copy').exists(), 'no standing sentence about the replace').toBe(false)
    expect(w.text()).not.toMatch(/owner too|cannot be restored/)
    for (const icon of ['pencil', 'trash', 'share-alt', 'file-export']) expect(buttonIcons(w), icon).not.toContain(icon)

    await pick(w, 'new.pdf')
    expect(dialog.text()).toContain(t('depot.guest.replaceFileConfirm', { name: 'Report.pdf', file: 'new.pdf' }))
    expect(guestFileUploadJob, 'nothing before the answer').not.toHaveBeenCalled()
    expect(requestGuest, 'no guest is named before the answer').not.toHaveBeenCalled()
    dialog.click(t('depot.replaceAccept'))
    await flushPromises()

    expect(requestGuest).toHaveBeenCalledOnce()
    const target = vi.mocked(guestFileUploadJob).mock.calls[0][0] as GuestFileTarget
    expect(target.replace?.nodeId).toBe(FILE)
    expect(Array.from(target.replace!.nodeKey)).toEqual(Array.from(fileKey))
    expect(target.replace!.expectedBlobId, 'the blob the guest saw').toBe(BLOB.blobId)
    expect((target.ctx as unknown as { contributorToken: string }).contributorToken).toBe('tok-1')
    await vi.waitFor(() => expect(reload).toHaveBeenCalled()) // the write re-resolves the link
    expectNoMemberWorld()
  })

  it('a declined question sends nothing; a visitor who declines to name itself sends nothing', async () => {
    const dialog = mountConfirmDialog()
    const w = await mountPage(ShareRole.ReadWrite)
    await pick(w, 'new.pdf')
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(guestFileUploadJob).not.toHaveBeenCalled()

    const shy = await mountPage(ShareRole.ReadWrite, { requestGuest: async () => null })
    await pick(shy, 'new.pdf')
    dialog.click(t('depot.replaceAccept'))
    await flushPromises()
    expect(guestFileUploadJob).not.toHaveBeenCalled()
  })

  it('says so when the file holds no content yet: nothing to download, nothing to lose by an upload (no question)', async () => {
    listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: { items: [{ nodeId: FILE, fileBlob: null }] } satisfies ShareFileListing }).listShareFiles
    const w = await mountPage(ShareRole.ReadWrite)
    expect(w.text()).toContain(t('depot.notUploaded'))
    expect(downloadButton(w).attributes('disabled')).toBeDefined()
    await pick(w, 'first.pdf')
    expect(confirmRequest.value, 'an empty file asks nothing').toBeNull()
    const target = vi.mocked(guestFileUploadJob).mock.calls[0][0] as GuestFileTarget
    expect(target.replace?.expectedBlobId).toBeNull()
  })
})
