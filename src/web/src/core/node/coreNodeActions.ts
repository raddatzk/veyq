// Core's own actions of a node, built in ONE place for every place a node shows its actions (owner 2026-11-01, ADR 0110):
// the favorite heart (inbox #409), the cover (inbox #411; a row's entry - a header's leading visual is its way in), "Copy to…" (uc.platform.copy-node), the ZIP download (ADR 0082)
// and Report (uc.moderation.report, ADR 0093 § 1 - a legal duty, DSA Art. 16: whoever sees a node can report it). Each is
// gated by its own core rights function (`favoriteRights`, `coverRights`, `canCopy`, `canDownload`, `canReport`) and fails
// closed; no application builds, lists or forgets one.
//
// **Who supplies them.** A core view that draws nodes calls {@link useCoreNodeActions} once and {@link provideCoreNodeActions}:
// the level (`LevelView`: every row and the folder on screen) and core's node page (`components/NodePage.vue`, which both
// hosts mount around a kind's `detail`). Core's `NodeHeader` asks the nearest provider for the node it heads
// ({@link useCoreHeaderActions}) and merges the answer with the application's actions - so a kind's page names the node
// its header heads and nothing else, and a page cannot leave Report out. A row gets the same actions through
// {@link CoreNodeActions.row}. The doors (`copy`, `download`) are the host's dialogs; where a host has none, the action
// is not offered - a button that would open nothing is never shown.
import { inject, provide, computed, type ComputedRef, type InjectionKey } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import type { CoreHeaderAction } from './nodeHeader'
import type { CoreRowAction } from './levelRow'
import { useNodeCover, type UseNodeCover } from './cover/useNodeCover'
import { favoriteHeaderActions, favoriteRowActions } from '../favorites/favoriteActions'
import { copyHeaderActions, copyRowActions } from '../export/copyActions'
import { downloadHeaderActions, downloadRowActions } from '../export/downloadActions'
import { reportHeaderActions, reportNodes, reportRowActions } from '../report/reportActions'
import { reportBaseOf, useReportOffered } from '../report/report'
import { useToast } from '../../shared/state/useToast'
import { targetOf } from '../access'
import { SHARE_BOOKMARK_KIND } from './kind'
import { bookmarkRights } from '../bookmark/rights'
import { bookmarkOfNode, openBookmark, useBookmarkDoors } from '../bookmark/openBookmark'

/** The host's dialogs core's actions open; an absent door is an action not offered. */
export interface CoreNodeDoors {
  /** Copy the nodes elsewhere (the host's `CopyNodesDialog`). */
  copy?: (nodes: TreeNode[]) => void
  /** Download the node and its subtree as a ZIP (the host's `ExportSubtreeDialog`). */
  download?: (node: TreeNode) => void
}

/** Core's actions of the nodes one view draws. */
export interface CoreNodeActions {
  /** The view's cover state and dialog (`useNodeCover`): the view mounts `<CoverEditor :cover="cover" />` once. */
  cover: UseNodeCover
  /** The header's core actions for `node` (none for the scope root). */
  header(node: TreeNode | null): CoreHeaderAction[]
  /** A row's core actions for `node`. */
  row(node: TreeNode): CoreRowAction[]
}

/** The injection key of {@link provideCoreNodeActions}; exported for a test that mounts a `NodeHeader` with a stand-in. */
export const coreNodeActionsKey: InjectionKey<CoreNodeActions> = Symbol('veyq.coreNodeActions')

/**
 * Core's actions for the nodes a view draws, for whoever looks. Called in a view's setup (it uses the i18n, toast and
 * cover contexts). `nodes` are those whose covers the view resolves (the folder on screen and its children, or the one
 * node of a page). In merge order a header's are: the heart (before the application's), the copy beside the move (a menu
 * entry, as the move), the download, and Report (menu-only, the menu's last group before delete); a row's add the cover
 * after the heart.
 */
