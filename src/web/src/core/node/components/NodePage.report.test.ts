// @vitest-environment happy-dom
// Report on EVERY node's page (uc.moderation.report, ADR 0093 § 1 - a legal duty, DSA Art. 16: whoever sees a node can
// report it). Core supplies its header actions centrally (ADR 0110: `coreNodeActions.ts`, `NodePage.vue`), so this walks the
// REAL registry as `main.ts` fills it (`src/test/setup.ts`) and mounts every registered kind's real `detail` inside core's
// frame, exactly as both hosts do, for a member whose keys are open: each page's header must offer Report in its ⋯ menu, the
// menu's last group before delete. The folder level is core's own page and is asked too. A kind registered later is walked
// the day it registers - nothing here names the applications but the anchor that the walk saw them.
//
// The network each page reads on mount is stubbed at its data layer (a gallery, a file listing, a Pot's revisions, the
// people); none of it decides the header.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../../../features/memories/api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../../../features/depot/api', () => ({ filesApi: vi.fn() }))
vi.mock('../../../shared/image/exifTags', () => ({ readExifTags: vi.fn(async () => undefined) }))
vi.mock('../../favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
vi.mock('../../people/directory', async (orig) => ({ ...(await orig<object>()), loadScopePeople: vi.fn(async () => []), loadTenantPeople: vi.fn(async () => new Map()) }))
vi.mock('../../../features/expenses/domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn() }))
vi.mock('../../../features/expenses/receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn() }))
// The instance publishes a moderation key, so Report is offered (core's `useReportOffered`); its dialog is not this test's.
vi.mock('../../report/report', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<object>()), useReportOffered: () => ref(true), openReport: vi.fn() }
})

import { flushPromises, mount } from '@vue/test-utils'
import { registry } from '../../applications/registry'
import { FOLDER_KIND } from '../kind'
import { childrenOf } from '../browse'
import LevelView from '../LevelView.vue'
import { blobApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../../../features/memories/api'
import { filesApi } from '../../../features/depot/api'
import { MEMORY_KIND, CHAPTER_KIND } from '../../../features/memories/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../../features/depot/kinds'
import { POT_KIND } from '../../../features/expenses/kinds'
import { readMemberPot } from '../../../features/expenses/domain/potApi'
import { readMemberReceipts } from '../../../features/expenses/receipts'
import { openReport } from '../../report/report'
import { epochsOf } from '../../../features/spaces/epochsTestUtil'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { galleryFakeStore } from '../../../features/memories/components/galleryFakeStore'
import { realI18n } from '../../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'
import { chooseHeaderEntry, headerMenuIds } from '../../../test/nodeHeader'
import { mountNodePage } from '../../../test/nodePage'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const EPOCHS = epochsOf(new Uint8Array(32).fill(4))
const KEY = new Uint8Array(32).fill(5)
const FOLDER = treeNode({ id: 'e0000000-0000-4000-8000-000000000001', kind: FOLDER_KIND, name: 'Trips', parentId: null })
const nodeOf = (kind: string) => treeNode({ id: 'e0000000-0000-4000-8000-000000000002', kind, name: `A ${kind}`, parentId: FOLDER.id })
const member = () => memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS })

/** Every registered kind with a page of its own (FOLDER has none: its page is the level). */
const pageKinds = () => registry.kinds().filter((k) => {
  const d = registry.kind(k)
  return d.supported && !!d.detail
})

beforeEach(() => {
  vi.clearAllMocks()
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  stubApi(filesApi, { listFiles: { items: [] } })
  vi.mocked(readMemberPot).mockRejectedValue(new Error('no Pot read in this test'))
  vi.mocked(readMemberReceipts).mockResolvedValue({ receipts: [], etag: null })
  const store = galleryFakeStore()
  fakeApi(mediaApi, store.api)
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.unstubAllGlobals()
})

async function mountPage(kind: string) {
  const node = nodeOf(kind)
  const d = registry.kind(kind)
  if (!d.supported || !d.detail) throw new Error(`${kind} has no page`)
  const w = mountNodePage(d.detail, {
    props: {
      node, scopeId: SCOPE, epochs: EPOCHS, contentKey: KEY, children: { folders: [], byKind: new Map() },
      access: member(), tree: { ...memberTreeOf(() => [FOLDER, node], { scopeId: SCOPE, epochs: EPOCHS }), keyOf: () => KEY },
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  await flushPromises()
  return w
}

describe('Report on every node\'s page (a legal duty, DSA Art. 16)', () => {
  it('walks every application\'s kinds (the anchor: a walk that saw none proves nothing)', () => {
    expect(pageKinds()).toEqual(expect.arrayContaining([MEMORY_KIND, CHAPTER_KIND, FILE_KIND, DROPBOX_KIND, POT_KIND]))
  })

  // One test that walks the kinds at RUN time: the registry is filled in the setup's `beforeAll`, after this file's
  // collection, so an `it.each` over it would collect no case at all - and pass.
  it('every kind\'s page offers Report in its header\'s ⋯ menu, the last group before delete, and it reports THAT node', async () => {
    const kinds = pageKinds()
    expect(kinds.length).toBeGreaterThanOrEqual(5)
    for (const kind of kinds) {
      vi.mocked(openReport).mockClear()
      const w = await mountPage(kind)
      expect(w.find('.veyq-node-header').exists(), `${kind}'s page draws core's header`).toBe(true)
      const menu = await headerMenuIds(w)
      expect(menu, `${kind}'s header menu`).toContain('report')
      expect(menu.slice(menu.indexOf('report')), `${kind}: report, then delete at most`).toEqual(menu.includes('delete') ? ['report', 'delete'] : ['report'])
      await chooseHeaderEntry(w, 'report')
      expect(vi.mocked(openReport), `${kind}: the report opens`).toHaveBeenCalledOnce()
      expect(vi.mocked(openReport).mock.calls[0]![0].targets.map((x) => [x.kind, 'id' in x ? x.id : null]), kind).toEqual([['NODE', nodeOf(kind).id]])
      w.unmount()
    }
  })

  it('a folder\'s page - core\'s level - offers it too', async () => {
    const tree = memberTreeOf(() => [FOLDER], { scopeId: SCOPE, epochs: EPOCHS })
    const w = mount(LevelView, {
      props: { folder: FOLDER, level: childrenOf([FOLDER], FOLDER.id), access: member(), tree },
      global: { plugins: [realI18n()] },
      attachTo: document.body,
    })
    await flushPromises()
    const menu = await headerMenuIds(w)
    expect(menu.slice(menu.indexOf('report'))).toEqual(['report', 'delete'])
  })
})
