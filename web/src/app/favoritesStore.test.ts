import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/memory/favorites', () => ({ loadFavorites: vi.fn(), saveFavorites: vi.fn() }))
vi.mock('./vault', () => ({ masterKey: vi.fn() }))

import { favoritesStore, isFavorite, toggleFavorite, loadFavoritesFor, clearFavorites } from './favoritesStore'
import { loadFavorites, saveFavorites } from '../features/memory/favorites'
import { masterKey } from './vault'

// The pin set seals under the member's MASTER key, not the scope key (ADR 0068) — so this suite holds a master
// key where it used to hold an epoch key set. What it asserts is unchanged; only the key it is asserted with
// moved. That the scope key can no longer OPEN the result is the separate, load-bearing case in
// favoritesPrivacy.test.ts.
const MK = new Uint8Array(32)

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockReturnValue(MK)
  clearFavorites()
})

describe('favoritesStore', () => {
  it('loads + reflects the pinned set via isFavorite', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set(['a']))
    await loadFavoritesFor('s')
    expect(favoritesStore.loaded).toBe(true)
    expect(isFavorite('a')).toBe(true)
    expect(isFavorite('b')).toBe(false)
  })

  it('toggles optimistically and persists the whole set', async () => {
    vi.mocked(saveFavorites).mockResolvedValue(undefined)
    await toggleFavorite('a', 's')
    expect(isFavorite('a')).toBe(true)
    expect(saveFavorites).toHaveBeenCalledWith('s', MK, new Set(['a']))

    await toggleFavorite('a', 's') // toggle off
    expect(isFavorite('a')).toBe(false)
  })

  it('reverts the optimistic change when the write fails', async () => {
    vi.mocked(saveFavorites).mockRejectedValue(new Error('network'))
    await toggleFavorite('a', 's')
    expect(isFavorite('a')).toBe(false) // reverted to the pre-toggle state
  })

  it('clears on sign-out / re-lock', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set(['a']))
    await loadFavoritesFor('s')
    clearFavorites()
    expect(favoritesStore.loaded).toBe(false)
    expect(isFavorite('a')).toBe(false)
  })
})
