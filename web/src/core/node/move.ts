// Moving a node elsewhere in the tree (inbox #358): what core's `MoveNodeDialog` offers as destinations, the one door it
// moves through for whoever looks, and the refusals it explains by name. Core's, like the placement write itself
// (`core/node/tree.ts` `moveNode`, `core/sharing/guestTree.ts` `placeShareNodeAsGuest`): a move carries no application
// semantics — the node's own content key is unchanged, only its wrap, name and slug token are re-sealed under the
// destination's key at the next placement version (ADR 0080 decision 3), so everything below it keeps opening.
//
// **Destinations are a hint; the server decides.** The picker mirrors the server's rules so it offers only what would be
// taken: the kind's `placement` (`registry.placementAllows`, the backend's `NodeKindRules`), never the node itself or
// anything below it (a cycle), never a guest's identity folder or anything inside one (nothing is placed INTO one, ADR
// 0080 decision 11), and for a guest only the share's subtree and only a `FOLDER` (core's `FolderGuestNodeRules`: the one
// container that lets a guest's child in). Where the two disagree the server's refusal is explained by its code
// ({@link moveRefusalOf}).
//
// **Who looks decides the door** ({@link nodeMover}): a member moves through `moveNode` over the unlocked scope (any
// destination of the scope, the root included), a `READ_WRITE` guest through `placeShareNodeAsGuest` after the page's
// one naming flow (`access.requestGuest`) — never to the scope root, which no guest holds a key of. Any other session has
// no door, so no move control can fall through to a call.
import type { Access, GuestAccess, MemberAccess, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { moveNode, PlacementConflictError } from './tree'
import { loadTree, treeStore } from './treeStore'
import { FOLDER_KIND, type NodeKind } from './kind'
import { registry } from '../applications/registry'
import { guestTreeContext, GuestTreeError, placeShareNodeAsGuest } from '../sharing/guestTree'
import { decodeHttpError } from '../../shared/httpError'
import { ShareRole } from '../../api/generated/sharing'

/** Why a destination cannot be picked; null: it can. */
export type MoveBlock = 'self' | 'inside' | 'current' | 'identityFolder' | 'kind'

/** One row of the destination picker: a node (or the member's scope root, `id` null) at its depth in the tree. */
export interface MoveDestination {
  id: string | null
  kind: NodeKind | null
  name: string
  depth: number
  blocked: MoveBlock | null
}

type MovedNode = { id: string; kind: NodeKind; parentId: string | null }

/**
 * The picker's rows for moving `node`, depth-first from the view's root (`tree.rootId`; the member's scope root as a row
 * named `rootLabel`), siblings by name. A row is listed when it could take SOME node — a `FOLDER` — or this one (the
 * kind's `placement`, e.g. a Memory for a chapter); what it holds is listed below it only when it is listed itself, so a
 * drop box (no placed child) and its guests' folders are not offered at all. Every row says whether it can be picked.
 */
export function moveDestinations(tree: TreeSource, node: MovedNode, mode: Access['mode'], rootLabel = ''): MoveDestination[] {
  const out: MoveDestination[] = []
  const inside = (id: string): boolean => {
    for (let n = tree.node(id), steps = 0; n && steps < 10_000; n = n.parentId ? tree.node(n.parentId) : null, steps++) {
      if (n.id === node.id) return true
    }
    return false
  }
  const holds = (kind: NodeKind | null) => kind === FOLDER_KIND || registry.placementAllows(node.kind, kind)
  const blockOf = (id: string | null, kind: NodeKind | null): MoveBlock | null => {
    if (id === node.id) return 'self'
    if (id !== null && inside(id)) return 'inside'
    if (id !== null && tree.inIdentityFolder(id)) return 'identityFolder'
    if (id === (node.parentId ?? null)) return 'current'
    if (!registry.placementAllows(node.kind, kind)) return 'kind'
    if (mode === 'guest' && kind !== FOLDER_KIND) return 'kind' // only a folder lets a guest's child in
    return null
  }
  const byName = (a: { id: string }, b: { id: string }) => tree.nameOf(a.id).localeCompare(tree.nameOf(b.id))
  const visit = (id: string | null, depth: number, seen: Set<string>) => {
    for (const child of [...tree.children(id)].sort(byName)) {
      if (seen.has(child.id) || !holds(child.kind)) continue
      seen.add(child.id)
      out.push({ id: child.id, kind: child.kind, name: tree.nameOf(child.id), depth, blocked: blockOf(child.id, child.kind) })
      visit(child.id, depth + 1, seen)
    }
  }
  if (mode === 'member' && tree.rootId === null) {
    out.push({ id: null, kind: null, name: rootLabel, depth: 0, blocked: blockOf(null, null) })
    visit(null, 1, new Set())
  } else if (tree.rootId !== null) {
    // A guest's view: the share root and its subtree, nothing above it (the scope root least of all).
    const root = tree.node(tree.rootId)
    if (!root) return out
    out.push({ id: root.id, kind: root.kind, name: tree.nameOf(root.id), depth: 0, blocked: blockOf(root.id, root.kind) })
    visit(root.id, 1, new Set([root.id]))
  }
  return out
}

/** The door a move goes through for the session on screen ({@link nodeMover}). */
export interface NodeMover {
  /** Move `nodeId` under `to` (null: the scope root — a member's only), then re-read the tree. Throws what the
   *  placement throws ({@link moveRefusalOf} names it); {@link MoveDeclined} when a guest declined to name itself. */
  move(nodeId: string, to: string | null): Promise<void>
  /** Re-read the tree (after a lost race, so the next attempt builds on the node as it now is). */
  refresh(): Promise<void>
}

/** A guest declined the naming the move needs: nothing was sent, nothing is said. */
export class MoveDeclined extends Error {
  constructor() {
    super('move_declined')
    this.name = 'MoveDeclined'
  }
}

/** The mover for whoever `access` is, read on every call (a re-resolve replaces a guest's head): a member's over the
 *  unlocked scope, a `READ_WRITE` guest's over its link; null for any other session — it moves nothing. */
export function nodeMover(access: () => Access): NodeMover | null {
  const a = access()
  if (a.mode === 'member') return memberMover(() => access() as MemberAccess)
  if (a.mode === 'guest' && a.role === ShareRole.ReadWrite) return guestMover(() => access() as GuestAccess)
  return null
}

function memberMover(access: () => MemberAccess): NodeMover {
  return {
    async move(nodeId, to) {
      const { scopeId, epochs } = access()
      if (!scopeId || !epochs) throw new Error('scope_locked')
      // The loaded tree: the node's key, the destination's, its placement version (re-read once on a lost race).
      await moveNode(scopeId, epochs, treeStore.nodes, nodeId, to)
      await loadTree()
    },
    refresh: loadTree,
  }
}

function guestMover(access: () => GuestAccess): NodeMover {
  return {
    async move(nodeId, to) {
      if (to === null) throw new GuestTreeError('illegal_structure') // no guest holds the scope root's key
      const a = access()
      const guest = await a.requestGuest()
      if (!guest) throw new MoveDeclined()
      const ctx = guestTreeContext(a.head, { contributorToken: guest.contributorToken, fragmentKey: a.fragmentKey, pwToken: a.pwToken })
      await placeShareNodeAsGuest(ctx, { nodeId, newParentId: to })
      await a.reload() // the node lists at its new place, under its new wrap
    },
    refresh: () => access().reload(),
  }
}

/** The refusals a move explains by name; `failed` is anything else (a network failure, an unexpected code). */
export type MoveRefusalCode =
  | 'slug_taken' | 'illegal_structure' | 'illegal_scope' | 'stale_placement' | 'stale_epoch'
  | 'share_not_contributable' | 'gone' | 'rate_limited' | 'scope_deleting' | 'failed'

/** A failed move as the code the dialog explains (`node.move.refused.<code>`). A placement that kept losing races
 *  ({@link PlacementConflictError}, after the bounded re-read) is `stale_placement`. */
export async function moveRefusalOf(e: unknown): Promise<MoveRefusalCode> {
  if (e instanceof PlacementConflictError) return 'stale_placement'
  const code = e instanceof GuestTreeError ? e.code : (await decodeHttpError(e)).code
  switch (code) {
    case 'slug_taken':
    case 'illegal_structure':
    case 'illegal_scope':
    case 'stale_placement':
    case 'stale_epoch':
    case 'share_not_contributable':
    case 'rate_limited':
    case 'scope_deleting':
      return code
    case 'node_not_found':
    case 'share_not_found':
    case 'share_password_incorrect':
      return 'gone'
    default:
      return 'failed'
  }
}

/** Whether moving `node` ends a drop-box guest's folder (ADR 0080 decision 11): its identity link goes with the move,
 *  the guest can add nothing to it any more, and no move brings it back into the drop box (no undo). */
export function endsIdentity(node: Pick<TreeNode, 'shareIdentityId'>): boolean {
  return !!node.shareIdentityId
}
