// @vitest-environment happy-dom
// What `MemoryDetail` offers follows `rights` (inbox #401), and nothing else: the member's rights show exactly the controls
// the detail always showed a member (the S1 promise: no change for members), the empty set shows none of them, and the
// member-only EFFECTS stay off without their right — the item marks are not read, the automatic date/place
// reconcile writes no node meta. Real DOM, real clicks; the media API is the in-memory gallery fake.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))

import { mount, flushPromises } from '@vue/test-utils'
import { readExifTags } from '../../../shared/image/exifTags'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { NO_MEMORY_RIGHTS, memoryRights, type MemoryRights } from '../rights'
import { ShareRole } from '../../../api/generated/sharing'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import type { ContentApi } from '../../../api/generated/content'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights, guestAccessOf } from '../../../test/models'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { headerActionLabels, headerButtonIds, headerMenuIds } from '../../../test/nodeHeader'
import type { CoreHeaderAction } from '../../../core/node/nodeHeader'
import { coreActionsStandIn } from '../../../test/nodePage'

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
  updateNode = vi.fn(async () => ({ metaRevision: 1 })) as unknown as Mock<ContentApi['updateNode']>
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
  vi.mocked(readExifTags).mockResolvedValue({ DateTimeOriginal: Date.UTC(2021, 4, 1) })
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg' }, deriver)
}

async function mountDetail(rights: MemoryRights, kind = MEMORY_KIND, o: { personalScope?: boolean; attach?: boolean; coreActions?: CoreHeaderAction[]; canSetCover?: boolean } = {}) {
  const w = mount(MemoryDetail, {
    props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(kind), scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), scopeKey: { epoch: 0, key: v1.randomBytes(32) }, personalScope: o.personalScope ?? false, chapters: [chapter], rights, canSetCover: o.canSetCover ?? false },
    // Core's actions come from the view around the header (`NodePage`); a stand-in supplies the given ones.
    global: { plugins: [realI18n()], provide: coreActionsStandIn(o.coreActions ?? []) },
    ...(o.attach ? { attachTo: document.body } : {}),
  })
  await flushPromises()
  await flushPromises()
  return w
}

const HEADER = ['media.upload', 'share.share', 'share.uploadRequest', 'memory.participants.title', 'memory.banner.add', 'node.rename', 'node.move.action', 'node.delete'] as const
/** The header's actions among {@link HEADER}, a button or a ⋯ menu entry (delete is always the latter, ADR 0110). */
const header = async (w: Awaited<ReturnType<typeof mountDetail>>) => {
  const offered = await headerActionLabels(w)
  return HEADER.filter((key) => offered.includes(t(key)))
}
// a tab is picked by `mousedown` below: Reka's tabs switch on the left button's press, not on `click`
const tabs = (w: Awaited<ReturnType<typeof mountDetail>>) => w.findAll('[role="tab"]').map((tab) => tab.text().replace(/\d+$/, ''))
const disabled = (w: Awaited<ReturnType<typeof mountDetail>>, selector: string) => w.find(selector).attributes('disabled') !== undefined

