// @vitest-environment happy-dom
// What `MemoryDetail` offers follows `caps` (inbox #401), and nothing else: the member's caps show exactly the controls
// the detail always showed a member (the S1 promise: no change for members), the empty set shows none of them, and the
// member-only EFFECTS stay off without their capability — the item marks are not read, the automatic date/place
// reconcile writes no node meta. Real DOM, real clicks; the media API is the in-memory gallery fake.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import { mount, flushPromises } from '@vue/test-utils'
import exifr from 'exifr'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { NO_MEMORY_CAPABILITIES, memoryCapabilities, type MemoryCapabilities } from '../capabilities'
import { ShareRole } from '../../../api/generated/sharing'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import type { ContentApi } from '../../../api/generated/content'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryCaps, guestAccessOf } from '../../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const node = (kind = MEMORY_KIND) => treeNode({ id: NODE_ID, kind, name: 'Rom 2026', parentId: null })
const chapter = treeNode({ id: 'c1', kind: CHAPTER_KIND, name: 'Day 1', parentId: NODE_ID })

const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}

let updateNode: Mock<ContentApi['updateNode']>
let getItemFavorites: Mock
beforeEach(() => {
  vi.clearAllMocks()
  updateNode = vi.fn(async () => ({ metaVersion: 1 })) as unknown as Mock<ContentApi['updateNode']>
  fakeApi(contentApi, { updateNode })
  getItemFavorites = stubApi(itemFavoritesApi, { getItemFavorites: [] }).getItemFavorites
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** A dated photo in the Memory: the automatic date reconcile has something to write on open. */
async function seedDated(memoryKey: Uint8Array) {
  vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(Date.UTC(2021, 4, 1)) })
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, deriver)
}

async function mountDetail(caps: MemoryCapabilities, kind = MEMORY_KIND, o: { personalScope?: boolean; attach?: boolean } = {}) {
  const w = mount(MemoryDetail, {
    props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(kind), scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), scopeKey: v1.randomBytes(32), personalScope: o.personalScope ?? false, chapters: [chapter], caps },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
    ...(o.attach ? { attachTo: document.body } : {}),
  })
  await flushPromises()
  await flushPromises()
  return w
}

const HEADER = ['media.upload', 'share.share', 'share.uploadRequest', 'memory.participants.title', 'node.rename', 'node.move.action', 'node.delete'] as const
const header = (w: Awaited<ReturnType<typeof mountDetail>>) =>
  HEADER.filter((key) => w.find(`.veyq-node-header-actions button[aria-label="${t(key)}"]`).exists())
const tabs = (w: Awaited<ReturnType<typeof mountDetail>>) => w.findAll('[role="tab"]').map((tab) => tab.text().replace(/\d+$/, ''))
const disabled = (w: Awaited<ReturnType<typeof mountDetail>>, selector: string) => w.find(selector).attributes('disabled') !== undefined

