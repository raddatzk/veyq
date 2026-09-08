// Favorites data layer (en.memory.favorites, cr-0031). Operator-blind: the caller's pinned-Memory id set is
// AEAD-sealed under the scope content key `K_SC` (ctx `favorites`) and stored server-side as one opaque blob —
// the server never sees which Memories are pinned, only ciphertext + its size. Last-write-wins (the whole set
// is re-sealed and PUT on every toggle; the set is tiny). Mirrors the sealing in tree.ts.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { favoritesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'

const FAV_CTX = 'favorites'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Load + decrypt the caller's pinned-Memory id set for the scope (empty when none stored or unreadable). */
export async function loadFavorites(scopeId: string, scopeKey: Uint8Array): Promise<Set<string>> {
  const blob = await withRefresh(() => favoritesApi().getFavorites({ scopeId }))
  if (!blob.ciphertext) return new Set()
  try {
    const json = decoder.decode(v1.symUnwrap(scopeKey, fromB64std(blob.ciphertext), FAV_CTX))
    const ids = JSON.parse(json)
    return Array.isArray(ids) ? new Set(ids.filter((id): id is string => typeof id === 'string')) : new Set()
  } catch {
    return new Set()
  }
}

/** Seal + store the caller's pinned-Memory id set for the scope (replaces the whole blob). */
export async function saveFavorites(scopeId: string, scopeKey: Uint8Array, ids: Set<string>): Promise<void> {
  const json = encoder.encode(JSON.stringify([...ids]))
  const ciphertext = b64std(v1.symWrap(scopeKey, json, FAV_CTX, v1.randomBytes(12)))
  await withRefresh(() => favoritesApi().putFavorites({ scopeId, putFavoritesRequest: { ciphertext } }))
}
