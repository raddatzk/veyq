// The application registry (ADR 0079): what the shell asks instead of
// importing an application. Core renders by node kind and by list slot; an application says, once, what it renders for
// which kind and which list items it adds.
//
// **Build-time only (ADR 0079 Decision 5, ADR 0026).** The registry is filled by plain function calls from ONE
// composition root (`web/src/applications.ts`), which statically imports each application. There is no glob, no
// computed `import()`, no plugin loading: what runs is what was reviewed. The guard
// (`scripts/validate_web_core_imports_no_applications.py`) keeps core from importing an application and from
// discovering one by glob.
//
// **Write-once.** Registered code runs with every unlocked key, so the registry is a trust surface and not a
// convenience: registering a kind twice, claiming a share body twice, or adding a list-slot item under a taken id THROWS —
// it never overwrites and never merges. Nothing here is exported as mutable state; `registry` exposes only the two register doors and read-only lookups.
//
// **Dialogs per kind (cr-files-application P4c).** A kind brings its dialogs with its descriptor: `createDialog` (the
// dialog that creates a node of the kind, mounted by the shell when a section or a detail asks for a create of that
// kind; props `NodeCreateDialogProps`) and `shareDialog` (the share management dialog of a node of the kind; props
// `NodeShareDialogProps`). The platform pre-registers FOLDER's share dialog (`core/sharing/FolderShareDialog.vue`) the
// way it pre-claims FOLDER's share body; a folder's create and every node's rename are core's own `FolderNameDialog`
// (`core/node/FolderNameDialog.vue`), which the shell mounts itself — a rename re-seals the name and derives the slug
// token by the node's kind (`slugFor`), whatever application it belongs to. A kind with no `shareDialog` offers no
// share; a create for a kind with no `createDialog` opens nothing. Every shell slot is a list slot (below).
//
// **Level sections (`KindDescriptor.section`).** The folder level view is core's (`core/node/LevelView.vue`, cr-files-
// application P4b): the folder's overview card, then one SECTION per application, then the sub-folders. A kind brings
// its section with its descriptor — a component that renders its own `<section>` (heading, create action, view modes)
// from `LevelSectionProps`. Several kinds may name the SAME component (Memories: MEMORY and CHAPTER): a section is keyed
// by its component's identity, rendered once, and handed the children of every kind that names it (in the order the
// kinds were registered). `sections()` lists them sorted by `order`, a tie in the order the section was first named.
// Two kinds naming one component with two different `order`s is a contradiction, so that `registerKind` throws (and
// registers nothing). A child whose kind no section serves — registered without one, or not registered at all — is
// not dropped: the level view renders core's fallback section for it (its icon, its name, the explicit unsupported
// state), never an empty area.
//
// **Share sections (`KindDescriptor.shareSection`, cr-files-application F2).** The guest's folder page of a public
// share is core's too (`core/sharing/FolderShareBody.vue`): at a folder it renders the sub-folders and every other child
// as a card, and a kind that brings a share section has its children rendered by THAT component instead (props
// `ShareSectionProps`) — Files' file list with its download and a contributor's upload. The same rules as the level
// sections: keyed by component, sorted by `order`, rendered at every folder (an empty one too), a contradicting `order`
// throws. A kind without one keeps its card, which opens the body its application claimed (below).
//
// **List slots (`addSlotItem`).** The top-bar tray and the sidebar pins are places where several applications each add
// their own entry, so they are ordered lists: an item is registered under an id (namespaced by its application, e.g.
// `memories.pinned`), write-once per (slot, id) — a second registration of the same id in the same list throws. The
// shell renders them sorted by `order` (default: registration order; a tie keeps registration order). `addSlotItem`
// refuses a name that is no list slot at runtime too, so a caller outside the type checker gets an error, never a
// silent no-op.
//
// **Share bodies (`claimShareBody`).** The public share page (`views/ShareView.vue`) is a shell: it resolves the
// kind-independent half (core/sharing/resolvePublicShare.ts), runs the password and terms gates, then renders the body
// the application claimed for the share's ROOT node kind (`PublicShare.rootKind`, served for every role — a WRITE_ONLY
// share too, so its drop page is the body of its kind's application). A kind is claimed once (a second claim throws). A
// root kind nobody claimed, or none at all, renders the shell's explicit unavailable state. A claim is also what core's
// folder page EMBEDS for a node of the kind below a shared folder (`subtreeRoot`), unless it is claimed `rootOnly`: a
// body that serves only a link rooted at its own node (Files' drop box, whose page is a WRITE_ONLY guest's upload form —
// embedded in a READ folder link it offered that form under the folder's name and failed, inbox #380). Core's folder
// page then shows no node of that kind and nothing below it, and treats `?node=` naming one as a node it did not open.
// Hiding is a rendering rule, not a gate: the served subtree still holds those nodes and their keys.
//
// **Lifecycle hooks (`registerLifecycle`).** What runs when a scope is entered or reset and when the master key
// rotates. Registered per application id (a second registration of the same id throws); several applications may
// each contribute, and the registry runs them all. The master-key hook is REQUIRED: material sealed under the master
// key (Memories' pins) is undecryptable after a rotation that skipped it, so `rotateKeys` asks
// `assertRotationHooks()` before it touches anything and refuses to start when no registered application carries the
// hook (a composition root that registered nothing is that case too).
//
// **Fail closed.** `kind()` never returns `undefined`: a node kind nobody registered resolves to
// `{ supported: false }`, which the host renders as an explicit unsupported state, never as an empty region. The kind
// is an open string token (`core/node/kind.ts`), so no compiler checks that every kind is handled: this answer and its
// tests (`registry.test.ts`) are what does.
import { defineAsyncComponent, type Component } from 'vue'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import type { TreeNode } from '../node/tree'
import type { Level } from '../node/browse'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { PublicShareHead } from '../sharing/resolvePublicShare'
import type { GuestIdentity } from '../sharing/guestIdentity'
import type { ShareNode } from '../../api/generated/sharing'
import { slugify } from '../../shared/naming'

