// en.memory.tree (web) — the encrypted Memory tree data layer. Operator-blind: node names are sealed under
// the scope content key K_SC, the slug token is a blinded keyed MAC under K_SC, and every content-bearing node
// (MEMORY or CHAPTER) carries a fresh random K_mem wrapped under its PARENT's content key — K_SC for a
// top-level Memory, the parent Memory's K_mem for a chapter (recursive K_mem, the share grain, ADR 0012 +
// cr-0060). Folders carry no key.
//
// **Epochs (ADR 0024, cr-0089).** A rotated scope's `K_SC` is a chain of epoch keys E_0, E_1, …; the caller
// opens the scope into an {@link EpochKeys} set (the write key `E_current` + a per-epoch lookup) and passes it
// in. New content seals name/slug + the top-level `K_mem` wrap under the **write key** and is tagged with the
// current epoch; a read opens each node's name (and a top-level Memory's `K_mem`) under **its own** epoch key,
// so a tree spanning several epochs decrypts fully. A chapter's `K_mem` wraps under its parent Memory's `K_mem`
// (epoch-independent) — only the top-level wrap keys off the epoch.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { contentApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { NodeKind, type NodeSummary, type TrashedNodeSummary } from '../../api/generated/content'
import { writeKey, keyAt, type EpochKeys } from '../spaces/epochs'
import { sealNodeMeta } from './nodeMeta'
import type { MemoryLocation } from './memoryLocation'
import { slugify } from '../../shared/naming'

const NAME_CTX = 'node-name'
const KMEM_CTX = 'content-key'
const decoder = new TextDecoder()

/** A decrypted tree node for the UI. `name` is decrypted client-side; the server never saw it. */
export type TreeNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  orderIndex: number
  /** The scope content-key **epoch** this node's name / slug / (top-level) `K_mem`-wrap are sealed under (ADR 0024);
   *  0 for pre-rotation nodes. A read opens this node under `E_epoch`; a rename re-seals under the same epoch. */
  epoch: number
  name: string
  wrappedContentKey: string | null
  /** Curated metadata (date now, place later) sealed under this node's `K_mem` (cr-0067); null when unset. */
  wrappedMeta: string | null
}

/** Seal a name under a scope content key `K_SC` (the `node-name` construction, ADR 0012). Reused verbatim for a
 *  group's name (en.spaces.group-scope, cr-0032) — a group name is sealed exactly like a node name. */
export function sealName(scopeKey: Uint8Array, name: string): string {
  return b64std(v1.symWrap(scopeKey, v1.utf8(name), NAME_CTX, v1.randomBytes(12)))
}
/** Open a name sealed under `K_SC` (the counterpart to {@link sealName}). */
export function openName(scopeKey: Uint8Array, encryptedName: string): string {
  return decoder.decode(v1.symUnwrap(scopeKey, fromB64std(encryptedName), NAME_CTX))
}
function slugTokenFor(scopeKey: Uint8Array, parentSlot: string, name: string): string {
  return b64std(v1.slugToken(scopeKey, parentSlot, slugify(name)))
}

/** List the scope's tree with each node's name decrypted under **its own** epoch key `E_{node.epoch}` (ADR 0024),
 *  so a tree spanning several epochs (post-rotation) decrypts fully for a current member. */
export async function listTree(scopeId: string, epochs: EpochKeys): Promise<TreeNode[]> {
  const rows = await withRefresh(() => contentApi().listNodes({ scopeId }))
  return rows.map((r: NodeSummary) => ({
    id: r.id,
    parentId: r.parentId ?? null,
    kind: r.kind,
    orderIndex: r.orderIndex,
    epoch: r.epoch,
    name: openName(keyAt(epochs, r.epoch), r.encryptedName),
    wrappedContentKey: r.wrappedContentKey ?? null,
    wrappedMeta: r.wrappedMeta ?? null,
  }))
}

