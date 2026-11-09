// en.platform.node-tree (web) — the encrypted node tree data layer. Operator-blind: EVERY node — FOLDER included
// (ADR 0080 Decision 1) — carries a fresh random content key wrapped under its PARENT's content key: K_SC for a
// scope-root node, the parent node's key below it, whatever the parent's kind (a folder's, a Memory's, a
// chapter's). Recursive keys, the share grain (ADR 0012, cr-0060, ADR 0080). A node's **directory entry** lives
// under that same parent key (ADR 0080 Decision 2): its name is sealed under it and its slug token is a blinded keyed
// MAC under it. So whoever holds a node's key reads every name below it — a share guest included — and nothing
// above it; a member, holding K_SC, opens the whole tree top-down in one pass ({@link listTree}).
//
// **Epochs (ADR 0115, ADR 0114 decisions 3-4).** A scope has ONE key at a time (its {@link ScopeKey}); a rotation
// re-seals every scope-root entry under the new one in its commit. So a scope-root entry (name, slug, key wrap) opens and
// is sealed under the one key the caller holds, and an entry below the root under its parent's key. A node carries two
// numbers: its own `epoch` (its key's: 0 at its mint, +1 at every replacement — what every write sealing under its key
// states as `nodeEpoch` / `parentEpoch`), and its `scopeEpoch` (the scope's epoch when its key was minted — what its
// `content-key` and `node-name` seals bind, and what the device floor reads).
//
// **Every seal names its node (ADR 0072).** A name and a `K_node` wrap are bound to (scope, node, scope epoch, placement
// revision) — the node id the client mints at creation, the scope epoch its key was minted under, and the revision of its placement
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
import { zeroScopeKey, type ScopeKey } from '../../features/spaces/epochs'
import { openScopeById } from '../../features/spaces/scopes'
import { registry } from '../applications/registry'
import { HttpStatus, isCodedError, isHttpStatus } from '../../shared/httpError'
import { expectMintedId } from './mintedId'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import { assertNodeKeyAtFloor, nodeKeyForWrite } from './rekey/floor'
import { pullRekeyPath } from './rekey/rekeyScope'
import { identityEntryKey } from '../sharing/dropKey'

/** Which node a seal belongs to: its scope, its id, the scope epoch its key was minted under (`scopeEpoch`) and the
 *  placement revision the wire stores it at (ADR 0072, ADR 0080 Decision 3, ADR 0115 decision 4). */
export type NodeAddress = { scopeId: string; nodeId: string; scopeEpoch: number; placementRevision: number }
// The seal's binding field is named `epoch` (`seal.ts`) and holds the node's `scopeEpoch`: fields are encoded by position.
const nameContext = (a: NodeAddress) =>
  ({ purpose: 'node-name', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.scopeEpoch, placementRevision: a.placementRevision }) as const
const contentKeyContext = (a: NodeAddress) =>
  ({ purpose: 'content-key', scopeId: a.scopeId, nodeId: a.nodeId, epoch: a.scopeEpoch, placementRevision: a.placementRevision }) as const
const decoder = new TextDecoder()

/** A decrypted tree node for the UI. `name` is decrypted client-side; the server never saw it. */
export type TreeNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  orderIndex: number
  /** The node's own key's epoch (ADR 0114 decision 3): 0 at its mint, +1 at each replacement. What every write that
   *  seals under its key states (`nodeEpoch`, `parentEpoch`). */
  epoch: number
  /** The scope epoch its key was minted under (ADR 0115 decision 4): what its entry's seals bind and the device floor
   *  reads (`core/node/rekey/floor.ts`). */
  scopeEpoch: number
  name: string
  /** The name as the wire serves it (the `node-name` field envelope): what a report hands over its `K_field` for
   *  (uc.moderation.report). Absent where a source does not carry it. */
  encryptedName?: string
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
  /** A moderator blocked this node's content, or a node above it (uc.moderation.take-a-measure): the level shows the
   *  placeholder "Blocked by the operator" and the node does not open. Absent: not blocked. */
  blockedByOperator?: boolean
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

