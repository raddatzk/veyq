// The application registry (ADR 0079): what the shell asks instead of
// importing an application. Core renders by node kind and by list slot; an application says, once, what it renders for
// which kind and which list items it adds.
//
// **Build-time only (ADR 0079 Decision 5, ADR 0026).** The registry is filled by plain function calls from ONE
// composition root (`src/web/src/applications.ts`), which statically imports each application. There is no glob, no
// computed `import()`, no plugin loading: what runs is what was reviewed. The guard
// (`scripts/validate_web_core_imports_no_applications.py`) keeps core from importing an application and from
// discovering one by glob.
//
// **Write-once.** Registered code runs with every unlocked key, so the registry is a trust surface and not a
// convenience: registering a kind twice, claiming a drop page twice, or adding a list-slot item under a taken id THROWS —
// it never overwrites and never merges. Nothing here is exported as mutable state; `registry` exposes only the two register doors and read-only lookups.
//
// **Dialogs per kind (cr-files-application P4c).** A kind brings its dialogs with its descriptor: `createDialog` (the
// dialog that creates a node of the kind, mounted by the shell when a section or a detail asks for a create of that
// kind; props `NodeCreateDialogProps`) and `shareDialog` (the share management dialog of a node of the kind; props
// `NodeShareDialogProps`). The platform pre-registers FOLDER's share dialog (`core/sharing/FolderShareDialog.vue`); a
// folder's create and every node's rename are core's own `FolderNameDialog`
// (`core/node/FolderNameDialog.vue`), which the shell mounts itself — a rename re-seals the name and derives the slug
// token by the node's kind (`tokenInputFor`), whatever application it belongs to. A kind with no `shareDialog` offers no
// share; a create for a kind with no `createDialog` opens nothing. Every shell slot is a list slot (below).
//
// **The unified level list (`KindDescriptor.list`, inbox #407).** The folder level view is core's (`core/node/LevelView.vue`):
// the folder's header, the application tabs, then ONE list (or one set of tiles) of every node at the level - folders,
// Memories, files, drop boxes - that CORE draws. An application no longer draws a section of its own: a kind brings, with its
// descriptor, a `list` - `rows`, a factory (`core/node/levelRow.ts`) the level calls once per view with its reactive context and
// a host, answering for each node of the kind a `LevelRow` (leading icon or cover, title, meta line, primary click, actions;
// each action `shown` by the application's fail-closed rights function) plus the notices and presentation only the
// application can supply - and `tabLabel` (the i18n key of its tab; kinds sharing a label share a tab, the tab a pure FILTER with
// the folders in every tab), `views` (the presentations beyond core's grid and list that its factory draws: Memories' map) and
// `emptyText` (what its tab says when it has nothing). Several kinds may name the SAME factory (Memories: MEMORY and CHAPTER; Depot:
// FILE and DEPOT_DROPBOX): it is one row source, called once, handed the nodes of every kind that names it; two kinds naming one
// factory with different `views` is a contradiction, so `registerKind` throws (and registers nothing). `lists()` and `tabs()` list
// what the level builds from; a child whose kind nobody lists - or for which the factory has no row - is not dropped: core
// shows the explicit unsupported row, never an empty area.
//
// **Who looks (`core/access.ts`).** A `detail` and a row factory are handed `access` (a member, or a share guest)
// and `tree` (the tree as that session reads it), and offer what their application's fail-closed rights function
// answers for them — one view per kind in both modes, never a flag the host decides. The guest's page of a public share
// (`core/sharing/ShareTreePage.vue`, inbox #401) is core's level view at a folder and the kind's `detail` at any other
// node, whatever the link's root kind, so a kind's rows and `detail` serve it as they serve a member; no guest-only
// list or body per kind exists (`shareSection` was retired in S3, the embedded Memories body in S5).
//
// **List slots (`addSlotItem`).** The top-bar tray and the sidebar pins are places where several applications each add
// their own entry, so they are ordered lists: an item is registered under an id (namespaced by its application, e.g.
// `memories.pinned`), write-once per (slot, id) — a second registration of the same id in the same list throws. The
// shell renders them sorted by `order` (default: registration order; a tie keeps registration order). `addSlotItem`
// refuses a name that is no list slot at runtime too, so a caller outside the type checker gets an error, never a
// silent no-op.
//
// **Drop pages (`claimShareBody`).** The public share page (`views/ShareView.vue`) is a shell: it resolves the
// kind-independent half (core/sharing/resolvePublicShare.ts) and runs the password and terms gates. A `READ` or
// `READ_WRITE` link then gets core's page (`core/sharing/ShareTreePage.vue`, the member's views with the guest's access and
// tree); a `WRITE_ONLY` link gets the one page with no member equivalent — its guest holds no key, so there is nothing to
// render the member's way — the drop page the application claimed for the share's ROOT kind (`PublicShare.rootKind`,
// served for every role): Memories' photo drop, Depot's drop box (ADR 0079 Consequences, inbox #401). A kind is claimed
// once (a second claim throws), FOLDER by nobody (no `WRITE_ONLY` link roots at a folder, ADR 0080 decision 11). A
// `WRITE_ONLY` root kind nobody claimed, or none at all, renders the shell's explicit unavailable state.
//
// **Lifecycle hooks (`registerLifecycle`).** What runs when a scope is entered or reset and when the master key
// rotates. Registered per application id (a second registration of the same id throws); several applications may
// each contribute, and the registry runs them all. The master-key hook is REQUIRED: material sealed under the master
// key (Memories' pins) is undecryptable after a rotation that skipped it, so `rotateKeys` asks
// `assertRotationHooks()` before it touches anything and refuses to start when no registered application carries the
// hook (a composition root that registered nothing is that case too).
//
// **Applications (`registerApplication`; uc.platform.switch-application-off).** An application names itself once: its id —
// its own entry of the capability register (`memories`), which the Server Owner and a tenant owner switch off — the kinds
// it owns, and its name as an i18n key of its own namespace. Core asks `applicationOf(kind)` for a node's application and
// reads `Access.applicationsOff` with it, so core names no application and an application switched off is read-only
// wherever core draws an action of its nodes (a cover, a copy); the host's banner names it by `applications()`. A kind no
// application named belongs to none and is never switched off (core's FOLDER). An id or a kind named twice throws.
//
// **Route slugs (`claimRouteSlug`, `claimNoRouteSegment`; inbox #328, ADR 0015).** A node's place is a path in the URL
// `#fragment` (`core/node/location.ts`); every application but one puts its slug before the name of its nodes
// (`#foo/memories/rom`) so a path says what kind of thing it opens. An application claims its slug once for the kinds it
// owns — a second claim of a slug throws, a slug the platform keeps for its own paths (`RESERVED_ROUTE_SEGMENTS`) is
// refused, and so is a kind claimed twice — or declares, explicitly, that it has NO segment (Depot: its nodes are plain
// names, a file is `#foo/report`). Only one application may have none: the unprefixed names are one namespace. A claimed
// slug is RESERVED in that namespace: a folder (or a file) whose name folds to it is numbered (`memories-2`) by
// `location.ts`. The claimed slug also prefixes the kinds' slug-token input (`tokenInputFor`), so a name is unique among
// its application's siblings only. A kind nobody claimed has no segment either and is numbered among the plain names of the application that has none.
//
// **The level view's "New" menu (inbox #406).** The creation entries leave the sections' headers and tiles for ONE menu in
// core's level toolbar, derived from here: a kind's `createEntry` (label key of its own namespace, icon, order, the
// rights function that decides whether it shows) opens that kind's `createDialog`; a kind's `levelActions` are entries
// that are no create dialog (Depot's "Upload files"), run through a handler the application's row factory registers with the
// level (`core/node/levelActions.ts`) for the level's whole life, whichever tab is shown - and an entry declaring `acceptsDrop`
// also takes what is dropped onto the level (files, or a directory). `levelMenu()` lists them (each with the kind that
// declared it); core adds the folder entry itself.
//
// **Fail closed.** `kind()` never returns `undefined`: a node kind nobody registered resolves to
// `{ supported: false }`, which the host renders as an explicit unsupported state, never as an empty region. The kind
// is an open string token (`core/node/kind.ts`), so no compiler checks that every kind is handled: this answer and its
// tests (`registry.test.ts`) are what does.
import { nothingOfItsOwnToRekey, type RekeyContentHook } from '../node/rekey/hooks'
import { defineAsyncComponent, type Component } from 'vue'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import type { TreeNode } from '../node/tree'
import type { Level } from '../node/browse'
import type { LevelViewOption } from '../node/levelViews'
import type { LevelRowsFactory } from '../node/levelRow'
import type { StoredBlob } from '../blob/sharedBlob'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { PublicShareHead } from '../sharing/resolvePublicShare'
import type { Access, GuestAccess, TreeSource } from '../access'
import { slugify } from '../../shared/naming'
import type { VeyqIconName } from '../../shared/ui/icons'
import type { ExportContentHook, ImportContentHook } from '../export/hooks'
import type { ManifestContent } from '../export/manifest'

