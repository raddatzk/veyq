// @vitest-environment happy-dom
// Files' section of a public folder share (cr-files-application F2), mounted with the real catalog and the real core
// upload queue. Stubbed by path: Files' guest client (`listShareFiles`, typed by the contract), the guest job factory
// (so the TARGET each pick produces is visible — its wire is `shareUpload.wire.test.ts`'s), the download (its `fileFacts`
// stays real: a row's size and date open from the listed blob with the real crypto) and core's
// `shareChunkUrls` (so the download's presign is visible as the link's), and core's `guestTreeContext`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { ShareFileListing } from '../../../api/generated/files'
import type { ShareNode } from '../../../api/generated/sharing'

vi.mock('../api', () => ({ publicShareFilesApi: vi.fn() }))
vi.mock('../download', async (importOriginal) => ({ ...(await importOriginal<typeof import('../download')>()), downloadFile: vi.fn() }))
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))
vi.mock('../shareUpload', () => ({ guestFileUploadJob: vi.fn() }))
vi.mock('../../../core/sharing/guestBlob', () => ({ shareChunkUrls: vi.fn(async () => ['https://store.test/0']) }))
vi.mock('../../../core/sharing/guestTree', () => ({ guestTreeContext: vi.fn((head: unknown, o: object) => ({ head, rootId: ROOT, keys: KEYS, ...o })) }))

