// @vitest-environment happy-dom
// A member's move through the personal-space host (inbox #358), over the REAL registry (Memories and Files, as `main.ts`
// fills it), the real router, PrimeVue, core's real level view, move dialog and confirmation dialog, Files' real drop-box
// detail and Memories' real detail adapter — every step a DOM click. The placement write is a recorder (`moveNode`): its
// wire is `core/node/move.wire.test.ts`'s. Only the heavy Memories views and Files' file list are stubs that expose the
// events the level view listens to.
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
vi.mock('../features/memory/favoritesStore', () => ({
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
  nodeContentKey: () => new Uint8Array(32).fill(9),
}))
vi.mock('../features/memory/components/MemoryDetail.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'MemoryDetailStub',
      props: ['node', 'scopeId', 'memoryKey', 'scopeKey', 'personalScope', 'favorite', 'chapters', 'caps', 'source'],
      emits: ['move'],
      setup: (p, { emit }) => () => h('div', { class: 'detail-stub' }, p.caps.move ? [h('button', { class: 'detail-move', onClick: () => emit('move') }, 'move')] : []),
    }),
  }
})
vi.mock('../features/memory/components/MemoriesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ props: ['folder', 'nodes', 'epochs', 'access', 'tree', 'viewMode'], setup: () => () => h('section', { class: 'memories-stub' }) }) }
})
vi.mock('../features/files/components/FilesSection.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['folder', 'nodes', 'epochs', 'access', 'tree', 'viewMode'],
      emits: ['move'],
      setup: (p, { emit }) => () => h('section', { class: 'files-stub' },
        (p.nodes as { id: string; name: string }[]).map((n) => h('button', { class: 'file-move', 'data-id': n.id, onClick: () => emit('move', n) }, n.name))),
    }),
  }
})

import MemoryHomeView from './MemoryHomeView.vue'
import { treeStore, loadTree } from '../core/node/treeStore'
import { moveNode, type TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memory/kinds'
import { DROPBOX_KIND, FILE_KIND } from '../features/files/kinds'
import { realI18n, t } from '../test/i18n'
import { treeNode } from '../test/models'
import { mountConfirmDialog } from '../test/confirmDialog'
import { hashForNode } from '../core/node/location'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../features/memory/favoritesStore'

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
const dialogOpen = () => document.body.querySelector('.move-destinations') !== null
const rows = () => [...document.body.querySelectorAll('.move-destination')].map((l) => l.querySelector('.move-destination-name')!.textContent)
async function pickAndMove(name: string) {
  const label = [...document.body.querySelectorAll('.move-destination')].find((l) => l.querySelector('.move-destination-name')!.textContent === name)!
  await click(label.querySelector('input')!)
  await click([...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('node.move.submit'))!)
}

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockReturnValue(false)
  treeStore.nodes = TREE()
  vi.mocked(moveNode).mockClear()
  vi.mocked(loadTree).mockClear()
  for (const f of Object.values(toast)) f.mockClear()
})

describe('a member moves a folder from its card', () => {
  it('Move to… opens core\'s dialog, the pick goes through moveNode, the URL follows the folder on screen, Undo moves it back', async () => {
    await mountAt('#~me/trips/rome/')
    expect(document.body.querySelector('.veyq-folder-overview-card h2')!.textContent).toBe('Rome')
    await click(byLabel(t('node.move.action'))!)
    expect(dialogOpen()).toBe(true)
    expect(rows(), 'the space\'s root, the folders; no drop box, no guest folder').toEqual([t('shell.personal'), 'Archive', 'Trips', 'Rome'])
    // the server's answer, as the re-read tree then holds it
    vi.mocked(moveNode).mockImplementationOnce(async () => {
      treeStore.nodes = treeStore.nodes.map((n) => (n.id === 'rome' ? { ...n, parentId: 'archive' } : n))
    })
    await pickAndMove('Archive')
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

  it('a section\'s row (Files\' file) and an opened Memory ask the host for the same dialog', async () => {
    await mountAt('#~me/trips/')
    await click(document.body.querySelector('.file-move[data-id="notes"]')!)
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'notes.txt' }))
    await pickAndMove('Archive')
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'notes', 'archive')
    expect(router.currentRoute.value.hash, 'a row moved away: the level stays').toBe('#~me/trips/')

    await router.push({ hash: '#~me/trips/memories/italy' })
    await flushPromises()
    await click(document.body.querySelector('.detail-move')!)
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'Italy' }))
    await pickAndMove(t('shell.personal'))
    expect(moveNode).toHaveBeenLastCalledWith('scope-1', expect.anything(), expect.any(Array), 'italy', null)
  })
})

// ADR 0080 decision 11, inbox #377 (a): a member moves a guest's folder OUT of a drop box; its identity ends.
describe('a member moves a guest\'s folder out of a drop box', () => {
  it('from the drop box\'s row: asked first, then moved; no undo', async () => {
    const confirm = mountConfirmDialog()
    await mountAt('#~me/inbox')
    await click(byLabel(t('files.dropBox.moveFolder', { name: 'Anna' }))!)
    expect(rows()).toEqual([t('shell.personal'), 'Archive', 'Trips', 'Rome'])
    await pickAndMove('Archive')
    expect(confirm.text()).toContain(t('node.move.identityConfirm', { name: 'Anna' }))
    expect(moveNode, 'nothing before the answer').not.toHaveBeenCalled()
    confirm.click(t('node.move.identityAccept'))
    await flushPromises()
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'anna', 'archive')
    expect(toast.success).toHaveBeenCalledWith(t('node.move.moved', { name: 'Anna', to: 'Archive' }))
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('from the guest\'s folder itself: its card offers the move out, and nothing else', async () => {
    await mountAt('#~me/inbox/anna/')
    const actions = [...document.body.querySelectorAll('.veyq-folder-overview-actions button')].map((b) => b.getAttribute('aria-label'))
    expect(actions).toEqual([t('node.move.action')])
  })

  it('the drop box itself moves from its card, like any node', async () => {
    await mountAt('#~me/inbox')
    await click(byLabel(t('node.move.action'))!)
    await pickAndMove('Trips')
    expect(moveNode).toHaveBeenCalledWith('scope-1', expect.anything(), expect.any(Array), 'inbox', 'trips')
  })
})
