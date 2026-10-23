// The Memories application's two content-node births — a Memory (wrapped under its parent's key: a folder's, or the
// write key at the scope root) and a Chapter (wrapped under its parent's K_node) — over core's kind-agnostic
// `createContentNode` (ADR 0079, ADR 0080 Decision 1). The name and slug token go under the same parent key (ADR 0080
// Decision 2): a Memory's under the folder's key (K_SC at the root), a chapter's under its Memory's K_node — so a guest
// of the Memory's share reads its chapter titles.
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import type { EpochKeys } from '../spaces/epochs'
import { createContentNode, type CreateNodeOptions, type NodeAddress, type NodeParent } from '../../core/node/tree'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import type { MemoryLocation } from './memoryLocation'
import { normalizeParticipants } from './memoryParticipants'

/** What a Memory's first `wrappedMeta` may carry. Each part is written only when given: a Memory with neither is born with
 *  no meta at all. */
export interface MemoryBirthMeta {
  /** A pre-armed place (cr-0069): a `fixed` pin, or an `auto` name with no `derived` yet. */
  location?: MemoryLocation | null
  /** Who was there from the start (uc.memory.participants, "at creation"): opaque user ids - the creator's own when they
   *  tick "I was there". Empty or absent writes NO `participants` key, which is exactly "no participants". */
  participants?: readonly string[]
}

/**
 * Create a MEMORY — births a fresh random `K_node` wrapped under its parent's content key (`parent.key`, a folder's;
 * the write key `E_current` at the scope root, ADR 0080 Decision 1). Returns the node id + the K_node. The optional
 * `birth` meta is **sealed at birth** into the node's `wrappedMeta` under that fresh `K_node`, in the one create call:
 * a pre-armed `location` (cr-0069; an `auto` one re-medoids on the first geotagged media) and the first `participants`
 * (ADR 0083 - ids only, no second write, no version bump).
 */
export async function createMemory(
  scopeId: string, epochs: EpochKeys, name: string, parent: NodeParent = null,
  birth: MemoryBirthMeta = {}, opts: CreateNodeOptions = {},
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const meta: Record<string, unknown> = {}
  if (birth.location) meta.location = birth.location
  const participants = normalizeParticipants({ userIds: birth.participants ?? [] })
  if (participants.length > 0) meta.participants = { userIds: participants }
  const metaSealer = Object.keys(meta).length > 0 ? (key: Uint8Array, node: NodeAddress) => sealNodeMeta(key, node, { v: 1, ...meta }) : undefined
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, parent, MEMORY_KIND, name, metaSealer, opts)
  return { nodeId, memoryKey: contentKey }
}

/**
 * Create a CHAPTER inside a Memory (or another chapter) — births a fresh random `K_node` wrapped under the
 * **parent node's `K_node`** (`parentKey`), so the chapter is a sharing unit of its own (ADR 0012, cr-0060).
 * `parentEpoch` is the parent's epoch as read, which the create states (ADR 0109 point 6). Returns the node id + the
 * chapter's K_node.
 */
export async function createChapter(
  scopeId: string, epochs: EpochKeys, parentKey: Uint8Array, name: string, parentId: string, parentEpoch: number,
  opts: CreateNodeOptions = {},
): Promise<{ nodeId: string; chapterKey: Uint8Array }> {
  const parent = { id: parentId, key: parentKey, epoch: parentEpoch }
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, parent, CHAPTER_KIND, name, undefined, opts)
  return { nodeId, chapterKey: contentKey }
}
