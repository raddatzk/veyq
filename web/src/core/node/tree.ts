// en.memory.tree (web) — the encrypted Memory tree data layer. Operator-blind: EVERY node — FOLDER included
// (ADR 0080 Decision 1) — carries a fresh random content key wrapped under its PARENT's content key: K_SC for a
// scope-root node, the parent node's key below it, whatever the parent's kind (a folder's, a Memory's, a
// chapter's). Recursive keys, the share grain (ADR 0012, cr-0060, ADR 0080). A node's **directory entry** lives
// under that same parent key (ADR 0080 Decision 2): its name is sealed under it and its slug token is a blinded keyed
// MAC under it. So whoever holds a node's key reads every name below it — a share guest included — and nothing
// above it; a member, holding K_SC, opens the whole tree top-down in one pass ({@link listTree}).
//
// **Epochs (ADR 0024, cr-0089).** A rotated scope's `K_SC` is a chain of epoch keys E_0, E_1, …; the caller
// opens the scope into an {@link EpochKeys} set (the write key `E_current` + a per-epoch lookup) and passes it
// in. New content is tagged with the current epoch, and a **scope-root** entry (name, slug, key wrap) is sealed under
// the **write key**; a read opens a scope-root entry under **its own** epoch key, so a tree spanning several epochs
// decrypts fully. Below the root an entry is under its parent's key (epoch-independent) — only scope-root entries key
// off the epoch, although every seal still binds the node's epoch (ADR 0072).
//
// **Every seal names its node (ADR 0072).** A name and a `K_mem` wrap are bound to (scope, node, epoch, placement
// version) — the node id the client mints at creation, the epoch it is sealed at, and the version of its placement
// the wire stores them at (0 at birth, n+1 after a move or rename built on n; ADR 0080 Decision 3) — so a server that
// serves one node's wrap on another's row, or a wrap a move superseded, gets a refusal, never the other Memory's key
// (the upload chain: every upload into X would land under the key of whichever Memory's wrap was served). A reader
// binds from the node it addresses.
//
// **Move and rename are one write ({@link placeNode}).** A rename is a move to the same parent: the node's key is
// re-wrapped under the destination parent's key and its name and slug re-sealed under it, at the next placement
// version, which the server compare-and-sets.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { contentApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import type { NodeSummary, TrashedNodeSummary } from '../../api/generated/content'
import { FOLDER_KIND, type NodeKind } from './kind'
import { writeKey, keyAt, type EpochKeys } from '../../features/spaces/epochs'
import { slugify } from '../../shared/naming'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { expectMintedId } from './mintedId'

/** Which node a seal belongs to: its scope, its id, the epoch it is sealed at and the placement version the wire stores
 *  it at (ADR 0072, ADR 0080 Decision 3). */
export type NodeAddress = { scopeId: string; nodeId: string; epoch: number; placementVersion: number }
const nameContext = (a: NodeAddress) =>
  ({ purpose: 'node-name', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.epoch, placementVersion: a.placementVersion }) as const
const contentKeyContext = (a: NodeAddress) =>
  ({ purpose: 'content-key', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.epoch, placementVersion: a.placementVersion }) as const
const decoder = new TextDecoder()

/** A decrypted tree node for the UI. `name` is decrypted client-side; the server never saw it. */
export type TreeNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  orderIndex: number
  /** The scope content-key **epoch** this node is tagged with (ADR 0024); 0 for pre-rotation nodes. A scope-root
   *  node's entry (name / slug / key wrap) is sealed under `E_epoch`; every seal of the node binds it (ADR 0072). */
  epoch: number
  name: string
  wrappedContentKey: string
  /** Curated metadata (date now, place later) sealed under this node's `K_mem` (cr-0067); null when unset. */
  wrappedMeta: string | null
  /** The version [wrappedMeta] is at — what a meta write states it was built from (ADR 0077). */
  metaVersion: number
  /** The placement version the node's name and key wrap are sealed at — what a move or rename states it was built on
   *  (ADR 0080 Decision 3). */
  placementVersion: number
}

/** Seal a node's name (the `node-name` seal, ADR 0012), bound to the node, under the key its **directory entry** lives
 *  under: its parent's content key, or `K_SC` at the node's epoch for a scope-root node (ADR 0080 Decision 2). A
 *  group's name and a share's Memory name are their own purposes (`group-name`, `share-memory-name`, ADR 0072). */
