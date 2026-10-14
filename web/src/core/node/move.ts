// Moving a node elsewhere in the tree (inbox #358): where core's move browser (`MoveBrowser.vue`, inside `MoveNodeDialog`) lets
// a node go, the one door it moves through for whoever looks, and the refusals it explains by name. Core's, like the
// placement write itself (`core/node/tree.ts` `moveNode`, `core/sharing/guestTree.ts` `placeShareNodeAsGuest`): a move
// carries no application semantics — the node's own content key is unchanged, only its wrap, name and slug token are
// re-sealed under the destination's key at the next placement version (ADR 0080 decision 3), so everything below it keeps
// opening.
//
// **The browser (owner decision 2026-10-14, replacing #358's list of every destination).** It opens at the moved nodes'
// place ({@link moveStart}), shows one place at a time with a trail up to the top of what the session reads
// ({@link moveTrail}: a member's scope root, a guest's link root), lists only the children a user may go into or move into
// ({@link movePlaces}), and says per place whether "Move here" may be chosen ({@link moveHereBlock}). Every function takes
// the moved nodes as a LIST: the selection of inbox #421 moves through the same browser, and a place is offered only where
// it takes every one of them.
//
// **The places are a hint; the server decides.** The browser mirrors the server's rules so it offers only what would be
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
//
// **Several at once** (inbox #421, owner decisions 2026-10-14): a selection moves through the same browser and the same
// door. A place is blocked where it blocks ANY of the nodes, named by that node ({@link moveHereBlockOf}); before anything
// is sent each node is checked as one is ({@link planMove}: already there, or a name that folds to a sibling's there) and
// a node that fails is left out and named; the rest is written ONE NODE AFTER THE OTHER, each the single node's own
// placement write - no batch request - and the tree is re-read ONCE at the end ({@link NodeMover.moveMany}). A refusal
// part-way stops nothing: what moved stays moved, and the outcome says per node how it ended.
import type { Access, GuestAccess, MemberAccess, SourceNode, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { moveNode, PlacementConflictError } from './tree'
import { collidingSibling } from './siblingCollision'
import { loadTree, treeStore } from './treeStore'
import { FOLDER_KIND, type NodeKind } from './kind'
import { registry } from '../applications/registry'
import { guestTreeContext, GuestTreeError, placeShareNodeAsGuest } from '../sharing/guestTree'
import { decodeHttpError } from '../../shared/httpError'
import { ShareRole } from '../../api/generated/sharing'

/** Why "Move here" cannot be chosen at a place (or, for `self`/`inside`, why a listed place cannot be opened); null: it can. */
export type MoveBlock = 'self' | 'inside' | 'current' | 'identityFolder' | 'kind'

type MovedNode = { id: string; kind: NodeKind; parentId: string | null }

/** Whether `id` is one of `nodes` or lies below one (bounded: a served parent cycle stops instead of spinning). */
function withinMoved(tree: TreeSource, nodes: readonly MovedNode[], id: string): 'self' | 'inside' | null {
  if (nodes.some((n) => n.id === id)) return 'self'
  const steps = new Set<string>()
  for (let n = tree.node(id); n && !steps.has(n.id); n = n.parentId ? tree.node(n.parentId) : null) {
    steps.add(n.id)
    if (nodes.some((m) => m.id === n!.id)) return 'inside'
  }
  return null
}

/** What the browser places the nodes for: a move, or a copy (uc.platform.copy-node) - which may go where the nodes are
 *  now (the copy is named `Name (2)` there), and never into itself or anything inside it, like a move. */
export type PlaceIntent = 'move' | 'copy'

/** Why `nodes` cannot be moved (or copied) to the place `at` (null: the member's scope root), or null where they can. */
export function moveHereBlock(
  tree: TreeSource, nodes: readonly MovedNode[], mode: Access['mode'], at: string | null, intent: PlaceIntent = 'move',
): MoveBlock | null {
  if (at !== null) {
    const within = withinMoved(tree, nodes, at)
    if (within) return within
    if (tree.inIdentityFolder(at)) return 'identityFolder'
  }
  if (intent === 'move' && nodes.length > 0 && nodes.every((n) => (n.parentId ?? null) === at)) return 'current'
  const kind = at === null ? null : (tree.node(at)?.kind ?? null)
  if (!nodes.every((n) => registry.placementAllows(n.kind, kind))) return 'kind'
  if (mode === 'guest' && kind !== FOLDER_KIND) return 'kind' // only a folder lets a guest's child in
  return null
}

/** Why several `nodes` cannot be moved to `at`, and the node that blocks it (null where all of them are already there);
 *  null where the place takes them. A node that is already there blocks nothing: {@link planMove} leaves it out. */
export function moveHereBlockOf<N extends MovedNode>(
  tree: TreeSource, nodes: readonly N[], mode: Access['mode'], at: string | null, intent: PlaceIntent = 'move',
): { block: MoveBlock; node: N | null } | null {
  if (intent === 'move' && nodes.length > 0 && nodes.every((n) => (n.parentId ?? null) === at)) return { block: 'current', node: null }
  for (const node of nodes) {
    const block = moveHereBlock(tree, [node], mode, at, intent)
    if (block && block !== 'current') return { block, node }
  }
  return null
}

/** Why one node of a selection is left out before anything is sent: it is already there, or a sibling there takes its
 *  name (`sibling`, folded by each node's own kind - core's `collidingSibling`). */
export type MoveLeftOut<N> = { node: N; reason: 'current' } | { node: N; reason: 'nameTaken'; sibling: TreeNode }

/** The pre-check of a selection's move to `to` (see the header): what is sent, in the given order, and what is left out. */
export function planMove<N extends TreeNode>(tree: TreeSource<TreeNode>, nodes: readonly N[], to: string | null): { send: N[]; leftOut: MoveLeftOut<N>[] } {
  const send: N[] = []
  const leftOut: MoveLeftOut<N>[] = []
  for (const node of nodes) {
    if ((node.parentId ?? null) === to) {
      leftOut.push({ node, reason: 'current' })
      continue
    }
    const sibling = collidingSibling(tree.children(to), to, node.kind, node.name, node.id)
    if (sibling) leftOut.push({ node, reason: 'nameTaken', sibling })
    else send.push(node)
  }
  return { send, leftOut }
}

/** The top of what the session may browse: a guest's link root, a member's scope root (null). */
export function moveTop(tree: TreeSource): string | null {
  return tree.rootId
}

/** Where the browser opens: the place the nodes are in (every one the same), else the top. A place this session does not
 *  read (a guest's node whose parent lies above the link) is the top too. */
export function moveStart(tree: TreeSource, nodes: readonly MovedNode[]): string | null {
  const parents = new Set(nodes.map((n) => n.parentId ?? null))
  const [parent] = parents
  return parents.size === 1 && parent && tree.node(parent) ? parent : moveTop(tree)
}

/** The trail from the top down to `at` (both included): the breadcrumb's places, as ids (null: the scope root). A chain
 *  that does not reach the top (bounded against a served cycle) still starts there. */
export function moveTrail(tree: TreeSource, at: string | null): (string | null)[] {
  const top = moveTop(tree)
  const trail: (string | null)[] = []
  const seen = new Set<string>()
  for (let id = at; id !== null && id !== top && !seen.has(id); id = tree.node(id)?.parentId ?? null) {
    seen.add(id)
    trail.unshift(id)
  }
  trail.unshift(top)
  return trail
}

/** One place the browser lists: a child of the place on screen; `blocked` (`self`/`inside`) says it cannot be opened. */
export interface MovePlace<N extends SourceNode = SourceNode> {
  node: N
  blocked: 'self' | 'inside' | null
}

/**
 * The places listed at `at`: the children a user may go into or move into, in the order served (the view sorts them as the
 * level does). A `FOLDER` is always one (it is the way down, and takes every kind but a chapter-like one), for a member
 * also a node of a kind whose placement takes every moved node (a Memory for a chapter); nothing else — no file, no drop
 * box, no Memory that cannot take them, no guest's identity folder. The moved nodes themselves (and anything below them)
 * are listed blocked: they cannot be opened.
 */
export function movePlaces<N extends SourceNode>(tree: TreeSource<N>, nodes: readonly MovedNode[], mode: Access['mode'], at: string | null): MovePlace<N>[] {
  const takes = (kind: NodeKind) =>
    kind === FOLDER_KIND || (mode === 'member' && nodes.length > 0 && nodes.every((n) => registry.placementAllows(n.kind, kind)))
  return tree
    .children(at)
    .filter((child) => takes(child.kind) && !tree.inIdentityFolder(child.id))
    .map((child) => ({ node: child, blocked: withinMoved(tree, nodes, child.id) }))
}

/** The door a move goes through for the session on screen ({@link nodeMover}). */
export interface NodeMover {
  /** Move `nodeId` under `to` (null: the scope root — a member's only), then re-read the tree. Throws what the
   *  placement throws ({@link moveRefusalOf} names it); {@link MoveDeclined} when a guest declined to name itself. */
  move(nodeId: string, to: string | null): Promise<void>
  /** Move each of `moves` in order, each the one placement write {@link move} sends, and re-read the tree ONCE after the
   *  last (inbox #421: N writes, not a batch). A refusal does not stop the rest: resolves every move's outcome (`error`
   *  null: it moved). Rejects {@link MoveDeclined} when a guest declined to name itself (nothing sent). */
  moveMany(moves: readonly { nodeId: string; to: string | null }[]): Promise<MoveOutcome[]>
  /** Re-read the tree (after a lost race, so the next attempt builds on the node as it now is). */
  refresh(): Promise<void>
}

/** How one move of several ended: `error` null where it moved, else what the placement threw ({@link moveRefusalOf}). */
export interface MoveOutcome {
  nodeId: string
  error: unknown
}

/** Send `moves` one after the other through `place`, collecting each outcome; nothing stops the rest. */
async function eachMove(moves: readonly { nodeId: string; to: string | null }[], place: (nodeId: string, to: string | null) => Promise<void>): Promise<MoveOutcome[]> {
  const outcomes: MoveOutcome[] = []
  for (const { nodeId, to } of moves) {
    try {
      await place(nodeId, to)
      outcomes.push({ nodeId, error: null })
    } catch (error) {
      outcomes.push({ nodeId, error })
    }
  }
  return outcomes
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
    async moveMany(moves) {
      const { scopeId, epochs } = access()
      if (!scopeId || !epochs) throw new Error('scope_locked')
      // Each against the tree loaded before the first: the nodes of one level are siblings, so a move changes nothing another
      // builds on (a lost race is still re-read once inside `moveNode`).
      try {
        return await eachMove(moves, (nodeId, to) => moveNode(scopeId, epochs, treeStore.nodes, nodeId, to))
      } finally {
        await loadTree()
      }
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
    async moveMany(moves) {
      const a = access()
      const guest = await a.requestGuest() // named once, for all of them
      if (!guest) throw new MoveDeclined()
      const ctx = guestTreeContext(a.head, { contributorToken: guest.contributorToken, fragmentKey: a.fragmentKey, pwToken: a.pwToken })
      try {
        return await eachMove(moves, async (nodeId, to) => {
          if (to === null) throw new GuestTreeError('illegal_structure') // no guest holds the scope root's key
          await placeShareNodeAsGuest(ctx, { nodeId, newParentId: to })
        })
      } finally {
        await a.reload() // once: every moved node lists at its new place, under its new wrap
      }
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
