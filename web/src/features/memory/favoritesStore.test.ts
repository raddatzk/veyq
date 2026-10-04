import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./favorites', () => ({ loadFavorites: vi.fn(), saveFavorites: vi.fn() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))

import { favoritesStore, isFavorite, toggleFavorite, loadFavoritesFor, clearFavorites } from './favoritesStore'
import { loadFavorites, saveFavorites } from './favorites'
import { masterKey } from '../../app/vault'

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
    vi.mocked(loadFavorites).mockResolvedValue(new Set())
    await loadFavoritesFor('s')
    vi.mocked(saveFavorites).mockResolvedValue(undefined)
    await toggleFavorite('a', 's')
    expect(isFavorite('a')).toBe(true)
    expect(saveFavorites).toHaveBeenCalledWith('s', MK, new Set(['a']))

    await toggleFavorite('a', 's') // toggle off
    expect(isFavorite('a')).toBe(false)
  })

  it('reverts the optimistic change when the write fails', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set())
    await loadFavoritesFor('s')
    vi.mocked(saveFavorites).mockRejectedValue(new Error('network'))
    await toggleFavorite('a', 's')
    expect(saveFavorites).toHaveBeenCalledOnce() // the write was tried — so the revert is what reads false here
    expect(isFavorite('a')).toBe(false) // reverted to the pre-toggle state
  })

  /**
   * W9 (#274, live defect): the write replaces the whole pin set, so a toggle before the set loaded stored a
   * one-element set over the real pins. It now does nothing — no flip, no write. Control: after the load the same
   * toggle saves the loaded pins plus the new one.
   */
  it('W9: a toggle before the set loaded does nothing; after the load it keeps the loaded pins', async () => {
    vi.mocked(saveFavorites).mockResolvedValue(undefined)
    await toggleFavorite('a', 's')
    expect(saveFavorites).not.toHaveBeenCalled()
    expect(isFavorite('a')).toBe(false)

    vi.mocked(loadFavorites).mockResolvedValue(new Set(['b']))
    await loadFavoritesFor('s')
    await toggleFavorite('a', 's')
    expect(saveFavorites).toHaveBeenCalledWith('s', MK, new Set(['b', 'a']))
  })

  it('clears on sign-out / re-lock', async () => {
    vi.mocked(loadFavorites).mockResolvedValue(new Set(['a']))
    await loadFavoritesFor('s')
    clearFavorites()
    expect(favoritesStore.loaded).toBe(false)
    expect(isFavorite('a')).toBe(false)
  })
})
