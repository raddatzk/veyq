// The web's side of the node tree's rules (`@veyq/core-js/tree`, ADR 0155): a level's order, the numbering of equal names,
// a name's sibling collision and the move's plan live in the platform's Kotlin (`src/mobile/core/…/tree`), one home for the
// web and the apps, held by contracts/platform/tree/v1-vectors.json. This file holds no rule: it keeps the two handles the
// rules read — the kinds' facts (the registry's `kindFacts()`) and one tree generation per node list (`treeFactsOf`) — and
// maps the ids the rules answer with back to the web's own nodes, so no Kotlin object reaches Vue.
import { markRaw } from 'vue'
import * as core from '@veyq/core-js/tree'
import type { TreeFactsHandle } from '@veyq/core-js/tree'
import type { Access, SourceNode, TreeSource } from '../access'
import { registry } from '../applications/registry'
import type { NodeKind } from './kind'
import type { TreeNode } from './tree'

export { compareByCreation } from '@veyq/core-js/tree'

/** What the tree's rules read of a node the web hands them (a `TreeNode` is one). */
type FactNode = Pick<TreeNode, 'id' | 'parentId' | 'kind' | 'name' | 'createdAt'> & { readonly shareIdentityId?: string | null }

// One generation per node list: a session replaces its list on every read (`treeStore`, a guest's re-resolve), and the
// handle goes with the list it was built from. The length guards a list grown in place.
const generations = new WeakMap<readonly FactNode[], { length: number; facts: TreeFactsHandle }>()

/** The tree's rules' handle over [nodes], built once per list. */
export function treeFactsOf(nodes: readonly FactNode[]): TreeFactsHandle {
  const held = generations.get(nodes)
  if (held && held.length === nodes.length) return held.facts
  const facts = markRaw(
    core.treeFacts(
      nodes.map((n) => ({ id: n.id, parentId: n.parentId ?? null, kind: n.kind, name: n.name, createdAt: n.createdAt, identity: !!n.shareIdentityId })),
    ),
  )
  generations.set(nodes, { length: nodes.length, facts })
  return facts
}

/** What the order and the numbering read: a node's id, name and creation instant (0 where none is served). */
type Entry = { readonly id: string; readonly name: string; readonly createdAt: number }

/** The list in the order it is drawn: `folders` (by name), then `others` (by name, all kinds mixed). Does not mutate. */
export function orderLevel<N extends Entry>(folders: readonly N[], others: readonly N[]): N[] {
  const byId = new Map<string, N>()
  for (const n of folders) byId.set(n.id, n)
  for (const n of others) byId.set(n.id, n)
  return core.orderLevel(folders, others).map((id) => byId.get(id)!)
}

/** The label of each of one parent's children by id (`Anna`, `Anna (2)`): in creation order, or a guest's in `id` order. */
export function displayNamesOf(siblings: readonly Entry[], order: 'creation' | 'id' = 'creation'): ReadonlyMap<string, string> {
  return core.displayNames(siblings, order === 'id')
}

/** The sibling under `parentId` (null: the scope root) whose token input `name` would take for a node of `kind`, or null;
 *  `self` (the node renamed or moved) never collides with itself. The server's `409 slug_taken` stays the race backstop. */
export function collidingSibling<N extends FactNode>(
  nodes: readonly N[], parentId: string | null, kind: NodeKind, name: string, self: string | null = null,
): N | null {
  const id = core.collidingSibling(registry.kindFacts(), treeFactsOf(nodes), parentId, kind, name, self)
  return id == null ? null : (nodes.find((n) => n.id === id) ?? null)
}

/** Why "Move here" cannot be chosen at a place (or, for `self`/`inside`, why a listed place cannot be opened). */
export type MoveBlock = 'self' | 'inside' | 'current' | 'identityFolder' | 'kind'

/** What the browser places the nodes for: a move, or a copy (uc.platform.copy-node), which may go where the nodes are. */
export type PlaceIntent = 'move' | 'copy'

