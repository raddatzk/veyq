// @vitest-environment happy-dom
// A link rooted at a MEMORY or a CHAPTER, through the whole share page (inbox #401 S5): the shell (`ShareView`) with the
// app's REAL registry, the real vue-router and PrimeVue, every step a DOM click on the rendered element. Such a link is
// the member's view with functions removed: core's page (`ShareTreePage`) renders the kind's member detail
// (`MemoryDetailHost` → `MemoryDetail`) at the link's root, with the guest's breadcrumb, the curator's note and the role
// badge; a guest names itself through the shell's one dialog; a WRITE_ONLY link alone gets Memories' drop page.
//
// What it proves, per role:
//  - READ: the Memory's gallery, its chapters to navigate into (the `#k=` fragment kept) — no upload, nothing of a member's;
//  - READ_WRITE: besides, the upload — into the CHAPTER it navigated to, sealed under that chapter's key and naming it as
//    the target (the server creates the guest's own bucket there); at the root no target is named; an unnamed guest is
//    asked by the shell's dialog first, then says who it is with its "Not you?";
//  - WRITE_ONLY: the drop page, no gallery, no tree.
// And on every guest page, the security review's half (inbox #401): no AUTHENTICATED client is called (each throws and is
// recorded, `test/guestMode.ts`), and nothing of the member's world is read or written — the active scope (`activeSpace`),
// the node tree (`treeStore`), the pins (`favoritesStore`), the item marks (`itemFavoritesStore`), the member's covers
// (`core/node/cover/coverStore`'s `memberCoverStore`, `covers`, `declaredCovers`, `resetCovers` - the guest's own store is the
// page's, `useCovers`), and the people directory with the profile-picture cache (ADR 0084): every export of those modules is recorded here, and the record must stay empty.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

