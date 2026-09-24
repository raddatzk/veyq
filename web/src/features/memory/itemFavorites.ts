// Item-mark data layer (en.memory.favorites, ADR 0068). The OTHER favorite object: where the Memory pin is
// private to its member, an item mark is deliberately **shared** with the scope — the gallery's
// "everyone's / only mine" filter and its per-tile count are computed on device, so the read hands back every
// member's sealed blob and the client opens each one.
//
// Sealed under `K_SC` at the **base epoch `E_0`** (ADR 0024, cr-0089). This rationale moved here from the pin
// store when the pin moved to the master key, and it still holds for exactly this object: the marked set is
// one blob re-sealed on every toggle, so it must key off a **stable** scope key that a rotation never changes
// — otherwise a rotation strands the previously-sealed set. For an unrotated scope `E_0` is simply the current
// key. (Node content still seals under the *write* epoch; item marks are the deliberate exception.)
//
// Partitioned per **node**, not per scope: the client has just listed that node's media, so the server already
// observed the node id and the partition leaks nothing new — while it keeps the 65 536-char `ciphertext` cap
// out of reach and stops a single toggle from re-uploading the whole scope's marks (ADR 0068 §4).
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { itemFavoritesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'

const ITEM_FAV_CTX = 'item-favorites'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/**
 * One member's marked-media set for a node, as the client holds it after opening the blob.
 *
 * `own` is the only identity there is — the wire carries no member id at all (ADR 0068 §6). That is what makes
 * "never show who marked what" a property of the data rather than a rule the UI is trusted to keep: this
 * client could not name a member if it wanted to. An item's count is simply how many sets contain its id.
 */
export type MemberMarks = { own: boolean; ids: Set<string> }

/**
 * Load + decrypt **every** member's marked set for this Memory. A blob that will not open degrades to an empty
 * set for that member rather than failing the gallery — one unreadable member must not blank the aggregate.
 */
export async function loadItemFavorites(
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
): Promise<MemberMarks[]> {
  const entries = await withRefresh(() => itemFavoritesApi().getItemFavorites({ scopeId, nodeId }))
  return entries.map((e) => {
    try {
      const json = decoder.decode(v1.symUnwrap(scopeKey, fromB64std(e.ciphertext), ITEM_FAV_CTX))
      const ids = JSON.parse(json)
      return {
        own: e.self,
        ids: new Set<string>(Array.isArray(ids) ? ids.filter((i): i is string => typeof i === 'string') : []),
      }
    } catch {
      return { own: e.self, ids: new Set<string>() }
    }
  })
}

/** Seal + store the **caller's own** marked set for this Memory (replaces that one blob). */
export async function saveItemFavorites(
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
  ids: Set<string>,
): Promise<void> {
  const json = encoder.encode(JSON.stringify([...ids]))
  const ciphertext = b64std(v1.symWrap(scopeKey, json, ITEM_FAV_CTX, v1.randomBytes(12)))
  await withRefresh(() => itemFavoritesApi().putItemFavorites({ scopeId, nodeId, putItemFavoritesRequest: { ciphertext } }))
}
