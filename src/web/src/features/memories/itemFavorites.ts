// Item-mark data layer (en.memory.favorites, ADR 0068). The OTHER favorite object: where the Memory pin is
// private to its member, an item mark is deliberately **shared** with the scope — the gallery's
// "everyone's / only mine" filter and its per-tile count are computed on device, so the read hands back every
// member's sealed blob and the client opens each one.
//
// Sealed under a value key `K_v` of its own, minted at every write, whose wrap the scope's **one key** holds (ADR 0115),
// stating the wrap's epoch on every write (decision 3: another than the current one is `409 stale_epoch`, and the scope
// is opened again and the set sealed once more). A rotation re-wraps every member's `K_v` under its new key before its
// commit and never rewrites a set — Memories' scope re-key participant ({@link stageItemMarksRekey}, registered by
// `register.ts`) — so no set is stranded and no rotator can rewrite another member's marks.
//
// Partitioned per **node**, not per scope: the client has just listed that node's media, so the server already
// observed the node id and the partition leaks nothing new — and a single toggle re-uploads that Memory's set, never
// the scope's (ADR 0068 §4). Each id rides as its 16 raw bytes (`encodeItemMarks`, inbox #500), so the contract's
// 262,144-character cap holds 12,284 marks: a 10,000-photo Memory, every photo marked.
//
// **Which entry is mine is proven here, not told by the server (ADR 0068 §9).** The plaintext is
// `tag ‖ ids`: the tag is an HMAC under a key derived from the marking member's Master Key, over the scope, the
// node and the marked set. An entry is mine only when the server flags it `self` AND its tag verifies under my key —
// the flag alone would let the server show another member's set as mine, and my next toggle would then write that
// set into my own row. Everything not provably mine counts as someone else's: a degradation, never a refusal.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { itemFavoritesApi, ownItemFavoritesApi } from './api'
import { openValue, sealValue } from '../spaces/valueKey'
import { withRefresh } from '../../app/sessionRefresh'
import { masterKey } from '../../app/vault'
import { isStaleEpoch, openScopeById } from '../spaces/scopes'
import { zeroScopeKey, type ScopeKey } from '../spaces/epochs'
import type { ScopeRekeyContext } from '../../core/node/rekey/hooks'
import { itemFavoritesRekeyApi } from './api'

/**
 * Every member's marks on one node, bound to (scope, node) (ADR 0072). Deliberately NOT to the marking member: the
 * read carries no member id (ADR 0068 §6 — the aggregate is anonymous in the DATA), so an opener could not supply
 * one. Whose an entry is, is the self-tag inside it. Also the address its value key's wrap is bound to (`value-key`).
 */
const itemFavContext = (scopeId: string, nodeId: string) => ({ purpose: 'item-favorites', scopeId, nodeId }) as const

/**
 * One member's marked-media set for a node, as the client holds it after opening the blob.
 *
 * `own` is the only identity there is — the wire carries no member id at all (ADR 0068 §6). That is what makes
 * "never show who marked what" a property of the data rather than a rule the UI is trusted to keep: this
 * client could not name a member if it wanted to. An item's count is simply how many sets contain its id.
 */
export type MemberMarks = { own: boolean; ids: Set<string> }

/** A blob as opened: its ids, and its self-tag when it carries one. */
type OpenedMarks = { ids: Set<string>; tag: Uint8Array | null }

/**
 * The contract's cap on one member's sealed marks for one Memory (`PutItemFavoritesRequest.ciphertext`'s `maxLength`,
 * item-favorites.openapi.yaml; itemFavorites.test.ts reads it there): the self-tag and 12,284 ids.
 */
export const ITEM_MARKS_CIPHERTEXT_MAX = 262_144

/** The caller's marks for one Memory would not fit {@link ITEM_MARKS_CIPHERTEXT_MAX}: refused before any write. */
export class ItemMarksTooMany extends Error {}

/**
 * Open one entry: its value key from the wrap under the scope's one key, then the set under it. A blob or a wrap that
 * will not open — a wrap a rotator could not open is staged as one nobody opens (ADR 0115) —, or a plaintext that is not
 * `tag ‖ ids` in its one canonical form, is an empty set that is nobody's own — a degradation, never a refusal (ADR 0068
 * §9).
 */
function openMarks(scopeId: string, nodeId: string, scopeKey: ScopeKey, ciphertext: string, wrappedValueKey: string): OpenedMarks {
  const context = itemFavContext(scopeId, nodeId)
  try {
    const { tag, ids } = v1.decodeItemMarks(openValue(scopeKey, context, wrappedValueKey, (valueKey) =>
      v1.open(context, valueKey, fromB64std(ciphertext))))
    return { ids: new Set(ids), tag }
  } catch {
    return { ids: new Set(), tag: null }
  }
}

/**
 * Seal `ids` with their self-tag under `selfKey` — under a fresh value key, wrapped under the scope key (ADR 0115) — and
 * store them as the caller's blob for the node. A set past the cap throws {@link ItemMarksTooMany} before the PUT, so the
 * server never sees the attempt and the caller can say why.
 */
