// @vitest-environment happy-dom
// The gallery's filter by facts on the member's Memory detail (uc.memory.gallery step 8): the field in the gallery's
// toolbar (the shell search's field), a day chip chosen by the keyboard narrowing the grid and the lightbox together, the
// text over the original's name, the combination with each mark filter, the empty state naming every filter in force, the
// state dropped when another Memory opens, and nothing written to any storage. The field's absence for a share guest is
// `MemoryDetail.guest.test.ts`'s (its member-only list), with this file's first test as its positive control.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn() }))
// The marks are the store's (`itemFavoritesStore.test.ts`); here they are a set the test names.
const marks = vi.hoisted(() => ({ mine: new Set<string>(), anyone: new Set<string>() }))
vi.mock('../itemFavoritesStore', async (orig) => ({
  ...(await orig<object>()),
  isMarkedByMe: vi.fn(),
  markedByAnyone: vi.fn(),
  markCount: vi.fn(),
  loadItemFavoritesFor: vi.fn(),
}))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { mountWithHeader } from '../../../test/nodeHeader'
import MemoryDetail from './MemoryDetail.vue'
import MediaLightbox from './MediaLightbox.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver, type MediaItem } from '../media'
import { readExifTags } from '../../../shared/image/exifTags'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi } from '../api'
import { fakeApi } from '../../../test/apiMock'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { MEMORY_KIND } from '../kinds'
import { isMarkedByMe, loadItemFavoritesFor, markCount, markedByAnyone } from '../itemFavoritesStore'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
// Noon UTC: the same calendar day in every zone a test machine runs in (the zone's edges are `galleryFilter.test.ts`').
const MAY_3 = Date.UTC(2024, 4, 3, 12)
const MAY_4 = Date.UTC(2024, 4, 4, 12)

const deriver = (): MediaDeriver => ({
  deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) => ({ bytes: new Uint8Array([0xff, 0xd8, maxEdge & 0xff]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])),
})

/** Three photos: two on 3 May, one on 4 May. Their ids by name. */
async function threePhotos(): Promise<{ key: Uint8Array; id: Record<string, string> }> {
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  const key = v1.randomBytes(32)
  for (const [name, at] of [['strand.jpg', MAY_3 + 60_000], ['kolosseum.jpg', MAY_3], ['forum.jpg', MAY_4]] as const) {
    vi.mocked(readExifTags).mockResolvedValueOnce({ DateTimeOriginal: at })
    await addMedia(SCOPE_ID, NODE_ID, key, { blob: new Blob([new Uint8Array(8).fill(name.length) as BlobPart]), mediaType: 'image/jpeg', name }, deriver())
  }
  const listed = (await mediaApi().listNodeMedia({ scopeId: SCOPE_ID, nodeId: NODE_ID })).media
  const names = ['strand.jpg', 'kolosseum.jpg', 'forum.jpg']
  return { key, id: Object.fromEntries(listed.map((m, i) => [names[i], m.mediaId])) }
}

function mountDetail(key: Uint8Array) {
  return mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Rom 2024' }),
      scopeId: SCOPE_ID, memoryKey: key, scopeKey: v1.randomBytes(32), personalScope: false, rights: memberMemoryRights({ publicShares: false }),
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
}
type Page = ReturnType<typeof mountDetail>

const field = () => document.body.querySelector<HTMLInputElement>('.gallery-fact-filter input.veyq-tags-input__input')
/** A node's text, its parts apart (the label, the chip's word). */
const words = (n: Node): string[] => (n.nodeType === 3 ? [n.textContent!.trim()].filter(Boolean) : [...n.childNodes].flatMap(words))
const options = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__option')].map((o) => words(o).join(' '))
const chips = () => [...document.body.querySelectorAll('.gallery-fact-filter .veyq-tags-input__label')].map((c) => c.textContent!.trim())
async function type(text: string) {
  const el = field()!
  el.focus()
  el.value = text
  el.setSelectionRange(text.length, text.length)
  el.dispatchEvent(new InputEvent('input', { data: text.slice(-1), bubbles: true }))
  await flushPromises()
}
async function press(key: string) {
  field()!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}
/** The day chip for `text`, taken as a member takes it: typed, then Enter on the highlighted suggestion. */
async function chooseDay(text: string, label: string) {
  await type(text)
  expect(options()[0]).toBe(`${label} ${t('media.filter.chipDay')}`)
  await press('Enter')
  expect(chips()).toContain(label)
}
const tiles = (w: Page) => w.findAll('[data-media-id]').map((e) => e.attributes('data-media-id'))
async function markFilter(w: Page, opt: 'all' | 'everyone' | 'mine') {
  await w.findAll('.gallery-mark-filter-option').find((b) => b.text() === t(`media.marks.filter.${opt}`))!.trigger('click')
  await flushPromises()
}
const empty = (w: Page) => w.find('.gallery-filter-empty')