// The platform's own share dialog for a FOLDER (uc.sharing.share-public-viewer:folder-share-root), FOLDER's
// `shareDialog` since P4c. LAZY for a reason: the dialog's module graph reaches this registry again (measured:
// `shareLink` → `manageShares` → `shareTree` → `core/node/tree`, which asks `registry.tokenInputFor`), so a static import is
// a cycle. `.default` explicitly: a test's `vi.mock` of the module is no ES-module namespace Vue would unwrap on its
// own.
const FolderShareDialog = defineAsyncComponent(() => import('../sharing/FolderShareDialog.vue').then((m) => m.default))

/** The props of a section an application adds to a person's profile page (`Registry.claimPersonSection`, ADR 0083). */
export interface PersonSectionProps {
  /** The person's user id (the fragment's). */
  userId: string
  /** How the interface names them (the real name, else the username); empty for a former member. */
  name: string
}

/** What core needs to know about a node kind. `detail` is the component the host renders when a node of the kind is
 *  opened. Only the platform's FOLDER has none (a folder browses, it does not open): an application kind that
 *  registers no `detail` renders the host's explicit unsupported state when opened. */
/** What is wrong with a node's content from a download (`KindDescriptor.validateContent`): the shape (`invalid_manifest`) or a
 *  field over its contract length (`too_large`). */
export type ContentProblem = { code: 'invalid_manifest' | 'too_large'; message: string }