import ShareFilesSection from './ShareFilesSection.vue'
import { publicShareFilesApi } from '../api'
import { downloadFile } from '../download'
import { guestFileUploadJob, type GuestFileTarget } from '../shareUpload'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { planBlob } from '../../../core/blob/transport'
import { FILE_KIND } from '../kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { enqueue, clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { formatBytes } from '../../../shared/formatBytes'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import type { GuestIdentity } from '../../../core/sharing/guestIdentity'
import { realI18n, t } from '../../../test/i18n'
import { SaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import { stubApi } from '../../../test/apiMock'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_5'
const ROOT = '5c09e000-0000-4000-8000-0000000000c0'
const R = '5c09e000-0000-4000-8000-0000000000c1'
const P = '5c09e000-0000-4000-8000-0000000000c2'
const F = '5c09e000-0000-4000-8000-0000000000c3'
const PW = 'cHc='
const KEYS = new Map<string, Uint8Array>([[ROOT, v1.randomBytes(32)], [R, v1.randomBytes(32)], [P, v1.randomBytes(32)], [F, v1.randomBytes(32)]])
const MODIFIED = Date.UTC(2026, 9, 1, 12)
const META = { type: 'application/pdf', name: 'Report.pdf', modifiedAt: MODIFIED }
const fileNode = (id: string, over: Partial<ShareNode> = {}): ShareNode => ({ id, parentId: ROOT, kind: FILE_KIND, epoch: 0, placementVersion: 0, ...over })
// The node's own payload is empty: a file's facts are its blob's (inbox #397).
const REPORT = fileNode(R)
/** The report's blob as `listShareFiles` serves it: planned under the file's key, its meta sealed under its K_blob. */
const REPORT_PLAN = planBlob(SCOPE, { ownerId: R, role: 'FILE_CONTENT' }, KEYS.get(R)!, new Blob([new Uint8Array(123_456)]), META)
const REPORT_BLOB = {
  blobId: REPORT_PLAN.blobId, chunkCount: REPORT_PLAN.chunkCount, byteSize: REPORT_PLAN.byteSize,
  wrappedBlobKey: REPORT_PLAN.wrappedBlobKey, wrappedBlobMeta: REPORT_PLAN.wrappedBlobMeta,
}
const PENDING = fileNode(P)
const SUBFOLDER: ShareNode = { id: F, parentId: ROOT, kind: FOLDER_KIND, epoch: 0 }
const NAMES = new Map([[R, 'Report.pdf'], [P, 'aaa.bin'], [F, 'Sub']])
const LISTING: ShareFileListing = { items: [{ nodeId: R, fileBlob: REPORT_BLOB }, { nodeId: P, fileBlob: null }] }
const HEAD = { shareId: SHARE, scopeId: SCOPE, name: 'Familie' } as PublicShareHead
const GUEST: GuestIdentity = { identityId: 'id-1', contributorToken: 'tok-1' }

let listShareFiles: ReturnType<typeof stubApi<typeof publicShareFilesApi, 'listShareFiles'>>['listShareFiles']

function mountSection(over: { canWrite?: boolean; requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void> } = {}) {
  return mount(ShareFilesSection, {
    props: {
      shareId: SHARE, fragmentKey: 'frag', pwToken: PW, head: HEAD, reload: over.reload ?? (async () => {}),
      folderId: ROOT, nodes: [REPORT, PENDING, SUBFOLDER], keys: KEYS, names: NAMES,
      canWrite: over.canWrite ?? false, requestGuest: over.requestGuest ?? (async () => GUEST),
    },
    global: { plugins: [realI18n()] },
  })
}
const rowTitles = (w: ReturnType<typeof mountSection>) => w.findAll('.veyq-row-title').map((r) => r.text())
/** Click the row's replace of `name`, then pick `picked` in its file input. */
async function pickReplace(w: ReturnType<typeof mountSection>, name: string, picked: string) {
  await w.find(`[aria-label="${t('files.replace', { name })}"]`).trigger('click')
  const single = w.find('input[type="file"]:not([multiple])')
  Object.defineProperty(single.element, 'files', { value: [new File(['n'], picked)], configurable: true })
  await single.trigger('change')
  await flushPromises()
}
const fakeJob = (name: string) => ({ name, sizeBytes: 1, run: vi.fn(async () => ({ nodeId: 'n' })), extra: { file: new File([''], name) } })

beforeEach(() => {
  listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: LISTING }).listShareFiles
  vi.mocked(guestFileUploadJob).mockReset().mockImplementation((_t, file) => fakeJob(file.name))
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe('a guest reads the files of a shared folder', () => {
  it('lists the FILE children by name with their sealed size and date, never another kind; reads the blobs through the link', async () => {
    const w = mountSection()
    await flushPromises()
    expect(rowTitles(w)).toEqual(['aaa.bin', 'Report.pdf'])
    const dated = t('files.modified', { date: new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(MODIFIED) })
    expect(w.text()).toContain(`${formatBytes(123_456, 'en')} · ${dated}`) // from the blob: its sealed size, its sealed meta
    expect(w.text()).toContain(t('files.notUploaded'))
    expect(listShareFiles).toHaveBeenCalledWith({ shareId: SHARE, xSharePassword: PW })
  })

  it('a READ guest gets no upload and no replace', async () => {
    const w = mountSection()
    await flushPromises()
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(w.findAll('button').map((b) => b.attributes('aria-label'))).toEqual([t('files.download', { name: 'aaa.bin' }), t('files.download', { name: 'Report.pdf' })])
    expect(w.text()).not.toContain(t('files.guest.contributorHint'))
  })

  it('a contributor is told before uploading that it may replace any file, the owner\'s too, with no version kept (inbox #383)', async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    expect(w.text()).toContain(t('files.guest.contributorHint'))
  })

  it('downloads through the link: the presign is the share\'s, with the password token', async () => {
    const w = mountSection()
    await flushPromises()
    await w.find(`[aria-label="${t('files.download', { name: 'Report.pdf' })}"]`).trigger('click')
    await flushPromises()
    const [scopeId, key, nodeId, blob, name, presign] = vi.mocked(downloadFile).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, name]).toEqual([SCOPE, R, REPORT_BLOB.blobId, 'Report.pdf'])
    expect(Array.from(key)).toEqual(Array.from(KEYS.get(R)!))
    await presign!(REPORT_BLOB.blobId)
    expect(shareChunkUrls).toHaveBeenCalledWith(SHARE, REPORT_BLOB.blobId, PW)
  })

  it('a guest\'s download starts inside the click; too large says so, a cancelled save says nothing', async () => {
    const w = mountSection()
    await flushPromises()
    const button = () => w.find(`[aria-label="${t('files.download', { name: 'Report.pdf' })}"]`)
    vi.mocked(downloadFile).mockRejectedValueOnce(new SaveTooLarge(2 ** 33))
    void button().trigger('click')
    expect(downloadFile).toHaveBeenCalledOnce() // synchronously in the click: the save picker needs its activation
    await flushPromises()
    expect(toastError.mock.calls).toEqual([[t('files.downloadTooLarge')]])
    toastError.mockClear()
    vi.mocked(downloadFile).mockRejectedValueOnce(new SaveCancelled())
    await button().trigger('click')
    await flushPromises()
    expect(toastError).not.toHaveBeenCalled()
  })

  it('an empty folder says so', async () => {
    const w = mount(ShareFilesSection, {
      props: { shareId: SHARE, fragmentKey: 'f', pwToken: undefined, head: HEAD, reload: async () => {}, folderId: ROOT, nodes: [], keys: KEYS, names: NAMES, canWrite: false, requestGuest: async () => null },
      global: { plugins: [realI18n()] },
    })
    await flushPromises()
    expect(w.find('.veyq-empty').text()).toBe(t('files.guest.empty'))
  })
})

