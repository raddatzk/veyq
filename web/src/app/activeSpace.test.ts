import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { httpError } from '../test/httpError'
import { setupStatus, treeNode } from '../test/models'

vi.mock('../features/spaces/scopes', () => ({
  ensurePersonalScope: vi.fn(),
  openScope: vi.fn(),
  openScopeById: vi.fn(),
  openGroupByToken: vi.fn(),
}))
// Stub only the async loaders refreshActiveScope drives (they hit the content/favorites APIs) — keep the rest of
// the stores real so the other tests still assert on treeStore/favoritesStore.
vi.mock('../core/node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn() }))
vi.mock('../features/memory/favoritesStore', async (orig) => ({ ...(await orig<object>()), loadFavoritesFor: vi.fn() }))
vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))

import { activeSpace, activeScopeKey, activeEpochKeys, enterScope, refreshActiveScope, clearActiveSpace } from './activeSpace'
import { ensurePersonalScope, openScope, openScopeById, openGroupByToken } from '../features/spaces/scopes'
import { ScopeKind } from '../api/generated/scopes'
import { TENANT_SCOPE } from '../core/node/location'
import { TrustStateRefused } from '../features/account/trustMarks'

const PERSONAL_SCOPE = '~me'
import { treeStore, resetTree } from '../core/node/treeStore'
import { favoritesStore, clearFavorites, loadFavoritesFor } from '../features/memory/favoritesStore'
import { registry } from '../core/applications/registry'
import { memoriesLifecycle } from '../features/memory/lifecycle'
import { getSetupStatus } from '../features/account/instance/instance'
import { serverState } from '../shared/state/serverState'
import { suspensionSignals } from './tenantSuspension'
import type { TreeNode } from '../core/node/tree'
import { FOLDER_KIND } from '../core/node/kind'

const TENANT_ID = '22222222-2222-2222-2222-222222222222'
const TENANT_SCOPE_ID = 'tn-1'
const PERSONAL_SCOPE_ID = '11111111-1111-1111-1111-111111111111'

// An unrotated OpenedScope: the write key IS E_0, so `key` is the same buffer the epoch map holds (zeroed together).
const epochsOf = (key: Uint8Array) => ({ current: 0, keys: new Map([[0, key]]) })
const opened = (scopeId: string, kind: ScopeKind, key: Uint8Array) => ({ scopeId, tenantId: TENANT_ID, kind, key, epochs: epochsOf(key), nameEpoch: 0, deletedAt: null, purgeAfter: null })

// A node-environment file: src/test/setup.ts registers no application here. The REAL Memories hooks go into the
// REAL registry, so the scope tests below prove activeSpace resets/loads application state THROUGH the registry.
beforeAll(() => registry.registerLifecycle('memories', memoriesLifecycle))

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
  resetTree()
  clearFavorites()
})

describe('enterScope — tenant', () => {
  it('opens the TENANT grant → ready with scopeId + K_SC + kind, tenant available', async () => {
    const key = new Uint8Array(32).fill(9)
    vi.mocked(openScope).mockResolvedValue(opened(TENANT_SCOPE_ID, ScopeKind.Tenant, key))

    await enterScope(TENANT_SCOPE)

    expect(openScope).toHaveBeenCalledWith(ScopeKind.Tenant)
    expect(ensurePersonalScope).not.toHaveBeenCalled() // open-only: the tenant scope is never minted client-side
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.scopeId).toBe(TENANT_SCOPE_ID)
    expect(activeSpace.kind).toBe(ScopeKind.Tenant)
    expect(activeSpace.name).toBeNull() // the tenant scope is anonymous — no per-scope name
    expect(activeSpace.tenantUnavailable).toBe(false)
    expect(activeScopeKey()).toBe(key)
  })

  it('falls back to Personal (auto-birth + open) when there is no TENANT grant, flagging it unavailable', async () => {
    const key = new Uint8Array(32).fill(5)
    vi.mocked(openScope).mockImplementation(async (kind) =>
      kind === ScopeKind.Personal ? opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key) : null,
    )
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)

    await enterScope(TENANT_SCOPE)

    expect(ensurePersonalScope).toHaveBeenCalledOnce() // personal is the one birth path
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.scopeId).toBe(PERSONAL_SCOPE_ID)
    expect(activeSpace.kind).toBe(ScopeKind.Personal)
    expect(activeSpace.tenantUnavailable).toBe(true)
    expect(activeScopeKey()).toBe(key)
  })
})

