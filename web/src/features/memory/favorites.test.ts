import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ favoritesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (fn: () => unknown) => fn() }))

import { loadFavorites, saveFavorites } from './favorites'
import { favoritesApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

const K = v1.randomBytes(32)

function mockApi(get: ReturnType<typeof vi.fn>, put: ReturnType<typeof vi.fn>) {
  vi.mocked(favoritesApi).mockReturnValue({ getFavorites: get, putFavorites: put } as unknown as ReturnType<typeof favoritesApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('favorites data layer', () => {
  it('round-trips the pinned set through seal → store → load → decrypt', async () => {
    let stored: string | null = null
    const put = vi.fn(({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext })
    const get = vi.fn(() => Promise.resolve({ ciphertext: stored }))
    mockApi(get, put)

    await saveFavorites('scope', K, new Set(['m1', 'm2']))
    expect(stored).toBeTruthy()
    // operator-blind: the stored blob is a sealed envelope (0x02 ‖ nonce(12) ‖ ciphertext ‖ tag(16)), never the
    // cleartext id list. Assert that *structurally* — a substring scan of the random base64 is flaky (a 2-byte
    // needle like "m1" hits the random ciphertext/nonce by chance).
    const raw = fromB64std(stored!)
    const plaintext = v1.utf8(JSON.stringify(['m1', 'm2']))
    expect(raw[0]).toBe(0x02) // SYM_TYPE marker
    expect(raw.length).toBe(1 + 12 + plaintext.length + 16) // type ‖ nonce ‖ ciphertext ‖ tag, not the raw ids
    expect([...raw.subarray(13, 13 + plaintext.length)]).not.toEqual([...plaintext]) // the body is encrypted

    const loaded = await loadFavorites('scope', K)
    expect([...loaded].sort()).toEqual(['m1', 'm2'])
  })

  it('returns an empty set when nothing is stored', async () => {
    mockApi(vi.fn(() => Promise.resolve({ ciphertext: null })), vi.fn())
    expect((await loadFavorites('scope', K)).size).toBe(0)
  })

  it('returns an empty set on an unreadable blob (wrong key) instead of throwing', async () => {
    let stored: string | null = null
    const put = vi.fn(({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext })
    mockApi(vi.fn(() => Promise.resolve({ ciphertext: stored })), put)
    await saveFavorites('scope', v1.randomBytes(32), new Set(['x'])) // sealed under a DIFFERENT key
    expect((await loadFavorites('scope', K)).size).toBe(0)
  })
})
