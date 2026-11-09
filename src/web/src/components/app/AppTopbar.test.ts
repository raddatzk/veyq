// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

// The bar gives every crumb a real `href` (`router.resolve`), so the stub resolves a hash the way the hash history does.
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), resolve: ({ hash }: { hash: string }) => ({ href: `/${hash}` }) }),
  useRoute: () => ({ name: 'home', hash: '' }),
}))
// The search field is in the bar (uc.platform.search); its index is core's and is not under test here.
vi.mock('../../core/search/searchIndex', async () => {
  const { reactive } = await import('vue')
  return {
    searchState: reactive({ started: false, loading: [], failed: [], listFailed: false, scopes: [], revision: 0 }),
    refreshSearchIndex: vi.fn(async () => {}), indexedDocuments: vi.fn(() => []), indexedTree: vi.fn(() => null), indexedScope: vi.fn(() => null),
  }
})
vi.mock('../../core/people/directory', async (orig) => ({ ...(await orig<typeof import('../../core/people/directory')>()), loadTenantPeople: vi.fn(async () => new Map()) }))
vi.mock('../../features/memories/media', () => ({ addMedia: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'
import { realI18n, t } from '../../test/i18n'
import { addMedia } from '../../features/memories/media'
import { enqueueUploads, clearUploads } from '../../features/memories/uploadQueue'
import { NODE_DRAG_TYPE, endNodeDrag, startNodeDrag } from '../../core/node/nodeDrag'

const mountTopbar = () => mount(AppTopbar, { global: { plugins: [realI18n()] } })

describe('AppTopbar breadcrumb', () => {
  it('renders the client-side breadcrumb trail labels', () => {
    setBreadcrumb([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'f1', name: 'Reisen 2026', hash: '#~me/reisen-2026' },
      { id: 'm1', name: 'Rom', hash: '#~me/reisen-2026/rom' },
    ])
    const w = mountTopbar()
    const text = w.text()
    expect(text).toContain('Personal')
    expect(text).toContain('Reisen 2026')
    expect(text).toContain('Rom')
  })
})

// The upload status is the platform's (cr-files-application B3): the bar mounts core's menu itself, over the REAL
// registry (src/test/setup.ts registers the applications as main.ts does), and a batch Memories enqueues through its
// own adapter shows in it. If the bar stopped mounting it, this is the test that goes red.
describe('AppTopbar upload status (core\'s menu)', () => {
  it('shows a Memories batch in the platform\'s upload menu with a live queue', async () => {
    vi.mocked(addMedia).mockImplementation(() => new Promise(() => {})) // stays in flight
    clearUploads()
    const w = mountTopbar()
    expect(w.find('.uploads-trigger').exists()).toBe(false) // an empty queue draws no trigger
    enqueueUploads({ scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) },
      [{ file: new File([new Uint8Array([1])], 'photo.png', { type: 'image/png' }), mediaType: 'image/png' }], { label: '1 photo' })
    await flushPromises()
    expect(w.find('.uploads-trigger').exists()).toBe(true)
    clearUploads()
  })
})

// Drag and drop (inbox #421): the space's breadcrumb is a drop target for nodes dragged from the level - each crumb stands
// for its place (the scope root's for `null`), and which of them take the drag is the drag's own answer (`nodeDrag.ts`,
// the move dialog's rule; `LevelView.drag.test.ts` drives that). A drag of files lights none.
describe('AppTopbar breadcrumb as drop targets', () => {
  const transfer = (types: string[]) => ({ types: [...types], dropEffect: 'unset', effectAllowed: 'unset', setData() {}, setDragImage() {} })
  const crumbs = (w: ReturnType<typeof mountTopbar>) => w.findAll('li.veyq-breadcrumb__item')
  async function dragOver(li: ReturnType<typeof crumbs>[number], data: object): Promise<boolean> {
    const e = new Event('dragover', { bubbles: true, cancelable: true })
    Object.defineProperty(e, 'dataTransfer', { value: data })
    li.element.dispatchEvent(e)
    await flushPromises()
    return e.defaultPrevented
  }

  it('an ancestor the drag may go to is lit and takes the drop; the place on screen and a drag of files never', async () => {
    setBreadcrumb([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'f1', name: 'Reisen 2026', hash: '#~me/reisen-2026' },
      { id: 'm1', name: 'Rom', hash: '#~me/reisen-2026/rom' },
    ])
    const w = mountTopbar()
    const dropped: (string | null)[] = []
    // the drag's answer: everything but the place its nodes are in (as `dropTakes` answers for nodes of Rom)
    startNodeDrag({ dataTransfer: transfer([]) } as unknown as DragEvent, { ids: new Set(['x']), accepts: (to) => to !== 'm1', drop: (to) => dropped.push(to) })
    try {
      const files = transfer(['Files'])
      expect(await dragOver(crumbs(w)[1], files), 'a drag of files').toBe(false)
      expect(crumbs(w)[1].classes()).not.toContain('is-drop-target')
      const data = transfer([NODE_DRAG_TYPE])
      expect(await dragOver(crumbs(w)[2], data), 'the place on screen').toBe(false)
      expect(crumbs(w)[2].classes()).not.toContain('is-drop-target')
      expect(await dragOver(crumbs(w)[1], data)).toBe(true)
      expect(crumbs(w)[1].classes()).toContain('is-drop-target')
      // given up over it (Escape): the drag's end, wherever it fires, leaves nothing lit
      document.dispatchEvent(new Event('dragend'))
      await flushPromises()
      expect(crumbs(w)[1].classes()).not.toContain('is-drop-target')
      expect(await dragOver(crumbs(w)[0], data), 'the scope root').toBe(true)
      await crumbs(w)[0].trigger('drop', { dataTransfer: data })
      expect(dropped).toEqual([null])
      expect(crumbs(w)[0].classes()).not.toContain('is-drop-target')
    } finally {
      endNodeDrag()
    }
  })

  it('with no drag of nodes under way, no crumb takes one', async () => {
    setBreadcrumb([{ id: null, name: 'Personal', hash: '#~me' }, { id: 'f1', name: 'Reisen 2026', hash: '#~me/reisen-2026' }])
    const w = mountTopbar()
    expect(await dragOver(crumbs(w)[0], transfer([NODE_DRAG_TYPE]))).toBe(false)
    expect(crumbs(w)[0].classes()).not.toContain('is-drop-target')
  })
})

