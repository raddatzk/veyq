// "Report" as a CORE action (uc.moderation.report, ADR 0093 § 1): one more action of a level's selection bar, every row
// and every header, for every kind and the folders, with no change to any application — the shape of
// `export/downloadActions.ts`. Anyone who sees a node can report it: a member whose keys are open, and a guest of a
// `READ` or `READ_WRITE` link (the member view with rights removed, never a guest-only UI); a `WRITE_ONLY` guest sees no
// node and reports the link itself (`views/ShareView.vue`). Shown only while the instance publishes a moderation key
// (`reportOffered`): before an epoch exists there is nothing to report to (ADR 0099 § 3).
import type { Access, TreeSource } from '../access'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'
import type { TreeNode } from '../node/tree'
import { ShareRole } from '../../api/generated/sharing'
import { SHARE_BOOKMARK_KIND } from '../node/kind'
import { nameReportKeys, nodeReportKeys } from './reportKeys'
import { openReport, type ReportTarget } from './report'
import { activeReview } from '../sharing/review'

type Translate = (key: string) => string

/** Whether `access` may report what it sees: a member whose keys are open, or a guest who views. */
export function canReport(access: Access): boolean {
  if (access.mode === 'guest' && activeReview()) return false // a moderator reviewing a case reports nothing
  return access.mode === 'member' ? access.epochs !== null : access.role === ShareRole.Read || access.role === ShareRole.ReadWrite
}

/** The space `access` reports in: the member's scope, or the link's. */
export function reportScope(access: Access): string {
  return access.mode === 'guest' ? access.head.scopeId : access.scopeId
}

/**
 * A node as a report target: its keys computed when the report is sent. A bookmark is reported here by its NAME alone,
 * wherever it is reported from (its row, a selection, above the cap): its key opens another link, perhaps another
 * operator's share, which this instance's moderators are never handed (ADR 0120 decision 6; the server refuses a `NODE`
 * item of one, `report_target_kind`). Its share is reported where the share lives (`core/bookmark/openBookmark.ts`).
 */
export function nodeTarget(access: Access, tree: TreeSource, node: TreeNode): ReportTarget {
  if (node.kind === SHARE_BOOKMARK_KIND) return { kind: 'NAME', id: node.id, keys: () => nameReportKeys(access, tree, reportScope(access), node) }
  return { kind: 'NODE', id: node.id, keys: () => nodeReportKeys(access, tree, reportScope(access), node) }
}

/**
 * A node's name as a report target of its own — offered for one node, never at a guest's link root, whose name is the
 * link's (`nameReportKeys`).
 */
export function nameTarget(access: Access, tree: TreeSource, node: TreeNode): ReportTarget | null {
  if (access.mode === 'guest' && node.id === tree.rootId) return null
  return { kind: 'NAME', id: node.id, keys: () => nameReportKeys(access, tree, reportScope(access), node) }
}

/**
 * Open the report dialog for `nodes`, with `enclosing` (the folder on screen) offered above the cap, and — one node —
 * its name on its own as the other choice.
 */
export function reportNodes(access: Access, tree: TreeSource, nodes: readonly TreeNode[], enclosing: TreeNode | null): void {
  // a bookmark's target already IS its name (`nodeTarget`): no second choice of the same
  const name = nodes.length === 1 && nodes[0]!.kind !== SHARE_BOOKMARK_KIND ? nameTarget(access, tree, nodes[0]!) : null
  openReport({
    access,
    scopeId: reportScope(access),
    targets: nodes.map((n) => nodeTarget(access, tree, n)),
    ...(enclosing ? { enclosing: nodeTarget(access, tree, enclosing) } : {}),
    ...(name ? { nameOnly: name } : {}),
  })
}

function action(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): (CoreRowAction & CoreHeaderAction)[] {
  const shown = offered && !!node && canReport(access)
  // Never a button in a node header (ADR 0110): a report opens a consequential dialog.
  return shown ? [{ id: 'report', label: t('report.action'), icon: 'flag', placement: 'after', menuOnly: true, shown, onClick: onReport }] : []
}

export function reportHeaderActions(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): CoreHeaderAction[] {
  return action(t, offered, access, node, onReport)
}

export function reportRowActions(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): CoreRowAction[] {
  return action(t, offered, access, node, onReport)
}