export interface KindDescriptor {
  kind: NodeKind
  /** The page of an opened node of the kind. While it is on screen the host keeps the scope's tree live, as on a level
   *  (`core/node/useLevelLive.ts`, ADR 0081: an idle scope is a `304`), whatever the kind: a block of the node or of a
   *  folder above it turns the page to the host's *Blocked by the operator* at once (inbox #503), a drop box's new guest
   *  folders and a Memory's new chapters arrive as rows, and a key re-derived from a re-read tree is the same object, so
   *  nothing keyed on it reloads. What lives beside the tree (a Memory's gallery, ADR 0033) the page polls itself. */
  detail?: Component
  /** The icon core draws next to a node of this kind (e.g. the trash list). */
  icon: VeyqIconName
  /** The i18n key of the title core's delete dialog (`core/node/components/DeleteNodeDialog.vue`) shows for a node of
   *  this kind, e.g. Memories' "Delete Memory". Omitted: the neutral `node.delete`. Core serves every application's
   *  nodes and so names none in its own strings (inbox #375): a kind's noun comes from the kind. */
  deleteTitle?: string
  /** The i18n key (one `{name}` parameter) of the sentence core's delete dialog says for a node of this kind, where
   *  the neutral `node.deleteCopy` would leave out what goes with it (Depot's drop box: its guests' folders and its
   *  links, inbox #395). Omitted: `node.deleteCopy`. */
  deleteCopy?: string
  /** The i18n key of the kind's noun with its indefinite article, as the object of a sentence (en "a file", de "eine
   *  Datei"): core's create and rename dialogs name a colliding sibling with it — "There is already a file named …"
   *  (`node.nameTakenBy`, inbox #389). Omitted: the neutral `node.anItem`. */
  nounWithArticle?: string
  /** The i18n key of the note core's trash shows on a trashed node of this kind that holds no attachment (the trash
   *  listing's `attached`): for a kind whose content IS its attachment, such a node is empty — Depot's `FILE` whose
   *  upload never finished (inbox #391). Its restore stays offered, the row says what comes back. Omitted: no note (a
   *  kind whose content lives elsewhere, e.g. a Memory, holds no attachment and is not empty for it). */
  emptyInTrash?: string
  /** Whether a public share can be created for a node of this kind (the rights check still applies). */
  shareable: boolean
  /** How a name of this kind folds before it is HMAC'd into its blinded slug token (inbox #350; read through
   *  {@link Registry.slugFor}). Omitted: `slugify`, the container folding that is also the URL segment. A kind that is
   *  need not fold like the URL segment (a file: `fileNameSlug`, which keeps punctuation; the URL segment of every kind is `slugify`). **Frozen into stored tokens**: the function
   *  a kind registers must never change once a node of the kind is stored, and must be pinned by vectors. */
  slug?: (name: string) => string
  /** Where core's rename dialog (`core/node/FolderNameDialog.vue`) ends the selection it puts on a node's name when the
   *  field takes focus: the length of the part a rename usually changes (a file: the name without its extension, inbox
   *  #385). Omitted: no selection, the caret at the end. */
  renameSelectionEnd?: (name: string) => number
  /** How this kind's nodes are drawn in the unified level list (inbox #407): the application supplies ROW DATA
   *  (`rows`), core draws the list. Omitted: a child of the kind shows as core's explicit unsupported row. Kinds naming the same
   *  `rows` function share ONE row source. */
  list?: KindList
  /** The dialog that creates a node of this kind (props `NodeCreateDialogProps`, emits `NodeCreateDialogEmits`),
   *  mounted by the shell when the level's "New" menu or a detail emits `create` / `createChild` with this kind. Omitted: a
   *  create of the kind opens nothing. FOLDER has none: its create is core's `FolderNameDialog`. */
  createDialog?: Component
  /** The entry this kind adds to the level view's "New" menu (`core/node/LevelView.vue`, inbox #406): its label (an i18n
   *  key of its own application namespace), icon and order, and `allowed` - the application's fail-closed rights
   *  answer for the level on screen, so a menu entry shows exactly where the old section button did. Choosing it asks the
   *  host for this kind's `createDialog` (which therefore must exist: `registerKind` throws without). Omitted: the kind
   *  is not created from a level (a chapter is created inside its Memory). */
  createEntry?: LevelMenuEntry
  /** Further entries of the "New" menu that are no create dialog - Depot's "Upload files". Each runs a handler the
   *  application's row factory registers with the level (`core/node/levelActions.ts`), so core stays
   *  application-agnostic. Ids are write-once across the registry. */
  levelActions?: readonly (LevelMenuEntry & { id: string })[]
  /** The share management dialog of a node of this kind (props `NodeShareDialogProps`, emits `close`). Omitted: the
   *  kind offers no share, whatever `shareable` says. FOLDER's is core's own `FolderShareDialog`, pre-registered. */
  shareDialog?: Component
  /** Where a node of this kind may be placed (inbox #358; read through {@link Registry.placementAllows}): at the scope
   *  root (`root`) and/or directly in a parent of one of `parents`. A HINT mirroring the server's `NodeKindRules` for the
   *  kind, so core's move dialog offers only destinations the server would take; the server stays the authority (its
   *  `illegal_structure` is explained by name when the two disagree). Omitted: the move dialog offers no destination for
   *  a node of the kind (fail closed). */
  placement?: KindPlacement
  /** A node of this kind can carry a COVER (inbox #411): an image the owner uploads as the node's own, shown as the node's
   *  leading visual wherever one is drawn. Core owns the action ("Set cover" / "Change cover" / "Remove cover"), the
   *  picker, the upload, the display and the guest read; the kind only opts in (and may say where an equal picture is
   *  stored, `KindCover.findStored`) - the server's `NodeKindRules.takesCover` is the authority and must agree. Omitted: no
   *  cover, no action. FOLDER's is core's own, pre-registered. */
  cover?: KindCover
  /** Export and import of this kind's CONTENT (ADR 0082): core walks the subtree and writes / reads the archive; the kind
   *  says what its content is. A kind with neither is left out of an export (with its subtree) and reported. FOLDER needs
   *  neither: it has no content. */
  exportContent?: ExportContentHook
  importContent?: ImportContentHook
  /** A node's re-key after a rotation (ADR 0109): the kind re-seals what only it stores under the node's key — its
   *  references inside the node's meta, its own rows (staged under the commit's re-key id). REQUIRED, so a kind cannot be
   *  registered without saying what its re-key does; one with nothing of its own passes `nothingOfItsOwnToRekey`. */
  rekeyContent: RekeyContentHook
  /** The application this kind's content belongs to and the highest version of its content schema this build writes and
   *  reads (ADR 0082 decision 9): a download names it in the manifest's `applications`, and an import's dry run lists a
   *  node whose content is newer as one it leaves out. `application` is the `<application>` part of every `content.type`
   *  the hook writes. Absent: the content carries no application and version 1. */
  contentSchema?: { application: string; version: number }
  /** Check a node's content from a download against this kind's contract (decision 10: the archive is untrusted input) -
   *  its shape and every field at its `maxLength` in `contracts/export/veyq-subtree.schema.json`. Called by the import's dry
   *  run before anything is written; the answer names the problem, null when the content is sound. Core has already checked
   *  the payload references inside it (their paths, sizes and checksums' shape). Absent: no check of the kind's own. */
  validateContent?: (content: ManifestContent) => ContentProblem | null
  /** A node of this kind IS one file (Depot's file): Download saves it as itself through the application's own action, so core
   *  offers no ZIP download for it, and inside an archive it is that one file at the node's path, not a directory. */
  downloadsAsFile?: boolean
  /** A node of this kind is imported as a plain `FOLDER` (Depot's drop box, ADR 0082 decision 4): what makes it
   *  this kind - a drop key, guests' identities, links - cannot be rebuilt from a download, so the import creates a folder of
   *  the node's name and its children under it, and needs no `importContent`. Its content is still checked
   *  (`validateContent`) and still names its application and version (`contentSchema`); the manifest keeps the kind it was. */
  importsAsFolder?: boolean
  /** This kind's `importContent`, in a copy (`ImportNodeContext.purpose`), reads each payload only by
   *  `slice(...).arrayBuffer()` - as the upload pump does - so a copy (ADR 0082 decision 11) can hand it the original read by
   *  frame, never held whole (ADR 0082 decision 11); a kind whose import decodes its bytes (Memories' tiers) hands over in
   *  a copy's walk what it would otherwise decode for, and decodes nothing in the copy's import. Absent: a
   *  copy leaves a node of this kind out, with its subtree, and names it before anything is written - fail closed, so a kind
   *  whose import needs its bytes as a real `Blob` (to decode them) is never handed a file it would have to buffer. */
  importStreams?: boolean
  /** What a copy (and an import) of a node of this kind does not carry beyond core's own list (shares, favourites, the
   *  trash, server-observed times): i18n keys of the application's OWN namespace, which core's copy dialog lists where a
   *  copied node is of this kind (uc.platform.copy-node, "What a copy does not carry"). */
  copyLosses?: readonly string[]
}

