// @vitest-environment happy-dom
// The member's Memory detail mounted for a SHARE GUEST below a shared folder (inbox #401 S4, shipped in S5): core's page of
// a link (`ShareTreePage`) with the app's REAL registry renders the kind's registered `detail` (`MemoryDetailHost` →
// `MemoryDetail`) with the guest's access and tree, and the host hands it the guest's data source (`../source.ts`). Real
// PrimeVue, the real vue-router, every step a DOM click on the rendered element. A link rooted at the Memory itself,
// through the shell, is `views/ShareView.memory.test.ts`.
//
// What it proves: nothing reaches an AUTHENTICATED client (each throws and is recorded, `test/guestMode.ts`), nothing of
// the member's world is read (`activeSpace`, the pin store, the item marks' load), no member-only control is on the page
// (rename, delete the Memory, the pin, share, the cover, date and place, buckets, marks, the media trash, the contributions
// tab), and what each role may do is there: READ — the gallery, the chapters to navigate into, the lightbox's download;
// READ_WRITE — besides, the upload (into the chapter on screen, under that chapter's key) and the delete of its own media
// (asked first, then purged). A chapter opens through the tree's `linkTo`, the `#k=` fragment kept.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../../files/api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
const memberReads = vi.hoisted(() => [] as string[])
vi.mock('../../../app/activeSpace', () => {
  const read = (name: string) => () => {
    memberReads.push(name)
    return null
  }
  return {
    activeSpace: new Proxy({}, { get: (_t, name) => { memberReads.push(`activeSpace.${String(name)}`); return null } }),
    activeScopeKey: read('activeScopeKey'),
    activeWriteKey: read('activeWriteKey'),
    activeEpochKeys: read('activeEpochKeys'),
    termsStale: read('termsStale'),
  }
})
vi.mock('../../../core/favorites/favoritesStore', async (orig) => ({ ...(await orig<object>()), isFavorite: vi.fn(), toggleFavorite: vi.fn() }))
vi.mock('../itemFavoritesStore', async (orig) => ({ ...(await orig<object>()), loadItemFavoritesFor: vi.fn(), toggleItemFavorite: vi.fn() }))
// The guest's upload is recorded at the share helper (its wire is `source.wire.test.ts`'s).
vi.mock('../share', async (orig) => ({ ...(await orig<typeof import('../share')>()), addSharePhoto: vi.fn() }))

import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { saveGuestSession, forgetGuestSession, type GuestIdentity } from '../../../core/sharing/guestIdentity'
import { ShareRole, type PublicShare, type ShareNode } from '../../../api/generated/sharing'
import type { ShareMedia } from '../../../api/generated/share-media'
import { publicSharesApi } from '../../../api/client'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { publicShareMediaApi } from '../api'
import { addSharePhoto } from '../share'
import { isFavorite } from '../../../core/favorites/favoritesStore'
import { loadItemFavoritesFor } from '../itemFavoritesStore'
import { rememberOwnShareMedia } from '../ownShareMedia'
import { clearUploads } from '../uploadQueue'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { realI18n, t } from '../../../test/i18n'
import { guestAccessFor } from '../../../test/models'
import { authenticatedCalls } from '../../../test/guestMode'
import { mountConfirmDialog } from '../../../test/confirmDialog'
import { setViewMode } from '../../../shared/state/useViewMode'

const SCOPE = '88888888-8888-4888-8888-888888888889'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_m'
const ROOT = '6e5a0000-0000-4000-8000-000000000700'
const MEMORY = '6e5a0000-0000-4000-8000-000000000701'
const CHAPTER = '6e5a0000-0000-4000-8000-000000000702'
const PW = 'cHc='
const K_SHARE = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`
const keys = { root: v1.randomBytes(32), memory: v1.randomBytes(32), chapter: v1.randomBytes(32) }
const GUEST: GuestIdentity = { identityId: 'id-g', contributorToken: 'tok-g' }

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
/** A folder link: the folder, a Memory in it, a chapter in the Memory. */
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(MEMORY, ROOT, keys.root, keys.memory, 'Rom 2026', MEMORY_KIND),
        child(CHAPTER, MEMORY, keys.memory, keys.chapter, 'Tag 1', CHAPTER_KIND),
      ],
    } as PublicShare,
  }
}
// No derivatives: the tiles draw the file icon, so no thumbnail is fetched.
const row = (mediaId: string, nodeId: string): ShareMedia => ({ mediaId, nodeId, blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 3, wrappedBlobKey: 'eA==' })

let router: Router
let requestGuest: ReturnType<typeof vi.fn<() => Promise<GuestIdentity | null>>>
beforeEach(async () => {
  localStorage.clear()
  forgetGuestSession(SHARE)
  clearUploads()
  authenticatedCalls.length = 0
  memberReads.length = 0
  vi.clearAllMocks()
  setViewMode('grid')
  stubApi(publicShareMediaApi, {
    listShareMedia: { media: [row('m-1', MEMORY), row('m-own', MEMORY), row('c-1', CHAPTER)], identities: [] },
    deleteShareMedia: undefined,
  })
  // A tier read (the lightbox's preview) is refused: the stage shows its error, the download stays.
  fakeApi(publicSharesApi, { resolveShareBlob: async () => { throw new Error('no store here') } })
  vi.mocked(addSharePhoto).mockResolvedValue({ blobId: 'b-up', mediaId: 'm-up' })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE}?node=${MEMORY}${HASH}`)
  await router.isReady()
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountPage(role: ShareRole) {
  const h = head(role)
  requestGuest = vi.fn(async () => null)
  const w = mount(ShareTreePage, {
    props: { shareId: SHARE, fragmentKey: b64url(K_SHARE), pwToken: PW, head: h, reload: async () => {}, access: guestAccessFor(h, { pwToken: PW, requestGuest }) },
    global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } },
    attachTo: document.body,
  })
  await flushPromises()
  await vi.waitFor(() => expect(w.findAll('[data-media-id]').length).toBeGreaterThan(0))
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>

