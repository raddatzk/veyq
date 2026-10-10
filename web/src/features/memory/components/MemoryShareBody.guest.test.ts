// @vitest-environment happy-dom
// What a guest of a Memory link sees and may do, with the REAL `openSharedMemory`, the real grid and tile and the
// app's real confirmation dialog — every interaction a DOM click (web/CLAUDE.md: a test that asserts what a handler
// calls has not clicked anything):
//  - inbox #356: the media are grouped by chapter, each chapter a section titled with its name (which a guest may read,
//    ADR 0080 Decision 2), the Memory's own media first under the page's heading, and the lightbox steps through them in
//    that order;
//  - inbox #361: a READ_WRITE guest deletes a photo it added — offered only on its own (the device remembers what it
//    uploaded under the link's identity; the server tells no guest whose a photo is, ADR 0030 point 8), asked first.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PrimeVue from 'primevue/config'
import { guestAccessFor } from '../../../test/models'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../api', () => ({ publicShareMediaApi: vi.fn() }))
vi.mock('../useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))
vi.mock('../share', async (importOriginal) => ({ ...(await importOriginal<typeof import('../share')>()), addSharePhoto: vi.fn() }))
vi.mock('../uploadQueue', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../uploadQueue')>()),
  collectPickedFiles: vi.fn(),
  enqueueUploads: vi.fn(),
}))

import MemoryShareBody from './MemoryShareBody.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { saveGuestSession } from '../../../core/sharing/guestIdentity'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import { CHAPTER_KIND, MEMORY_KIND } from '../kinds'
import { addSharePhoto } from '../share'
import { collectPickedFiles, enqueueUploads } from '../uploadQueue'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import type { ShareMedia } from '../../../api/generated/share-media'
import { publicShareMediaApi } from '../api'
import { stubApi, fakeApi } from '../../../test/apiMock'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { settleConfirm } from '../../../shared/state/useConfirm'
import { realI18n, t } from '../../../test/i18n'

const SCOPE_ID = '44444444-4444-4444-8444-444444444444'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000d0'
const DAY1 = '6e5a0000-0000-4000-8000-0000000000d1'
const DAY2 = '6e5a0000-0000-4000-8000-0000000000d2'
const TWIN = '6e5a0000-0000-4000-8000-0000000000d3' // a second chapter titled like DAY1
const K_SHARE = v1.randomBytes(32)
const MEMORY_KEY = v1.randomBytes(32)
const KEYS = new Map([[MEMORY, MEMORY_KEY], [DAY1, v1.randomBytes(32)], [DAY2, v1.randomBytes(32)], [TWIN, v1.randomBytes(32)]])

const chapter = (id: string, name: string) => {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return {
    id, parentId: MEMORY, kind: CHAPTER_KIND, epoch: 0, placementVersion: 0,
    encryptedName: sealName(MEMORY_KEY, address, name), wrappedContentKey: wrapContentKey(MEMORY_KEY, address, KEYS.get(id)!),
  }
}

function head(role: ShareRole, withChapters = true): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Rom 2026', noteByLocale: {}, role, rootKind: MEMORY_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: MEMORY_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, MEMORY_KEY)),
      nodes: [
        { id: MEMORY, parentId: null, kind: MEMORY_KIND, epoch: 0 },
        ...(withChapters ? [chapter(DAY1, 'Day 1'), chapter(DAY2, 'Day 2'), chapter(TWIN, 'Day 1')] : []),
      ],
    } as PublicShare,
  }
}

/** A listed media on `nodeId`, captured `daysAgo` days back — sealed under that node's key, bound to its id. */
function row(nodeId: string, mediaId: string, daysAgo: number): ShareMedia {
  const capturedAt = Date.UTC(2026, 0, 31) - daysAgo * 86_400_000
  const meta = new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt, w: 400, h: 300 }))
  return {
    nodeId, mediaId, blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==',
    wrappedMeta: b64std(v1.seal({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId }, KEYS.get(nodeId)!, meta)),
  }
}

const M_ROOT = '7c3f0000-0000-4000-8000-000000000001'
const M_DAY1_OLD = '7c3f0000-0000-4000-8000-000000000002'
const M_DAY2 = '7c3f0000-0000-4000-8000-000000000003'
const M_DAY1_NEW = '7c3f0000-0000-4000-8000-000000000004'
const M_TWIN = '7c3f0000-0000-4000-8000-000000000005'
const M_OWN = '7c3f0000-0000-4000-8000-000000000006'

