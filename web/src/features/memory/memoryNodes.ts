// The Memories application's two content-node births — a Memory (wrapped under its parent's key: a folder's, or the
// write key at the scope root) and a Chapter (wrapped under its parent's K_mem) — over core's kind-agnostic
// `createContentNode` (ADR 0079, ADR 0080 Decision 1). The name and slug token go under the same parent key (ADR 0080
// Decision 2): a Memory's under the folder's key (K_SC at the root), a chapter's under its Memory's K_mem — so a guest
// of the Memory's share reads its chapter titles.
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import type { EpochKeys } from '../spaces/epochs'
import { createContentNode, type NodeAddress, type NodeParent } from '../../core/node/tree'
import { sealNodeMeta } from './nodeMeta'
import type { MemoryLocation } from './memoryLocation'

/**
 * Create a MEMORY — births a fresh random `K_mem` wrapped under its parent's content key (`parent.key`, a folder's;
 * the write key `E_current` at the scope root, ADR 0080 Decision 1). Returns the node id + the K_mem. An
 * optional `location` is **sealed at birth** into the node's `wrappedMeta` under that fresh `K_mem` (cr-0069
 * pre-arm) — a `fixed` pin, or an `auto` name with no `derived` yet (it re-medoids on the first geotagged media).
 */
export async function createMemory(
  scopeId: string, epochs: EpochKeys, name: string, parent: NodeParent = null,
  location: MemoryLocation | null = null,
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const metaSealer = location ? (key: Uint8Array, node: NodeAddress) => sealNodeMeta(key, node, { v: 1, location }) : undefined
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, parent, MEMORY_KIND, name, metaSealer)
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
  const { nodeId, contentKey } = await createContentNode(scopeId, epochs, { id: parentId, key: parentKey }, CHAPTER_KIND, name)
  return { nodeId, chapterKey: contentKey }
}
