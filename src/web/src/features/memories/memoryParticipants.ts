// uc.memory.participants (web) — who was there: the `participants` sub-object of the sealed node-meta blob (ADR 0083).
// `{ userIds: string[] }` — OPAQUE USER IDS ONLY. Never a name, never a snapshot of one: the list sits in the blob a
// share serves unchanged (a guest holds `K_node`), so the interface is what hides it from a guest (`rights.ts`), and
// ids are what keeps the residual harmless (ADR 0083 decision 4). If this ever carries a name, that decision is open again.
//
// The write is a read-modify-write through `writeNodeMeta` (ADR 0077) with its OWN edit function, not `editField`: the
// list is a set two members may extend at once, and `editField` answers "the same field moved" with a conflict the editor
// must re-open on — which would drop a peer's addition or make the editor redo its pick. Here a `409 stale_revision`
// re-reads the blob and MERGES: the union of what is stored and what the editor wants, except that an id the editor
// REMOVED stays removed. The write and its merge are `participantsWrite.ts` (the merge the Kotlin core's): this file is read
// eagerly (the level's rows, the search's facets), the write only by a Memory's page, so Memories' module loads with that page.
import { unsealNodeMeta } from '../../core/node/nodeMeta'

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

/** A Memory's participants from its `wrappedMeta` under `nodeKey` (`K_node`). Best-effort: `[]` when absent or unreadable. */
export function unsealMemoryParticipants(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): string[] {
  return normalizeParticipants(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).participants)
}
