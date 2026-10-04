import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ favoritesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (fn: () => unknown) => fn() }))

import { loadFavorites, saveFavorites } from './favorites'
import { favoritesApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { FavoritesApi } from '../../api/generated/favorites'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

const K = v1.randomBytes(32)

function mockApi(getFavorites: FavoritesApi['getFavorites'], putFavorites: FavoritesApi['putFavorites']) {
  fakeApi(favoritesApi, { getFavorites, putFavorites })
}

beforeEach(() => vi.clearAllMocks())

// A canonical scope id: the pin set is sealed bound to its scope (ADR 0072).
const SCOPE = '5c09e000-0000-4000-8000-0000000000f3'

describe('favorites data layer', () => {
  it('round-trips the pinned set through seal → store → load → decrypt', async () => {
    let stored: string | null = null
    mockApi(
      async () => ({ ciphertext: stored }),
      async ({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext },
    )

    await saveFavorites(SCOPE, K, new Set(['m1', 'm2']))
    expect(stored).toBeTruthy()
    // operator-blind: the stored blob is a sealed envelope (0x02 ‖ nonce(12) ‖ ciphertext ‖ tag(16)), never the
    // cleartext id list. Assert that *structurally* — a substring scan of the random base64 is flaky (a 2-byte
    // needle like "m1" hits the random ciphertext/nonce by chance).
    const raw = fromB64std(stored!)
    const plaintext = v1.utf8(JSON.stringify(['m1', 'm2']))
    expect(raw[0]).toBe(0x02) // SYM_TYPE marker
    expect(raw.length).toBe(1 + 12 + plaintext.length + 16) // type ‖ nonce ‖ ciphertext ‖ tag, not the raw ids
    expect([...raw.subarray(13, 13 + plaintext.length)]).not.toEqual([...plaintext]) // the body is encrypted

    const loaded = await loadFavorites(SCOPE, K)
    expect([...loaded].sort()).toEqual(['m1', 'm2'])
  })

  it('returns an empty set when nothing is stored', async () => {
    mockApi(async () => ({ ciphertext: null }), async () => {})
    expect((await loadFavorites(SCOPE, K)).size).toBe(0)
  })

  it('returns an empty set on an unreadable blob (wrong key) instead of throwing', async () => {
    let stored: string | null = null
    mockApi(
      async () => ({ ciphertext: stored }),
      async ({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext },
    )
    await saveFavorites(SCOPE, v1.randomBytes(32), new Set(['x'])) // sealed under a DIFFERENT key
    expect((await loadFavorites(SCOPE, K)).size).toBe(0)
  })
})
