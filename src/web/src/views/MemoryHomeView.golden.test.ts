// @vitest-environment happy-dom
// GOLDEN: the Memories folder level as a user meets it, pinned before the registry/level-view re-cut
// (cr-files-application P4-0). The re-cut moves the overview card, the sections and the create cards from Memories into
// a core level view, and promises that nobody using Memories sees a difference; every e2e suite is API-level, so this
// file is the proof of that promise. It mounts the REAL host (`MemoryHomeView`), the REAL registry as `main.ts` fills
// it (`src/test/setup.ts` runs `registerApplications()` into this file's fresh registry), core's REAL `LevelView` (since
// P4b: the overview card and the Folders section), Memories' REAL rows (`memoryRows`) and `MemoryCard`, core's REAL `ViewModeToggle`, and
// the layer's buttons, real crypto (every date and pin below is sealed
// and unsealed for real), and the real i18n catalog. Stubbed: the scope/tree/favourites state (no network), the Leaflet
// map (no canvas in happy-dom).
//
// What it does NOT pin: layout and computed CSS (a width rule that hides an element is invisible — the 721–1120 px
// hiding of the folder card's actions was CSS-only and found in a real browser), the dialogs a click opens, and
// covers (no cover is sealed here, so every card shows its placeholder).
//
// Updating it deliberately: `npx vitest run -u src/views/MemoryHomeView.golden.test.ts`, then READ the diff of
// `__snapshots__/MemoryHomeView.golden/` — a re-cut that claims "no visible change" may only move class names (P4d).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('leaflet', () => ({ default: {} }))
const fixed = vi.hoisted(() => ({
  scopeId: '5c09e000-0000-4000-8000-0000000000bb',
  scopeKey: new Uint8Array(32).fill(3),
}))
vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: fixed.scopeId, tenantId: 't-1', kind: 'PERSONAL', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, fixed.scopeKey]]) }),
  }
})
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(async () => {}),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return {
    treeStore: reactive({ nodes: [], loaded: true, error: false }),
    loadTree: vi.fn(async () => {}),
    patchNodeMeta: vi.fn(),
  }
})

import MemoryHomeView from './MemoryHomeView.vue'
import { memoryRows } from '../features/memories/levelRows'
import { depotRows } from '../features/depot/levelRows'
import { potRows } from '../features/expenses/levelRows'
import { POT_KIND } from '../features/expenses/kinds'
import { DROPBOX_KIND, FILE_KIND } from '../features/depot/kinds'
import { registry } from '../core/applications/registry'
import { activeSpace } from '../app/activeSpace'
import { treeStore } from '../core/node/treeStore'
import { listMyTenants } from '../features/spaces/scopes'
import { isFavorite } from '../core/favorites/favoritesStore'
import { FOLDER_KIND, type NodeKind } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memories/kinds'
import { sealNodeMeta, type RawNodeMeta } from '../core/node/nodeMeta'
import type { TreeNode } from '../core/node/tree'
import { setViewMode, type ViewMode } from '../shared/state/useViewMode'
import { setLevelTab } from '../shared/state/useLevelTab'
import { capabilityState } from '../shared/state/capabilities'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX, DEPOT, EXPENSES, MEMORIES } from '../features/platform/capabilities'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n } from '../test/i18n'
import { serializeDom } from '../test/domGolden'
import { treeNode } from '../test/models'

// The map stub renders the pins it was handed, by name and sorted, so the map scenario pins WHICH nodes are on it.
const SpaceMapStub = {
  props: ['pins'],
  template: `<div class="space-map-stub">{{ pins.map((p) => p.node.name).sort().join(', ') }}</div>`,
}

// The fixtures of `MemoriesSection.test.ts` (the former `MemoryBrowse.test.ts`), mirrored (a test file cannot import another's): canonical UUID ids, each
// node's content key wrapped under its parent's key (the scope key at the root, the folder's inside one, ADR 0080) and
// its meta sealed under its own key, both bound to the node (ADR 0072).
const uuidOf = new Map<string, string>()
const uid = (label: string): string => {
  if (!uuidOf.has(label)) uuidOf.set(label, crypto.randomUUID())
  return uuidOf.get(label)!
}
const keyOfLabel = new Map<string, Uint8Array>()
const keyOf = (label: string): Uint8Array => {
  if (!keyOfLabel.has(label)) keyOfLabel.set(label, v1.randomBytes(32))
  return keyOfLabel.get(label)!
}
const wrapFor = (id: string, under: Uint8Array, key: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: fixed.scopeId, nodeId: id, epoch: 0, placementRevision: 0 }, under, key))

