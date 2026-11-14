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
import { chooseHeaderEntry, headerActionLabels, headerButtonIds, headerMenuIds, mountWithHeader } from '../../../test/nodeHeader'
import type { CoreHeaderAction } from '../../../core/node/nodeHeader'
import { coreActionsStandIn, coverStandIn } from '../../../test/nodePage'

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

async function mountDetail(
  rights: MemoryRights,
  kind = MEMORY_KIND,
  o: { personalScope?: boolean; attach?: boolean; coreActions?: CoreHeaderAction[]; canSetCover?: boolean; renameTo?: (name: string) => Promise<void> } = {},
) {
  const w = mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(kind), scopeId: SCOPE_ID, memoryKey: v1.randomBytes(32), scopeKey: { epoch: 0, key: v1.randomBytes(32) },
      personalScope: o.personalScope ?? false, chapters: [chapter], rights, renameTo: o.renameTo ?? null,
    },
    // Core's page draws the header; core's actions and its cover come from the view around it, a stand-in here.
    global: { plugins: [realI18n()], provide: coreActionsStandIn(o.coreActions ?? [], coverStandIn({ canSet: o.canSetCover ?? false })) },
    ...(o.attach ? { attachTo: document.body } : {}),
  })
  await flushPromises()
  await flushPromises()
  return w
}

