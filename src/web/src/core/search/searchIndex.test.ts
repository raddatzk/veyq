// The search's index (ADR 0125 decisions 2 and 3) over sealed trees opened with the real crypto: every space of the member,
// the active one from the tree on screen, the others read conditionally and their keys zeroed; a 304 keeps, a moved tree
// rebuilds, a failure drops and names the space; a reset abandons a walk; nothing is written to any storage.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../features/spaces/scopes', () => ({ listMyScopes: vi.fn(), listMyTenants: vi.fn(), openListedScope: vi.fn() }))
vi.mock('../node/tree', async (orig) => ({ ...(await orig<typeof import('../node/tree')>()), fetchTree: vi.fn() }))
vi.mock('../../features/spaces/epochs', async (orig) => ({ ...(await orig<typeof import('../../features/spaces/epochs')>()), zeroScopeKey: vi.fn() }))
vi.mock('../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({ scopeId: null as string | null, tenantId: 'tenant-1', kind: null as string | null, name: null as string | null, status: 'idle', epoch: 0 }),
    activeEpochKeys: vi.fn(() => null),
  }
})
vi.mock('../node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [] as unknown[], loaded: false }), shownTreeTag: vi.fn(() => 'W/"active-1"') }
})

import * as v1 from '@veyq/crypto/v1'
import { nextTick } from 'vue'
import { fetchTree, openTree, sealName, wrapContentKey, type TreeNode } from '../node/tree'
import { listMyScopes, listMyTenants, openListedScope } from '../../features/spaces/scopes'
import { zeroScopeKey, type ScopeKey } from '../../features/spaces/epochs'
import { activeEpochKeys, activeSpace } from '../../app/activeSpace'
import { treeStore } from '../node/treeStore'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealNodeMeta, unsealNodeMeta } from '../node/nodeMeta'
import { ScopeKind } from '../../api/generated/scopes'
import type { NodeSummary } from '../../api/generated/content'
import type { MyTenant } from '../../api/generated/scopes'
import { openedScope, scopeSummary } from '../../test/models'
import { testKind } from '../../test/testApplication'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { indexedDocuments, indexedScope, indexedTree, refreshSearchIndex, resetSearchIndex, searchState } from './searchIndex'
import type { SearchFacetContext } from '../applications/registry'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const TENANT = id(101)
const GROUP = id(102)
const PERSONAL = id(103)
const SEARCHABLE = 'SEARCH_TEST_KIND'

const seen: SearchFacetContext[] = []
const facets = vi.fn((ctx: SearchFacetContext) => {
  seen.push(ctx)
  const meta = unsealNodeMeta(ctx.nodeKey, { scopeId: ctx.scopeId, nodeId: ctx.node.id }, ctx.node.wrappedMeta) as { place?: string }
  return meta.place ? { places: [meta.place] } : {}
})
testKind(registry, { kind: SEARCHABLE, icon: 'file', rekeyContent: nothingOfItsOwnToRekey, search: { label: 'test.searchable', facets } })

const keys = new Map<string, Uint8Array>()
/** A scope's sealed rows: a root folder holding one node of the searchable kind per name (with its place sealed in its meta). */
function rows(scopeId: string, folder: string, items: { id: string; name: string; place?: string; metaRevision?: number }[], placementRevision = 1): NodeSummary[] {
  const scopeKey = keys.get(scopeId)!
  const addr = (nodeId: string) => ({ scopeId, nodeId, scopeEpoch: 0, placementRevision })
  const base = { createdAt: new Date(0), placementRevision, epoch: 0, scopeEpoch: 0, slugToken: 's', orderIndex: 0, metaRevision: 0, blockedByOperator: false }
  const folderId = id(200 + [TENANT, GROUP, PERSONAL].indexOf(scopeId))
  const folderKey = v1.randomBytes(32)
  const out: NodeSummary[] = [{
    ...base, id: folderId, parentId: null, kind: FOLDER_KIND,
    encryptedName: sealName(scopeKey, addr(folderId), folder), wrappedContentKey: wrapContentKey(scopeKey, addr(folderId), folderKey),
  }]
  for (const it of items) {
    const key = v1.randomBytes(32)
    out.push({
      ...base, id: it.id, parentId: folderId, kind: SEARCHABLE, metaRevision: it.metaRevision ?? 0,
      encryptedName: sealName(folderKey, addr(it.id), it.name), wrappedContentKey: wrapContentKey(folderKey, addr(it.id), key),
      wrappedMeta: sealNodeMeta(key, { scopeId, nodeId: it.id }, { v: 1, ...(it.place ? { place: it.place } : {}) }),
    })
  }
  return out
}