// The platform's own share dialog for a FOLDER (cr-node-key-hierarchy 5b.3), FOLDER's `shareDialog` since P4c. LAZY for
// the reason the share body below is: the dialog's module graph reaches this registry again (measured: `shareLink` →
// `manageShares` → `shareTree` → `core/node/tree`, which asks `registry.slugFor`), so a static import is a cycle.
// `.default` explicitly: a test's `vi.mock` of the module is no ES-module namespace Vue would unwrap on its own.
const FolderShareDialog = defineAsyncComponent(() => import('../sharing/FolderShareDialog.vue').then((m) => m.default))

// The platform's own share body for a link rooted at a FOLDER (cr-node-key-hierarchy 5b.4b). LAZY, and on purpose: the
// body reads this registry (`registry.kind`, `registry.shareBody` for what it embeds), so a static import here is a
// cycle — measured: with `FolderShareBody.vue` evaluated first, the app's `registry` below was built while the body's
// module was still mid-evaluation and answered null for FOLDER (`createRegistry()` called later answered it). A literal
// `import()` is still build-time code (bundled and reviewed like every other module, ADR 0079 Decision 5): nothing here
// is computed or discovered.
const FolderShareBody = defineAsyncComponent(() => import('../sharing/FolderShareBody.vue'))

/** What core needs to know about a node kind. `detail` is the component the host renders when a node of the kind is
 *  opened. Only the platform's FOLDER has none (a folder browses, it does not open): an application kind that
 *  registers no `detail` renders the host's explicit unsupported state when opened. */
