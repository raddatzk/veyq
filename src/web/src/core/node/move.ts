// Moving a node elsewhere in the tree (inbox #358): the one door a move goes through for whoever looks, and the refusals it
// explains by name. Core's, like the placement write itself (`core/node/tree.ts` `moveNode`, `core/sharing/guestTree.ts`
// `placeShareNodeAsGuest`): a move carries no application semantics — the node's own content key is unchanged, only its
// wrap, name and slug token are re-sealed under the destination's key at the next placement revision (ADR 0080 decision 3),
// so everything below it keeps opening.
//
// **Where a node may go is the platform's** (`treeRules.ts` over `@veyq/core-js/tree`, the apps' `MovePlan` too): where the
// browser opens, its trail, the places it lists, whether "Move here" may be chosen, and a selection's plan before anything is
// sent. They are a hint; the server decides, and where the two disagree its refusal is explained by its code
// ({@link moveRefusalOf}).
//
// **Who looks decides the door** ({@link nodeMover}): a member moves through `moveNode` over the unlocked scope (any
// destination of the scope, the root included), a `READ_WRITE` guest through `placeShareNodeAsGuest` after the page's
// one naming flow (`access.requestGuest`) — never to the scope root, which no guest holds a key of. Any other session has
// no door, so no move control can fall through to a call.
//
// **Several at once** (inbox #421, owner decisions 2026-10-14): a selection's nodes that pass the plan are written ONE NODE
// AFTER THE OTHER, each the single node's own placement write - no batch request - and the tree is re-read ONCE at the end
// ({@link NodeMover.moveMany}). A refusal part-way stops nothing: what moved stays moved, and the outcome says per node how
// it ended.
import type { Access, GuestAccess, MemberAccess } from '../access'
import { moveNode, PlacementConflictError } from './tree'
import { loadTree, treeStore } from './treeStore'
import { guestTreeContext, GuestTreeError, placeShareNodeAsGuest } from '../sharing/guestTree'
import { decodeHttpError } from '../../shared/httpError'
import { isBelowNodeKeyFloor } from '../../shared/rekeyRetry'
import { ShareRole } from '../../api/generated/sharing'

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
      // The loaded tree: the node's key, the destination's, its placement revision (re-read once on a lost race).
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
  | 'slug_taken' | 'illegal_structure' | 'illegal_scope' | 'stale_placement' | 'stale_epoch' | 'node_rekey_pending'
  | 'share_not_contributable' | 'gone' | 'rate_limited' | 'scope_deleting' | 'failed'

/** A failed move as the code the dialog explains (`node.move.refused.<code>`). A placement that kept losing races
 *  ({@link PlacementConflictError}, after the bounded re-read) is `stale_placement`. A node still not re-keyed after the
 *  waits (`node_rekey_pending`, or the device floor's own refusal) is `node_rekey_pending` — the space's new lock is
 *  still going in (ADR 0109 point 6). */
export async function moveRefusalOf(e: unknown): Promise<MoveRefusalCode> {
  if (e instanceof PlacementConflictError) return 'stale_placement'
  if (isBelowNodeKeyFloor(e)) return 'node_rekey_pending'
  const code = e instanceof GuestTreeError ? e.code : (await decodeHttpError(e)).code
  switch (code) {
    case 'node_rekey_pending':
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
