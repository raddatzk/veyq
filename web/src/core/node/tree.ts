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
// **Every seal names its node (ADR 0072).** A name and a `K_node` wrap are bound to (scope, node, epoch, placement
// revision) — the node id the client mints at creation, the epoch it is sealed at, and the revision of its placement
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
import { registry } from '../applications/registry'
import { HttpStatus, isCodedError, isHttpStatus } from '../../shared/httpError'
import { expectMintedId } from './mintedId'
import { identityEntryKey } from '../sharing/dropKey'

/** Which node a seal belongs to: its scope, its id, the epoch it is sealed at and the placement revision the wire stores
 *  it at (ADR 0072, ADR 0080 Decision 3). */
export type NodeAddress = { scopeId: string; nodeId: string; epoch: number; placementRevision: number }
const nameContext = (a: NodeAddress) =>
  ({ purpose: 'node-name', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.epoch, placementRevision: a.placementRevision }) as const
const contentKeyContext = (a: NodeAddress) =>
  ({ purpose: 'content-key', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.epoch, placementRevision: a.placementRevision }) as const
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
  /** Curated metadata (date now, place later) sealed under this node's `K_node` (cr-0067); null when unset. */
  wrappedMeta: string | null
  /** The revision [wrappedMeta] is at — what a meta write states it was built from (ADR 0077). */
  metaRevision: number
  /** The placement revision the node's name and key wrap are sealed at — what a move or rename states it was built on
   *  (ADR 0080 Decision 3). */
  placementRevision: number
  /** When the server stored the node, in epoch milliseconds (`NodeSummary.createdAt`): server-observed, set once and
   *  kept by every rename, move, trash and restore. Siblings are ordered by it, then by id (`location.ts`
   *  `compareByCreation`). */
  createdAt: number
  /** The node's drop private key sealed under its content key (a WRITE_ONLY link's root, ADR 0031 point 7), if any. */
  sealedDropPrivateKey?: string | null
  /** A guest's identity folder only (ADR 0080 decision 11): the identity whose `K_up` its entry lives under, and that
   *  `K_up` wrapped to the parent's drop public key ({@link childEntryKey}). */
  shareIdentityId?: string | null
  wrappedIdentityKey?: string | null
}

/** What {@link childEntryKey} reads of a parent and of a child. */
type DropParent = { id: string; sealedDropPrivateKey?: string | null }
type IdentityChild = { shareIdentityId?: string | null; wrappedIdentityKey?: string | null }

/**
 * The key a child's directory entry (name, slug, content-key wrap) lives under, given its parent's content key: that
 * key itself (ADR 0080 Decision 2) — except for a guest's **identity folder** (ADR 0080 decision 11), whose entry the
 * WRITE_ONLY guest sealed under its own identity key `K_up`, opened here through the parent's drop key
 * (`core/sharing/dropKey.ts`). Throws `OpenRefused` when that chain does not open.
 */