export function sealName(entryKey: Uint8Array, node: NodeAddress, name: string): string {
  return b64std(v1.seal(nameContext(node), entryKey, v1.utf8(name)))
}
/** Open a node's name under the key its entry lives under (the counterpart to {@link sealName}). */
export function openName(entryKey: Uint8Array, node: NodeAddress, encryptedName: string): string {
  return decoder.decode(v1.open(nameContext(node), entryKey, fromB64std(encryptedName)))
}
/** The blinded slug token of `name` in the slot `parentSlot` (the parent's id, the scope id at the root), keyed by the
 *  key the entry lives under (ADR 0080 Decision 2): unique per parent, stable per parent key. */
export function slugTokenFor(entryKey: Uint8Array, parentSlot: string, name: string): string {
  return b64std(v1.slugToken(entryKey, parentSlot, slugify(name)))
}

/** The fields of a listed row that its directory entry and key chain need. */
type EntryRow = {
  id: string; parentId: string | null; epoch: number; placementVersion: number; encryptedName: string; wrappedContentKey: string
}

/**
 * Open every row's name **top-down in one pass** (ADR 0080 Decision 2): a scope-root entry under its own epoch's
 * `K_SC` (ADR 0024), every other one under its parent's content key, opened one level up on the way down. The keys
 * live only in this call. A row whose chain does not reach the scope root (its parent is not listed, or a served
 * parent cycle) has no key to open its name with: it is refused like a name that does not open (`OpenRefused`), and
 * so is a name or a wrap the server moved onto another row.
 */
function openEntries(scopeId: string, epochs: EpochKeys, rows: EntryRow[]): Map<string, string> {
  const children = new Map<string | null, EntryRow[]>()
  for (const r of rows) children.set(r.parentId, [...(children.get(r.parentId) ?? []), r])
  const names = new Map<string, string>()
  const level: { row: EntryRow; entryKey: Uint8Array }[] = (children.get(null) ?? []).map((row) => ({ row, entryKey: keyAt(epochs, row.epoch) }))
  while (level.length) {
    const { row, entryKey } = level.shift()!
    const node = { scopeId, nodeId: row.id, epoch: row.epoch, placementVersion: row.placementVersion }
    names.set(row.id, openName(entryKey, node, row.encryptedName))
    const key = openContentKey(scopeId, entryKey, row)
    for (const child of children.get(row.id) ?? []) level.push({ row: child, entryKey: key })
  }
  if (names.size !== new Set(rows.map((r) => r.id)).size) throw new v1.OpenRefused('node-name') // a row no chain reaches
  return names
}

/** List the scope's tree, each name opened under the key its entry lives under ({@link openEntries}): a scope-root
 *  entry under **its own** epoch key `E_{node.epoch}` (ADR 0024), every other under its parent's content key — so a
 *  tree spanning several epochs (post-rotation) decrypts fully for a current member. */
export async function listTree(scopeId: string, epochs: EpochKeys): Promise<TreeNode[]> {
  const rows = (await withRefresh(() => contentApi().listNodes({ scopeId }))).map((r: NodeSummary) => ({
    ...r,
    parentId: r.parentId ?? null,
  }))
  const names = openEntries(scopeId, epochs, rows)
  return rows.map((r) => ({
    id: r.id,
    parentId: r.parentId,
    kind: r.kind,
    orderIndex: r.orderIndex,
    epoch: r.epoch,
    name: names.get(r.id)!,
    wrappedContentKey: r.wrappedContentKey,
    wrappedMeta: r.wrappedMeta ?? null,
    metaVersion: r.metaVersion,
    placementVersion: r.placementVersion,
  }))
}

/**
 * Where a new node goes (ADR 0080 Decision 1): its parent's id and the parent's **content key**, the key the new
 * node's own key wraps under, or `null` for the scope root, where it wraps under the write key `E_current`.
 * The parent's key comes from {@link nodeContentKey}.
 */
export type NodeParent = { id: string; key: Uint8Array } | null

/** Create a FOLDER. Like every node it births a random content key wrapped under its parent's (ADR 0080
 *  Decision 1), so whoever holds the folder's key opens every key created below it. Returns its node id. */
export async function createFolder(scopeId: string, epochs: EpochKeys, name: string, parent: NodeParent = null): Promise<string> {
  return (await createContentNode(scopeId, epochs, parent, FOLDER_KIND, name)).nodeId
}

/**
 * Create a node of any kind: births a fresh random content key wrapped under the **parent's content key**
 * (`parent.key`; the write key `E_current` at the scope root; recursive keys, ADR 0012 / ADR 0080 Decision 1), and
 * seals its directory entry — name + slug token — under that same key (ADR 0080 Decision 2). The node is tagged with
 * the **current epoch** (ADR 0024). The kind is the caller's: core's {@link createFolder} and an application's own
 * wrappers (Memories: `createMemory` / `createChapter`) call this.
 */
