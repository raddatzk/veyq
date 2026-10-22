// @vitest-environment happy-dom
// A member's rename through the personal-space host, over the REAL registry (Memories and Depot, as `main.ts` fills it),
// the real router, the UI layer, core's real level view, overview card and name dialog — every step a DOM click. The position
// lives in the #fragment as a slug path derived from the names (ADR 0015), so a rename of the node on screen changes its
// own path — and a restore of an older sibling of one plain segment renumbers it: the URL must follow it, or the old path
// resolves to nothing and the view collapses to the scope root (found by the read-only assessment of inbox #328). The
// writes are recorders (`renameNode`, `restoreNode`; their wire is `core/node/tree.wire.test.ts`'s) and the re-read is
// `loadTree`'s stub. Only the heavy Memories detail is a stub exposing the events the host listens to; the level's rows
// (inbox #407: one list, the applications' real row factories) are real, Depot's blob listing is an empty stub.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: 'scope-1', tenantId: 't-1', kind: 'PERSONAL', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(1)]]) }),
  }
})
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() }, loadFavoritesFor: vi.fn(), isFavorite: vi.fn(), toggleFavorite: vi.fn(async () => {}),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../shared/state/useToast', () => ({ useToast: () => toast }))
vi.mock('../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}), patchNodeMeta: vi.fn() }
})
vi.mock('../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../core/node/tree')>()),
  renameNode: vi.fn(async () => {}),
  listTrash: vi.fn(async () => []),
  restoreNode: vi.fn(async () => {}),
  nodeContentKey: () => new Uint8Array(32).fill(9),
  parentContentKey: () => new Uint8Array(32).fill(9), // the key a Memory row opens its meta under
}))
vi.mock('../core/sharing/manageShares', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../core/sharing/manageShares')>()),
  resealShareNames: vi.fn(async () => ({ resealed: [], failed: [], skipped: [] })),
}))
vi.mock('../features/memories/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'rights', 'source'],
      emits: ['rename'],
      setup: (p, { emit }) => () =>
        h('div', { class: 'detail-stub' }, [h('h1', p.node.name), h('button', { class: 'detail-rename', onClick: () => emit('rename') }, 'rename')]),
    }),
  }
})
vi.mock('../features/depot/api', () => ({ filesApi: vi.fn() }))