describe('MemoryDetail follows rights', () => {
  it('a Memory\'s header in the owner\'s priority (ADR 0110), core\'s actions by their ids; report and delete in the ⋯', async () => {
    const core = (id: string, over: Partial<CoreHeaderAction> = {}): CoreHeaderAction => ({ id, label: id, icon: 'heart', shown: true, onClick: () => {}, ...over })
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, {
      coreActions: [core('favorite', { placement: 'before' }), core('cover'), core('copy', { placement: { after: 'move' } }), core('download'), core('report', { menuOnly: true })],
    })
    expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'share', 'shareUpload', 'participants', 'download', 'cover', 'banner', 'rename', 'move', 'copy'])
    expect(await headerMenuIds(w)).toEqual(['report', 'delete'])
  })

  it("a member's rights: every header action, all four tabs, the chapter card, the editable cover/date/place, the marks", async () => {
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, { canSetCover: true })
    expect(await header(w)).toEqual([...HEADER])
    expect(tabs(w)).toEqual([t('memory.gallery'), t('memory.chapters'), t('memory.buckets.title'), t('memory.trash.title')])
    expect(w.find('input[type="file"]').exists()).toBe(true)
    expect(disabled(w, '.memory-cover-preview')).toBe(false)
    expect(disabled(w, '.memory-date-chip')).toBe(false)
    expect(disabled(w, '.memory-location-chip')).toBe(false)
    expect(getItemFavorites, 'the marks of this Memory are read').toHaveBeenCalled()
    await w.findAll('[role="tab"]')[1].trigger('mousedown')
    await flushPromises()
    expect(w.find('.memory-create-card-memory').text()).toBe(t('memory.addChapter'))
  })

  it('no rights: none of them — the gallery alone, nothing editable, no marks read', async () => {
    const w = await mountDetail(NO_MEMORY_RIGHTS)
    expect(await header(w)).toEqual([])
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
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: { ...memberMemoryRights(), editMeta: false } },
      global: { plugins: [realI18n()] },
    })
    await flushPromises()
    await flushPromises()
    expect(updateNode).not.toHaveBeenCalled()
    // control: the same Memory with editMeta self-heals its date on open
    mount(MemoryDetail, {
      props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: memberMemoryRights() },
      global: { plugins: [realI18n()] },
    })
    await flushPromises()
    await flushPromises()
    expect(updateNode).toHaveBeenCalled()
  })

  // The cover is edited in core's dialog, which the host mounts (inbox #411): this page only ASKS for it, from its tile, and only
  // where core's cover right grants it (`coverRights`, the host's `canSetCover`). A cover is set only by an upload there: the lightbox offers no way in.
  it('the cover tile asks for the dialog only with the core cover right; this page writes no cover itself', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDated(memoryKey)
    for (const setCover of [false, true]) {
      updateNode.mockClear()
      const w = mount(MemoryDetail, {
        props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: { ...memberMemoryRights(), editMeta: false }, canSetCover: setCover },
        global: { plugins: [realI18n()] },
      })
      await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
      await w.find('.memory-cover-preview').trigger('click')
      await flushPromises()
      expect(w.emitted('editCover')?.length ?? 0, `tile, canSetCover ${setCover}`).toBe(setCover ? 1 : 0)
      expect(updateNode, 'this page writes no cover itself').not.toHaveBeenCalled()
      w.unmount()
    }
  })

  // uc.memory.banner: the banner's dialog opens from the header's action where `setBanner` grants it - its only way in.
  it('the banner dialog opens from the header only with setBanner', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDated(memoryKey)
    const dialog = () => document.body.querySelector('.banner-dialog')
    for (const setBanner of [false, true]) {
      const w = mount(MemoryDetail, {
        props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: { ...memberMemoryRights(), editMeta: false, setBanner } },
        global: { plugins: [realI18n()] },
        attachTo: document.body,
      })
      await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
      const action = w.find(`.veyq-node-header-actions button[aria-label="${t('memory.banner.add')}"]`)
      expect(action.exists(), `the header's action, setBanner ${setBanner}`).toBe(setBanner)
      if (setBanner) {
        await action.trigger('click')
        await vi.waitFor(() => expect(!!dialog(), 'the dialog').toBe(true))
      }
      w.unmount()
      document.body.innerHTML = ''
    }
  })

  // uc.memory.participants (owner decision 2026-10-14): "Participants" is a header action, a member's alone, and the way into
  // the picker; the line under the title has no link of its own.
  it('the "Participants" action is shown for a member on a Memory and opens the picker', async () => {
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, { attach: true })
    const action = w.find('.veyq-node-header-actions button[data-action="participants"]')
    expect(action.attributes('aria-label')).toBe(t('memory.participants.title'))
    // its own icon: "Request uploads" is `user-plus`, and two actions of one bar never share one (inbox #425)
    expect(w.findAllComponents(VeyqButton).find((b) => b.element === action.element)?.props('icon')).toBe('users')
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
      const w = await mountDetail(memoryRights(guestAccessOf(role), { kind: MEMORY_KIND, inIdentityFolder: false, shareable: true }))
      expect(w.find('[data-action="participants"]').exists(), role).toBe(false)
      w.unmount()
    }
    // the `participants` gate on its own, with the meta writable: a role that may write the meta still gets no action
    const writerWithout = await mountDetail({ ...memberMemoryRights(), participants: false })
    expect(await header(writerWithout), 'participants off, editMeta on').not.toContain('memory.participants.title')
    const personal = await mountDetail(memberMemoryRights(), MEMORY_KIND, { personalScope: true })
    expect(await header(personal), 'personal scope').not.toContain('memory.participants.title')
    expect(await header(personal), 'control: the rest of the header is there').toContain('node.rename')
    const onChapter = await mountDetail(memberMemoryRights({}, CHAPTER_KIND), CHAPTER_KIND)
    expect(await header(onChapter), 'chapter').not.toContain('memory.participants.title')
  })

  it('a pending deletion: share stays, every write goes', async () => {
    const w = await mountDetail(memberMemoryRights({ readonly: true }))
    expect(await header(w)).toEqual(['share.share', 'share.uploadRequest'])
    expect(disabled(w, '.memory-date-chip')).toBe(true)
    await w.findAll('[role="tab"]')[1].trigger('mousedown')
    await flushPromises()
    expect(w.find('.memory-card-grid').exists(), 'control: the chapters grid renders').toBe(true)
    expect(w.find('.memory-create-card-memory').exists()).toBe(false)
  })

  // inbox #358: the move is the host's (core's move dialog), asked for through `move`; offered only with rights.move.
  it('Move to… asks the host for core\'s move dialog, and only with move', async () => {
    const w = await mountDetail(memberMemoryRights())
    await w.find(`.veyq-node-header-actions button[aria-label="${t('node.move.action')}"]`).trigger('click')
    expect(w.emitted('move')).toHaveLength(1)
    const only = await mountDetail({ ...NO_MEMORY_RIGHTS, move: true })
    expect(await header(only)).toEqual(['node.move.action'])
  })

  it('a chapter offers no "Add chapter" (a chapter lives only directly in a Memory)', async () => {
    const w = await mountDetail(memberMemoryRights({}, CHAPTER_KIND), CHAPTER_KIND)
    await w.findAll('[role="tab"]')[1].trigger('mousedown')
    await flushPromises()
    expect(w.find('.memory-card-grid').exists(), 'control: the chapters grid renders').toBe(true)
    expect(w.find('.memory-create-card-memory').exists()).toBe(false)
  })
})
