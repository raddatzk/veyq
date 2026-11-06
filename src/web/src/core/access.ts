// Who is looking, and through what (inbox #401, ADR 0079 § The registry's contract): the two inputs every application
// view receives in BOTH modes, so one component per kind serves a member and a share guest alike — the guest's page is
// the member's page with functions removed, never a second page.
//
// - `Access` says who is looking and what that session may do in principle: a MEMBER of an unlocked scope (its epoch
//   keys, whether the scope is pending deletion, the instance's sharing capabilities) or a GUEST of a public link (its
//   role, the resolved head, the password token, the naming flow). It carries no position: what is possible HERE is a
//   rights function's answer for a node (below).
// - `TreeSource` is the tree as that session reads it: the member's decrypted scope tree (`treeStore`) or the guest's
//   opened subtree (`core/sharing/guestAccess.ts`, over `walkSubtree`). Navigation always goes through `linkTo`, so a guest's link keeps its `#k=`
//   fragment (src/web/CLAUDE.md, the breadcrumb trap) and a member's stays a slug path in the fragment.
//
// **Rights: core's base, the application narrows** (owner 2026-11-01). What every node may do whatever its kind - be
// shared, renamed, moved, deleted - is ONE answer, core's {@link nodeRights}: who looks (member or guest role), a pending
// deletion, the application switch ({@link applicationWritable}), the instance's public-share leaves, the kind's share
// dialog, a guest's identity folder and the scope root. Each application's rights function - `memoryRights`
// (features/memories/rights.ts), `depotRights` (features/depot/rights.ts), `expensesRights` (features/expenses/rights.ts) -
// and core's own `folderRights` here for the one kind core owns start from that answer and only AND their own conditions
// onto it, never granting what it denies (each one's `rights.test.ts` asserts so over every access shape). All are pure
// and FAIL-CLOSED: every right defaults to false; an unknown mode, role or kind grants nothing. The role matrix each one
// encodes is written down in its `docs/apps/<application>.md` (ADR 0080 decision 6). A right is the matrix's half; the
// other half - that the data source implements the operation at all - is the application's source (Depot's `source.ts`
// since inbox #401 S3, Memories' `source.ts` since S4): a control is shown only where both say yes.
//
// Core imports no application here (`validate_web_core_imports_no_applications.py`).
import type { RouteLocationRaw } from 'vue-router'
import type { ScopeKey } from '../features/spaces/epochs'
import type { ShareRole } from '../api/generated/sharing'
import type { TreeNode } from './node/tree'
import { FOLDER_KIND, type NodeKind } from './node/kind'
import type { PublicShareHead } from './sharing/resolvePublicShare'
import type { GuestIdentity } from './sharing/guestIdentity'
import { nodeContentKey } from './node/tree'
import { displayNameOf, nodeById } from './node/browse'
import { hashForNode } from './node/location'
import { registry } from './applications/registry'

/** A member of an unlocked scope. */
export interface MemberAccess {
  readonly mode: 'member'
  /** The scope every read and write addresses. */
  readonly scopeId: string
  /** The scope's epoch key set (ADR 0024): reads open each node under its own epoch, writes seal under the current one.
   *  Null while the scope is not unlocked — every write right that needs a key is then the view's to disable. */
  readonly epochs: ScopeKey | null
  /** The scope is pending deletion (cr-0090, uc.spaces.scope-access): the server refuses every write during the grace
   *  window, so no write is offered. Reads, downloads, pins and the listing of links stay, and so does revoking a link
   *  (A1: it only closes exposure). */
  readonly readonly: boolean
  /** The instance offers READ public links (`core.public-shares`, ADR 0032, ADR 0088). One boolean per register leaf,
   *  each filled from ONE manifest id by the host: the server computes the AND along the path (a child whose parent is
   *  off is absent from the manifest, ADR 0088 decision 2), so no rights function ANDs two of these. */
  readonly publicShares: boolean
  /** The instance offers READ_WRITE (contribute) public links (`core.public-shares.contribute`). */
  readonly publicSharesContribute: boolean
  /** The instance offers WRITE_ONLY (drop-box) public links and the drop-box node (`core.public-shares.drop-box`). */
  readonly publicSharesDropBox: boolean
  /** The applications switched off for this tenant (uc.platform.switch-application-off): each registered application's
   *  id (`registry.applications()`) whose register entry is absent from the effective set — one `hasCapability` each,
   *  filled by the host. Off, an application is read-only: its rights function offers no write but a delete to the trash
   *  and its restore, and core offers no cover or copy of its nodes ({@link applicationWritable}). */
  readonly applicationsOff: ReadonlySet<string>
}

