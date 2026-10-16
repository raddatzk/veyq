// @vitest-environment happy-dom
// A member's move through the personal-space host (inbox #358) - and a level's selection moved and deleted (inbox #421) - over the REAL registry (Memories and Depot, as `main.ts`
// fills it), the real router, PrimeVue, core's real level view, move dialog and confirmation dialog, Depot's real drop-box
// detail and Memories' real detail adapter — every step a DOM click. The placement write is a recorder (`moveNode`): its
// wire is `core/node/move.wire.test.ts`'s. Only the heavy Memories detail view is a stub that exposes the events the host
// listens to; the level's rows (inbox #407: one list, the applications' real row factories) are real, Depot's blob
// listing is an empty stub.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'

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
  favoritesStore: { loaded: true, ids: new Set<string>() }, loadFavoritesFor: vi.fn(), isFavorite: vi.fn(), toggleFavorite: vi.fn(),
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
  moveNode: vi.fn(async () => {}),
  deleteNode: vi.fn(async () => {}),
  restoreNode: vi.fn(async () => {}),
  nodeContentKey: () => new Uint8Array(32).fill(9),
  parentContentKey: () => new Uint8Array(32).fill(9), // the key a Memory row opens its meta under
}))
vi.mock('../features/memory/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'rights', 'source'],
      emits: ['move'],
      setup: (p, { emit }) => () => h('div', { class: 'detail-stub' }, p.rights.move ? [h('button', { class: 'detail-move', onClick: () => emit('move') }, 'move')] : []),
    }),
  }
})
vi.mock('../features/depot/api', () => ({ filesApi: vi.fn() }))

import MemoryHomeView from './MemoryHomeView.vue'
import { treeStore, loadTree } from '../core/node/treeStore'
import { deleteNode, moveNode, restoreNode, type TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memory/kinds'
import { DROPBOX_KIND, FILE_KIND } from '../features/depot/kinds'
import { realI18n, t } from '../test/i18n'
import { treeNode } from '../test/models'
import { mountConfirmDialog } from '../test/confirmDialog'
import { hashForNode } from '../core/node/location'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../core/favorites/favoritesStore'
import { stubApi } from '../test/apiMock'
import { filesApi } from '../features/depot/api'
import { clickAction } from '../test/levelRows'
import { browserOpen as dialogOpen, crumbs, goTo, moveHere, placeNames } from '../test/moveBrowser'
import { deleteSelection, moveSelection, selectRows, startSelecting } from '../test/levelSelection'

const node = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Trips/ ─ Rome/   Archive/   Inbox (drop box) ─ Anna/ (a guest's folder) ─ scan.pdf     Italy (Memory) in Trips
const TREE = (): TreeNode[] => [
  node('trips', FOLDER_KIND, null, 'Trips'), node('rome', FOLDER_KIND, 'trips', 'Rome'), node('archive', FOLDER_KIND, null, 'Archive'),
  node('italy', MEMORY_KIND, 'trips', 'Italy'), node('notes', FILE_KIND, 'trips', 'notes.txt'),
  node('inbox', DROPBOX_KIND, null, 'Inbox'), node('anna', FOLDER_KIND, 'inbox', 'Anna', { shareIdentityId: 'identity-1' }),
  node('scan', FILE_KIND, 'anna', 'scan.pdf'),
]

let router: Router
async function mountAt(hash: string) {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const w = mount(MemoryHomeView, { attachTo: document.body, global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } } })
  await flushPromises()
  return w
}
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const byLabel = (label: string) => [...document.body.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.getAttribute('aria-label') === label)
beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockReturnValue(false)
  stubApi(filesApi, { listFiles: { items: [] } })
  treeStore.nodes = TREE()
  vi.mocked(moveNode).mockClear()
  vi.mocked(loadTree).mockClear()
  for (const f of Object.values(toast)) f.mockClear()
})