/** What a kind that carries a cover may add to core's mechanism (inbox #411). A cover is set only by an upload in core's
 *  dialog; a kind whose nodes already store pictures may say where an equal one is, so the cover shares it. */
export interface KindCover {
  /** The stored blob the cover may share instead of being uploaded (ADR 0013 § 5): a blob of THIS node that is the picked
   *  image derived at core's cover spec (Memories: the PREVIEW of a gallery medium whose original equals the file). Null:
   *  none, and the cover is uploaded. Best-effort - a failure is null. Omitted: every cover is uploaded. */
  findStored?(ctx: CoverFindContext): Promise<StoredBlob | null>
}

/** What a kind's `cover.findStored` is handed: the node, its key and the picked file. */
export interface CoverFindContext {
  scopeId: string
  nodeId: string
  /** The node's own content key (the chain's), never held reactively. */
  nodeKey: Uint8Array
  file: File
  signal?: AbortSignal
}

/** What an entry of the level view's "New" menu is told about the level: who looks, the tree as that session reads it, the
 *  folder on screen (`null`: the scope root) and the member's epoch keys (`null` for a guest). */
export interface LevelActionContext {
  access: Access
  tree: TreeSource
  folder: TreeNode | null
  epochs: EpochKeys | null
}

/** One entry of the "New" menu, declared by an application on its kind. `allowed` is the rights answer (fail closed:
 *  an entry whose function says no is not drawn); `enabled` (default: always) greys an allowed entry out while something
 *  else is not ready (the member's epoch keys for a create). Entries sort by `order` ascending. */
export interface LevelMenuEntry {
  /** The i18n key of the entry's label, in the application's own namespace (core names none). */
  label: string
  icon: VeyqIconName
  order: number
  allowed(ctx: LevelActionContext): boolean
  enabled?(ctx: LevelActionContext): boolean
  /** An action entry that takes what is dropped onto the level: core hands dropped FILES to the handler registered under
   *  the entry's id that has a `LevelActionHandler.drop` (Depot's "Upload files"), and a dropped DIRECTORY that is no download
   *  to one that has a `dropDirectory` (Depot's "Upload folder…") - only while the entry is `allowed` and the handler
   *  `enabled`: a drop is never more than the menu entry would be. Ignored on a create entry. */
  acceptsDrop?: boolean
}

/** A menu entry as the level view draws it: `create` is the kind to create (the host mounts its `createDialog`), absent
 *  for an application action, which runs the handler registered under `id`. */
export interface LevelMenuItem extends LevelMenuEntry {
  id: string
  create?: NodeKind
  /** The kind whose descriptor declared the entry. */
  kind: NodeKind
}

/** Where a kind may live in the tree ({@link KindDescriptor.placement}). */
export interface KindPlacement {
  root: boolean
  parents: readonly NodeKind[]
}

/** A kind's part in the unified level list (inbox #407). `rows` is the application's row factory
 *  (`core/node/levelRow.ts`); kinds naming the SAME function share one row source, called once per level view. */
export interface KindList {
  rows: LevelRowsFactory
  /** The i18n key of the kind's TAB on the level view's tab bar (`core/node/levelTabs.ts`), the application's own word.
   *  Kinds sharing a label share one tab. Omitted: no tab - the kind shows under `All` only. */
  tabLabel?: string
  /** The presentations beyond core's grid and list that the factory's `presentation(mode)` draws (Memories: the map). The
   *  factory's kinds must agree: one function renders one way. */
  views?: readonly LevelViewOption[]
  /** The i18n key of the plain text an application tab shows when it has nothing, per the viewer's rights
   *  (a writer is pointed at "New", a reader is not). Omitted: the neutral `node.tabEmpty`. */
  emptyText?: (ctx: LevelActionContext) => string
}

/** One row source of the level: the factory, every kind that named it (in registration order) and its presentations. */
export interface LevelList {
  rows: LevelRowsFactory
  kinds: readonly NodeKind[]
  views: readonly LevelViewOption[]
}

/** One tab of the level view's bar: the id (the first kind that named the label - stable across levels and what the
 *  per-user preference stores), the label key, and every kind it filters to. */
export interface LevelTabSpec {
  id: string
  label: string
  kinds: readonly NodeKind[]
  emptyText?: (ctx: LevelActionContext) => string
}

export type KindLookup = ({ supported: true } & KindDescriptor) | { supported: false; kind: NodeKind }

/** The generic context the host hands the `detail` component of an opened node (event names below). The
 *  application's own host component maps it to whatever its detail view takes. What the detail offers is its
 *  application's rights function over `access` and the node (`core/access.ts`), never a flag the host decides. */
export interface NodeDetailProps {
  node: TreeNode
  scopeId: string
  epochs: EpochKeys | null
  /** The opened node's content key, resolved by walking the content-key chain (null while the keys are not ready). */
  contentKey: Uint8Array | null
  /** The opened node's children, bucketed by kind (`byKind`). */
  children: Level
  /** Who looks: a member of the scope (its pending deletion, the instance's sharing capabilities), or a share guest —
   *  core's page of a `READ` or `READ_WRITE` link mounts the detail of every non-folder node, the link's root included
   *  (inbox #401; a guest's `scopeId` is the link's, its `epochs` null, its keys the tree's). */
  access: Access
  /** The tree as that session reads it: keys, names, the identity-folder test and the links every navigation takes. Its
   *  nodes are `TreeNode`s in both modes (the member's tree, the guest's served rows as `TreeNode`s). */
  tree: TreeSource<TreeNode>
}
/** Events a `detail` component emits to the host: `rename` (core's `FolderNameDialog`, any kind), `delete`,
 *  `createChild` (the KIND of the child to create: the host mounts that kind's `createDialog` under the opened node),
 *  `openNode` (a child), `share` and `shareUpload` (the opened kind's `shareDialog`, the second with `uploader`). `share`
 *  carries the {@link NodeShareOffer} of the rights function that showed the button, which the host hands on. */