/** A guest of a public share link. Built by the share page after its gates (`guestAccess`, core/sharing/guestAccess.ts);
 *  no member state is reachable from it. */
export interface GuestAccess {
  readonly mode: 'guest'
  readonly shareId: string
  /** The API base of the instance that serves the link (ADR 0121 decision 5): `''` for this origin's — the share page's —,
   *  another instance's origin for a link a bookmark's page shows in place. Every guest read and write takes it, and what
   *  the guest keeps on this device is keyed by it too, so a guest access is itself the share's address
   *  (`core/sharing/shareAt.ts` `ShareAt`). */
  readonly apiBase: string
  /** The link's access mode — the wire's `ShareRole` (ADR 0079 Consequences, three access modes). */
  readonly role: ShareRole
  /** The guest password token, when the link is password-protected and unlocked. */
  readonly pwToken?: string
  /** The platform half of the resolve: the share, `K_share` (key material), its scope, name and note. */
  readonly head: PublicShareHead
  /** The URL fragment's key (`#k=`, key material): what a guest's tree operation re-reads the link with when a placement
   *  lost a race (`guestTreeContext`). */
  readonly fragmentKey: string
  /** The guest's identity for this link, asking the visitor's name first when it has none; null: the visitor declined.
   *  The ONE naming flow of the page (core's), so no view asks for a name itself. */
  requestGuest(): Promise<GuestIdentity | null>
  /** Re-resolve the link after a guest's write, so what it created lists with its key and name; resolves once `head`
   *  has been replaced (the shell's). */
  reload(): Promise<void>
  /** The live refresh's read (inbox #408, `core/node/useLevelLive.ts`): re-resolve the link QUIETLY and CONDITIONALLY
   *  (ADR 0081: a `304` is no work) and replace `head` only if the subtree changed. Resolves whether the link's state
   *  MOVED (the sections then look again at what lives beside the tree); a link that is gone flips the shell to its
   *  unavailable state — the one thing it does change. It rejects on a failure that says nothing about the link (the
   *  poller backs off) and checks the signal before applying. Absent: the page does not poll. */
  poll?(signal: AbortSignal): Promise<boolean>
}

export type Access = MemberAccess | GuestAccess

/** What a rights function is asked about: the node on screen as core knows it — an opened node, or the folder a level
 *  shows. Built by {@link targetOf}; a test may spell one out. */
export interface RightsTarget {
  /** The node's kind; null at a member's scope root (a guest is never there). */
  readonly kind: NodeKind | null
  /** It is a guest's identity folder in a drop box or lies below one (ADR 0080 decision 11: nothing is placed there but
   *  by that folder's own guest). */
  readonly inIdentityFolder: boolean
  /** Its kind brings a share dialog (the registry's answer, `KindDescriptor.shareDialog`): a share right needs it, so no
   *  view shows a share button whose dialog the host cannot mount. False at the scope root. */
  readonly shareable: boolean
  /** The kind of its parent as the session reads it (inbox #358: a guest moves only what a `FOLDER` lets out, core's
   *  `FolderGuestNodeRules`): null at the scope root (a member's top level); undefined where the session cannot read the
   *  parent — the view's own root (a guest's share root, whose parent lies above the link) and the scope root itself. */
  readonly parentKind?: NodeKind | null
}