/** Seal a node's name (the `node-name` field, ADR 0012), bound to the node, under a field key of its own derived from
 *  the key its **directory entry** lives under: its parent's content key, or the scope key for a scope-root node
 *  (ADR 0080 Decision 2, ADR 0093 § 9). A group's name and a share's Memory name are their own purposes
 *  (`group-name`, `share-memory-name`, ADR 0072). */
export function sealName(entryKey: Uint8Array, node: NodeAddress, name: string): string {
  return b64std(v1.sealField(nameContext(node), entryKey, v1.utf8(name)))
}
/** Open a node's name with the key its entry lives under (the counterpart to {@link sealName}). */
export function openName(entryKey: Uint8Array, node: NodeAddress, encryptedName: string): string {
  return decoder.decode(v1.openField(nameContext(node), entryKey, fromB64std(encryptedName)))
}
/** The blinded slug token of `name` for a node of `kind` in the slot `parentSlot` (the parent's id, the scope id at the
 *  root), keyed by the key the entry lives under (ADR 0080 Decision 2): unique per parent, stable per parent key. Its
 *  input is `registry.tokenInputFor(kind, name)` — the name folded by ITS kind's function (inbox #350: a container's
 *  `slugify`, a file's stricter `fileNameSlug`), behind its application's route slug where it claimed one
 *  (`memories/rom`, ADR 0080 decision 16) — so every write of one node — create, rename or move,
 *  restore, a re-key, a guest's — must pass that node's kind, or its token stops matching the one it was stored with.
 *  The server's uniqueness is per token among siblings, so a name is unique within its route group: a Memory and a Pot
 *  `Rom` coexist, and so do a folder `a.b` (slug `a-b`) and a file `a.b` (slug `a.b`); a folder `a.b` and a file `a-b`
 *  collide. */
export function slugTokenFor(entryKey: Uint8Array, parentSlot: string, kind: NodeKind, name: string): string {
  return b64std(v1.slugToken(entryKey, parentSlot, registry.tokenInputFor(kind, name)))
}

/** The fields of a listed row that its directory entry and key chain need. */
type EntryRow = {
  id: string; parentId: string | null; scopeEpoch: number; placementRevision: number; encryptedName: string; wrappedContentKey: string
} & DropParent & IdentityChild

/**
 * Open every row's name **top-down in one pass** (ADR 0080 Decision 2): a scope-root entry under the scope's one key
 * (ADR 0115 decision 1), every other one under its parent's content key, opened one level up on the way down. The keys
 * live only in this call. A row whose chain does not reach the scope root (its parent is not listed, or a served
 * parent cycle) has no key to open its name with: it is refused like a name that does not open (`OpenRefused`), and
 * so is a name or a wrap the server moved onto another row.
 *
 * **What a guest sealed never fails the tree** (ADR 0080 decision 11): a guest's identity folder and everything below
 * it carry entries a WRITE_ONLY guest sealed — its identity key, its folder's name and key, its files'. One that does
 * not open (a guest's garbage, a wrap of another identity) leaves that folder's subtree out of the result instead of
 * refusing the member's whole space; every other row keeps the strict rule above.
 *
 * **One row per step** (the search's build in slices, ADR 0125 § Consequences): it pauses after each row, so a caller that must not hold the main thread
 * for a whole big tree (the search's index, {@link openTreeSteps}) runs it in slices; {@link openTree} runs it through.
 */
