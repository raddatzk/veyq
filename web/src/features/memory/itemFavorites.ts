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
//
// **Which entry is mine is proven here, not told by the server (ADR 0068 §9).** The plaintext is
// `{"tag","ids"}`: the tag is an HMAC under a key derived from the marking member's Master Key, over the scope, the
// node and the marked set. An entry is mine only when the server flags it `self` AND its tag verifies under my key —
// the flag alone would let the server show another member's set as mine, and my next toggle would then write that
// set into my own row. Everything not provably mine counts as someone else's: a degradation, never a refusal.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { itemFavoritesApi, ownItemFavoritesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { masterKey } from '../../app/vault'
import { openScopeById } from '../spaces/scopes'
import { keyAt, zeroEpochKeys } from '../spaces/epochs'

/**
 * Every member's marks on one node, bound to (scope, node) (ADR 0072). Deliberately NOT to the marking member: the
 * read carries no member id (ADR 0068 §6 — the aggregate is anonymous in the DATA), so an opener could not supply
 * one. Whose an entry is, is the self-tag inside it.
 */
const itemFavContext = (scopeId: string, nodeId: string) => ({ purpose: 'item-favorites', scopeId, nodeId }) as const
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

/** A blob as opened: its ids, and its self-tag when it carries a well-formed one. */
type OpenedMarks = { ids: Set<string>; tag: Uint8Array | null }

/**
 * Open one entry. A blob that will not open is an empty set; a plaintext with no usable tag — a bare array, a tag
 * that is not base64 — still counts its ids, it just cannot be anybody's *own* (ADR 0068 §9).
 */
function openMarks(scopeId: string, nodeId: string, scopeKey: Uint8Array, ciphertext: string): OpenedMarks {
  let pt: unknown
  try {
    pt = JSON.parse(decoder.decode(v1.open(itemFavContext(scopeId, nodeId), scopeKey, fromB64std(ciphertext))))
  } catch {
    return { ids: new Set(), tag: null }
  }
  const body = (pt ?? {}) as { tag?: unknown; ids?: unknown }
  const list = Array.isArray(pt) ? pt : Array.isArray(body.ids) ? body.ids : []
  const ids = new Set<string>(list.filter((i): i is string => typeof i === 'string'))
  let tag: Uint8Array | null = null
  if (typeof body.tag === 'string') {
    try {
      tag = fromB64std(body.tag)
    } catch {
      tag = null
    }
  }
  return { ids, tag }
}

/** Seal `ids` with their self-tag under `selfKey` and store them as the caller's blob for the node. */
async function putMarks(scopeId: string, nodeId: string, scopeKey: Uint8Array, selfKey: Uint8Array, ids: Set<string>): Promise<void> {
  const tag = b64std(v1.itemMarkSelfTag(selfKey, { scopeId, nodeId, ids }))
  const json = encoder.encode(JSON.stringify({ tag, ids: [...ids] }))
  const ciphertext = b64std(v1.seal(itemFavContext(scopeId, nodeId), scopeKey, json))
  await withRefresh(() => itemFavoritesApi().putItemFavorites({ scopeId, nodeId, putItemFavoritesRequest: { ciphertext } }))
}

/** The caller's self-tag key, derived per call from the vault's Master Key — null while the vault is locked. */
function selfTagKey(): Uint8Array | null {
  const mk = masterKey()
  if (!mk) return null
  try {
    return v1.itemMarkSelfKey(mk)
  } finally {
    mk.fill(0)
  }
}

/**
 * Load + decrypt **every** member's marked set for this Memory. A blob that will not open degrades to an empty
 * set for that member rather than failing the gallery — one unreadable member must not blank the aggregate.
 *
 * `own` is `self` AND a verifying tag, for the first such entry only (an honest server flags at most one; more are
 * replays of the caller's own older sets, which is freshness — ADR 0072 §5). With the vault locked nothing is mine.
 */
export async function loadItemFavorites(
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
): Promise<MemberMarks[]> {
  const entries = await withRefresh(() => itemFavoritesApi().getItemFavorites({ scopeId, nodeId }))
  const selfKey = selfTagKey()
  try {
    let mineFound = false
    return entries.map((e) => {
      const { ids, tag } = openMarks(scopeId, nodeId, scopeKey, e.ciphertext)
      const own =
        !mineFound && e.self && selfKey !== null && tag !== null && v1.itemMarkSelfTagVerifies(selfKey, { scopeId, nodeId, ids }, tag)
      if (own) mineFound = true
      return { own, ids }
    })
  } finally {
    selfKey?.fill(0)
  }
}

/**
 * Seal + store the **caller's own** marked set for this Memory (replaces that one blob), tagged as theirs.
 * Refuses before any write while the vault is locked: an untagged write would replace the caller's real set with
 * one nothing can recognise as theirs — and the store's toggle reverts on exactly this throw, so nothing is sent.
 */
export async function saveItemFavorites(
  scopeId: string,
  nodeId: string,
  scopeKey: Uint8Array,
  ids: Set<string>,
): Promise<void> {
  const selfKey = selfTagKey()
  if (!selfKey) throw new Error('item marks: the vault is locked, so there is no key to tag them as mine')
  try {
    await putMarks(scopeId, nodeId, scopeKey, selfKey, ids)
  } finally {
    selfKey.fill(0)
  }
}

/**
 * Re-tag the caller's own marks from the **old** Master Key to a **new** one — key rotation (ADR 0060, ADR 0068 §9).
 * Both keys are explicit for the reason the other Master-Key riders take them: the vault still holds the old key
 * (and the old identity, which is what opens each scope's grant here) while the rotation runs.
 *
 * **The old key is what licenses a re-tag.** The listing is the server's — a hint, not a proof — so a row is
 * re-sealed under the new key only when its tag verifies under the old one; a co-member's blob slipped into the
 * listing stays not-mine. **Best-effort per Memory**, like the riders it joins: a scope that does not open (another
 * tenant than the one this app is on, a grant awaiting the current key) or a write that fails leaves that Memory's
 * set reading as someone else's until its next toggle, and every other Memory is still re-tagged.
 */
export async function retagItemMarksUnderNewMasterKey(oldMk: Uint8Array, newMk: Uint8Array): Promise<void> {
  const rows = await withRefresh(() => ownItemFavoritesApi().listOwnItemFavorites())
  const byScope = new Map<string, typeof rows>()
  for (const row of rows) byScope.set(row.scopeId, [...(byScope.get(row.scopeId) ?? []), row])
  const oldKey = v1.itemMarkSelfKey(oldMk)
  const newKey = v1.itemMarkSelfKey(newMk)
  try {
    for (const [scopeId, scopeRows] of byScope) {
      const opened = await openScopeById(scopeId).catch(() => null)
      if (!opened) continue
      try {
        const e0 = keyAt(opened.epochs, 0)
        for (const { nodeId, ciphertext } of scopeRows) {
          const { ids, tag } = openMarks(scopeId, nodeId, e0, ciphertext)
          if (!tag || !v1.itemMarkSelfTagVerifies(oldKey, { scopeId, nodeId, ids }, tag)) continue
          await putMarks(scopeId, nodeId, e0, newKey, ids).catch(() => undefined)
        }
      } finally {
        zeroEpochKeys(opened.epochs)
      }
    }
  } finally {
    oldKey.fill(0)
    newKey.fill(0)
  }
}