/** A node as a tree source serves it: enough to navigate, bucket by kind and ask a right. */
export interface SourceNode {
  readonly id: string
  readonly parentId: string | null
  readonly kind: NodeKind
}

/** The tree as the looking session reads it (see the header). `N` is the node type the source hands out — the member's
 *  `TreeNode`; a view written against the base type works in both modes. */
export interface TreeSource<N extends SourceNode = SourceNode> {
  /** The node the session's view is rooted at: the share root for a guest; null for a member (the scope root is no
   *  node). Navigation never goes above it. */
  readonly rootId: string | null
  /** The node `id`, or null when this session cannot read it. */
  node(id: string): N | null
  /** The children of `id` (null: the member's scope root), in the order they are served. */
  children(id: string | null): readonly N[]
  /** The content key of `id` as the key chain opens it (key material), or null when the chain does not reach it. */
  keyOf(id: string): Uint8Array | null
  /** The name of `id` as its siblings tell it apart (`Anna (2)`, inbox #393) — what a heading and a breadcrumb say. */
  nameOf(id: string): string
  /** Whether `id` is a guest's identity folder or lies below one (null: the scope root, never). */
  inIdentityFolder(id: string | null): boolean
  /** Where node `id` is (null: the root of this view): a member's fragment slug path, or a guest's `?node=<id>` with the
   *  `#k=` fragment kept. Every navigation goes through it. */
  linkTo(id: string | null): RouteLocationRaw
}

/** The rights target of `node` (null: the scope root) as `tree` reads it, its shareability asked of the registry. */
export function targetOf(tree: TreeSource, node: { id: string; kind: NodeKind } | null): RightsTarget {
  if (!node) return { kind: null, inIdentityFolder: false, shareable: false }
  const info = registry.kind(node.kind)
  const parentId = tree.node(node.id)?.parentId ?? null
  return {
    kind: node.kind,
    inIdentityFolder: tree.inIdentityFolder(node.id),
    shareable: info.supported && !!info.shareDialog,
    // The view's root has no parent this session reads (a guest's served root carries `parentId` null, the scope root's
    // marker — so the root is asked first); a parent the tree does not serve is unknown, never the scope root.
    parentKind: node.id === tree.rootId ? undefined : parentId === null ? null : (tree.node(parentId)?.kind ?? undefined),
  }
}

/** Whether a node of `kind` takes writes for `access`: false for a member while the kind's application is switched off
 *  (`MemberAccess.applicationsOff`); true for core's own kinds and a kind nobody named. A guest's page is not told the
 *  switch (the link's resolve carries no tenant setting), so it is true for a guest: the server refuses the write. */
export function applicationWritable(access: Access, kind: NodeKind | null): boolean {
  if (access.mode !== 'member' || kind === null) return true
  const application = registry.applicationOf(kind)
  return application === null || !access.applicationsOff.has(application)
}

/** The member's `Access`, from what the host already knows (see `MemberAccess` for each field). */
export function memberAccess(fields: Omit<MemberAccess, 'mode'>): MemberAccess {
  return { mode: 'member', ...fields }
}

/** The member's `TreeSource` over the decrypted scope tree. Getters, not values: the host's tree, keys and URL scope
 *  token change under it (a reload, a rotation, a scope switch), and every answer reads them anew. */
export function memberTreeSource(from: {
  nodes: () => TreeNode[]
  scopeId: () => string | null
  epochs: () => ScopeKey | null
  /** The URL's scope token (`''` tenant, `~me` personal, a group's token), which a fragment link carries. */
  scope: () => string
}): TreeSource<TreeNode> {
  return {
    rootId: null,
    node: (id) => nodeById(from.nodes(), id),
    children: (id) => from.nodes().filter((n) => (n.parentId ?? null) === id),
    keyOf(id) {
      const scopeId = from.scopeId()
      const epochs = from.epochs()
      return scopeId && epochs ? nodeContentKey(scopeId, epochs, from.nodes(), id) : null
    },
    nameOf(id) {
      const node = nodeById(from.nodes(), id)
      return node ? displayNameOf(from.nodes(), node) : ''
    },
    inIdentityFolder(id) {
      const nodes = from.nodes()
      // Bounded by the tree's size: a served parent cycle stops instead of spinning.
      for (let n = nodeById(nodes, id), steps = 0; n && steps <= nodes.length; n = nodeById(nodes, n.parentId), steps++) {
        if (n.shareIdentityId) return true
      }
      return false
    },
    linkTo: (id) => ({ hash: hashForNode(from.nodes(), id, from.scope()) }),
  }
}