type MovedNode = { id: string; kind: NodeKind; parentId: string | null }

/** Why `nodes` cannot be moved (or copied) to `at` (null: the member's scope root), or null where they can. */
export function moveHereBlock(tree: TreeSource, nodes: readonly MovedNode[], mode: Access['mode'], at: string | null, intent: PlaceIntent = 'move'): MoveBlock | null {
  return (core.moveHereBlock(registry.kindFacts(), tree.facts(), nodes, mode === 'guest', at, intent === 'copy') ?? null) as MoveBlock | null
}

/** Why several `nodes` cannot be moved to `at`, and the node that blocks it (null where all of them are there already). */
export function moveHereBlockOf<N extends MovedNode>(
  tree: TreeSource, nodes: readonly N[], mode: Access['mode'], at: string | null, intent: PlaceIntent = 'move',
): { block: MoveBlock; node: N | null } | null {
  const r = core.moveHereBlockOf(registry.kindFacts(), tree.facts(), nodes, mode === 'guest', at, intent === 'copy')
  if (!r) return null
  return { block: r.block as MoveBlock, node: r.node == null ? null : (nodes.find((n) => n.id === r.node) ?? null) }
}

/** Why one node of a selection is left out before anything is sent: it is already there, or a sibling there takes its name. */
export type MoveLeftOut<N> = { node: N; reason: 'current' } | { node: N; reason: 'nameTaken'; sibling: TreeNode }

/** The pre-check of a selection's move to `to`: what is sent, in the given order, and what is left out. */
export function planMove<N extends TreeNode>(tree: TreeSource<TreeNode>, nodes: readonly N[], to: string | null): { send: N[]; leftOut: MoveLeftOut<N>[] } {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const plan = core.planMove(registry.kindFacts(), tree.facts(), nodes, to)
  return {
    send: plan.send.map((id) => byId.get(id)!),
    leftOut: plan.leftOut.map((l): MoveLeftOut<N> => {
      const node = byId.get(l.node)!
      const sibling = l.sibling === null || l.sibling === undefined ? null : tree.node(l.sibling)
      return l.reason === 'nameTaken' && sibling ? { node, reason: 'nameTaken', sibling } : { node, reason: 'current' }
    }),
  }
}

/** The top of what the session may browse: a guest's link root, a member's scope root (null). */
export function moveTop(tree: TreeSource): string | null {
  return tree.rootId
}

/** Where the browser opens: the place the nodes are in (every one the same), else the top. */
export function moveStart(tree: TreeSource, nodes: readonly MovedNode[]): string | null {
  return core.moveStart(tree.facts(), tree.rootId, nodes) ?? null
}

/** The trail from the top down to `at` (both included): the breadcrumb's places, as ids (null: the scope root). */
export function moveTrail(tree: TreeSource, at: string | null): (string | null)[] {
  return core.moveTrail(tree.facts(), tree.rootId, at).map((id) => id ?? null)
}

/** One place the browser lists: a child of the place on screen; `blocked` (`self`/`inside`) says it cannot be opened. */
export interface MovePlace<N extends SourceNode = SourceNode> {
  node: N
  blocked: 'self' | 'inside' | null
}

/** The places listed at `at`, in the order served (the view sorts them as the level does). */
export function movePlaces<N extends SourceNode>(tree: TreeSource<N>, nodes: readonly MovedNode[], mode: Access['mode'], at: string | null): MovePlace<N>[] {
  return core.movePlaces(registry.kindFacts(), tree.facts(), nodes, mode === 'guest', at).map((p) => ({
    node: tree.node(p.node)!,
    blocked: (p.blocked ?? null) as 'self' | 'inside' | null,
  }))
}

/** Whether moving `node` ends a drop-box guest's folder (ADR 0080 decision 11): its identity link goes with the move. */
export function endsIdentity(tree: TreeSource, node: Pick<TreeNode, 'id'>): boolean {
  return core.endsIdentity(tree.facts(), node.id)
}