/** Create a FOLDER (organization only — no content key). Returns its node id. Sealed under the write key `E_current`. */
export async function createFolder(
  scopeId: string, epochs: EpochKeys, name: string, parentId: string | null = null,
): Promise<string> {
  const parentSlot = parentId ?? scopeId
  const scopeKey = writeKey(epochs)
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        parentId: parentId ?? undefined,
        kind: NodeKind.Folder,
        encryptedName: sealName(scopeKey, name),
        slugToken: slugTokenFor(scopeKey, parentSlot, name),
        orderIndex: 0,
        epoch: epochs.current,
      },
    }),
  )
  return res.nodeId
}

/**
 * Create a content-bearing node (MEMORY or CHAPTER) — births a fresh random `K_mem`, wrapped under the
 * **parent's content key** `wrapKey` (recursive `K_mem`, ADR 0012): the write key `E_current` for a top-level
 * Memory, the parent Memory's `K_mem` for a chapter. The name + slug are always sealed under the write key, and
 * the node is tagged with the **current epoch** (ADR 0024).
 */
async function createContentNode(
  scopeId: string, epochs: EpochKeys, wrapKey: Uint8Array, kind: NodeKind, name: string, parentId: string | null,
  metaSealer?: (contentKey: Uint8Array) => string,
): Promise<{ nodeId: string; contentKey: Uint8Array }> {
  const parentSlot = parentId ?? scopeId
  const scopeKey = writeKey(epochs)
  const contentKey = v1.randomBytes(32)
  const wrappedContentKey = b64std(v1.symWrap(wrapKey, contentKey, KMEM_CTX, v1.randomBytes(12)))
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        parentId: parentId ?? undefined,
        kind,
        encryptedName: sealName(scopeKey, name),
        slugToken: slugTokenFor(scopeKey, parentSlot, name),
        orderIndex: 0,
        epoch: epochs.current,
        wrappedContentKey,
        wrappedMeta: metaSealer?.(contentKey), // sealed under the just-minted K_mem (e.g. a pre-armed location)
      },
    }),
  )
  return { nodeId: res.nodeId, contentKey }
}

/**
 * Create a MEMORY — births a fresh random `K_mem` wrapped under `K_SC`. Returns the node id + the K_mem. An
 * optional `location` is **sealed at birth** into the node's `wrappedMeta` under that fresh `K_mem` (cr-0069
 * pre-arm) — a `fixed` pin, or an `auto` name with no `derived` yet (it re-medoids on the first geotagged media).
 */
export async function createMemory(
  scopeId: string, epochs: EpochKeys, name: string, parentId: string | null = null,
  location: MemoryLocation | null = null,
): Promise<{ nodeId: string; memoryKey: Uint8Array }> {
  const metaSealer = location ? (key: Uint8Array) => sealNodeMeta(key, { v: 1, location }) : undefined
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

/**
 * Open a content node's `K_mem` by unwrapping `node.wrappedContentKey` under its parent's content key
 * (`parentKey` = `K_SC` for a top-level Memory, the parent Memory's `K_mem` for a chapter). Null if it carries
 * no key (a folder). For a node at arbitrary depth, resolve `parentKey` with [nodeContentKey].
 */
export function openContentKey(parentKey: Uint8Array, node: { wrappedContentKey: string | null }): Uint8Array | null {
  if (!node.wrappedContentKey) return null
  return v1.symUnwrap(parentKey, fromB64std(node.wrappedContentKey), KMEM_CTX)
}

/** Back-compat alias: a top-level Memory's `K_mem` opens under `K_SC`. Prefer [openContentKey] / [nodeContentKey]. */
export const openMemory = openContentKey

/**
 * Resolve a content node's `K_mem`, walking the content-bearing ancestors top-down: folders are transparent (no
 * key), so the **top-level Memory** opens under `E_{its epoch}` (ADR 0024) and each nested chapter under its
 * parent's `K_mem`. Returns null if the node carries no key or any link in the chain can't be opened. (Recursive
 * `K_mem`, ADR 0012 — only the top-level wrap keys off the epoch; deeper links chain through `K_mem`.)
 */
export function nodeContentKey(epochs: EpochKeys, tree: TreeNode[], nodeId: string): Uint8Array | null {
  const byId = new Map(tree.map((n) => [n.id, n]))
  // collect the content-bearing chain from the node up to the root (nearest-last → reverse to top-down)
  const chain: TreeNode[] = []
  for (let cur = byId.get(nodeId); cur; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    if (cur.wrappedContentKey) chain.unshift(cur)
  }
  if (!chain.length) return null
  let key = keyAt(epochs, chain[0].epoch) // the top-level Memory's K_mem is wrapped under its own epoch's K_SC
  for (const node of chain) {
    const opened = openContentKey(key, node)
    if (!opened) return null
    key = opened
  }
  return key
}

/** Rename a node — re-seal the name + recompute the slug under the node's **own** epoch key (its epoch is fixed at
 *  birth; a rename never re-tags it, so name and `K_mem`-wrap stay under the same epoch, ADR 0024). */
export async function renameNode(
  scopeId: string, epochs: EpochKeys, node: { id: string; parentId: string | null; epoch: number }, newName: string,
): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  const key = keyAt(epochs, node.epoch)
  await withRefresh(() =>
    contentApi().updateNode({
      scopeId,
      nodeId: node.id,
      updateNodeRequest: {
        encryptedName: sealName(key, newName),
        slugToken: slugTokenFor(key, parentSlot, newName),
      },
    }),
  )
}