/** The node-generic rights of the node on screen (owner 2026-11-01): core's ONE answer for every kind, the base each
 *  application's rights function - and core's own {@link folderRights} - narrows (AND), never widens. One name per
 *  right, used everywhere: a level row's `standard` and `selection` ({@link rowRightsOf}), a header's
 *  `standardNodeActions` flags. */
export interface NodeRights {
  /** Open the node's share dialog: list and copy its `READ` / `READ_WRITE` links (`core.public-shares`). Listing is a
   *  read, so a pending deletion keeps it; the dialog then mints none and changes none but switching the caller's own
   *  live link off (the host's `NodeShareDialogProps.readonly`: the server refuses every share write during the grace
   *  window but a revoke, uc.spaces.scope-access A1). */
  share: boolean
  /** The share dialog offers the READ_WRITE (contribute) role for a new link (`core.public-shares.contribute`); handed to
   *  the dialog as a prop by the host (`NodeShareDialogProps.contribute`), never read there. */
  shareContribute: boolean
  /** The node's uploader (`WRITE_ONLY`) links (`core.public-shares.drop-box`): Memories' uploader link, a drop box's
   *  links - the same reading of a pending deletion as {@link share}. */
  shareUpload: boolean
  /** Rename the node (core's name dialog). */
  rename: boolean
  /** Move the node elsewhere (core's `MoveNodeDialog`, inbox #358). */
  move: boolean
  /** Delete the node (into the scope's trash). */
  delete: boolean
}

export const NO_NODE_RIGHTS: Readonly<NodeRights> = Object.freeze({
  share: false, shareContribute: false, shareUpload: false, rename: false, move: false, delete: false,
})

/**
 * Core's answer for the node `at` describes; the scope root (`kind` null) is no node and grants nothing. Fail-closed: an
 * unknown mode or role grants nothing.
 *
 * - **Member:** rename and move need a scope not pending deletion (`readonly`) and the node's application switched on
 *   ({@link applicationWritable}); a delete needs only the first - an application switched off keeps the delete to the
 *   trash (the server's exemption). A share needs the application on, a kind whose descriptor brings a share dialog
 *   (`at.shareable`) and ONE instance leaf each - `publicShares`, `publicSharesContribute`, `publicSharesDropBox`, never
 *   two ANDed (the server ANDs the path, ADR 0088) - and survives a pending deletion (listing links is a read). In a
 *   guest's identity folder nothing is renamed and nothing shared (ADR 0080 decision 11): a member only moves out of it
 *   and deletes there.
 * - **Guest:** no share, rename or delete in any role (ADR 0080 decision 6: a guest deletes no node; its rename is the
 *   server's but offered nowhere yet). **`READ_WRITE`** moves a node a `FOLDER` holds between the subtree's folders
 *   (core decides between folders, `FolderGuestNodeRules`) - never the link's root (its parent lies above the link:
 *   `parentKind` unknown), never in or of an identity folder (only a member moves out of a drop box, inbox #377 (a)). A
 *   guest's page is not told the application switch: the server refuses its write.
 */
export function nodeRights(access: Access, at: RightsTarget): NodeRights {
  if (at.kind === null) return NO_NODE_RIGHTS
  if (access.mode === 'member') {
    const on = applicationWritable(access, at.kind)
    const write = !access.readonly && on
    const shareable = on && at.shareable && !at.inIdentityFolder
    return {
      share: shareable && access.publicShares,
      shareContribute: shareable && access.publicSharesContribute,
      shareUpload: shareable && access.publicSharesDropBox,
      rename: write && !at.inIdentityFolder,
      move: write,
      delete: !access.readonly,
    }
  }
  if (access.mode === 'guest' && access.role === 'READ_WRITE') {
    return { ...NO_NODE_RIGHTS, move: at.parentKind === FOLDER_KIND && !at.inIdentityFolder }
  }
  return NO_NODE_RIGHTS
}

