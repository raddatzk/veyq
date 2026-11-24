// The participants' write and its merge on a conflict (uc.memory.participants A2): what the server holds now (`stored`)
// united with what the editor wants (`wanted`), minus the ids the editor removed (`removed` = opened-with minus wanted).
// A peer's addition survives; the editor's own removal wins for the id it removed; the order is stored first, then the
// editor's new ones; capped at `MAX_PARTICIPANTS`. The rule is Memories' Kotlin `Participants.merge` (ADR 0155 decision 7.3, § Memories' move),
// drawn through `@veyq/core-js/memories` and held by `contracts/memories/v1-vectors.json`: a change to it is made there,
// never here. And the participants EDITOR's save, which runs it on a conflict (uc.memory.participants, ADR 0077): a file of
// its own, off the eager `memoryParticipants.ts`, so Memories' module loads with a Memory's page, never for a visitor who
// opens none.
import * as core from '@veyq/core-js/memories'
import { editParticipantsOf } from '../../core/node/nodeMeta'
import { writeNodeMeta, type WriteNodeMetaOptions } from '../../core/node/nodeMetaWrite'
import type { NodeMetaState } from '../../core/node/nodeMeta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'

export function mergeParticipants(stored: readonly string[], wanted: readonly string[], openedWith: readonly string[]): string[] {
  return [...core.memoriesMergeParticipants(stored, wanted, openedWith)]
}

/**
 * The participants EDITOR's save: set `wanted` as the Memory's list, read-modify-write, version-checked, MERGING on a
 * conflict (`memoryParticipants.ts`' header). Empty drops the sub-object. Unknown fields inside the sub-object and every sibling (date,
 * place, cover) are kept. Returns the state the server confirmed (or the one it handed back when nothing needed writing).
 *
 * `openedWith` is the list the picker SHOWED when it opened, never one read from `current`: the page's poll may have
 * brought a peer's addition into the node meanwhile, and that id is no removal of the editor's (inbox #952). `current` is
 * the node as the page holds it at the save — the revision the write states.
 */
export async function setMemoryParticipants(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  wanted: readonly string[],
  openedWith: readonly string[],
  current: NodeMetaState,
  opts: WriteNodeMetaOptions = {},
): Promise<SealedMetaState> {
  const { state } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, editParticipantsOf((stored) => mergeParticipants(stored, wanted, openedWith)), undefined, opts)
  return state
}