function* openEntrySteps(scopeId: string, epochs: ScopeKey, rows: EntryRow[]): Generator<void, Map<string, string>, void> {
  const children = new Map<string | null, EntryRow[]>()
  for (const r of rows) children.set(r.parentId, [...(children.get(r.parentId) ?? []), r])
  const names = new Map<string, string>()
  let leftOut = 0
  const countSubtree = (id: string): number => 1 + (children.get(id) ?? []).reduce((n, c) => n + countSubtree(c.id), 0)
  type Item = { row: EntryRow; parent: EntryRow | null; parentKey: Uint8Array; guestSealed: boolean }
  const level: Item[] = (children.get(null) ?? []).map((row) => ({ row, parent: null, parentKey: epochs.key, guestSealed: false }))
  while (level.length) {
    const { row, parent, parentKey, guestSealed } = level.shift()!
    const inGuestFolder = guestSealed || !!row.shareIdentityId
    const node = { scopeId, nodeId: row.id, scopeEpoch: row.scopeEpoch, placementRevision: row.placementRevision }
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
    yield
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
 *  sealed entry and its placement revision, the sealed meta and its revision, and the operator's block — a moderator's
 *  block or lift changes no ciphertext, and a fingerprint without it dropped the poll that carried one (inbox #503).
 *  Ciphertext, ids and that flag only — it is compared for equality, never read — so a live poll (`treeStore.pollTree`)
 *  can tell "nothing changed" from "something did" without opening a single name. A row's encrypted name changes with
 *  every rename (a fresh nonce), so nothing a rename or a move writes can leave it equal. A row's drop key and an identity
 *  folder's wrapped `K_up` are in it too (inbox #506): opening an identity folder reads them from the rows held
 *  (`childEntryKey`), and a node's first WRITE_ONLY link sets the drop key and nothing else (a bump site of ADR 0081). */
export function treeFingerprint(rows: readonly NodeSummary[]): string {
  return rows
    .map((r) =>
      [r.id, r.parentId ?? '', r.kind, r.orderIndex, r.epoch, r.scopeEpoch, r.placementRevision, r.metaRevision, r.encryptedName, r.wrappedContentKey, r.wrappedMeta ?? '', r.shareIdentityId ?? '', r.wrappedIdentityKey ?? '', r.sealedDropPrivateKey ?? '', r.blockedByOperator].join('|'),
    )
    .sort()
    .join('\n')
}

/** A generator's steps run through to its result, in one go. */
export function runThrough<T>(steps: Generator<void, T, void>): T {
  for (;;) {
    const step = steps.next()
    if (step.done) return step.value
  }
}

/** Open listed rows into the decrypted tree: each name under the key its entry lives under ({@link openEntrySteps}). */
export function openTree(scopeId: string, epochs: ScopeKey, rows: NodeSummary[]): TreeNode[] {
  return runThrough(openTreeSteps(scopeId, epochs, rows))
}

/** {@link openTree} one row per step (see {@link openEntrySteps}): the same tree, for a caller that runs it in slices. */
export function* openTreeSteps(scopeId: string, epochs: ScopeKey, rows: NodeSummary[]): Generator<void, TreeNode[], void> {
  const names = yield* openEntrySteps(scopeId, epochs, rows.map((r) => ({ ...r, parentId: r.parentId ?? null })))
  return rows.filter((r) => names.has(r.id)).map((r) => ({
    id: r.id,
    parentId: r.parentId ?? null,
    kind: r.kind,
    orderIndex: r.orderIndex,
    epoch: r.epoch,
    scopeEpoch: r.scopeEpoch,
    name: names.get(r.id)!,
    encryptedName: r.encryptedName,
    wrappedContentKey: r.wrappedContentKey,
    wrappedMeta: r.wrappedMeta ?? null,
    metaRevision: r.metaRevision,
    placementRevision: r.placementRevision,
    createdAt: r.createdAt.getTime(),
    sealedDropPrivateKey: r.sealedDropPrivateKey ?? null,
    shareIdentityId: r.shareIdentityId ?? null,
    wrappedIdentityKey: r.wrappedIdentityKey ?? null,
    blockedByOperator: r.blockedByOperator,
  }))
}

/** List the scope's tree, each name opened under the key its entry lives under ({@link openEntrySteps}): a scope-root
 *  entry under the scope's one key, every other under its parent's content key. */
export async function listTree(scopeId: string, epochs: ScopeKey): Promise<TreeNode[]> {
  return openTree(scopeId, epochs, await fetchTreeRows(scopeId))
}

/**
 * Where a new node goes (ADR 0080 Decision 1): its parent's id, the parent's **content key** — the key the new node's own
 * key wraps under — the parent's own **epoch** as read (`TreeNode.epoch`, its key's: the create states it as
 * `parentEpoch`, ADR 0114 decision 3) and its `scopeEpoch` (the device floor's, `core/node/rekey/floor.ts`); or `null`
 * for the scope root, where it wraps under the scope key. The parent's key comes from {@link nodeContentKey}.
 */
export type NodeParent = { id: string; key: Uint8Array; epoch: number; scopeEpoch: number } | null

/** Create a FOLDER. Like every node it births a random content key wrapped under its parent's (ADR 0080
 *  Decision 1), so whoever holds the folder's key opens every key created below it. Returns its node id. */
export async function createFolder(
  scopeId: string, epochs: ScopeKey, name: string, parent: NodeParent = null, opts: CreateNodeOptions = {},
): Promise<string> {
  return (await createContentNode(scopeId, epochs, parent, FOLDER_KIND, name, undefined, opts)).nodeId
}

/**
 * Create a node of any kind: births a fresh random content key wrapped under the **parent's content key**
 * (`parent.key`; the scope key at the scope root; recursive keys, ADR 0012 / ADR 0080 Decision 1), and seals its
 * directory entry — name + slug token — under that same key (ADR 0080 Decision 2). Its key is minted under the scope's
 * **current epoch** (`scopeEpoch`) and starts at its own epoch 0 (ADR 0114 decision 3). The kind is the caller's: core's {@link createFolder} and an application's own
 * wrappers (Memories: `createMemory` / `createChapter`) call this.
 */
export async function createContentNode(
  scopeId: string, epochs: ScopeKey, parent: NodeParent, kind: NodeKind, name: string,
  metaSealer?: (contentKey: Uint8Array, node: NodeAddress) => string,
  opts: CreateNodeOptions = {},
): Promise<{ nodeId: string; contentKey: Uint8Array; epoch: number; scopeEpoch: number }> {
  const reread = opts.reread
  // the epochs this device holds NOW: a long run (an import, a copy) outlives a rotation, and the node it mints states the
  // epoch the device re-opened the scope at when its pull met that rotation (inbox #670)
  const held = () => opts.epochs?.() ?? epochs
  if (!reread) return createContentNodeOnce(scopeId, held(), parent, kind, name, metaSealer)
  return writeAtNodeEpoch(async (again) =>
    createContentNodeOnce(scopeId, held(), again ? await reread() : parent, kind, name, metaSealer),
  { pull: opts.pull })
}

/** How a create re-reads its parent after an epoch refusal (ADR 0109 point 6) — `node_rekey_pending` or the device
 *  floor (after a pull or a wait), `stale_epoch` (once): the parent's key and epoch as the tree now holds them (null at
 *  the scope root, where only the epoch moves). A member's create also pulls the parent's path forward first (`pull`,
 *  ADR 0111 decision 11), and states the scope's epochs as the device holds them at each attempt (`epochs`; absent, the
 *  ones the caller passed): the pull re-opens a scope this device held behind, and the new node's epoch is that one. */
export type CreateNodeOptions = {
  reread?: () => Promise<NodeParent>
  pull?: () => Promise<boolean>
  epochs?: () => ScopeKey | null
}

/** One create attempt (see {@link createContentNode}). The device floor runs first: a parent whose key is from before
 *  the last rotation this device holds is not sealed under (`NodeKeyBelowFloorError`, layer 2). */
async function createContentNodeOnce(
  scopeId: string, epochs: ScopeKey, parent: NodeParent, kind: NodeKind, name: string,
  metaSealer?: (contentKey: Uint8Array, node: NodeAddress) => string,
): Promise<{ nodeId: string; contentKey: Uint8Array; epoch: number; scopeEpoch: number }> {
  if (parent) assertNodeKeyAtFloor(parent, epochs)
  const parentId = parent?.id ?? null
  const parentSlot = parentId ?? scopeId
  const entryKey = parent?.key ?? epochs.key
  const contentKey = v1.randomBytes(32)
  // The client mints the node's id, so its name, K_node wrap and meta are all sealed bound to it (ADR 0072), at the
  // placement revision a new node has (0, ADR 0080 Decision 3) and the scope epoch its key is minted under.
  const node = { scopeId, nodeId: crypto.randomUUID(), scopeEpoch: epochs.epoch, placementRevision: 0 }
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
        // the scope epoch the node's key is minted under (ADR 0115 decision 4): the server refuses another than current
        scopeEpoch: epochs.epoch,
        // the parent's own epoch as read: the entry is sealed under its key (ADR 0114 decision 3)
        parentEpoch: parent?.epoch,
        wrappedContentKey,
        wrappedMeta: metaSealer?.(contentKey, node), // sealed under the just-minted K_node (e.g. a pre-armed location)
      },
    }),
  )
  // a new key is at its own epoch 0 (ADR 0114 decision 3): what every later write to it states as `nodeEpoch`
  return { nodeId: expectMintedId(node.nodeId, res.nodeId), contentKey, epoch: 0, scopeEpoch: node.scopeEpoch }
}

