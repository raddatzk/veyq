// The application registry (ADR 0079): what the shell asks instead of
// importing an application. Core renders by node kind; an application says, once, what it renders for which kind.
//
// **Build-time only (ADR 0079 Decision 5, ADR 0026).** The registry is filled by plain function calls from ONE
// composition root (`src/web/src/applications.ts`), which statically imports each application. There is no glob, no
// computed `import()`, no plugin loading: what runs is what was reviewed. The guard
// (`scripts/validate_web_core_imports_no_applications.py`) keeps core from importing an application and from
// discovering one by glob.
//
// **One door per application: its registrar (owner 2026-11-01).** `registerApplication({ id, name })` names the
// application and answers its SCOPED registrar (`ApplicationRegistrar`), through which it registers everything else:
// `app.kind(kind, descriptor)` per node kind, `app.route(slug | null)` once, `app.lifecycle(hooks)`, `app.shareBody(rootKind,
// body)`, `app.folderUploadShare(kind, share)`, `app.personSection(component)`, `app.itemReview(component)`, `app.shortcuts(groups)`,
// `app.footerBar(component)`. Ownership comes from the call: a kind belongs to the
// application whose registrar registered it, so no list of its kinds exists beside the registrations, and `app.id` is the
// one place an application's id lives on the web side (its kinds' `contentSchema.application` is read from it). There are
// no free register functions.
//
// **Write-once.** Registered code runs with every unlocked key, so the registry is a trust surface and not a
// convenience: registering a kind twice, claiming a drop page twice, or naming an application or a route twice THROWS —
// it never overwrites and never merges. Nothing here is exported as mutable state; `registry` exposes only the register doors and read-only lookups.
//
// **Dialogs per kind (cr-files-application P4c).** A kind brings its dialogs with its descriptor: `createDialog` (the
// dialog that creates a node of the kind, mounted by the shell when a section or a detail asks for a create of that
// kind; props `NodeCreateDialogProps`) and `shareDialog` (the share management dialog of a node of the kind; props
// `NodeShareDialogProps`). The platform pre-registers FOLDER's share dialog (`core/sharing/FolderShareDialog.vue`); a
// folder's create and every node's rename are core's own `FolderNameDialog`
// (`core/node/FolderNameDialog.vue`), which the shell mounts itself — a rename re-seals the name and derives the slug
// token by the node's kind (`tokenInputFor`), whatever application it belongs to. A kind with no `shareDialog` offers no
// share - the dialog's presence is the kind's whole answer, there is no flag beside it; a create for a kind with no
// `createDialog` opens nothing.
//
// **The unified level list (`KindDescriptor.list`, inbox #407).** The folder level view is core's (`core/node/LevelView.vue`):
// the folder's header, the application tabs, then every node at the level - the folders, then the contents (Memories, files,
// drop boxes, every kind mixed) - that CORE draws. An application no longer draws a section of its own: a kind brings, with its
// descriptor, a `list` - `rows`, a factory (`core/node/levelRow.ts`) the level calls once per view with its reactive context and
// a host, answering for each node of the kind a `LevelRow` (leading icon or cover, title, meta line, primary click, actions;
// each action `shown` by the application's fail-closed rights function) plus the notices and presentation only the
// application can supply - and `tabLabel` (the i18n key of its tab; kinds sharing a label share a tab, the tab a pure FILTER with
// the folders in every tab), `views` (the presentations beyond core's grid and list that its factory draws: Memories' map) and
// `emptyText` (what its tab says when it has nothing). Several kinds may name the SAME factory (Memories: MEMORY and CHAPTER; Depot:
// FILE and DEPOT_DROPBOX): it is one row source, called once, handed the nodes of every kind that names it; two kinds naming one
// factory with different `views` is a contradiction, so `app.kind` throws (and registers nothing). `lists()` and `tabs()` list
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
// **One shell slot: the footer bar (`app.footerBar`, ADR 0131).** The shell's top bar and sidebar are core's alone: the
// upload status (`core/blob/UploadStatusMenu.vue`) lists every application's uploads and the Favorites section
// (`core/favorites/PinnedNodes.vue`) every kind's pins, so no application adds an entry there. The one place the shell
// gives an application is a bar BELOW its footer, which core mounts once per shell - the signed-in app's
// (`components/app/AppShell.vue`, only while a session is signed in and unlocked) and the share page's
// (`views/ShareView.vue`) - so it outlives every change of page within that shell: Memories' music player, whose music
// keeps playing while the viewer moves on. Core draws its frame (`core/footerBar/FooterBars.vue`: in the page's flow after
// the footer, sticky at the viewport's bottom edge) and nothing of its content; an application switched off here gets
// none mounted. One per application (a second call throws), the same component never twice. Another place in the shell
// is a seam that comes with its first user.
//
// **Drop pages (`app.shareBody`).** The public share page (`views/ShareView.vue`) is a shell: it resolves the
// kind-independent half (core/sharing/resolvePublicShare.ts) and runs the password and terms gates. A `READ` or
// `READ_WRITE` link then gets core's page (`core/sharing/ShareTreePage.vue`, the member's views with the guest's access and
// tree); a `WRITE_ONLY` link gets the one page with no member equivalent — its guest holds no key, so there is nothing to
// render the member's way — the drop page the application claimed for the share's ROOT kind (`PublicShare.rootKind`,
// served for every role): Memories' photo drop, Depot's drop box (ADR 0079 Consequences, inbox #401). A kind is claimed
// once (a second claim throws), only by the application that registered it (another's kind throws), FOLDER by nobody (no
// `WRITE_ONLY` link roots at a folder, ADR 0080 decision 11). A `WRITE_ONLY` root kind nobody claimed, or none at all,
// renders the shell's explicit unavailable state.
//
// **The folder's upload-only link (`app.folderUploadShare`, owner 2026-11-04).** No `WRITE_ONLY` link roots at a folder (ADR
// 0080 decision 11): an upload-only link names a NEW node of an application's kind under the folder - Depot's drop box - which
// the link then roots at. Core's folder share dialog (`core/sharing/FolderShareDialog.vue`) offers that role, "Add files
// only", beside READ and READ_WRITE, and the space's top level offers it alone (a scope root is no link's root); one
// application fills it, ONCE across the registry (a second registration throws), with a kind of its own whose drop page it
// already claimed (`app.shareBody`, else it throws: a link nobody serves). It hands `allowed` - its fail-closed rights
// answer for the level, as a "New" entry's -, `hint` - the i18n key of its own sentence for what the link does - and
// `create`, which births the node named by the link's name under the folder (null: the scope root) and mints its first
// `WRITE_ONLY` link with the application's own key handling. Core decides who is offered it
// (`core/sharing/folderUploadShare.ts`: a member, the kind's application switched on, the kind placeable there, and
// `allowed`), never names the kind, and the node it created is a row of the level like any other, its links its own dialog's.
//
// **Lifecycle hooks (`app.lifecycle`).** What runs when a scope is entered or reset and when the master key
// rotates, and (optional) what runs before a session ending loads the sign-in page (`onSessionEnding`, handed to
// `app/sessionEndingSteps.ts`, which the ending runs bounded). Registered through the application's registrar - so a hook set cannot exist for an application this build does
// not have -, once per application (a second registration throws);
// several applications may each contribute, and the registry runs them all. The master-key hook is REQUIRED of every hook
// set: material an application seals under the Master Key (Memories' item marks) is unverifiable after a rotation that
// skipped it. There is no registry-wide rotation gate: core re-seals what it seals under the Master Key itself (the node
// pins, `rotateKeys`), so a build with no application - or none that registers hooks - rotates as safely as any other.
//
// **A scope's re-key (`app.scopeRekey`, ADR 0115 decision 2).** A rotation replaces the scope key and re-wraps every value
// key under it before its commit — it never rewrites a value; core stages its own wraps, and an application that stores
// values under value keys the scope key wraps (Memories' item marks) re-wraps and stages its own through the participant
// it registers here — once per application, handed no scope key, only the re-wrap.
// Not required: an application with no such row registers none, and the server refuses a commit that leaves any row
// unstaged (`rekey_parts_missing`), so a forgotten participant fails closed, never silently.
//
// **Applications (`registerApplication`; uc.platform.switch-application-off).** An application names itself once: its id —
// its own entry of the capability register (`memories`), which the Server Owner and a tenant owner switch off — and its
// name as an i18n key of its own namespace; the kinds it owns are those its registrar registers. Core asks
// `applicationOf(kind)` for a node's application and reads `Access.applicationsOff` with it, so core names no application
// and an application switched off is read-only wherever core draws an action of its nodes (a cover, a copy); the host's
// banner names it by `applications()`. A kind no application registered belongs to none and is never switched off (core's
// FOLDER). An id or a kind registered twice throws.
//
// **Routes (`app.route`; inbox #328, ADR 0015).** A node's place is a path in the URL
// `#fragment` (`core/node/location.ts`); every application but one puts its slug before the name of its nodes
// (`#foo/memories/rom`) so a path says what kind of thing it opens. An application decides its route ONCE, for every kind
// it owns (a second decision throws): its slug — a slug claimed by another throws, a slug the platform keeps for its own
// paths (`RESERVED_ROUTE_SEGMENTS`) is refused — or, explicitly, `null`: NO segment (Depot: its nodes are plain
// names, a file is `#foo/report`). Only one application may have none: the unprefixed names are one namespace. A claimed
// slug is RESERVED in that namespace: a folder (or a file) whose name folds to it is numbered (`memories-2`) by
// `location.ts`. The claimed slug also prefixes the kinds' slug-token input (`tokenInputFor`), so a name is unique among
// its application's siblings only. A kind whose application decided no route has no segment either and is numbered among
// the plain names of the application that has none.
//
// **Kinds that are no row (`KindDescriptor.listed: false`).** A kind whose nodes the level never draws - a future
// application's one pool per space, created on first open and reached through its own entry, not as a row
// (`docs/apps/inventory.md`): core's level shows no row for it and no unsupported row either; the node is still a node
// everywhere else (the tree, the move, the trash, a download). Such a kind brings no `list` (`app.kind` throws on both).
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
//
// **Keyboard shortcuts (`app.shortcuts`, uc.platform.keyboard-shortcuts).** An application names the shortcuts of its own
// screens ONCE, as groups of `{ keys, label }` (`core/shortcuts/shortcuts.ts`), its labels keys of its own namespace; core's
// overview (`core/shortcuts/ShortcutsDialog.vue`) lists them after core's own, for an application switched on here only.
import { nothingOfItsOwnToRekey, type RekeyContentHook, type ScopeRekeyParticipant } from '../node/rekey/hooks'
import { defineAsyncComponent, type Component } from 'vue'
import { onSessionEnding, type SessionEndingContext } from '../../app/sessionEndingSteps'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import type { NodeParent, TreeNode } from '../node/tree'
import type { Level } from '../node/browse'
import type { LevelViewOption } from '../node/levelViews'
import type { LevelRowsFactory } from '../node/levelRow'
import type { StoredBlob } from '../blob/sharedBlob'
import type { ScopeKey } from '../../features/spaces/epochs'
import type { PublicShareHead } from '../sharing/resolvePublicShare'
import type { MintedShare } from '../sharing/manageShares'
import type { Access, GuestAccess, TreeSource } from '../access'
import { slugify } from '../../shared/naming'
import type { VeyqIconName } from '../../shared/ui/icons'
import type { ExportContentHook, ImportContentHook } from '../export/hooks'
import type { ManifestContent } from '../export/manifest'
import type { ScopeDocument, SearchFacets } from '../search/model'
import type { ShortcutGroup } from '../shortcuts/shortcuts'