// What is edited at its place has no entry (ADR 0110 decision 11): the name, the cover, the participants (their fact).
const HEADER = ['media.upload', 'share.share', 'memory.banner.add', 'node.move.action', 'node.delete'] as const
const EDITED_IN_PLACE = ['node.rename', 'node.cover.set', 'node.cover.change', 'memory.participants.title', 'memory.participants.edit'] as const
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
      coreActions: [core('favorite', { placement: 'before' }), core('copy', { placement: { after: 'move' }, menuOnly: true }), core('download'), core('report', { menuOnly: true })],
    })
    expect(headerButtonIds(w)).toEqual(['favorite', 'upload', 'share', 'download'])
    // The banner only while none is drawn, the move and copy: ⋯ entries, before report and delete.
    expect(await headerMenuIds(w)).toEqual(['banner', 'move', 'copy', 'report', 'delete'])
  })

  it("a member's rights: every header action, every tab, the chapter card, the editable cover/date/place, the marks", async () => {
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, { canSetCover: true })
    expect(await header(w)).toEqual([...HEADER])
    const offered = await headerActionLabels(w)
    for (const key of EDITED_IN_PLACE) expect(offered, key).not.toContain(t(key))
    // the Music tab where the viewer may add music (ADR 0135 decision 9), even before there is any
    expect(tabs(w)).toEqual([t('memory.gallery'), t('memory.chapters'), t('memory.tracks.tab'), t('memory.music.tab'), t('memory.buckets.title'), t('memory.trash.title')])
    expect(w.find('input[type="file"]').exists()).toBe(true)
    expect(w.find('[data-testid="node-header-cover"]').exists(), 'core\'s cover button, where its right says so').toBe(true)
    expect(disabled(w, '.veyq-node-header-chip[data-fact="date"]')).toBe(false)
    expect(disabled(w, '.veyq-node-header-chip[data-fact="place"]')).toBe(false)
    expect(getItemFavorites, 'the marks of this Memory are read').toHaveBeenCalled()
    await w.findAll('[role="tab"]')[1].trigger('mousedown')
    await flushPromises()
    expect(w.find('.memory-create-card-memory').text()).toBe(t('memory.addChapter'))
  })

  it('no rights: none of them — the gallery and the map alone, nothing editable, no marks read', async () => {
    const w = await mountDetail(NO_MEMORY_RIGHTS)
    expect(await header(w)).toEqual([])
    // reading, as the gallery: the map and its tracks (uc.memory.tracks); no Music tab where there is no music and none may be added
    expect(tabs(w)).toEqual([t('memory.gallery'), t('memory.tracks.tab')])
    expect(w.find('input[type="file"]').exists()).toBe(false)
    expect(w.find('[data-testid="node-header-cover"]').exists(), 'no cover button').toBe(false)
    expect(disabled(w, '.veyq-node-header-chip[data-fact="date"]')).toBe(true)
    expect(w.find('.veyq-node-header-chip[data-fact="place"]').exists(), 'an unset place it may not set is left out').toBe(false)
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

  // The cover is core's (inbox #411, ADR 0110 decision 10): core's header shows it and opens core's dialog from it, where core's
  // cover right grants it (`coverRights`). The Memory draws no cover control of its own and writes no cover; the lightbox offers
  // no way in.
  it('the cover is core\'s: core\'s header opens core\'s dialog with the core cover right; this page draws and writes none', async () => {
    const memoryKey = v1.randomBytes(32)
    await seedDated(memoryKey)
    for (const setCover of [false, true]) {
      updateNode.mockClear()
      const open = vi.fn()
      const w = mountWithHeader(MemoryDetail, {
        props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: { ...memberMemoryRights(), editMeta: false } },
        global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ canSet: setCover, open })) },
      })
      await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
      expect(w.view.find('.veyq-node-header-leading, [data-testid="node-header-cover"]').exists(), 'no cover of the Memory\'s own').toBe(false)
      const cover = w.find('[data-testid="node-header-cover"]')
      expect(cover.exists(), `core's cover button, canSet ${setCover}`).toBe(setCover)
      if (setCover) await cover.trigger('click')
      await flushPromises()
      expect(open.mock.calls.map(([n]) => n.id), `core's dialog, canSet ${setCover}`).toEqual(setCover ? [NODE_ID] : [])
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
      const w = mountWithHeader(MemoryDetail, {
        props: { source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: node(), scopeId: SCOPE_ID, memoryKey, rights: { ...memberMemoryRights(), editMeta: false, setBanner } },
        global: { plugins: [realI18n()] },
        attachTo: document.body,
      })
      await vi.waitFor(() => expect(w.find('[data-media-id] button').exists()).toBe(true))
      // No banner yet: the ⋯ menu's entry (never a button), the way in while none is drawn.
      expect(w.find(`.veyq-node-header-actions button[aria-label="${t('memory.banner.add')}"]`).exists()).toBe(false)
      expect((await headerMenuIds(w)).includes('banner'), `the header's entry, setBanner ${setBanner}`).toBe(setBanner)
      if (setBanner) {
        await chooseHeaderEntry(w, 'banner')
        await vi.waitFor(() => expect(!!dialog(), 'the dialog').toBe(true))
      }
      w.unmount()
      document.body.innerHTML = ''
    }
  })

  // uc.memory.participants (owner 2026-11-04, ADR 0110 decision 11): the people fact is the way into the picker, a member's
  // alone - with nobody listed, the empty chip "Add participants"; the header has no "Participants" action.
  it('the participants\' fact opens the picker for a member on a Memory: the empty chip while nobody is listed', async () => {
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, { attach: true })
    expect(w.find('[data-action="participants"]').exists(), 'no header action').toBe(false)
    const chip = w.get('.veyq-node-header-facts.is-people button[data-fact="people-edit"]')
    expect(chip.text()).toBe(t('memory.participants.add'))
    expect(chip.attributes('aria-label')).toBe(t('memory.participants.edit'))
    const picker = () => document.body.querySelector('.memory-participants-dialog')
    expect(picker(), 'no picker before the click').toBeNull()
    await chip.trigger('click')
    await flushPromises()
    expect(picker(), 'the picker').not.toBeNull()
    expect(document.body.textContent).toContain(t('memory.participants.dialogTitle'))
    w.unmount()
    document.body.innerHTML = ''
  })

  it('no way to the picker for any share role, in a personal scope or on a chapter - and no action in its place', async () => {
    const edit = (w: Awaited<ReturnType<typeof mountDetail>>) => w.find('[data-fact="people-edit"]').exists()
    for (const role of Object.values(ShareRole)) {
      const w = await mountDetail(memoryRights(guestAccessOf(role), { kind: MEMORY_KIND, inIdentityFolder: false, shareable: true }))
      expect(w.find('h1').text(), `control: ${role}'s header`).toBe('Rom 2026')
      expect(edit(w), role).toBe(false)
      expect(w.find('[data-action="participants"]').exists(), role).toBe(false)
      w.unmount()
    }
    // the `participants` gate on its own, with the meta writable: a role that may write the meta still gets no way in
    const writerWithout = await mountDetail({ ...memberMemoryRights(), participants: false })
    expect(edit(writerWithout), 'participants off, editMeta on').toBe(false)
    const personal = await mountDetail(memberMemoryRights(), MEMORY_KIND, { personalScope: true })
    expect(edit(personal), 'personal scope').toBe(false)
    expect(await header(personal), 'control: the rest of the header is there').toContain('node.move.action')
    const onChapter = await mountDetail(memberMemoryRights({}, CHAPTER_KIND), CHAPTER_KIND)
    expect(edit(onChapter), 'chapter').toBe(false)
    const member = await mountDetail(memberMemoryRights())
    expect(edit(member), 'control: a member on a Memory has it').toBe(true)
  })

  it('the name is renamed in place through the page\'s door, only with the rename right and the door', async () => {
    const renameTo = vi.fn(async () => {})
    const w = await mountDetail(memberMemoryRights(), MEMORY_KIND, { renameTo })
    await w.get('h1 button[data-testid="node-header-rename"]').trigger('click')
    const field = w.get<HTMLInputElement>('input[data-testid="node-header-name-input"]')
    await field.setValue('Roma')
    await field.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(renameTo).toHaveBeenCalledExactlyOnceWith('Roma')
    const without = await mountDetail({ ...memberMemoryRights(), rename: false }, MEMORY_KIND, { renameTo })
    expect(without.find('h1').text(), 'control: the name').toBe('Rom 2026')
    expect(without.find('h1 button').exists()).toBe(false)
    expect((await mountDetail(memberMemoryRights())).find('h1 button').exists(), 'no door: plain text').toBe(false)
  })

  it('a pending deletion: share stays, every write goes', async () => {
    const w = await mountDetail(memberMemoryRights({ readonly: true }))
    expect(await header(w)).toEqual(['share.share'])
    expect(disabled(w, '.veyq-node-header-chip[data-fact="date"]')).toBe(true)
    await w.findAll('[role="tab"]')[1].trigger('mousedown')
    await flushPromises()
    expect(w.find('.memory-card-grid').exists(), 'control: the chapters grid renders').toBe(true)
    expect(w.find('.memory-create-card-memory').exists()).toBe(false)
  })

  // inbox #358: the move is the host's (core's move dialog), asked for through `move`; offered only with rights.move.
  it('Move to… asks the host for core\'s move dialog, and only with move', async () => {
    const w = await mountDetail(memberMemoryRights())
    expect(w.find(`.veyq-node-header-actions button[aria-label="${t('node.move.action')}"]`).exists(), 'a ⋯ entry, never a button').toBe(false)
    await chooseHeaderEntry(w, 'move')
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
