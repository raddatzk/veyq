// Core's dialogs behind the host doors (`nodeHost.ts`), ONE set for both host pages (owner 2026-11-01): the member's space
// page and a share guest's page each call `useNodeDialogs` once and mount `components/NodeDialogs.vue` with its answer, so
// the name dialog, the create dialogs, the move dialogs, the delete question, the share dialogs, the download, the copy,
// the import and the space's trash each exist once. The host hands on the `doors` its session has (a guest's: the move -
// where its link moves anything -, the download and the create) and adds its own navigation (`open`).
//
// **What the page is.** `env` says who looks, the tree as that session reads it, the node on screen (a create lands under
// the node it is handed; a rename, move, copy or delete remembers what was on screen, which a write may give a new path) and
// what the page does after a write: `stayOn` (the member's page follows the node that was on screen to its path in the
// re-read tree), `leave` (the node a page was about went: its parent's page), `reload` (re-read the tree after a create or
// an import). A guest's page re-reads through its shell and follows nothing, so it passes none of them.
//
// **The member's dialogs read the member's access** (`scopeId`, `epochs`, `readonly`): they mount for nobody else, whatever
// door a host would hand on - a door of a dialog that cannot mount for the session is a host's bug, not a state this page can
// explain. The guest's create is core's naming of the new node (`ShareNameDialog`) through the page's own write
// (`env.guestCreate`).
import { computed, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { sameKeyOr } from './tree'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { SelectedForDelete } from './selection'
import type { ImportOffer } from '../export/importSource'
import { nodeMover, type NodeMover } from './move'
import type { NodeHost, NodeShareOffer } from './nodeHost'

/** The page the dialogs serve (see the header). */
export interface NodeDialogsEnv {
  access: () => Access
  /** The tree as the session reads it; null while it is not read yet (no dialog mounts then). */
  tree: () => TreeSource<TreeNode> | null
  /** The node on screen (null: the scope root). */
  onScreen: () => string | null
  /** The member's scope root as the move and copy browsers name it. */
  rootLabel?: () => string
  /** What the import names as its target (the folder on screen, else the space). */
  importTarget?: () => string
  /** The member's whole tree, which the space's trash reads its trashed nodes against. */
  trashTree?: () => TreeNode[]
  /** After a write re-read the tree: keep `shown` - what was on screen before it - on screen under its new path. */
  stayOn?: (shown: string | null) => void
  /** The node a page was about went: leave for its parent. */
  leave?: (node: TreeNode) => void
  /** Re-read the tree (a create, an import, a restore wrote). */
  reload?: () => Promise<void>
  /** A guest's create (READ_WRITE): write a node of `kind` named `name` under `parentId`; and the message of its refusal. */
  guestCreate?: { create(kind: NodeKind, parentId: string, name: string): Promise<void>; errorText(e: unknown): string }
}

/** Every door a dialog of this set opens (`open` is the host's own navigation). */
export type NodeDialogDoors = Required<Omit<NodeHost, 'open'>>

/** The open dialogs, each null while closed. */
export interface NodeDialogState {
  name: Ref<{ mode: 'create'; parent: TreeNode | null } | { mode: 'rename'; node: TreeNode; shown: string | null } | null>
  create: ShallowRef<{ kind: NodeKind; parent: TreeNode | null } | null>
  guestCreate: ShallowRef<{ kind: NodeKind; parentId: string } | null>
  moving: ShallowRef<{ node: TreeNode; shown: string | null } | null>
  movingMany: ShallowRef<{ nodes: TreeNode[]; shown: string | null } | null>
  /** What is deleted after one question: one node or a selection - and, `leaving`, the node a page is about. */
  deleting: ShallowRef<{ items: SelectedForDelete[]; shown: string | null; leaving?: TreeNode } | null>
  share: ShallowRef<{ node: TreeNode; offer: NodeShareOffer } | null>
  exporting: ShallowRef<TreeNode | null>
  copying: ShallowRef<{ nodes: TreeNode[]; shown: string | null } | null>
  importing: ShallowRef<ImportOffer | null>
  trash: Ref<boolean>
}

export interface NodeDialogs {
  env: NodeDialogsEnv
  doors: NodeDialogDoors
  state: NodeDialogState
  /** The session's move door (`nodeMover`): none for a link that moves nothing. */
  mover: NodeMover | null
  /** Whether any dialog is open (the page's live refresh pauses meanwhile). */
  open: ComputedRef<boolean>
  /** The content key of the node a create lands under, and of the node being shared (the same object while the same key). */
  createParentKey: ComputedRef<Uint8Array | null>
  shareKey: ComputedRef<Uint8Array | null>
  /** The node on screen with its key, which an import lands under (null at the scope root or while the key is not open). */
  importParent: ComputedRef<{ id: string; key: Uint8Array; epoch: number } | null>
  /** Follow what was on screen after a write (a no-op where the page follows nothing). */
  stayOn(shown: string | null): void
  /** The delete re-read the tree: a page whose node went is left for its parent, one whose node was refused stays. */
  deleted(): void
  /** After the toast's Undo moved the nodes back (the dialog long closed): follow `shown` back, where the page follows. */
  afterUndo(shown: string | null): (() => void) | undefined
}

export function useNodeDialogs(env: NodeDialogsEnv): NodeDialogs {
  const state: NodeDialogState = {
    name: ref(null),
    create: shallowRef(null),
    guestCreate: shallowRef(null),
    moving: shallowRef(null),
    movingMany: shallowRef(null),
    deleting: shallowRef(null),
    share: shallowRef(null),
    exporting: shallowRef(null),
    copying: shallowRef(null),
    importing: shallowRef(null),
    trash: ref(false),
  }
  const shown = () => env.onScreen()
  const stayOn = (s: string | null) => env.stayOn?.(s)
  const keyOf = (id: string | null | undefined) => (id ? (env.tree()?.keyOf(id) ?? null) : null)

  const doors: NodeDialogDoors = {
    rename(node) {
      state.create.value = null
      state.name.value = { mode: 'rename', node, shown: shown() }
    },
    createChild(kind, parent) {
      if (env.access().mode === 'guest') {
        if (parent && env.guestCreate) state.guestCreate.value = { kind, parentId: parent.id }
        return
      }
      // A FOLDER is core's (`FolderNameDialog`), any other kind opens its own `createDialog`.
      state.name.value = kind === FOLDER_KIND ? { mode: 'create', parent } : null
      state.create.value = kind === FOLDER_KIND ? null : { kind, parent }
    },
    move(node) {
      state.moving.value = { node, shown: shown() }
    },
    moveMany(nodes) {
      state.movingMany.value = { nodes: [...nodes], shown: shown() }
    },
    delete(node, remove) {
      const item: SelectedForDelete = { node, ...(remove ? { remove } : {}) }
      // The node a page is about (the folder on screen, an opened node): its Undo keeps the parent - where the member
      // lands - on screen, as a row's keeps the level.
      state.deleting.value = node.id === shown() ? { items: [item], shown: node.parentId, leaving: node } : { items: [item], shown: shown() }
    },
    deleteMany(items) {
      state.deleting.value = { items: [...items], shown: shown() }
    },
    share(node, offer) {
      state.share.value = { node, offer }
    },
    download(node) {
      state.exporting.value = node
    },
    copy(nodes) {
      state.copying.value = { nodes: [...nodes], shown: shown() }
    },
    importArchive(offer) {
      state.importing.value = offer
    },
    openTrash() {
      state.trash.value = true
    },
  }

  const open = computed(
    () =>
      !!state.name.value || !!state.create.value || !!state.guestCreate.value || !!state.moving.value || !!state.movingMany.value ||
      !!state.deleting.value || !!state.share.value || !!state.exporting.value || !!state.copying.value || !!state.importing.value ||
      state.trash.value,
  )
  const createParentKey = computed<Uint8Array | null>((previous) => {
    const parent = state.name.value?.mode === 'create' ? state.name.value.parent : (state.create.value?.parent ?? null)
    return sameKeyOr(previous, keyOf(parent?.id))
  })
  const shareKey = computed<Uint8Array | null>((previous) => sameKeyOr(previous, keyOf(state.share.value?.node.id)))
  const importParent = computed(() => {
    const id = shown()
    const node = id ? (env.tree()?.node(id) ?? null) : null
    const key = keyOf(id)
    return node && key ? { id: node.id, key, epoch: node.epoch } : null
  })

  return {
    env,
    doors,
    state,
    mover: nodeMover(env.access),
    open,
    createParentKey,
    shareKey,
    importParent,
    stayOn,
    deleted() {
      const d = state.deleting.value
      if (!d) return
      if (!d.leaving) stayOn(d.shown)
      else if (env.tree()?.node(d.leaving.id)) stayOn(d.leaving.id)
      else env.leave?.(d.leaving)
    },
    afterUndo: (s) => (env.stayOn ? () => stayOn(s) : undefined),
  }
}