const mocked = {
  listMyScopes: vi.mocked(listMyScopes), listMyTenants: vi.mocked(listMyTenants), openListedScope: vi.mocked(openListedScope),
  fetchTree: vi.mocked(fetchTree), zeroScopeKey: vi.mocked(zeroScopeKey), activeEpochKeys: vi.mocked(activeEpochKeys),
}
/** Every key the index was handed, to look at after a walk. */
const handedOut: ScopeKey[] = []
const names = (scopeId?: string) => indexedDocuments().filter((d) => !scopeId || d.scopeId === scopeId).map((d) => d.name).sort()

beforeEach(async () => {
  vi.clearAllMocks()
  const real = await vi.importActual<typeof import('../../features/spaces/epochs')>('../../features/spaces/epochs')
  mocked.zeroScopeKey.mockImplementation(real.zeroScopeKey)
  resetSearchIndex()
  seen.length = 0
  handedOut.length = 0
  for (const s of [TENANT, GROUP, PERSONAL]) keys.set(s, v1.randomBytes(32))
  Object.assign(activeSpace, { scopeId: null, kind: null, name: null, status: 'idle', epoch: 0 })
  Object.assign(treeStore, { nodes: [], loaded: false })
  mocked.listMyScopes.mockResolvedValue([
    scopeSummary({ id: TENANT, kind: ScopeKind.Tenant }),
    scopeSummary({ id: GROUP, kind: ScopeKind.Group, encryptedName: 'sealed-name-1' }),
    scopeSummary({ id: PERSONAL, kind: ScopeKind.Personal }),
  ])
  mocked.listMyTenants.mockResolvedValue([{ tenantId: 'tenant-1', name: 'Familie Raddatz' } as unknown as MyTenant])
  mocked.openListedScope.mockImplementation(async (s) => {
    const opened = openedScope({ scopeId: s.id, kind: s.kind, key: keys.get(s.id)!.slice(), name: s.kind === ScopeKind.Group ? 'Family group' : undefined })
    handedOut.push(opened.epochs)
    return opened
  })
  mocked.fetchTree.mockImplementation(async (scopeId) => ({
    rows: scopeId === TENANT ? rows(TENANT, 'Trips', [{ id: id(1), name: 'Rome 2024', place: 'Roma' }])
      : scopeId === GROUP ? rows(GROUP, 'Family', [{ id: id(2), name: 'Garden party' }])
        : rows(PERSONAL, 'Mine', [{ id: id(3), name: 'Diary' }]),
    etag: `W/"${scopeId}-1"`,
  }))
})
afterEach(() => resetSearchIndex())

describe('the first build', () => {
  it('opens every space of the member and names each one', async () => {
    await refreshSearchIndex()
    expect(names()).toEqual(['Diary', 'Family', 'Garden party', 'Mine', 'Rome 2024', 'Trips'])
    expect(indexedScope(TENANT)?.name).toBe('Familie Raddatz')
    expect(indexedScope(GROUP)?.name).toBe('Family group')
    expect(indexedScope(PERSONAL)?.name).toBeNull()
    expect(searchState.loading).toEqual([])
    expect(searchState.failed).toEqual([])
    // a whole read the first time: no tag
    expect(mocked.fetchTree.mock.calls.map((c) => c[1])).toEqual([null, null, null])
  })

  it('lists the spaces once per walk, not once per space', async () => {
    await refreshSearchIndex()
    expect(mocked.listMyScopes).toHaveBeenCalledTimes(1)
  })

  it('zeroes every scope key it opened, and every node key the walk opened', async () => {
    await refreshSearchIndex()
    expect(handedOut).toHaveLength(3)
    for (const k of handedOut) expect(mocked.zeroScopeKey).toHaveBeenCalledWith(k)
    for (const k of handedOut) expect(k.key.every((b) => b === 0)).toBe(true)
    expect(seen.length).toBeGreaterThan(0)
    for (const ctx of seen) expect(ctx.nodeKey.every((b) => b === 0)).toBe(true)
  })

  it('asks the kind for its facets while the keys are open, and keeps what it answered', async () => {
    await refreshSearchIndex()
    expect(indexedDocuments().find((d) => d.name === 'Rome 2024')?.facets).toEqual({ places: ['Roma'] })
    expect(indexedDocuments().find((d) => d.name === 'Rome 2024')?.path).toEqual(['Trips'])
  })

  it('does not change the active space', async () => {
    Object.assign(activeSpace, { scopeId: TENANT, kind: ScopeKind.Tenant, status: 'ready' })
    await refreshSearchIndex()
    expect(activeSpace.scopeId).toBe(TENANT)
  })
})