export async function createContentNode(
  scopeId: string, epochs: EpochKeys, parent: NodeParent, kind: NodeKind, name: string,
  metaSealer?: (contentKey: Uint8Array, node: NodeAddress) => string,
): Promise<{ nodeId: string; contentKey: Uint8Array }> {
  const parentId = parent?.id ?? null
  const parentSlot = parentId ?? scopeId
  const entryKey = parent?.key ?? writeKey(epochs)
  const contentKey = v1.randomBytes(32)
  // The client mints the node's id, so its name, K_mem wrap and meta are all sealed bound to it (ADR 0072), at the
  // placement version a new node has (0, ADR 0080 Decision 3).
  const node = { scopeId, nodeId: crypto.randomUUID(), epoch: epochs.current, placementVersion: 0 }
  const wrappedContentKey = wrapContentKey(entryKey, node, contentKey)
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        nodeId: node.nodeId,
        parentId: parentId ?? undefined,
        kind,
        encryptedName: sealName(entryKey, node, name),
        slugToken: slugTokenFor(entryKey, parentSlot, name),
        orderIndex: 0,
        epoch: epochs.current,
        wrappedContentKey,
        wrappedMeta: metaSealer?.(contentKey, node), // sealed under the just-minted K_mem (e.g. a pre-armed location)
      },
    }),
  )
  return { nodeId: expectMintedId(node.nodeId, res.nodeId), contentKey }
}

/** Wrap a node's content key under the key its entry lives under (its parent's, `K_SC` at a scope-root), bound to the
 *  node's address (the counterpart to {@link openContentKey}). The one place the `content-key` seal is made: a guest's
 *  tree operations (`core/sharing/guestTree.ts`) wrap through it too, so no second copy of the context drifts. */
export function wrapContentKey(entryKey: Uint8Array, node: NodeAddress, contentKey: Uint8Array): string {
  return b64std(v1.seal(contentKeyContext(node), entryKey, contentKey))
}

/**
 * Open a node's content key by unwrapping `node.wrappedContentKey` under its parent's content key
 * (`parentKey` = `K_SC` at the node's epoch for a scope-root node, the parent node's key below it; every node carries
 * a wrap, ADR 0080 Decision 1). Bound to (scope, the node's id, its epoch, its placement version) — the node the caller
 * addresses, at the version listed with it — so a wrap the server moved onto this row from another node, or one a move
 * superseded, throws `OpenRefused` instead of yielding a key. For a node at arbitrary depth, resolve `parentKey` with
 * [nodeContentKey].
 */
export function openContentKey(
  scopeId: string, parentKey: Uint8Array, node: { id: string; epoch: number; placementVersion: number; wrappedContentKey: string },
): Uint8Array {
  const address = { scopeId, nodeId: node.id, epoch: node.epoch, placementVersion: node.placementVersion }
  return v1.open(contentKeyContext(address), parentKey, fromB64std(node.wrappedContentKey))
}

/** Back-compat alias: a scope-root node's key opens under `K_SC`. Prefer [openContentKey] / [nodeContentKey]. */
export const openMemory = openContentKey

/**
 * Resolve a node's content key, walking **every** ancestor top-down (ADR 0080 Decision 1: a folder is a link of the
 * chain like any other node, never transparent). The **scope-root ancestor** opens under `E_{its epoch}`
 * (ADR 0024) and each node below under its parent's key. Returns null if the node is not in the tree or its chain does
 * not reach the scope root. (Recursive keys, ADR 0012: only the scope-root wrap keys off the epoch; deeper links chain
 * through their parent's key.)
 */
export function nodeContentKey(scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string): Uint8Array | null {
  const byId = new Map(tree.map((n) => [n.id, n]))
  // collect the chain from the node up to the scope root (nearest-last → reverse to top-down)
  const chain: TreeNode[] = []
  for (let cur = byId.get(nodeId); cur && chain.length <= tree.length; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    chain.unshift(cur) // the length bound stops a served parent cycle, which then reaches no root below
  }
  if (!chain.length || chain[0].parentId !== null) return null // an ancestor missing from the tree: no chain
  let key = keyAt(epochs, chain[0].epoch) // the scope-root node's key is wrapped under its own epoch's K_SC
  for (const node of chain) key = openContentKey(scopeId, key, node)
  return key
}