// The search (uc.platform.search main flow 1): the field in the bar, and the shortcut that reaches it - `/` and Ctrl/⌘+K,
// never while a field has the keys.
describe('AppTopbar search', () => {
  const searchInput = () => document.body.querySelector<HTMLInputElement>('[data-testid="search-field"] input')!
  const key = (init: KeyboardEventInit, target: EventTarget = document.body) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }))

  it('holds the field and a button that opens it in a sheet at a phone\'s width', () => {
    const w = mount(AppTopbar, { global: { plugins: [realI18n()] }, attachTo: document.body })
    expect(w.find('[data-testid="search-field"]').exists()).toBe(true)
    expect(w.find('[data-testid="search-open"]').attributes('aria-label')).toBe(t('shell.search.open'))
    w.unmount()
  })

  it('/ and Ctrl+K put the focus in the field; neither does while another field has the keys', async () => {
    const w = mount(AppTopbar, { global: { plugins: [realI18n()] }, attachTo: document.body })
    key({ key: '/' })
    await flushPromises()
    expect(document.activeElement).toBe(searchInput())
    searchInput().blur()
    key({ key: 'k', ctrlKey: true })
    await flushPromises()
    expect(document.activeElement).toBe(searchInput())
    searchInput().blur()
    const other = document.createElement('input')
    document.body.appendChild(other)
    other.focus()
    key({ key: '/' }, other)
    await flushPromises()
    expect(document.activeElement).toBe(other)
    other.remove()
    w.unmount()
  })
})
