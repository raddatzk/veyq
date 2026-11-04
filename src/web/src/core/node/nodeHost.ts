// Core's ONE host contract (owner 2026-11-01): the doors through which whatever draws a node - a kind's `detail`, a row
// factory, core's level view, core's node page - asks the page around it to open a node or one of core's dialogs (the name
// dialog, the move dialog, the delete question with its Undo, the share dialog, a kind's create dialog, the ZIP download,
// the copy, the import, the space's trash). Two hosts build one: the member's space page (`views/SpaceView.vue`) and a share
// guest's page (`core/sharing/ShareTreePage.vue`); the dialogs the doors open are core's ONE `components/NodeDialogs.vue`
// (`nodeDialogs.ts`), which both mount, so each dialog exists once. The two hosts differ only in which doors they hand on.
//
// **A door may be absent - and absence is the answer.** An absent door is an action not offered (fail closed): a guest's
// host has no `rename`, `delete`, `share`, `copy`, `importArchive` or `openTrash`, and a `READ` link's no `move`, so no view
// it renders draws a control that would open nothing - whatever a rights function says. A view narrows its rights answer by
// the doors (`hostRights`) and binds its standard actions through them (`hostHandlers`); core's row defaults
// (`standardRowActions.ts`) and core's own actions (`coreNodeActions.ts`) take the same doors.
//
// **One object, two ways in.** The host view provides it (`provideNodeHost`) and every view below injects it
// (`injectNodeHost`): core's `LevelView`, core's `NodePage` and so every kind's `detail`. A row factory is handed it by the
// level as its `LevelRowsHost` (`levelRow.ts`), the same doors plus what only a level has (`registerAction`, the covers,
// the upload screen). A detail mounted bare, as in a unit test of it, has no host: every door absent.
import { inject, provide, type InjectionKey } from 'vue'
import type { TreeNode } from './tree'
import type { NodeKind } from './kind'
import type { SelectedForDelete } from './selection'
import type { NodeRights } from '../access'
import type { StandardActionHandlers } from './nodeHeader'
import type { ImportOffer } from '../export/importSource'

/** What a `share` door hands the dialog it mounts: the share rights beyond "may share at all" that the application's rights
 *  function answered for the node (`contribute`: offer the READ_WRITE role for a new link, `core.public-shares.contribute`),
 *  or `uploader`: the dialog's uploader (`WRITE_ONLY`) variant, which offers no role. The host passes them on as
 *  `NodeShareDialogProps`, so a dialog never reads a capability itself (src/web/CLAUDE.md: never a `hasCapability` read
 *  inline). */
export interface NodeShareOffer {
  contribute: boolean
  uploader?: boolean
}

/** The page's doors (see the header). Every one may be absent: then the action it would open is not offered. */
export interface NodeHost {
  /** Open a node: its page, or the folder's level. */
  open?(node: TreeNode): void
  /** Rename a node: core's `FolderNameDialog`, whatever its kind (a level row's rename). */
  rename?(node: TreeNode): void
  /** Rename a node to `name` in place (a header's name, ADR 0110 decision 11): the same check and write as the dialog's
   *  (`nodeName.ts`), then the page follows the node to its new path. Rejects with the refusal or the write's error. A host
   *  hands it on together with `rename`, never alone. */
  renameTo?(node: TreeNode, name: string): Promise<void>
  /** Move a node: core's `MoveNodeDialog` (inbox #358). */
  move?(node: TreeNode): void
  /** Move a level's selection: core's `MoveNodesDialog` (inbox #421). */
  moveMany?(nodes: readonly TreeNode[]): void
  /** Delete one node after one question, with its Undo (core's `DeleteNodesDialog`); `remove` is the kind's own delete
   *  write where it has one (`LevelRowSelection.remove`). The node a page is about is left for its parent once it went. */
  delete?(node: TreeNode, remove?: (nodeId: string) => Promise<void>): void
  /** Delete a level's selection after one question, each node with its kind's own write where it has one. */
  deleteMany?(items: readonly SelectedForDelete[]): void
  /** Manage a node's links: its kind's `shareDialog` with this offer. */
  share?(node: TreeNode, offer: NodeShareOffer): void
  /** Create a node of `kind` under `parent` (null: the scope root): core's `FolderNameDialog` for a FOLDER, the kind's
   *  `createDialog` otherwise (a guest's: core's naming of the new node). */
  createChild?(kind: NodeKind, parent: TreeNode | null): void
  /** Download a node and its subtree as a ZIP: core's `ExportSubtreeDialog` (ADR 0082). */
  download?(node: TreeNode): void
  /** Copy nodes elsewhere: core's `CopyNodesDialog` (uc.platform.copy-node). */
  copy?(nodes: readonly TreeNode[]): void
  /** Import a veyq download under the node on screen: core's `ImportSubtreeDialog` (ADR 0082 decision 7). */
  importArchive?(offer: ImportOffer): void
  /** The space's node trash: core's `SpaceTrashDialog`. */
  openTrash?(): void
}

/** A host with no door: what a view mounted outside a host page gets. */
export const NO_DOORS: NodeHost = Object.freeze({})

const nodeHostKey: InjectionKey<NodeHost> = Symbol('veyq.nodeHost')

/** Hand `host` to every view below the calling host page. */
export function provideNodeHost(host: NodeHost): void {
  provide(nodeHostKey, host)
}

/** The doors of the host page around the caller; {@link NO_DOORS} outside one. */
export function injectNodeHost(): NodeHost {
  return inject(nodeHostKey, NO_DOORS)
}

/** A `global.provide` entry for a test that mounts a view as a host page would. */
export function nodeHostProvide(host: NodeHost): Record<symbol, NodeHost> {
  return { [nodeHostKey as symbol]: host }
}

/** `rights` narrowed to the doors `host` has: the share rights by `share`, rename, move and delete by theirs (an absent
 *  door grants nothing, whatever the answer says). Every other field is kept. */
export function hostRights<T extends Partial<NodeRights>>(rights: T, host: NodeHost): T {
  const share = !!host.share
  return {
    ...rights,
    ...('share' in rights ? { share: !!rights.share && share } : {}),
    ...('shareContribute' in rights ? { shareContribute: !!rights.shareContribute && share } : {}),
    ...('shareUpload' in rights ? { shareUpload: !!rights.shareUpload && share } : {}),
    ...('rename' in rights ? { rename: !!rights.rename && !!host.rename } : {}),
    ...('move' in rights ? { move: !!rights.move && !!host.move } : {}),
    ...('delete' in rights ? { delete: !!rights.delete && !!host.delete } : {}),
  }
}

/** The standard actions' handlers of `node` (`standardNodeActions`, `standardNodeRename`) through `host`'s doors: absent
 *  where the door is. `offer` is what the share door hands its dialog. */
export function hostHandlers(host: NodeHost, node: () => TreeNode, offer: () => NodeShareOffer): StandardActionHandlers {
  return {
    ...(host.share ? { share: () => host.share!(node(), offer()) } : {}),
    ...(host.rename ? { rename: () => host.rename!(node()) } : {}),
    ...(host.renameTo ? { renameTo: (name: string) => host.renameTo!(node(), name) } : {}),
    ...(host.move ? { move: () => host.move!(node()) } : {}),
    ...(host.delete ? { remove: () => host.delete!(node()) } : {}),
  }
}