/** Wrap a node's content key under the key its entry lives under (its parent's, the scope key at a scope-root), bound to the
 *  node's address (the counterpart to {@link openContentKey}). The one place the `content-key` seal is made: a guest's
 *  tree operations (`core/sharing/guestTree.ts`) wrap through it too, so no second copy of the context drifts. */
export function wrapContentKey(entryKey: Uint8Array, node: NodeAddress, contentKey: Uint8Array): string {
  return b64std(v1.seal(contentKeyContext(node), entryKey, contentKey))
}

/**
 * Open a node's content key by unwrapping `node.wrappedContentKey` under its parent's content key
 * (`parentKey` = the scope key for a scope-root node, the parent node's key below it; every node carries a wrap, ADR 0080
 * Decision 1). Bound to (scope, the node's id, its scope epoch, its placement revision) — the node the caller
 * addresses, at the revision listed with it — so a wrap the server moved onto this row from another node, or one a move
 * superseded, throws `OpenRefused` instead of yielding a key. For a node at arbitrary depth, resolve `parentKey` with
 * [nodeContentKey].
 */
export function openContentKey(
  scopeId: string, parentKey: Uint8Array, node: { id: string; scopeEpoch: number; placementRevision: number; wrappedContentKey: string },
): Uint8Array {
  const address = { scopeId, nodeId: node.id, scopeEpoch: node.scopeEpoch, placementRevision: node.placementRevision }
  return v1.open(contentKeyContext(address), parentKey, fromB64std(node.wrappedContentKey))
}