/** What a level row's core default actions and a selection read (`LevelRow.standard`, `LevelRow.selection`), from ONE
 *  rights answer for THAT node - so a row's rename, move and delete and its part in a selection never disagree. */
export function rowRightsOf(r: Pick<NodeRights, 'rename' | 'move' | 'delete'>): {
  standard: { rename: boolean; move: boolean; delete: boolean }
  selection: { move: boolean; delete: boolean }
} {
  return { standard: { rename: r.rename, move: r.move, delete: r.delete }, selection: { move: r.move, delete: r.delete } }
}

/** What a level of core's own kind offers (core's `LevelView`: its toolbar, the folder's overview card, the folders
 *  section): {@link NodeRights} for the folder on screen, narrowed, and the level's own. Every field false unless
 *  granted. A folder takes no uploader link (no `WRITE_ONLY` link roots at a folder, `ShareRoots`), so it has no
 *  `shareUpload`. */
export interface FolderRights extends Omit<NodeRights, 'shareUpload'> {
  /** Open a sub-folder. */
  navigate: boolean
  /** Create a folder at this level. */
  createFolder: boolean
  /** Open the scope's node trash (its restore is a write). */
  nodeTrash: boolean
}

const NO_FOLDER_RIGHTS: FolderRights = Object.freeze({
  navigate: false, createFolder: false, rename: false, delete: false, share: false, shareContribute: false, nodeTrash: false,
  move: false,
})

/**
 * Core's matrix for a `FOLDER` level (`kind` FOLDER, or null at a member's scope root): {@link nodeRights} for the
 * folder on screen (nothing at the scope root, which is no folder), narrowed by the folder's own rule, plus what the level
 * itself offers. Fail-closed: any other kind, an unknown mode or role grants nothing.
 *
 * - **Member:** core's answer, but no delete of a guest's identity folder here (a member deletes there file by file -
 *   the application's section - or the folder from its drop box, never the folder's own delete). A folder moves - a
 *   guest's identity folder too: a member's move OUT of a drop box is the one placement it takes (ADR 0080 decision 11,
 *   inbox #377 (a)); nothing moves INTO one (the move dialog offers no such destination, the server refuses it). The level
 *   creates a folder and opens the node trash, neither during a pending deletion nor in an identity folder.
 * - **Guest `READ`:** navigate. **`READ_WRITE`:** navigate, create a folder (core's `FolderGuestNodeRules`), and core's
 *   move. **`WRITE_ONLY`:** nothing - no folder is a `WRITE_ONLY` root (`ShareRoots`).
 */
export function folderRights(access: Access, at: RightsTarget): FolderRights {
  if (at.kind !== FOLDER_KIND && !(at.kind === null && access.mode === 'member')) return NO_FOLDER_RIGHTS
  const node = nodeRights(access, at)
  if (access.mode === 'member') {
    const place = !access.readonly && !at.inIdentityFolder
    return {
      navigate: true,
      createFolder: place,
      nodeTrash: place,
      share: node.share,
      shareContribute: node.shareContribute,
      rename: node.rename,
      move: node.move,
      delete: node.delete && !at.inIdentityFolder,
    }
  }
  if (access.mode === 'guest') {
    switch (access.role) {
      case 'READ':
        return { ...NO_FOLDER_RIGHTS, navigate: true }
      case 'READ_WRITE':
        return { ...NO_FOLDER_RIGHTS, navigate: true, createFolder: !at.inIdentityFolder, move: node.move }
      default:
        return NO_FOLDER_RIGHTS
    }
  }
  return NO_FOLDER_RIGHTS
}
