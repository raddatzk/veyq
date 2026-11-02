import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./itemFavorites', () => ({
  loadItemFavorites: vi.fn(),
  saveItemFavorites: vi.fn(),
}))

import {
  itemFavoritesStore,
  isMarkedByMe,
  markCount,
  markedByAnyone,
  loadItemFavoritesFor,
  toggleItemFavorite,
  clearItemFavorites,
} from './itemFavoritesStore'
import { loadItemFavorites, saveItemFavorites } from './itemFavorites'
import type { ScopeKey } from '../spaces/epochs'

// The scope's one key at the epoch a write states (ADR 0115 decision 3): the read opens under its bytes, the write is
// handed the whole of it.
const KEY: ScopeKey = { epoch: 2, key: new Uint8Array(32).fill(3) }

/** Two co-members and the caller, as the read hands them over: `own` is the only identity there is. */
const threeMembers = [
  { own: true, ids: new Set(['a', 'b']) },
  { own: false, ids: new Set(['b']) },
  { own: false, ids: new Set(['b', 'c']) },
]

beforeEach(() => {
  vi.clearAllMocks()
  clearItemFavorites()
})

describe('itemFavoritesStore', () => {
  /**
   * **The aggregate.** The count is across ALL members, not the caller's own — that is the whole difference
   * between this and a private pin, and it is what the tile heart shows. Counting only the caller's set
   * reddens here.
   */
  it('counts a mark across every member, not just the caller', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)

    expect(loadItemFavorites).toHaveBeenCalledWith('s', 'n', KEY) // the read opens each wrap under the key at its epoch
    expect(markCount('b')).toBe(3) // all three
    expect(markCount('a')).toBe(1) // the caller alone
    expect(markCount('c')).toBe(1) // one co-member alone
    expect(markCount('zzz')).toBe(0)
  })

  it('tells the caller-s own marks from everyone-s', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)

    expect(isMarkedByMe('a')).toBe(true)
    expect(isMarkedByMe('c')).toBe(false) // marked, but not by me
    expect([...markedByAnyone()].sort()).toEqual(['a', 'b', 'c'])
  })

  /**
   * The aggregate-only rule is carried by the DATA, not by the components. There is no member id anywhere in
   * what this store holds, so no view can leak one however it is written — and any future change that puts an
   * identity back on the wire has to pass through this assertion first.
   */
  it('holds no member identity beyond "is this mine"', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)

    for (const m of itemFavoritesStore.marks) {
      expect(Object.keys(m).sort()).toEqual(['ids', 'own'])
    }
    // and exactly one of them is the caller's
    expect(itemFavoritesStore.marks.filter((m) => m.own)).toHaveLength(1)
  })

  it('toggles optimistically and persists only the caller-s own set', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)
    vi.mocked(saveItemFavorites).mockResolvedValue(undefined)

    await toggleItemFavorite('c', 's', 'n', KEY)

    expect(isMarkedByMe('c')).toBe(true)
    expect(saveItemFavorites).toHaveBeenCalledWith('s', 'n', KEY, new Set(['a', 'b', 'c']))
    // the co-members' sets are untouched — the count for 'c' is now the caller plus the one who had it
    expect(markCount('c')).toBe(2)
  })

  it('reverts the optimistic mark when the write fails', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)
    const network = new Error('network')
    vi.mocked(saveItemFavorites).mockRejectedValue(network)

    await expect(toggleItemFavorite('c', 's', 'n', KEY)).rejects.toBe(network) // rethrown: MemoryDetail says why (inbox #500)

    expect(isMarkedByMe('c')).toBe(false)
    expect(markCount('c')).toBe(1) // back to the one co-member who had it
  })

  /**
   * W9 (#274, live defect): the write replaces the caller's whole set for the node, so a toggle before that set
   * loaded — or after a load that failed, which leaves `loaded` false — stored a one-element set over the real one.
   * It now does nothing: no optimistic flip, no write. A load for ANOTHER node does not count either. Control:
   * after this node's load the same toggle saves the loaded set plus the new id.
   */
  it('W9: a toggle before this node\'s set loaded does nothing; after the load it keeps the loaded set', async () => {
    vi.mocked(saveItemFavorites).mockResolvedValue(undefined)
    await toggleItemFavorite('c', 's', 'n', KEY)
    expect(saveItemFavorites).not.toHaveBeenCalled()
    expect(isMarkedByMe('c')).toBe(false)

    vi.mocked(loadItemFavorites).mockRejectedValueOnce(new Error('network'))
    await loadItemFavoritesFor('s', 'n', KEY).catch(() => undefined) // a failed load leaves the store unloaded
    await toggleItemFavorite('c', 's', 'n', KEY)
    expect(saveItemFavorites).not.toHaveBeenCalled()

    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'other-node', KEY)
    await toggleItemFavorite('c', 's', 'n', KEY)
    expect(saveItemFavorites).not.toHaveBeenCalled()

    await loadItemFavoritesFor('s', 'n', KEY)
    await toggleItemFavorite('c', 's', 'n', KEY)
    expect(saveItemFavorites).toHaveBeenCalledWith('s', 'n', KEY, new Set(['a', 'b', 'c']))
  })

  it('clears when the Memory closes', async () => {
    vi.mocked(loadItemFavorites).mockResolvedValue(threeMembers)
    await loadItemFavoritesFor('s', 'n', KEY)
    clearItemFavorites()
    expect(itemFavoritesStore.loaded).toBe(false)
    expect(markCount('b')).toBe(0)
  })
})