describe('a contributor uploads into the shared folder', () => {
  it('asks for the guest, then enqueues one job per picked file into the folder on screen', async () => {
    const requestGuest = vi.fn(async () => GUEST)
    const w = mountSection({ canWrite: true, requestGuest })
    await flushPromises()
    const input = w.find('input[type="file"][multiple]')
    Object.defineProperty(input.element, 'files', { value: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(requestGuest).toHaveBeenCalledOnce()
    const targets = vi.mocked(guestFileUploadJob).mock.calls.map(([target]) => target as GuestFileTarget)
    expect(targets.map((t0) => [t0.parentId, t0.replace, (t0.ctx as unknown as { contributorToken: string }).contributorToken]))
      .toEqual([[ROOT, undefined, 'tok-1'], [ROOT, undefined, 'tok-1']])
  })

  it('a visitor who declines to name itself uploads nothing', async () => {
    const w = mountSection({ canWrite: true, requestGuest: async () => null })
    await flushPromises()
    const input = w.find('input[type="file"][multiple]')
    Object.defineProperty(input.element, 'files', { value: [new File(['a'], 'a.txt')], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(guestFileUploadJob).not.toHaveBeenCalled()
  })

  // inbox #383 (owner decision 2026-10-08): a contributor's replace asks first — it changes the file for everyone, the
  // owner too, and no version is kept. Answered through the real dialog's buttons; nothing is sent, and no guest is
  // asked for, before the answer.
  it("a row's replace asks first, then targets that file with its key, served meta and the blob the guest saw", async () => {
    const dialog = mountConfirmDialog()
    const requestGuest = vi.fn(async () => GUEST)
    const w = mountSection({ canWrite: true, requestGuest })
    await flushPromises()
    await pickReplace(w, 'Report.pdf', 'new.pdf')
    expect(dialog.text()).toContain(t('files.guest.replaceConfirm', { name: 'Report.pdf', file: 'new.pdf' }))
    expect(t('files.guest.replaceConfirm', { name: 'a', file: 'b' })).toMatch(/owner too.*cannot be restored/)
    expect(guestFileUploadJob, 'nothing before the answer').not.toHaveBeenCalled()
    expect(requestGuest).not.toHaveBeenCalled()
    dialog.click(t('files.replaceAccept'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    const target = vi.mocked(guestFileUploadJob).mock.calls[0][0] as GuestFileTarget
    expect(target.replace).toEqual({ nodeId: R, nodeKey: KEYS.get(R), expectedBlobId: REPORT_BLOB.blobId })
    expect(Array.from(target.replace!.nodeKey)).toEqual(Array.from(KEYS.get(R)!))
  })

  it("a row's replace cancelled in the dialog sends nothing", async () => {
    const dialog = mountConfirmDialog()
    const w = mountSection({ canWrite: true })
    await flushPromises()
    await pickReplace(w, 'Report.pdf', 'new.pdf')
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    expect(guestFileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #384: a guest cannot delete the content-less file an unfinished upload left; its row uploads into it instead.
  it('a file known to hold no content offers an upload into it, filling that node with nothing to replace', async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    expect(w.find(`[aria-label="${t('files.replace', { name: 'aaa.bin' })}"]`).exists()).toBe(false)
    const into = w.find(`[aria-label="${t('files.uploadInto', { name: 'aaa.bin' })}"]`)
    expect(into.find('.pi-upload').exists()).toBe(true)
    await into.trigger('click')
    const single = w.find('input[type="file"]:not([multiple])')
    Object.defineProperty(single.element, 'files', { value: [new File(['n'], 'again.bin')], configurable: true })
    await single.trigger('change')
    await flushPromises()
    expect(confirmRequest.value, 'nothing to lose: no question').toBeNull()
    const target = vi.mocked(guestFileUploadJob).mock.calls[0][0] as GuestFileTarget
    expect(target.replace).toMatchObject({ nodeId: P, expectedBlobId: null })
  })

  it("a refused upload of this folder is explained: a link that is full says so, not the members' trash", async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    const failing = { name: 'big.iso', sizeBytes: 1, run: async () => { throw new Error('quota_exceeded') }, extra: { file: new File([''], 'big.iso') } }
    enqueue([failing], { label: 'x', key: `files-share:${SHARE}:${ROOT}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files[0]?.status).toBe('failed'))
    await flushPromises()
    expect(w.text()).toContain(t('files.guest.linkFull', { name: 'big.iso' }))
  })

  // Inbox #392 (owner decision): a contributor's upload of a name a file here holds stays refused (409 slug_taken) —
  // and says so plainly, with where the replace is; a name taken by something else keeps the generic wording.
  it('a refused name a file here holds says a file exists and points at its replace; another taken name stays generic', async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    const refused = (name: string) => ({ name, sizeBytes: 1, run: async () => { throw new Error('slug_taken') }, extra: { file: new File([''], name) } })
    enqueue([refused('report.PDF'), refused('Sub')], { label: 'x', key: `files-share:${SHARE}:${ROOT}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files.every((f) => f.status === 'failed')).toBe(true))
    await flushPromises()
    expect(w.text()).toContain(t('files.guest.fileExists', { name: 'report.PDF' }))
    expect(w.text()).toContain(t('files.errors.slug_taken', { name: 'Sub' }))
    expect(w.text()).not.toContain(t('files.errors.slug_taken', { name: 'report.PDF' }))
  })

  // Inbox #395 (F9): once the same file arrives, its failure is no longer explained.
  it('a failure is no longer shown once the same file has been uploaded since', async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    const key = `files-share:${SHARE}:${ROOT}`
    enqueue([{ name: 'x.txt', sizeBytes: 1, run: async () => { throw new Error('upload_failed') }, extra: { file: new File([''], 'x.txt') } }], { label: 'x', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files[0]?.status).toBe('failed'))
    await flushPromises()
    expect(w.text()).toContain(t('files.errors.failed', { name: 'x.txt' }))
    enqueue([fakeJob('x.txt')], { label: 'x', key })
    await vi.waitFor(() => expect(useUploadQueue().batches.value.flatMap((b) => b.files).some((f) => f.status === 'done')).toBe(true))
    await flushPromises()
    expect(w.text()).not.toContain(t('files.errors.failed', { name: 'x.txt' }))
  })
})