// Day 2 holds the newest photo, so it comes first; Day 1's two photos sort newest-first inside it; the twin keeps its own section.
const LISTING = [row(DAY1, M_DAY1_OLD, 9), row(MEMORY, M_ROOT, 5), row(DAY2, M_DAY2, 1), row(DAY1, M_DAY1_NEW, 2), row(TWIN, M_TWIN, 7)]

function mountBody(h: PublicShareHead) {
  return mount(MemoryShareBody, {
    attachTo: document.body,
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: 'pw-tok', head: h, reload: async () => {}, access: guestAccessFor(h, { pwToken: 'pw-tok' }) },
    global: { plugins: [realI18n(), PrimeVue], stubs: { OptimisticTile: true, MediaLightbox: true } },
  })
}

/** Each group on the page: its heading (null for the Memory's own media) and its tiles' media ids, in page order. */
function groupsOf(w: ReturnType<typeof mountBody>) {
  return w.findAll('.share-media-group').map((g) => ({
    title: g.find('.veyq-section-header h2').exists() ? g.find('.veyq-section-header h2').text() : null,
    count: g.find('.veyq-section-header span').exists() ? g.find('.veyq-section-header span').text() : null,
    media: g.findAll('[data-media-id]').map((tile) => tile.attributes('data-media-id')),
  }))
}

