// The active scope's resolved Memory covers, shared by every surface that shows one (cr-0072 + this CR's
// sidebar rail). Beside `treeStore.ts` and owned by the same lifecycle: `activeSpace.enterScope` resets the
// tree + favorites so no stale per-scope state bleeds across a switch, and the cover map joins that reset.
//
// **Why a store rather than a second copy of the browse grid's loop.** Wiring the rail meant a second caller of
// `loadNodeCover`, and a per-surface cache decrypts the same thumbnail once per surface. Worse, it would put
// key handling in an always-mounted component: `nodeContentKey` peels the epoch chain (ADR 0024 — a node opens
// under its OWN epoch's `K_SC`), and that chain has no business in the rail's scope. Here it is in one place,
// and the rail imports no key API at all.
//
// **Key material never enters Vue reactivity** (`activeSpace.ts`'s standing trap): `nodeContentKey` is resolved
// into a local, used, and dropped. What is reactive is the URL map and the crops — never a byte of `K_mem`.
import { computed, reactive, type Ref } from 'vue'
import { activeSpace, activeEpochKeys } from '../../app/activeSpace'
import { treeStore } from './treeStore'
import { nodeContentKey } from './tree'
import { loadNodeCover, type CoverCrop, type LoadedCover } from './memoryCover'
import { useObjectUrls } from '../../shared/state/useObjectUrls'

// The one owner of the decrypted covers' object-URL lifecycle. It has no component to unmount with, which is
// why `useObjectUrls` guards its hook — `resetCovers` is this store's unmount.
const urls = useObjectUrls()
const crops = reactive(new Map<string, CoverCrop>())
/**
 * nodeId → the **`wrappedMeta` the held entry was resolved from**, which is the cache key and not merely a
 * record of it. A per-visit cache used to hide this: the browse grid unmounts when a Memory opens, so changing
 * a cover in the detail header and going back re-resolved by accident. A module-level store outlives that, so a
 * changed cover would otherwise leave a stale thumbnail on the card it came from *and* in the rail. Keying on
 * the sealed meta dissolves it at the source: `patchNodeMeta` already writes the new blob into the tree, so no
 * new signal is needed and no future call site can forget to send one. It over-invalidates — a date edit
 * re-decrypts the cover — which is rare and costs one thumbnail.
 *
 * A node absent from this map is **un-tried**: a node whose `K_mem` is not openable yet (still unlocking) is
 * deliberately left out so a later pass retries it, rather than being remembered as "has no cover".
 */
const resolvedFrom = new Map<string, string | null>()

/** nodeId → the decrypted cover + its crop, for every surface that renders one. Read-only to consumers. */
export const covers: Ref<Map<string, LoadedCover>> = computed(() => {
  const m = new Map<string, LoadedCover>()
  for (const [id, url] of urls.urls.value) {
    const crop = crops.get(id)
    if (crop) m.set(id, { url, crop })
  }
  return m
}) as Ref<Map<string, LoadedCover>>

/**
 * Resolve the covers for `nodeIds` that are missing or stale, best-effort and in parallel. Safe to call on
 * every render pass: a node already resolved from its current `wrappedMeta` is skipped.
 */
export function ensureCovers(nodeIds: readonly string[]): void {
  const scopeId = activeSpace.scopeId
  const epochs = activeEpochKeys()
  if (!scopeId || !epochs) return
  for (const id of nodeIds) {
    const node = treeStore.nodes.find((n) => n.id === id)
    if (!node) continue
    if (resolvedFrom.has(id) && resolvedFrom.get(id) === node.wrappedMeta) continue // current, nothing to do
    const nodeKey = nodeContentKey(scopeId, epochs, treeStore.nodes, id)
    if (!nodeKey) continue // K_mem not openable yet — leave un-tried so a later pass retries
    const meta = node.wrappedMeta
    resolvedFrom.set(id, meta)
    void loadNodeCover(scopeId, nodeKey, node).then((cover) => {
      if (resolvedFrom.get(id) !== meta) return // a newer meta won the race; its own resolve owns the entry
      if (!cover) {
        urls.drop(id) // a cover that was REMOVED must stop showing, not linger from the previous resolve
        crops.delete(id)
        return
      }
      crops.set(id, cover.crop)
      urls.set(id, cover.blob)
    })
  }
}

/** Drop every resolved cover, revoking each decrypted URL — a scope switch, a rotation, or sign-out. */
export function resetCovers(): void {
  urls.clear()
  crops.clear()
  resolvedFrom.clear()
}
