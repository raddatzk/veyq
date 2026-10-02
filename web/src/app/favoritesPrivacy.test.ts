// The one property this whole split exists for: a member's Memory pins are PRIVATE — unreadable by a
// co-member who holds the shared scope key (ADR 0068 §1, discharging ADR 0014 §7).
//
// Two things make this test different from everything in favoritesStore.test.ts, and both are deliberate:
//
//  1. It does NOT mock `features/memory/favorites` — the seal is the thing under test, so the real data layer
//     has to run and the ciphertext it PUTs is the evidence.
//  2. It asserts on the RAW `v1.open` throwing, never on `loadFavorites` returning nothing. `loadFavorites`
//     swallows a failed unwrap by design (a corrupt blob must not block browse, and favorites.test.ts asserts
//     that as intended behaviour) — so "loading with the scope key yields an empty set" is green today, green
//     after the fix, and green again if the fix is reverted. It is a bite that can never bite.
//
// The lever: re-seal under the scope key in `favoritesStore.ts`, and the first expectation below goes red.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../api/client', () => ({ favoritesApi: vi.fn() }))
vi.mock('./sessionRefresh', () => ({ withRefresh: (fn: () => unknown) => fn() }))
vi.mock('./vault', () => ({ masterKey: vi.fn() }))

import { toggleFavorite, clearFavorites, loadFavoritesFor } from './favoritesStore'
import { favoritesApi } from '../api/client'
import { fakeApi } from '../test/apiMock'
import { masterKey } from './vault'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

const K_SC = v1.randomBytes(32) // the scope content key — shared with every member of a group
const MK = v1.randomBytes(32) // this member's master key — theirs alone, on every device they unlock

const decoder = new TextDecoder()
// Canonical scope ids: the pin set is sealed bound to its scope (ADR 0072).
const SCOPE = '5c09e000-0000-4000-8000-0000000000f1'
const SCOPE_PERSONAL = '5c09e000-0000-4000-8000-0000000000f2'
const favs = (scopeId: string) => ({ purpose: 'favorites', scopeId }) as const

let stored: string | null = null

beforeEach(() => {
  vi.clearAllMocks()
  clearFavorites()
  stored = null
  vi.mocked(masterKey).mockReturnValue(MK)
  fakeApi(favoritesApi, {
    getFavorites: () => Promise.resolve({ ciphertext: stored }),
    putFavorites: ({ putFavoritesRequest }) => {
      stored = putFavoritesRequest.ciphertext
      return Promise.resolve()
    },
  })
})

describe("a member's Memory pins are private to them", () => {
  it('does not open with the scope key a co-member holds', async () => {
    await loadFavoritesFor(SCOPE) // a toggle waits for the set it replaces (ADR 0077 point 5)
    await toggleFavorite('memory-1', SCOPE)
    expect(stored).toBeTruthy()
    const blob = fromB64std(stored!)

    // THE bite. In a group scope every member holds K_SC, so a pin set sealed under it is readable by all of
    // them — the leak ADR 0014 §7 named and this seal closes. Raw unwrap, because the loader swallows.
    expect(() => v1.open(favs(SCOPE), K_SC, blob)).toThrow(new v1.OpenRefused('favorites'))

    // ...and it does open with the member's own key, or the feature would simply be broken rather than private.
    expect(JSON.parse(decoder.decode(v1.open(favs(SCOPE), MK, blob)))).toEqual(['memory-1'])
  })

  it('seals the same way in a personal scope — the seal does not branch on scope type', async () => {
    // A scope-conditional seal (K_SC when personal, master key when group) would be defensible on paper and is
    // exactly where a fallback survives untested. There is no personal/group argument anywhere on this path,
    // so this asserts the absence of that branch the only way it can be observed: identically sealed output.
    await loadFavoritesFor(SCOPE_PERSONAL)
    await toggleFavorite('memory-2', SCOPE_PERSONAL)
    const blob = fromB64std(stored!)
    expect(() => v1.open(favs(SCOPE_PERSONAL), K_SC, blob)).toThrow(new v1.OpenRefused('favorites'))
    expect(JSON.parse(decoder.decode(v1.open(favs(SCOPE_PERSONAL), MK, blob)))).toEqual(['memory-2'])
  })
})
