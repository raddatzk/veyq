import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/memory/favorites', () => ({ loadFavorites: vi.fn(), saveFavorites: vi.fn() }))

import { favoritesStore, isFavorite, toggleFavorite, loadFavoritesFor, clearFavorites } from './favoritesStore'
import { loadFavorites, saveFavorites } from '../features/memory/favorites'
import { epochsOf } from '../features/spaces/epochsTestUtil'

const KEY = new Uint8Array(32)

beforeEach(() => {
  vi.clearAllMocks()
  clearFavorites()
})

describe('favoritesStore', () => {
  it('loads + reflects the pinned set via isFavorite', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set(['a']))
    await loadFavoritesFor('s', epochsOf(KEY))
    expect(favoritesStore.loaded).toBe(true)
    expect(isFavorite('a')).toBe(true)
    expect(isFavorite('b')).toBe(false)
  })

  it('toggles optimistically and persists the whole set', async () => {
    vi.mocked(saveFavorites).mockResolvedValue(undefined)
    await toggleFavorite('a', 's', epochsOf(KEY))
    expect(isFavorite('a')).toBe(true)
    expect(saveFavorites).toHaveBeenCalledWith('s', KEY, new Set(['a']))

    await toggleFavorite('a', 's', epochsOf(KEY)) // toggle off
    expect(isFavorite('a')).toBe(false)
  })

  it('reverts the optimistic change when the write fails', async () => {
    vi.mocked(saveFavorites).mockRejectedValue(new Error('network'))
    await toggleFavorite('a', 's', epochsOf(KEY))
    expect(isFavorite('a')).toBe(false) // reverted to the pre-toggle state
  })

  it('clears on sign-out / re-lock', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set(['a']))
    await loadFavoritesFor('s', epochsOf(KEY))
    clearFavorites()
    expect(favoritesStore.loaded).toBe(false)
    expect(isFavorite('a')).toBe(false)
  })
})