/** Back-compat alias: a scope-root node's key opens under `K_SC`. Prefer [openContentKey] / [nodeContentKey]. */
export const openMemory = openContentKey

/**
 * Resolve a node's content key, walking **every** ancestor top-down (ADR 0080 Decision 1: a folder is a link of the
 * chain like any other node, never transparent). The **scope-root ancestor** opens under the scope key and each node
 * below under its parent's key. Returns null if the node is not in the tree or its chain does not reach the scope root.
 */
export function nodeContentKey(scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string): Uint8Array | null {
  return contentKeyChain(scopeId, epochs, tree, nodeId)[0] ?? null
}

/**
 * `next`, unless it holds the very bytes of `previous` — then `previous`. A host derives the key of the node on screen
 * from its tree, and the tree is re-read under it (a live poll, ADR 0081; a write's own re-read): the key comes out as
 * a new array of the same bytes, and a page that keys anything on it — a gallery's reload, a tab's reset — would run it
 * again for nothing. Fed as a `computed`'s previous value, it keeps the key the same object while it is the same key.
 */
export function sameKeyOr(previous: Uint8Array | null | undefined, next: Uint8Array | null): Uint8Array | null {
  if (!previous || !next || previous.length !== next.length) return next
  for (let i = 0; i < next.length; i++) if (previous[i] !== next[i]) return next
  return previous
}

/**
 * The content keys of `nodeId` and of every ancestor up to its scope-root node, NEAREST FIRST (`[0]` is the node's own,
 * {@link nodeContentKey}), opened top-down as there. Empty when the node is not in the tree or its chain does not reach
 * the scope root. What a member reads that was sealed under an ANCESTOR's key needs it: a folder link's guest names
 * itself under the folder's key, and its name is read on a Memory below (`features/memories/mediaBuckets.ts`).
 */
export function contentKeyChain(scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string): Uint8Array[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  // collect the chain from the node up to the scope root (nearest-last → reverse to top-down)
  const chain: TreeNode[] = []
  for (let cur = byId.get(nodeId); cur && chain.length <= tree.length; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    chain.unshift(cur) // the length bound stops a served parent cycle, which then reaches no root below
  }
  if (!chain.length || chain[0].parentId !== null) return [] // an ancestor missing from the tree: no chain
  let key = epochs.key // the scope-root node's key is wrapped under the scope's one key (ADR 0115 decision 1)
  const keys: Uint8Array[] = []
  chain.forEach((node, i) => {
    // an identity folder's wrap is under its guest's K_up, opened through the parent's drop key (ADR 0080 decision 11)
    const entryKey = i === 0 ? key : childEntryKey(scopeId, chain[i - 1], key, node)
    keys.unshift((key = openContentKey(scopeId, entryKey, node)))
  })
  return keys
}

