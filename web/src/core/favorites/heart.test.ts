// @vitest-environment happy-dom
// The heart on EVERY kind, by real clicks (inbox #409): core's real `LevelView` with the real registry as `main.ts` fills it
// (Memories' and Files' real row factories), a folder, a Memory, a file and a drop box in one level. Each row carries the ONE
// heart core contributes, and so does the folder header; a click toggles the node through the real store, which seals the whole
// set under the member's Master Key and PUTs it (`saveFavorites`, mocked at the data layer - its wire is `favorites.test.ts`'s
// and `favorites.wire.test.ts`'s). A share guest's mount of the same level has no heart anywhere.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('./favorites', () => ({ loadFavorites: vi.fn(), saveFavorites: vi.fn(), resealFavoritesUnderNewMasterKey: vi.fn() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))
vi.mock('../../features/files/api', () => ({ filesApi: vi.fn() }))
vi.mock('../../features/memory/coverStore', () => ({
  covers: { get value() { return new Map() } },
  ensureCovers: vi.fn(),
  resetCovers: vi.fn(),
}))

import LevelView from '../node/LevelView.vue'
import { childrenOf } from '../node/browse'
import { FOLDER_KIND } from '../node/kind'
import type { TreeNode } from '../node/tree'
import type { Access } from '../access'
import { favoritesStore, clearFavorites, loadFavoritesFor } from './favoritesStore'
import { loadFavorites, saveFavorites } from './favorites'
import { masterKey } from '../../app/vault'
import { filesApi } from '../../features/files/api'
import { MEMORY_KIND } from '../../features/memory/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../features/files/kinds'
import { ShareRole } from '../../api/generated/sharing'
import type { FileListPage } from '../../api/generated/files'
import { stubApi } from '../../test/apiMock'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { rowOf } from '../../test/levelRows'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const MK = new Uint8Array(32).fill(9)
const node = (id: string, kind: string, name: string, parentId: string | null = null): TreeNode =>
  treeNode({ id, kind, name, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })

const TRIPS = node('d0000000-0000-4000-8000-000000000001', FOLDER_KIND, 'Trips')
const ROME = node('d0000000-0000-4000-8000-000000000002', MEMORY_KIND, 'Rome', TRIPS.id)
const PLAN = node('d0000000-0000-4000-8000-000000000003', FILE_KIND, 'Plan.pdf', TRIPS.id)
const BOX = node('d0000000-0000-4000-8000-000000000004', DROPBOX_KIND, 'Inbox', TRIPS.id)
const SUB = node('d0000000-0000-4000-8000-000000000005', FOLDER_KIND, 'Sub', TRIPS.id)
const TREE = [TRIPS, ROME, PLAN, BOX, SUB]

function mountLevel(folder: TreeNode | null, access: Access = memberAccessOf({ scopeId: SCOPE })) {
  const tree = memberTreeOf(() => TREE)
  return mount(LevelView, {
    props: { folder, level: childrenOf(TREE, folder?.id ?? null), access, tree },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}
const heartIn = (el: ReturnType<typeof rowOf>) => el.find('[data-action="favorite"]')
const headerHeart = (w: ReturnType<typeof mountLevel>) => w.find('.veyq-node-header-actions [data-action="favorite"]')

beforeEach(async () => {
  vi.clearAllMocks()
  clearFavorites()
  vi.mocked(masterKey).mockReturnValue(MK)
  vi.mocked(loadFavorites).mockResolvedValue(new Set())
  vi.mocked(saveFavorites).mockResolvedValue(undefined)
  stubApi(filesApi, { listFiles: { items: [] } satisfies FileListPage })
  await loadFavoritesFor(SCOPE)
})

describe('the heart in a level (real clicks, real registry)', () => {
  it('every kind\'s row has one heart, first, unpressed: a folder, a Memory, a file and a drop box', async () => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    for (const title of ['Sub', 'Rome', 'Plan.pdf', 'Inbox']) {
      const hearts = rowOf(w, title).findAll('[data-action="favorite"]')
      expect(hearts, title).toHaveLength(1)
      expect(hearts[0].attributes('aria-pressed'), title).toBe('false')
      expect(hearts[0].attributes('aria-label'), title).toBe(t('node.favorite'))
      expect(hearts[0].classes(), title).not.toContain('is-pressed')
    }
  })

  it.each([['Sub', 'FOLDER'], ['Rome', 'MEMORY'], ['Plan.pdf', 'FILE'], ['Inbox', 'FILES_DROPBOX']])(
    'a click on %s (%s) pins it: the whole set is sealed and stored, the heart turns pressed; a second click unpins',
    async (title, kind) => {
      const id = TREE.find((n) => n.name === title)!.id
      const w = mountLevel(TRIPS)
      await flushPromises()
      await heartIn(rowOf(w, title)).trigger('click')
      await flushPromises()
      expect(saveFavorites, kind).toHaveBeenLastCalledWith(SCOPE, MK, new Set([id]))
      const pressed = heartIn(rowOf(w, title))
      expect(pressed.attributes('aria-pressed')).toBe('true')
      expect(pressed.attributes('aria-label')).toBe(t('node.unfavorite'))
      expect(pressed.classes()).toContain('is-pressed')
      expect(pressed.find('.pi-heart-fill').exists()).toBe(true)
      expect(favoritesStore.ids.has(id)).toBe(true)

      await heartIn(rowOf(w, title)).trigger('click')
      await flushPromises()
      expect(saveFavorites).toHaveBeenLastCalledWith(SCOPE, MK, new Set())
      expect(heartIn(rowOf(w, title)).attributes('aria-pressed')).toBe('false')
    },
  )

  it('the folder on screen has the heart in its header; a click pins it and its row in the parent level shows it pressed', async () => {
    const w = mountLevel(TRIPS)
    await flushPromises()
    expect(headerHeart(w).attributes('aria-pressed')).toBe('false')
    await headerHeart(w).trigger('click')
    await flushPromises()
    expect(saveFavorites).toHaveBeenLastCalledWith(SCOPE, MK, new Set([TRIPS.id]))
    expect(headerHeart(w).attributes('aria-pressed')).toBe('true')
    const root = mountLevel(null)
    await flushPromises()
    expect(heartIn(rowOf(root, 'Trips')).attributes('aria-pressed')).toBe('true')
  })

  it('the scope root has no header and so no heart there; its rows do', async () => {
    const w = mountLevel(null)
    await flushPromises()
    expect(w.find('.veyq-node-header').exists()).toBe(false)
    expect(heartIn(rowOf(w, 'Trips')).exists()).toBe(true)
  })

  it('a pending deletion keeps the heart, as Memories always did (a pin is the member\'s own preference)', async () => {
    const w = mountLevel(TRIPS, memberAccessOf({ scopeId: SCOPE, readonly: true }))
    await flushPromises()
    expect(heartIn(rowOf(w, 'Rome')).exists()).toBe(true)
    expect(headerHeart(w).exists()).toBe(true)
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite])('a %s share guest\'s mount of the same level has no heart in a row or the header', async (role) => {
    const w = mountLevel(TRIPS, guestAccessOf(role))
    await flushPromises()
    expect(w.findAll('li.veyq-level-item').length).toBeGreaterThan(0)
    expect(w.find('[data-action="favorite"]').exists()).toBe(false)
    expect(w.findAll('button').some((b) => [t('node.favorite'), t('node.unfavorite')].includes(b.attributes('aria-label') ?? ''))).toBe(false)
    expect(saveFavorites).not.toHaveBeenCalled()
  })
})
