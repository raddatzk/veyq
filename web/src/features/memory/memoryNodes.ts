// The Memories application's two content-node births — a Memory (top-level, wrapped under the write key) and a
// Chapter (wrapped under its parent's K_mem) — over core's kind-agnostic `createContentNode` (ADR 0079).
import { NodeKind } from '../../api/generated/content'
import { writeKey, type EpochKeys } from '../spaces/epochs'
import { createContentNode, type NodeAddress } from '../../core/node/tree'
import { sealNodeMeta } from './nodeMeta'
import type { MemoryLocation } from './memoryLocation'

/**
 * Create a MEMORY — births a fresh random `K_mem` wrapped under `K_SC`. Returns the node id + the K_mem. An
 * optional `location` is **sealed at birth** into the node's `wrappedMeta` under that fresh `K_mem` (cr-0069
 * pre-arm) — a `fixed` pin, or an `auto` name with no `derived` yet (it re-medoids on the first geotagged media).
 */
export async function createMemory(
  scopeId: string, epochs: EpochKeys, name: string, parentId: string | null = null,
  location: MemoryLocation | null = null,
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const metaSealer = location ? (key: Uint8Array, node: NodeAddress) => sealNodeMeta(key, node, { v: 1, location }) : undefined
  // A top-level Memory's K_mem wraps under the write key E_current (the scope's current epoch).
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, writeKey(epochs), NodeKind.Memory, name, parentId, metaSealer)
  return { nodeId, memoryKey: contentKey }
}

/**
 * Create a CHAPTER inside a Memory (or another chapter) — births a fresh random `K_mem` wrapped under the
 * **parent node's `K_mem`** (`parentKey`), so the chapter is a sharing unit of its own (ADR 0012, cr-0060).
 * Returns the node id + the chapter's K_mem.
 */
export async function createChapter(
  scopeId: string, epochs: EpochKeys, parentKey: Uint8Array, name: string, parentId: string,
): Promise<{ nodeId: string; chapterKey: Uint8Array }> {
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, parentKey, NodeKind.Chapter, name, parentId)
  return { nodeId, chapterKey: contentKey }
}