const tabs = (w: Page) => w.findAll('[role="tab"]').map((b) => b.text().replace(/\d+$/, '').trim())
const tiles = (w: Page) => w.findAll('[data-media-id]').map((e) => e.attributes('data-media-id')).sort()
const byLabel = (w: Page, label: string) => w.find(`[aria-label="${label}"]`)

/** Nothing a member alone may do is on the page, nothing reached the member's world or an authenticated client. */
function expectGuestOnly(w: Page) {
  for (const label of [
    t('share.share'), t('share.uploadRequest'), t('node.favorite'), t('node.unfavorite'), t('node.rename'), t('node.delete'),
    t('media.marks.mark'), t('media.marks.unmark'), t('memory.addChapter'),
  ]) {
    expect(byLabel(w, label).exists(), label).toBe(false)
    expect(w.findAll('button').some((b) => b.text() === label), label).toBe(false)
  }
  expect(tabs(w)).toEqual([t('memory.gallery'), t('memory.chapters')]) // no contributions organizer, no media trash
  expect(w.find('.gallery-mark-filter').exists(), 'the mark filter').toBe(false)
  expect(w.find('.memory-cover-preview').attributes('disabled'), 'the cover').toBeDefined()
  expect(w.find('.memory-date-chip').attributes('disabled'), 'the date').toBeDefined()
  expect(w.find('.memory-location-chip').attributes('disabled'), 'the place').toBeDefined()
  expect(authenticatedCalls).toEqual([])
  expect(memberReads).toEqual([])
  expect(isFavorite).not.toHaveBeenCalled()
  expect(loadItemFavoritesFor).not.toHaveBeenCalled()
}

/** A real click on a rendered element (its default action runs too). */
function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
}