/** The key `node`'s directory entry (name, slug token) and its own key wrap live under (ADR 0080 Decisions 1 and 2):
 *  its parent's content key, or the scope key for a scope-root node. Null when the parent's chain does not open. */
export function parentContentKey(
  scopeId: string, epochs: ScopeKey, tree: TreeNode[], node: { parentId: string | null } & IdentityChild,
): Uint8Array | null {
  if (node.parentId === null) return epochs.key
  const parentKey = nodeContentKey(scopeId, epochs, tree, node.parentId)
  const parent = tree.find((n) => n.id === node.parentId)
  if (!parentKey || !parent) return null
  // a guest's identity folder: its entry lives under the guest's K_up, not the parent's key (ADR 0080 decision 11)
  return childEntryKey(scopeId, parent, parentKey, node)
}

/** {@link parentContentKey} for a write or a read that cannot go on without it: a parent whose chain does not open has
 *  no key to seal or open the entry under, refused like a name that does not open. */
function entryKeyOf(
  scopeId: string, epochs: ScopeKey, tree: TreeNode[], node: { parentId: string | null } & IdentityChild,
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
 * The epoch a placement states: ALWAYS the node's own (ADR 0114 decision 3) — the placement re-wraps the node's key, and
 * no placement replaces it. The server checks the request's `epoch` against the stored one (`stale_epoch`) and refuses a
 * node due for its re-key (`node_rekey_pending`). The entry's seals bind the node's `scopeEpoch`, which no placement moves.
 */
export function placementEpoch(node: { epoch: number }): number {
  return node.epoch
}

/**
 * The one placement write (ADR 0080 Decision 3): move `nodeId` to `to.parentId`, renaming it to `to.name` when given —
 * a rename is a move to the same parent. The node's own content key, opened from the loaded `tree`, is re-wrapped under
 * the destination parent's key (the scope key at the scope root), and its name and slug token are re-sealed under that
 * key, all bound to the NEXT placement revision (`placementRevision + 1`), which the server compare-and-sets against the
 * version this write states. A rename re-wraps too: the old wrap is bound to the old version and would stop opening.
 *
 * On `409 stale_placement` (a concurrent move or rename of this node landed first) the tree is re-read and the
 * placement rebuilt on the node as it now is, at most {@link PLACEMENT_RETRIES} time(s); then
 * {@link PlacementConflictError}. The two epoch refusals (ADR 0109 point 6) re-read the tree too and rebuild under the
 * keys and epochs it now carries: `node_rekey_pending` (the node or the destination is not re-keyed since the last
 * rotation) after this member's device pulled the path forward (or a wait, when it cannot), `stale_epoch` (re-keyed
 * since this tree was read) once — `writeAtNodeEpoch`. Every other
 * failure (`slug_taken`, `illegal_structure`, …) is the caller's. The server checks structure only (a live destination
 * in the scope, the kind's placement rule, no cycle).
 */
export async function placeNode(
  scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string, to: Placement,
): Promise<void> {
  let current = tree
  // A member's placement pulls forward what it seals under (ADR 0111 decision 11): the destination's path, or the node's
  // own at the scope root (the placement re-wraps its key, which must not be due).
  const pull = pullRekeyPath(scopeId, [to.parentId ?? nodeId])
  for (let attempt = 0; ; attempt++) {
    try {
      await writeAtNodeEpoch(async (reread) => {
        if (reread) current = await listTree(scopeId, epochs)
        await sendPlacement(scopeId, epochs, current, nodeId, to)
      }, { pull })
      return
    } catch (e) {
      if (!(await isCodedError(e, HttpStatus.conflict, 'stale_placement'))) throw e
      if (attempt >= PLACEMENT_RETRIES) throw new PlacementConflictError(nodeId)
      current = await listTree(scopeId, epochs)
    }
  }
}

/** Rename a node in place: {@link placeNode} to its current parent under `newName`. */
export function renameNode(scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string, newName: string): Promise<void> {
  const node = tree.find((n) => n.id === nodeId)
  if (!node) return Promise.reject(new v1.OpenRefused('content-key'))
  return placeNode(scopeId, epochs, tree, nodeId, { parentId: node.parentId, name: newName })
}

/** Move a node under `parentId` (`null` = the scope root), keeping its name: {@link placeNode}. */
export function moveNode(scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string, parentId: string | null): Promise<void> {
  return placeNode(scopeId, epochs, tree, nodeId, { parentId })
}

/** Build and send one placement against `tree` (see {@link placeNode}). */
async function sendPlacement(scopeId: string, epochs: ScopeKey, tree: TreeNode[], nodeId: string, to: Placement): Promise<void> {
  const node = tree.find((n) => n.id === nodeId)
  const contentKey = node ? nodeContentKey(scopeId, epochs, tree, nodeId) : null
  if (!node || !contentKey) throw new v1.OpenRefused('content-key') // its chain does not open: nothing to re-wrap
  const epoch = placementEpoch(node)
  // The key the entry is sealed under, through the device floor (layer 2): at the scope root the scope key — the node's
  // own key, re-wrapped, must be from this scope epoch —; else the destination's key, whose epoch the request states.
  if (to.parentId === null) assertNodeKeyAtFloor(node, epochs)
  const destination = to.parentId === null ? null : nodeKeyForWrite(scopeId, epochs, tree, to.parentId)
  const destinationKey = to.parentId === null ? epochs.key : destination?.key
  if (!destinationKey) throw new v1.OpenRefused('content-key') // a destination whose chain does not open
  const name = to.name ?? node.name
  const sealedAt = { scopeId, nodeId, scopeEpoch: node.scopeEpoch, placementRevision: node.placementRevision + 1 }
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
        // the destination parent's own epoch as read: the entry is sealed under its key (ADR 0114 decision 3)
        parentEpoch: destination?.epoch,
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

/** Permanently delete a trashed subtree now — the scheduled purge brought forward (uc.memory.delete-node, `purgeNode`):
 *  irreversible, and only for a trash ROOT, the row {@link listTrash} lists. The server erases it with everything an
 *  application attached to it; a live node, one inside a trashed subtree, or one already restored or purged answers
 *  `404`. No key is needed: nothing is sealed, the request names the node. */
export async function purgeNode(scopeId: string, nodeId: string): Promise<void> {
  await withRefresh(() => contentApi().purgeNode({ scopeId, nodeId }))
}

/** One soft-deleted subtree ROOT in the space's trash (a deleted node's own root, or a descendant a restore left
 *  behind because its name was freed, see {@link listTrash}), its name decrypted client-side. */
export type TrashedNode = {
  id: string
  parentId: string | null
  kind: NodeKind
  /** Its own key's epoch, and the scope epoch that key was minted under (see {@link TreeNode}). */
  epoch: number
  scopeEpoch: number
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
 *  content key, resolved from the loaded `tree` (a trashed root's parent is always live, or the scope root), or the
 *  scope key at the scope root — exactly as {@link listTree} does. So after a restore the list is
 *  read against the RELOADED tree: the parent that just came back is in no older one, and its left-behind child's
 *  entry opens under no key there (`OpenRefused`). */
export async function listTrash(scopeId: string, epochs: ScopeKey, tree: TreeNode[]): Promise<TrashedNode[]> {
  const rows = await withRefresh(() => contentApi().listTrashedNodes({ scopeId }))
  return rows.flatMap((r: TrashedNodeSummary) => {
    const parentId = r.parentId ?? null
    const identity = { shareIdentityId: r.shareIdentityId ?? null, wrappedIdentityKey: r.wrappedIdentityKey ?? null }
    let name: string
    try {
      const entryKey = entryKeyOf(scopeId, epochs, tree, { parentId, ...identity })
      name = openName(entryKey, { scopeId, nodeId: r.id, scopeEpoch: r.scopeEpoch, placementRevision: r.placementRevision }, r.encryptedName)
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
      scopeEpoch: r.scopeEpoch,
      placementRevision: r.placementRevision,
      ...identity,
      name,
      deletedAt: r.deletedAt,
      purgeAfter: r.purgeAfter,
      attached: r.attached,
      blockedByOperator: r.blockedByOperator,
    }]
  })
}

/**
 * Restore a trashed subtree. The client **must** supply the slug token: it is a blinded MAC under the parent's key, so
 * the server cannot recompute one, and the delete consumed the original when it freed the name. It is derived under
 * the **live** parent's content key (ADR 0080 Decision 8), resolved from the loaded `tree`; at the scope root under
 * the scope key — a restore moves no epoch, and neither does a placement ({@link placementEpoch}).
 *
 * `newName` is the A1 retry after a `slug_taken`: a node created while this one sat in the trash already holds
 * the name, so the user picks another. The re-sealed name travels in the SAME request as the token derived from
 * it — sent as two calls, the node would be briefly live carrying a slug its own name does not produce.
 */
export async function restoreNode(
  scopeId: string,
  epochs: ScopeKey,
  tree: TreeNode[],
  node: RestoredNode,
  newName?: string,
): Promise<void> {
  // A member's restore of what is not re-keyed since a rotation (the entry's parent, or any node it brings back — the
  // server's gate asks for all of them) pulls them forward on this device and restores the node as the re-key left it,
  // re-read from the scope opened anew (ADR 0111 decision 11); `stale_epoch` re-reads the same way, once.
  let at = { epochs, tree, node }
  let opened: ScopeKey | null = null
  try {
    await writeAtNodeEpoch(async (reread) => {
      if (reread) {
        const scope = await openScopeById(scopeId)
        if (!scope) throw new v1.OpenRefused('content-key')
        if (opened) zeroScopeKey(opened)
        opened = scope.epochs
        const live = await listTree(scopeId, opened)
        const again = (await listTrash(scopeId, opened, live)).find((n) => n.id === node.id)
        if (!again) throw new v1.OpenRefused('content-key') // restored or purged meanwhile
        at = { epochs: opened, tree: live, node: again }
      }
      await restoreOnce(scopeId, at.epochs, at.tree, at.node, newName)
    }, { pull: pullRekeyPath(scopeId, [node.id], { subtree: true }) })
  } finally {
    if (opened) zeroScopeKey(opened)
  }
}

/** What a restore reads of the trashed node: a {@link TrashedNode} carries it. */
type RestoredNode = { id: string; parentId: string | null; kind: NodeKind; epoch: number; scopeEpoch: number; placementRevision: number; name: string } & IdentityChild

/** One restore attempt (see {@link restoreNode}). */
async function restoreOnce(scopeId: string, epochs: ScopeKey, tree: TreeNode[], node: RestoredNode, newName?: string): Promise<void> {
  const parentSlot = node.parentId ?? scopeId
  const key = entryKeyOf(scopeId, epochs, tree, node)
  const name = newName ?? node.name
  // A restore is not a placement: the node comes back under the parent it left, its key wrap untouched, so the revision
  // does not move and a re-sealed name binds the one the node already has (ADR 0080 Decision 3).
  const address = { scopeId, nodeId: node.id, scopeEpoch: node.scopeEpoch, placementRevision: node.placementRevision }
  // The own epoch the request states (ADR 0114 decision 3): the live parent's, whose key the entry is re-sealed under,
  // or at the scope root the restored node's own (its entry is under the scope key; a due node restores only re-keyed).
  const parent = node.parentId === null ? node : tree.find((n) => n.id === node.parentId)
  if (!parent) throw new v1.OpenRefused('content-key') // a parent the loaded tree does not hold
  assertNodeKeyAtFloor(parent, epochs) // the device floor (layer 2): never re-seal under a key from before the last rotation
  const parentEpoch = parent.epoch
  await withRefresh(() =>
    contentApi().restoreNode({
      scopeId,
      nodeId: node.id,
      restoreNodeRequest: {
        parentEpoch,
        slugToken: slugTokenFor(key, parentSlot, node.kind, name),
        encryptedName: newName === undefined ? undefined : sealName(key, address, newName),
      },
    }),
  )
}
