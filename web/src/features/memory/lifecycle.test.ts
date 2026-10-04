// The Memories application's lifecycle hooks (lifecycle.ts) in isolation: what each hook does, and what it must not.
// Their wiring into the shell is proven where the shell calls the registry (activeSpace.test.ts: scope reset/refresh,
// coverStore.test.ts: sign-out, rotateKeys.test.ts: the master-key hook).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./favorites', () => ({ resealFavoritesUnderNewMasterKey: vi.fn(), loadFavorites: vi.fn(), saveFavorites: vi.fn() }))
vi.mock('./itemFavorites', () => ({ retagItemMarksUnderNewMasterKey: vi.fn() }))
vi.mock('./coverStore', () => ({ resetCovers: vi.fn() }))
vi.mock('./favoritesStore', () => ({
  favoritesStore: { loaded: false, ids: new Set<string>() },
  clearFavorites: vi.fn(),
  loadFavoritesFor: vi.fn(),
}))

import { memoriesLifecycle } from './lifecycle'
import { resealFavoritesUnderNewMasterKey } from './favorites'
import { retagItemMarksUnderNewMasterKey } from './itemFavorites'
import { resetCovers } from './coverStore'
import { favoritesStore, clearFavorites, loadFavoritesFor } from './favoritesStore'

beforeEach(() => {
  vi.clearAllMocks()
  favoritesStore.loaded = false
})

describe('onScopeReset', () => {
  it('drops the pins and releases the covers', () => {
    memoriesLifecycle.onScopeReset?.()
    expect(clearFavorites).toHaveBeenCalledTimes(1)
    expect(resetCovers).toHaveBeenCalledTimes(1)
  })
})

describe('onScopeEntered', () => {
  it('loads the pin set of the entered scope', async () => {
    await memoriesLifecycle.onScopeEntered?.('s1')
    expect(loadFavoritesFor).toHaveBeenCalledWith('s1')
  })

  it('is idempotent per reset: an already loaded pin set is not fetched again', async () => {
    favoritesStore.loaded = true
    await memoriesLifecycle.onScopeEntered?.('s1')
    expect(loadFavoritesFor).not.toHaveBeenCalled()
  })
})

describe('onMasterKeyRotated', () => {
  const o = new Uint8Array([1])
  const n = new Uint8Array([2])

  it('re-seals the pins of every scope and re-tags the item marks', async () => {
    await memoriesLifecycle.onMasterKeyRotated(o, n, ['a', 'b'])
    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledWith(o, n, ['a', 'b'])
    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledWith(o, n)
  })

  it('a failing pin re-seal does not skip the item marks (and vice versa), and neither throws', async () => {
    vi.mocked(resealFavoritesUnderNewMasterKey).mockRejectedValueOnce(new Error('unreadable'))
    await expect(memoriesLifecycle.onMasterKeyRotated(o, n, [])).resolves.toBeUndefined()
    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledTimes(1)

    vi.mocked(retagItemMarksUnderNewMasterKey).mockRejectedValueOnce(new Error('bad tag'))
    await expect(memoriesLifecycle.onMasterKeyRotated(o, n, [])).resolves.toBeUndefined()
    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledTimes(2)
  })
})