export interface NodeDetailEmits {
  rename: []
  delete: []
  /** Move `node` (the opened node, or a child the detail lists — a drop box's guest folder): the host mounts core's
   *  `MoveNodeDialog` for it (inbox #358). */
  move: [node: TreeNode]
  /** Download the opened node and its subtree as a ZIP (ADR 0082): the host mounts core's `ExportSubtreeDialog`. */
  download: []
  /** Copy the opened node and its subtree elsewhere (uc.platform.copy-node): the host mounts core's `CopyNodesDialog`. */
  copy: []
  createChild: [kind: NodeKind]
  openNode: [node: TreeNode]
  share: [offer: NodeShareOffer]
  shareUpload: []
}

/** What a `share` emit hands the host for the dialog it mounts: the share rights beyond "may share at all" that the
 *  application's rights function answered for the node (`contribute`: offer the READ_WRITE role for a new link,
 *  `core.public-shares.contribute`). The host passes them on as `NodeShareDialogProps`, so a dialog never reads a
 *  capability itself (src/web/CLAUDE.md: never a `hasCapability` read inline). */
export interface NodeShareOffer {
  contribute: boolean
}

/** What the host hands a kind's `createDialog`: the kind to create (one component may serve several kinds), the scope,
 *  its epoch keys (a create seals under the write epoch), and the parent — `parentId` null at the scope root, else the
 *  parent's id and its content key, which the new node's key wraps under (ADR 0080 Decision 1). The dialog is mounted
 *  while open; it emits `done` after a create (the host reloads the tree) and `close`. */
export interface NodeCreateDialogProps {
  kind: NodeKind
  scopeId: string
  epochs: EpochKeys
  parentId: string | null
  parentKey: Uint8Array | null
  /** The parent's epoch as read (`TreeNode.epoch`): the create states it as `parentEpoch` (ADR 0109 point 6). Null at
   *  the scope root. */
  parentEpoch: number | null
}
export interface NodeCreateDialogEmits {
  done: []
  close: []
}

/** What the host hands a kind's `shareDialog`: the node, its content key as the key chain opens it (what a link wraps;
 *  key material), the scope's epoch keys (each link's recovery copy opens under `K_SC`), the node's name as the tree
 *  opened it (re-sealed under `K_share`) and its kind. `uploader`: the uploader (WRITE_ONLY drop) variant, set only on
 *  the detail's `shareUpload` — a kind whose dialog has no such variant ignores it. `contribute`: offer the READ_WRITE role
 *  for a new link, from the `share` emit's {@link NodeShareOffer}; absent, it is not offered (fail closed) — a dialog
 *  with no role choice ignores it. Mounted while open; emits `close`. */
export interface NodeShareDialogProps {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  /** The node's epoch as read (`TreeNode.epoch`): the epoch `nodeKey` was minted at, which a mint or re-issue states as
   *  `nodeEpoch` (ADR 0109 point 6). */
  nodeEpoch: number
  epochs: EpochKeys
  nodeName: string
  nodeKind: NodeKind
  uploader?: boolean
  contribute?: boolean
  /** The scope is pending deletion (`MemberAccess.readonly`, set by the host for every kind's dialog, core's rule): the
   *  dialog lists the node's links and copies them, but mints none and changes none - the server refuses every share
   *  write during the grace window, a revoke included (`ScopeAccess`'s WRITE answer `Deleting`), and serves no guest
   *  then, so a link is dormant until a restore. */
  readonly?: boolean
}

/** The shell's `sidebarPins` list slot: each component an application adds to it is mounted in the left rail
 *  (`variant: 'rail'`) and in the mobile navigation sheet (`'sheet'`); it renders nothing when it has nothing to
 *  pin. It emits `navigate` when the member follows one of its links, so the sheet can close. */
export interface SidebarPinsProps {
  variant: 'rail' | 'sheet'
}
export interface SidebarPinsEmits {
  navigate: []
}

/** What the shell hands a `WRITE_ONLY` link's drop page, the one share body (event: `unavailable` — the page found the
 *  share unusable after the shell had already shown it). The page renders everything below the password and terms
 *  gates: its own naming (the guest's identity key is the application's), the drop, and the session's own tiles. */
export interface ShareBodyProps {
  shareId: string
  /** The URL-fragment key (`#k=`), for the application's own key-proof calls. Key material. */
  fragmentKey: string
  /** The guest password token, when the share is password-protected and unlocked. */
  pwToken: string | undefined
  /** The platform half of the resolve. A new object after `reload()`. */
  head: PublicShareHead
  /** Re-resolve the share; resolves once `head` has been replaced. */
  reload: () => Promise<void>
  /** Who looks: the guest of this link (`core/sharing/guestAccess.ts`), built by the shell. */
  access: GuestAccess
}
export interface ShareBodyEmits {
  unavailable: []
}

/** The list slots: several applications may each add items (see the header). A `topbarTray` item takes no props (the
 *  shell's top bar mounts it after the invitations badge and core's own upload status menu); a `sidebarPins` item
 *  takes `SidebarPinsProps`. */
export type ListSlotName = 'topbarTray' | 'sidebarPins'

/**
 * What a moderator's review of a reported ITEM hands the application that owns it (uc.moderation.review-a-case A5): an
 * item is an application's record (a Memories medium), so core cannot draw it. `keys` are what the report shares handed
 * over (key material: each blob's `K_blob` by its id, the item's field keys); the component reads the held rows through
 * the review's own data source (`core/sharing/review.ts`) and draws nothing for an item that is not its own.
 */
export interface ItemReviewProps {
  caseId: string
  itemId: string
  scopeId: string
  keys: readonly { role: string; ref: string | null; key: Uint8Array }[]
}

/** One entry of a list slot, as the shell renders it (`id` is the item's key). */
export interface SlotItem {
  id: string
  component: Component
}

/** What an application runs at the platform's lifecycle moments. `onMasterKeyRotated` is mandatory (the type says
 *  so; the registry additionally refuses a hook set that is not a function at runtime). */