describe('a member moves a folder from its card', () => {
  it('Move to… opens core\'s dialog, the pick goes through moveNode, the URL follows the folder on screen, Undo moves it back', async () => {
    await mountAt('#~me/trips/rome/')
    expect(document.body.querySelector('.veyq-node-header-title')!.textContent).toBe('Rome')
    await click(byLabel(t('node.move.action'))!)
    expect(dialogOpen()).toBe(true)
    // it opens where the folder is (the Memory there takes no folder); up at the space's root: the folders, no drop box
    expect(crumbs()).toEqual([t('shell.personal'), 'Trips'])
    expect(placeNames()).toEqual(['Rome'])
    await goTo(t('shell.personal'))
    expect(placeNames(), 'no drop box, no guest folder').toEqual(['Archive', 'Trips'])
    // the server's answer, as the re-read tree then holds it
    vi.mocked(moveNode).mockImplementationOnce(async () => {
      treeStore.nodes = treeStore.nodes.map((n) => (n.id === 'rome' ? { ...n, parentId: 'archive' } : n))
    })
    await moveHere('Archive')
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'rome', 'archive')
    expect(loadTree).toHaveBeenCalled()
    expect(dialogOpen(), 'closed after the move').toBe(false)
    expect(router.currentRoute.value.hash, 'the folder on screen is followed to its new path').toBe(hashForNode(treeStore.nodes, 'rome', '~me'))
    expect(router.currentRoute.value.hash).toBe('#~me/archive/rome/')
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.moved', { name: 'Rome', to: 'Archive' }) }))
    // Undo: back to Trips, and the URL with it
    vi.mocked(moveNode).mockImplementationOnce(async () => {
      treeStore.nodes = treeStore.nodes.map((n) => (n.id === 'rome' ? { ...n, parentId: 'trips' } : n))
    })
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(moveNode).toHaveBeenLastCalledWith('scope-1', expect.anything(), expect.any(Array), 'rome', 'trips')
    expect(router.currentRoute.value.hash).toBe('#~me/trips/rome/')
  })

  it('a row\'s move (Depot\'s file, by its real menu entry) and an opened Memory ask the host for the same dialog', async () => {
    const w = await mountAt('#~me/trips/')
    await clickAction(w, 'notes.txt', 'move')
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'notes.txt' }))
    await moveHere(t('shell.personal'), 'Archive')
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'notes', 'archive')
    expect(router.currentRoute.value.hash, 'a row moved away: the level stays').toBe('#~me/trips/')

    await router.push({ hash: '#~me/trips/memories/italy' })
    await flushPromises()
    await click(document.body.querySelector('.detail-move')!)
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'Italy' }))
    await moveHere(t('shell.personal'))
    expect(moveNode).toHaveBeenLastCalledWith('scope-1', expect.anything(), expect.any(Array), 'italy', null)
  })
})

// inbox #421: a member moves a mixed selection of a level - a folder, a Memory, a file - through core's dialog for several.
describe('a member moves a selection of the level', () => {
  it('Select, tick a folder, a Memory and a file, Move to…: one write each through moveNode, the tree re-read once, one Undo', async () => {
    await mountAt('#~me/trips/')
    await startSelecting()
    await selectRows('Rome', 'Italy', 'notes.txt')
    await moveSelection()
    expect(document.body.textContent).toContain(t('node.move.many.title', { count: 3 }))
    expect(crumbs()).toEqual([t('shell.personal'), 'Trips'])
    await moveHere(t('shell.personal'), 'Archive')
    expect(vi.mocked(moveNode).mock.calls.map((c) => [c[3], c[4]]).sort()).toEqual([['italy', 'archive'], ['notes', 'archive'], ['rome', 'archive']])
    expect(loadTree, 'one re-read for the three').toHaveBeenCalledTimes(1)
    expect(dialogOpen(), 'closed: all of them moved').toBe(false)
    expect(router.currentRoute.value.hash, 'the level on screen stays').toBe('#~me/trips/')
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.many.moved', { count: 3, to: 'Archive' }) }))
    vi.mocked(moveNode).mockClear()
    vi.mocked(loadTree).mockClear()
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(vi.mocked(moveNode).mock.calls.map((c) => [c[3], c[4]]).sort()).toEqual([['italy', 'trips'], ['notes', 'trips'], ['rome', 'trips']])
    expect(loadTree).toHaveBeenCalledTimes(1)
  })
})

