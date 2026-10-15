// uc.memory.participants (web) — who was there: the `participants` sub-object of the sealed node-meta blob (ADR 0083).
// `{ userIds: string[] }` — OPAQUE USER IDS ONLY. Never a name, never a snapshot of one: the list sits in the blob a
// share serves unchanged (a guest holds `K_node`), so the interface is what hides it from a guest (`capabilities.ts`), and
// ids are what keeps the residual harmless (ADR 0083 decision 4). If this ever carries a name, that decision is open again.
//
// The write is a read-modify-write through `writeNodeMeta` (ADR 0077) with its OWN edit function, not `editField`: the
// list is a set two members may extend at once, and `editField` answers "the same field moved" with a conflict the editor
// must re-open on — which would drop a peer's addition or make the editor redo its pick. Here a `409 stale_version`
// re-reads the blob and MERGES: the union of what is stored and what the editor wants, except that an id the editor
// REMOVED stays removed ({@link mergeParticipants}).
import { editParticipantsOf, unsealNodeMeta } from '../../core/node/nodeMeta'
import { writeNodeMeta } from '../../core/node/nodeMetaWrite'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'

/** At most this many participants: `wrappedMeta` is limited to 8192 base64 characters (~6 KB sealed) and an id is ~40
 *  bytes of JSON beside the date, place and cover (ADR 0083 decision 3a). */
export const MAX_PARTICIPANTS = 100

/** Normalize a loosely-typed `participants` payload to its user ids: strings only, non-empty, unique (first wins),
 *  capped at {@link MAX_PARTICIPANTS}. Anything else is "no participants" — a blob that does not fit is never trusted. */
export function normalizeParticipants(raw: unknown): string[] {
  if (!raw || typeof raw !== 'object') return []
  const ids = (raw as { userIds?: unknown }).userIds
  if (!Array.isArray(ids)) return []
  const out: string[] = []
  const seen = new Set<string>()
  for (const id of ids) {
    if (typeof id !== 'string' || id === '' || seen.has(id)) continue
    seen.add(id)
    out.push(id)
    if (out.length >= MAX_PARTICIPANTS) break
  }
  return out
}

/**
 * The merge on a conflict: what the server holds now (`stored`) united with what the editor wants (`wanted`), minus the
 * ids the editor removed (`removed` = opened-with minus wanted). A peer's addition survives; the editor's own removal wins
 * for the id it removed; the order is stored first, then the editor's new ones. Capped at {@link MAX_PARTICIPANTS}.
 */
export function mergeParticipants(stored: readonly string[], wanted: readonly string[], openedWith: readonly string[]): string[] {
  const wantedSet = new Set(wanted)
  const removed = new Set(openedWith.filter((id) => !wantedSet.has(id)))
  const out: string[] = []
  const seen = new Set<string>()
  for (const id of [...stored, ...wanted]) {
    if (removed.has(id) || seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out.slice(0, MAX_PARTICIPANTS)
}

/** A Memory's participants from its `wrappedMeta` under `nodeKey` (`K_node`). Best-effort: `[]` when absent or unreadable. */
export function unsealMemoryParticipants(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): string[] {
  return normalizeParticipants(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).participants)
}

/**
 * The participants EDITOR's save: set `wanted` as the Memory's list, read-modify-write, version-checked, MERGING on a
 * conflict (see the header). Empty drops the sub-object. Unknown fields inside the sub-object and every sibling (date,
 * place, cover) are kept. Returns the state the server confirmed (or the one it handed back when nothing needed writing).
 */
export async function setMemoryParticipants(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  wanted: readonly string[],
  current: SealedMetaState,
): Promise<SealedMetaState> {
  const openedWith = unsealMemoryParticipants(scopeId, nodeKey, { id: nodeId, wrappedMeta: current.wrappedMeta })
  const { state } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, editParticipantsOf((stored) => mergeParticipants(stored, wanted, openedWith)))
  return state
}
