// Expenses' part of the unified level list (inbox #407, ADR 0079): the ROW DATA of a folder level's Pots, which core draws.
// A Pot's row names its base currency as its one detail (docs/apps/README.md § How an application shows
// facts), read from its sealed meta under the Pot's own key — no total, which would need every Pot's revisions read to
// draw a folder. The actions are `./rights.ts`' answer for THAT Pot: rename and move for a member who may write, nothing
// for a guest; the heart and the download are core's row actions; a selection deletes through core's `deleteNode`.
import type { Ref } from 'vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelRow, LevelRowAction, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../../core/node/levelRow'
import type { TreeNode } from '../../core/node/tree'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { targetOf, type Access } from '../../core/access'
import { potMetaOf } from './domain/potMeta'
import { expensesRights } from './rights'
import { POT_KIND } from './kinds'

const scopeOf = (access: Access): string => (access.mode === 'guest' ? access.head.scopeId : access.scopeId)

export function potRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  const { t } = useI18n()
  const pots = computed(() => ctx.value.nodes.filter((n) => n.kind === POT_KIND))

  /** The base currency, or nothing when the Pot's meta does not open here (the detail is then left out). */
  function currencyOf(node: TreeNode): string | null {
    let key: Uint8Array | null
    try {
      key = ctx.value.tree.keyOf(node.id)
    } catch {
      key = null
    }
    if (!key) return null
    return potMetaOf(unsealNodeMeta(key, { scopeId: scopeOf(ctx.value.access), nodeId: node.id }, node.wrappedMeta))?.base.currency ?? null
  }

  function potRow(node: TreeNode): LevelRow {
    const c = expensesRights(ctx.value.access, targetOf(ctx.value.tree, node))
    const currency = currencyOf(node)
    const actions: LevelRowAction[] = [
      { id: 'rename', label: t('node.rename'), icon: 'pencil', shown: c.renameNode, onClick: () => host.rename(node) },
      { id: 'move', label: t('node.move.action'), icon: 'file-export', shown: c.moveNode, onClick: () => host.move(node) },
    ]
    return {
      icon: 'wallet',
      title: node.name,
      meta: t('expenses.kindPot'),
      details: currency ? [{ id: 'currency', text: currency }] : [],
      primary: { label: t('expenses.open', { name: node.name }), onClick: () => host.open(node) },
      actions,
      selection: { move: c.moveNode, delete: c.deleteNode },
    }
  }

  const rows = computed(() => new Map(pots.value.map((n) => [n.id, potRow(n)] as const)))
  return { row: (node) => rows.value.get(node.id) ?? null }
}