// The platform's own share dialog for a FOLDER (uc.sharing.share-public-viewer:folder-share-root), FOLDER's
// `shareDialog` since P4c. LAZY for a reason: the dialog's module graph reaches this registry again (measured:
// `shareLink` → `manageShares` → `shareTree` → `core/node/tree`, which asks `registry.tokenInputFor`), so a static import is
// a cycle. `.default` explicitly: a test's `vi.mock` of the module is no ES-module namespace Vue would unwrap on its
// own.
const FolderShareDialog = defineAsyncComponent(() => import('../sharing/FolderShareDialog.vue').then((m) => m.default))

/** The props of a section an application adds to a person's profile page (`ApplicationRegistrar.personSection`, ADR 0083). */
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
  /** The icon core draws next to a node of this kind (e.g. the trash list), and its header's 16:9 frame wherever no cover is shown. */
  icon: VeyqIconName
  /** What core's header of a node of this kind needs that never changes per node (owner 2026-11-03, ADR 0110 decision 10):
   *  core draws every node page's header (`core/node/components/NodePage.vue`, the folder level for FOLDER), from this,
   *  the `icon`, the `cover` and what the page's detail contributes (`core/node/nodeHeaderContribution.ts`). Omitted: no
   *  priority, the actions in their merge order. */
  header?: KindHeader
  /** The i18n key of the title core's delete question (`core/node/components/DeleteNodesDialog.vue`, one node's) shows for a node of
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
  /** How a name of this kind folds before it is HMAC'd into its blinded slug token (inbox #350; read through
   *  {@link Registry.tokenInputFor}). Omitted: `slugify`, the container folding that is also the URL segment. A kind that is
   *  need not fold like the URL segment (a file: `fileNameSlug`, which keeps punctuation; the URL segment of every kind is `slugify`). **Frozen into stored tokens**: the function
   *  a kind registers must never change once a node of the kind is stored, and must be pinned by vectors. */
  slug?: (name: string) => string
  /** `false`: the level draws no row for a node of this kind - and no unsupported row (see the header). Omitted: listed,
   *  through `list` (or as the unsupported row without one). */
  listed?: boolean
  /** Where core's rename dialog (`core/node/FolderNameDialog.vue`) ends the selection it puts on a node's name when the
   *  field takes focus: the length of the part a rename usually changes (a file: the name without its extension, inbox
   *  #385). Omitted: no selection, the caret at the end. */
  renameSelectionEnd?: (name: string) => number
  /** How this kind's nodes are drawn in the unified level list (inbox #407): the application supplies ROW DATA
   *  (`rows`), core draws the list. Omitted: a child of the kind shows as core's explicit unsupported row. Kinds naming the same
   *  `rows` function share ONE row source. */
  list?: KindList
  /** The dialog that creates a node of this kind (props `NodeCreateDialogProps`, emits `NodeCreateDialogEmits`),
   *  mounted by the page when the level's "New" menu or a detail asks its `createChild` door for this kind. Omitted: a
   *  create of the kind opens nothing. FOLDER has none: its create is core's `FolderNameDialog`. */
  createDialog?: Component
  /** The entry this kind adds to the level view's "New" menu (`core/node/LevelView.vue`, inbox #406): its label (an i18n
   *  key of its own application namespace), icon and order, and `allowed` - the application's fail-closed rights
   *  answer for the level on screen, so a menu entry shows exactly where the old section button did. Choosing it asks the
   *  host for this kind's `createDialog` (which therefore must exist: `app.kind` throws without). Omitted: the kind
   *  is not created from a level (a chapter is created inside its Memory). */
  createEntry?: LevelMenuEntry
  /** Further entries of the "New" menu that are no create dialog - Depot's "Upload files". Each runs a handler the
   *  application's row factory registers with the level (`core/node/levelActions.ts`), so core stays
   *  application-agnostic. Ids are write-once across the registry. */
  levelActions?: readonly (LevelMenuEntry & { id: string })[]
  /** The share management dialog of a node of this kind (props `NodeShareDialogProps`, emits `close`): its presence is
   *  what makes the kind shareable (`targetOf`; the rights check still applies). Omitted: the kind offers no share.
   *  FOLDER's is core's own `FolderShareDialog`, pre-registered. */
  shareDialog?: Component
  /** Where a node of this kind may be placed (inbox #358; read through {@link Registry.placementAllows}): at the scope
   *  root (`root`) and/or directly in a parent of one of `parents`. A HINT mirroring the server's `NodeKindRules` for the
   *  kind, so core's move dialog offers only destinations the server would take; the server stays the authority (its
   *  `illegal_structure` is explained by name when the two disagree). Both answer `contracts/platform/kinds/<owner>.json`
   *  (`src/web/src/kindsContract.test.ts` holds this side to it). Omitted: the move dialog offers no destination for
   *  a node of the kind (fail closed). */
  placement?: KindPlacement
  /** A node of this kind can carry a COVER (inbox #411): an image the owner uploads as the node's own, shown as the node's
   *  leading visual wherever one is drawn. Core owns the action ("Set cover" / "Change cover" / "Remove cover"), the
   *  picker, the upload, the display and the guest read; the kind only opts in (and may say where an equal picture is
   *  stored, `KindCover.findStored`) - the server's `NodeKindRules.takesCover` is the authority, and both answer the kind's
   *  `cover` in `contracts/platform/kinds/<owner>.json` (`kindsContract.test.ts`). Omitted: no
   *  cover, no action. FOLDER's is core's own, pre-registered. */
  cover?: KindCover
  /** What the search knows of a node of this kind beyond its name (ADR 0125 decision 6): the kind's word for a kind chip,
   *  the facets core asks for while it holds the node's key, and the kinds of the documents the kind's page contributes for
   *  the node on screen (`core/search/contribution.ts`). Omitted: the node is found by its name alone and offers no kind
   *  chip. FOLDER's is core's own, pre-registered. */
  search?: KindSearch
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
   *  the hook writes - the registering application's id, which the registry fills in (`KindRegistration` names the
   *  version alone). Absent: the content carries no application and version 1. */
  contentSchema?: { application: string; version: number }
  /** Check a node's content from a download against this kind's contract (decision 10: the archive is untrusted input) -
   *  its shape and every field at its `maxLength` in its application's own `contracts/export/<application>/content.schema.json`
   *  (core's envelope leaves `content` opaque). Called by the import's dry
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

/** A kind's header (ADR 0110 decisions 5 and 10). */
export interface KindHeader {
  /** The kind's priority: the ids of the header's actions that come first as buttons, in that order (core's included -
   *  `favorite`, `cover`, `copy`, `download`); an unlisted one follows, never vanishes. Delete and report are always the ⋯
   *  menu's last entries, whatever this says. */
  order?: readonly string[]
}