describe("the member's Memory detail for a share guest (inbox #401 S4/S5)", () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: the gallery of the node on screen, its chapters, and nothing of a member's`, async () => {
      const w = await mountPage(role)
      expect(w.find('.veyq-node-header-title').text()).toBe('Rom 2026')
      expect(tiles(w)).toEqual(['m-1', 'm-own'])
      expectGuestOnly(w)
      expect(byLabel(w, t('media.upload')).exists(), 'upload').toBe(role === ShareRole.ReadWrite)
      w.unmount()
    })

    it(`${role}: a tile opens the lightbox with the download and no write`, async () => {
      const w = await mountPage(role)
      click(w.find('[data-media-id="m-1"] button').element)
      await flushPromises()
      const box = w.find('.media-lightbox')
      expect(box.exists()).toBe(true)
      expect(box.find(`[aria-label="${t('media.download')}"]`).exists(), 'download').toBe(true)
      expect(box.find(`[aria-label="${t('media.delete')}"]`).exists(), 'the trash').toBe(false)
      expect(box.find(`[aria-label="${t('memory.cover.setFromPhoto')}"]`).exists(), 'set as cover').toBe(false)
      expectGuestOnly(w)
      w.unmount()
    })

    it(`${role}: a chapter opens with the real router through the tree's link, the #k= fragment kept`, async () => {
      const w = await mountPage(role)
      click(w.findAll('[role="tab"]').find((b) => b.text().startsWith(t('memory.chapters')))!.element)
      await flushPromises()
      const card = w.find('.memory-card .memory-card-main')
      expect(card.text()).toContain('Tag 1')
      click(card.element)
      await flushPromises()
      expect(router.currentRoute.value.query.node).toBe(CHAPTER)
      expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
      await vi.waitFor(() => expect(w.find('.veyq-node-header-title').text()).toBe('Tag 1'))
      await vi.waitFor(() => expect(tiles(w)).toEqual(['c-1']))
      expectGuestOnly(w)
      w.unmount()
    })
  }

  it('READ: no delete on any tile, not even one this device once sent', async () => {
    saveGuestSession(SHARE, GUEST)
    rememberOwnShareMedia(SHARE, GUEST.identityId, 'm-own')
    const w = await mountPage(ShareRole.Read)
    expect(w.find('.share-own-delete').exists()).toBe(false)
    w.unmount()
  })

  it('READ_WRITE, unnamed: the upload asks the page\'s one naming flow first, and opens no picker', async () => {
    const w = await mountPage(ShareRole.ReadWrite)
    const pick = vi.spyOn(HTMLInputElement.prototype, 'click')
    click(byLabel(w, t('media.upload')).element)
    await flushPromises()
    expect(requestGuest).toHaveBeenCalledTimes(1)
    expect(pick).not.toHaveBeenCalled()
    w.unmount()
  })

  it('READ_WRITE: deletes its own photo — only its own — after asking, for good', async () => {
    saveGuestSession(SHARE, GUEST)
    rememberOwnShareMedia(SHARE, GUEST.identityId, 'm-own')
    const confirm = mountConfirmDialog()
    const w = await mountPage(ShareRole.ReadWrite)
    const deletes = w.findAll('.share-own-delete')
    expect(deletes).toHaveLength(1)
    expect(deletes[0].element.closest('[data-media-id]')?.getAttribute('data-media-id')).toBe('m-own')
    click(deletes[0].element)
    await flushPromises()
    expect(confirm.isOpen()).toBe(true)
    confirm.click(t('share.deleteOwnAccept'))
    await flushPromises()
    expect(publicShareMediaApi().deleteShareMedia).toHaveBeenCalledWith({ shareId: SHARE, mediaId: 'm-own', xContributorToken: GUEST.contributorToken, xSharePassword: PW })
    await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
    expectGuestOnly(w)
    w.unmount()
  })

  // S5: the lightbox's one `readonly` split per capability — a contributor deletes its OWN photo there too (asked first,
  // purged), never another's, and is offered no "set as cover".
  it('READ_WRITE: in the lightbox, its own photo carries the delete — asked first, then purged — and no other does', async () => {
    saveGuestSession(SHARE, GUEST)
    rememberOwnShareMedia(SHARE, GUEST.identityId, 'm-own')
    const confirm = mountConfirmDialog()
    const w = await mountPage(ShareRole.ReadWrite)
    const trashIn = (box: ReturnType<Page['find']>) => box.find(`[aria-label="${t('media.delete')}"]`)

    click(w.find('[data-media-id="m-1"] button').element)
    await flushPromises()
    expect(trashIn(w.find('.media-lightbox')).exists(), "another's photo").toBe(false)
    expect(w.find(`.media-lightbox [aria-label="${t('memory.cover.setFromPhoto')}"]`).exists(), 'set as cover').toBe(false)
    click(w.find(`.media-lightbox [aria-label="${t('media.close')}"]`).element)
    await flushPromises()

    click(w.find('[data-media-id="m-own"] button').element)
    await flushPromises()
    click(trashIn(w.find('.media-lightbox')).element)
    await flushPromises()
    expect(confirm.isOpen(), 'asked first').toBe(true)
    confirm.click(t('share.deleteOwnAccept'))
    await flushPromises()
    expect(publicShareMediaApi().deleteShareMedia).toHaveBeenCalledWith({ shareId: SHARE, mediaId: 'm-own', xContributorToken: GUEST.contributorToken, xSharePassword: PW })
    await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
    expectGuestOnly(w)
    w.unmount()
  })

  it("READ_WRITE: inside a chapter, an upload goes into THAT chapter under its key", async () => {
    saveGuestSession(SHARE, GUEST)
    await router.push(`/s/${SHARE}?node=${CHAPTER}${HASH}`)
    const w = await mountPage(ShareRole.ReadWrite)
    expect(w.find('.veyq-node-header-title').text()).toBe('Tag 1')
    const input = w.find('input[type="file"]')
    const file = new File([new Uint8Array(4)], 'neu.jpg', { type: 'image/jpeg' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await vi.waitFor(() => expect(addSharePhoto).toHaveBeenCalledTimes(1))
    const [session, source] = vi.mocked(addSharePhoto).mock.calls[0]
    expect(session).toMatchObject({ ...GUEST, shareId: SHARE, scopeId: SCOPE, pwToken: PW, nodeId: CHAPTER })
    expect(Array.from(session.sealKey), "sealed under the chapter's key").toEqual(Array.from(keys.chapter))
    expect(source.blob).toBe(file)
    expectGuestOnly(w)
    w.unmount()
  })
})