export interface KindDescriptor {
  kind: NodeKind
  detail?: Component
  /** The PrimeIcons class core draws next to a node of this kind (e.g. the trash list). */
  icon: string
  /** The i18n key of the title core's delete dialog (`core/node/components/DeleteNodeDialog.vue`) shows for a node of
   *  this kind, e.g. Memories' "Delete Memory". Omitted: the neutral `node.delete`. Core serves every application's
   *  nodes and so names none in its own strings (inbox #375): a kind's noun comes from the kind. */
  deleteTitle?: string
  /** Whether a public share can be created for a node of this kind (the capability check still applies). */
  shareable: boolean
  /** How a name of this kind folds before it is HMAC'd into its blinded slug token (inbox #350; read through
   *  {@link Registry.slugFor}). Omitted: `slugify`, the container folding that is also the URL segment. A kind that is
   *  never a URL segment may fold more strictly (a file: `fileNameSlug`). **Frozen into stored tokens**: the function
   *  a kind registers must never change once a node of the kind is stored, and must be pinned by vectors. */
  slug?: (name: string) => string
  /** The section the folder level view renders for this kind's children (see the header): `component` takes
   *  `LevelSectionProps` and emits `LevelSectionEmits`; `order` sorts the sections (ascending). Omitted: a child of the
   *  kind shows in core's fallback section. Kinds naming the same component share ONE section. */
  section?: KindSection
  /** The dialog that creates a node of this kind (props `NodeCreateDialogProps`, emits `NodeCreateDialogEmits`),
   *  mounted by the shell when a section emits `create` or a detail emits `createChild` with this kind. Omitted: a
   *  create of the kind opens nothing. FOLDER has none: its create is core's `FolderNameDialog`. */
  createDialog?: Component
  /** The share management dialog of a node of this kind (props `NodeShareDialogProps`, emits `close`). Omitted: the
   *  kind offers no share, whatever `shareable` says. FOLDER's is core's own `FolderShareDialog`, pre-registered. */
  shareDialog?: Component
  /** The section the public share's folder page renders for this kind's children (see the header): `component` takes
   *  `ShareSectionProps`. Omitted: a child of the kind is a card on that page. */
  shareSection?: KindSection
}

/** A kind's level section: the component and where it sorts among the sections. */
export interface KindSection {
  order: number
  component: Component
}

/** A level section as the level view renders it: the component, its order, and every kind that named it (in
 *  registration order) — the section receives the children of all of them. */
export interface LevelSection {
  component: Component
  order: number
  kinds: readonly NodeKind[]
}

export type KindLookup = ({ supported: true } & KindDescriptor) | { supported: false; kind: NodeKind }

/** The generic context the host hands the `detail` component of an opened node (event names below). The
 *  application's own host component maps it to whatever its detail view takes. */
export interface NodeDetailProps {
  node: TreeNode
  scopeId: string
  epochs: EpochKeys | null
  /** The opened node's content key, resolved by walking the content-key chain (null while the keys are not ready). */
  contentKey: Uint8Array | null
  /** The opened node's children, bucketed by kind (`byKind`). */
  children: Level
  /** Whether a public share may be offered for this node: the kind's descriptor AND the instance capability. */
  shareable: boolean
}
/** Events a `detail` component emits to the host: `rename` (core's `FolderNameDialog`, any kind), `delete`,
 *  `createChild` (the KIND of the child to create: the host mounts that kind's `createDialog` under the opened node),
 *  `openNode` (a child), `share` and `shareUpload` (the opened kind's `shareDialog`, the second with `uploader`). */
export interface NodeDetailEmits {
  rename: []
  delete: []
  createChild: [kind: NodeKind]
  openNode: [node: TreeNode]
  share: []
  shareUpload: []
}

/** What the folder level view (`core/node/LevelView.vue`) hands an application's section: the folder (or the scope
 *  root, `folder` null) and the children of every kind that names the section, the scope's epoch keys, and whether
 *  the scope is read-only (pending deletion: no create action). */
export interface LevelSectionProps {
  folder: TreeNode | null
  nodes: readonly TreeNode[]
  epochs: EpochKeys | null
  readonly: boolean
}
/** Events a section emits to the level view: `openNode` (a child), `create` (the KIND to create at this level, e.g.
 *  Memories' MEMORY: the host mounts that kind's `createDialog`), `rename` (a child renamed in place, for a kind that
 *  does not open — Files' file: the host mounts core's `FolderNameDialog` in rename mode for it). The scope's node trash
 *  is the level view's own toolbar button, no section's (inbox #370, #385). */
export interface LevelSectionEmits {
  openNode: [node: TreeNode]
  create: [kind: NodeKind]
  rename: [node: TreeNode]
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
}
export interface NodeCreateDialogEmits {
  done: []
  close: []
}

/** What the host hands a kind's `shareDialog`: the node, its content key as the key chain opens it (what a link wraps;
 *  key material), the scope's epoch keys (each link's recovery copy opens under `K_SC`), the node's name as the tree
 *  opened it (re-sealed under `K_share`) and its kind. `uploader`: the uploader (WRITE_ONLY drop) variant, set only on
 *  the detail's `shareUpload` — a kind whose dialog has no such variant ignores it. Mounted while open; emits `close`. */