/** What a kind that carries a cover may add to core's mechanism (inbox #411). A cover is set only by an upload in core's
 *  dialog; a kind whose nodes already store pictures may say where an equal one is, so the cover shares it. */
export interface KindCover {
  /** The stored blob the cover may share instead of being uploaded (ADR 0013 § 5): a blob of THIS node that is the picked
   *  image derived at core's cover spec (Memories: the PREVIEW of a gallery medium whose original equals the file). Null:
   *  none, and the cover is uploaded. Best-effort - a failure is null. Omitted: every cover is uploaded. */
  findStored?(ctx: CoverFindContext): Promise<StoredBlob | null>
}

/** A kind's part in the search (ADR 0125 decision 6; `KindDescriptor.search`). */
export interface KindSearch {
  /** The i18n key, of the application's own namespace, of the kind's noun ("Memory"): what a kind chip says and matches. */
  label: string
  /**
   * The facets of one node of the kind - a Memory's date range, place and participants. Core calls it while it holds the
   * tree's keys, once per sealed meta (a document whose `wrappedMeta` did not change keeps its facets), and zeroes the keys
   * after the walk: the hook keeps none, and anything it returns is plaintext the search holds in memory only. A throw is
   * no facets. Omitted: none.
   */
  facets?(ctx: SearchFacetContext): SearchFacets
  /** The kinds of the documents the kind's page contributes for its node, and its application's search of a space finds in
   *  such a node (Memories: a photo, a video): each one's word for a kind chip and its icon in a result row. */
  documents?: readonly SearchDocumentKind[]
}

