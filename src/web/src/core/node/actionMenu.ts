// The one mapping from a node action to an entry of a "more" menu (ADR 0110 decision 7), shared by the level row's menu
// (`components/LevelRowItem.vue`) and the node header's ⋯ (`components/NodeHeader.vue`): the two split their actions by
// different rules — a row by its two `first` actions, the header by its measured width — and share the entries alone.
// An action that is not a button keeps its label, its icon and its disabled state; a primary one is a plain entry, a
// danger one carries `is-danger` (styled red in `styles/ui.css`).
import type { VeyqMenuItem } from '../../shared/ui/menu'
import type { NodeHeaderAction } from './nodeHeader'

/** An action as a menu draws it: the header's model plus the row's running state (a download in progress is disabled). */
export type MenuAction = NodeHeaderAction & { loading?: boolean }

/** Each surface's entry classes, spelled whole so the stylesheet guard (`validate_stylesheet_anchors.py`) reads them. */
const HOOKS = {
  'veyq-row': { item: 'veyq-row-menu-item', action: 'veyq-row-action-' },
  'veyq-node-header': { item: 'veyq-node-header-menu-item', action: 'veyq-node-header-action-' },
} as const

/** The menu entries of `groups`, a separator between two non-empty groups. Each entry carries the classes
 *  `<hook>-menu-item <hook>-action-<id>` — the styling and testing hook of the surface it belongs to. */
export function actionMenuItems(groups: readonly (readonly MenuAction[])[], hook: keyof typeof HOOKS): VeyqMenuItem[] {
  const { item, action } = HOOKS[hook]
  const items: VeyqMenuItem[] = []
  for (const group of groups.filter((g) => g.length > 0)) {
    if (items.length) items.push({ separator: true, key: `separator-${items.length}` })
    for (const a of group) {
      items.push({
        key: a.id,
        label: a.label,
        icon: a.icon,
        disabled: a.disabled === true || a.loading === true,
        class: [item, `${action}${a.id}`, a.severity === 'danger' ? 'is-danger' : ''].filter(Boolean).join(' '),
        command: () => a.onClick(),
      })
    }
  }
  return items
}