import MemoryHomeView from './MemoryHomeView.vue'
import { treeStore, loadTree } from '../core/node/treeStore'
import { renameNode, listTrash, restoreNode, type TreeNode, type TrashedNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memories/kinds'
import { FILE_KIND } from '../features/depot/kinds'
import { realI18n, t } from '../test/i18n'
import { treeNode } from '../test/models'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../core/favorites/favoritesStore'
import { stubApi } from '../test/apiMock'
import { filesApi } from '../features/depot/api'
import { clickAction, titlesOf } from '../test/levelRows'

const node = (id: string, kind: string, parentId: string | null, name: string, createdAt: number): TreeNode =>
  treeNode({ id, kind, name, parentId, createdAt })
// Trips/ ─ x.y (a file, the older)   Rome/ ─ Forum/   Italy (a Memory)   Archive/
const TREE = (): TreeNode[] => [
  node('trips', FOLDER_KIND, null, 'Trips', 1), node('xy', FILE_KIND, 'trips', 'x.y', 2), node('rome', FOLDER_KIND, 'trips', 'Rome', 3),
  node('forum', FOLDER_KIND, 'rome', 'Forum', 4), node('italy', MEMORY_KIND, 'trips', 'Italy', 5), node('archive', FOLDER_KIND, null, 'Archive', 6),
]
/** What the next re-read of the tree holds once the server took the write (`renames`: node id → its new name). The write
 *  itself (`renameNode`, `restoreNode`) leaves the loaded tree alone, as the real one does: only the re-read changes it. */
function reReadBrings(change: (nodes: TreeNode[]) => TreeNode[]) {
  vi.mocked(loadTree).mockImplementationOnce(async () => {
    treeStore.nodes = change(treeStore.nodes)
  })
}
const serverRenames = (renames: Record<string, string>) =>
  reReadBrings((nodes) => nodes.map((n) => (renames[n.id] ? { ...n, name: renames[n.id] } : n)))

let router: Router
async function mountAt(hash: string) {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const w = mount(MemoryHomeView, { attachTo: document.body, global: { plugins: [router, realI18n()] } })
  await flushPromises()
  return w
}
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const byLabel = (label: string) => [...document.body.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.getAttribute('aria-label') === label)
const heading = () => document.body.querySelector('.veyq-node-header-title')?.textContent ?? null
/** Type the new name into core's open name dialog and press its Save. */
async function renameTo(name: string) {
  const input = document.body.querySelector<HTMLInputElement>('.veyq-form input')!
  input.value = name
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  await click([...document.body.querySelectorAll('.veyq-form button')].find((b) => b.textContent?.trim() === t('common.save'))!)
}

beforeEach(() => {
  document.body.innerHTML = ''
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockReturnValue(false)
  stubApi(filesApi, { listFiles: { items: [] } })
  treeStore.nodes = TREE()
  vi.mocked(renameNode).mockReset()
  vi.mocked(restoreNode).mockReset()
  vi.mocked(loadTree).mockReset()
})

describe('a member renames the node on screen', () => {
  it('a nested folder from its overview card: the view stays on it and the fragment becomes its new path, no history entry', async () => {
    await mountAt('#~me/trips/rome/')
    expect(heading()).toBe('Rome')
    const push = vi.spyOn(router, 'push')
    await click(byLabel(t('node.rename'))!)
    serverRenames({ rome: 'Roma Antica' })
    await renameTo('Roma Antica')
    expect(renameNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'rome', 'Roma Antica')
    expect(loadTree).toHaveBeenCalled()
    expect(router.currentRoute.value.hash, 'the folder on screen is followed to its new path').toBe('#~me/trips/roma-antica/')
    expect(heading(), 'still on the renamed folder, not the scope root').toBe('Roma Antica')
    expect(push, 'the same logical position: replaced, not pushed').not.toHaveBeenCalled()
  })

  it('the dialog closes only once the fragment follows — a reload the moment it is gone opens the renamed folder (inbox #475)', async () => {
    // The move's dialog re-reads the tree before it closes (`move.ts` `memberMover`); the rename's closed first and the
    // host re-read after, so for one tree read the dialog was gone and the URL still the OLD path — a reload in that
    // window (lane L1's, under load) resolved nothing and stood on the scope root.
    await mountAt('#~me/trips/rome/')
    await click(byLabel(t('node.rename'))!)
    let release!: () => void
    vi.mocked(loadTree).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          release = () => {
            treeStore.nodes = treeStore.nodes.map((n) => (n.id === 'rome' ? { ...n, name: 'Roma Antica' } : n))
            resolve()
          }
        }),
    )
    await renameTo('Roma Antica')
    expect(renameNode).toHaveBeenCalled()
    expect(document.body.querySelector('.veyq-form'), 'open while the tree is re-read').not.toBeNull()
    expect(router.currentRoute.value.hash).toBe('#~me/trips/rome/')
    release()
    await flushPromises()
    expect(document.body.querySelector('.veyq-form'), 'closed once the re-read is in').toBeNull()
    expect(router.currentRoute.value.hash, 'the URL followed before the dialog went').toBe('#~me/trips/roma-antica/')
  })

  it('an opened Memory from its detail: the detail stays open under the new path', async () => {
    await mountAt('#~me/trips/memories/italy')
    await click(document.body.querySelector('.detail-rename')!)
    serverRenames({ italy: 'Italia' })
    await renameTo('Italia')
    expect(router.currentRoute.value.hash).toBe('#~me/trips/memories/italia')
    expect(document.body.querySelector('.detail-stub h1')?.textContent).toBe('Italia')
  })

  it('a folder renamed to a file\'s name keeps the plain segment — the trailing slash tells them apart', async () => {
    // the file `x.y` and the folder `X.Y` share the URL segment `x-y` but are separate numbering spaces (ADR 0015)
    await mountAt('#~me/trips/rome/')
    await click(byLabel(t('node.rename'))!)
    serverRenames({ rome: 'X.Y' })
    await renameTo('X.Y')
    expect(router.currentRoute.value.hash).toBe('#~me/trips/x-y/')
    expect(heading(), 'the folder, not the file of the same segment').toBe('X.Y')
  })
})