describe('enterScope — personal', () => {
  it('auto-births + opens the personal scope → ready, tenant flag clear', async () => {
    const key = new Uint8Array(32).fill(7)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key))

    await enterScope(PERSONAL_SCOPE)

    expect(openScope).not.toHaveBeenCalledWith(ScopeKind.Tenant) // `~me` never touches the tenant grant
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.kind).toBe(ScopeKind.Personal)
    expect(activeSpace.tenantUnavailable).toBe(false)
  })

  it('maps a 404 (no tenant for this Host) to status no-tenant', async () => {
    vi.mocked(ensurePersonalScope).mockRejectedValue(httpError(404))
    await enterScope(PERSONAL_SCOPE)
    expect(activeSpace.status).toBe('no-tenant')
    expect(activeScopeKey()).toBeNull()
  })

  it('falls back to error when no scope can be opened', async () => {
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(null)
    await enterScope(PERSONAL_SCOPE)
    expect(activeSpace.status).toBe('error')
  })

  it('names a trust-state refusal on the error (ADR 0028 Decision 7) — a shared grant\u2019s granter could not be checked', async () => {
    vi.mocked(openScope).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    await enterScope(TENANT_SCOPE)
    expect(activeSpace.status).toBe('error')
    expect(activeSpace.trustRefusal).toBe('verify.trustRolledBack')
  })

  it('control: any other failure is the plain error, with no refusal named', async () => {
    vi.mocked(openScope).mockRejectedValue(new Error('boom'))
    await enterScope(TENANT_SCOPE)
    expect(activeSpace.status).toBe('error')
    expect(activeSpace.trustRefusal).toBeNull()
  })
})

describe('enterScope — group', () => {
  it('opens a group by fragment token (@<slug|id>) → ready, holding its decrypted name on activeSpace', async () => {
    const key = new Uint8Array(32).fill(3)
    vi.mocked(openGroupByToken).mockResolvedValue({ scopeId: 'g-1', tenantId: TENANT_ID, kind: ScopeKind.Group, key, epochs: epochsOf(key), nameEpoch: 0, name: 'Familie Raddatz', deletedAt: null, purgeAfter: null })

    await enterScope('@familie-raddatz')

    expect(openGroupByToken).toHaveBeenCalledWith('familie-raddatz')
    expect(ensurePersonalScope).not.toHaveBeenCalled() // a resolvable group never falls back to Personal
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.kind).toBe(ScopeKind.Group)
    expect(activeSpace.scopeId).toBe('g-1')
    expect(activeSpace.name).toBe('Familie Raddatz')
    expect(activeScopeKey()).toBe(key)
  })

  it('falls back to Personal when the @<slug|id> group cannot be resolved', async () => {
    vi.mocked(openGroupByToken).mockResolvedValue(null)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, new Uint8Array(32).fill(2)))

    await enterScope('@missing')

    expect(ensurePersonalScope).toHaveBeenCalledOnce()
    expect(activeSpace.kind).toBe(ScopeKind.Personal)
    expect(activeSpace.name).toBeNull()
  })
})

describe('enterScope — re-entry resets per-scope state', () => {
  it('clears the tree + favorites so nothing bleeds across a scope switch', async () => {
    // Seed stale per-scope state as if a previous scope was loaded.
    const stale: TreeNode = treeNode({
      id: 'stale', name: 'Stale', parentId: null, kind: FOLDER_KIND, orderIndex: 0, epoch: 0,
      wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0,
    })
    treeStore.nodes = [stale]
    treeStore.loaded = true
    favoritesStore.ids = new Set(['fav-1'])
    favoritesStore.loaded = true

    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, new Uint8Array(32).fill(1)))

    await enterScope(PERSONAL_SCOPE)

    expect(treeStore.nodes).toEqual([])
    expect(treeStore.loaded).toBe(false)
    expect(favoritesStore.ids.size).toBe(0)
    expect(favoritesStore.loaded).toBe(false)
  })
})