async function putMarks(scopeId: string, nodeId: string, scopeKey: ScopeKey, selfKey: Uint8Array, ids: Set<string>): Promise<void> {
  const tag = v1.itemMarkSelfTag(selfKey, { scopeId, nodeId, ids })
  const context = itemFavContext(scopeId, nodeId)
  const plaintext = v1.encodeItemMarks(tag, ids)
  const sealed = sealValue(scopeKey, context, (valueKey) => v1.seal(context, valueKey, plaintext))
  if (sealed.sealed.length > ITEM_MARKS_CIPHERTEXT_MAX) throw new ItemMarksTooMany(`${ids.size} marks do not fit one Memory's set`)
  await withRefresh(() =>
    itemFavoritesApi().putItemFavorites({
      scopeId, nodeId, putItemFavoritesRequest: { ciphertext: sealed.sealed, wrappedValueKey: sealed.wrapped, epoch: scopeKey.epoch },
    }),
  )
}

/** {@link putMarks}, and once more under the scope opened again when a rotation replaced the key in between
 *  (`409 stale_epoch`, ADR 0115 decision 3). */
async function putMarksAtCurrentEpoch(scopeId: string, nodeId: string, scopeKey: ScopeKey, selfKey: Uint8Array, ids: Set<string>): Promise<void> {
  try {
    await putMarks(scopeId, nodeId, scopeKey, selfKey, ids)
  } catch (e) {
    if (!(await isStaleEpoch(e))) throw e
    const opened = await openScopeById(scopeId)
    if (!opened) throw e
    try {
      await putMarks(scopeId, nodeId, opened.epochs, selfKey, ids)
    } finally {
      zeroScopeKey(opened.epochs)
    }
  }
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
  scopeKey: ScopeKey,
): Promise<MemberMarks[]> {
  const entries = await withRefresh(() => itemFavoritesApi().getItemFavorites({ scopeId, nodeId }))
  const selfKey = selfTagKey()
  try {
    let mineFound = false
    return entries.map((e) => {
      const { ids, tag } = openMarks(scopeId, nodeId, scopeKey, e.ciphertext, e.wrappedValueKey)
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
  scopeKey: ScopeKey,
  ids: Set<string>,
): Promise<void> {
  const selfKey = selfTagKey()
  if (!selfKey) throw new Error('item marks: the vault is locked, so there is no key to tag them as mine')
  try {
    await putMarksAtCurrentEpoch(scopeId, nodeId, scopeKey, selfKey, ids)
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
        for (const { nodeId, ciphertext, wrappedValueKey } of scopeRows) {
          const { ids, tag } = openMarks(scopeId, nodeId, opened.epochs, ciphertext, wrappedValueKey)
          if (!tag || !v1.itemMarkSelfTagVerifies(oldKey, { scopeId, nodeId, ids }, tag)) continue
          await putMarksAtCurrentEpoch(scopeId, nodeId, opened.epochs, newKey, ids).catch(() => undefined)
        }
      } finally {
        zeroScopeKey(opened.epochs)
      }
    }
  } finally {
    oldKey.fill(0)
    newKey.fill(0)
  }
}

/** The contract's cap per page of the scope re-key listing and per stage (`ScopeItemFavoritesRekeyStage`). */
export const ITEM_MARKS_REKEY_BATCH = 256

/**
 * Memories' part of a rotation's scope-level move (ADR 0115 decision 2, core's `ScopeRekeyParticipant`): every member's
 * marked set of every Memory in the scope, listed page by page as its value key's wrap — never its ciphertext — and that
 * wrap moved to the new scope key by core's re-wrap, staged under the rotation's re-key id beside the wrap it replaces.
 * The set itself stays byte-identical: its self-tag stays its marker's, nobody can attribute a mark (the listing carries
 * a row id, never a member's), and no rotator can rewrite another member's marks. A wrap that does not open under the
 * old key is staged as one nobody opens, so the set reads as an empty set that is nobody's own, as before: one member's
 * unreadable row must not stop every rotation of the scope.
 */
export async function stageItemMarksRekey(ctx: ScopeRekeyContext): Promise<void> {
  const { scopeId, rekeyId } = ctx
  let since: string | undefined
  for (;;) {
    const page = await withRefresh(() =>
      itemFavoritesRekeyApi().listScopeItemFavoritesRekey({ scopeId, since, limit: ITEM_MARKS_REKEY_BATCH }),
    )
    const marks = page.marks.map((m) => ({
      markId: m.markId,
      wrappedValueKey: ctx.rewrap(itemFavContext(scopeId, m.nodeId), m.wrappedValueKey),
      replacedValueKey: m.wrappedValueKey,
    }))
    if (marks.length) {
      await withRefresh(() =>
        itemFavoritesRekeyApi().stageScopeItemFavoritesRekey({
          scopeId, rekeyId, scopeItemFavoritesRekeyStage: { fromEpoch: ctx.fromEpoch, marks },
        }),
      )
    }
    if (!page.more || !page.cursor) return
    since = page.cursor
  }
}