export interface NodeShareDialogProps {
  scopeId: string
  nodeId: string
  nodeKey: Uint8Array
  epochs: EpochKeys
  nodeName: string
  nodeKind: NodeKind
  uploader?: boolean
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

/** What the shell hands a share body (event: `unavailable` — the body found the share unusable after the shell had
 *  already shown it). The body renders everything below the password and terms gates: its own name pre-screen, its
 *  content, and any overlay. */
export interface ShareBodyProps {
  shareId: string
  /** The URL-fragment key (`#k=`), for the application's own key-proof calls. Key material. */
  fragmentKey: string
  /** The guest password token, when the share is password-protected and unlocked. */
  pwToken: string | undefined
  /** The platform half of the resolve. A new object after `reload()`. */
  head: PublicShareHead
  /** Re-resolve the share (after a contribution, so the new media lists); resolves once `head` has been replaced. */
  reload: () => Promise<void>
  /** Set only when a container's body embeds this one for a node BELOW the share root (core's folder body for a Memory
   *  in a shared folder, cr-node-key-hierarchy 5b.4b): that node's id, its key and its name as the chain opened them.
   *  The body renders that node, not the root; the share's note is the container's to show. The shell never sets it. */
  subtreeRoot?: { nodeId: string; key: Uint8Array; name: string }
}
export interface ShareBodyEmits {
  unavailable: []
}

/** What core's folder page of a public share (`core/sharing/FolderShareBody.vue`) hands a kind's `shareSection`: the
 *  share as the shell resolved it, the folder on screen and the children of every kind that names the section (the
 *  served rows; their keys and names are what the chain opened — key material), whether the link takes writes, and
 *  `requestGuest`: the guest's identity for this link, asking the visitor's name first when it has none (null: the
 *  visitor declined). After a write the section calls `reload` so the new nodes list. */
export interface ShareSectionProps {
  shareId: string
  fragmentKey: string
  pwToken: string | undefined
  head: PublicShareHead
  reload: () => Promise<void>
  folderId: string
  nodes: readonly ShareNode[]
  keys: ReadonlyMap<string, Uint8Array>
  names: ReadonlyMap<string, string>
  canWrite: boolean
  requestGuest: () => Promise<GuestIdentity | null>
}

/** The list slots: several applications may each add items (see the header). A `topbarTray` item takes no props (the
 *  shell's top bar mounts it after the invitations badge and core's own upload status menu); a `sidebarPins` item
 *  takes `SidebarPinsProps`. */
export type ListSlotName = 'topbarTray' | 'sidebarPins'

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

export interface Registry {
  /** Register an application's lifecycle hooks under its id. Throws if the id is already registered, or if
   *  `onMasterKeyRotated` is not a function. */
  registerLifecycle(applicationId: string, hooks: LifecycleHooks): void
  /** Claim the public-share body for a root kind, whatever the share's role. Throws if that kind is already claimed.
   *  `rootOnly`: the body serves only a link rooted at a node of the kind and is never embedded below a shared folder
   *  (see the header). */
  claimShareBody(kind: NodeKind, component: Component, options?: { rootOnly?: boolean }): void
  /** The body claimed for a share's root kind, or null when nobody claimed it (the shell renders its unavailable
   *  state — never an empty page). */
  shareBody(kind: NodeKind): Component | null
  /** Whether the kind's body was claimed `rootOnly`: core's folder page of a share shows no node of the kind. False for
   *  a kind nobody claimed (its card stays, and opens the explicit unsupported state). */
  shareBodyRootOnly(kind: NodeKind): boolean
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
   *  `section` names a component another kind already named with a different `order`. */
  registerKind(descriptor: KindDescriptor): void
  /** Add an item to a list slot under `id`. Throws if that slot already holds an item with this id. `order` sorts the
   *  list (ascending); omitted, the item's registration index is used. Ties keep registration order. */
  addSlotItem(slot: ListSlotName, id: string, component: Component, order?: number): void
  /** The kind's descriptor, or `{ supported: false }` — never `undefined`. */
  kind(kind: NodeKind): KindLookup
  /** The slug of `name` for a node of `kind`: the kind's own `slug` hook, else `slugify` (also for a kind nobody
   *  registered: the server stores any kind, and a token must still be computable for it). Every slug token is
   *  derived through this, so a node's token always uses ITS kind's folding (`core/node/tree.ts` `slugTokenFor`). */
  slugFor(kind: NodeKind, name: string): string
  /** The items of a list slot, sorted by `order` (stable); empty when nobody added one (the shell renders nothing). */
  slotItems(slot: ListSlotName): readonly SlotItem[]
  /** The level sections, one per distinct section component, sorted by `order` (stable: a tie keeps the order in which
   *  each section was first named); empty when no kind registered one (the level view still renders the overview, the
   *  fallback and the folders). */
  sections(): readonly LevelSection[]
  /** The share sections (`KindDescriptor.shareSection`), by the same rules as [sections]. */
  shareSections(): readonly LevelSection[]
}

const LIST_SLOTS: readonly string[] = ['topbarTray', 'sidebarPins'] satisfies readonly ListSlotName[]

/** A fresh registry holding only the platform's own kind (FOLDER: browsable, never openable, shareable through core's
 *  own `core/sharing/FolderShareDialog.vue`, its pre-registered `shareDialog` — cr-node-key-hierarchy 5b.3).
 *  The app uses the single `registry` below; tests build their own to prove the rules on an untouched instance. */
export function createRegistry(): Registry {
  const kinds = new Map<NodeKind, KindDescriptor>([
    [FOLDER_KIND, { kind: FOLDER_KIND, icon: 'pi pi-folder', shareable: true, shareDialog: FolderShareDialog }],
  ])
  const lists = new Map<ListSlotName, { id: string; component: Component; order: number }[]>()
  const lifecycles = new Map<string, LifecycleHooks>()
  // One entry per distinct section component, in the order each was first named (the stable sort's tie-break): the
  // level sections and the share sections, each its own list under the same rules.
  const sections: { component: Component; order: number; kinds: NodeKind[] }[] = []
  const shareSections: { component: Component; order: number; kinds: NodeKind[] }[] = []
  // The platform's own share body: a link rooted at a FOLDER browses the subtree (cr-node-key-hierarchy 5b.4b). Pre-claimed
  // like FOLDER's kind above, so an application claiming FOLDER throws.
  const shareBodies = new Map<NodeKind, Component>([[FOLDER_KIND, FolderShareBody]])
  const rootOnlyBodies = new Set<NodeKind>()
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
    claimShareBody(kind, component, options) {
      if (shareBodies.has(kind)) throw new Error(`share body for ${kind} is already claimed`)
      shareBodies.set(kind, component)
      if (options?.rootOnly) rootOnlyBodies.add(kind)
    },
    shareBody: (kind) => shareBodies.get(kind) ?? null,
    shareBodyRootOnly: (kind) => rootOnlyBodies.has(kind),
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
      // Both checked before anything is stored, so a contradiction registers nothing.
      const add = [sectionJoin(sections, descriptor.kind, descriptor.section), sectionJoin(shareSections, descriptor.kind, descriptor.shareSection)]
      kinds.set(descriptor.kind, { ...descriptor })
      for (const f of add) f()
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
    sections: () => sortedSections(sections),
    shareSections: () => sortedSections(shareSections),
  }
}

type SectionEntry = { component: Component; order: number; kinds: NodeKind[] }

/** Check that `kind` may join `list` with `section` (a component already named with another `order` throws) and return
 *  the join, to run once every check of the registration passed. */
function sectionJoin(list: SectionEntry[], kind: NodeKind, section: KindSection | undefined): () => void {
  if (!section) return () => {}
  const shared = list.find((s) => s.component === section.component)
  if (shared && shared.order !== section.order) {
    throw new Error(`node kind ${kind} names the section of ${shared.kinds.join(', ')} with order ${section.order}, not ${shared.order}`)
  }
  return () => {
    if (shared) shared.kinds.push(kind)
    else list.push({ component: section.component, order: section.order, kinds: [kind] })
  }
}

function sortedSections(list: readonly SectionEntry[]): LevelSection[] {
  return [...list]
    .sort((a, b) => a.order - b.order) // stable (ES2019): a tie keeps the order each section was first named
    .map((s) => ({ component: s.component, order: s.order, kinds: [...s.kinds] }))
}

/** The app's registry, filled once by `registerApplications()` (web/src/applications.ts) before the first route
 *  resolves (`main.ts`) and, in tests, by `src/test/setup.ts`. */
export const registry: Registry = createRegistry()