/** Delete a node and its subtree — a SOFT delete into the space's trash (uc.memory.delete-node, ADR 0061).
 *  Nothing is destroyed: the subtree's media, buckets, covers and blobs survive until the scheduled purge, which
 *  is what makes {@link restoreNode} complete. The name is freed server-side at once, so it is immediately
 *  reusable — and that is why a restore has to bring a fresh slug token with it. */
export async function deleteNode(scopeId: string, nodeId: string): Promise<void> {
  await withRefresh(() => contentApi().deleteNode({ scopeId, nodeId }))
}

/** One soft-deleted subtree ROOT in the space's trash, its name decrypted client-side. */
export type TrashedNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  epoch: number
  name: string
  /** When the subtree was deleted. */
  deletedAt: Date
  /** When the scheduled job erases it for good — derived server-side from the operator's window, not stored. */
  purgeAfter: Date
}

/** The space's node trash: the ROOT of each deleted subtree (the descendants went with it and return with it),
 *  each name opened under **its own** epoch key `E_{node.epoch}`, exactly as {@link listTree} does. */
export async function listTrash(scopeId: string, epochs: EpochKeys): Promise<TrashedNode[]> {
  const rows = await withRefresh(() => contentApi().listTrashedNodes({ scopeId }))
  return rows.map((r: TrashedNodeSummary) => ({
    id: r.id,
    parentId: r.parentId ?? null,
    kind: r.kind,
    epoch: r.epoch,
    name: openName(keyAt(epochs, r.epoch), r.encryptedName),
    deletedAt: r.deletedAt,
    purgeAfter: r.purgeAfter,
  }))
}

/**
 * Restore a trashed subtree. The client **must** supply the slug token: it is a blinded MAC under `K_SC`, so the
 * server cannot recompute one, and the delete consumed the original when it freed the name. It is derived under
 * the node's **own** epoch key — its epoch is fixed at birth and a restore does not re-tag it, same rule as a
 * rename (ADR 0024).
 *
 * `newName` is the A1 retry after a `slug_taken`: a node created while this one sat in the trash already holds
 * the name, so the user picks another. The re-sealed name travels in the SAME request as the token derived from
 * it — sent as two calls, the node would be briefly live carrying a slug its own name does not produce.
 */
export async function restoreNode(
  scopeId: string,
  epochs: EpochKeys,
  node: { id: string; parentId: string | null; epoch: number; name: string },
  newName?: string,
): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  const key = keyAt(epochs, node.epoch)
  const name = newName ?? node.name
  await withRefresh(() =>
    contentApi().restoreNode({
      scopeId,
      nodeId: node.id,
      restoreNodeRequest: {
        slugToken: slugTokenFor(key, parentSlot, name),
        encryptedName: newName === undefined ? undefined : sealName(key, newName),
      },
    }),
  )
}