export interface LifecycleHooks {
  /** A scope was entered (or re-opened after a rotation) and its tree is loaded: load the application's per-scope
   *  state. Idempotent: the host may call it again for a scope that is already loaded. */
  onScopeEntered?(scopeId: string): Promise<void> | void
  /** The per-scope state is about to be replaced or dropped (scope switch, epoch refresh, sign-out/re-lock):
   *  clear everything the application holds for the old scope. Synchronous. */
  onScopeReset?(): void
  /** The Master Key rotates (uc.account.rotate-keys): re-seal everything the application sealed under `oldMk` so
   *  `newMk` opens it. `scopeIds` are the scopes the caller can see. Runs after the rotation's publish commit. */
  onMasterKeyRotated(oldMk: Uint8Array, newMk: Uint8Array, scopeIds: string[]): Promise<void>
}

/** An application of this build, as `registerApplication` named it. */
export interface ApplicationInfo {
  /** Its own entry of the capability register (`memories`): its switch, ADR 0079 decision 8. */
  id: string
  /** The i18n key, of the application's own namespace, of its name (`memory.memories`). */
  name: string
}

export interface Registry {
  /** Name an application: its register id, its name's i18n key and the node kinds it owns (see the header). Throws on an
   *  id named before, on an empty `kinds`, on FOLDER and on a kind another application already owns. */
  registerApplication(info: ApplicationInfo & { kinds: readonly NodeKind[] }): void
  /** The applications named, in registration order. */
  applications(): readonly ApplicationInfo[]
  /** The id of the application that owns `kind`, or null: core's FOLDER, a kind nobody named. */
  applicationOf(kind: NodeKind): string | null
  /** Register an application's lifecycle hooks under its id. Throws if the id is already registered, or if
   *  `onMasterKeyRotated` is not a function. */
  registerLifecycle(applicationId: string, hooks: LifecycleHooks): void
  /** Claim the drop page of a `WRITE_ONLY` link rooted at `kind` (see the header). Throws if that kind is already
   *  claimed, and for FOLDER (the platform's: no `WRITE_ONLY` link roots at a folder). */
  claimShareBody(kind: NodeKind, component: Component): void
  /** The drop page claimed for a `WRITE_ONLY` link's root kind, or null when nobody claimed it (the shell renders its
   *  unavailable state — never an empty page). */
  shareBody(kind: NodeKind): Component | null
  /** Add a section to a person's profile page (`#people/<userId>`, ADR 0083): what an application knows of the person -
   *  Memories' "Memories with this person". The component takes the props `PersonSectionProps` and draws its own loading,
   *  empty and error states. Sections show in registration order; the same component twice throws. */
  claimPersonSection(component: Component): void
  /** The sections claimed for a person's profile page, in registration order. */
  personSections(): readonly Component[]
  /** Claim the review of a reported item (props `ItemReviewProps`): what a moderator sees of an application's record.
   *  The same component twice throws. */
  claimItemReview(component: Component): void
  /** The item reviews claimed, in registration order — each draws only the items that are its application's. */
  itemReviews(): readonly Component[]
  /** Run every application's `onScopeReset`. */
  scopeReset(): void
  /** Run every application's `onScopeEntered`, in registration order; rejects with the first failure after all ran. */
  scopeEntered(scopeId: string): Promise<void>
  /** Run every application's `onMasterKeyRotated`; rejects with the first failure after all ran. */
  masterKeyRotated(oldMk: Uint8Array, newMk: Uint8Array, scopeIds: string[]): Promise<void>
  /** Throws unless at least one registered application carries `onMasterKeyRotated`: `rotateKeys` refuses to start
   *  without it (fail closed — see the header). */
  assertRotationHooks(): void
  /** Register a node kind. Throws if the kind is already registered (the platform's own FOLDER included), or if its
   *  `list` names a row factory another kind already named with different `views`. */
  registerKind(descriptor: KindDescriptor): void
  /** Add an item to a list slot under `id`. Throws if that slot already holds an item with this id. `order` sorts the
   *  list (ascending); omitted, the item's registration index is used. Ties keep registration order. */
  addSlotItem(slot: ListSlotName, id: string, component: Component, order?: number): void
  /** The kind's descriptor, or `{ supported: false }` — never `undefined`. */
  kind(kind: NodeKind): KindLookup
  /** The slug of `name` for a node of `kind`: the kind's own `slug` hook, else `slugify` (also for a kind nobody
   *  registered: the server stores any kind, and a token must still be computable for it). Every slug token is
   *  derived from this (through {@link Registry.tokenInputFor}), so a node's token always uses ITS kind's folding. */
  slugFor(kind: NodeKind, name: string): string
  /** The string a node's blinded slug token HMACs (`core/node/tree.ts` `slugTokenFor`), and so what its name is unique by
   *  among its siblings (ADR 0080 decision 16): {@link Registry.slugFor}'s slug behind `<routeSlug>/` for
   *  a kind whose application claimed a route slug (`memories/korfubanien`, `expenses/korfubanien`), the bare slug for a
   *  folder, Depot's kinds and a kind nobody claimed. A name is unique within its route group only, as the URL already
   *  separates them (`#foo/memories/korfubanien`, `#foo/expenses/korfubanien`): a Memory and a Pot of one name are
   *  siblings, a folder and a Depot file of one name still meet. A route slug is `[a-z0-9-]` and the `/` ends it, so two
   *  applications' inputs never meet, and a container's `slugify` never yields a `/`. A file's folding keeps one: a Depot
   *  file literally named `memories/x` still meets a Memory `x` (accepted, like a folder `a.b` and a file `a-b`). Every
   *  token and every client-side name check (`core/node/siblingCollision.ts`, the guest's, an import's numbering) goes
   *  through this, so they agree with the server, which compares tokens. */
  tokenInputFor(kind: NodeKind, name: string): string
  /** Claim the URL route slug of an application for the `kinds` it owns (see the header). Throws on a slug that is
   *  malformed or reserved, on a slug already claimed, on a kind already claimed (or FOLDER, the platform's), and on an
   *  empty `kinds`. */
  claimRouteSlug(slug: string, kinds: readonly NodeKind[]): void
  /** Declare that the application owning `kinds` has NO route segment: its nodes are plain names in the path (Depot).
   *  Throws on a kind already claimed (or FOLDER), on an empty `kinds`, and when another application already declared
   *  it — the unprefixed names are one namespace. */
  claimNoRouteSegment(kinds: readonly NodeKind[]): void
  /** How a node of `kind` is addressed in the path: its application's `slug` (null: none — a folder, the application
   *  that declared no segment, a kind nobody claimed) and the `group` that kind's nodes are numbered in among equal
   *  siblings — one per application with a segment, one for the folders, one for every node with none (Depot and the kinds nobody claimed). */
  routeOf(kind: NodeKind): KindRoute
  /** The claimed route slugs (the applications' own, not the platform's reserved words). */
  routeSlugs(): readonly string[]
  /** Whether a node of `kind` may be placed in a parent of `parentKind` (null: the scope root), by the kind's
   *  `placement` hint — false for a kind that declares none or nobody registered (fail closed). */
  placementAllows(kind: NodeKind, parentKind: NodeKind | null): boolean
  /** The items of a list slot, sorted by `order` (stable); empty when nobody added one (the shell renders nothing). */
  slotItems(slot: ListSlotName): readonly SlotItem[]
  /** The applications' entries of the level view's "New" menu, sorted by `order` (stable): every kind's `createEntry`
   *  (id `create:<KIND>`, `create` set) and every `levelActions` entry, each with the kind that declared it. Core adds its own
   *  folder entry. */
  levelMenu(): readonly LevelMenuItem[]
  /** The row sources of the unified level list, one per distinct `list.rows` function, in registration order. */
  lists(): readonly LevelList[]
  /** The tabs of the level view's bar, one per distinct `list.tabLabel`, in registration order. */
  tabs(): readonly LevelTabSpec[]
}