/** A contributed document kind (`KindSearch.documents`). */
export interface SearchDocumentKind {
  /** The document's `kind` token, the application's own (`memories.photo`). */
  kind: string
  /** The i18n key of its noun, of the application's own namespace. */
  label: string
  icon: VeyqIconName
}

/**
 * What an application adds to the search of a whole space (ADR 0125 decision 8a; `ApplicationRegistrar.search`): the objects
 * of its own inside the space's nodes - Memories' photos and videos of every Memory. Core asks it once per space per walk.
 */
export interface ApplicationSearch {
  /**
   * The space's objects, or null when nothing moved since the answer `ctx.tag` names (a `304`): core keeps that answer's
   * documents. Core calls it while it walks the space and zeroes every key `ctx.keys()` opened once it has returned; the
   * hook keeps none, and what it returns is plaintext the search holds in memory only. A throw is a space not searched.
   */
  scopeDocuments(ctx: ScopeDocumentsContext): Promise<ScopeDocumentsAnswer | null>
}

/** What `ApplicationSearch.scopeDocuments` answers: its tag (what the next walk hands back as `ctx.tag`) and its objects. */
export interface ScopeDocumentsAnswer {
  tag: string | null
  documents: readonly ScopeDocument[]
}

/** What `ApplicationSearch.scopeDocuments` is handed for one space. */
export interface ScopeDocumentsContext {
  scopeId: string
  /** The tag of the hook's last answer for this space, or null (the first walk, or after a failure). */
  tag: string | null
  /** The hook's last documents for this space, by id: one at an unchanged `revision` is kept, not opened again. */
  previous: ReadonlyMap<string, ScopeDocument>
  /**
   * The space's node keys, opened on the first call (a walk whose tree did not move opens them only now, so an answer that
   * opens nothing costs no key) and valid until the hook returns: a node's content key, or null for a node not in the
   * space's tree or whose chain does not open. Never kept.
   */
  keys(): Promise<(nodeId: string) => Uint8Array | null>
  /** Run `steps` in core's slices (at most `SLICE_MS` of the main thread at a time), so a space of thousands of objects
   *  never holds the page; rejects when the walk is abandoned between two. */
  slices<T>(steps: Generator<void, T, void>): Promise<T>
  /** Aborted when the index is reset (a sign-out, a re-lock): stop fetching. */
  signal: AbortSignal
}

/** What a kind's `search.facets` is handed: the node and, for that call only, its key and its parent's (the scope key at the
 *  scope root). */
