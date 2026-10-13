// The Memories application's lifecycle hooks (lifecycle.ts) in isolation: what each hook does, and what it must not.
// Their wiring into the shell is proven where the shell calls the registry (activeSpace.test.ts: scope reset/refresh,
// core/node/cover/coverStore.test.ts: sign-out, rotateKeys.test.ts: the master-key hook). The node pins are CORE's since inbox #409
// (`core/favorites`) and the covers since #411: the shell clears and loads them itself, so this application's hooks touch none of that.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./participantIndex', () => ({ resetParticipantIndex: vi.fn() }))
vi.mock('./itemFavorites', () => ({ retagItemMarksUnderNewMasterKey: vi.fn() }))
vi.mock('../../core/favorites/favorites', () => ({ resealFavoritesUnderNewMasterKey: vi.fn(), loadFavorites: vi.fn(), saveFavorites: vi.fn() }))
vi.mock('../../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: false, ids: new Set<string>() },
  clearFavorites: vi.fn(),
  loadFavoritesFor: vi.fn(),
}))

import { memoriesLifecycle } from './lifecycle'
import { retagItemMarksUnderNewMasterKey } from './itemFavorites'
import { resealFavoritesUnderNewMasterKey } from '../../core/favorites/favorites'
import { clearFavorites, loadFavoritesFor } from '../../core/favorites/favoritesStore'
import { resetParticipantIndex } from './participantIndex'

beforeEach(() => vi.clearAllMocks())

describe('onScopeReset', () => {
  it('drops the participants index: it holds Memory keys that must not outlive the scope', () => {
    memoriesLifecycle.onScopeReset?.()
    expect(resetParticipantIndex).toHaveBeenCalledTimes(1)
  })
  it('leaves the covers and the node pins to core: it holds no per-scope state of its own to drop', () => {
    memoriesLifecycle.onScopeReset?.()
    expect(clearFavorites).not.toHaveBeenCalled()
  })
})

describe('onScopeEntered', () => {
  it('has no hook: Memories holds no per-scope state of its own to load (the pins are core\'s)', async () => {
    expect(memoriesLifecycle.onScopeEntered).toBeUndefined()
    expect(loadFavoritesFor).not.toHaveBeenCalled()
  })
})

describe('onMasterKeyRotated', () => {
  const o = new Uint8Array([1])
  const n = new Uint8Array([2])

  it('re-tags the item marks, and re-seals no node pins (rotateKeys does, for every application)', async () => {
    await memoriesLifecycle.onMasterKeyRotated(o, n, ['a', 'b'])
    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledWith(o, n)
    expect(resealFavoritesUnderNewMasterKey).not.toHaveBeenCalled()
  })

  it('a failing item re-tag does not throw (a mark whose tag the new key does not verify is someone else\'s set)', async () => {
    vi.mocked(retagItemMarksUnderNewMasterKey).mockRejectedValueOnce(new Error('bad tag'))
    await expect(memoriesLifecycle.onMasterKeyRotated(o, n, [])).resolves.toBeUndefined()
  })
})
