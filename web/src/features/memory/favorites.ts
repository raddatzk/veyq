// Memory-pin data layer (en.memory.favorites, ADR 0068). Operator-blind: the caller's pinned-Memory id set is
// AEAD-sealed (ctx `favorites`) and stored server-side as one opaque blob — the server never sees which
// Memories are pinned, only ciphertext + its size. Last-write-wins (the whole set is re-sealed and PUT on
// every toggle; the set is tiny).
//
// The KEK is passed in rather than read from the vault, and that is load-bearing in two places: it keeps this
// layer pure for the privacy test, and `rotateKeys` must seal under the NEW master key while the vault still
// holds the OLD one (the same reason identityPins.ts takes both keys explicitly). Callers pass the member's
// **master key** — never `K_SC`, which is shared in a group and would make the set readable by co-members.
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

/**
 * Re-seal the caller's pin sets from the **old** Master Key to a **new** one — key rotation (ADR 0060 step 4).
 *
 * It exists for the same reason `resealPinsUnderNewMasterKey` does: during a rotation the vault still holds the
 * *old* Master Key (the sole-member scope re-key that follows opens grants with the old identity), so a helper
 * that read the vault would seal under exactly the key the rotation is retiring. Both keys are therefore
 * explicit.
 *
 * **Per scope, because the pin set is per scope.** `RotateAccountKeysResult`'s two lists partition the caller's
 * scopes, so their union is the complete set — including the queued ones, where the caller's *grant* is stale
 * but their *membership* is not, and this endpoint gates on membership.
 *
 * **Best-effort by design, and the failure is a degradation rather than a loss of the account.** An unreadable
 * blob already decodes to the empty set ({@link loadFavorites} swallows), so a re-seal that never lands costs
 * the pins, not access: the Memories are all still there, they are simply no longer pinned.
 */
export async function resealFavoritesUnderNewMasterKey(
  oldMk: Uint8Array,
  newMk: Uint8Array,
  scopeIds: string[],
): Promise<void> {
  for (const scopeId of scopeIds) {
    const ids = await loadFavorites(scopeId, oldMk)
    if (ids.size === 0) continue // nothing pinned here, or nothing readable — either way nothing to carry
    await saveFavorites(scopeId, newMk, ids)
  }
}