describe('revalidation by the tree revision', () => {
  it('keeps a space that answers 304, without opening its key again', async () => {
    await refreshSearchIndex()
    mocked.fetchTree.mockClear()
    mocked.openListedScope.mockClear()
    mocked.fetchTree.mockResolvedValue(null)
    await refreshSearchIndex()
    expect(mocked.fetchTree.mock.calls.map((c) => c[1])).toEqual([`W/"${TENANT}-1"`, `W/"${GROUP}-1"`, `W/"${PERSONAL}-1"`])
    expect(mocked.openListedScope).not.toHaveBeenCalled()
    expect(names()).toContain('Rome 2024')
  })

  it('rebuilds a space whose tree moved, reusing the facets of a seal it already read', async () => {
    await refreshSearchIndex()
    facets.mockClear()
    const tenantRows = rows(TENANT, 'Trips', [{ id: id(1), name: 'Rome 2024', place: 'Roma' }])
    mocked.fetchTree.mockImplementation(async (scopeId) => (scopeId === TENANT ? { rows: tenantRows, etag: 'W/"t-2"' } : null))
    await refreshSearchIndex()
    // the rows are new seals: the facets are read again for them
    expect(facets).toHaveBeenCalledTimes(1)
    // the tree moved again (a new tag), the node's sealed meta did not
    mocked.fetchTree.mockImplementation(async (scopeId) => (scopeId === TENANT ? { rows: tenantRows, etag: 'W/"t-3"' } : null))
    facets.mockClear()
    await refreshSearchIndex()
    expect(facets).not.toHaveBeenCalled() // the same sealed meta: its facets are kept
    expect(names(TENANT)).toEqual(['Rome 2024', 'Trips'])
  })

  it('drops a space whose tree no longer fetches and names it as not searched', async () => {
    await refreshSearchIndex()
    mocked.fetchTree.mockImplementation(async (scopeId) => {
      if (scopeId === GROUP) throw new Error('404 scope_not_found')
      return null
    })
    await refreshSearchIndex()
    expect(names(GROUP)).toEqual([])
    expect(searchState.failed).toEqual([{ id: GROUP, kind: ScopeKind.Group, name: 'Family group' }])
    expect(names(TENANT)).toContain('Rome 2024')
  })

  it('drops a space whose key does not open on this device', async () => {
    mocked.openListedScope.mockImplementation(async (s) => (s.id === PERSONAL ? null : openedScope({ scopeId: s.id, kind: s.kind, key: keys.get(s.id)!.slice() })))
    await refreshSearchIndex()
    expect(names(PERSONAL)).toEqual([])
    expect(searchState.failed.map((s) => s.id)).toEqual([PERSONAL])
  })

  it('drops a space the member is no longer listed in', async () => {
    await refreshSearchIndex()
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: TENANT, kind: ScopeKind.Tenant })])
    mocked.fetchTree.mockResolvedValue(null)
    await refreshSearchIndex()
    expect(names(GROUP)).toEqual([])
    expect(indexedTree(GROUP)).toBeNull()
  })

  it('reads a renamed group whole, even when its tree did not move', async () => {
    await refreshSearchIndex()
    mocked.listMyScopes.mockResolvedValue([scopeSummary({ id: GROUP, kind: ScopeKind.Group, encryptedName: 'sealed-name-2' })])
    mocked.fetchTree.mockClear()
    await refreshSearchIndex()
    expect(mocked.fetchTree).toHaveBeenCalledWith(GROUP, null)
  })

  it('searches nothing but the space on screen when the spaces cannot be listed', async () => {
    await refreshSearchIndex()
    mocked.listMyScopes.mockRejectedValue(new Error('offline'))
    await refreshSearchIndex()
    expect(indexedDocuments()).toEqual([])
    expect(searchState.listFailed).toBe(true)
  })
})

