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
import { nodeReportKeys } from './reportKeys'
import { openReport, type ReportTarget } from './report'

type Translate = (key: string) => string

/** Whether `access` may report what it sees: a member whose keys are open, or a guest who views. */
export function canReport(access: Access): boolean {
  return access.mode === 'member' ? access.epochs !== null : access.role === ShareRole.Read || access.role === ShareRole.ReadWrite
}

/** The space `access` reports in: the member's scope, or the link's. */
export function reportScope(access: Access): string {
  return access.mode === 'guest' ? access.head.scopeId : access.scopeId
}

/** A node as a report target: its keys computed when the report is sent. */
export function nodeTarget(access: Access, tree: TreeSource, node: TreeNode): ReportTarget {
  return { kind: 'NODE', id: node.id, keys: () => nodeReportKeys(access, tree, reportScope(access), node) }
}

/** Open the report dialog for `nodes`, with `enclosing` (the folder on screen) offered above the cap. */
export function reportNodes(access: Access, tree: TreeSource, nodes: readonly TreeNode[], enclosing: TreeNode | null): void {
  openReport({
    access,
    scopeId: reportScope(access),
    targets: nodes.map((n) => nodeTarget(access, tree, n)),
    ...(enclosing ? { enclosing: nodeTarget(access, tree, enclosing) } : {}),
  })
}

function action(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): (CoreRowAction & CoreHeaderAction)[] {
  if (!offered || !node || !canReport(access)) return []
  return [{ id: 'report', label: t('report.action'), icon: 'pi pi-flag', placement: 'after', onClick: onReport }]
}

export function reportHeaderActions(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): CoreHeaderAction[] {
  return action(t, offered, access, node, onReport)
}

export function reportRowActions(t: Translate, offered: boolean, access: Access, node: TreeNode | null, onReport: () => void): CoreRowAction[] {
  return action(t, offered, access, node, onReport)
}