/** The key `node`'s directory entry (name, slug token) and its own key wrap live under (ADR 0080 Decisions 1 and 2):
 *  its parent's content key, or `E_{node.epoch}` for a scope-root node. Null when the parent's chain does not open. */
export function parentContentKey(
  scopeId: string, epochs: EpochKeys, tree: TreeNode[], node: { parentId: string | null; epoch: number },
): Uint8Array | null {
  return node.parentId === null ? keyAt(epochs, node.epoch) : nodeContentKey(scopeId, epochs, tree, node.parentId)
}

/** {@link parentContentKey} for a write or a read that cannot go on without it: a parent whose chain does not open has
 *  no key to seal or open the entry under, refused like a name that does not open. */
function entryKeyOf(scopeId: string, epochs: EpochKeys, tree: TreeNode[], node: { parentId: string | null; epoch: number }): Uint8Array {
  const key = parentContentKey(scopeId, epochs, tree, node)
  if (!key) throw new v1.OpenRefused('node-name')
  return key
}

/** Where a placement puts a node: its destination parent (`null` = the scope root) and, for a rename, the new name
 *  (absent = keep the current one). A rename is a placement to the node's current parent. */
export type Placement = { parentId: string | null; name?: string }

/** A placement that kept losing to concurrent moves or renames of the same node: the bounded retry gave up. Its own
 *  error, not the `409` it ends on, so a caller never shows it as a taken name. */
export class PlacementConflictError extends Error {
  constructor(readonly nodeId: string) {
    super(`placement_conflict: ${nodeId}`)
    this.name = 'PlacementConflictError'
  }
}

/** How many times a placement is rebuilt after a `409 stale_placement` — the node moved or was renamed in between. */
export const PLACEMENT_RETRIES = 1

/**
 * The epoch a placement seals its entry at (ADR 0080 Decision 4). An entry at the scope root lives under `K_SC`, so a
 * placement into the root — a rename there included — seals at the CURRENT epoch (the server refuses another); a move
 * out of the root stores the epoch it was sealed at, so it uses the current one too. Between two non-root parents the
 * entry is under the parent's key, which no rotation changes: the server keeps the node's stored epoch and ignores the
 * request's, so the client seals at the stored one — anything else would be bound to an epoch the row does not carry.
 */
export function placementEpoch(epochs: EpochKeys, node: { parentId: string | null; epoch: number }, to: { parentId: string | null }): number {
  return node.parentId === null || to.parentId === null ? epochs.current : node.epoch
}

/**
 * The one placement write (ADR 0080 Decision 3): move `nodeId` to `to.parentId`, renaming it to `to.name` when given —
 * a rename is a move to the same parent. The node's own content key, opened from the loaded `tree`, is re-wrapped under
 * the destination parent's key (`E_current` at the scope root), and its name and slug token are re-sealed under that
 * key, all bound to the NEXT placement version (`placementVersion + 1`), which the server compare-and-sets against the
 * version this write states. A rename re-wraps too: the old wrap is bound to the old version and would stop opening.
 *
 * On `409 stale_placement` (a concurrent move or rename of this node landed first) the tree is re-read and the
 * placement rebuilt on the node as it now is, at most {@link PLACEMENT_RETRIES} time(s); then
 * {@link PlacementConflictError}. Every other failure (`slug_taken`, `stale_epoch`, `illegal_structure`, …) is the
 * caller's. The server checks structure only (a live destination in the scope, the kind's placement rule, no cycle).
 */
export async function placeNode(
  scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string, to: Placement,
): Promise<void> {
  let current = tree
  for (let attempt = 0; ; attempt++) {
    try {
      await sendPlacement(scopeId, epochs, current, nodeId, to)
      return
    } catch (e) {
      if (!(await isCodedError(e, HttpStatus.conflict, 'stale_placement'))) throw e
      if (attempt >= PLACEMENT_RETRIES) throw new PlacementConflictError(nodeId)
      current = await listTree(scopeId, epochs)
    }
  }
}

/** Rename a node in place: {@link placeNode} to its current parent under `newName`. */
export function renameNode(scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string, newName: string): Promise<void> {
  const node = tree.find((n) => n.id === nodeId)
  if (!node) return Promise.reject(new v1.OpenRefused('content-key'))
  return placeNode(scopeId, epochs, tree, nodeId, { parentId: node.parentId, name: newName })
}

/** Move a node under `parentId` (`null` = the scope root), keeping its name: {@link placeNode}. */
export function moveNode(scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string, parentId: string | null): Promise<void> {
  return placeNode(scopeId, epochs, tree, nodeId, { parentId })
}