export function useCoreNodeActions(
  access: () => Access,
  tree: () => TreeSource,
  nodes: () => readonly TreeNode[],
  doors: CoreNodeDoors,
): CoreNodeActions {
  const { t } = useI18n()
  const toast = useToast()
  const cover = useNodeCover(access, tree, nodes)
  const reportOn = useReportOffered(reportBaseOf(access()))
  const report = (node: TreeNode) => () => reportNodes(access(), tree(), [node], null)
  const bookmarkDoors = useBookmarkDoors()
  /** A bookmark's Report is its share's (ADR 0120 decision 6): handed to the share page where the share lives, as that
   *  link's guest would report it, offered wherever the bookmark opens - whether or not THIS instance takes reports. Its
   *  name, the one thing of it that is content here, is this instance's to judge by core's rule (`nodeTarget` reports a
   *  bookmark by its name alone): `report-name` beside the share's, or `report` itself where no link reads. */
  function bookmarkReport(node: TreeNode): (CoreRowAction & CoreHeaderAction)[] {
    const a = access()
    const kept = bookmarkRights(a, targetOf(tree(), node)).open ? bookmarkOfNode(a, tree(), node) : null
    const name = reportRowActions(t, reportOn.value, a, node, report(node)).map((r) => ({ ...r, label: t('node.bookmark.reportName') }))
    if (!kept) return name
    return [
      ...name.map((r) => ({ ...r, id: 'report-name' })),
      { id: 'report', label: t('node.bookmark.reportShare'), icon: 'flag', placement: 'after', menuOnly: true, shown: true,
        onClick: () => openBookmark(kept, true, bookmarkDoors) },
    ]
  }
  const copy = (node: TreeNode) => () => doors.copy?.([node])
  const download = (node: TreeNode) => () => doors.download?.(node)
  // The rights functions answer whether an action may be offered; the door whether the host can open it - both must say yes.
  return {
    cover,
    header(node) {
      if (!node) return []
      const a = access()
      // No cover action in a header: its leading visual IS the cover's way in (ADR 0110 decision 11), and the copy is a
      // menu entry, as the move beside it.
      return [
        ...favoriteHeaderActions(t, toast.error, a, node),
        ...(doors.copy ? copyHeaderActions(t, a, node, copy(node)).map((c) => ({ ...c, menuOnly: true })) : []),
        ...(doors.download ? downloadHeaderActions(t, a, node, download(node)) : []),
        ...(node.kind === SHARE_BOOKMARK_KIND ? bookmarkReport(node) : reportHeaderActions(t, reportOn.value, a, node, report(node))),
      ]
    },
    row(node) {
      const a = access()
      return [
        ...favoriteRowActions(t, toast.error, a, node),
        ...cover.rowActions(node),
        ...(doors.copy ? copyRowActions(t, a, node, copy(node)) : []),
        ...(doors.download ? downloadRowActions(t, a, node, download(node)) : []),
        ...(node.kind === SHARE_BOOKMARK_KIND ? bookmarkReport(node) : reportRowActions(t, reportOn.value, a, node, report(node))),
      ]
    },
  }
}

/** Hand `actions` to every `NodeHeader` below the calling view. */
export function provideCoreNodeActions(actions: CoreNodeActions): void {
  provide(coreNodeActionsKey, actions)
}

/** The core actions of the view around the caller (its cover above all: a page's own cover control opens the same dialog
 *  as the header's cover action), or null outside one - a kind's page mounted bare, as in a unit test of it. */
export function injectCoreNodeActions(): CoreNodeActions | null {
  return inject(coreNodeActionsKey, null)
}

/** The core actions of the node a header heads, from the nearest view that provides them; none outside one (a header
 *  mounted on its own, as in a unit test of the header). */
export function useCoreHeaderActions(node: () => TreeNode | null): ComputedRef<CoreHeaderAction[]> {
  const actions = inject(coreNodeActionsKey, null)
  return computed(() => actions?.header(node()) ?? [])
}