describe('a rename that changes an ancestor of the open node', () => {
  // This host renames only the node on screen (its card or detail) or a row of the level on screen, so an ancestor of
  // the open node is never the one renamed HERE; the re-read tree can still carry one renamed elsewhere (another device
  // of the member, another member). The URL is rebuilt from the whole re-read path, not patched at the renamed segment.
  it('the re-read tree also brings an ancestor\'s new name: the fragment is the open node\'s whole new path', async () => {
    await mountAt('#~me/trips/rome/forum/')
    await click(byLabel(t('node.rename'))!)
    serverRenames({ forum: 'Foro', trips: 'Travel' })
    await renameTo('Foro')
    expect(router.currentRoute.value.hash).toBe('#~me/travel/rome/foro/')
    expect(heading()).toBe('Foro')
  })
})

// A node keeps its creation instant through a trash and a restore, so an older sibling that comes back takes its birth
// place among the siblings of one plain segment and shifts the later ones' (`location.ts` `segmentsOf`): the same
// collapse, reached through the space's trash.
describe('a restore that renumbers an ancestor of the open node', () => {
  it('the older sibling comes back: the fragment follows the open node to its renumbered path', async () => {
    // two folders of one URL segment `x-y` (in real data two drop-box guests' identity folders, whose slug tokens the server
    // cannot see collide; the numbering is kind-neutral): the older one is trashed while the younger is open
    treeStore.nodes = [
      node('trips', FOLDER_KIND, null, 'Trips', 1), node('xyfolder', FOLDER_KIND, 'trips', 'x-y', 5), node('deep', FOLDER_KIND, 'xyfolder', 'Deep', 6),
    ]
    const trashed: TrashedNode = {
      id: 'xyold', parentId: 'trips', kind: FOLDER_KIND, epoch: 0, placementRevision: 0, name: 'x_y',
      deletedAt: new Date(0), purgeAfter: new Date(0),
    } as TrashedNode
    vi.mocked(listTrash).mockResolvedValueOnce([trashed]).mockResolvedValue([])
    reReadBrings((nodes) => [...nodes, node('xyold', FOLDER_KIND, 'trips', 'x_y', 2)])
    await mountAt('#~me/trips/x-y/deep/')
    expect(heading()).toBe('Deep')
    await click([...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('node.trash.open'))!)
    await click([...document.body.querySelectorAll('.trash-row button')].find((b) => b.textContent?.trim() === t('node.trash.restore'))!)
    expect(restoreNode).toHaveBeenCalled()
    expect(router.currentRoute.value.hash).toBe('#~me/trips/x-y-2/deep/')
    expect(heading()).toBe('Deep')
  })
})

describe('a rename of a row on the level', () => {
  it('leaves the level where it is', async () => {
    const w = await mountAt('#~me/trips/')
    await clickAction(w, 'x.y', 'rename') // Depot's row: a real click on its entry in the row's menu
    serverRenames({ xy: 'notes.txt' })
    await renameTo('notes.txt')
    expect(renameNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'xy', 'notes.txt')
    expect(router.currentRoute.value.hash).toBe('#~me/trips/')
    expect(heading()).toBe('Trips')
    // the list mixes the non-folders by name now (the Memory beside the file), the folder first
    expect(titlesOf(w)).toEqual(['Rome', 'Italy', 'notes.txt'])
  })
})