export function childEntryKey(scopeId: string, parent: DropParent, parentKey: Uint8Array, child: IdentityChild): Uint8Array {
  if (!child.shareIdentityId) return parentKey
  if (!parent.sealedDropPrivateKey || !child.wrappedIdentityKey) throw new v1.OpenRefused('content-key')
  return identityEntryKey(
    scopeId,
    { id: parent.id, key: parentKey, sealedDropPrivateKey: parent.sealedDropPrivateKey },
    { identityId: child.shareIdentityId, wrappedIdentityKey: child.wrappedIdentityKey },
  )
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
/** The blinded slug token of `name` for a node of `kind` in the slot `parentSlot` (the parent's id, the scope id at the
 *  root), keyed by the key the entry lives under (ADR 0080 Decision 2): unique per parent, stable per parent key. The
 *  name is folded by ITS kind's function (`registry.slugFor`, inbox #350: a container's `slugify`, a file's stricter
 *  `fileNameSlug`), so every write of one node — create, rename or move, restore, a guest's — must pass that node's
 *  kind, or its token stops matching the one it was stored with. The server's uniqueness is per token among siblings,
 *  whatever their kinds: a folder `a.b` (slug `a-b`) and a file `a.b` (slug `a.b`) do not collide, a folder `a.b` and a
 *  file `a-b` do. */
export function slugTokenFor(entryKey: Uint8Array, parentSlot: string, kind: NodeKind, name: string): string {
  return b64std(v1.slugToken(entryKey, parentSlot, registry.slugFor(kind, name)))
}

/** The fields of a listed row that its directory entry and key chain need. */
type EntryRow = {
  id: string; parentId: string | null; epoch: number; placementRevision: number; encryptedName: string; wrappedContentKey: string
} & DropParent & IdentityChild

/**
 * Open every row's name **top-down in one pass** (ADR 0080 Decision 2): a scope-root entry under its own epoch's
 * `K_SC` (ADR 0024), every other one under its parent's content key, opened one level up on the way down. The keys
 * live only in this call. A row whose chain does not reach the scope root (its parent is not listed, or a served
 * parent cycle) has no key to open its name with: it is refused like a name that does not open (`OpenRefused`), and
 * so is a name or a wrap the server moved onto another row.
 *
 * **What a guest sealed never fails the tree** (ADR 0080 decision 11): a guest's identity folder and everything below
 * it carry entries a WRITE_ONLY guest sealed — its identity key, its folder's name and key, its files'. One that does
 * not open (a guest's garbage, a wrap of another identity) leaves that folder's subtree out of the result instead of
 * refusing the member's whole space; every other row keeps the strict rule above.
 */
function openEntries(scopeId: string, epochs: EpochKeys, rows: EntryRow[]): Map<string, string> {
  const children = new Map<string | null, EntryRow[]>()
  for (const r of rows) children.set(r.parentId, [...(children.get(r.parentId) ?? []), r])
  const names = new Map<string, string>()
  let leftOut = 0
  const countSubtree = (id: string): number => 1 + (children.get(id) ?? []).reduce((n, c) => n + countSubtree(c.id), 0)
  type Item = { row: EntryRow; parent: EntryRow | null; parentKey: Uint8Array; guestSealed: boolean }
  const level: Item[] = (children.get(null) ?? []).map((row) => ({ row, parent: null, parentKey: keyAt(epochs, row.epoch), guestSealed: false }))
  while (level.length) {
    const { row, parent, parentKey, guestSealed } = level.shift()!
    const inGuestFolder = guestSealed || !!row.shareIdentityId
    const node = { scopeId, nodeId: row.id, epoch: row.epoch, placementRevision: row.placementRevision }
    let key: Uint8Array
    try {
      const entryKey = parent ? childEntryKey(scopeId, parent, parentKey, row) : parentKey
      names.set(row.id, openName(entryKey, node, row.encryptedName))
      key = openContentKey(scopeId, entryKey, row)
    } catch (e) {
      if (!inGuestFolder) throw e
      names.delete(row.id)
      leftOut += countSubtree(row.id)
      continue
    }
    for (const child of children.get(row.id) ?? []) level.push({ row: child, parent: row, parentKey: key, guestSealed: inGuestFolder })
  }
  if (names.size + leftOut !== new Set(rows.map((r) => r.id)).size) throw new v1.OpenRefused('node-name') // a row no chain reaches
  return names
}

/** The scope's rows as the server lists them (`parentId` normalised to null), still sealed. */
export async function fetchTreeRows(scopeId: string): Promise<NodeSummary[]> {
  return (await withRefresh(() => contentApi().listNodes({ scopeId }))).map((r: NodeSummary) => ({ ...r, parentId: r.parentId ?? null }))
}

/** The scope's rows with the tree revision they were read at (the listing's `ETag`, ADR 0081; `null` when the server
 *  names none). */
export interface TreeRead {
  rows: NodeSummary[]
  etag: string | null
}

/**
 * The scope's rows as a conditional read (ADR 0081): with `ifNoneMatch` — the tag of the last read — an unchanged scope
 * answers `304` and this resolves `null` (nothing read, nothing to open); otherwise the rows and their new tag. Other
 * failures reject. The live refresh asks this way; a plain load passes no tag and so always gets rows.
 */
export async function fetchTree(scopeId: string, ifNoneMatch?: string | null): Promise<TreeRead | null> {
  try {
    const res = await withRefresh(() => contentApi().listNodesRaw({ scopeId, ifNoneMatch: ifNoneMatch ?? undefined }))
    const rows = (await res.value()).map((r: NodeSummary) => ({ ...r, parentId: r.parentId ?? null }))
    return { rows, etag: res.raw.headers.get('ETag') }
  } catch (e) {
    if (ifNoneMatch && isHttpStatus(e, HttpStatus.notModified)) return null
    throw e
  }
}

/** A string that differs whenever any listed row differs in a way a view can show: the shape (parent, kind, order), the
 *  sealed entry and its placement revision, the sealed meta and its revision. Ciphertext and ids only — it is compared
 *  for equality, never read — so a live poll (`treeStore.pollTree`) can tell "nothing changed" from "something did"
 *  without opening a single name. A row's encrypted name changes with every rename (a fresh nonce), so nothing a
 *  rename or a move writes can leave it equal. */
export function treeFingerprint(rows: readonly NodeSummary[]): string {
  return rows
    .map((r) =>
      [r.id, r.parentId ?? '', r.kind, r.orderIndex, r.epoch, r.placementRevision, r.metaRevision, r.encryptedName, r.wrappedContentKey, r.wrappedMeta ?? '', r.shareIdentityId ?? ''].join('|'),
    )
    .sort()
    .join('\n')
}

/** Open listed rows into the decrypted tree: each name under the key its entry lives under ({@link openEntries}). */
export function openTree(scopeId: string, epochs: EpochKeys, rows: NodeSummary[]): TreeNode[] {
  const names = openEntries(scopeId, epochs, rows.map((r) => ({ ...r, parentId: r.parentId ?? null })))
  return rows.filter((r) => names.has(r.id)).map((r) => ({
    id: r.id,
    parentId: r.parentId ?? null,
    kind: r.kind,
    orderIndex: r.orderIndex,
    epoch: r.epoch,
    name: names.get(r.id)!,
    wrappedContentKey: r.wrappedContentKey,
    wrappedMeta: r.wrappedMeta ?? null,
    metaRevision: r.metaRevision,
    placementRevision: r.placementRevision,
    createdAt: r.createdAt.getTime(),
    sealedDropPrivateKey: r.sealedDropPrivateKey ?? null,
    shareIdentityId: r.shareIdentityId ?? null,
    wrappedIdentityKey: r.wrappedIdentityKey ?? null,
  }))
}

/** List the scope's tree, each name opened under the key its entry lives under ({@link openEntries}): a scope-root
 *  entry under **its own** epoch key `E_{node.epoch}` (ADR 0024), every other under its parent's content key — so a
 *  tree spanning several epochs (post-rotation) decrypts fully for a current member. */
export async function listTree(scopeId: string, epochs: EpochKeys): Promise<TreeNode[]> {
  return openTree(scopeId, epochs, await fetchTreeRows(scopeId))
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
  // The client mints the node's id, so its name, K_node wrap and meta are all sealed bound to it (ADR 0072), at the
  // placement revision a new node has (0, ADR 0080 Decision 3).
  const node = { scopeId, nodeId: crypto.randomUUID(), epoch: epochs.current, placementRevision: 0 }
  const wrappedContentKey = wrapContentKey(entryKey, node, contentKey)
  const res = await withRefresh(() =>
    contentApi().createNode({
      scopeId,
      createNodeRequest: {
        nodeId: node.nodeId,
        parentId: parentId ?? undefined,
        kind,
        encryptedName: sealName(entryKey, node, name),
        slugToken: slugTokenFor(entryKey, parentSlot, kind, name),
        orderIndex: 0,
        epoch: epochs.current,
        wrappedContentKey,
        wrappedMeta: metaSealer?.(contentKey, node), // sealed under the just-minted K_node (e.g. a pre-armed location)
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
 * a wrap, ADR 0080 Decision 1). Bound to (scope, the node's id, its epoch, its placement revision) — the node the caller
 * addresses, at the revision listed with it — so a wrap the server moved onto this row from another node, or one a move
 * superseded, throws `OpenRefused` instead of yielding a key. For a node at arbitrary depth, resolve `parentKey` with
 * [nodeContentKey].
 */
export function openContentKey(
  scopeId: string, parentKey: Uint8Array, node: { id: string; epoch: number; placementRevision: number; wrappedContentKey: string },
): Uint8Array {
  const address = { scopeId, nodeId: node.id, epoch: node.epoch, placementRevision: node.placementRevision }
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
  return contentKeyChain(scopeId, epochs, tree, nodeId)[0] ?? null
}

/**
 * The content keys of `nodeId` and of every ancestor up to its scope-root node, NEAREST FIRST (`[0]` is the node's own,
 * {@link nodeContentKey}), opened top-down as there. Empty when the node is not in the tree or its chain does not reach
 * the scope root. What a member reads that was sealed under an ANCESTOR's key needs it: a folder link's guest names
 * itself under the folder's key, and its name is read on a Memory below (`features/memory/mediaBuckets.ts`).
 */
export function contentKeyChain(scopeId: string, epochs: EpochKeys, tree: TreeNode[], nodeId: string): Uint8Array[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  // collect the chain from the node up to the scope root (nearest-last → reverse to top-down)
  const chain: TreeNode[] = []
  for (let cur = byId.get(nodeId); cur && chain.length <= tree.length; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    chain.unshift(cur) // the length bound stops a served parent cycle, which then reaches no root below
  }
  if (!chain.length || chain[0].parentId !== null) return [] // an ancestor missing from the tree: no chain
  let key = keyAt(epochs, chain[0].epoch) // the scope-root node's key is wrapped under its own epoch's K_SC
  const keys: Uint8Array[] = []
  chain.forEach((node, i) => {
    // an identity folder's wrap is under its guest's K_up, opened through the parent's drop key (ADR 0080 decision 11)
    const entryKey = i === 0 ? key : childEntryKey(scopeId, chain[i - 1], key, node)
    keys.unshift((key = openContentKey(scopeId, entryKey, node)))
  })
  return keys
}

/** The key `node`'s directory entry (name, slug token) and its own key wrap live under (ADR 0080 Decisions 1 and 2):
 *  its parent's content key, or `E_{node.epoch}` for a scope-root node. Null when the parent's chain does not open. */
export function parentContentKey(
  scopeId: string, epochs: EpochKeys, tree: TreeNode[], node: { parentId: string | null; epoch: number } & IdentityChild,
): Uint8Array | null {
  if (node.parentId === null) return keyAt(epochs, node.epoch)
  const parentKey = nodeContentKey(scopeId, epochs, tree, node.parentId)
  const parent = tree.find((n) => n.id === node.parentId)
  if (!parentKey || !parent) return null
  // a guest's identity folder: its entry lives under the guest's K_up, not the parent's key (ADR 0080 decision 11)
  return childEntryKey(scopeId, parent, parentKey, node)
}

/** {@link parentContentKey} for a write or a read that cannot go on without it: a parent whose chain does not open has
 *  no key to seal or open the entry under, refused like a name that does not open. */
function entryKeyOf(
  scopeId: string, epochs: EpochKeys, tree: TreeNode[], node: { parentId: string | null; epoch: number } & IdentityChild,
): Uint8Array {
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
 * key, all bound to the NEXT placement revision (`placementRevision + 1`), which the server compare-and-sets against the
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
  const sealedAt = { scopeId, nodeId, epoch, placementRevision: node.placementRevision + 1 }
  await withRefresh(() =>
    contentApi().updateNode({
      scopeId,
      nodeId,
      updateNodeRequest: {
        parentId: to.parentId ?? undefined,
        wrappedContentKey: wrapContentKey(destinationKey, sealedAt, contentKey),
        encryptedName: sealName(destinationKey, sealedAt, name),
        slugToken: slugTokenFor(destinationKey, to.parentId ?? scopeId, node.kind, name),
        epoch,
        placementRevision: node.placementRevision,
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

/** One soft-deleted subtree ROOT in the space's trash (a deleted node's own root, or a descendant a restore left
 *  behind because its name was freed, see {@link listTrash}), its name decrypted client-side. */
export type TrashedNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  epoch: number
  /** The placement revision its name is sealed at; a trash and a restore leave it alone. */
  placementRevision: number
  name: string
  /** When the subtree was deleted. */
  deletedAt: Date
  /** When the scheduled job erases it for good — derived server-side from the operator's window, not stored. */
  purgeAfter: Date
  /** Whether the node itself holds a blob in one of core's attachment slots (the server's `attached`): what tells an
   *  empty node of a kind whose content is its attachment apart (`KindDescriptor.emptyInTrash`, inbox #391). */
  attached: boolean
} & IdentityChild

/** The space's node trash: the ROOT of each deleted subtree (the descendants went with it and return with it — except
 *  one whose own name an earlier delete freed: a restore of its parent leaves it in the trash, where it is a root of
 *  its own from then on, inbox #357), each name opened under the key its entry lives under — its **live** parent's
 *  content key, resolved from the loaded `tree` (a trashed root's parent is always live, or the scope root), or **its
 *  own** epoch key `E_{node.epoch}` at the scope root — exactly as {@link listTree} does. So after a restore the list is
 *  read against the RELOADED tree: the parent that just came back is in no older one, and its left-behind child's
 *  entry opens under no key there (`OpenRefused`). */
export async function listTrash(scopeId: string, epochs: EpochKeys, tree: TreeNode[]): Promise<TrashedNode[]> {
  const rows = await withRefresh(() => contentApi().listTrashedNodes({ scopeId }))
  return rows.flatMap((r: TrashedNodeSummary) => {
    const parentId = r.parentId ?? null
    const identity = { shareIdentityId: r.shareIdentityId ?? null, wrappedIdentityKey: r.wrappedIdentityKey ?? null }
    let name: string
    try {
      const entryKey = entryKeyOf(scopeId, epochs, tree, { parentId, epoch: r.epoch, ...identity })
      name = openName(entryKey, { scopeId, nodeId: r.id, epoch: r.epoch, placementRevision: r.placementRevision }, r.encryptedName)
    } catch (e) {
      // a guest-sealed entry that does not open is left out, never failing the member's trash (ADR 0080 decision 11)
      if (identity.shareIdentityId) return []
      throw e
    }
    return [{
      id: r.id,
      parentId,
      kind: r.kind,
      epoch: r.epoch,
      placementRevision: r.placementRevision,
      ...identity,
      name,
      deletedAt: r.deletedAt,
      purgeAfter: r.purgeAfter,
      attached: r.attached,
    }]
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
  node: { id: string; parentId: string | null; kind: NodeKind; epoch: number; placementRevision: number; name: string } & IdentityChild,
  newName?: string,
): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  const key = entryKeyOf(scopeId, epochs, tree, node)
  const name = newName ?? node.name
  // A restore is not a placement: the node comes back under the parent it left, its key wrap untouched, so the revision
  // does not move and a re-sealed name binds the one the node already has (ADR 0080 Decision 3).
  const address = { scopeId, nodeId: node.id, epoch: node.epoch, placementRevision: node.placementRevision }
  await withRefresh(() =>
    contentApi().restoreNode({
      scopeId,
      nodeId: node.id,
      restoreNodeRequest: {
        slugToken: slugTokenFor(key, parentSlot, node.kind, name),
        encryptedName: newName === undefined ? undefined : sealName(key, address, newName),
      },
    }),
  )
}