function nodeOf(label: string, kind: NodeKind, parentLabel: string | null, orderIndex: number, meta?: Omit<RawNodeMeta, 'v'>): TreeNode {
  const id = uid(label)
  const key = keyOf(label)
  return treeNode({
    id, kind, name: label, parentId: parentLabel === null ? null : uid(parentLabel), orderIndex, epoch: 0,
    wrappedContentKey: wrapFor(id, parentLabel === null ? fixed.scopeKey : keyOf(parentLabel), key),
    wrappedMeta: meta ? sealNodeMeta(key, { scopeId: fixed.scopeId, nodeId: id }, { v: 1, ...meta }) : null,
    metaRevision: 0, placementRevision: 0,
  })
}
const day = (from: string, to = from) => ({ date: { mode: 'single' as const, from, to } })
const at = (lat: number, lng: number) => ({ location: { name: `p-${lat}`, nameSource: 'manual' as const, derivedMode: 'fixed' as const, derived: { lat, lng } } })

// The space: at the root three Memories (a range, a single day, an undated located one: sorted newest first, undated
// last) and two folders; `Trips` holds a dated, located Memory and a sub-folder; `Empty` holds nothing.
const TREE: TreeNode[] = [
  nodeOf('Trips', FOLDER_KIND, null, 0),
  nodeOf('Empty', FOLDER_KIND, null, 1),
  nodeOf('Solo', MEMORY_KIND, null, 0, day('2023-03-04')),
  nodeOf('Summer', MEMORY_KIND, null, 1, day('2024-06-01', '2024-06-30')),
  nodeOf('Beach', MEMORY_KIND, null, 2, at(2, 2)),
  nodeOf('Italy', MEMORY_KIND, 'Trips', 0, { ...day('2024-05-01', '2024-05-10'), ...at(1, 1) }),
  nodeOf('Rome', FOLDER_KIND, 'Trips', 1),
]

async function mountHome(hash: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const w = mount(MemoryHomeView, {
    global: { plugins: [router, realI18n()], stubs: { SpaceMap: SpaceMapStub } },
  })
  await flushPromises()
  return w
}

async function golden(hash: string, mode: ViewMode = 'grid'): Promise<string> {
  setViewMode(mode)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const w = mount(MemoryHomeView, {
    global: { plugins: [router, realI18n()], stubs: { SpaceMap: SpaceMapStub } },
  })
  await flushPromises()
  return serializeDom(w.element)
}

const file = (name: string) => `./__snapshots__/MemoryHomeView.golden/${name}.txt`

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(isFavorite).mockImplementation((id) => id === uid('Solo')) // one favourite, so both pin states show
  treeStore.nodes = TREE.map((n) => ({ ...n }))
  capabilityState.ids = [MEMORIES, DEPOT, EXPENSES, CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX]
  capabilityState.tenantIds = null
  capabilityState.tenantErasures = null
  activeSpace.deletedAt = null
  activeSpace.purgeAfter = null
})
afterEach(() => {
  setViewMode('grid')
  setLevelTab('all')
  vi.restoreAllMocks()
})

// The banner formats its purge day in the runner's LOCAL zone, and no single instant is the same calendar day from
// UTC-12 to UTC+14 (measured: noon UTC read 11/16 under TZ=Pacific/Kiritimati). The day is formatted in UTC here, so
// the golden is the same on every machine; what the banner says is pinned, not the zone arithmetic.
function pendingDeletion() {
  activeSpace.deletedAt = new Date('2026-10-01T12:00:00Z')
  activeSpace.purgeAfter = new Date('2026-11-15T12:00:00Z')
  const local = Date.prototype.toLocaleDateString
  vi.spyOn(Date.prototype, 'toLocaleDateString').mockImplementation(function (this: Date, locales, options) {
    return local.call(this, locales, { ...options, timeZone: 'UTC' })
  })
}