/** What the guest page touched of the member's world (see the header). */
const touched = vi.hoisted(() => [] as string[])
/** `mod` with every export recorded on use: a function on call, an object (a store) on any property read. */
const recording = vi.hoisted(() => (prefix: string, mod: Record<string, unknown>) =>
  Object.fromEntries(
    Object.entries(mod).map(([name, value]) => {
      const tag = `${prefix}.${name}`
      if (typeof value === 'function') {
        return [name, (...a: unknown[]) => {
          touched.push(tag)
          return (value as (...x: unknown[]) => unknown)(...a)
        }]
      }
      if (value !== null && typeof value === 'object') {
        return [name, new Proxy(value as object, {
          get: (t, p, r) => {
            touched.push(`${tag}.${String(p)}`)
            return Reflect.get(t, p, r)
          },
        })]
      }
      return [name, value]
    }),
  ))

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memory/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/files/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../app/activeSpace', async (orig) => recording('activeSpace', await orig<Record<string, unknown>>()))
vi.mock('../core/node/treeStore', async (orig) => recording('treeStore', await orig<Record<string, unknown>>()))
vi.mock('../core/favorites/favoritesStore', async (orig) => recording('favoritesStore', await orig<Record<string, unknown>>()))
vi.mock('../features/memory/itemFavoritesStore', async (orig) => recording('itemFavoritesStore', await orig<Record<string, unknown>>()))
// The people directory and the profile pictures' cache (ADR 0084): a guest holds no K_tenant and the share page has no people, so
// no avatar is looked up, fetched or drawn - every export is recorded and the record must stay empty.
vi.mock('../core/people/avatarCache', async (orig) => recording('avatarCache', await orig<Record<string, unknown>>()))
vi.mock('../core/people/directory', async (orig) => recording('directory', await orig<Record<string, unknown>>()))
vi.mock('../core/node/cover/coverStore', async (orig) => {
  const mod = await orig<Record<string, unknown>>()
  const { memberCoverStore, covers, declaredCovers, resetCovers } = mod
  return { ...mod, ...recording('coverStore', { memberCoverStore, covers, declaredCovers, resetCovers }) }
})
// The guest's upload is recorded at the share helper (its wire is `features/memory/source.wire.test.ts`'s).
vi.mock('../features/memory/share', async (orig) => ({ ...(await orig<typeof import('../features/memory/share')>()), addSharePhoto: vi.fn() }))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { sealName, wrapContentKey } from '../core/node/tree'
import { sealNodeMeta } from '../core/node/nodeMeta'
import { loadGuestSession, saveGuestSession, forgetGuestSession, type GuestIdentity } from '../core/sharing/guestIdentity'
import { ShareRole, type PublicShare, type ShareNode } from '../api/generated/sharing'
import type { ShareMedia } from '../api/generated/share-media'
import { publicSharesApi } from '../api/client'
import { publicShareMediaApi } from '../features/memory/api'
import { addSharePhoto } from '../features/memory/share'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memory/kinds'
import { clearUploads } from '../features/memory/uploadQueue'
import { setViewMode } from '../shared/state/useViewMode'
import { fakeApi, stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'
import { authenticatedCalls } from '../test/guestMode'
import { confirmRequest, settleConfirm } from '../shared/state/useConfirm'

const SCOPE = '77777777-7777-4777-8777-777777777777'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_s'
const MEMORY = '6e5a0000-0000-4000-8000-000000000801'
const CHAPTER = '6e5a0000-0000-4000-8000-000000000802'
const K_SHARE = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`
const keys = { memory: v1.randomBytes(32), chapter: v1.randomBytes(32) }
const GUEST: GuestIdentity = { identityId: 'id-g', contributorToken: 'tok-g', displayName: 'Oma' }

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
/** A link rooted at the Memory "Rom 2026" (its chapter "Tag 1" below), or — `chapterRoot` — at the chapter itself. */
function head(role: ShareRole, o: { chapterRoot?: boolean } = {}): PublicShareHead {
  const rootKind = o.chapterRoot ? CHAPTER_KIND : MEMORY_KIND
  const name = o.chapterRoot ? 'Tag 1' : 'Rom 2026'
  const base = { shareId: SHARE, kShare: K_SHARE, scopeId: SCOPE, name, noteByLocale: { en: 'Curator **note**' }, role, rootKind }
  if (role === ShareRole.WriteOnly) {
    const sealedDropPublicKey = b64std(v1.seal({ purpose: 'drop-public-key' }, K_SHARE, v1.serializeHybridPublic(v1.dropKeyPublic(v1.generateDropKey()))))
    return { ...base, share: { role, scopeId: SCOPE, rootKind, nodeId: MEMORY, sealedDropPublicKey, nodes: [] } as unknown as PublicShare }
  }
  const nodes = o.chapterRoot
    ? [{ id: CHAPTER, parentId: null, kind: CHAPTER_KIND, epoch: 0 }]
    // the served root meta lists the participants as the member sealed it (a share serves `wrappedMeta` unchanged, ADR 0083)
    : [{ id: MEMORY, parentId: null, kind: MEMORY_KIND, epoch: 0, wrappedMeta: sealNodeMeta(keys.memory, { scopeId: SCOPE, nodeId: MEMORY }, { v: 1, participants: { userIds: ['aaaaaaaa-0000-4000-8000-000000000001'] } }) }, child(CHAPTER, MEMORY, keys.memory, keys.chapter, 'Tag 1', CHAPTER_KIND)]
  const rootKey = o.chapterRoot ? keys.chapter : keys.memory
  return { ...base, share: { role, scopeId: SCOPE, rootKind, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, rootKey)), nodes } as PublicShare }
}
// No derivatives: the tiles draw the file icon, so no thumbnail is fetched.
const row = (mediaId: string, nodeId: string): ShareMedia => ({ mediaId, nodeId, blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 3, wrappedBlobKey: 'eA==' })

// PrimeVue's Dialog teleports; rendered in place so the test reads the shell's naming dialog.
const stubs = { Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' } }

let router: Router
beforeEach(async () => {
  if (confirmRequest.value) settleConfirm(false)
  localStorage.clear()
  forgetGuestSession(SHARE)
  clearUploads()
  authenticatedCalls.length = 0
  vi.clearAllMocks()
  setViewMode('grid')
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsVersion: 0, terms: {} })
  stubApi(publicShareMediaApi, { listShareMedia: { media: [row('m-1', MEMORY), row('c-1', CHAPTER)], identities: [] } })
  // A tier read (the lightbox's preview) is refused: the stage shows its error, the download stays.
  fakeApi(publicSharesApi, { resolveShareBlob: async () => { throw new Error('no store here') } })
  vi.mocked(addSharePhoto).mockResolvedValue({ blobId: 'b-up', mediaId: 'm-up' })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: ShareView }] })
  await router.push(`/s/${SHARE}${HASH}`)
  await router.isReady()
  touched.length = 0 // what setup touched is not the page's
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountPage(h: PublicShareHead) {
  vi.mocked(resolvePublicShare).mockResolvedValue(h)
  const w = mount(ShareView, { global: { plugins: [router, realI18n(), PrimeVue], stubs, directives: { tooltip: {} } }, attachTo: document.body })
  await flushPromises()
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>
const tiles = (w: Page) => w.findAll('[data-media-id]').map((e) => e.attributes('data-media-id')).sort()
const uploadButton = (w: Page) => w.find(`[aria-label="${t('media.upload')}"]`)
/** A real click on a rendered element (its default action runs too). */
function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
}
async function openChapter(w: Page) {
  click(w.findAll('[role="tab"]').find((b) => b.text().startsWith(t('memory.chapters')))!.element)
  await flushPromises()
  click(w.find('.memory-card .memory-card-main').element)
  await flushPromises()
  await vi.waitFor(() => expect(w.find('.veyq-node-header-title').text()).toBe('Tag 1'))
}
async function pick(w: Page, name: string) {
  const input = w.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [new File([new Uint8Array(4)], name, { type: 'image/jpeg' })], configurable: true })
  await input.trigger('change')
}
/** Nothing of a member's world was touched, nothing authenticated called, no member control is on the page. */
function expectGuestOnly(w: Page) {
  expect(authenticatedCalls).toEqual([])
  expect(touched).toEqual([])
  for (const label of [t('share.share'), t('node.favorite'), t('node.rename'), t('node.delete'), t('memory.addChapter'), t('media.marks.mark')]) {
    expect(w.find(`[aria-label="${label}"]`).exists(), label).toBe(false)
  }
  expect(w.find('.drop-stub, .share-add-button').exists(), "no drop page on a reading guest's page").toBe(false)
  // ADR 0083 decision 4: no participants in any share view - no row, no chip, no control, no link to a profile
  expect(w.find('[data-testid="memory-participants"]').exists(), 'the participants row').toBe(false)
  expect(w.find('.memory-participant-chip, [data-action="participants"], a[href*="people/"]').exists(), 'a participant control or link').toBe(false)
}

describe('a Memory link is the member\'s Memory detail (inbox #401 S5)', () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: the root's detail with the note, the role badge and the breadcrumb — the member's gallery, no guest body`, async () => {
      const w = await mountPage(head(role))
      await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
      expect(w.find('.share-view-unavailable').exists()).toBe(false)
      expect(w.find('.memory-detail .veyq-node-header-title').text()).toBe('Rom 2026')
      expect(w.find('.markdown-body').text()).toContain('Curator note')
      expect(w.find('.share-folder-badge').text()).toBe(t(role === ShareRole.Read ? 'share.readonly' : 'share.contributorBadge'))
      expect(w.findAll('a.p-breadcrumb-item-link').map((a) => a.text())).toEqual(['Rom 2026'])
      expect(uploadButton(w).exists(), 'upload').toBe(role === ShareRole.ReadWrite)
      expectGuestOnly(w)
      w.unmount()
    })

    it(`${role}: navigates into the chapter through the tree's link, the #k= fragment kept, and back by the breadcrumb`, async () => {
      const w = await mountPage(head(role))
      await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
      await openChapter(w)
      expect(router.currentRoute.value.query.node).toBe(CHAPTER)
      expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
      await vi.waitFor(() => expect(tiles(w)).toEqual(['c-1']))
      expect(uploadButton(w).exists(), 'upload in the chapter').toBe(role === ShareRole.ReadWrite)
      const crumbs = w.findAll('a.p-breadcrumb-item-link')
      expect(crumbs.map((a) => a.text())).toEqual(['Rom 2026', 'Tag 1'])
      click(crumbs[0].element)
      await flushPromises()
      expect(router.currentRoute.value.query.node).toBeUndefined()
      expect(router.currentRoute.value.hash).toBe(HASH)
      await vi.waitFor(() => expect(w.find('.veyq-node-header-title').text()).toBe('Rom 2026'))
      expectGuestOnly(w)
      w.unmount()
    })
  }

  it('READ_WRITE: inside the chapter it navigated to, an upload names THAT chapter and seals under its key', async () => {
    saveGuestSession(SHARE, GUEST)
    const w = await mountPage(head(ShareRole.ReadWrite))
    await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
    await openChapter(w)
    click(uploadButton(w).element) // named: the picker opens (a no-op in this DOM); the browser then fills the input
    await pick(w, 'neu.jpg')
    await vi.waitFor(() => expect(addSharePhoto).toHaveBeenCalledTimes(1))
    const [session, source] = vi.mocked(addSharePhoto).mock.calls[0]
    expect(session).toMatchObject({ identityId: GUEST.identityId, contributorToken: GUEST.contributorToken, shareId: SHARE, scopeId: SCOPE, nodeId: CHAPTER })
    expect(Array.from(session.sealKey), "sealed under the chapter's key").toEqual(Array.from(keys.chapter))
    expect((source.blob as File).name).toBe('neu.jpg')
    expectGuestOnly(w)
    w.unmount()
  })

  it('READ_WRITE: at the Memory itself no target is named, sealed under the Memory\'s key', async () => {
    saveGuestSession(SHARE, GUEST)
    const w = await mountPage(head(ShareRole.ReadWrite))
    await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
    await pick(w, 'root.jpg')
    await vi.waitFor(() => expect(addSharePhoto).toHaveBeenCalledTimes(1))
    const [session] = vi.mocked(addSharePhoto).mock.calls[0]
    expect(session.nodeId, 'the root names no target').toBeUndefined()
    expect(Array.from(session.sealKey)).toEqual(Array.from(keys.memory))
    w.unmount()
  })

  it("READ_WRITE, unnamed: the upload asks the shell's one naming dialog first; named, the shell says who it is", async () => {
    fakeApi(publicSharesApi, {
      createShareIdentity: async (r) => ({ identityId: r.createShareIdentityRequest.identityId, contributorToken: 'tok-1' }),
      resolveShareBlob: async () => { throw new Error('no store here') },
    })
    const w = await mountPage(head(ShareRole.ReadWrite))
    await vi.waitFor(() => expect(tiles(w)).toEqual(['m-1']))
    const opened = vi.spyOn(HTMLInputElement.prototype, 'click')
    click(uploadButton(w).element)
    await flushPromises()
    expect(opened, 'no picker before a name').not.toHaveBeenCalled()
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.guest.nameTitle'))
    await w.find('.dialog-stub .guest-naming-input').setValue('Oma')
    await w.find('.dialog-stub form').trigger('submit')
    await flushPromises()
    expect(loadGuestSession(SHARE)).toMatchObject({ contributorToken: 'tok-1', displayName: 'Oma' })
    expect(w.find('.share-view-identity').text()).toContain(t('share.guest.as', { name: 'Oma' }))
    expect(w.find('.share-view-identity button.guest-naming-not-you').exists(), '"Not you?"').toBe(true)
    click(uploadButton(w).element)
    expect(opened, 'named, the next click opens the picker').toHaveBeenCalledTimes(1)
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('a CHAPTER link: the chapter\'s detail at the root, under its own key; a contributor uploads into it', async () => {
    saveGuestSession(SHARE, GUEST)
    stubApi(publicShareMediaApi, { listShareMedia: { media: [row('c-1', CHAPTER)], identities: [] } })
    fakeApi(publicSharesApi, { resolveShareBlob: async () => { throw new Error('no store here') } })
    const w = await mountPage(head(ShareRole.ReadWrite, { chapterRoot: true }))
    await vi.waitFor(() => expect(tiles(w)).toEqual(['c-1']))
    expect(w.find('.veyq-node-header-title').text()).toBe('Tag 1')
    await pick(w, 'tag.jpg')
    await vi.waitFor(() => expect(addSharePhoto).toHaveBeenCalledTimes(1))
    const [session] = vi.mocked(addSharePhoto).mock.calls[0]
    expect(session.nodeId, "the link's root names no target").toBeUndefined()
    expect(Array.from(session.sealKey)).toEqual(Array.from(keys.chapter))
    expectGuestOnly(w)
    w.unmount()
  })

  it('WRITE_ONLY: the drop page — no tree, no gallery, no member detail', async () => {
    const w = await mountPage(head(ShareRole.WriteOnly))
    expect(w.find('.share-view-unavailable').exists()).toBe(false)
    expect(w.find('.share-name-card').text()).toContain(t('share.uploaderIntro'))
    expect(w.find('.memory-detail').exists()).toBe(false)
    expect(w.find('[data-testid="memory-participants"]').exists(), 'no participants on a drop page').toBe(false)
    expect(w.find('.share-folder-bar').exists(), "core's page").toBe(false)
    expect(publicShareMediaApi().listShareMedia, 'an uploader is served no media').not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
    expect(touched).toEqual([])
    w.unmount()
  })
})