describe('refreshActiveScope — reactive epoch swap after a rotation (cr-0089)', () => {
  it('re-opens the scope, swaps in the new epoch keys, and bumps the reactive epoch so key-reading views re-run', async () => {
    // Enter a group at epoch 0 (via its fragment token → openGroupByToken).
    const e0 = new Uint8Array(32).fill(4)
    vi.mocked(openGroupByToken).mockResolvedValue(opened('g-1', ScopeKind.Group, e0))
    await enterScope('@g-1')
    expect(activeSpace.epoch).toBe(0)

    // A rotation bumped the scope to epoch 2 — the re-opened grant now carries E_2 (+ the peeled chain E_1/E_0).
    const e2 = new Uint8Array(32).fill(8)
    const peeled = { current: 2, keys: new Map([[2, e2], [1, new Uint8Array(32).fill(7)], [0, new Uint8Array(32).fill(4)]]) }
    vi.mocked(openScopeById).mockResolvedValue({ scopeId: 'g-1', tenantId: TENANT_ID, kind: ScopeKind.Group, key: e2, epochs: peeled, nameEpoch: 0, deletedAt: null, purgeAfter: null })
    await refreshActiveScope()

    // The reactive signal moved forward (view computeds depend on it) AND the write key is now E_2, so the owner's
    // next new content seals under E_2 — not the stale/zeroed E_0 (the bug the live smoke caught).
    expect(activeSpace.epoch).toBe(2)
    expect(activeEpochKeys()?.current).toBe(2)
    expect(activeScopeKey()).toBe(e2)
  })

  it('drops the application state of the old epoch and has each application re-load its per-scope state (Memories: the pin set)', async () => {
    const e0 = new Uint8Array(32).fill(4)
    vi.mocked(openGroupByToken).mockResolvedValue(opened('g-1', ScopeKind.Group, e0))
    await enterScope('@g-1')
    favoritesStore.ids = new Set(['fav-1'])
    favoritesStore.loaded = true
    vi.mocked(loadFavoritesFor).mockClear()
    vi.mocked(openScopeById).mockResolvedValue(opened('g-1', ScopeKind.Group, e0))

    await refreshActiveScope()

    expect(favoritesStore.loaded).toBe(false) // onScopeReset ran (the load itself is the stubbed loader)…
    expect(loadFavoritesFor).toHaveBeenCalledWith('g-1') // …and onScopeEntered asked for the reload
  })
})

describe('clearActiveSpace', () => {
  it('zeroes the held key and resets state', async () => {
    const key = new Uint8Array(32).fill(7)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key))
    await enterScope(PERSONAL_SCOPE)

    clearActiveSpace()

    expect(activeSpace.status).toBe('idle')
    expect(activeSpace.scopeId).toBeNull()
    expect(activeSpace.kind).toBeNull()
    expect(activeSpace.name).toBeNull()
    expect(activeScopeKey()).toBeNull()
    expect(Array.from(key)).toEqual(Array(32).fill(0)) // zeroed in place
  })
})

describe('enterScope — a suspended tenant (uc.spaces.scope-access A7)', () => {
  const suspended = () => httpError(403, 'tenant_suspended')

  beforeEach(() => {
    serverState.suspended = false
    suspensionSignals.leaveHome = 0
  })

  it('on this domain’s own tenant, hands over to the offline notice instead of an error', async () => {
    vi.mocked(openGroupByToken).mockRejectedValue(suspended())
    vi.mocked(getSetupStatus).mockRejectedValue(suspended())
    await enterScope('@group')
    expect(serverState.suspended).toBe(true)
    expect(activeSpace.status).not.toBe('error')
  })

  it('in another tenant, asks the home to leave for this domain’s own space', async () => {
    vi.mocked(openGroupByToken).mockRejectedValue(suspended())
    vi.mocked(getSetupStatus).mockResolvedValue(setupStatus())
    await enterScope('@group')
    expect(suspensionSignals.leaveHome).toBe(1)
    expect(serverState.suspended).toBe(false)
    expect(activeSpace.status).not.toBe('error')
  })

  it('control: a 404 is still no-tenant, with neither signal', async () => {
    vi.mocked(ensurePersonalScope).mockRejectedValue(httpError(404))
    await enterScope(PERSONAL_SCOPE)
    expect(activeSpace.status).toBe('no-tenant')
    expect(serverState.suspended).toBe(false)
    expect(suspensionSignals.leaveHome).toBe(0)
  })
})