async function opened(): Promise<{ w: Page; id: Record<string, string> }> {
  const { key, id } = await threePhotos()
  const w = mountDetail(key)
  await flushPromises()
  await flushPromises()
  await vi.waitFor(() => expect(w.findAll('[data-media-id]')).toHaveLength(3))
  return { w, id }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  marks.mine = new Set()
  marks.anyone = new Set()
  vi.mocked(isMarkedByMe).mockImplementation((id) => marks.mine.has(id))
  vi.mocked(markedByAnyone).mockImplementation(() => marks.anyone)
  vi.mocked(markCount).mockReturnValue(0)
  vi.mocked(loadItemFavoritesFor).mockResolvedValue(undefined)
  fakeApi(contentApi, { updateNode: vi.fn(async () => {}) })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('the gallery\'s filter by facts (uc.memory.gallery step 8)', () => {
  it('is in the member\'s gallery toolbar (the positive control of the guest\'s absence)', async () => {
    const { w } = await opened()
    expect(w.find('.gallery-toolbar .gallery-fact-filter').exists()).toBe(true)
    expect(field()?.getAttribute('aria-label')).toBe(t('media.filter.label'))
    w.unmount()
  })

  it('a day chip narrows the grid, the order kept, and the lightbox pages only through what is left', async () => {
    const { w, id } = await opened()
    expect(tiles(w)).toEqual([id['forum.jpg'], id['strand.jpg'], id['kolosseum.jpg']]) // newest first
    await chooseDay('may 3', 'May 3')
    expect(field()!.value, 'the chip took the text').toBe('')
    expect(tiles(w)).toEqual([id['strand.jpg'], id['kolosseum.jpg']])

    await w.find(`[data-media-id="${id['kolosseum.jpg']}"] .media-tile-open`).trigger('click')
    await flushPromises()
    const box = w.findComponent(MediaLightbox)
    expect(box.props('items').map((it: MediaItem) => it.mediaId)).toEqual([id['strand.jpg'], id['kolosseum.jpg']])
    expect(box.props('startIndex')).toBe(1)
    w.unmount()
  })

  it('the text narrows by the original\'s name while it is typed', async () => {
    const { w, id } = await opened()
    await type('kolos')
    expect(tiles(w)).toEqual([id['kolosseum.jpg']])
    await type('')
    expect(tiles(w)).toHaveLength(3)
    w.unmount()
  })

  it('combines with each mark filter: only what both keep stays', async () => {
    const { w, id } = await opened()
    marks.mine = new Set([id['kolosseum.jpg']])
    marks.anyone = new Set([id['kolosseum.jpg'], id['forum.jpg']])
    await chooseDay('may 3', 'May 3')

    await markFilter(w, 'mine')
    expect(tiles(w)).toEqual([id['kolosseum.jpg']])
    await markFilter(w, 'everyone')
    expect(tiles(w)).toEqual([id['kolosseum.jpg']])
    await markFilter(w, 'all')
    expect(tiles(w)).toEqual([id['strand.jpg'], id['kolosseum.jpg']])
    w.unmount()
  })

  it('an empty result names every filter in force; the mark filter alone keeps its own sentence', async () => {
    const { w, id } = await opened()
    marks.anyone = new Set([id['forum.jpg']])
    await markFilter(w, 'mine')
    expect(empty(w).text()).toBe(t('media.marks.emptyMine'))

    await chooseDay('may 4', 'May 4')
    await type('zzz')
    expect(tiles(w)).toEqual([])
    const named = new Intl.ListFormat('en', { type: 'conjunction' }).format([t('media.filter.marksMine'), 'May 4', t('media.filter.text', { text: 'zzz' })])
    expect(empty(w).text()).toBe(t('media.filter.empty', { filters: named }))
    expect(w.find('.gallery-fact-filter').exists(), 'the field stays to undo it').toBe(true)
    w.unmount()
  })

  it('is dropped when another Memory opens', async () => {
    const { w } = await opened()
    await chooseDay('may 3', 'May 3')
    await type('strand')
    // the page stand-in hands its attributes to the detail (`test/nodeHeader.ts`), which its props' type does not know
    await w.setProps({ node: treeNode({ id: 'another-memory', kind: MEMORY_KIND, name: 'Neapel' }) } as unknown as Record<string, never>)
    await flushPromises()
    expect(chips()).toEqual([])
    expect(field()?.value ?? '').toBe('')
    w.unmount()
  })

  it('writes nothing to any storage', async () => {
    // On each storage itself, not on `Storage.prototype`: in a run of the whole file a prototype spy stayed silent through
    // the component's own sessionStorage write (its bite stayed green), though it saw a call made here.
    const writes = [localStorage, sessionStorage].flatMap((s) => [vi.spyOn(s, 'setItem'), vi.spyOn(s, 'removeItem'), vi.spyOn(s, 'clear')])
    const idb = typeof indexedDB === 'undefined' ? null : vi.spyOn(indexedDB, 'open')
    const { w, id } = await opened()
    writes.forEach((s) => s.mockClear())
    await chooseDay('may 3', 'May 3')
    await type('kolos')
    await markFilter(w, 'mine')
    expect(tiles(w)).toEqual([])
    marks.mine = new Set([id['kolosseum.jpg']])
    await markFilter(w, 'all')
    for (const s of writes) expect(s).not.toHaveBeenCalled()
    if (idb) expect(idb).not.toHaveBeenCalled()
    // control: the spies see a write to either storage
    localStorage.setItem('probe', '1')
    sessionStorage.setItem('probe', '1')
    expect(writes[0]).toHaveBeenCalledWith('probe', '1')
    expect(writes[3]).toHaveBeenCalledWith('probe', '1')
    localStorage.removeItem('probe')
    sessionStorage.removeItem('probe')
    w.unmount()
  })
})
