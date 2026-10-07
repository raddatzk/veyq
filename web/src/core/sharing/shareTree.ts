// The kind-independent tree half of a public share (ADR 0080 Decisions 2 and 9; cr-node-key-hierarchy 5b.2): the root's
// key opened from the share's own wrap, and every descendant's key and name opened top-down through the chain. Nothing
// here reads what a node IS — a walk opens a folder, a Memory or anything an application registers the same way, so a
// body of any kind (the platform's own folder body in 5b.4, the Memories body today) builds on it.
//
// What stays out: the WRITE_ONLY drop key (an uploader link carries no wrap and opens nothing here — ADR 0031 point 7;
// the application that registered the drop flow opens its drop public key) and anything attached to the nodes (the
// application's own guest read).
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { openContentKey, openName } from '../node/tree'
import { ShareUnavailableError, type PublicShareHead } from './resolvePublicShare'
import type { PublicShare, ShareNode } from '../../api/generated/sharing'

/** The share's second wrap of the root's key under `K_share` (ADR 0012): its purpose only, as every seal under `K_share`
 *  (ADR 0072 Decision 2) — `K_share` is unique to one share, so the key is the binding. Distinct from the tree's
 *  `content-key`. Sealed by the curator ([mintShare]), opened by the guest ([openShareRoot]). */
export const SHARE_KEY = { purpose: 'share-key' } as const

/** What a walk opened: each reachable node's key (the root's included), each descendant's name (the root has no
 *  entry — its title is the share's `sealedName`), and the served `ShareNode` of every node it opened, root included. */
export type SharedSubtree = {
  keys: Map<string, Uint8Array>
  names: Map<string, string>
  nodes: Map<string, ShareNode>
}

/**
 * Open the root of a READ or READ_WRITE share: its key unwrapped from the share's `wrap` with `K_share`, and its name
 * (already opened by the platform half from `sealedName`). Throws [ShareUnavailableError] `not_found` when the share
 * carries no wrap (a server that served a share without the wrap its role needs — or a WRITE_ONLY link, which never
 * holds one), `bad_fragment_key` when the wrap does not open under the fragment key.
 */
export function openShareRoot(head: Pick<PublicShareHead, 'kShare' | 'name'> & { share: Pick<PublicShare, 'wrap'> }): {
  key: Uint8Array
  name: string
} {
  if (!head.share.wrap) throw new ShareUnavailableError('not_found')
  try {
    return { key: v1.open(SHARE_KEY, head.kShare, fromB64std(head.share.wrap)), name: head.name }
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
}

/**
 * Walk a shared subtree top-down from the root's key — unwrapped with the fragment key from the share `wrap` — (ADR
 * 0012 recursive keys, ADR 0080 Decisions 2 and 9): each descendant's key unwraps under its parent's, and its name — its
 * directory entry, sealed under the same parent key — opens with it. The root has no entry here (its title is the
 * share's `sealedName`). A node whose key or name does not open is skipped, and so is everything below it: what hangs
 * under it simply won't render, its title won't show.
 */
export function walkSubtree(scopeId: string, nodes: ShareNode[], rootKey: Uint8Array): SharedSubtree {
  const keys = new Map<string, Uint8Array>()
  const names = new Map<string, string>()
  const opened = new Map<string, ShareNode>()
  const byParent = new Map<string, ShareNode[]>()
  let root: ShareNode | undefined
  for (const n of nodes) {
    if (n.parentId == null) root = n
    else byParent.set(n.parentId, [...(byParent.get(n.parentId) ?? []), n])
  }
  if (!root) return { keys, names, nodes: opened }
  keys.set(root.id, rootKey)
  opened.set(root.id, root)
  // BFS from the root, deriving each child's key under its parent's (already-known) key.
  const queue: string[] = [root.id]
  while (queue.length) {
    const parentId = queue.shift()!
    const parentKey = keys.get(parentId)
    if (!parentKey) continue
    for (const child of byParent.get(parentId) ?? []) {
      if (!child.wrappedContentKey || !child.encryptedName || child.placementVersion == null) continue
      try {
        // Both bound to (scope, this node, its epoch, the placement version served with them) — the node they are
        // listed for (ADR 0072, ADR 0080 Decision 3) — and both under the parent's key: the node's directory entry
        // (ADR 0080 Decision 2).
        const node = { scopeId, nodeId: child.id, epoch: child.epoch, placementVersion: child.placementVersion }
        const key = openContentKey(scopeId, parentKey, {
          id: child.id, epoch: child.epoch, placementVersion: child.placementVersion, wrappedContentKey: child.wrappedContentKey,
        })
        const name = openName(parentKey, node, child.encryptedName)
        keys.set(child.id, key)
        names.set(child.id, name)
        opened.set(child.id, child)
        queue.push(child.id)
      } catch {
        /* a node whose key or title won't open is skipped (what hangs under it then simply won't render) */
      }
    }
  }
  return { keys, names, nodes: opened }
}