/** How a kind is addressed in the URL path ({@link Registry.routeOf}). */
export interface KindRoute {
  slug: string | null
  group: string
}

/** Words the platform keeps for itself in a route slug's place: the history-mode routes (`router.ts`) and the scope tokens'
 *  names. An application's slug can never shadow them, whatever a later route or mapping makes of the fragment. */
export const RESERVED_ROUTE_SEGMENTS: readonly string[] = [
  'api', 'confirm-email', 'confirm-email-change', 'folder', 'invitations', 'login', 'not-set-up', 'offline', 'people', 'recover', 's', 'settings', 'tenant', 'terms',
]
const ROUTE_SLUG = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/

const LIST_SLOTS: readonly string[] = ['topbarTray', 'sidebarPins'] satisfies readonly ListSlotName[]

/** A fresh registry holding only the platform's own kind (FOLDER: browsable, never openable, shareable through core's
 *  own `core/sharing/FolderShareDialog.vue`, its pre-registered `shareDialog` —
 *  uc.sharing.share-public-viewer:folder-share-root). The app uses the single `registry` below; tests build their own
 *  to prove the rules on an untouched instance. */
export function createRegistry(): Registry {
  const kinds = new Map<NodeKind, KindDescriptor>([
    // A folder lives at the root or in another folder (the server's `FolderNodeRules`).
    [FOLDER_KIND, {
      kind: FOLDER_KIND, icon: 'folder', shareable: true, shareDialog: FolderShareDialog, nounWithArticle: 'node.aFolder',
      placement: { root: true, parents: [FOLDER_KIND] }, cover: {}, copyLosses: ['node.copy.lossFolderCover'],
      rekeyContent: nothingOfItsOwnToRekey,
    }],
  ])
  const lists = new Map<ListSlotName, { id: string; component: Component; order: number }[]>()
  const lifecycles = new Map<string, LifecycleHooks>()
  const applications: ApplicationInfo[] = []
  const applicationOfKind = new Map<NodeKind, string>()
  // The unified list's row sources and tabs (inbox #407), in registration order.
  const listEntries: { rows: LevelRowsFactory; views: readonly LevelViewOption[]; kinds: NodeKind[] }[] = []
  const tabEntries: LevelTabSpec[] = []
  const shareBodies = new Map<NodeKind, Component>()
  const personSections: Component[] = []
  const itemReviews: Component[] = []
  // kind → its application's route slug, or null for the application with no segment.
  const routes = new Map<NodeKind, string | null>()
  const slugs = new Set<string>()
  function claimKinds(kinds: readonly NodeKind[], slug: string | null) {
    if (kinds.length === 0) throw new Error('a route claim names no node kind')
    for (const k of kinds) {
      if (k === FOLDER_KIND) throw new Error("FOLDER is the platform's: no application claims its route")
      if (routes.has(k)) throw new Error(`the route of node kind ${k} is already claimed`)
    }
    for (const k of kinds) routes.set(k, slug)
  }
  /** Run `f` on every application, never stopping at the first failure; rethrow the first one at the end. */
  async function eachApplication(f: (h: LifecycleHooks) => Promise<void> | void): Promise<void> {
    let failure: { error: unknown } | null = null
    for (const h of lifecycles.values()) {
      try {
        await f(h)
      } catch (error) {
        failure ??= { error }
      }
    }
    if (failure) throw failure.error
  }
  return {
    registerApplication({ id, name, kinds: owned }) {
      if (applications.some((a) => a.id === id)) throw new Error(`application ${id} is already registered`)
      if (owned.length === 0) throw new Error(`application ${id} names no node kind`)
      for (const k of owned) {
        if (k === FOLDER_KIND) throw new Error("FOLDER is the platform's: no application owns it")
        if (applicationOfKind.has(k)) throw new Error(`node kind ${k} already belongs to ${applicationOfKind.get(k)}`)
      }
      applications.push({ id, name })
      for (const k of owned) applicationOfKind.set(k, id)
    },
    applications: () => applications.map((a) => ({ ...a })),
    applicationOf: (kind) => applicationOfKind.get(kind) ?? null,
    claimShareBody(kind, component) {
      if (kind === FOLDER_KIND || shareBodies.has(kind)) throw new Error(`share body for ${kind} is already claimed`)
      shareBodies.set(kind, component)
    },
    shareBody: (kind) => shareBodies.get(kind) ?? null,
    claimPersonSection(component) {
      if (personSections.includes(component)) throw new Error('this person-profile section is already claimed')
      personSections.push(component)
    },
    personSections: () => [...personSections],
    claimItemReview(component) {
      if (itemReviews.includes(component)) throw new Error('this item review is already claimed')
      itemReviews.push(component)
    },
    itemReviews: () => [...itemReviews],
    registerLifecycle(applicationId, hooks) {
      if (lifecycles.has(applicationId)) throw new Error(`lifecycle hooks of ${applicationId} are already registered`)
      if (typeof hooks.onMasterKeyRotated !== 'function') {
        throw new Error(`application ${applicationId} registered no onMasterKeyRotated hook (it is required)`)
      }
      lifecycles.set(applicationId, { ...hooks })
    },
    scopeReset() {
      // Synchronous on purpose: callers (enterScope, clearActiveSpace) rely on the state being gone on return.
      for (const h of lifecycles.values()) h.onScopeReset?.()
    },
    scopeEntered: (scopeId) => eachApplication((h) => h.onScopeEntered?.(scopeId)),
    masterKeyRotated: (oldMk, newMk, scopeIds) => eachApplication((h) => h.onMasterKeyRotated(oldMk, newMk, scopeIds)),
    assertRotationHooks() {
      if (lifecycles.size === 0) {
        throw new Error('no application registered an onMasterKeyRotated hook; refusing to rotate keys (fail closed)')
      }
    },
    registerKind(descriptor) {
      if (kinds.has(descriptor.kind)) throw new Error(`node kind ${descriptor.kind} is already registered`)
      // Checked before anything is stored, so a contradiction registers nothing.
      const joinList = listJoin(listEntries, tabEntries, descriptor.kind, descriptor.list)
      if (descriptor.createEntry && !descriptor.createDialog) {
        throw new Error(`node kind ${descriptor.kind} has a create menu entry but no createDialog to open`)
      }
      const own = descriptor.levelActions ?? []
      for (const a of own) {
        const taken = a.id.startsWith('create:') || [...kinds.values()].some((k) => k.levelActions?.some((x) => x.id === a.id))
        if (taken || own.filter((x) => x.id === a.id).length > 1) {
          throw new Error(`level action ${a.id} is already registered (or reserved)`)
        }
      }
      kinds.set(descriptor.kind, { ...descriptor })
      joinList()
    },
    addSlotItem(slot, id, component, order) {
      if (!LIST_SLOTS.includes(slot)) throw new Error(`slot ${slot} is not a list slot`)
      const items = lists.get(slot) ?? []
      if (items.some((i) => i.id === id)) throw new Error(`slot ${slot} already holds an item ${id}`)
      items.push({ id, component, order: order ?? items.length })
      lists.set(slot, items)
    },
    slotItems(slot) {
      return [...(lists.get(slot) ?? [])]
        .sort((a, b) => a.order - b.order) // stable (ES2019): a tie keeps registration order
        .map(({ id, component }) => ({ id, component }))
    },
    kind(kind) {
      const d = kinds.get(kind)
      return d ? { supported: true, ...d } : { supported: false, kind }
    },
    slugFor(kind, name) {
      return (kinds.get(kind)?.slug ?? slugify)(name)
    },
    tokenInputFor(kind, name) {
      const slug = (kinds.get(kind)?.slug ?? slugify)(name)
      // FOLDER is never in `routes`; Depot's kinds are claimed with no slug (null), a kind nobody claimed is absent
      const route = routes.get(kind)
      return route ? `${route}/${slug}` : slug
    },
    claimRouteSlug(slug, claimed) {
      if (!ROUTE_SLUG.test(slug)) throw new Error(`route slug ${JSON.stringify(slug)} is not a slug ([a-z0-9-], starting with a letter)`)
      if (RESERVED_ROUTE_SEGMENTS.includes(slug)) throw new Error(`route slug ${slug} is reserved by the platform`)
      if (slugs.has(slug)) throw new Error(`route slug ${slug} is already claimed`)
      claimKinds(claimed, slug)
      slugs.add(slug)
    },
    claimNoRouteSegment(claimed) {
      if ([...routes.values()].includes(null)) throw new Error('an application already declared that it has no route segment')
      claimKinds(claimed, null)
    },
    routeOf(kind) {
      if (kind === FOLDER_KIND) return { slug: null, group: 'folder' }
      const slug = routes.get(kind)
      // A kind nobody claimed shares the unprefixed namespace with the application that has no segment, so its names are
      // numbered among that application's (two nodes never build one URL).
      return { slug: slug ?? null, group: slug ? `application:${slug}` : 'none' }
    },
    routeSlugs: () => [...slugs],
    placementAllows(kind, parentKind) {
      const p = kinds.get(kind)?.placement
      if (!p) return false
      return parentKind === null ? p.root : p.parents.includes(parentKind)
    },
    levelMenu() {
      const items: LevelMenuItem[] = []
      for (const d of kinds.values()) {
        if (d.createEntry) items.push({ ...d.createEntry, id: `create:${d.kind}`, create: d.kind, kind: d.kind })
        for (const a of d.levelActions ?? []) items.push({ ...a, kind: d.kind })
      }
      return items.sort((a, b) => a.order - b.order) // stable (ES2019): a tie keeps registration order
    },
    lists: () => listEntries.map((l) => ({ rows: l.rows, kinds: [...l.kinds], views: [...l.views] })),
    tabs: () => tabEntries.map((t) => ({ ...t, kinds: [...t.kinds] })),
  }
}