/** Build and send one placement against `tree` (see {@link placeNode}). */
async function sendPlacement(scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string, to: Placement): Promise<void> {
  const node = tree.find((n) => n.id === nodeId)
  const contentKey = node ? nodeContentKey(scopeId, epochs, tree, nodeId) : null
  if (!node || !contentKey) throw new v1.OpenRefused('content-key') // its chain does not open: nothing to re-wrap
  const epoch = placementEpoch(epochs, node, to)
  const destinationKey = to.parentId === null ? keyAt(epochs, epoch) : nodeContentKey(scopeId, epochs, tree, to.parentId)
  if (!destinationKey) throw new v1.OpenRefused('content-key') // a destination whose chain does not open
  const name = to.name ?? node.name
  const sealedAt = { scopeId, nodeId, epoch, placementVersion: node.placementVersion + 1 }
  await withRefresh(() =>
    contentApi().updateNode({
      scopeId,
      nodeId,
      updateNodeRequest: {
        parentId: to.parentId ?? undefined,
        wrappedContentKey: wrapContentKey(destinationKey, sealedAt, contentKey),
        encryptedName: sealName(destinationKey, sealedAt, name),
        slugToken: slugTokenFor(destinationKey, to.parentId ?? scopeId, name),
        epoch,
        placementVersion: node.placementVersion,
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
  /** The placement version its name is sealed at; a trash and a restore leave it alone. */
  placementVersion: number
  name: string
  /** When the subtree was deleted. */
  deletedAt: Date
  /** When the scheduled job erases it for good — derived server-side from the operator's window, not stored. */
  purgeAfter: Date
}

/** The space's node trash: the ROOT of each deleted subtree (the descendants went with it and return with it), each
 *  name opened under the key its entry lives under — its **live** parent's content key, resolved from the loaded
 *  `tree` (a trashed root's parent is always live, or the scope root), or **its own** epoch key `E_{node.epoch}` at the
 *  scope root — exactly as {@link listTree} does. */
export async function listTrash(scopeId: string, epochs: EpochKeys, tree: TreeNode[]): Promise<TrashedNode[]> {
  const rows = await withRefresh(() => contentApi().listTrashedNodes({ scopeId }))
  return rows.map((r: TrashedNodeSummary) => {
    const parentId = r.parentId ?? null
    const entryKey = entryKeyOf(scopeId, epochs, tree, { parentId, epoch: r.epoch })
    return {
      id: r.id,
      parentId,
      kind: r.kind,
      epoch: r.epoch,
      placementVersion: r.placementVersion,
      name: openName(entryKey, { scopeId, nodeId: r.id, epoch: r.epoch, placementVersion: r.placementVersion }, r.encryptedName),
      deletedAt: r.deletedAt,
      purgeAfter: r.purgeAfter,
    }
  })
}

/**
 * Restore a trashed subtree. The client **must** supply the slug token: it is a blinded MAC under the parent's key, so
 * the server cannot recompute one, and the delete consumed the original when it freed the name. It is derived under
 * the **live** parent's content key (ADR 0080 Decision 8), resolved from the loaded `tree`; at the scope root under
 * the node's **own** epoch key — a restore does not re-tag the epoch (ADR 0024), unlike a placement into the root,
 * which seals at the current one ({@link placementEpoch}).
 *
 * `newName` is the A1 retry after a `slug_taken`: a node created while this one sat in the trash already holds
 * the name, so the user picks another. The re-sealed name travels in the SAME request as the token derived from
 * it — sent as two calls, the node would be briefly live carrying a slug its own name does not produce.
 */
export async function restoreNode(
  scopeId: string,
  epochs: EpochKeys,
  tree: TreeNode[],
  node: { id: string; parentId: string | null; epoch: number; placementVersion: number; name: string },
  newName?: string,
): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  const key = entryKeyOf(scopeId, epochs, tree, node)
  const name = newName ?? node.name
  // A restore is not a placement: the node comes back under the parent it left, its key wrap untouched, so the version
  // does not move and a re-sealed name binds the one the node already has (ADR 0080 Decision 3).
  const address = { scopeId, nodeId: node.id, epoch: node.epoch, placementVersion: node.placementVersion }
  await withRefresh(() =>
    contentApi().restoreNode({
      scopeId,
      nodeId: node.id,
      restoreNodeRequest: {
        slugToken: slugTokenFor(key, parentSlot, name),
        encryptedName: newName === undefined ? undefined : sealName(key, address, newName),
      },
    }),
  )
}