export interface SearchFacetContext {
  scopeId: string
  node: TreeNode
  nodeKey: Uint8Array
  parentKey: Uint8Array
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
  epochs: ScopeKey | null
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

/** What an application hands `app.kind` for a kind: its descriptor without the kind (the call names it) and with its
 *  content schema's version alone (the application is the registrar's). */
export type KindRegistration = Omit<KindDescriptor, 'kind' | 'contentSchema'> & { contentSchema?: { version: number } }

/** The generic context the host hands the `detail` component of an opened node, always through core's page
 *  `core/node/components/NodePage.vue`, which draws the node's header above it (core's `NodeHeader`, with core's own actions
 *  of the node - the heart, the cover, copy, download, Report, `core/node/coreNodeActions.ts`); the detail is the body and
 *  contributes to that header only as data (`contributeNodeHeader`, `core/node/nodeHeaderContribution.ts`). What the detail
 *  asks of the page - rename, move, delete, share, a child's create, opening a node - goes through the page's doors, which
 *  it injects (`core/node/nodeHost.ts` `injectNodeHost`: the very doors a row factory is handed; an absent one is an
 *  action not offered). The application's own host component maps it to whatever its detail view takes. What the detail
 *  offers is its application's rights function over `access` and the node (`core/access.ts`), narrowed to the page's doors
 *  (`hostRights`), never a flag the host decides. */
export interface NodeDetailProps {
  node: TreeNode
  scopeId: string
  epochs: ScopeKey | null
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

/** What the host hands a kind's `createDialog`: the kind to create (one component may serve several kinds), the scope,
 *  its epoch keys (a create seals under the write epoch), and the parent — `parentId` null at the scope root, else the
 *  parent's id and its content key, which the new node's key wraps under (ADR 0080 Decision 1). The dialog is mounted
 *  while open; it emits `done` after a create (the host reloads the tree) and `close`. */
export interface NodeCreateDialogProps {
  kind: NodeKind
  scopeId: string
  epochs: ScopeKey
  parentId: string | null
  parentKey: Uint8Array | null
  /** The parent's own epoch as read (`TreeNode.epoch`): the create states it as `parentEpoch` (ADR 0114 decision 3).
   *  Null at the scope root. */
  parentEpoch: number | null
  /** The scope epoch the parent's key was minted under (`TreeNode.scopeEpoch`): the device floor's (ADR 0115 decision
   *  6). Null at the scope root. */
  parentScopeEpoch: number | null
}
export interface NodeCreateDialogEmits {
  done: []
  close: []
}

/** What the host hands a kind's `shareDialog`: the node, its content key as the key chain opens it (what a link wraps;
 *  key material), the scope's epoch keys (each link's recovery copy opens under `K_SC`), the node's name as the tree
 *  opened it (re-sealed under `K_share`) and its kind. `view`, `contribute`, `upload`: offer the `READ`, `READ_WRITE`,
 *  `WRITE_ONLY` role for a NEW link, from the `share` door's `NodeShareOffer` (`core/node/nodeHost.ts`); each absent is
 *  not offered (fail closed) - a dialog whose roles its kind or the door already decides ignores them. Mounted while
 *  open; emits `close`. */
export interface NodeShareDialogProps {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  /** The node's epoch as read (`TreeNode.epoch`): the epoch `nodeKey` was minted at, which a mint or re-issue states as
   *  `nodeEpoch` (ADR 0109 point 6). */
  nodeEpoch: number
  /** The scope epoch the node's key was minted under (`TreeNode.scopeEpoch`): what a create under the node states - the
   *  folder dialog's upload-only link births its node there (`app.folderUploadShare`). Absent: no such create is offered. */
  nodeScopeEpoch?: number
  epochs: ScopeKey
  nodeName: string
  nodeKind: NodeKind
  view?: boolean
  contribute?: boolean
  upload?: boolean
  /** The scope is pending deletion (`MemberAccess.readonly`, set by the host for every kind's dialog, core's rule): the
   *  dialog lists the node's links and copies them, and switches the caller's own live link off, but mints none and
   *  changes none else - the server refuses every share write during the grace window (`ScopeAccess`'s WRITE answer
   *  `Deleting`) but a revoke, which only closes exposure and stays if the deletion is stopped (uc.spaces.scope-access
   *  A1); it serves no guest then, so a link left on is dormant until a restore. */
  readonly?: boolean
}

/** What core's folder share dialog hands an application's {@link FolderUploadShare.create}: the member's scope and epoch keys,
 *  the folder the new node lands under (`null`: the scope root) and the link's settings - its name, which names the node,
 *  its password (empty: none) and its end. */
export interface FolderUploadShareInput {
  scopeId: string
  epochs: ScopeKey
  parent: NodeParent
  name: string
  password: string
  expiresAt: Date | null
}

/** What an application registers with `app.folderUploadShare` (see the header): the upload-only link of a folder level. */
export interface FolderUploadShare {
  /** The application's fail-closed rights answer for the level on screen (`ctx.folder`, null: the scope root): whether the
   *  viewer may create the node and its link there. */
  allowed(ctx: LevelActionContext): boolean
  /** The i18n key, of the application's own namespace, of the sentence the dialog says for the role: what the link does. */
  hint: string
  /** Birth the node named `input.name` under `input.parent` and mint its first `WRITE_ONLY` link; resolves with the link's
   *  id and key (the dialog builds the address). Rejects with the write's error; a node born before a failed mint stays. */
  create(input: FolderUploadShareInput): Promise<MintedShare>
}

/** A registered {@link FolderUploadShare} as core reads it: with the kind it creates. */
export interface FolderUploadShareRegistration extends FolderUploadShare {
  kind: NodeKind
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
  /** The session ends (sign-out, a refused refresh, another tab's announcement): undo what the application left OUTSIDE
   *  this page, before the ending loads the sign-in page as a new document - Memories' music frame forgets the vendor's
   *  sign-in (ADR 0131 decision 5). Bounded by the ending (`app/sessionEndingSteps.ts`): it is given up after its budget,
   *  and a rejection is ignored. Not run on a lock. */
  onSessionEnding?(ctx: SessionEndingContext): Promise<void>
}

/** An application of this build, as `registerApplication` named it. */
export interface ApplicationInfo {
  /** Its own entry of the capability register (`memories`): its switch, ADR 0079 decision 8. */
  id: string
  /** The i18n key, of the application's own namespace, of its name (`memory.memories`). */
  name: string
}

/**
 * What an application registers through (see the header): the scoped registrar `registerApplication` answers. Every
 * call is write-once and refuses at runtime what would make the registry ambiguous.
 */
export interface ApplicationRegistrar {
  /** The application's id - its entry of the capability register: the one place the id lives on the web side. */
  readonly id: string
  /** Register a node kind this application owns. Throws if the kind is already registered (the platform's own FOLDER
   *  included), if its `list` names a row factory another kind already named with different `views`, on a create menu
   *  entry without a `createDialog`, on a level action id already taken, and on `listed: false` beside a `list`. */
  kind(kind: NodeKind, descriptor: KindRegistration): void
  /** Decide the application's route for every kind it owns: its URL slug, or `null` for NO segment (see the header).
   *  Throws on a second decision, on a slug that is malformed, reserved or already claimed, and on `null` when another
   *  application already has no segment. */
  route(slug: string | null): void
  /** Register the application's lifecycle hooks. Throws on a second hook set and if `onMasterKeyRotated` is not a
   *  function. */
  lifecycle(hooks: LifecycleHooks): void
  /** Register the application's part of a rotation's scope-level re-wrap (see the header). Throws on a second one. */
  scopeRekey(participant: ScopeRekeyParticipant): void
  /** Claim the drop page of a `WRITE_ONLY` link rooted at `rootKind` (see the header). Throws if that kind is already
   *  claimed, if it is not a kind this application registered, and for FOLDER. */
  shareBody(rootKind: NodeKind, body: Component): void
  /** Offer the folder share dialog's upload-only link (see the header): `share.create` births a node of `kind` and mints its
   *  `WRITE_ONLY` link. Throws on a second registration (by any application), for a kind that is not this application's,
   *  and for a kind whose drop page this application has not claimed (`shareBody` first). */
  folderUploadShare(kind: NodeKind, share: FolderUploadShare): void
  /** Add a section to a person's profile page (`#people/<userId>`, ADR 0083): what an application knows of the person -
   *  Memories' "Memories with this person". The component takes the props `PersonSectionProps` and draws its own loading,
   *  empty and error states. Sections show in registration order; the same component twice throws. */
  personSection(component: Component): void
  /** Claim the review of a reported item (props `ItemReviewProps`): what a moderator sees of an application's record.
   *  The same component twice throws. */
  itemReview(component: Component): void
  /** Add the application's objects to the search of every space (ADR 0125 decision 8a, {@link ApplicationSearch}): Memories'
   *  photos and videos. Throws on a second one. */
  search(search: ApplicationSearch): void
  /** Name the keyboard shortcuts of the application's own screens, in the order the overview lists them (see the header).
   *  Throws on a second call, on a group without an entry and on an entry without a key. */
  shortcuts(groups: readonly ShortcutGroup[]): void
  /** Fill the shell's footer bar (see the header): a component core mounts once per shell, below its footer, for as long
   *  as that shell stands - every page of the signed-in app, and the share page. It takes no props and draws nothing while
   *  it has nothing to show. With [shown], core mounts it only while that answers true (read reactively): a bar that
   *  exists only under a condition of its application's. Throws on a second one of this application and on a component
   *  another already registered. */
  footerBar(component: Component, shown?: () => boolean): void
}

export interface Registry {
  /** Register one of core's own kinds beside FOLDER (core's bookmark, ADR 0120): served like an application's kind - its
   *  rows, page and create entry - but owned by no application, so no switch turns it off, it claims no route segment and
   *  names no content schema. Called once per kind by the composition root (`src/applications.ts`), before the
   *  applications; a kind registered before throws. */
  registerCoreKind(kind: NodeKind, registration: KindRegistration): void
  /** Name an application - its register id and its name's i18n key - and answer its registrar (see the header). Throws on
   *  an id named before. */
  registerApplication(info: ApplicationInfo): ApplicationRegistrar
  /** The applications named, in registration order. */
  applications(): readonly ApplicationInfo[]
  /** The id of the application that registered `kind`, or null: core's FOLDER, a kind nobody registered. */
  applicationOf(kind: NodeKind): string | null
  /** The drop page claimed for a `WRITE_ONLY` link's root kind, or null when nobody claimed it (the shell renders its
   *  unavailable state — never an empty page). */
  shareBodyFor(kind: NodeKind): Component | null
  /** The folder's upload-only link an application registered, or null: none offered (core's
   *  `core/sharing/folderUploadShare.ts` decides who is offered it). */
  folderUploadShare(): FolderUploadShareRegistration | null
  /** The sections claimed for a person's profile page, in registration order. */
  personSections(): readonly Component[]
  /** The item reviews claimed, in registration order — each draws only the items that are its application's. */
  itemReviews(): readonly Component[]
  /** Every application's part in the search of a whole space, in registration order. */
  searches(): readonly { application: string; search: ApplicationSearch }[]
  /** Every application's shortcut groups, in registration order, each with the application that named them. */
  shortcuts(): readonly { application: string; groups: readonly ShortcutGroup[] }[]
  /** Every application's footer bar, in registration order, each with the application that registered it and its
   *  condition, if any. */
  footerBars(): readonly { application: string; component: Component; shown?: () => boolean }[]
  /** Run every application's `onScopeReset`. */
  scopeReset(): void
  /** Run every application's `onScopeEntered`, in registration order; rejects with the first failure after all ran. */
  scopeEntered(scopeId: string): Promise<void>
  /** Run every application's `onMasterKeyRotated`; rejects with the first failure after all ran. */
  masterKeyRotated(oldMk: Uint8Array, newMk: Uint8Array, scopeIds: string[]): Promise<void>
  /** Every application's scope re-key participant, in registration order (ADR 0115 decision 2). */
  scopeRekeyParticipants(): readonly ScopeRekeyParticipant[]
  /** The kind's descriptor, or `{ supported: false }` — never `undefined`. */
  kind(kind: NodeKind): KindLookup
  /** Every registered kind, the platform's FOLDER first, then in registration order: what the composition's tests walk
   *  (each kind's application, route and page), never a list core renders from. */
  kinds(): readonly NodeKind[]
  /** Whether the level draws a node of `kind` (`KindDescriptor.listed`): false only for a kind registered `listed: false`;
   *  a kind nobody registered is listed - as the unsupported row. */
  listed(kind: NodeKind): boolean
  /** The string a node's blinded slug token HMACs (`core/node/tree.ts` `slugTokenFor`), and so what its name is unique by
   *  among its siblings (ADR 0080 decision 16): the name's slug by its kind's own `slug` hook, else `slugify` (also for a
   *  kind nobody registered: the server stores any kind, and a token must still be computable for it), behind
   *  `<routeSlug>/` for
   *  a kind whose application claimed a route slug (`memories/korfubanien`, `expenses/korfubanien`), the bare slug for a
   *  folder, Depot's kinds and a kind whose application decided no route. A name is unique within its route group only, as the URL already
   *  separates them (`#foo/memories/korfubanien`, `#foo/expenses/korfubanien`): a Memory and a Pot of one name are
   *  siblings, a folder and a Depot file of one name still meet. A route slug is `[a-z0-9-]` and the `/` ends it, so two
   *  applications' inputs never meet, and a container's `slugify` never yields a `/`. A file's folding keeps one: a Depot
   *  file literally named `memories/x` still meets a Memory `x` (accepted, like a folder `a.b` and a file `a-b`). Every
   *  token and every client-side name check (`core/node/siblingCollision.ts`, the guest's, an import's numbering) goes
   *  through this, so they agree with the server, which compares tokens. */
  tokenInputFor(kind: NodeKind, name: string): string
  /** Whether the application of `kind` decided its route (`app.route`: a slug, or none). False for FOLDER (the
   *  platform's), for a kind nobody registered and for one whose application decided nothing. */
  routeDecided(kind: NodeKind): boolean
  /** How a node of `kind` is addressed in the path: its application's `slug` (null: none — a folder, the application
   *  that declared no segment, a kind whose application decided nothing or nobody registered) and the `group` that kind's
   *  nodes are numbered in among equal siblings — one per application with a segment, one for the folders, one for every
   *  node with none (Depot and the kinds with no route). */
  routeOf(kind: NodeKind): KindRoute
  /** The claimed route slugs (the applications' own, not the platform's reserved words). */
  routeSlugs(): readonly string[]
  /** Whether a node of `kind` may be placed in a parent of `parentKind` (null: the scope root), by the kind's
   *  `placement` hint — false for a kind that declares none or nobody registered (fail closed). */
  placementAllows(kind: NodeKind, parentKind: NodeKind | null): boolean
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

/** A fresh registry holding only the platform's own kind (FOLDER: browsable, never openable, shareable through core's
 *  own `core/sharing/FolderShareDialog.vue`, its pre-registered `shareDialog` —
 *  uc.sharing.share-public-viewer:folder-share-root). The app uses the single `registry` below; tests build their own
 *  to prove the rules on an untouched instance. */
export function createRegistry(): Registry {
  const kinds = new Map<NodeKind, KindDescriptor>([
    // A folder lives at the root or in another folder (the server's `FolderNodeRules`).
    [FOLDER_KIND, {
      // The folder header's priority (ADR 0110): favorite, then sharing, its content, its look, then editing and moving it.
      kind: FOLDER_KIND, icon: 'folder', header: { order: ['favorite', 'share', 'download', 'move', 'copy'] },
      shareDialog: FolderShareDialog, nounWithArticle: 'node.aFolder',
      placement: { root: true, parents: [FOLDER_KIND] }, cover: {}, copyLosses: ['node.copy.lossFolderCover'],
      rekeyContent: nothingOfItsOwnToRekey, search: { label: 'node.kindFolder' },
    }],
  ])
  const lifecycles = new Map<string, LifecycleHooks>()
  const scopeRekeys = new Map<string, ScopeRekeyParticipant>()
  const applications: ApplicationInfo[] = []
  const applicationOfKind = new Map<NodeKind, string>()
  // The unified list's row sources and tabs (inbox #407), in registration order.
  const listEntries: { rows: LevelRowsFactory; views: readonly LevelViewOption[]; kinds: NodeKind[] }[] = []
  const tabEntries: LevelTabSpec[] = []
  const shareBodies = new Map<NodeKind, Component>()
  let uploadShare: FolderUploadShareRegistration | null = null
  const personSections: Component[] = []
  const itemReviews: Component[] = []
  const searches = new Map<string, ApplicationSearch>()
  const shortcuts = new Map<string, readonly ShortcutGroup[]>()
  const footerBars = new Map<string, { component: Component; shown?: () => boolean }>()
  // application id → its route slug, or null for the application with no segment.
  const routes = new Map<string, string | null>()
  const slugs = new Set<string>()
  /** The route slug of `kind`'s application: a slug, null (no segment), or undefined (nothing decided, or no application). */
  const routeOfKind = (kind: NodeKind): string | null | undefined => {
    const app = applicationOfKind.get(kind)
    return app === undefined ? undefined : routes.get(app)
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
  /** Register `kind` for application `id` (the registrar's `kind`), or as core's own with `id` null (core's bookmark, ADR
   *  0120: no application, so no switch, no route segment and no content schema of an application). */
  function registerKind(id: string | null, kind: NodeKind, registration: KindRegistration) {
    if (kinds.has(kind)) throw new Error(`node kind ${kind} is already registered`)
    const { contentSchema, ...rest } = registration
    const descriptor: KindDescriptor = { ...rest, kind, ...(contentSchema && id !== null ? { contentSchema: { application: id, version: contentSchema.version } } : {}) }
    // Checked before anything is stored, so a contradiction registers nothing.
    if (descriptor.listed === false && descriptor.list) throw new Error(`node kind ${kind} is not listed but names a list`)
    const joinList = listJoin(listEntries, tabEntries, kind, descriptor.list)
    if (descriptor.createEntry && !descriptor.createDialog) {
      throw new Error(`node kind ${kind} has a create menu entry but no createDialog to open`)
    }
    const own = descriptor.levelActions ?? []
    for (const a of own) {
      const taken = a.id.startsWith('create:') || [...kinds.values()].some((k) => k.levelActions?.some((x) => x.id === a.id))
      if (taken || own.filter((x) => x.id === a.id).length > 1) {
        throw new Error(`level action ${a.id} is already registered (or reserved)`)
      }
    }
    kinds.set(kind, descriptor)
    if (id !== null) applicationOfKind.set(kind, id)
    joinList()
  }
  /** The registrar of application `id` (see the header). */
  function registrar(id: string): ApplicationRegistrar {
    return Object.freeze({
      id,
      kind: (kind: NodeKind, registration: KindRegistration) => registerKind(id, kind, registration),
      route(slug: string | null) {
        if (routes.has(id)) throw new Error(`the route of application ${id} is already decided`)
        if (slug === null) {
          if ([...routes.values()].includes(null)) throw new Error('an application already declared that it has no route segment')
        } else {
          if (!ROUTE_SLUG.test(slug)) throw new Error(`route slug ${JSON.stringify(slug)} is not a slug ([a-z0-9-], starting with a letter)`)
          if (RESERVED_ROUTE_SEGMENTS.includes(slug)) throw new Error(`route slug ${slug} is reserved by the platform`)
          if (slugs.has(slug)) throw new Error(`route slug ${slug} is already claimed`)
          slugs.add(slug)
        }
        routes.set(id, slug)
      },
      lifecycle(hooks: LifecycleHooks) {
        if (lifecycles.has(id)) throw new Error(`lifecycle hooks of ${id} are already registered`)
        if (typeof hooks.onMasterKeyRotated !== 'function') {
          throw new Error(`application ${id} registered no onMasterKeyRotated hook (it is required)`)
        }
        lifecycles.set(id, { ...hooks })
        if (hooks.onSessionEnding) onSessionEnding(hooks.onSessionEnding.bind(hooks))
      },
      scopeRekey(participant: ScopeRekeyParticipant) {
        if (scopeRekeys.has(id)) throw new Error(`the scope re-key of ${id} is already registered`)
        if (typeof participant !== 'function') throw new Error(`application ${id} registered no scope re-key function`)
        scopeRekeys.set(id, participant)
      },
      shareBody(rootKind: NodeKind, body: Component) {
        if (rootKind === FOLDER_KIND || shareBodies.has(rootKind)) throw new Error(`share body for ${rootKind} is already claimed`)
        if (applicationOfKind.get(rootKind) !== id) throw new Error(`node kind ${rootKind} is not a kind of ${id}: its share body is refused`)
        shareBodies.set(rootKind, body)
      },
      folderUploadShare(kind: NodeKind, share: FolderUploadShare) {
        if (uploadShare) throw new Error('the folder upload-only link is already registered')
        if (applicationOfKind.get(kind) !== id) throw new Error(`node kind ${kind} is not a kind of ${id}: its upload-only link is refused`)
        if (!shareBodies.has(kind)) throw new Error(`node kind ${kind} has no drop page: claim it (shareBody) before its upload-only link`)
        uploadShare = Object.freeze({ kind, allowed: share.allowed, hint: share.hint, create: share.create })
      },
      personSection(component: Component) {
        if (personSections.includes(component)) throw new Error('this person-profile section is already claimed')
        personSections.push(component)
      },
      itemReview(component: Component) {
        if (itemReviews.includes(component)) throw new Error('this item review is already claimed')
        itemReviews.push(component)
      },
      search(search: ApplicationSearch) {
        if (searches.has(id)) throw new Error(`the search of ${id} is already registered`)
        if (typeof search?.scopeDocuments !== 'function') throw new Error(`application ${id} registered no scopeDocuments hook`)
        searches.set(id, search)
      },
      shortcuts(groups: readonly ShortcutGroup[]) {
        if (shortcuts.has(id)) throw new Error(`the shortcuts of ${id} are already registered`)
        for (const g of groups) {
          if (g.entries.length === 0) throw new Error(`the shortcut group ${g.title} of ${id} has no entry`)
          for (const e of g.entries) {
            if (e.keys.length === 0 || e.keys.some((chord) => chord.length === 0)) throw new Error(`the shortcut ${e.label} of ${id} names no key`)
          }
        }
        shortcuts.set(id, Object.freeze(groups.map((g) => Object.freeze({ ...g, entries: Object.freeze([...g.entries]) }))))
      },
      footerBar(component: Component, shown?: () => boolean) {
        if (footerBars.has(id)) throw new Error(`the footer bar of ${id} is already registered`)
        if ([...footerBars.values()].some((b) => b.component === component)) throw new Error('this footer bar is already registered')
        footerBars.set(id, shown === undefined ? { component } : { component, shown })
      },
    })
  }
  return {
    registerCoreKind: (kind, registration) => registerKind(null, kind, registration),
    registerApplication({ id, name }) {
      if (applications.some((a) => a.id === id)) throw new Error(`application ${id} is already registered`)
      applications.push({ id, name })
      return registrar(id)
    },
    applications: () => applications.map((a) => ({ ...a })),
    applicationOf: (kind) => applicationOfKind.get(kind) ?? null,
    shareBodyFor: (kind) => shareBodies.get(kind) ?? null,
    folderUploadShare: () => uploadShare,
    personSections: () => [...personSections],
    itemReviews: () => [...itemReviews],
    searches: () => [...searches].map(([application, search]) => ({ application, search })),
    shortcuts: () => [...shortcuts].map(([application, groups]) => ({ application, groups })),
    footerBars: () => [...footerBars].map(([application, bar]) => ({ application, ...bar })),
    scopeReset() {
      // Synchronous on purpose: callers (enterScope, clearActiveSpace) rely on the state being gone on return.
      for (const h of lifecycles.values()) h.onScopeReset?.()
    },
    scopeEntered: (scopeId) => eachApplication((h) => h.onScopeEntered?.(scopeId)),
    masterKeyRotated: (oldMk, newMk, scopeIds) => eachApplication((h) => h.onMasterKeyRotated(oldMk, newMk, scopeIds)),
    scopeRekeyParticipants: () => [...scopeRekeys.values()],
    kind(kind) {
      const d = kinds.get(kind)
      return d ? { supported: true, ...d } : { supported: false, kind }
    },
    kinds: () => [...kinds.keys()],
    listed: (kind) => kinds.get(kind)?.listed !== false,
    tokenInputFor(kind, name) {
      const slug = (kinds.get(kind)?.slug ?? slugify)(name)
      // FOLDER belongs to no application; Depot decided no slug (null), a kind whose application decided nothing is undefined
      const route = routeOfKind(kind)
      return route ? `${route}/${slug}` : slug
    },
    routeDecided: (kind) => routeOfKind(kind) !== undefined,
    routeOf(kind) {
      if (kind === FOLDER_KIND) return { slug: null, group: 'folder' }
      const slug = routeOfKind(kind)
      // A kind with no route decided shares the unprefixed namespace with the application that has no segment, so its names are
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