describe('MemoryDetail follows caps', () => {
  it("a member's caps: every header action, all four tabs, the chapter card, the editable cover/date/place, the marks", async () => {
    const w = await mountDetail(memberMemoryCaps())
    expect(header(w)).toEqual([...HEADER])
    expect(tabs(w)).toEqual([t('memory.gallery'), t('memory.chapters'), t('memory.buckets.title'), t('memory.trash.title')])
    expect(w.find('input[type="file"]').exists()).toBe(true)
    expect(disabled(w, '.memory-cover-preview')).toBe(false)
    expect(disabled(w, '.memory-date-chip')).toBe(false)
    expect(disabled(w, '.memory-location-chip')).toBe(false)
    expect(getItemFavorites, 'the marks of this Memory are read').toHaveBeenCalled()
    await w.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    expect(w.find('.memory-create-card-memory').text()).toBe(t('memory.addChapter'))
  })

  it('no caps: none of them — the gallery alone, nothing editable, no marks read', async () => {
    const w = await mountDetail(NO_MEMORY_CAPABILITIES)
    expect(header(w)).toEqual([])
    expect(tabs(w)).toEqual([t('memory.gallery')])
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(disabled(w, '.memory-cover-preview')).toBe(true)
    expect(disabled(w, '.memory-date-chip')).toBe(true)
    expect(disabled(w, '.memory-location-chip')).toBe(true)
    expect(getItemFavorites).not.toHaveBeenCalled()
  })

  it('the automatic date reconcile writes the node meta only with editMeta', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDated(memoryKey)
    mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, caps: { ...memberMemoryCaps(), editMeta: false } },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
    })
    await flushPromises()
    await flushPromises()
    expect(updateNode).not.toHaveBeenCalled()
    // control: the same Memory with editMeta self-heals its date on open
    mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, caps: memberMemoryCaps() },
      global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
    })
    await flushPromises()
    await flushPromises()
    expect(updateNode).toHaveBeenCalled()
  })

  // The cover is edited in core's dialog, which the host mounts (inbox #411): this page only ASKS for it, and only where the
  // capabilities grant `setCover` - the lightbox offers "set as cover" exactly there (S5 split its one `readonly` per
  // capability), and even a pick forced from it without `setCover` asks for nothing and reaches no write.
  it('a cover picked in the lightbox asks for the dialog only with setCover; the tile likewise', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDated(memoryKey)
    const LightboxStub = { props: ['canSetCover'], emits: ['setCover'], template: `<button class="stub-set-cover" @click="$emit('setCover', { mediaId: 'x' })" />` }
    for (const setCover of [false, true]) {
      updateNode.mockClear()
      const w = mount(MemoryDetail, {
        props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, caps: { ...memberMemoryCaps(), editMeta: false, setCover } },
        global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { MediaLightbox: LightboxStub } },
      })
      await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
      await w.find('[data-media-id] button').trigger('click')
      expect(w.findComponent(LightboxStub).props('canSetCover'), 'offered in the lightbox').toBe(setCover)
      await w.find('.stub-set-cover').trigger('click')
      await w.find('.memory-cover-preview').trigger('click')
      await flushPromises()
      expect(w.emitted('coverFromPhoto')?.length ?? 0, `photo, setCover ${setCover}`).toBe(setCover ? 1 : 0)
      expect(w.emitted('editCover')?.length ?? 0, `tile, setCover ${setCover}`).toBe(setCover ? 1 : 0)
      expect(updateNode, 'this page writes no cover itself').not.toHaveBeenCalled()
      w.unmount()
    }
  })

  // uc.memory.participants (owner decision 2026-10-14): "Participants" is a header action, a member's alone, and the way into
  // the picker; the line under the title has no link of its own.
  it('the "Participants" action is shown for a member on a Memory and opens the picker', async () => {
    const w = await mountDetail(memberMemoryCaps(), MEMORY_KIND, { attach: true })
    const action = w.find('.veyq-node-header-actions button[data-action="participants"]')
    expect(action.attributes('aria-label')).toBe(t('memory.participants.title'))
    expect(action.find('.pi-user-plus').exists()).toBe(true)
    const picker = () => document.body.querySelector('.memory-participants-dialog')
    expect(picker(), 'no picker before the click').toBeNull()
    await action.trigger('click')
    await flushPromises()
    expect(picker(), 'the picker').not.toBeNull()
    expect(document.body.textContent).toContain(t('memory.participants.dialogTitle'))
    w.unmount()
    document.body.innerHTML = ''
  })

  it('the "Participants" action is absent for every share role, in a personal scope and on a chapter', async () => {
    for (const role of Object.values(ShareRole)) {
      const w = await mountDetail(memoryCapabilities(guestAccessOf(role), { kind: MEMORY_KIND, inIdentityFolder: false, shareable: true }))
      expect(w.find('[data-action="participants"]').exists(), role).toBe(false)
      w.unmount()
    }
    // the `participants` gate on its own, with the meta writable: a role that may write the meta still gets no action
    const writerWithout = await mountDetail({ ...memberMemoryCaps(), participants: false })
    expect(header(writerWithout), 'participants off, editMeta on').not.toContain('memory.participants.title')
    const personal = await mountDetail(memberMemoryCaps(), MEMORY_KIND, { personalScope: true })
    expect(header(personal), 'personal scope').not.toContain('memory.participants.title')
    expect(header(personal), 'control: the rest of the header is there').toContain('node.rename')
    const onChapter = await mountDetail(memberMemoryCaps({}, CHAPTER_KIND), CHAPTER_KIND)
    expect(header(onChapter), 'chapter').not.toContain('memory.participants.title')
  })

  it('a pending deletion: share stays, every write goes', async () => {
    const w = await mountDetail(memberMemoryCaps({ readonly: true }))
    expect(header(w)).toEqual(['share.share', 'share.uploadRequest'])
    expect(disabled(w, '.memory-date-chip')).toBe(true)
    await w.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    expect(w.find('.memory-create-card-memory').exists()).toBe(false)
  })

  // inbox #358: the move is the host's (core's move dialog), asked for through `move`; offered only with caps.move.
  it('Move to… asks the host for core\'s move dialog, and only with move', async () => {
    const w = await mountDetail(memberMemoryCaps())
    await w.find(`.veyq-node-header-actions button[aria-label="${t('node.move.action')}"]`).trigger('click')
    expect(w.emitted('move')).toHaveLength(1)
    const only = await mountDetail({ ...NO_MEMORY_CAPABILITIES, move: true })
    expect(header(only)).toEqual(['node.move.action'])
  })

  it('a chapter offers no "Add chapter" (a chapter lives only directly in a Memory)', async () => {
    const w = await mountDetail(memberMemoryCaps({}, CHAPTER_KIND), CHAPTER_KIND)
    await w.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    expect(w.find('.memory-card-grid').exists(), 'control: the chapters grid renders').toBe(true)
    expect(w.find('.memory-create-card-memory').exists()).toBe(false)
  })
})