const click = (el: Element) => el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
const deleteButtons = (w: ReturnType<typeof mountBody>) => w.findAll(`button[aria-label="${t('share.deletePhoto')}"]`)
const tileOf = (w: ReturnType<typeof mountBody>, mediaId: string) => w.find(`[data-media-id="${mediaId}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  settleConfirm(false)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  vi.mocked(collectPickedFiles).mockImplementation((_input, done) => {
    done([new File([new Uint8Array(4)], 'a.jpg', { type: 'image/jpeg' })])
    return () => {}
  })
})

describe('MemoryShareBody — a Memory link shows its chapters grouped, with their titles (#356)', () => {
  it("the Memory's own media first under the page heading, then one titled section per chapter, newest chapter first", async () => {
    stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] } })
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(groupsOf(w)).toEqual([
      { title: null, count: null, media: [M_ROOT] },
      { title: 'Day 2', count: '1', media: [M_DAY2] },
      { title: 'Day 1', count: '2', media: [M_DAY1_NEW, M_DAY1_OLD] },
      { title: 'Day 1', count: '1', media: [M_TWIN] }, // the same title, another chapter: its own section
    ])
    expect(w.find('.gallery-count').text(), 'the page still counts every media').toContain('5')
  })

  it('a tile opens the lightbox on itself, and the lightbox steps through the groups in page order', async () => {
    stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] } })
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    click(tileOf(w, M_DAY1_OLD).find('button.media-tile-open').element)
    await flushPromises()
    const lightbox = w.findComponent({ name: 'MediaLightbox' })
    const order = (lightbox.props('items') as { mediaId: string }[]).map((i) => i.mediaId)
    expect(order).toEqual([M_ROOT, M_DAY2, M_DAY1_NEW, M_DAY1_OLD, M_TWIN])
    expect(order[lightbox.props('startIndex') as number]).toBe(M_DAY1_OLD)
  })

  it('control: a Memory without chapters is one grid with no section heading', async () => {
    stubApi(publicShareMediaApi, { listShareMedia: { media: [row(MEMORY, M_ROOT, 5)], identities: [] } })
    const w = mountBody(head(ShareRole.Read, false))
    await flushPromises()
    expect(groupsOf(w)).toEqual([{ title: null, count: null, media: [M_ROOT] }])
    expect(w.findAll('.media-grid')).toHaveLength(1)
  })
})

describe('MemoryShareBody — a READ_WRITE guest deletes its own photos (#361)', () => {
  const OWN_KEY = `veyq:share-own-media:${SHARE_ID}`

  /** A named guest adds one photo; the server answers with `mediaId`. */
  async function addOne(w: ReturnType<typeof mountBody>, mediaId: string) {
    vi.mocked(addSharePhoto).mockResolvedValue({ blobId: 'b', mediaId })
    await w.find('.share-add-button').trigger('click')
    await w.find('input[type="file"]').trigger('change')
    const [entry] = vi.mocked(enqueueUploads).mock.calls[0]
    await entry.upload!({ blob: new Blob([]), mediaType: 'image/jpeg' }, {})
    await flushPromises()
  }

  it('a photo this guest added carries a delete; nobody else\'s does', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    stubApi(publicShareMediaApi, { listShareMedia: { media: [...LISTING, row(DAY1, M_OWN, 0)], identities: [] } })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    expect(deleteButtons(w), 'nothing is this guest\'s before it adds a photo').toHaveLength(0)
    await addOne(w, M_OWN)
    expect(deleteButtons(w)).toHaveLength(1)
    expect(tileOf(w, M_OWN).find(`button[aria-label="${t('share.deletePhoto')}"]`).exists()).toBe(true)
    expect(JSON.parse(localStorage.getItem(OWN_KEY)!), 'remembered on the device, bound to the identity').toEqual({ identityId: 'id-1', mediaIds: [M_OWN] })
  })

  it('the delete asks first and, accepted, deletes with the guest\'s token and the link\'s password token', async () => {
    const dialog = mountConfirmDialog()
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    localStorage.setItem(OWN_KEY, JSON.stringify({ identityId: 'id-1', mediaIds: [M_DAY2] })) // added on an earlier visit
    const { deleteShareMedia } = stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] }, deleteShareMedia: undefined })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()

    click(deleteButtons(w)[0].element)
    await flushPromises()
    expect(dialog.text()).toContain(t('share.deleteOwnConfirm'))
    expect(deleteShareMedia, 'nothing before the answer').not.toHaveBeenCalled()
    dialog.click(t('share.deleteOwnAccept'))
    await flushPromises()

    expect(deleteShareMedia).toHaveBeenCalledWith({ shareId: SHARE_ID, mediaId: M_DAY2, xContributorToken: 'tok-1', xSharePassword: 'pw-tok' })
    expect(tileOf(w, M_DAY2).exists(), 'gone from the page').toBe(false)
    expect(groupsOf(w).map((g) => g.title), 'and its emptied chapter with it').toEqual([null, 'Day 1', 'Day 1'])
    expect(JSON.parse(localStorage.getItem(OWN_KEY)!).mediaIds).toEqual([])
  })

  it('cancelled, nothing is deleted', async () => {
    const dialog = mountConfirmDialog()
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    localStorage.setItem(OWN_KEY, JSON.stringify({ identityId: 'id-1', mediaIds: [M_DAY2] }))
    const { deleteShareMedia } = stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] }, deleteShareMedia: undefined })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    click(deleteButtons(w)[0].element)
    await flushPromises()
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(deleteShareMedia).not.toHaveBeenCalled()
    expect(tileOf(w, M_DAY2).exists()).toBe(true)
  })

  it('a refused delete keeps the tile and says so', async () => {
    const dialog = mountConfirmDialog()
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    localStorage.setItem(OWN_KEY, JSON.stringify({ identityId: 'id-1', mediaIds: [M_DAY2] }))
    fakeApi(publicShareMediaApi, {
      listShareMedia: async () => ({ media: LISTING, identities: [] }),
      deleteShareMedia: async () => { throw new Error('404') },
    })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    click(deleteButtons(w)[0].element)
    await flushPromises()
    dialog.click(t('share.deleteOwnAccept'))
    await flushPromises()
    expect(tileOf(w, M_DAY2).exists()).toBe(true)
    expect(w.text()).toContain(t('share.deleteFailed'))
  })

  it("control: another identity's record offers nothing — a new name on the device is not the old one", async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-2', contributorToken: 'tok-2' })
    localStorage.setItem(OWN_KEY, JSON.stringify({ identityId: 'id-1', mediaIds: [M_DAY2] }))
    stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] } })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    expect(tileOf(w, M_DAY2).exists()).toBe(true)
    expect(deleteButtons(w)).toHaveLength(0)
  })

  it('control: a READ link offers no delete, whatever the device remembers', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    localStorage.setItem(OWN_KEY, JSON.stringify({ identityId: 'id-1', mediaIds: [M_DAY2] }))
    stubApi(publicShareMediaApi, { listShareMedia: { media: LISTING, identities: [] } })
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(tileOf(w, M_DAY2).exists()).toBe(true)
    expect(deleteButtons(w)).toHaveLength(0)
  })
})
