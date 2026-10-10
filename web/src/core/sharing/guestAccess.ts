// The share guest's two inputs of every view (inbox #401 S2; `core/access.ts` says what they are): its `GuestAccess` and
// its `TreeSource` over the opened subtree — the guest's counterparts of `memberAccess` / `memberTreeSource`, built by the
// share page (`views/ShareView.vue` the access, `FolderShareBody.vue` the tree) after its gates, so a level or a detail
// renders for a guest exactly as for a member, with what the capability functions take away.
//
// **The tree is what the walk opened, and nothing above it.** Every node, key and name comes from `openGuestTreeParts`
// (the share's `wrap` under `K_share`, then the chain, `shareTree.walkSubtree`): a node whose key or name did not open is
// not served, nor anything below it, and nothing above the share root exists here — `node()` of an id outside answers
// null, `children(null)` (the member's scope root) is empty, and the root has no parent (`parentId` null), so no view
// navigates above it.
//
// **A served `ShareNode` as a `TreeNode`** (the views are `TreeNode`-typed): its name is the chain's (the root's the
// share's own name), `wrappedMeta` is served on `ShareNode` (the kind's sealed payload — a Memory's date, place and
// cover), `metaVersion` is not (a guest writes no meta: 0), and neither is a creation time — `createdAt` is 0 for every
// node, so `compareByCreation` falls to its node-id tie-break and a guest numbers equal-named siblings by id
// (`compareById`, `location.ts`; the numbers a guest sees may differ from a member's, documented there).
//
// **Links keep `#k=`.** `linkTo` is the route `?node=<id>` (no query at the root) with the CURRENT route's hash, which
// carries the share key the shell re-resolves with — a push without it leaves the next reload keyless (web/CLAUDE.md,
// the breadcrumb trap).
import type { RouteLocationRaw } from 'vue-router'
import type { GuestAccess, TreeSource } from '../access'
import type { TreeNode } from '../node/tree'
import { compareById, displayNamesOf } from '../node/location'
import type { ShareNode } from '../../api/generated/sharing'
import type { PublicShareHead } from './resolvePublicShare'
import type { GuestIdentity } from './guestIdentity'
import { openGuestTreeParts, type GuestTreeParts } from './guestTree'

/** The guest's tree source: core's `TreeSource` over `TreeNode`s, plus what the share page itself reads — the opened
 *  parts (served rows, keys, names: key material) and every opened node as a `TreeNode`. */
export interface GuestTreeSource extends TreeSource<TreeNode> {
  readonly rootId: string
  readonly parts: GuestTreeParts
  /** Every node the walk opened, the root first, as `TreeNode`s (the views' type). */
  readonly nodes: readonly TreeNode[]
}

/** A served row as a `TreeNode` (see the header for each field the share does not serve). */
function asTreeNode(n: ShareNode, rootId: string, name: string): TreeNode {
  return {
    id: n.id,
    parentId: n.id === rootId ? null : (n.parentId ?? null),
    kind: n.kind,
    orderIndex: n.orderIndex ?? 0,
    epoch: n.epoch,
    name,
    wrappedContentKey: n.wrappedContentKey ?? '',
    wrappedMeta: n.wrappedMeta ?? null,
    metaVersion: 0,
    placementVersion: n.placementVersion ?? 0,
    createdAt: 0,
    sealedDropPrivateKey: n.sealedDropPrivateKey ?? null,
    shareIdentityId: n.shareIdentityId ?? null,
    wrappedIdentityKey: n.wrappedIdentityKey ?? null,
  }
}

/**
 * The guest's `TreeSource` over the link `head` resolved: opens the subtree (`openGuestTreeParts`; throws
 * `ShareUnavailableError` when the fragment key does not open it — the page's unavailable state) and serves it as the
 * header says. `route` is read on every `linkTo`, so a link always carries the hash the page is at.
 */
export function guestTreeSource(head: PublicShareHead, route: { readonly hash: string }): GuestTreeSource {
  const parts = openGuestTreeParts(head)
  const { rootId } = parts
  // The opened nodes only: the root, and every descendant whose name opened (a row without one is not served).
  const opened = [...parts.nodes.values()].filter((n) => n.id === rootId || parts.names.has(n.id))
  const rawName = (id: string) => (id === rootId ? head.name : (parts.names.get(id) ?? ''))
  const nodes: TreeNode[] = opened.map((n) => asTreeNode(n, rootId, rawName(n.id)))
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const byParent = new Map<string, TreeNode[]>()
  for (const n of nodes) {
    if (n.parentId === null) continue
    const group = byParent.get(n.parentId)
    if (group) group.push(n)
    else byParent.set(n.parentId, [n])
  }
  // A repeated name among siblings numbered in node-id order (core's `displayNamesOf`, inbox #393).
  const labels = new Map<string, string>()
  for (const group of byParent.values()) {
    for (const [id, label] of displayNamesOf(group, (n) => n.name, compareById)) labels.set(id, label)
  }

  return {
    rootId,
    parts,
    nodes,
    node: (id) => byId.get(id) ?? null,
    children: (id) => (id === null ? [] : (byParent.get(id) ?? [])),
    keyOf: (id) => (byId.has(id) ? (parts.keys.get(id) ?? null) : null),
    nameOf: (id) => (id === rootId ? head.name : (labels.get(id) ?? byId.get(id)?.name ?? '')),
    inIdentityFolder(id) {
      // Up the served parents to the root, the root included (bounded: a served cycle stops).
      for (let n = id ? byId.get(id) : undefined, steps = 0; n && steps <= nodes.length; n = n.parentId ? byId.get(n.parentId) : undefined, steps++) {
        if (n.shareIdentityId) return true
      }
      return false
    },
    linkTo: (id): RouteLocationRaw => ({ query: id === null || id === rootId ? {} : { node: id }, hash: route.hash }),
  }
}

/** The guest's `Access` for the link `head` resolved behind the page's gates; `requestGuest` is the page's one naming
 *  flow (the shell's dialog, `views/ShareView.vue`). */
export function guestAccess(fields: {
  head: PublicShareHead
  pwToken?: string
  requestGuest: () => Promise<GuestIdentity | null>
}): GuestAccess {
  return {
    mode: 'guest',
    shareId: fields.head.shareId,
    role: fields.head.role,
    pwToken: fields.pwToken,
    head: fields.head,
    requestGuest: fields.requestGuest,
  }
}
