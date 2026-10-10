// @vitest-environment happy-dom
// Files' level section mounted for a SHARE GUEST (inbox #401 S3): the member's section, handed a guest's access and the
// tree its link opened (`guestTreeSource` over real crypto), as core's folder page mounts it. It replaced the guest-only
// `ShareFilesSection`; its cases are ported here. The guest sees the member's rows with the member-only functions removed
// (no rename, no delete for any guest; upload and replace for a contributor only, never inside an identity folder) and
// NOTHING of the member's world is touched: every authenticated client throws (`test/guestMode.ts`), the member's
// `activeSpace` records any read, core's member node operations and `treeStore` are stubs that must stay uncalled.
// Stubbed by path besides: the guest job factory (the TARGET each pick produces; its wire is `shareUpload.wire.test.ts`'s),
// the download (`fileFacts` stays real), core's `shareChunkUrls` and `guestTreeContext` (the rest of `guestTree`, the walk,
// is real).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
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
vi.mock('../download', async (orig) => ({ ...(await orig<typeof import('../download')>()), downloadFile: vi.fn() }))
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

import FilesSection from './FilesSection.vue'
import { publicShareFilesApi } from '../api'
import { downloadFile } from '../download'
import { guestFileUploadJob, type GuestFileTarget } from '../shareUpload'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { guestTreeContext } from '../../../core/sharing/guestTree'
import { planBlob } from '../../../core/blob/transport'
import { deleteNode, sealName, wrapContentKey, type TreeNode } from '../../../core/node/tree'
import { loadTree } from '../../../core/node/treeStore'
import { FOLDER_KIND } from '../../../core/node/kind'
import { childrenOf } from '../../../core/node/browse'
import { guestTreeSource, type GuestTreeSource } from '../../../core/sharing/guestAccess'
import { dropKeysForShare, mintIdentityKey, openDropKey } from '../../../core/sharing/dropKey'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import type { GuestIdentity } from '../../../core/sharing/guestIdentity'
import { enqueue, clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { formatBytes } from '../../../shared/formatBytes'
import { setViewMode } from '../../../shared/state/useViewMode'
import { SaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import { ShareRole, type PublicShare, type ShareNode } from '../../../api/generated/sharing'
import type { ShareFileListing } from '../../../api/generated/files'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { guestAccessFor } from '../../../test/models'
import { authenticatedCalls } from '../../../test/guestMode'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_5'
const ROOT = '5c09e000-0000-4000-8000-0000000000c0'
const R = '5c09e000-0000-4000-8000-0000000000c1'
const P = '5c09e000-0000-4000-8000-0000000000c2'
const SUB = '5c09e000-0000-4000-8000-0000000000c3'
const DROP = '5c09e000-0000-4000-8000-0000000000c4'
const ANNA = '5c09e000-0000-4000-8000-0000000000c5'
const ANNA_FILE = '5c09e000-0000-4000-8000-0000000000c6'
const IDENTITY = '5c09e000-0000-4000-8000-0000000000ca'
const PW = 'cHc='
const K_SHARE = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`
const keys = {
  root: v1.randomBytes(32), report: v1.randomBytes(32), pending: v1.randomBytes(32), sub: v1.randomBytes(32),
  drop: v1.randomBytes(32), anna: v1.randomBytes(32), annaFile: v1.randomBytes(32),
}
const MODIFIED = Date.UTC(2026, 9, 1, 12)
const META = { type: 'application/pdf', name: 'Report.pdf', modifiedAt: MODIFIED }
/** The report's blob as `listShareFiles` serves it: planned under the file's key, its meta sealed under its K_blob. */
const REPORT_PLAN = planBlob(SCOPE, { ownerId: R, role: 'FILE_CONTENT' }, keys.report, new Blob([new Uint8Array(123_456)]), META)
const REPORT_BLOB = {
  blobId: REPORT_PLAN.blobId, chunkCount: REPORT_PLAN.chunkCount, byteSize: REPORT_PLAN.byteSize,
  wrappedBlobKey: REPORT_PLAN.wrappedBlobKey, wrappedBlobMeta: REPORT_PLAN.wrappedBlobMeta,
}
const LISTING: ShareFileListing = {
  items: [{ nodeId: R, fileBlob: REPORT_BLOB }, { nodeId: P, fileBlob: null }, { nodeId: ANNA_FILE, fileBlob: { ...REPORT_BLOB, blobId: 'blob-a' } }],
}
const GUEST: GuestIdentity = { identityId: 'id-1', contributorToken: 'tok-1' }

function child(id: string, parentId: string, entryKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(entryKey, address, name), wrappedContentKey: wrapContentKey(entryKey, address, key) }
}
// A drop box below the link with one guest's identity folder (ADR 0080 decision 11): nobody but that guest places there.
const { sealedDropPrivateKey } = dropKeysForShare(SCOPE, DROP, keys.drop, v1.randomBytes(32), null)
const anna = mintIdentityKey(SCOPE, DROP, IDENTITY, v1.dropKeyPublic(openDropKey(SCOPE, DROP, keys.drop, sealedDropPrivateKey!)))

function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(R, ROOT, keys.root, keys.report, 'Report.pdf', FILE_KIND),
        child(P, ROOT, keys.root, keys.pending, 'aaa.bin', FILE_KIND),
        child(SUB, ROOT, keys.root, keys.sub, 'Sub', FOLDER_KIND),
        { ...child(DROP, ROOT, keys.root, keys.drop, 'Postfach', DROPBOX_KIND), sealedDropPrivateKey },
        { ...child(ANNA, DROP, anna.identityKey, keys.anna, 'Anna', FOLDER_KIND), shareIdentityId: IDENTITY, wrappedIdentityKey: anna.wrappedIdentityKey },
        child(ANNA_FILE, ANNA, keys.anna, keys.annaFile, 'steuer.pdf', FILE_KIND),
      ],
    } as PublicShare,
  }
}

let listShareFiles: ReturnType<typeof stubApi<typeof publicShareFilesApi, 'listShareFiles'>>['listShareFiles']

type Opts = { role?: ShareRole; at?: string; requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void>; nodes?: TreeNode[] }
function mountSection(o: Opts = {}) {
  const h0 = head(o.role ?? ShareRole.Read)
  const tree: GuestTreeSource = guestTreeSource(h0, { hash: HASH })
  const at = o.at ?? ROOT
  const access = guestAccessFor(h0, { pwToken: PW, requestGuest: o.requestGuest ?? (async () => GUEST), reload: o.reload })
  return mount(FilesSection, {
    props: {
      folder: tree.node(at), nodes: o.nodes ?? (childrenOf([...tree.nodes], at).byKind.get(FILE_KIND) ?? []),
      epochs: null, access, tree,
    },
    global: { plugins: [realI18n()], directives: { tooltip: {} } },
  })
}
type Wrapper = ReturnType<typeof mountSection>
const rowTitles = (w: Wrapper) => w.findAll('.veyq-row-title').map((r) => r.text())
const labels = (w: Wrapper) => w.findAll('button').map((b) => b.attributes('aria-label') ?? b.text())
/** Click the row's replace of `name` (or its upload into, for a content-less file), then pick `picked`. */
async function pickReplace(w: Wrapper, name: string, picked: string, key: 'files.replace' | 'files.uploadInto' = 'files.replace') {
  await w.find(`button[aria-label="${t(key, { name })}"]`).trigger('click')
  const single = w.find('input[type="file"]:not([multiple])')
  Object.defineProperty(single.element, 'files', { value: [new File(['n'], picked)], configurable: true })
  await single.trigger('change')
  await flushPromises()
}
async function pickNew(w: Wrapper, names: string[]) {
  const input = w.find('input[type="file"][multiple]')
  Object.defineProperty(input.element, 'files', { value: names.map((n) => new File([n], n)), configurable: true })
  await input.trigger('change')
  await flushPromises()
}
const fakeJob = (name: string) => ({ name, sizeBytes: 1, run: vi.fn(async () => ({ nodeId: 'n' })), extra: { file: new File([''], name) } })
const targets = () => vi.mocked(guestFileUploadJob).mock.calls.map(([target]) => target as GuestFileTarget)
/** Nothing of the member's world was touched (see the header). */
function expectNoMemberWorld() {
  expect(authenticatedCalls, 'no authenticated client').toEqual([])
  expect(memberReads, "the member's activeSpace is not read").toEqual([])
  expect(loadTree, "the member's tree is not reloaded").not.toHaveBeenCalled()
  expect(deleteNode, 'no member node operation').not.toHaveBeenCalled()
}

beforeEach(() => {
  authenticatedCalls.length = 0
  memberReads.length = 0
  setViewMode('grid')
  vi.mocked(loadTree).mockClear()
  vi.mocked(deleteNode).mockClear()
  listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: LISTING }).listShareFiles
  vi.mocked(guestFileUploadJob).mockReset().mockImplementation((_t, file) => fakeJob(file.name))
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
  vi.mocked(guestTreeContext).mockClear()
  toastError.mockClear()
})
afterEach(() => {
  if (confirmRequest.value) settleConfirm(false)
  clearUploads()
})

describe('a guest reads the files of a shared folder in the member\'s section', () => {
  it('lists the FILE children with their sealed size and date, reading the blobs through the link only', async () => {
    const w = mountSection()
    await flushPromises()
    expect(rowTitles(w)).toEqual(['aaa.bin', 'Report.pdf'])
    const dated = t('files.modified', { date: new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(MODIFIED) })
    expect(w.text()).toContain(`${formatBytes(123_456, 'en')} · ${dated}`) // from the blob: its sealed size, its sealed meta
    expect(w.text()).toContain(t('files.notUploaded'))
    expect(listShareFiles).toHaveBeenCalledWith({ shareId: SHARE, xSharePassword: PW })
    expectNoMemberWorld()
  })

  it('a READ guest gets the download only: no upload, replace, rename or delete, no contributor hint', async () => {
    const w = mountSection()
    await flushPromises()
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(new Set(labels(w))).toEqual(new Set([t('files.download', { name: 'aaa.bin' }), t('files.download', { name: 'Report.pdf' })]))
    expect(w.find('.pi-pencil').exists(), 'no rename').toBe(false)
    expect(w.find('.pi-trash').exists(), 'no delete').toBe(false)
    expect(w.text()).not.toContain(t('files.upload'))
    expect(w.text()).not.toContain(t('files.guest.contributorHint'))
    expectNoMemberWorld()
  })

  it('a contributor gets upload and replace — still no rename and no delete — and is told what a replace does (inbox #383)', async () => {
    const w = mountSection({ role: ShareRole.ReadWrite })
    await flushPromises()
    expect(w.text()).toContain(t('files.guest.contributorHint'))
    expect(w.find('input[type="file"][multiple]').exists()).toBe(true)
    expect(w.find(`button[aria-label="${t('files.replace', { name: 'Report.pdf' })}"]`).attributes('disabled')).toBeUndefined()
    expect(w.find('.pi-pencil').exists(), 'no rename').toBe(false)
    expect(w.find('.pi-trash').exists(), 'no delete').toBe(false)
    expectNoMemberWorld()
  })

  it("inside a guest's identity folder a contributor only downloads (nobody but that guest places anything there)", async () => {
    const w = mountSection({ role: ShareRole.ReadWrite, at: ANNA })
    await flushPromises()
    expect(rowTitles(w)).toEqual(['steuer.pdf'])
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(labels(w).every((l) => l === t('files.download', { name: 'steuer.pdf' }))).toBe(true)
    expect(w.text()).not.toContain(t('files.guest.contributorHint'))
    expectNoMemberWorld()
  })

  it("downloads through the link with a real click: the presign is the share's, with the password token", async () => {
    const w = mountSection()
    await flushPromises()
    await w.find(`button.files-row-open[aria-label="${t('files.download', { name: 'Report.pdf' })}"]`).trigger('click')
    await flushPromises()
    const [scopeId, key, nodeId, blob, name, presign] = vi.mocked(downloadFile).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, name]).toEqual([SCOPE, R, REPORT_BLOB.blobId, 'Report.pdf'])
    expect(Array.from(key)).toEqual(Array.from(keys.report))
    await presign!(REPORT_BLOB.blobId)
    expect(shareChunkUrls).toHaveBeenCalledWith(SHARE, REPORT_BLOB.blobId, PW)
    expectNoMemberWorld()
  })

  it("a guest's download starts inside the click; too large says so, a cancelled save says nothing", async () => {
    const w = mountSection()
    await flushPromises()
    const button = () => w.find(`.veyq-prime-icon-button[aria-label="${t('files.download', { name: 'Report.pdf' })}"]`)
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

  it("an empty folder says so: a viewer's words, a contributor's with the upload", async () => {
    const viewer = mountSection({ at: SUB })
    await flushPromises()
    expect(viewer.find('.veyq-empty').text()).toBe(t('files.guest.empty'))
    const contributor = mountSection({ role: ShareRole.ReadWrite, at: SUB })
    await flushPromises()
    expect(contributor.find('.veyq-empty').text()).toBe(t('files.empty'))
    expect(listShareFiles, 'an empty level asks nothing').not.toHaveBeenCalled()
  })
})

describe('a contributor uploads into the shared folder', () => {
  it('asks for the guest, then enqueues one job per picked file into the folder on screen; the write reloads the link', async () => {
    const requestGuest = vi.fn(async () => GUEST)
    const reload = vi.fn(async () => {})
    const w = mountSection({ role: ShareRole.ReadWrite, requestGuest, reload })
    await flushPromises()
    await pickNew(w, ['a.txt', 'b.txt'])
    expect(requestGuest).toHaveBeenCalledOnce()
    expect(targets().map((t0) => [t0.parentId, t0.replace, (t0.ctx as unknown as { contributorToken: string }).contributorToken]))
      .toEqual([[ROOT, undefined, 'tok-1'], [ROOT, undefined, 'tok-1']])
    expect(vi.mocked(guestTreeContext).mock.calls[0][1]).toEqual({ contributorToken: 'tok-1', fragmentKey: 'frag', pwToken: PW })
    await vi.waitFor(() => expect(reload).toHaveBeenCalled())
    expect(useUploadQueue().batches.value[0]?.label).toBe('Familie')
    expectNoMemberWorld()
  })

  it('a visitor who declines to name itself uploads nothing', async () => {
    const w = mountSection({ role: ShareRole.ReadWrite, requestGuest: async () => null })
    await flushPromises()
    await pickNew(w, ['a.txt'])
    expect(guestFileUploadJob).not.toHaveBeenCalled()
    expect(useUploadQueue().batches.value).toEqual([])
  })

  // inbox #392 (owner decision 2026-10-08): no replace by upload for a guest — the name a file here holds is sent as a new
  // file, asks nothing, and the server's 409 is explained below.
  it("an upload of a name a file here holds is not turned into a replace: no question, a new file's job", async () => {
    const w = mountSection({ role: ShareRole.ReadWrite })
    await flushPromises()
    await pickNew(w, ['report.PDF'])
    expect(confirmRequest.value, 'no replace question').toBeNull()
    expect(targets().map((t0) => [t0.parentId, t0.replace])).toEqual([[ROOT, undefined]])
  })

  // inbox #383 (owner decision 2026-10-08): a contributor's replace asks first — it changes the file for everyone, the
  // owner too, and no version is kept. Answered through the real dialog's buttons; nothing is sent, and no guest is
  // asked for, before the answer.
  it("a row's replace asks first, then targets that file with its key and the blob the guest saw", async () => {
    const dialog = mountConfirmDialog()
    const requestGuest = vi.fn(async () => GUEST)
    const w = mountSection({ role: ShareRole.ReadWrite, requestGuest })
    await flushPromises()
    await pickReplace(w, 'Report.pdf', 'new.pdf')
    expect(dialog.text()).toContain(t('files.guest.replaceConfirm', { name: 'Report.pdf', file: 'new.pdf' }))
    expect(t('files.guest.replaceConfirm', { name: 'a', file: 'b' })).toMatch(/owner too.*cannot be restored/)
    expect(guestFileUploadJob, 'nothing before the answer').not.toHaveBeenCalled()
    expect(requestGuest).not.toHaveBeenCalled()
    dialog.click(t('files.replaceAccept'))
    await flushPromises()
    expect(dialog.isOpen()).toBe(false)
    const target = targets()[0]
    expect(target.replace).toMatchObject({ nodeId: R, expectedBlobId: REPORT_BLOB.blobId })
    expect(Array.from(target.replace!.nodeKey), "the file's key from the chain").toEqual(Array.from(keys.report))
  })

  it("a row's replace cancelled in the dialog sends nothing", async () => {
    const dialog = mountConfirmDialog()
    const w = mountSection({ role: ShareRole.ReadWrite })
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
    const w = mountSection({ role: ShareRole.ReadWrite })
    await flushPromises()
    expect(w.find(`button[aria-label="${t('files.replace', { name: 'aaa.bin' })}"]`).exists()).toBe(false)
    expect(w.find(`button[aria-label="${t('files.uploadInto', { name: 'aaa.bin' })}"]`).find('.pi-upload').exists()).toBe(true)
    await pickReplace(w, 'aaa.bin', 'again.bin', 'files.uploadInto')
    expect(confirmRequest.value, 'nothing to lose: no question').toBeNull()
    expect(targets()[0].replace).toMatchObject({ nodeId: P, expectedBlobId: null })
  })

  it("a refused upload of this folder is explained: a link that is full says so, not the members' trash", async () => {
    const w = mountSection({ role: ShareRole.ReadWrite })
    await flushPromises()
    const failing = { name: 'big.iso', sizeBytes: 1, run: async () => { throw new Error('quota_exceeded') }, extra: { file: new File([''], 'big.iso') } }
    enqueue([failing], { label: 'x', key: `files-share:${SHARE}:${ROOT}` })
    await vi.waitFor(() => expect(useUploadQueue().batches.value[0]?.files[0]?.status).toBe('failed'))
    await flushPromises()
    expect(w.text()).toContain(t('files.guest.linkFull', { name: 'big.iso' }))
    expect(w.text()).not.toContain(t('files.errors.quota_exceeded', { name: 'big.iso' }))
  })

  // Inbox #392: the refused name a file here holds says so plainly, with where the replace is; a name taken by something
  // else keeps the generic wording.
  it('a refused name a file here holds says a file exists and points at its replace; another taken name stays generic', async () => {
    const w = mountSection({ role: ShareRole.ReadWrite })
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
    const w = mountSection({ role: ShareRole.ReadWrite })
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