const modesOf = (views: readonly LevelViewOption[]) => views.map((v) => v.mode).join(', ')

/** Check that `kind` may join the list `list` (a row factory already named with other `views` throws: one function renders one
 *  way) and return the join - the factory's entry and the kind's tab - to run once every check of the registration passed. */
function listJoin(
  lists: { rows: LevelRowsFactory; views: readonly LevelViewOption[]; kinds: NodeKind[] }[],
  tabs: LevelTabSpec[],
  kind: NodeKind,
  list: KindList | undefined,
): () => void {
  if (!list) return () => {}
  const shared = lists.find((l) => l.rows === list.rows)
  const views = [...(list.views ?? [])]
  if (shared && modesOf(shared.views) !== modesOf(views)) {
    throw new Error(`node kind ${kind} names the row source of ${shared.kinds.join(', ')} with views [${modesOf(views)}], not [${modesOf(shared.views)}]`)
  }
  return () => {
    if (shared) shared.kinds.push(kind)
    else lists.push({ rows: list.rows, views, kinds: [kind] })
    if (list.tabLabel === undefined) return
    const tab = tabs.find((t) => t.label === list.tabLabel)
    if (tab) (tab.kinds as NodeKind[]).push(kind)
    else tabs.push({ id: kind, label: list.tabLabel, kinds: [kind], emptyText: list.emptyText })
  }
}

/** The app's registry, filled once by `registerApplications()` (src/web/src/applications.ts) before the first route
 *  resolves (`main.ts`) and, in tests, by `src/test/setup.ts`. */
export const registry: Registry = createRegistry()
