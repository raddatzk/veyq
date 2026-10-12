// A node pin on the wire (ADR 0068, inbox #409 moved it to core): through the real store, data layer and generated client,
// recorded at `fetch`. What is pinned is what moving the pin from Memories to core must NOT change:
//   - the route `/api/scopes/{scopeId}/favorites`, GET to read and PUT to replace, the body exactly `{ "ciphertext": <b64> }`;
//   - the plaintext is a bare JSON array of node ids, sealed with purpose `favorites` bound to the scope, under the MASTER key
//     (not a scope key) - the seal ADR 0068 describes and the crypto vectors pin, so every pin set stored before the move opens;
//   - the whole set is sent on every toggle (last-write-wins), and a toggle before the set has loaded sends nothing.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'

vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { masterKey } from '../../app/vault'
import { clearFavorites, favoritesStore, loadFavoritesFor, toggleFavorite } from './favoritesStore'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f3'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const B = '5c09e000-0000-4000-8000-0000000000b0'
const MK = v1.randomBytes(32)

const calls: { url: string; method: string; body: string | null }[] = []
let stored: string | null = null
beforeEach(() => {
  calls.length = 0
  stored = null
  clearFavorites()
  vi.mocked(masterKey).mockReturnValue(MK)
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ url: String(input), method, body: init?.body ? String(init.body) : null })
    if (method === 'PUT') {
      stored = (JSON.parse(String(init?.body)) as { ciphertext: string }).ciphertext
      return new Response(null, { status: 204 })
    }
    return new Response(JSON.stringify({ ciphertext: stored }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
})
afterEach(() => vi.unstubAllGlobals())

const open = (b64: string) => new TextDecoder().decode(v1.open({ purpose: 'favorites', scopeId: SCOPE }, MK, fromB64std(b64)))

describe('the pin on the wire', () => {
  it('PUTs exactly { ciphertext } to /api/scopes/{id}/favorites: a bare id array, sealed `favorites` under the master key', async () => {
    await loadFavoritesFor(SCOPE)
    await toggleFavorite(A, SCOPE)
    const put = calls.filter((c) => c.method === 'PUT')
    expect(put).toHaveLength(1)
    expect(put[0].url).toMatch(new RegExp(`/api/scopes/${SCOPE}/favorites$`))
    const json = JSON.parse(put[0].body!) as Record<string, unknown>
    expect(Object.keys(json)).toEqual(['ciphertext'])
    expect(open(json.ciphertext as string)).toBe(JSON.stringify([A]))
    // standard base64 (the contract's pattern), never base64url
    expect(json.ciphertext).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
  })

  it('does not open with another key or another scope\'s address (ADR 0072), and is not a scope key\'s seal', async () => {
    await loadFavoritesFor(SCOPE)
    await toggleFavorite(A, SCOPE)
    const blob = fromB64std(stored!)
    expect(() => v1.open({ purpose: 'favorites', scopeId: SCOPE }, v1.randomBytes(32), blob)).toThrow()
    expect(() => v1.open({ purpose: 'favorites', scopeId: '5c09e000-0000-4000-8000-0000000000f4' }, MK, blob)).toThrow()
  })

  it('sends the WHOLE set on every toggle (last-write-wins), and the next session reads it back', async () => {
    await loadFavoritesFor(SCOPE)
    await toggleFavorite(A, SCOPE)
    await toggleFavorite(B, SCOPE)
    const puts = calls.filter((c) => c.method === 'PUT').map((c) => open((JSON.parse(c.body!) as { ciphertext: string }).ciphertext))
    expect(puts).toEqual([JSON.stringify([A]), JSON.stringify([A, B])])
    clearFavorites()
    await loadFavoritesFor(SCOPE)
    expect([...favoritesStore.ids]).toEqual([A, B])
  })

  it('a set stored by the Memories-era client (a plain sealed id array) opens unchanged', async () => {
    stored = b64std(v1.seal({ purpose: 'favorites', scopeId: SCOPE }, MK, new TextEncoder().encode(JSON.stringify([A, 'gone-node']))))
    await loadFavoritesFor(SCOPE)
    expect([...favoritesStore.ids]).toEqual([A, 'gone-node'])
  })

  it('a toggle before the set has loaded sends nothing: it would store a one-element set over the real pins (ADR 0077 point 5)', async () => {
    await toggleFavorite(A, SCOPE)
    expect(calls.filter((c) => c.method === 'PUT')).toEqual([])
  })
})