describe('a member deletes a selection of the level', () => {
  it('Select, tick a folder, a Memory and a file, Delete: one question, each through its door, one re-read, one Undo', async () => {
    vi.mocked(deleteNode).mockClear()
    vi.mocked(restoreNode).mockClear()
    await mountAt('#~me/trips/')
    await startSelecting()
    await selectRows('Rome', 'Italy', 'notes.txt')
    await deleteSelection()
    const dialog = () => document.body.querySelector('.delete-nodes-dialog')
    expect(dialog()!.textContent).toContain(t('node.deleteMany.title', { count: 3 }))
    expect(deleteNode, 'nothing before the answer').not.toHaveBeenCalled()
    const confirm = [...dialog()!.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('node.delete'))!
    await click(confirm)
    // the folder and the Memory through core's delete, the file through Depot's source (which writes core's delete too)
    expect(vi.mocked(deleteNode).mock.calls.map((c) => c[1]).sort()).toEqual(['italy', 'notes', 'rome'])
    expect(loadTree, 'one re-read for the three').toHaveBeenCalledTimes(1)
    expect(dialog(), 'closed').toBeNull()
    expect(router.currentRoute.value.hash, 'the level on screen stays').toBe('#~me/trips/')
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.deleteMany.moved', { count: 3 }, 3) }))
    vi.mocked(loadTree).mockClear()
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(vi.mocked(restoreNode).mock.calls.map((c) => c[3].id).sort()).toEqual(['italy', 'notes', 'rome'])
    expect(loadTree).toHaveBeenCalledTimes(1)
  })
})

// ADR 0080 decision 11, inbox #377 (a): a member moves a guest's folder OUT of a drop box; its identity ends.
describe('a member moves a guest\'s folder out of a drop box', () => {
  it('from the drop box\'s row: asked first, then moved; no undo', async () => {
    const confirm = mountConfirmDialog()
    await mountAt('#~me/inbox')
    await click(byLabel(t('depot.dropBox.moveFolder', { name: 'Anna' }))!)
    expect(crumbs(), 'it opens in the drop box').toEqual([t('shell.personal'), 'Inbox'])
    expect(placeNames(), 'no guest folder is offered').toEqual([])
    await moveHere(t('shell.personal'), 'Archive')
    expect(confirm.text()).toContain(t('node.move.identityConfirm', { name: 'Anna' }))
    expect(moveNode, 'nothing before the answer').not.toHaveBeenCalled()
    confirm.click(t('node.move.identityAccept'))
    await flushPromises()
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'anna', 'archive')
    expect(toast.success).toHaveBeenCalledWith(t('node.move.moved', { name: 'Anna', to: 'Archive' }))
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('from the guest\'s folder itself: its card offers the move out (and core\'s heart, copy and download), and nothing else', async () => {
    await mountAt('#~me/inbox/anna/')
    const actions = [...document.body.querySelectorAll('.veyq-node-header-actions button')].map((b) => b.getAttribute('aria-label'))
    // a copy of it is an ordinary folder elsewhere (uc.platform.copy-node); the folder itself stays the guest's
    expect(actions).toEqual([t('node.favorite'), t('node.move.action'), t('node.copy.action'), t('export.action')])
  })

  it('the drop box itself moves from its card, like any node', async () => {
    await mountAt('#~me/inbox')
    await click(byLabel(t('node.move.action'))!)
    await moveHere('Trips')
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'inbox', 'trips')
  })
})