describe('the active space', () => {
  const activeKey = (): ScopeKey => ({ epoch: 0, key: keys.get(TENANT)! })
  function showTenant() {
    const tree: TreeNode[] = openTree(TENANT, activeKey(), rows(TENANT, 'Trips', [{ id: id(1), name: 'Rome 2024' }]))
    mocked.activeEpochKeys.mockReturnValue(activeKey())
    Object.assign(activeSpace, { scopeId: TENANT, kind: ScopeKind.Tenant, status: 'ready' })
    Object.assign(treeStore, { nodes: tree, loaded: true })
    return tree
  }

  it('comes from the tree on screen, without a read of its own and without zeroing the key the page owns', async () => {
    showTenant()
    await refreshSearchIndex()
    expect(mocked.fetchTree.mock.calls.map((c) => c[0])).toEqual([GROUP, PERSONAL])
    expect(names(TENANT)).toEqual(['Rome 2024', 'Trips'])
    expect(keys.get(TENANT)!.some((b) => b !== 0)).toBe(true)
  })

  it('follows the tree poll: a rename on screen reaches the documents', async () => {
    const tree = showTenant()
    await refreshSearchIndex()
    treeStore.nodes = tree.map((n) => (n.id === id(1) ? { ...n, name: 'Roma 2024', placementRevision: 2 } : n))
    await nextTick()
    expect(names(TENANT)).toEqual(['Roma 2024', 'Trips'])
  })

  it('is built first, before any other space is read', async () => {
    showTenant()
    let atFirstRead: string[] = []
    mocked.fetchTree.mockImplementationOnce(async () => {
      atFirstRead = names()
      return { rows: rows(GROUP, 'Family', []), etag: null }
    })
    await refreshSearchIndex()
    expect(atFirstRead).toEqual(['Rome 2024', 'Trips'])
  })

  it('keeps its documents after the member left it, and revalidates them by the tag they were read at', async () => {
    showTenant()
    await refreshSearchIndex()
    Object.assign(activeSpace, { scopeId: GROUP, kind: ScopeKind.Group, name: 'Family group' })
    Object.assign(treeStore, { nodes: [], loaded: false })
    await nextTick()
    mocked.fetchTree.mockClear()
    mocked.fetchTree.mockResolvedValue(null)
    await refreshSearchIndex()
    expect(mocked.fetchTree).toHaveBeenCalledWith(TENANT, 'W/"active-1"')
    expect(names(TENANT)).toEqual(['Rome 2024', 'Trips'])
  })
})

describe('a reset (sign-out, re-lock)', () => {
  it('drops every document', async () => {
    await refreshSearchIndex()
    expect(indexedDocuments().length).toBeGreaterThan(0)
    resetSearchIndex()
    expect(indexedDocuments()).toEqual([])
    expect(searchState.started).toBe(false)
  })

  it('abandons a walk in flight: it installs nothing, and the key it opened is still zeroed', async () => {
    let release!: () => void
    mocked.openListedScope.mockImplementationOnce(async (s) => {
      await new Promise<void>((r) => (release = r))
      const opened = openedScope({ scopeId: s.id, kind: s.kind, key: keys.get(s.id)!.slice() })
      handedOut.push(opened.epochs)
      return opened
    })
    const walk = refreshSearchIndex()
    await vi.waitFor(() => expect(release).toBeDefined())
    resetSearchIndex()
    release()
    await walk
    expect(indexedDocuments()).toEqual([])
    expect(handedOut[0].key.every((b) => b === 0)).toBe(true)
  })
})

describe('nothing is written to any storage', () => {
  function spyOnStorage() {
    const writes: string[] = []
    const store = () => ({
      getItem: () => null, key: () => null, length: 0, clear: () => writes.push('clear'),
      setItem: (k: string) => writes.push(`set ${k}`), removeItem: (k: string) => writes.push(`remove ${k}`),
    })
    vi.stubGlobal('localStorage', store())
    vi.stubGlobal('sessionStorage', store())
    vi.stubGlobal('indexedDB', { open: (n: string) => (writes.push(`idb ${n}`), {}), deleteDatabase: (n: string) => writes.push(`idb-delete ${n}`) })
    vi.stubGlobal('caches', { open: async (n: string) => (writes.push(`cache ${n}`), {}), delete: async (n: string) => writes.push(`cache-delete ${n}`) })
    return writes
  }
  afterEach(() => vi.unstubAllGlobals())

  it('sees a write when one happens (the control)', () => {
    const writes = spyOnStorage()
    localStorage.setItem('probe', '1')
    sessionStorage.setItem('probe', '1')
    void indexedDB.open('probe')
    void caches.open('probe')
    expect(writes).toEqual(['set probe', 'set probe', 'idb probe', 'cache probe'])
  })

  it('writes nothing during a build, a revalidation and a query', async () => {
    const writes = spyOnStorage()
    await refreshSearchIndex()
    mocked.fetchTree.mockResolvedValue(null)
    await refreshSearchIndex()
    const { matchAll } = await import('./query')
    expect(matchAll(indexedDocuments(), { chips: [], text: 'rome' }).map((m) => m.doc.name)).toEqual(['Rome 2024'])
    expect(writes).toEqual([])
  })
})