describe('the Memories level view (golden)', () => {
  // The control every snapshot rests on: the level's rows are the real Memories and Depot registrations, as `main.ts` registers
  // them (MEMORY and CHAPTER naming one row factory, FILE and the drop box the other) - a golden of a stub would pin nothing
  // about what a user sees. Since the unified list (inbox #407) core draws ONE list of folders and every kind's rows.
  it('control: the level view renders the real Memories, Depot and Expenses registrations', () => {
    // Memories names the grid, the list and its map as its presentations (inbox #370): core's level toolbar offers them.
    expect(registry.lists().map((l) => ({ ...l, views: l.views.map((v) => v.mode) }))).toEqual([
      { rows: memoryRows, views: ['grid', 'list', 'map'], kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { rows: depotRows, views: [], kinds: [FILE_KIND, DROPBOX_KIND] },
      { rows: potRows, views: [], kinds: [POT_KIND] },
    ])
  })

  it('space root, grid: Memories section with dated cards and the create card, folders with the new-folder card', async () => {
    await expect(await golden('#~me')).toMatchFileSnapshot(file('root-grid'))
  })

  it('space root, list: the same level in the list variant of both grids', async () => {
    await expect(await golden('#~me', 'list')).toMatchFileSnapshot(file('root-list'))
  })

  it('space root, map: the map replaces the grid, every located node of the space is pinned, no folders section', async () => {
    await expect(await golden('#~me', 'map')).toMatchFileSnapshot(file('root-map'))
  })

  it('inside a folder, sharing on: the overview card with share, rename and delete; a Memory and a sub-folder', async () => {
    await expect(await golden('#~me/trips')).toMatchFileSnapshot(file('folder-grid'))
  })

  it('inside a folder, sharing off: the overview card keeps rename and delete, no share', async () => {
    capabilityState.ids = [MEMORIES, DEPOT, EXPENSES]
    await expect(await golden('#~me/trips')).toMatchFileSnapshot(file('folder-unshareable'))
  })

  it('inside a folder of a space pending deletion: the banner, share only, no create cards, no trash', async () => {
    pendingDeletion()
    await expect(await golden('#~me/trips')).toMatchFileSnapshot(file('folder-readonly'))
  })

  it('pending deletion with sharing off: the overview card has no actions row at all', async () => {
    pendingDeletion()
    capabilityState.ids = [MEMORIES, DEPOT, EXPENSES]
    await expect(await golden('#~me/trips')).toMatchFileSnapshot(file('folder-readonly-unshareable'))
  })

  it('Memories switched off for the tenant: the banner names it, no new Memory, the Memory keeps no write (uc.platform.switch-application-off)', async () => {
    capabilityState.ids = [DEPOT, EXPENSES, CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX]
    await expect(await golden('#~me/trips')).toMatchFileSnapshot(file('folder-memories-off'))
  })

  // uc.platform.erase-switched-off-application (web): the banner names the day the server erases the application's
  // content, and only while the set says it is off — the server keeps the day while a due erasure still runs.
  it('an application switched off with a named day: the dated banner; on again with the day still served: none', async () => {
    const until = new Date('2026-11-25T10:00:00Z')
    const local = Date.prototype.toLocaleDateString
    vi.spyOn(Date.prototype, 'toLocaleDateString').mockImplementation(function (this: Date, locales, options) {
      return local.call(this, locales, { ...options, timeZone: 'UTC' })
    })
    capabilityState.tenantIds = [MEMORIES, EXPENSES, CORE_PUBLIC_SHARES]
    capabilityState.tenantErasures = { depot: until, memories: until }
    const banners = (await mountHome('#~me')).findAll('.application-off-banner').map((b) => b.text())
    expect(banners).toEqual([
      'Depot is switched off here. You can open and download what’s here until 11/25/2026; then it is erased. Save what you want to keep.',
    ]) // Memories is on: its served day (an erasure that still runs) draws no banner

    capabilityState.tenantErasures = {} // off, with no day served: the undated banner stands
    const undated = (await mountHome('#~me')).findAll('.application-off-banner').map((b) => b.text())
    expect(undated).toEqual(['Depot is switched off here. You can still open and download what’s here, so save what you want to keep.'])
  })

  it('an empty folder: both empty states beside the create cards', async () => {
    await expect(await golden('#~me/empty')).toMatchFileSnapshot(file('folder-empty'))
  })
})
