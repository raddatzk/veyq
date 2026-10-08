// @vitest-environment happy-dom
// Files' section of a public folder share (cr-files-application F2), mounted with the real catalog and the real core
// upload queue. Stubbed by path: Files' guest client (`listShareFiles`, typed by the contract), the guest job factory
// (so the TARGET each pick produces is visible — its wire is `shareUpload.wire.test.ts`'s), the download and core's
// `shareChunkUrls` (so the download's presign is visible as the link's), and core's `guestTreeContext`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { ShareFileListing } from '../../../api/generated/files'
import type { ShareNode } from '../../../api/generated/sharing'

vi.mock('../api', () => ({ publicShareFilesApi: vi.fn() }))
vi.mock('../download', () => ({ downloadFile: vi.fn() }))
vi.mock('../shareUpload', () => ({ guestFileUploadJob: vi.fn() }))
vi.mock('../../../core/sharing/guestBlob', () => ({ shareChunkUrls: vi.fn(async () => ['https://store.test/0']) }))
vi.mock('../../../core/sharing/guestTree', () => ({ guestTreeContext: vi.fn((head: unknown, o: object) => ({ head, rootId: ROOT, keys: KEYS, ...o })) }))

import ShareFilesSection from './ShareFilesSection.vue'
import { publicShareFilesApi } from '../api'
import { downloadFile } from '../download'
import { guestFileUploadJob, type GuestFileTarget } from '../shareUpload'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { sealFileMeta } from '../fileMeta'
import { FILE_KIND } from '../kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { enqueue, clearUploads, useUploadQueue } from '../../../core/blob/queue'
import { formatBytes } from '../../../shared/formatBytes'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import type { GuestIdentity } from '../../../core/sharing/guestIdentity'
import { realI18n, t } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_5'
const ROOT = '5c09e000-0000-4000-8000-0000000000c0'
const R = '5c09e000-0000-4000-8000-0000000000c1'
const P = '5c09e000-0000-4000-8000-0000000000c2'
const F = '5c09e000-0000-4000-8000-0000000000c3'
const PW = 'cHc='
const KEYS = new Map<string, Uint8Array>([[ROOT, v1.randomBytes(32)], [R, v1.randomBytes(32)], [P, v1.randomBytes(32)], [F, v1.randomBytes(32)]])
const META = { mime: 'application/pdf', size: 123_456, modifiedAt: Date.UTC(2026, 9, 1, 12) }
const fileNode = (id: string, over: Partial<ShareNode> = {}): ShareNode => ({ id, parentId: ROOT, kind: FILE_KIND, epoch: 0, placementVersion: 0, ...over })
const REPORT = fileNode(R, { wrappedMeta: sealFileMeta(KEYS.get(R)!, { scopeId: SCOPE, nodeId: R }, { v: 1, ...META }) })
const PENDING = fileNode(P)
const SUBFOLDER: ShareNode = { id: F, parentId: ROOT, kind: FOLDER_KIND, epoch: 0 }
const NAMES = new Map([[R, 'Report.pdf'], [P, 'aaa.bin'], [F, 'Sub']])
const LISTING: ShareFileListing = { items: [{ nodeId: R, fileBlob: { blobId: 'blob-r', chunkCount: 1, byteSize: 123_456 + v1.FRAME_OVERHEAD, wrappedBlobKey: 'eA==' } }, { nodeId: P, fileBlob: null }] }
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
const fakeJob = (name: string) => ({ name, sizeBytes: 1, run: vi.fn(async () => ({ nodeId: 'n', metaWritten: true })), extra: { file: new File([''], name) } })

beforeEach(() => {
  listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: LISTING }).listShareFiles
  vi.mocked(guestFileUploadJob).mockReset().mockImplementation((_t, file) => fakeJob(file.name))
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
})
afterEach(() => clearUploads())

describe('a guest reads the files of a shared folder', () => {
  it('lists the FILE children by name with their sealed size and date, never another kind; reads the blobs through the link', async () => {
    const w = mountSection()
    await flushPromises()
    expect(rowTitles(w)).toEqual(['aaa.bin', 'Report.pdf'])
    expect(w.text()).toContain(formatBytes(META.size, 'en'))
    expect(w.text()).toContain(t('files.notUploaded'))
    expect(listShareFiles).toHaveBeenCalledWith({ shareId: SHARE, xSharePassword: PW })
  })

  it('a READ guest gets no upload and no replace', async () => {
    const w = mountSection()
    await flushPromises()
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(w.findAll('button').map((b) => b.attributes('aria-label'))).toEqual([t('files.download', { name: 'aaa.bin' }), t('files.download', { name: 'Report.pdf' })])
  })

  it('downloads through the link: the presign is the share\'s, with the password token', async () => {
    const w = mountSection()
    await flushPromises()
    await w.find(`[aria-label="${t('files.download', { name: 'Report.pdf' })}"]`).trigger('click')
    await flushPromises()
    const [scopeId, key, nodeId, blob, name, presign] = vi.mocked(downloadFile).mock.calls[0]
    expect([scopeId, nodeId, blob.blobId, name]).toEqual([SCOPE, R, 'blob-r', 'Report.pdf'])
    expect(Array.from(key)).toEqual(Array.from(KEYS.get(R)!))
    await presign!('blob-r')
    expect(shareChunkUrls).toHaveBeenCalledWith(SHARE, 'blob-r', PW)
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

  it("a row's replace targets that file with its key, served meta and the blob the guest saw", async () => {
    const w = mountSection({ canWrite: true })
    await flushPromises()
    await w.find(`[aria-label="${t('files.replace', { name: 'Report.pdf' })}"]`).trigger('click')
    const single = w.find('input[type="file"]:not([multiple])')
    Object.defineProperty(single.element, 'files', { value: [new File(['n'], 'new.pdf')], configurable: true })
    await single.trigger('change')
    await flushPromises()
    const target = vi.mocked(guestFileUploadJob).mock.calls[0][0] as GuestFileTarget
    expect(target.replace).toMatchObject({ nodeId: R, wrappedMeta: REPORT.wrappedMeta, expectedBlobId: 'blob-r' })
    expect(Array.from(target.replace!.nodeKey)).toEqual(Array.from(KEYS.get(R)!))
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
})
